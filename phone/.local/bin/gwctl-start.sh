#!/data/data/com.termux/files/usr/bin/sh
# Start the gwctl control server if it is not already running.
# Called from ~/.termux/boot/start.sh and from the hcontrol app's
# "بیدار کردن سرویس کنترل" button (via Termux RUN_COMMAND).
#
# gwctl v2 needs PyYAML, so it runs under the Hermes venv interpreter. Termux's
# system python3 has no yaml module and would fail every config endpoint.

GWCTL="$HOME/.local/bin/gwctl.py"
LOG="$HOME/.hermes/gwctl.log"
VENV_PY="$PREFIX/lib/hermes-agent/app/venv/bin/python"

# Already up? Nothing to do — the port answers.
if curl -s --max-time 3 http://127.0.0.1:8777/ping >/dev/null 2>&1; then
  exit 0
fi

if [ -x "$VENV_PY" ]; then
  PY="$VENV_PY"
else
  PY="$PREFIX/bin/python3"
fi

mkdir -p "$HOME/.hermes"
tmux kill-session -t gwctl 2>/dev/null

# tmux, not nohup: a nohup'd process can silently never come up once the launching
# shell disappears — the exact failure this script exists to fix.
tmux new-session -d -s gwctl -x 120 -y 30 \
  "$PY $GWCTL 2>&1 | tee $LOG"
