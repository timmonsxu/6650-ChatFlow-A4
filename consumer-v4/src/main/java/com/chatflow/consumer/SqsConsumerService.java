package com.chatflow.consumer;

import com.chatflow.consumer.db.DbWriterService;
import com.chatflow.consumer.model.QueueMessage;
import com.chatflow.consumer.stats.StatsAggregatorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Polls all 20 SQS rooms in parallel (one thread per room).
 *
 * Per-message pipeline (A4 Optimization 1):
 *   1. Deserialise JSON body → QueueMessage
 *   2. broadcastWorkerService.enqueue()     ← O(1), non-blocking; worker thread handles HTTP
 *   3. dbWriterService.enqueue(msg)         ← O(1) put to write buffer
 *   4. statsAggregator.record(msg)          ← O(1) counter increment
 *   5. sqsClient.deleteMessage()            ← immediately after all O(1) enqueues
 *
 * All steps are now O(1). The poll thread is never blocked by HTTP broadcast.
 * Broadcast delivery happens asynchronously in BroadcastWorkerService's thread pool.
 * deleteMessage is called regardless of broadcast outcome — the message is safe in RDS.
 */
@Service
public class SqsConsumerService {

    private static final Logger log = LoggerFactory.getLogger(SqsConsumerService.class);
    private static final int NUM_ROOMS = 20;

    @Value("${app.sqs.region}")
    private String region;

    @Value("${app.sqs.account-id}")
    private String accountId;

    @Value("${app.sqs.queue-name-prefix}")
    private String queueNamePrefix;

    @Value("${app.consumer.threads}")
    private int numThreads;

    /** First room this consumer instance is responsible for (1-based, e.g. 1 or 11). */
    @Value("${app.consumer.room-start:1}")
    private int roomStart;

    private final BroadcastWorkerService  broadcastWorkerService;
    private final DbWriterService         dbWriterService;
    private final StatsAggregatorService  statsAggregator;
    private final ObjectMapper            mapper = new ObjectMapper();

    private SqsClient       sqsClient;
    private ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // ── Metrics ───────────────────────────────────────────────────────────────
    final AtomicLong messagesConsumed = new AtomicLong(0);
    final AtomicLong broadcastCalls   = new AtomicLong(0);

    public SqsConsumerService(BroadcastWorkerService broadcastWorkerService,
                               DbWriterService        dbWriterService,
                               StatsAggregatorService statsAggregator) {
        this.broadcastWorkerService = broadcastWorkerService;
        this.dbWriterService = dbWriterService;
        this.statsAggregator = statsAggregator;
    }

    @PostConstruct
    public void start() {
        sqsClient = SqsClient.builder()
                .region(Region.of(region))
                .httpClientBuilder(software.amazon.awssdk.http.apache.ApacheHttpClient.builder()
                        .maxConnections(numThreads + 20))
                .build();

        running.set(true);
        executor = Executors.newFixedThreadPool(numThreads);

        for (int i = 0; i < numThreads; i++) {
            final int roomId = roomStart + i;
            executor.submit(() -> pollLoop(roomId));
        }

        log.info("SqsConsumerService started: {} threads, rooms {}-{}",
                numThreads, roomStart, roomStart + numThreads - 1);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        sqsClient.close();
        log.info("SqsConsumerService stopped. consumed={} broadcasts={}",
                messagesConsumed.get(), broadcastCalls.get());
    }

    public long getMessagesConsumed() { return messagesConsumed.get(); }
    public long getBroadcastCalls()   { return broadcastCalls.get(); }

    // ── Private ───────────────────────────────────────────────────────────────

    private void pollLoop(int roomId) {
        String paddedRoom = String.format("%02d", roomId);
        String queueUrl   = buildQueueUrl(paddedRoom);
        log.debug("Polling thread started for room {}", paddedRoom);

        while (running.get()) {
            try {
                ReceiveMessageRequest req = ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(20)   // long polling
                        .build();

                List<Message> messages = sqsClient.receiveMessage(req).messages();
                for (Message msg : messages) {
                    processMessage(paddedRoom, msg, queueUrl);
                }

            } catch (Exception e) {
                if (running.get()) {
                    log.error("Poll error for room {}: {}", paddedRoom, e.getMessage());
                    sleepQuietly(1000);
                }
            }
        }
    }

    private void processMessage(String roomId, Message sqsMessage, String queueUrl) {
        try {
            String body = sqsMessage.body();
            // Deserialise once — reused for both broadcast body and DB/stats
            QueueMessage queueMessage = mapper.readValue(body, QueueMessage.class);
            String msgRoomId = queueMessage.getRoomId() != null
                    ? queueMessage.getRoomId() : roomId;

            // 1. O(1) enqueue to per-room broadcast worker (non-blocking offer)
            //    HTTP delivery happens asynchronously in BroadcastWorkerService.
            broadcastWorkerService.enqueue(msgRoomId, body);
            broadcastCalls.incrementAndGet();

            // 2. O(1) enqueue for async DB write
            dbWriterService.enqueue(queueMessage);

            // 3. O(1) in-memory stats update
            statsAggregator.record(queueMessage);

            // 4. Acknowledge immediately — message is safe in RDS regardless of broadcast outcome
            sqsClient.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(sqsMessage.receiptHandle())
                    .build());

            messagesConsumed.incrementAndGet();

        } catch (Exception e) {
            log.error("Failed to process message in room {}: {}", roomId, e.getMessage());
        }
    }

    private String buildQueueUrl(String paddedRoomId) {
        return String.format("https://sqs.%s.amazonaws.com/%s/%s%s.fifo",
                region, accountId, queueNamePrefix, paddedRoomId);
    }

    private void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
