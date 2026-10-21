#!/data/data/com.termux/files/usr/bin/sh
# Termux:Boot — single consolidated startup for the phone's Hermes stack.
# Replaces both start.sh and start-hermes.sh (which raced each other and one of
# them had a mangled heredoc pasted into it, leaving stray EOF/chmod lines).

sleep 20
termux-wake-lock
export PATH="$HOME/.local/bin:$PREFIX/bin:$PATH"

# SSH in, so the PC can drive this device
pgrep -x sshd >/dev/null || sshd

# Local ADB loopback (phone automation: screenshots, taps, app launch)
adb connect 127.0.0.1:5555 >/dev/null 2>&1

# Optional helper bridge
if [ -f "$HOME/agy_bridge.py" ]; then
  pgrep -f agy_bridge.py >/dev/null || \
    nohup python3.13 "$HOME/agy_bridge.py" >> "$HOME/.hermes/bridge.log" 2>&1 &
fi

# Control server FIRST: the Hermes Control app talks to it to report status and to
# restart a dead gateway. If this is down, the app is blind.
sh "$HOME/.local/bin/gwctl-start.sh"

# Hermes gateway under tmux — NOT nohup. A foreground gateway started with
# `nohup ... &` can silently never come up, and then the Telegram bot is offline
# with no error anywhere. tmux keeps a real TTY and survives the boot shell.
if [ -f "$HOME/.hermes/.env" ]; then
  if ! pgrep -f "hermes gateway run" >/dev/null; then
    tmux kill-session -t gw 2>/dev/null
    tmux new-session -d -s gw -x 200 -y 50 \
      "PYTHONUNBUFFERED=1 hermes gateway run --replace 2>&1 | tee $HOME/.hermes/gw-live.log"
  fi
fi
