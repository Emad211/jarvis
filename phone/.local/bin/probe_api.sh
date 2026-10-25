#!/data/data/com.termux/files/usr/bin/sh
# End-to-end check of Hermes' own /v1/runs contract on the phone.
#   probe_api.sh "prompt"
# Prints: capabilities summary, run id, every SSE event with its arrival time, and totals.
# The API key is read from ~/.hermes/.env here and never echoed.
set -u
BASE="http://127.0.0.1:8642"
K=$(grep -m1 '^API_SERVER_KEY=' "$HOME/.hermes/.env" | cut -d= -f2-)
[ -n "$K" ] || { echo "no API_SERVER_KEY"; exit 1; }
AUTH="Authorization: Bearer $K"
PROMPT="${1:-reply with exactly: READY}"
T="${TMPDIR:-$PREFIX/tmp}"

echo "--- capabilities"
curl -s -m 20 -H "$AUTH" "$BASE/v1/capabilities" -o "$T/cap.json" -w 'http=%{http_code}\n'
$PREFIX/lib/hermes-agent/app/venv/bin/python - "$T/cap.json" <<'PY'
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception as exc:
    print("capabilities unreadable:", exc); raise SystemExit
for k in ("object", "version", "streaming", "runs", "approvals", "sessions", "toolsets", "models"):
    if k in d:
        v = d[k]
        v = v if not isinstance(v, (list, dict)) else (str(v)[:120])
        print(" ", k, "=", v)
PY

echo "--- start run"
start=$(date +%s%N)
req=$(printf '{"input":%s,"session_id":"probe-%s"}' "$($PREFIX/lib/hermes-agent/app/venv/bin/python -c 'import json,sys;print(json.dumps(sys.argv[1],ensure_ascii=False))' "$PROMPT")" "$(date +%s)")
rid=$(printf '%s' "$req" | curl -s -m 30 -X POST -H "$AUTH" -H 'Content-Type: application/json' --data-binary @- "$BASE/v1/runs" | tee "$T/run.json" | sed -n 's/.*"run_id": *"\([^"]*\)".*/\1/p')
head -c 300 "$T/run.json"; echo
[ -n "$rid" ] || { echo "no run_id"; exit 1; }
echo "run_id=$rid"

echo "--- events (ms since POST)"
timeout 180 curl -s -N -H "$AUTH" "$BASE/v1/runs/$rid/events" | while IFS= read -r line; do
  now=$(date +%s%N)
  ms=$(( (now - start) / 1000000 ))
  case "$line" in
    event:*) printf '%6s ms  %s\n' "$ms" "${line#event: }" ;;
    data:*)  printf '          %s\n' "$(printf '%s' "${line#data: }" | cut -c1-220)" ;;
  esac
done
