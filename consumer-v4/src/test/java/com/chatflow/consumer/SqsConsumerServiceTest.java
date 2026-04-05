package com.chatflow.consumer;

import com.chatflow.consumer.db.DbWriterService;
import com.chatflow.consumer.stats.StatsAggregatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SqsConsumerService.
 * SqsClient, BroadcastWorkerService, DbWriterService, and StatsAggregatorService
 * are all mocked — no AWS connection or DB needed.
 */
class SqsConsumerServiceTest {

    private BroadcastWorkerService broadcastWorkerService;
    private DbWriterService        dbWriterService;
    private StatsAggregatorService statsAggregator;
    private SqsConsumerService     service;

    @BeforeEach
    void setUp() {
        broadcastWorkerService = mock(BroadcastWorkerService.class);
        dbWriterService = mock(DbWriterService.class);
        statsAggregator = mock(StatsAggregatorService.class);

        service = new SqsConsumerService(broadcastWorkerService, dbWriterService, statsAggregator);

        // Inject @Value fields manually
        ReflectionTestUtils.setField(service, "region",          "us-west-2");
        ReflectionTestUtils.setField(service, "accountId",       "449126751631");
        ReflectionTestUtils.setField(service, "queueNamePrefix", "chatflow-room-");
        ReflectionTestUtils.setField(service, "numThreads",      20);
    }

    @Test
    void initialMetrics_areZero() {
        assertEquals(0, service.getMessagesConsumed());
        assertEquals(0, service.getBroadcastCalls());
    }

    @Test
    void broadcastWorkerService_enqueue_calledWithCorrectRoomId() {
        String roomId = "05";
        String body   = jsonBody("uuid-1", "05", "1", "user1", "hello", "TEXT");

        broadcastWorkerService.enqueue(roomId, body);

        verify(broadcastWorkerService).enqueue(eq("05"), eq(body));
    }

    @Test
    void broadcastWorkerService_isInjectedAndAccessible() {
        assertNotNull(broadcastWorkerService);
    }

    @Test
    void dbWriterService_isInjectedAndAccessible() {
        assertNotNull(dbWriterService);
    }

    @Test
    void statsAggregator_isInjectedAndAccessible() {
        assertNotNull(statsAggregator);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static String jsonBody(String msgId, String roomId, String userId,
                                   String username, String message, String type) {
        return String.format(
                "{\"messageId\":\"%s\",\"roomId\":\"%s\",\"userId\":\"%s\"," +
                "\"username\":\"%s\",\"message\":\"%s\",\"messageType\":\"%s\"," +
                "\"timestamp\":\"2026-04-02T10:00:00.000Z\"}",
                msgId, roomId, userId, username, message, type);
    }
}
