# CS6650 Assignment 3 — Implementation Plan

## Architecture Decisions (Finalized)

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Database | PostgreSQL on RDS (db.t3.micro) | SQL queries are straightforward, `ON CONFLICT DO NOTHING` for idempotency, free tier |
| Connection Pool | HikariCP (Spring Boot default) | Zero extra config, battle-tested |
| DB Writer Queue | `LinkedBlockingQueue` + `put()` | Semantic correctness; fast batch writes ensure it never actually blocks |
| Batch INSERT | Multi-row VALUES (single SQL statement) | 3–5x faster than JDBC `batchUpdate()`; single transaction, single WAL write |
| Broadcast vs DB Write | Parallel via `CompletableFuture` | DB write never slows down broadcast path |
| SQS Delete timing | After broadcast success (same as A2) | Acceptable for academic context; true production would wait for DB write too |
| Metrics API location | server (port 8080) | Server owns data queries; consumer owns data writes |
| Stats Aggregation | In-memory counters (consumer) + DB queries (server) | Real-time stats from counters, historical from DB |

---

## Final Architecture

```
SQS Poll Thread ×20  (existing, unchanged)
        |
        ├──── CompletableFuture ────► BroadcastClient ────► EC2-A :8080
        |                                                ──► EC2-B :8080
        |                                    ↓ on success
        |                              SQS DeleteMessage
        |
        ├──── dbWriteQueue.put() ────► LinkedBlockingQueue<QueueMessage>  (cap: 100_000)
        |                                          ↓
        |                              DbWriterService (×4–6 threads)
        |                                drainTo(batch, BATCH_SIZE)
        |                                multi-row INSERT ... ON CONFLICT DO NOTHING
        |                                          ↓
        |                                 PostgreSQL RDS  (db.t3.micro)
        |
        └──── statsAggregator.record() ──► In-memory counters (ConcurrentHashMap)
                                           Scheduled flush every 1s → StatsSnapshot

Client (after test ends)
    └──► GET /metrics  ──► MetricsController (server)
                            ├── 4 core DB queries
                            └── analytics from DB
```

---

## Repository Structure

```
6650-ChatFlow-A3/
├── server-v2/              # Unchanged from A2 (WebSocket + broadcast endpoint)
├── server-v3/              # NEW: server-v2 + MetricsController + DB query layer
├── consumer-v2/            # Unchanged from A2 (reference)
├── consumer-v3/            # NEW: consumer-v2 + DB write pipeline
├── client-v2/              # Mostly unchanged; add metrics API call at end
├── database/               # NEW: schema + setup scripts
│   ├── schema.sql
│   ├── indexes.sql
│   └── setup.sh
├── load-tests/             # NEW: test configs + results
│   ├── batch-tuning/       # results for 100/500/1000/5000 batch sizes
│   ├── test1-baseline/     # 500K messages
│   ├── test2-stress/       # 1M messages
│   └── test3-endurance/    # 30-min sustained
├── monitoring/             # Existing + new DB metrics scripts
├── results/                # Existing
├── plan.md                 # This file
├── review.md               # Debug log
└── PROJECT_CONTEXT.md
```

---

## Step-by-Step Implementation

---

### Phase 0: Infrastructure Setup (Day 1)

#### Step 0.1 — Create RDS PostgreSQL Instance

1. AWS Console → RDS → Create database
   - Engine: PostgreSQL 15.x
   - Template: Free tier
   - Instance class: `db.t3.micro`
   - Storage: 20 GB gp2
   - DB name: `chatflow`
   - Username: `chatflow_user`
   - Password: (store securely, use env var or AWS Secrets Manager)
   - VPC: same as EC2 instances (us-west-2)
   - Public access: No (private VPC only)
   - Security group: new SG `chatflow-rds-sg`

2. Configure Security Groups
   - `chatflow-rds-sg`: inbound TCP 5432 from `chatflow-ec2-sg` only
   - EC2 security group: no changes needed (already allows outbound)

3. Note the RDS endpoint (e.g., `chatflow.xxxxxx.us-west-2.rds.amazonaws.com`)

#### Step 0.2 — Create Database Schema

Create `database/schema.sql`:

```sql
-- Main messages table
CREATE TABLE IF NOT EXISTS messages (
    id           BIGSERIAL    PRIMARY KEY,
    message_id   VARCHAR(36)  NOT NULL,           -- UUID, idempotency key
    room_id      SMALLINT     NOT NULL,            -- 1–20
    user_id      INT          NOT NULL,            -- 1–100000
    username     VARCHAR(20)  NOT NULL,
    message      TEXT         NOT NULL,
    message_type VARCHAR(8)   NOT NULL,            -- TEXT / JOIN / LEAVE
    server_id    VARCHAR(50),
    sent_at      BIGINT       NOT NULL,            -- epoch millis (client timestamp)
    created_at   TIMESTAMPTZ  DEFAULT NOW(),
    CONSTRAINT uq_message_id UNIQUE (message_id)
);
```

Create `database/indexes.sql`:

```sql
-- Support Q1: messages in room + time range
CREATE INDEX IF NOT EXISTS idx_room_time
    ON messages (room_id, sent_at);

-- Support Q2: user message history
CREATE INDEX IF NOT EXISTS idx_user_time
    ON messages (user_id, sent_at);

-- Support Q3: active users in time window
CREATE INDEX IF NOT EXISTS idx_time
    ON messages (sent_at);

-- Support Q4: rooms per user (covered by idx_user_time)
-- No additional index needed
```

