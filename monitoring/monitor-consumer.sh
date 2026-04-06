#!/bin/bash
# monitor-consumer.sh
# Polls /health on C1 (localhost) AND C2 every 10 seconds, appends a CSV row.
# Captures SQS, broadcast, and DB metrics from both consumers combined.
# Timestamps let you measure exact DB-write-complete time after client finishes.
#
# Run on C1 BEFORE starting the client:
#   bash ~/monitoring/monitor-consumer.sh test3-endurance
#
# Stop with Ctrl+C when both consumers show db.inserted stable at 500000.
# Output file: consumer-<label>-<timestamp>.csv

LABEL="${1:-test}"
OUTPUT="consumer-${LABEL}-$(date +%Y%m%d-%H%M%S).csv"
INTERVAL=10
C1_URL="http://localhost:8081/health"
C2_URL="http://172.31.38.86:8081/health"

# CSV header
echo "timestamp_epoch,timestamp_utc,\
c1_sqs_consumed,c1_bc_sent,c1_bc_failed,c1_bc_queue_depth,c1_db_inserted,c1_db_queue_depth,c1_db_avg_insert_ms,c1_msg_per_sec_60s,\
c2_sqs_consumed,c2_bc_sent,c2_bc_failed,c2_bc_queue_depth,c2_db_inserted,c2_db_queue_depth,c2_db_avg_insert_ms,c2_msg_per_sec_60s,\
total_sqs_consumed,total_bc_sent,total_bc_failed,total_db_inserted,total_msg_per_sec_60s" \
  > "$OUTPUT"

echo "[monitor] Writing to $OUTPUT every ${INTERVAL}s — Ctrl+C to stop"
echo "[monitor] C1=$C1_URL  C2=$C2_URL"

while true; do
    C1_RAW=$(curl -s --max-time 3 "$C1_URL" 2>/dev/null)
    C2_RAW=$(curl -s --max-time 3 "$C2_URL" 2>/dev/null)

    TS_EPOCH=$(date +%s)
    TS_UTC=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

    ROW=$(python3 -c "
import sys, json

def parse(raw):
    if not raw:
        return None
    try:
        return json.loads(raw)
    except:
        return None

def extract(d):
    if d is None:
        return ['ERROR'] * 8
    sqs = d.get('sqs', {})
    bc  = d.get('broadcast', {})
    db  = d.get('db', {})
    st  = d.get('stats', {})
    return [
        sqs.get('consumed', 0),
        bc.get('sent', 0),
        bc.get('failed', 0),
        bc.get('queueDepth', 0),
        db.get('inserted', 0),
        db.get('queueDepth', 0),
        db.get('avgInsertMs', 0),
        st.get('msgPerSec60s', 0),
    ]

c1 = parse('''${C1_RAW}''')
c2 = parse('''${C2_RAW}''')
r1 = extract(c1)
r2 = extract(c2)

# totals (only if both parseable)
if c1 and c2:
    tot_consumed  = r1[0] + r2[0]
    tot_bc_sent   = r1[1] + r2[1]
    tot_bc_failed = r1[2] + r2[2]
    tot_inserted  = r1[4] + r2[4]
    tot_rate60    = float(str(r1[7])) + float(str(r2[7]))
else:
    tot_consumed = tot_bc_sent = tot_bc_failed = tot_inserted = tot_rate60 = 'ERROR'

row = r1 + r2 + [tot_consumed, tot_bc_sent, tot_bc_failed, tot_inserted, tot_rate60]
print(','.join(str(x) for x in row))
" 2>/dev/null)

    echo "${TS_EPOCH},${TS_UTC},${ROW}" >> "$OUTPUT"

    # Also print a quick summary to stdout so you can watch progress
    python3 -c "
import json
try:
    c1 = json.loads('''${C1_RAW}''')
    c2 = json.loads('''${C2_RAW}''')
    ins  = c1['db']['inserted'] + c2['db']['inserted']
    cons = c1['sqs']['consumed'] + c2['sqs']['consumed']
    sent = c1['broadcast']['sent'] + c2['broadcast']['sent']
    fail = c1['broadcast']['failed'] + c2['broadcast']['failed']
    rate = float(c1['stats']['msgPerSec60s']) + float(c2['stats']['msgPerSec60s'])
    print(f'[${TS_UTC}] consumed={cons:,}  db.inserted={ins:,}  bc.sent={sent:,}  bc.failed={fail:,}  rate60s={rate:.0f}msg/s')
except:
    print('[${TS_UTC}] parse error')
" 2>/dev/null

    sleep "$INTERVAL"
done
