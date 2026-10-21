#!/data/data/com.termux/files/usr/bin/sh
# Termux:Boot entry point. All the logic lives in hermes-svc so the boot path and the
# manual/app restart path are the same code.
sleep 20
exec sh "$HOME/.local/bin/hermes-svc" boot >> "$HOME/.hermes/boot.log" 2>&1
