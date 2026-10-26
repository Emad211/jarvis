#!/usr/bin/env bash
# ============================================================================
# install.sh — install Jarvis on a phone from this laptop.
#
#   bash install.sh                     # full path: push, bootstrap, build + install APK
#   bash install.sh --dry-run           # print every step, run nothing that changes anything
#   bash install.sh --ip 10.0.0.7       # pin the phone's address (skips auto-discovery)
#   bash install.sh --port 8781         # daemon port on the phone
#   bash install.sh --skip-apk          # phone side only (the APK is installed separately)
#   bash install.sh --no-build          # install the existing jarvis/dist/jarvis.apk as-is
#
# What it does:
#   1. checks the laptop has adb + ssh + tar (and pwsh when it has to build), all at once,
#      and says exactly what is missing and how to get it;
#   2. finds the phone: --ip / $PHONE_IP / cached ~/.hermes-phone-ip / `adb devices` /
#      the LAN scan in pc/ph.sh (the phone's IP is DHCP and drifts);
#   3. verifies ssh to Termux (port 8022) and adb (port 5555) before touching anything;
#   4. pushes this repo's phone/ tree into the phone's $HOME (config.yaml is never pushed);
#   5. runs `jarvis-install` on the phone over ssh (idempotent bootstrap: packages, Hermes
#      Agent, ~/.local/bin, ~/.hermes, tmux services, boot hook);
#   6. builds and installs the console APK: `pwsh -File jarvis/build.ps1 -Install`, or plain
#      `adb install -r` for an already built jarvis/dist/jarvis.apk with --no-build.
#
# Exit codes: 1 usage/prereq · 2 phone not found/unreachable · 3 push or bootstrap failed ·
#             4 APK step failed.
#
# Nothing here reads, prints or stores an API key: keys are typed into the app's first-run
# wizard on the phone and stay in ~/.hermes/.env there.
# ============================================================================
set -euo pipefail

cd "$(dirname "$0")"
ROOT="$PWD"
PH="pc/ph.sh"
PORT=""
IP_ARG=""
KEY_ARG=""
DRY=0
SKIP_APK=0
NO_BUILD=0

usage() {
  sed -n '2,32p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

die() { printf '\n\xE2\x9C\x97 %s\n' "$*" >&2; exit "${2:-1}"; }
step() { printf '\n== %s\n' "$*"; }
ok() { printf '  \xE2\x9C\x93 %s\n' "$*"; }
say() { printf '%s\n' "$*"; }
# Run a command, or only describe it under --dry-run.
run() {
  if [ "$DRY" -eq 1 ]; then
    printf '  [dry-run] %s\n' "$*"
    return 0
  fi
  "$@"
}

while [ $# -gt 0 ]; do
  case "$1" in
    -h | --help) usage 0 ;;
    --dry-run) DRY=1 ;;
    --skip-apk) SKIP_APK=1 ;;
    --no-build) NO_BUILD=1 ;;
    --ip)
      [ $# -ge 2 ] || die "--ip needs an address" 1
      IP_ARG=$2
      shift
      ;;
    --ip=*) IP_ARG=${1#--ip=} ;;
    --key)
      [ $# -ge 2 ] || die "--key needs a path to the ssh private key" 1
      KEY_ARG=$2
      shift
      ;;
    --key=*) KEY_ARG=${1#--key=} ;;
    --port)
      [ $# -ge 2 ] || die "--port needs a number" 1
      PORT=$2
      shift
      ;;
    --port=*) PORT=${1#--port=} ;;
    *) die "unknown option: $1 (try --help)" 1 ;;
  esac
  shift
done

if [ -n "$PORT" ]; then
  case "$PORT" in
    '' | *[!0-9]*) die "--port must be a number, got: $PORT" 1 ;;
  esac
fi

say "── Jarvis installer (laptop → phone) ──"
[ "$DRY" -eq 1 ] && say "(dry-run: nothing is pushed, built or installed)"

# --------------------------------------------------------------- 1. laptop prereqs --
step "1/6 laptop prerequisites"
MISSING=""
have() { command -v "$1" >/dev/null 2>&1; }
have adb || MISSING="$MISSING adb"
have ssh || MISSING="$MISSING ssh"
have tar || MISSING="$MISSING tar"
if [ "$SKIP_APK" -eq 0 ] && [ "$NO_BUILD" -eq 0 ]; then
  have pwsh || MISSING="$MISSING pwsh"
fi
[ -f "$PH" ] || die "$PH is missing — run install.sh from a full checkout of this repo" 1
[ -f "phone/.local/bin/jarvis-install" ] || die "phone/.local/bin/jarvis-install is missing from this checkout" 1
[ -f "phone/.local/bin/jarvisd.py" ] || die "phone/.local/bin/jarvisd.py is missing from this checkout" 1

if [ -n "$MISSING" ]; then
  say ""
  die "missing on this laptop:$MISSING
  adb   → https://developer.android.com/tools/releases/platform-tools (put it on PATH)
  ssh   → Windows: Settings → Optional features → OpenSSH Client
  pwsh  → only needed to build the APK; use --no-build or --skip-apk to skip that step" 1
fi
ok "adb / ssh / tar present"

# ------------------------------------------------------------------ 2. find the phone --
step "2/6 find the phone"
IP="${IP_ARG:-${PHONE_IP:-}}"
if [ -z "$IP" ] && [ -f "$HOME/.hermes-phone-ip" ]; then
  IP=$(cat "$HOME/.hermes-phone-ip" 2>/dev/null || true)
  [ -n "$IP" ] && say "  … cached address: $IP"
