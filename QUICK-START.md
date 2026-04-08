# ChatFlow A4 — Quick Start (For Teammates)

## Infrastructure

| Component | Instance | Public IP | Private IP | Port |
|-----------|----------|-----------|------------|------|
| Server 1 (S1) | t3.micro | 54.184.109.66 | 172.31.25.72 | 8080 |
| Server 2 (S2) | t3.micro | 54.190.22.194 | 172.31.24.104 | 8080 |
| Consumer 1 (C1) | t3.micro | 35.92.149.159 | 172.31.27.142 | 8081 · rooms 1–10 |
| Consumer 2 (C2) | t3.micro | 54.218.131.243 | 172.31.38.86 | 8081 · rooms 11–20 |
| RDS (PostgreSQL 17) | db.t3.micro | — | chatflow-db.clm2w2kwmivu.us-west-2.rds.amazonaws.com | 5432 |

**Region**: us-west-2 · **AWS Account**: 449126751631

---

## Step 0 — Access

### AWS Console
```
URL:      https://449126751631.signin.aws.amazon.com/console
Region:   us-west-2 (Oregon)
Username: a4-teammate
Password: 6650A4tivinibo
```

### SSH (PEM file: `6650-Timmons-Project.pem`)
```bash
chmod 400 ~/6650-Timmons-Project.pem   # run once on Mac/Linux

ssh -i ~/6650-Timmons-Project.pem ec2-user@54.184.109.66   # S1
ssh -i ~/6650-Timmons-Project.pem ec2-user@54.190.22.194   # S2
ssh -i ~/6650-Timmons-Project.pem ec2-user@35.92.149.159   # C1
ssh -i ~/6650-Timmons-Project.pem ec2-user@54.218.131.243   # C2
```

### tmux Basics (recommended — keeps processes alive after SSH disconnect)

```bash
tmux false              # check what session you have
tmux new -s <name>        # create a new session named "name"
tmux attach -t <name>     # re-attach after disconnect

# Inside tmux:
Ctrl+B  C               # new window
Ctrl+B  N               # next window
Ctrl+B  P               # previous window
Ctrl+B  "               # split pane horizontally
Ctrl+B  %               # split pane vertically
Ctrl+B  arrow keys      # move between panes
Ctrl+B  D               # detach (process keeps running in background)

```

**Recommended layout per EC2**: open one tmux session with 2 windows — one for the process (`java -jar ...`), one for logs/health checks.

---

## Step 1 — Pre-Test Checklist ⚠️ (run before EVERY test)

### 1a. Verify all 20 SQS queues are empty

Check the SQS console — all 20 `chatflow-room-XX.fifo` queues must show **0** messages.
If any queue has messages, purge (only when both consumers are fully stopped):

```bash
for i in $(seq -f "%02g" 1 20); do
  aws sqs purge-queue \
    --queue-url https://sqs.us-west-2.amazonaws.com/449126751631/chatflow-room-${i}.fifo \
    --region us-west-2
  echo "Purged room-${i}"
done
```

> ⚠️ SQS purge has a **60-second cooldown** per queue. If you get `PurgeQueueInProgress`, wait 60s and retry.

### 1b. Truncate RDS messages table

RDS is only reachable from within the VPC — SSH into C1 first:

note: RDS PASSWORD=Xjz15693333013!
```bash
ssh -i ~/6650-Timmons-Project.pem ec2-user@35.92.149.159

psql -h $RDS_HOST -U $RDS_USER -d chatflow \
  -c "TRUNCATE TABLE messages;"

# Verify empty
psql -h $RDS_HOST -U $RDS_USER -d chatflow \
  -c "SELECT COUNT(*) FROM messages;"
# Expected: 0
```

### 1c. Kill any leftover processes on all 4 EC2s

```bash
# Run on each EC2
sudo ss -tlnp | grep -E '8080|8081'
kill -9 <PID>   # if anything is running
```

---

## Step 2 — Start Components (in order)

### S1 — Server
```bash
ssh -i ~/6650-Timmons-Project.pem ec2-user@54.184.109.66
tmux new -s s1
java -jar server-1.0.0.jar
# Wait for "Started ServerApplication"
```

