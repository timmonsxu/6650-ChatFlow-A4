# JMeter Load Test Instructions

## 1. Download and Install JMeter (Mac)

Install JMeter via Homebrew:

```bash
brew install jmeter
```

Verify the installation:

```bash
jmeter --version
```

### WebSocket Plugin (required)

The tests use the Peter Doornbosch WebSocket sampler. Install it via Homebrew:

```bash
brew install jmeter-plugins-manager
```

Then install the WebSocket sampler plugin:
Restart JMeter, then Options → Plugins Manager → Available Plugins → search "WebSocket Samplers by Peter Doornbosch" → Apply Changes and Restart.

Verify the plugin jar is present in the JMeter `lib/ext/` directory:

```bash
ls $(brew --prefix jmeter)/libexec/lib/ext/ | grep websocket
```

---

## 2. Pre-Test Checklist

Before running either test, confirm the following:

### AWS Infrastructure
- [ ] **EC2 Server 1 (S1)** is running and healthy
- [ ] **EC2 Server 2 (S2)** is running and healthy
- [ ] **EC2 Consumer 1 (C1)** is running and processing messages
- [ ] **EC2 Consumer 2 (C2)** is running and processing messages
- [ ] **ALB** is active and both servers are registered as healthy targets
- [ ] Verify the `ALB_HOST` value in the `.jmx` file matches your current ALB DNS name

> For how to start EC2 instances and connect, see [QUICK-START.md](../../QUICK-START.md)

### SQS — Purge Queues if Necessary
If there are leftover messages from a previous run, purge the SQS queues before starting so queue depth metrics start from zero.

> See [QUICK-START.md](../../QUICK-START.md) for purge instructions.

### RDS — Truncate if Necessary
If you want a clean message count for this run, truncate the messages table in RDS before starting.

> See [QUICK-START.md](../../QUICK-START.md) for truncate instructions.

---

## 3. Running the Tests

Run from the `load-tests/jmeter/` directory. Always run in **non-GUI mode** (`-n`) for load tests.

### Test 1 — Baseline (5 min, 1000 users)
- 300 WebSocket writers + 700 HTTP `/metrics` readers
- Target: ~100K total requests

```bash
jmeter -n -t test1-baseline.jmx -l baseline-results.jtl -e -o baseline-report/
```

### Test 2 — Stress (30 min, 500 users)
- 150 WebSocket writers + 350 HTTP `/metrics` readers
- Target: ~360K total requests

```bash
jmeter -n -t test2-stress.jmx -l stress-results.jtl -e -o stress-report/
```

After the test finishes, open the HTML report:
```bash
open baseline-report/index.html
# or
open stress-report/index.html
```

---

## 4. Re-Running a Test

JMeter will error if the report folder already exists. Delete the old results first:

```bash
# For baseline
rm -rf baseline-report/ baseline-results.jtl baseline-summary.jtl
jmeter -n -t test1-baseline.jmx -l baseline-results.jtl -e -o baseline-report/

# For stress
rm -rf stress-report/ stress-results.jtl stress-summary.jtl
jmeter -n -t test2-stress.jmx -l stress-results.jtl -e -o stress-report/
```

---

## 5. Monitoring in CloudWatch During the Test

While the test is running, watch these metrics in **CloudWatch > Dashboards** CS6650A4-dashboard.

### EC2 — CPU
- **Metrics > EC2 > Per-Instance Metrics**
- Metric: `CPUUtilization`
- Watch all three instances: S1, S2, Consumer
- Expected: S1/S2 climb under WebSocket + HTTP load; Consumer climbs with SQS throughput

### RDS — DB Connections & CPU
- **Metrics > RDS > Per-Database Metrics**
- Metrics: `DatabaseConnections`, `CPUUtilization`, `WriteLatency`
- `DatabaseConnections` should stay within your HikariCP pool size (max 10 per server)

### SQS — Queue Depth
- **Metrics > SQS**
- Metric: `ApproximateNumberOfMessagesVisible` per queue
- Should stay near 0 if the consumer keeps up; a growing number means the consumer is falling behind