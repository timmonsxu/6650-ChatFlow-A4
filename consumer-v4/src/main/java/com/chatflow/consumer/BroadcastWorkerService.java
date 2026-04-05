package com.chatflow.consumer;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A4 Optimization 1 — In-memory per-room broadcast queue.
 *
 * Problem (A3): SqsConsumerService.processMessage() calls broadcastClient.broadcast()
 * which BLOCKS ~50–200 ms waiting for HTTP responses. The poll thread can only process
 * ~10–20 messages/sec per room, limiting total throughput to ~1,000 msg/s.
 *
 * Solution: One LinkedBlockingQueue<BroadcastTask> and one dedicated worker thread per
 * room (20 total). The SQS poll thread does O(1) offer() and immediately proceeds to
 * deleteMessage. Workers handle the actual HTTP broadcast asynchronously.
 *
 * If a per-room queue is full, the broadcast is dropped (offer returns false) and
 * broadcast.failed is incremented. The SQS message is still deleted — the message
 * is persisted in RDS via DbWriterService, only the real-time push is lost.
 */
@Service
public class BroadcastWorkerService {

    private static final Logger log = LoggerFactory.getLogger(BroadcastWorkerService.class);
    private static final int NUM_ROOMS = 20;

    private final BroadcastClient broadcastClient;

    @Value("${app.broadcast.queue-capacity:5000}")
    private int queueCapacity;

    @Value("${app.broadcast.worker-threads:20}")
    private int workerThreads;

    // One queue per room: index 0 = room "01", index 19 = room "20"
    private List<LinkedBlockingQueue<BroadcastTask>> roomQueues;
    private ExecutorService workerPool;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    // Metrics
    private final AtomicLong totalEnqueued  = new AtomicLong();
    private final AtomicLong totalBroadcast = new AtomicLong();
    private final AtomicLong totalFailed    = new AtomicLong();

    public BroadcastWorkerService(BroadcastClient broadcastClient) {
        this.broadcastClient = broadcastClient;
    }

    @PostConstruct
    public void start() {
        roomQueues = new ArrayList<>(NUM_ROOMS);
        for (int i = 0; i < NUM_ROOMS; i++) {
            roomQueues.add(new LinkedBlockingQueue<>(queueCapacity));
        }

        int threads = Math.min(workerThreads, NUM_ROOMS);
        AtomicLong threadCounter = new AtomicLong();
        workerPool = Executors.newFixedThreadPool(threads,
                r -> new Thread(r, "broadcast-worker-" + threadCounter.getAndIncrement()));

        for (int i = 0; i < threads; i++) {
            final int roomIndex = i;
            workerPool.submit(() -> workerLoop(roomIndex));
        }

        log.info("BroadcastWorkerService started: {} worker threads, queueCapacity={} per room",
                threads, queueCapacity);
    }

    @PreDestroy
    public void stop() {
        shutdown.set(true);
        workerPool.shutdown();
        try {
            workerPool.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("BroadcastWorkerService stopped. enqueued={} broadcast={} failed={}",
                totalEnqueued.get(), totalBroadcast.get(), totalFailed.get());
    }

    /**
     * Called by SqsConsumerService poll thread. Non-blocking (offer, not put).
     * If the room queue is full, the broadcast is dropped and totalFailed is incremented.
     *
     * @param roomId      zero-padded room id, e.g. "05"
     * @param messageJson raw JSON body of the SQS message
     */
    public void enqueue(String roomId, String messageJson) {
        int index = roomIndex(roomId);
        boolean accepted = roomQueues.get(index).offer(new BroadcastTask(roomId, messageJson));
        if (accepted) {
            totalEnqueued.incrementAndGet();
        } else {
            log.warn("BroadcastQueue full for room {}, dropping broadcast", roomId);
            totalFailed.incrementAndGet();
        }
    }

    private void workerLoop(int roomIndex) {
        LinkedBlockingQueue<BroadcastTask> queue = roomQueues.get(roomIndex);
        while (!shutdown.get()) {
            try {
                BroadcastTask task = queue.poll(500, TimeUnit.MILLISECONDS);
                if (task == null) continue;
                try {
                    broadcastClient.broadcast(task.roomId(), task.messageJson());
                    totalBroadcast.incrementAndGet();
                } catch (BroadcastClient.BroadcastException e) {
                    // All servers failed — SQS message is already deleted, log and move on
                    log.error("Broadcast failed for room {}: {}", task.roomId(), e.getMessage());
                    totalFailed.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private int roomIndex(String roomId) {
        try {
            return Integer.parseInt(roomId) - 1;  // "01"→0, "20"→19
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ── Metrics ───────────────────────────────────────────────────────────────

    public long getTotalEnqueued()  { return totalEnqueued.get(); }
    public long getTotalBroadcast() { return totalBroadcast.get(); }
    public long getTotalFailed()    { return totalFailed.get(); }

    public int getTotalQueueDepth() {
        return roomQueues.stream().mapToInt(LinkedBlockingQueue::size).sum();
    }

    // ── Inner record ──────────────────────────────────────────────────────────

    public record BroadcastTask(String roomId, String messageJson) {}
}
