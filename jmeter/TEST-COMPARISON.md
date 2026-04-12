# JMeter Test Comparison

| Parameter | `test1-baseline.jmx` | `test2-stress.jmx` |
|---|---|---|
| **Purpose** | Establish a performance baseline — does the system handle expected load? | Push the system to its limits — expose CPU ceiling, SQS depth behavior over time |
| **Total users** | 1,000 | 500 |
| **WS Writers** | 300 threads (30%) | 150 threads (30%) |
| **HTTP Readers** | 700 threads (70%) | 350 threads (70%) |
| **Duration** | 5 minutes (300s) | 30 minutes (1800s) |
| **Ramp-up** | 60s | 120s |
| **WS think time** | 2000–3000ms (avg 2500ms) | 1500–2500ms (avg 2000ms) |
| **HTTP think time** | 2500–3500ms (avg 3000ms) | 2000–3000ms (avg 2500ms) |
| **Target write calls** | ~30K | ~108K |
| **Target read calls** | ~70K | ~252K |
| **Target total calls** | ~100K | ~360K |
| **Write definition** | WS send → RECEIVED ACK → /metrics DB verify | same |
| **Read definition** | GET /metrics 100ms window → HTTP 200 | same |
| **Write rate** | ~100 msg/s | ~60 msg/s (fewer threads, but longer run) |
| **Read rate** | ~233 calls/s | ~140 calls/s |
| **Results file** | `baseline-results.jtl` | `stress-results.jtl` |
| **Report folder** | `baseline-report/` | `stress-report/` |

## Why the stress test has fewer users but a harder workload

Fewer users keeps the baseline comparison clean — the stress comes from **duration** (30 min vs 5 min)
and **tighter think times**, not just raw concurrency. The goal is to find where things degrade over
time: SQS queue depth creep, HikariCP connection exhaustion, EC2 CPU saturation, memory pressure —
none of which show up in a 5-minute run.