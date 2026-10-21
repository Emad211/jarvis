---
name: android-caldav-cert
description: Samsung CA certs and CalDAV/Radicale TLS from Termux rish.
---

# Android + Samsung (One UI) automation: Certs, CalDAV, Radicale

Hard-won knowledge for driving Emad's Samsung phone (Android 16, One UI) from Termux
via `rish` + vision bridge. Read before touching Tasks.org/CalDAV or installing certs.

## Environment recap
- `rish` = `/data/data/com.termux/files/home/.local/bin/rish` (Shizuku shell, uid 2000).
- Always `export RISH_APPLICATION_ID=com.termux` first.
- Vision: POST base64 PNG to `http://127.0.0.1:8790/v1/chat/completions`
  (model `gemini-3.8-flash-low` fast / `-high` better but times out ~90s often).
- Default `python3` now symlinks to **3.14**; packages (radicale) live on **3.13**.
  Run as `python3.13` explicitly.

## CRITICAL Android security blockers (Android 9+ / One UI)
1. **Cleartext (http://) is blocked app-side.** Any app (Tasks.org) targeting SDK 24+
   refuses plain `http://127.0.0.1:...` with
   `CLEARTEXT communication to 127.0.0.1 not permitted by network security policy`
   — even to loopback. Fix = use HTTPS (TLS), not http.
2. **User-installed CAs are NOT trusted by modern apps by default.** Apps targeting
   API 24+ trust only the system store unless their manifest's network security config
   says otherwise. So simply installing your CA into Settings → user store is often
   NOT enough for arbitrary apps to trust your self-signed server cert.
   - **Tasks.org**: version 12.7+ (2022) added "Fix self-signed SSL certificates on
     Android 12+" — it DOES handle self-signed/CA in the **user store** across the
     DAVx5 and native CalDAV paths. So for Tasks.org specifically, installing the CA
     into the user store + enabling HTTPS works.
   - If an app still won't trust it, that app must bundle CA via network_security_config
     (can't inject without root) → prefer DAVx5 (supports self-signed/SNI explicitly)
     or a real certificate.
3. **Android 11+ cannot jump-straight to the CA install screen via intent.** Apps/ADB
   can only open generic Settings; the cert-install button is deep in manual menus.
   Automation must walk the UI taps itself (which is why this doc exists).

## Samsung One UI menu paths (from research + live tapping)
- **Install CA cert**: Settings → Security and privacy → (scroll) More security
  settings / Other security settings → Encryption and credentials / Install from
  device storage → **CA certificate** → confirm warning → file picker.
  - Exact Samsung wording: *Biometrics and security → Other security settings →
    Install from device storage → CA certificate*.
  - Older/stock (Pixel): *Security & privacy → More security settings → Encryption
    & credentials → Install a certificate*.
- **View installed user certs**: Security → ... → User certificates (shows entries
  under "Installed for VPN and apps").
- The **cert installer in the file-picker step** only lists certificate-type files
  (`.crt/.cer/.pem/.der/.p12/.pfx`) and only from the current folder — a `.pem` at the
  root of Internal storage may show as "No files" until a **MediaStore scan**:
  `am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///storage/emulated/0/<file>`.

## Generating a local TLS cert for Radicale (no internet, no fqdn)
```sh
export PATH="/data/data/com.termux/files/usr/bin:$PATH"
cd ~/.radicale/tls
openssl genrsa -out ca.key 2048
openssl req -x509 -new -nodes -key ca.key -sha256 -days 3650 -out ca.crt \
  -subj "/CN=Emad Local CA/O=EmadLocal/C=IR"
openssl genrsa -out server.key 2048
# san.cnf: CN=127.0.0.1, subjectAltName IP:127.0.0.1 + DNS:localhost
openssl req -new -key server.key -out server.csr -config san.cnf
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out server.crt -days 3650 -sha256 -extfile ext.cnf
```
`install_cert.sh` (rooted) alternative: compute `subject_hash_old`, drop into
`/system/etc/security/cacerts/` or APEX (`/apex/com.android.conscrypt/cacerts`) via
tmpfs+nsenter. NOT needed here (no root); user-store route is correct for Tasks.org.

## Radicale config for TLS
```ini
[server]
hosts = 127.0.0.1:5232
ssl = True
certificate = /data/.../.radicale/tls/server.crt
key = /data/.../.radicale/tls/server.key
```
Run with `python3.13 -m radicale` (NOT `python3` → now 3.14). Boot script
`~/.termux/boot/start-hermes.sh` must call `python3.13 -m radicale`.
Verify: TLS socket + PROPFIND over https returns 207; wrong pass returns 401.

## CalDAV in Tasks.org
- Setup: ⚙ App settings → Synchronization → Add account → CalDAV → URL / USER /
  PASSWORD / **Server type = Other** (for plain Radicale; don't pick a SaaS preset).
- Enter home-set URL (`https://127.0.0.1:5232/emad/`). Radicale needs explicit home set.
- Tasks.org supports Radicale (self-host row listed in docs).

## Copies between Termux and /sdcard
- `rish` shell (uid 2000) **cannot read `/data/data/com.termux/...`** (sandbox). `cp`
  from termux-home yields empty/0-byte files. **Write files to /sdcard from the Termux
  process itself** (`cp ~/.radicale/tls/ca.crt /storage/emulated/0/...`), then let
  apps/pickers see them.

## Pitfalls
- `input keyevent` only numeric codes (BACK=4, enter=66, keypad; notifications=83).
- Long-press = `input swipe X Y X Y 2000`.
- Samsung settings UI often has NO clickable/text bounds in uiautomator dump → use
  vision to find row coords.
- Vision `-high` frequently times out (~90s) on big images; prefer `-low`.
- VLM calls hit URL `http://127.0.0.1:8790/v1/chat/completions`, auth
  `Bearer antigravity-local`; extract image saved path pattern.