Create `database/setup.sh`:

```bash
#!/bin/bash
psql -h $RDS_HOST -U chatflow_user -d chatflow -f schema.sql
psql -h $RDS_HOST -U chatflow_user -d chatflow -f indexes.sql
echo "Schema setup complete."
```

#### Step 0.3 — Verify Connectivity from EC2

```bash
# On EC2, test connection
psql -h <rds-endpoint> -U chatflow_user -d chatflow -c "\dt"
```

---

### Phase 1: Consumer-v3 (Day 2–3)

Copy `consumer-v2/` to `consumer-v3/`. All new code goes here.

#### Step 1.1 — Add Dependencies (`pom.xml`)

```xml
<!-- PostgreSQL driver -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>

<!-- Spring JDBC (for NamedParameterJdbcTemplate) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
```

#### Step 1.2 — Configuration (`application.properties`)

```properties
server.port=8081

# SQS (unchanged from A2)
app.sqs.region=us-west-2
app.sqs.account-id=449126751631
app.consumer.threads=20
app.broadcast.server-urls=http://172.31.25.72:8080,http://172.31.24.104:8080

# PostgreSQL / HikariCP
spring.datasource.url=jdbc:postgresql://${RDS_HOST}:5432/chatflow
spring.datasource.username=${RDS_USER}
spring.datasource.password=${RDS_PASSWORD}
spring.datasource.driver-class-name=org.postgresql.Driver
spring.datasource.hikari.maximum-pool-size=10
spring.datasource.hikari.minimum-idle=2
spring.datasource.hikari.connection-timeout=3000
spring.datasource.hikari.idle-timeout=600000
spring.datasource.hikari.max-lifetime=1800000

# DB Write tuning (adjust per experiment)
app.db.batch-size=500
app.db.flush-interval-ms=500
app.db.writer-threads=5
app.db.queue-capacity=100000
```

Use env vars for secrets: `RDS_HOST`, `RDS_USER`, `RDS_PASSWORD` — set in EC2 startup script, never commit to git.

#### Step 1.3 — `MessageRepository.java`

```
package com.chatflow.consumer.db;

Responsibility: Execute multi-row batch INSERT to PostgreSQL

Key method:
  void batchInsert(List<QueueMessage> messages)
    - Build single SQL: INSERT INTO messages (...) VALUES (?,?,...),(?,?,...) ... ON CONFLICT (message_id) DO NOTHING
    - Use JdbcTemplate.update(sql, params[])
    - Track: insertedCount (AtomicLong), batchCount (AtomicLong), totalInsertLatencyMs (AtomicLong)

Multi-row VALUES construction:
  StringBuilder sql = new StringBuilder("INSERT INTO messages (...) VALUES ");
  List<Object> params = new ArrayList<>();
  for each message:
      sql.append("(?,?,?,?,?,?,?,?),")
      params.add(message.getMessageId(), roomId, userId, username, message, messageType, serverId, sentAt)
  sql.deleteCharAt(last comma)
  sql.append(" ON CONFLICT (message_id) DO NOTHING")
  jdbcTemplate.update(sql.toString(), params.toArray())
```

#### Step 1.4 — `DbWriterService.java`

```
package com.chatflow.consumer.db;

Responsibility: Drain the write queue in batches and call MessageRepository

Fields:
  LinkedBlockingQueue<QueueMessage> dbWriteQueue   (shared with SqsConsumerService)
  ExecutorService writerPool                        (fixed, app.db.writer-threads)
  MessageRepository repository
  int batchSize                                     (from config)
  long flushIntervalMs                              (from config)

Lifecycle:
  @PostConstruct start()
    → submit writerPool × writerThreads of WriterTask

WriterTask (inner Runnable):
  while (!shutdown):
    1. QueueMessage first = dbWriteQueue.poll(flushIntervalMs, MILLISECONDS)
       // Wait up to flushIntervalMs for at least one message
    2. if first == null: continue  // timeout, nothing to write
    3. List<QueueMessage> batch = new ArrayList<>(batchSize)
       batch.add(first)
       dbWriteQueue.drainTo(batch, batchSize - 1)
       // drainTo is non-blocking, takes whatever is available up to limit
    4. try:
         long t0 = System.currentTimeMillis()
         repository.batchInsert(batch)
         recordLatency(System.currentTimeMillis() - t0, batch.size())
       catch (Exception e):
         retryQueue.addAll(batch)  // dead letter handling (Step 1.5)

Metrics exposed:
  long getTotalInserted()
  long getTotalBatches()
  double getAvgBatchSize()
  long getP99LatencyMs()
```

#### Step 1.5 — Dead Letter Queue & Retry

```
In DbWriterService:
  LinkedBlockingQueue<QueueMessage> retryQueue (cap: 10_000)
  ScheduledExecutorService retryScheduler

  @PostConstruct: retryScheduler.scheduleAtFixedRate(retryTask, 5, 5, SECONDS)

  retryTask:
    List<QueueMessage> retryBatch = new ArrayList<>(100)
    retryQueue.drainTo(retryBatch, 100)
    if not empty: repository.batchInsert(retryBatch)  // with exponential backoff
```

#### Step 1.6 — `StatsAggregatorService.java`