fi
if [ -z "$IP" ]; then
  # adb's wireless target is usually <ip>:5555 and needs no scan.
  IP=$(adb devices 2>/dev/null | awk '/:5555[[:space:]]+device$/ {split($1,a,":"); print a[1]; exit}' || true)
  [ -n "$IP" ] && say "  … from adb devices: $IP"
fi
if [ -z "$IP" ] && [ "$DRY" -eq 0 ]; then
  # Last resort: pc/ph.sh caches the address after scanning the local /24 for port 8022.
  bash "$PH" true >/dev/null 2>&1 || die "phone not reachable over ssh and not found on the LAN.
  Connect the phone to the same Wi-Fi, make sure Termux sshd runs (sshd on the phone),
  or pass --ip <address>." 2
  IP=$(cat "$HOME/.hermes-phone-ip" 2>/dev/null || true)
fi
[ -n "$IP" ] || die "no phone address (pass --ip <address> or set PHONE_IP)" 2
ok "phone: $IP"

# ------------------------------------------------------------------------ 3. reach it --
step "3/6 verify ssh + adb"
export PHONE_IP="$IP"
[ -n "$KEY_ARG" ] && export PHONE_KEY="$KEY_ARG"

if [ "$DRY" -eq 1 ]; then
  printf '  [dry-run] bash %s true   (ssh to %s:8022)\n' "$PH" "$IP"
else
  bash "$PH" 'echo "  ✓ ssh ok: $HOME"' ||
    die "ssh to u0_a309@$IP:8022 failed.
  • Is the phone on the same Wi-Fi?  • Did the IP drift? (--ip, or delete ~/.hermes-phone-ip)
  • On the phone: pgrep -x sshd || sshd
  • Key: --key <path> or PHONE_KEY=<path> (default: hermes-handoff/connect/id_phone)" 2
fi

if [ "$SKIP_APK" -eq 1 ]; then
  ok "adb not needed (--skip-apk)"
else
  run adb connect "$IP:5555" >/dev/null 2>&1 || true
  if [ "$DRY" -eq 0 ]; then
    STATE=$(adb -s "$IP:5555" get-state 2>/dev/null || true)
    [ "$STATE" = "device" ] ||
      die "adb cannot reach $IP:5555 (needed to install the APK).
  • On the phone enable Developer options → Wireless debugging, then: adb pair $IP:<port>
  • or run with --skip-apk and install jarvis.apk by hand.
  • Hermes Control's wake button / the fast path need this too." 4
    ok "adb: device"
  fi
fi

# -------------------------------------------------------------------- 4. push the tree --
step "4/6 push phone/ → phone:\$HOME"
# config.yaml is live state on the phone and is never pushed (same rule as pc/deploy-phone.sh).
if [ "$DRY" -eq 1 ]; then
  printf '  [dry-run] tar -C phone --exclude=./.hermes/config.yaml -czf - . | bash %s (extract in $HOME)\n' "$PH"
else
  tar -C phone --exclude=./.hermes/config.yaml -czf - . |
    bash "$PH" 'tar -xzf - -C "$HOME" && chmod +x "$HOME"/.local/bin/* 2>/dev/null; echo "  ✓ pushed"' ||
    die "pushing phone/ to the phone failed (ssh dropped? no space left on the phone?)" 3
  ok "phone/ pushed"
fi

# ------------------------------------------------------------------ 5. bootstrap phone --
step "5/6 bootstrap on the phone (jarvis-install)"
ARGS=""
[ -n "$PORT" ] && ARGS="--port $PORT"
if [ "$DRY" -eq 1 ]; then
  printf '  [dry-run] bash %s sh ~/.local/bin/jarvis-install %s\n' "$PH" "$ARGS"
else
  bash "$PH" "sh \"\$HOME/.local/bin/jarvis-install\" $ARGS" ||
    die "jarvis-install failed on the phone (see the output above).
  Re-run on the phone with more detail:  sh ~/.local/bin/jarvis-install --dry-run
  Log: ~/.hermes/jarvis.log" 3
fi

# -------------------------------------------------------------------------- 6. the APK --
step "6/6 console APK"
if [ "$SKIP_APK" -eq 1 ]; then
  ok "skipped (--skip-apk)"
elif [ "$NO_BUILD" -eq 1 ]; then
  [ -f "jarvis/dist/jarvis.apk" ] || die "jarvis/dist/jarvis.apk not found — build it once with: pwsh -File jarvis/build.ps1" 4
  run adb -s "$IP:5555" install -r jarvis/dist/jarvis.apk ||
    die "adb install failed — is the phone unlocked and adb authorized?" 4
  ok "jarvis.apk installed"
else
  run pwsh -File jarvis/build.ps1 -Install ||
    die "the APK build/install step failed (see the pwsh output above)" 4
  ok "jarvis.apk built and installed"
fi

# ------------------------------------------------------------------------ what next --
say ""
say "── کارهای بعدی ──"
say "۱) اپ جارویس را روی گوشی باز کن."
say "۲) در ویزارد اول‌بار: ارائه‌دهنده (پیش‌فرض River AI) + کلید API را وارد کن، «تست» را بزن."
say "۳) اولین پیام را بفرست؛ بالای صفحه باید «آماده» باشد."
say "۴) اگر گوشی ریبوت شد و اپ وصل نشد: اپ Termux:Boot را یک‌بار باز کن."
say ""
say "راهنمای کامل: docs/install.md · عیب‌یابی: docs/install.md#عیب‌یابی"
