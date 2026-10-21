#!/data/data/com.termux/files/usr/bin/sh
# Exercise every gwctl v2 endpoint and print a compact pass/fail table.
B="http://127.0.0.1:8777"
ok() { printf "  %-26s %s\n" "$1" "$2"; }

echo "=== GET endpoints ==="
for p in /ping /version /status /system /config /models /keys /logs/files /checks /cron; do
  code=$(curl -s -o /tmp/gw.out -w "%{http_code}" --max-time 90 "$B$p")
  bytes=$(wc -c < /tmp/gw.out)
  ok "$p" "HTTP $code  ${bytes}B"
done

echo
echo "=== key payload shape ==="
curl -s --max-time 30 "$B/keys" | python3 -c "
import json,sys
d=json.load(sys.stdin)['providers']
for prov, rows in d.items():
    print(f'  {prov}: {len(rows)} keys')
    for r in rows:
        print('    -', r['label'], '|', r['status'], '|', r['key'], '| cd=', r['cooldown'])
"

echo
echo "=== status core ==="
curl -s --max-time 30 "$B/status" | python3 -c "
import json,sys; d=json.load(sys.stdin)
print('  overall:', d['overall'], '| tg:', d['telegram'], '| model:', d['model'])
print('  pid:', d['pid'], '| uptime:', d['uptime_seconds'], 's | ver:', d['version'])
"

echo
echo "=== system core ==="
curl -s --max-time 40 "$B/system" | python3 -c "
import json,sys; d=json.load(sys.stdin)
b=d['battery']; m=d['memory']
print('  battery:', b.get('percent'), '%', b.get('status'))
print('  mem:', m['percent'], '% used |', m['used_kb']//1024, 'MB /', m['total_kb']//1024, 'MB')
print('  swap:', m['swap_percent'], '%')
for k,v in d['storage'].items(): print('  storage', k, v['percent'], '%')
print('  load:', d['load'])
"

echo
echo "=== config fields ==="
curl -s --max-time 40 "$B/config" | python3 -c "
import json,sys; d=json.load(sys.stdin)
print('  model:', d['model'], '| providers:', d['providers'])
print('  editable fields:', len(d['fields']))
for f in d['fields'][:5]: print('   ', f['key'], '=', f['value'], '(', f['type'], ')')
"

echo
echo "=== models ==="
curl -s --max-time 40 "$B/models" | python3 -c "
import json,sys; d=json.load(sys.stdin)
print('  configured:', [(c['provider'], c['model']) for c in d['configured']])
print('  live:', d['live'], '| err:', d['live_error'])
"

echo
echo "=== checks ==="
curl -s --max-time 60 "$B/checks" | python3 -c "
import json,sys
for c in json.load(sys.stdin)['checks']:
    print(f\"  {'OK ' if c['ok'] else 'FAIL'} {c['label']:<26} {c['detail'][:60]} ({c['ms']}ms)\")
"

echo
echo "=== logs tail ==="
curl -s --max-time 30 "$B/logs?file=gw-live.log&lines=2" | python3 -c "
import json,sys; d=json.load(sys.stdin)
print('  file:', d.get('file'), '| size:', d.get('size'), '| lines:', len(d.get('lines',[])))
for l in d.get('lines',[])[-2:]: print('   ', l[:110])
"

echo
echo "=== write test: toggle streaming.edit_interval then restore ==="
curl -s --max-time 30 -X POST "$B/config/set" -H 'Content-Type: application/json' \
  -d '{"key":"streaming.edit_interval","value":1.4}' | head -c 300
echo
curl -s --max-time 30 -X POST "$B/config/set" -H 'Content-Type: application/json' \
  -d '{"key":"streaming.edit_interval","value":1.0}' | head -c 300
echo

echo
echo "=== model test (real inference) ==="
curl -s --max-time 120 -X POST "$B/model/test" -H 'Content-Type: application/json' \
  -d '{"provider":"agentrouter","model":"deepseek-v4-flash","prompt":"reply with exactly: PONG"}' \
 | python3 -c "
import json,sys; d=json.load(sys.stdin)
print('  ok:', d.get('ok'), '| ms:', d.get('ms'), '| mode:', d.get('mode'))
print('  reply:', (d.get('reply') or d.get('error') or '')[:120])
"

echo
echo "=== key test ==="
curl -s --max-time 90 -X POST "$B/keys/test" -H 'Content-Type: application/json' \
  -d '{"provider":"agentrouter"}' | python3 -c "
import json,sys; d=json.load(sys.stdin)
for r in d['results']: print('  ', r['label'], '->', 'OK' if r['ok'] else 'FAIL', r['ms'],'ms', r['detail'][:60])
"
