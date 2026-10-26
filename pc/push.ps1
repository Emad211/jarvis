# Push this repo to GitHub from the Windows sandbox.
#   pwsh -File pc/push.ps1                      # push what is already committed
#   pwsh -File pc/push.ps1 -Message "text"      # commit everything, then push
#
# Two things make this non-obvious on this host:
#  * Windows' default TLS backend (schannel) cannot acquire a credential handle inside the
#    sandbox, so every git call needs `-c http.sslBackend=openssl`.
#  * git's askpass helper is a shell script and MSYS bash is broken here, so a credential
#    helper cannot answer the prompt. The token is injected through a temporary remote URL
#    and the clean URL is restored afterwards — it must never stay in .git/config.
param(
    [string]$Message = "",
    [string]$Repo = "Emad211/jarvis"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if ($Message) {
    git add -A
    if ((git status --porcelain)) {
        git -c core.hooksPath=.git/empty-hooks commit -m $Message | Out-Null
    }
}

$tokenFile = Join-Path $env:USERPROFILE ".hermes-secrets\github.token"
if (-not (Test-Path $tokenFile)) {
    throw "no token at $tokenFile — store one there (mode: this user only) before pushing"
}
$token = (Get-Content $tokenFile -Raw).Trim()
$clean = "https://github.com/$Repo.git"
$authed = "https://x-access-token:$token@github.com/$Repo.git"

git remote remove origin 2>$null
git remote add origin $clean
try {
    git remote set-url origin $authed
    git -c http.sslBackend=openssl push -u origin HEAD 2>&1 | Select-Object -Last 4
} finally {
    git remote set-url origin $clean
}
"pushed; remote restored to $((git remote get-url origin))"