```
package com.chatflow.consumer.stats;

Responsibility: Real-time in-memory statistics (separate thread pool as required)

Fields:
  ConcurrentHashMap<Integer, LongAdder> roomMessageCounts    // roomId → count
  ConcurrentHashMap<Integer, LongAdder> userMessageCounts    // userId → count
  LongAdder totalMessages
  ConcurrentLinkedDeque<Long> recentTimestamps               // for msg/sec calculation (last 60s)
  ScheduledExecutorService aggregatorPool                     (1 thread, dedicated)

  @PostConstruct: aggregatorPool.scheduleAtFixedRate(pruneOldTimestamps, 1, 1, SECONDS)

  void record(QueueMessage msg):
    roomMessageCounts.computeIfAbsent(roomId, k -> new LongAdder()).increment()
    userMessageCounts.computeIfAbsent(userId, k -> new LongAdder()).increment()
    totalMessages.increment()
    recentTimestamps.addLast(System.currentTimeMillis())

  StatsSnapshot getSnapshot():
    topNRooms(10), topNUsers(10), messagesPerSecond (last 1s, 10s, 60s)
```

#### Step 1.7 — Modify `SqsConsumerService.java`

Changes from A2:
1. Inject `DbWriterService` and `StatsAggregatorService`
2. After SQS poll, for each message:
   ```
   // Fire broadcast (unchanged from A2)
   CompletableFuture<Void> broadcastFuture =
       CompletableFuture.runAsync(() -> broadcastClient.broadcast(roomId, msg), broadcastExecutor);

   // Put to DB write queue (blocking, but in practice never blocks)
   dbWriterService.getQueue().put(msg);

   // Record stats (non-blocking)
   statsAggregator.record(msg);

   // Wait for broadcast then delete from SQS (unchanged from A2)
   broadcastFuture.thenRun(() -> sqsClient.deleteMessage(...));
   ```

#### Step 1.8 — Update `HealthController.java`

Add to health response:
- `dbWritten`: total messages inserted to DB
- `dbBatches`: total batch INSERT calls
- `avgBatchSize`: average batch size
- `dbQueueDepth`: current dbWriteQueue size
- `retryQueueDepth`: current retryQueue size
- `statsSnapshot`: top rooms, top users, msg/sec

---

### Phase 2: Server-v3 (Day 4)

Copy `server-v2/` to `server-v3/`. Add DB query layer + Metrics API.

#### Step 2.1 — Add Dependencies

Same as consumer-v3: `postgresql` + `spring-boot-starter-jdbc`

#### Step 2.2 — Configuration

```properties
# Same as server-v2 PLUS:
spring.datasource.url=jdbc:postgresql://${RDS_HOST}:5432/chatflow
spring.datasource.username=${RDS_USER}
spring.datasource.password=${RDS_PASSWORD}
spring.datasource.hikari.maximum-pool-size=5
spring.datasource.hikari.minimum-idle=1
```

Server only reads DB (for metrics queries), so pool size can be small.

#### Step 2.3 — `MessageQueryService.java`

```
package com.chatflow.server.metrics;

Responsibility: Execute the 4 core queries + 4 analytics queries

Core queries (use NamedParameterJdbcTemplate for readability):

  Q1: List<MessageDto> getMessagesInRoom(int roomId, long startTime, long endTime)
    SELECT message_id, room_id, user_id, username, message, message_type, sent_at
    FROM messages
    WHERE room_id = :roomId AND sent_at BETWEEN :start AND :end
    ORDER BY sent_at
    LIMIT 1000

  Q2: List<MessageDto> getUserHistory(int userId, long startTime, long endTime)
    SELECT message_id, room_id, user_id, username, message, message_type, sent_at
    FROM messages
    WHERE user_id = :userId AND sent_at BETWEEN :start AND :end
    ORDER BY sent_at
    LIMIT 1000

  Q3: int countActiveUsers(long startTime, long endTime)
    SELECT COUNT(DISTINCT user_id)
    FROM messages
    WHERE sent_at BETWEEN :start AND :end

  Q4: List<RoomActivityDto> getUserRooms(int userId)
    SELECT room_id, MAX(sent_at) AS last_active
    FROM messages
    WHERE user_id = :userId
    GROUP BY room_id
    ORDER BY last_active DESC

Analytics queries:

  A1: List<MsgRateDto> getMessagesPerMinute(long startTime, long endTime)
    SELECT
      (sent_at / 60000) * 60000 AS minute_bucket,
      COUNT(*) AS msg_count
    FROM messages
    WHERE sent_at BETWEEN :start AND :end
    GROUP BY minute_bucket
    ORDER BY minute_bucket

  A2: List<UserRankDto> getTopActiveUsers(int n)
    SELECT user_id, username, COUNT(*) AS msg_count
    FROM messages
    GROUP BY user_id, username
    ORDER BY msg_count DESC
    LIMIT :n

  A3: List<RoomRankDto> getTopActiveRooms(int n)
    SELECT room_id, COUNT(*) AS msg_count
    FROM messages
    GROUP BY room_id
    ORDER BY msg_count DESC
    LIMIT :n

  A4: long getTotalMessages()
    SELECT COUNT(*) FROM messages
```

#### Step 2.4 — `MetricsController.java`

