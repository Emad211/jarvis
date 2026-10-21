#!/bin/bash
# Push the phone/ tree of this repo into the phone's Termux $HOME and restart services.
# config.yaml is never pushed: it is live state (edited via `hermes config` / gwctl); the copy
# in phone/.hermes is a reference snapshot pulled from the phone.
#   pc/deploy-phone.sh            push files + restart gwctl/agy (gateway untouched)
#   pc/deploy-phone.sh --all      push files + restart everything, including the gateway
set -euo pipefail
cd "$(dirname "$0")/.."
PH="pc/ph.sh"

tar -C phone --exclude=./.hermes/config.yaml -czf - . | bash "$PH" 'tar -xzf - -C "$HOME" && chmod +x $HOME/.local/bin/* $HOME/.termux/boot/*.sh'

bash "$PH" 'sh -s' <<EOF
mkdir -p \$HOME/old
for f in \$HOME/.local/bin/open-app \$HOME/antigravity_bridge.py \$HOME/check_custom.py \$HOME/check_status.sh \$HOME/.termux/boot/start-hermes.sh; do
  [ -e "\$f" ] && mv -f "\$f" \$HOME/old/
done
SVC=\$HOME/.local/bin/hermes-svc
\$SVC restart gwctl
\$SVC restart agy
$( [ "${1:-}" = "--all" ] && echo 'for s in $($SVC list | awk "{print \$1}"); do case $s in gw|gw-*|router) $SVC restart $s;; esac; done' )
\$SVC list
EOF
