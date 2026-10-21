#!/data/data/com.termux/files/usr/bin/sh
# Kept at this path because the Hermes Control app's "wake" button runs it through
# Termux RUN_COMMAND. Wakes the control server and anything else that is down.
exec sh "$HOME/.local/bin/hermes-svc" boot