```
package com.chatflow.server.controller;

@RestController
@RequestMapping("/metrics")

GET /metrics
  - Parameters: roomId (default 1), userId (default 1), startTime, endTime, topN (default 10)
  - If startTime/endTime not provided, default to last 1 hour
  - Calls all 8 queries in parallel via CompletableFuture
  - Returns single JSON:
    {
      "testSummary": {
        "totalMessages": 500000,
        "queryTimeMs": 234
      },
      "coreQueries": {
        "roomMessages": { "roomId": 1, "timeRange": "...", "count": ..., "messages": [...] },
        "userHistory":  { "userId": 1, "count": ..., "messages": [...] },
        "activeUsers":  { "timeRange": "...", "uniqueUsers": ... },
        "userRooms":    { "userId": 1, "rooms": [...] }
      },
      "analytics": {
        "messagesPerMinute":  [...],
        "topActiveUsers":     [...],
        "topActiveRooms":     [...],
        "totalMessageCount":  ...
      }
    }

Error handling:
  - Catch DataAccessException, return 503 with error message
  - Log query times for each sub-query
```

---

### Phase 3: Client-v2 Updates (Day 4)

Minor changes only — do NOT create client-v3, just update client-v2.

#### Step 3.1 — Add Metrics API Call

In `ChatClient.java`, after main phase completes:

```java
// Wait 5 seconds for DB writes to flush before querying
Thread.sleep(5000);

// Call metrics API
String metricsUrl = "http://<ALB-DNS>/metrics?startTime=" + testStartTime + "&endTime=" + testEndTime;
HttpClient httpClient = HttpClient.newHttpClient();
HttpRequest request = HttpRequest.newBuilder().uri(URI.create(metricsUrl)).GET().build();
HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

logger.info("=== METRICS API RESPONSE ===");
logger.info(response.body());
// Pretty-print JSON: use Jackson ObjectMapper for formatted output
logger.info("=== END METRICS ===");
```

#### Step 3.2 — Make Message Count Configurable

In `MessageGenerator.java`, read from system property or env:
```java
int totalMessages = Integer.parseInt(System.getProperty("app.total-messages", "500000"));
```

Run with `-Dapp.total-messages=1000000` for stress test.

---

### Phase 4: Batch Size Tuning Experiments (Day 5)

Run 4–5 experiments by changing only `app.db.batch-size` and `app.db.flush-interval-ms` in consumer-v3.

| Experiment | batch-size | flush-interval-ms | Expected trade-off |
|------------|-----------|-------------------|-------------------|
| E1 | 100 | 100ms | Low latency, high overhead |
| E2 | 500 | 500ms | Balanced |
| E3 | 1000 | 500ms | Higher throughput |
| E4 | 5000 | 1000ms | Max batch efficiency, higher latency |
| E5 | 1000 | 100ms | (optional) tuned sweet spot |

For each experiment, record from consumer `/health`:
- `avgBatchSize` (actual vs configured)
- `dbBatches` count
- `dbWritten` count
- `dbQueueDepth` at end
- Total DB write time (from start to all messages written)

**Expected winner**: E3 (1000 / 500ms) — 1000-row batch keeps transaction overhead low without excessive latency; 500ms flush ensures queue doesn't accumulate during bursty periods.

Store results in `load-tests/batch-tuning/results.md`.

---

### Phase 5: Load Tests (Day 6–7)

All tests use the optimal batch size from Phase 4.

#### Test 1: Baseline (500K messages)

Same as A2 run but now with DB persistence.

```bash
# EC2-A: run consumer-v3 + server-v3
java -jar consumer-v3.jar
java -jar server-v3.jar

# EC2-B: run server-v3
java -jar server-v3.jar

# Local: run client
java -Dapp.total-messages=500000 -jar client-v2.jar
```

Metrics to collect:
- Throughput (msg/sec) — from client MetricsCollector
- DB write throughput — from consumer /health (dbWritten / test duration)
- Write latency p50/p95/p99 — from consumer /health
- Queue depth stability — sample dbWriteQueue.size() every 5s
- RDS CPU/memory — from AWS CloudWatch

#### Test 2: Stress Test (1M messages)

```bash
java -Dapp.total-messages=1000000 -jar client-v2.jar
```

Watch for:
- DB write queue growing unbounded (would indicate DB is bottleneck)
- Connection pool exhaustion (HikariCP timeout exceptions)
- RDS CPU > 80%

#### Test 3: Endurance Test (30 minutes sustained)

Modify client to send at 80% of max throughput for 30 minutes.
Estimated: if max is 5,100 msg/s, target ~4,000 msg/s sustained.

```java
// In SenderThread, add rate limiter
// RateLimiter rateLimiter = RateLimiter.create(targetRate / numThreads);
```

Monitor every 5 minutes:
- DB queue depth (should be stable, not growing)
- JVM heap usage (check for leaks)
- RDS active connections (should be stable ≤ pool size)
- Message write count vs expected (should match)

Store all results in `load-tests/test1-baseline/`, `test2-stress/`, `test3-endurance/`.

---

### Phase 6: Documentation (Day 7–8)

#### `database/DESIGN.md` (2 pages max, required for grading)

Sections:
1. Database Choice: PostgreSQL vs alternatives (DynamoDB, MySQL) — explain SQL simplicity, `ON CONFLICT DO NOTHING`, free tier
2. Schema Design: table DDL, field rationale, data types
3. Indexing Strategy: each index, which query it serves, estimated selectivity
4. Scaling: read replicas for analytics, partitioning by sent_at for large datasets
5. Backup: RDS automated backups (7-day retention), point-in-time recovery

#### Performance Report

Located in `load-tests/REPORT.md`:
- Batch tuning results table with analysis
- Test 1/2/3 results with graphs (CloudWatch screenshots)
- Bottleneck analysis: where did time go? (SQS? Broadcast? DB write?)
- Trade-offs made (e.g., SQS delete before DB write)

