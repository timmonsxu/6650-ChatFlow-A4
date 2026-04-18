# JMeter Load Test Instructions for A3

This folder reuses the A4 JMeter method for A3 so the 5-minute and 30-minute runs stay comparable.

Kept from A4:
- Two test plans: `test1-baseline.jmx` and `test2-stress.jmx`
- Mixed workload: WebSocket writers plus HTTP `/metrics` readers
- Same write definition: WebSocket send, `RECEIVED` ack, then verify persistence through `/metrics`
- Same read definition: repeated `GET /metrics` calls over a short time window

Removed from A4:
- A4-only notes about multiple consumers
- A4-specific CloudWatch dashboard naming
- Generated reports, screenshots, and prior result files

## 1. Install JMeter

```bash
brew install jmeter
jmeter --version
```

## 2. Install the WebSocket plugin

These test plans use the Peter Doornbosch WebSocket sampler.

```bash
brew install jmeter-plugins-manager
ls $(brew --prefix jmeter)/libexec/lib/ext/ | grep websocket
```

If the jar is not present, open JMeter and install:

`Options -> Plugins Manager -> Available Plugins -> WebSocket Samplers by Peter Doornbosch`

## 3. Pre-test checklist for A3

- Both A3 server instances are running and healthy behind the ALB
- The A3 consumer is running and processing SQS messages
- The ALB target group is healthy
- The `ALB_HOST` value inside each `.jmx` file matches the current A3 ALB DNS name
- SQS queues are empty before the run if you want a clean queue-depth graph
- The `messages` table is truncated before the run if you want clean counts

Relevant A3 endpoints confirmed in this repo:
- WebSocket path: `/chat/{roomId}`
- Metrics path: `/metrics`

## 4. Test 1: baseline

Purpose: 5-minute run using the same A4 baseline method.

- 300 WebSocket writer threads
- 700 HTTP `/metrics` reader threads
- Duration: 300 seconds
- Ramp-up: 60 seconds

Run:

```bash
cd /Users/shibofolder/CS6650/Assignment4/6650-chatflow-a3/jmeter
jmeter -n -t test1-baseline.jmx -l baseline-results.jtl -e -o baseline-report
```

## 5. Test 2: stress

Purpose: 30-minute run using the same A4 endurance/stress method.

- 150 WebSocket writer threads
- 350 HTTP `/metrics` reader threads
- Duration: 1800 seconds
- Ramp-up: 120 seconds

Run:

```bash
cd /Users/shibofolder/CS6650/Assignment4/6650-chatflow-a3/jmeter
jmeter -n -t test2-stress.jmx -l stress-results.jtl -e -o stress-report
```

## 6. Re-run a test

Delete old outputs first or JMeter will fail when the report directory already exists.

```bash
cd /Users/shibofolder/CS6650/Assignment4/6650-chatflow-a3/jmeter
rm -rf baseline-report baseline-results.jtl baseline-summary.jtl
rm -rf stress-report stress-results.jtl stress-summary.jtl
```

## 7. What to monitor during A3 runs

- EC2 CPU for both A3 servers
- EC2 CPU for the A3 consumer
- RDS `DatabaseConnections`, `CPUUtilization`, and `WriteLatency`
- SQS `ApproximateNumberOfMessagesVisible`

The test plans intentionally keep the A4 request mix and assertions so you can compare A3 and A4 under the same measurement method.