### S2 — Server
```bash
ssh -i ~/6650-Timmons-Project.pem ec2-user@54.190.22.194
tmux new -s s2
java -jar server-1.0.0.jar
```

### C1 — Consumer (rooms 1–10)
```bash
ssh -i ~/6650-Timmons-Project.pem ec2-user@35.92.149.159
tmux new -s c1
java -jar consumer-1.0.0.jar --app.consumer.threads=10
# Wait for "BroadcastWorkerService started: 10 rooms × 4 workers"
```

### C2 — Consumer (rooms 11–20)
```bash
ssh -i ~/6650-Timmons-Project.pem ec2-user@54.218.131.243
tmux new -s c2
java -jar consumer-1.0.0.jar --app.consumer.threads=10 --app.consumer.room-start=11
# Wait for "rooms 11-20"
```

### Verify everything is up
```bash
# Run from C1
curl -s http://localhost:8081/health | python3 -m json.tool         # C1
curl -s http://172.31.38.86:8081/health | python3 -m json.tool      # C2
curl -s http://172.31.25.72:8080/ | head -3                         # S1
```

---

## Step 3 — Start Monitoring (on C1, before running client)

**Live dashboard** (open a second tmux window on C1):
```bash
Ctrl+B  C               # new window in existing tmux session
./watch-consumers.sh    # refreshes every 10s, Ctrl+C to stop
```

**CSV recording** (for data collection):
```bash
bash ~/monitoring/monitor-consumer.sh <test name>
# Shows one line per 10s — stop when db.inserted=500,000 and rate60s=0
```

---

## Step 4 — Run the Client (local machine)

```bash
java -jar target/client-1.0.0.jar
```

The client prints a summary when done. **Do not stop consumers yet** — they continue draining SQS and writing to DB for ~2–3 minutes after the client exits.

---

## Step 5 — Confirm Test Complete

```bash
# From C1 — check both consumers
curl -s http://localhost:8081/health | python3 -m json.tool
curl -s http://172.31.38.86:8081/health | python3 -m json.tool

# Confirm total DB row count
psql -h $RDS_HOST -U $RDS_USER -d chatflow \
  -c "SELECT COUNT(*) FROM messages;"
# Expected: 500000
```

Collect CSV from C1:
```bash
scp -i ~/6650-Timmons-Project.pem \
  ec2-user@35.92.149.159:~/consumer-test3-endurance-*.csv \
  ./load-tests/
```

---

## Step 6 — Deploying Code Changes

Build locally first (skip tests for speed):
```bash
mvn clean package -DskipTests
```

### Updated server → S1 and S2
```bash
scp -i ~/6650-Timmons-Project.pem \
  server-v3/target/server-1.0.0.jar ec2-user@54.184.109.66:~/server-1.0.0.jar

scp -i ~/6650-Timmons-Project.pem \
  server-v3/target/server-1.0.0.jar ec2-user@54.190.22.194:~/server-1.0.0.jar
```

### Updated consumer → C1 and C2
```bash
scp -i ~/6650-Timmons-Project.pem \
  consumer-v4/target/consumer-1.0.0.jar ec2-user@35.92.149.159:~/consumer-1.0.0.jar

scp -i ~/6650-Timmons-Project.pem \
  consumer-v4/target/consumer-1.0.0.jar ec2-user@54.218.131.243:~/consumer-1.0.0.jar
```

### Restart after upload

SSH into the EC2, kill the old process, start the new one:
```bash
# Find and kill the running jar
sudo ss -tlnp | grep 8080   # or 8081 for consumer
kill -9 <PID>

# Start fresh (inside tmux)
java -jar server-1.0.0.jar       # on S1/S2
java -jar consumer-1.0.0.jar --app.consumer.threads=10                          # on C1
java -jar consumer-1.0.0.jar --app.consumer.threads=10 --app.consumer.room-start=11  # on C2
```

### Updated monitoring scripts → C1
```bash
scp -i ~/6650-Timmons-Project.pem \
  monitoring/monitor-consumer.sh monitoring/watch-consumers.sh \
  ec2-user@35.92.149.159:~/
```