---

## Configuration Quick Reference

### consumer-v3 tunable parameters

```properties
app.db.batch-size=1000            # Messages per INSERT
app.db.flush-interval-ms=500      # Max wait before flush (even if batch not full)
app.db.writer-threads=5           # DB writer thread count
app.db.queue-capacity=100000      # In-memory write buffer size
app.db.retry-max-attempts=3       # Retry attempts for failed writes
app.db.retry-backoff-ms=1000      # Base backoff for retry
```

### server-v3 tunable parameters

```properties
spring.datasource.hikari.maximum-pool-size=5   # Read-only queries, keep small
```

---

## Deployment Checklist

```
[ ] RDS instance created, endpoint noted
[ ] Security groups: EC2 → RDS port 5432 open
[ ] Schema applied: psql -f schema.sql && psql -f indexes.sql
[ ] ENV vars set on EC2: RDS_HOST, RDS_USER, RDS_PASSWORD
[ ] consumer-v3.jar built: mvn clean package -pl consumer-v3
[ ] server-v3.jar built: mvn clean package -pl server-v3
[ ] EC2-A: consumer-v3 (port 8081) + server-v3 (port 8080) running
[ ] EC2-B: server-v3 (port 8080) running
[ ] Health checks pass: curl /health on both consumers and servers
[ ] Test DB write: check SELECT COUNT(*) FROM messages after small test
[ ] ALB health checks green for server-v3
[ ] Batch tuning experiments completed, optimal config selected
[ ] Test 1 (500K): complete, metrics logged
[ ] Test 2 (1M): complete, metrics logged
[ ] Test 3 (endurance 30min): complete, metrics logged
[ ] Metrics API verified: GET /metrics returns valid JSON
[ ] Client logs metrics API response (screenshot ready)
[ ] database/DESIGN.md written
[ ] load-tests/REPORT.md written
```

---

## Grading Alignment

| Rubric Item | Points | Covered By |
|-------------|--------|-----------|
| Schema design appropriateness | 4 | `database/schema.sql` + `DESIGN.md` |
| Query optimization | 3 | Indexes + prepared statements + multi-row VALUES |
| Error handling | 3 | Dead letter queue + retry + `ON CONFLICT DO NOTHING` |
| Clarity and completeness | 3 | `DESIGN.md` |
| Design justification | 2 | PostgreSQL choice rationale in `DESIGN.md` |
| Metrics API & Results Log | 5 | `MetricsController` + client screenshot |
| Sustained write throughput | 7 | Test 1/2 results |
| System stability under load | 4 | Test 3 results |
| Resource efficiency | 4 | Batch tuning analysis |
| **Total** | **35** | |

Bonus opportunities:
- Exceptional performance (+2): target > 5,000 msg/s with DB write (vs 5,100 msg/s A2 without)
- Innovative optimization (+1): materialized view for analytics, or query result caching
- Monitoring dashboard (+1): CloudWatch dashboard with DB + SQS + EC2 metrics in one view

---

---

# A4 Implementation Plan

> **Context for new session**: This repo is being copied as the A4 base (rename folder to 6650-ChatFlow-A4).
> A3 is COMPLETE. All code in consumer-v3/, server-v3/, client-v3/, database/ is working and tested.
> A4 requires 2 optimizations + JMeter measurement. Both optimizations are in consumer-v3 only.
> The A3 baseline throughput is ~1,008 msg/s (Test 1, 500K messages).

## A4 Infrastructure (3 EC2 t3.micro + 1 RDS db.t3.micro, all existing from A3)

| Instance | Private IP | Role |
|----------|-----------|------|
| EC2-S1 | 172.31.25.72 | server-v3 :8080 |
| EC2-S2 | 172.31.24.104 | server-v3 :8080 |
| EC2-Consumer | 35.92.149.159 (public) | consumer-v3 :8081 |
| RDS | see $RDS_HOST env var | PostgreSQL 17, chatflow DB |
| ALB | 6650A2-476604144.us-west-2.elb.amazonaws.com | routes clients → EC2-S1/S2 |

---

## Optimization 1: In-Memory Per-Room Broadcast Queue

### What and Why

**Problem**: In `SqsConsumerService.processMessage()`, the call to `broadcastClient.broadcast()` is BLOCKING.
It uses `CompletableFuture.sendAsync()` internally but then waits for all HTTP responses before returning.
At ~50–200ms per broadcast, the poll thread can only process ~5–20 messages/second per room.

**Solution**: Add a `BroadcastWorkerService` with 20 `LinkedBlockingQueue<BroadcastTask>` (one per room)
and 20 dedicated broadcast worker threads. The poll thread does O(1) enqueue and immediately deletes
from SQS. The broadcast worker thread handles the actual HTTP call asynchronously.

**DeleteMessage timing change**: Was "after broadcast succeeds". Now "after enqueue to broadcastQueue".
This means if EC2-Consumer crashes with items in broadcastQueue, those messages are lost from broadcast
but already in RDS. Acceptable for this system (consistent with existing design where DB write is async).

**Expected result**: poll thread drops from ~100ms/message to ~1ms/message → throughput 3,000–5,000 msg/s.

### Files to Modify

