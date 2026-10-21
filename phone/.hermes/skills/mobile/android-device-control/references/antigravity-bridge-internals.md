# Local Antigravity vision bridge (`agy_bridge.py`) — how it works & how it was fixed

The phone's Hermes talks to Google's Antigravity API through a small local HTTP
bridge. Details below are the durable truths learned while getting VLM vision
working — check these before re-debugging.

## Topology
- `~/agy_bridge.py` runs a `ThreadingHTTPServer` on `127.0.0.1:8790`
  (env `AGY_BRIDGE_PORT`, auth key `ANTIGRAVITY_BRIDGE_KEY` = `antigravity-local`).
- Real running file is `agy_bridge.py` (NOT the older `antigravity_bridge.py`,
  which still sits in `~/` and has pre-existing syntax bugs — edit the right one).
- It receives OpenAI-style `/v1/chat/completions`, converts the messages into a
  text prompt, then shells out to the `agy` CLI binary (`~/.local/bin/agy`,
  v1.3.3) with `-p <prompt> --model <base> --output-format json [--effort X]`.
- It is auto-started by `~/.termux/boot/start-hermes.sh`:
  `pgrep -f agy_bridge.py || nohup python3.13 agy_bridge.py > ~/.hermes/bridge.log 2>&1 &`

## Two bugs I fixed (the "why vision/apps seemed broken")
1. **Images were silently stripped.** The original handler only kept
   parts with `type == "text"` and dropped `image_url`/`image` parts, so the
   model always got a text-only prompt. Fix: decode base64 image parts, write
   them to `~/.hermes/vision/bridge_*.png`, and append `[Image saved at: <path>]`
   to the text so `agy` can read the file.
2. **No SSE streaming → "malformed SSE response" from Hermes.** The bridge
   returned a single JSON blob, but Hermes sends `stream:true` and expects
   `text/event-stream`. Fix: when `stream` is set, emit OpenAI-style chunks —
   each line prefixed `data: ` (CRITICAL — missing the `data: ` prefix is what
   caused the malformed-SSE error), then `data: <chunk>` with
   `"finish_reason":"stop"`, then `data: [DONE]`. Keep `Content-Length` exact.

## Restart discipline (test bridge changes this way)
Kill the OLD process by exact name — `pkill -f agy_bridge.py` can match your own
grep/command line, so verify with `ps -eo pid,cmd | grep python3.13.*agy_bridge`.
Then relaunch in the background with a tracked session and confirm with
`curl -s http://127.0.0.1:8790/v1/models`.

## Verification
- `curl http://127.0.0.1:8790/v1/models` lists the model IDs (gemini-3.8-flash-low/medium/high, claude-sonnet-4-6, claude-opus-4-6-thinking, gpt-oss-120b-medium).
- End-to-end: `hermes chat -q "..." -m gemini-3.8-flash-low --provider antigravity`.