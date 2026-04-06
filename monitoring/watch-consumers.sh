#!/bin/bash
# watch-consumers.sh
# Run on C1. Shows C1, C2, and combined totals every 10s.

watch -n 10 "python3 -c \"
import urllib.request, json, sys

def fetch(url):
    try:
        return json.loads(urllib.request.urlopen(url, timeout=3).read())
    except Exception as e:
        print(f'  [ERROR fetching {url}: {e}]')
        return None

c1 = fetch('http://localhost:8081/health')
c2 = fetch('http://172.31.38.86:8081/health')

def section(label, d):
    if d is None:
        print(f'{label}: unreachable')
        return
    bc = d['broadcast']
    bc_total = bc['sent'] + bc['failed']
    drop_pct = 100 * bc['failed'] / bc_total if bc_total > 0 else 0
    print(f'{label}')
    print(f'  sqs.consumed:    {d[\\\"sqs\\\"][\\\"consumed\\\"]:>10,}')
    print(f'  bc.sent:         {bc[\\\"sent\\\"]:>10,}')
    print(f'  bc.failed:       {bc[\\\"failed\\\"]:>10,}   drop={drop_pct:.1f}%')
    print(f'  bc.queueDepth:   {bc[\\\"queueDepth\\\"]:>10,}')
    print(f'  db.inserted:     {d[\\\"db\\\"][\\\"inserted\\\"]:>10,}')
    print(f'  db.queueDepth:   {d[\\\"db\\\"][\\\"queueDepth\\\"]:>10,}')
    print(f'  db.avgInsertMs:  {d[\\\"db\\\"][\\\"avgInsertMs\\\"]:>10}')
    print(f'  msgPerSec  1s:   {d[\\\"stats\\\"][\\\"msgPerSec1s\\\"]:>10}')
    print(f'  msgPerSec 10s:   {d[\\\"stats\\\"][\\\"msgPerSec10s\\\"]:>10}')
    print(f'  msgPerSec 60s:   {d[\\\"stats\\\"][\\\"msgPerSec60s\\\"]:>10}')

section('=== C1 (rooms  1-10) ===', c1)
print()
section('=== C2 (rooms 11-20) ===', c2)

if c1 and c2:
    print()
    print('=== TOTAL (C1 + C2) ===')
    consumed  = c1['sqs']['consumed']  + c2['sqs']['consumed']
    sent      = c1['broadcast']['sent']    + c2['broadcast']['sent']
    failed    = c1['broadcast']['failed']  + c2['broadcast']['failed']
    depth     = c1['broadcast']['queueDepth'] + c2['broadcast']['queueDepth']
    inserted  = c1['db']['inserted']   + c2['db']['inserted']
    db_depth  = c1['db']['queueDepth'] + c2['db']['queueDepth']
    total_msg = c1['stats']['totalMessages'] + c2['stats']['totalMessages']
    bc_total  = sent + failed
    drop_pct  = 100 * failed / bc_total if bc_total > 0 else 0
    rate1  = float(c1['stats']['msgPerSec1s'])  + float(c2['stats']['msgPerSec1s'])
    rate10 = float(c1['stats']['msgPerSec10s']) + float(c2['stats']['msgPerSec10s'])
    rate60 = float(c1['stats']['msgPerSec60s']) + float(c2['stats']['msgPerSec60s'])
    print(f'  sqs.consumed:    {consumed:>10,}')
    print(f'  bc.sent:         {sent:>10,}')
    print(f'  bc.failed:       {failed:>10,}   drop={drop_pct:.1f}%')
    print(f'  bc.queueDepth:   {depth:>10,}')
    print(f'  db.inserted:     {inserted:>10,}')
    print(f'  db.queueDepth:   {db_depth:>10,}')
    print(f'  stats.total:     {total_msg:>10,}')
    print(f'  msgPerSec  1s:   {rate1:>10.1f}')
    print(f'  msgPerSec 10s:   {rate10:>10.1f}')
    print(f'  msgPerSec 60s:   {rate60:>10.1f}')
\""