1. **NEW**: `consumer-v3/src/main/java/com/chatflow/consumer/BroadcastWorkerService.java`
2. **MODIFY**: `consumer-v3/src/main/java/com/chatflow/consumer/SqsConsumerService.java`
3. **MODIFY**: `consumer-v3/src/main/java/com/chatflow/consumer/HealthController.java`
4. **MODIFY**: `consumer-v3/src/main/resources/application.properties`

### Step 1: Create BroadcastWorkerService.java

Create file at: `consumer-v3/src/main/java/com/chatflow/consumer/BroadcastWorkerService.java`

```java
package com.chatflow.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@Service
public class BroadcastWorkerService {

    private static final Logger log = LoggerFactory.getLogger(BroadcastWorkerService.class);

    private final BroadcastClient broadcastClient;

    @Value("${app.broadcast.queue-capacity:10000}")
    private int queueCapacity;

    @Value("${app.broadcast.worker-threads:20}")
    private int workerThreads;

    // One queue per room (index 0 = room 1, index 19 = room 20)
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
        roomQueues = new ArrayList<>(workerThreads);
        for (int i = 0; i < workerThreads; i++) {
            roomQueues.add(new LinkedBlockingQueue<>(queueCapacity));
        }
        workerPool = Executors.newFixedThreadPool(workerThreads,
                r -> new Thread(r, "broadcast-worker-" + r.hashCode()));
        for (int i = 0; i < workerThreads; i++) {
            final int roomIndex = i;
            workerPool.submit(() -> workerLoop(roomIndex));
        }
        log.info("BroadcastWorkerService started. rooms={} queueCapacity={}", workerThreads, queueCapacity);
    }

    @PreDestroy
    public void stop() {
        shutdown.set(true);
        workerPool.shutdown();
        try { workerPool.awaitTermination(10, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        log.info("BroadcastWorkerService stopped. enqueued={} broadcast={} failed={}",
                totalEnqueued.get(), totalBroadcast.get(), totalFailed.get());
    }

    /**
     * Called by SqsConsumerService poll thread. O(1) — never blocks (offer, not put).
     * roomId is the zero-padded string like "01"–"20".
     */
    public void enqueue(String roomId, String messageJson) {
        int index = parseRoomIndex(roomId);  // "01" → 0, "20" → 19
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
                    // All servers failed — message already deleted from SQS, log and move on
                    log.error("Broadcast failed for room {}: {}", task.roomId(), e.getMessage());
                    totalFailed.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private int parseRoomIndex(String roomId) {
        try {
            return Integer.parseInt(roomId) - 1;  // "01"→0, "20"→19
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ── Metrics getters ────────────────────────────────────────────
    public long getTotalEnqueued()  { return totalEnqueued.get(); }
    public long getTotalBroadcast() { return totalBroadcast.get(); }
    public long getTotalFailed()    { return totalFailed.get(); }

    public int getTotalQueueDepth() {
        return roomQueues.stream().mapToInt(LinkedBlockingQueue::size).sum();
    }

    // ── Inner record ───────────────────────────────────────────────
    public record BroadcastTask(String roomId, String messageJson) {}
}
```

### Step 2: Modify SqsConsumerService.java

**Current `processMessage()` method** (exact code, do not change anything else):
```java
private void processMessage(String roomId, Message sqsMessage, String queueUrl) {
    try {
        String body = sqsMessage.body();
        QueueMessage queueMessage = mapper.readValue(body, QueueMessage.class);
        String msgRoomId = queueMessage.getRoomId() != null ? queueMessage.getRoomId() : roomId;
        broadcastClient.broadcast(msgRoomId, body);  // ← REMOVE THIS LINE
        broadcastCalls.incrementAndGet();
        dbWriterService.enqueue(queueMessage);
        statsAggregator.record(queueMessage);
        sqsClient.deleteMessage(...);
        messagesConsumed.incrementAndGet();
    } catch (BroadcastClient.BroadcastException e) {
        log.error("Broadcast failed for room {}, message will be retried: {}", roomId, e.getMessage());
    } catch (Exception e) {
        log.error("Failed to process message in room {}: {}", roomId, e.getMessage());
    }
}
```

**Changes needed:**

a) Add constructor parameter `BroadcastWorkerService broadcastWorkerService` and store as field.

b) Replace `broadcastClient.broadcast(msgRoomId, body)` with `broadcastWorkerService.enqueue(msgRoomId, body)`.

c) Remove the `catch (BroadcastClient.BroadcastException e)` block (BroadcastException is now thrown inside the worker thread, not here).

d) Move `deleteMessage` to be called BEFORE the catch block, right after `statsAggregator.record()`.
   (deleteMessage is now called immediately after all O(1) enqueues, not waiting for broadcast).

**After change, processMessage should be:**
```java
private void processMessage(String roomId, Message sqsMessage, String queueUrl) {
    try {
        String body = sqsMessage.body();
        QueueMessage queueMessage = mapper.readValue(body, QueueMessage.class);
        String msgRoomId = queueMessage.getRoomId() != null ? queueMessage.getRoomId() : roomId;
        broadcastWorkerService.enqueue(msgRoomId, body);   // O(1), non-blocking
        broadcastCalls.incrementAndGet();
        dbWriterService.enqueue(queueMessage);              // O(1), non-blocking
        statsAggregator.record(queueMessage);               // O(1), non-blocking
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(sqsMessage.receiptHandle())
                .build());
        messagesConsumed.incrementAndGet();
    } catch (Exception e) {
        log.error("Failed to process message in room {}: {}", roomId, e.getMessage());
    }
}
```

### Step 3: Modify HealthController.java

a) Add constructor parameter `BroadcastWorkerService broadcastWorkerService` and store as field.

b) In the `health()` method, add a new `"broadcast"` section to the returned map:

```java
Map<String, Object> broadcast = new LinkedHashMap<>();
broadcast.put("enqueued",   broadcastWorkerService.getTotalEnqueued());
broadcast.put("sent",       broadcastWorkerService.getTotalBroadcast());
broadcast.put("failed",     broadcastWorkerService.getTotalFailed());
broadcast.put("queueDepth", broadcastWorkerService.getTotalQueueDepth());
result.put("broadcast", broadcast);
```

### Step 4: Add config to application.properties

```properties
# ── Broadcast Worker Queue ─────────────────────────────────────────
# Per-room in-memory queue capacity (20 queues total)
app.broadcast.queue-capacity=10000
# Number of broadcast worker threads (must equal number of rooms)
app.broadcast.worker-threads=20
```

### Step 5: Build and deploy

```bash
# Build
cd consumer-v3
mvn clean package -DskipTests -q
cp target/consumer-v3-*.jar target/consumer-v3.jar

# Deploy to EC2-Consumer
scp -i ~/.ssh/6650-Timmons-Project.pem target/consumer-v3.jar \
    ec2-user@35.92.149.159:~/consumer-v3/

# Restart on EC2-Consumer
pkill -f consumer-v3 || true; sleep 3
export RDS_HOST=<endpoint> RDS_USER=chatflow_user RDS_PASS=<pass>
nohup java -jar consumer-v3/consumer-v3.jar \
  --spring.config.location=consumer-v3/application.properties \
  > consumer-v3/consumer.log 2>&1 &

# Verify new broadcast section in /health
curl -s http://localhost:8081/health | python3 -m json.tool
# Should see: "broadcast": {"enqueued": 0, "sent": 0, "failed": 0, "queueDepth": 0}
```

### Step 6: Measure before/after

**Before (A3 baseline already recorded):** ~1,008 msg/s

**After measurement:**
```bash
# Run same 500K test
java -Dapp.total-messages=500000 -jar client-v3/client-v3.jar

# After test, check /health for:
# - sqs.consumed / test_duration_seconds = new msg/s rate
# - broadcast.failed should be 0
# - broadcast.queueDepth should be near 0 (workers keeping up)
```

---

## Optimization 2: Consumer Horizontal Scaling (Room Partitioning)

### What and Why

**Problem**: 1 EC2-Consumer handles all 20 rooms. After Opt 1, throughput ceiling moves to
SQS FIFO API rate (~300 TPS/queue × 20 queues theoretical). A second consumer halves per-consumer
queue load.

**Solution**: Add `app.sqs.room-start` and `app.sqs.room-end` config properties to
`SqsConsumerService`. Deploy a second EC2-Consumer-B instance configured for rooms 11–20.
EC2-Consumer-A handles rooms 1–10. Both share the same RDS (ON CONFLICT handles idempotency).

**No ALB needed**: Consumers PULL from SQS. There is no incoming traffic to route.

### Files to Modify

1. **MODIFY**: `consumer-v3/src/main/java/com/chatflow/consumer/SqsConsumerService.java`
2. **MODIFY**: `consumer-v3/src/main/resources/application.properties`
3. **NEW FILE**: `consumer-v3/src/main/resources/application-consumer-b.properties` (for EC2-Consumer-B)

### Step 1: Modify SqsConsumerService.java

Add two new `@Value` fields:
```java
@Value("${app.sqs.room-start:1}")
private int roomStart;   // inclusive, default 1

@Value("${app.sqs.room-end:20}")
private int roomEnd;     // inclusive, default 20
```

In the `start()` method, find the loop that launches poll threads. Currently it likely loops
`for (int room = 1; room <= 20; room++)` or similar using `numThreads`.

Change it to:
```java
for (int room = roomStart; room <= roomEnd; room++) {
    final String paddedRoomId = String.format("%02d", room);
    executor.submit(() -> pollLoop(paddedRoomId));   // or however pollLoop is currently called
}
```

Also change `numThreads` (used to size the executor) to `roomEnd - roomStart + 1`.

> **NOTE**: Check the exact loop structure in `start()` before editing — the above is the intent,
> adapt to match actual code style.

### Step 2: Update application.properties

Add to `consumer-v3/src/main/resources/application.properties`:
```properties
# ── Room partitioning (for horizontal scaling) ──────────────────────
# Consumer-A (default): handles all 20 rooms
app.sqs.room-start=1
app.sqs.room-end=20
```

### Step 3: Create application-consumer-b.properties

Create `consumer-v3/src/main/resources/application-consumer-b.properties`:
```properties
# Consumer-B override: handles rooms 11-20 only
app.sqs.room-start=11
app.sqs.room-end=20
```

And update Consumer-A's `application.properties`:
```properties
# Consumer-A: handles rooms 1-10 only (after adding Consumer-B)
app.sqs.room-start=1
app.sqs.room-end=10
```

### Step 4: Provision EC2-Consumer-B

Option A — Launch a new t3.micro EC2 in us-west-2 with the same IAM role as EC2-Consumer:
```
AWS Console → EC2 → Launch Instance
  AMI: Amazon Linux 2023
  Instance type: t3.micro
  Key pair: 6650-Timmons-Project
  Security group: same as EC2-Consumer (allow outbound to RDS port 5432, allow outbound to EC2-S1/S2 port 8080)
  IAM role: same role with sqs:ReceiveMessage, sqs:DeleteMessage, sqs:GetQueueUrl permissions
```

Option B — Use the existing EC2-Consumer with a second JAR process (rooms 1-10 on process A, rooms 11-20 on process B, port 8081 and 8082 respectively). Simpler for testing.

### Step 5: Deploy to EC2-Consumer-B

```bash
# Upload JAR and config
scp -i ~/.ssh/6650-Timmons-Project.pem consumer-v3/consumer-v3.jar \
    ec2-user@<EC2-B-IP>:~/consumer-v3/

# On EC2-Consumer-B: override room range via command line
export RDS_HOST=<endpoint> RDS_USER=chatflow_user RDS_PASS=<pass>
nohup java -jar consumer-v3/consumer-v3.jar \
  --spring.config.location=consumer-v3/application.properties \
  --app.sqs.room-start=11 \
  --app.sqs.room-end=20 \
  > consumer-v3/consumer-b.log 2>&1 &

# On EC2-Consumer-A: restrict to rooms 1-10
nohup java -jar consumer-v3/consumer-v3.jar \
  --spring.config.location=consumer-v3/application.properties \
  --app.sqs.room-start=1 \
  --app.sqs.room-end=10 \
  > consumer-v3/consumer-a.log 2>&1 &
```

### Step 6: Verify no duplicate inserts

Both consumers write to the same RDS. `ON CONFLICT (message_id) DO NOTHING` ensures
that if a message is (rarely) polled by both consumers before deletion, only one insert wins.

```bash
# After a test run:
psql -h $RDS_HOST -U $RDS_USER -d chatflow \
  -c "SELECT COUNT(*) - COUNT(DISTINCT message_id) AS duplicates FROM messages;"
# Should be 0
```

### Step 7: Measure before/after

**Before**: Post-Opt-1 throughput (single consumer) — record this first.
**After**: Run same 500K test with both consumers active.

Monitor both consumers:
```bash
# Consumer-A
curl -s http://<EC2-Consumer-A-IP>:8081/health | python3 -c \
  "import sys,json; d=json.load(sys.stdin); print('A consumed:', d['sqs']['consumed'])"

# Consumer-B
curl -s http://<EC2-Consumer-B-IP>:8081/health | python3 -c \
  "import sys,json; d=json.load(sys.stdin); print('B consumed:', d['sqs']['consumed'])"

# Total = A + B, should be ~500,000
# Each should handle ~50% (250,000)
# Combined rate should approach 2× single-consumer rate
```

---

## Measurement and Report Summary

### Before/After Table (fill in after running tests)

| Configuration | Throughput (msg/s) | avgInsertMs | Notes |
|--------------|-------------------|-------------|-------|
| A3 Baseline (1 consumer, blocking broadcast) | ~1,008 | 3.2ms | Measured in A3 |
| After Opt 1 (1 consumer, async broadcast queue) | ??? | ??? | Measure after Opt 1 deploy |
| After Opt 2 (2 consumers, 10 rooms each) | ??? | ??? | Measure after Opt 2 deploy |

### JMeter Setup (separate from client-v3 tests)

JMeter is used to test the **WebSocket + /metrics API** under concurrent user load.
Install WebSocket plugin: JMeter Plugin Manager → search "WebSocket Samplers by Peter Doornbosch"

Two test plans needed:
1. **Baseline**: 1,000 concurrent users, 100K API calls, 5 min, 70% GET /metrics + 30% WS send
2. **Stress**: 500 concurrent users, 200K–500K calls, 30 min, mixed

Measure: avg response time, p95/p99, throughput (req/s), error rate.

---

## Quick Reference: Key File Locations

| File | Purpose |
|------|---------|
| `consumer-v3/src/main/java/com/chatflow/consumer/SqsConsumerService.java` | Main polling loop + processMessage() |
| `consumer-v3/src/main/java/com/chatflow/consumer/BroadcastClient.java` | HTTP broadcast to server EC2s, throws BroadcastException |
| `consumer-v3/src/main/java/com/chatflow/consumer/db/DbWriterService.java` | DB write queue (already async, DO NOT modify for A4) |
| `consumer-v3/src/main/java/com/chatflow/consumer/HealthController.java` | /health endpoint — add broadcast metrics here |
| `consumer-v3/src/main/resources/application.properties` | All config — add broadcast queue + room range config |
| `database/schema.sql` + `indexes.sql` | RDS schema (DO NOT modify for A4) |
| `server-v3/` | Metrics API — no A4 changes needed |
| `client-v3/` | Load test client — no A4 changes needed |

## Key Facts for New Session

- **Package**: `com.chatflow.consumer` (consumer), `com.chatflow.server` (server)
- **Build**: `mvn clean package -DskipTests -q` from project root, or `-pl consumer-v3` for just consumer
- **Test with H2**: consumer-v3 has H2 in-memory DB for tests — `src/test/resources/application.properties`
- **A3 baseline**: 500K test = 8m15s = ~1,008 msg/s. This is the "before" number for A4.
- **RDS**: 1.5M+ rows already in `messages` table from A3 tests
- **EC2-Consumer public IP**: 35.92.149.159
- **EC2-S1 private IP**: 172.31.25.72:8080
- **EC2-S2 private IP**: 172.31.24.104:8080
- **SQS queue names**: `chatflow-room-01.fifo` through `chatflow-room-20.fifo`, account 449126751631, us-west-2
