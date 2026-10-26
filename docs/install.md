# نصب جارویس

مخزن: **https://github.com/Emad211/jarvis**

جارویس دو تکه است: یک **اپ اندروید** (`ir.emad.jarvis`) و یک **دیمن روی همان گوشی**
(`jarvisd` روی `127.0.0.1:8780`). دیمن، خودِ عامل Hermes را داخل گوشی گرم نگه می‌دارد؛
هیچ سروری در کار نیست. یک صفحه معرفی: [`product.md`](product.md).

---

## ۱. پیش‌نیازها

| لازم | چرا | از کجا |
|---|---|---|
| گوشی اندروید ۸ به بالا (API 26+) | اپ و دیمن | — |
| **Termux از F‑Droid** | دیمن داخلش اجرا می‌شود؛ نسخهٔ Play Store قدیمی و رهاشده است | f-droid.org → Termux |
| اپ **Termux:Boot** (اختیاری) | شروع خودکار بعد از ریبوت | f-droid.org → Termux:Boot |
| اپ **Termux:API** (اختیاری) | باتری/کلیپ‌بورد/اعلان برای ابزارهای گوشی | f-droid.org → Termux:API |
| **یک کلید API** | مدل بدون کلید جواب نمی‌دهد | River AI (پیش‌فرض) یا Google AI Studio (جایگزین) — بخش ۵ |
| لپ‌تاپ با `adb` + `ssh` | فقط برای مسیر نصب از لپ‌تاپ (بخش ۲) | platform-tools + OpenSSH |

هیچ‌کدام از این‌ها پول نمی‌خواهد؛ حساب/کلید River AI را خودت می‌سازی (بخش ۵).

---

## ۲. مسیر کوتاه (لپ‌تاپ + گوشی، یک دستور)

گوشی را به همان Wi‑Fi لپ‌تاپ وصل کن، در Termux یک‌بار `sshd` را روشن بگذار، بعد:

```bash
git clone https://github.com/Emad211/jarvis ~/hermes-control && cd ~/hermes-control
bash install.sh
```

`install.sh` این شش کار را می‌کند و در هر مرحله، اگر پیش‌نیازی نبود، **صریح** می‌گوید چه کم است:

1. وجود `adb`، `ssh`، `tar` (و `pwsh` اگر باید APK ساخته شود) را یک‌جا چک می‌کند؛
2. آدرس گوشی را پیدا می‌کند: `--ip` ← `$PHONE_IP` ← کش `~/.hermes-phone-ip` ← `adb devices` ←
   اسکن LAN با `pc/ph.sh` (آی‌پی گوشی DHCP است و جابه‌جا می‌شود)؛
3. ssh (پورت ۸۰۲۲) و adb (پورت ۵۵۵۵) را قبل از هر تغییری تست می‌کند؛
4. `phone/` را به `$HOME` گوشی می‌فرستد (`config.yaml` هرگز پوش نمی‌شود)؛
5. روی گوشی `jarvis-install` را اجرا می‌کند (بخش ۳)؛
6. APK را می‌سازد و نصب می‌کند: `pwsh -File jarvis/build.ps1 -Install`.

گزینه‌ها:

```bash
bash install.sh --dry-run        # فقط برنامه را چاپ می‌کند، کاری نمی‌کند
bash install.sh --ip 10.0.0.7    # آدرس ثابت (اسکن را رد می‌کند)
bash install.sh --port 8781      # پورت دیگری برای دیمن
bash install.sh --skip-apk       # فقط سمت گوشی
bash install.sh --no-build       # همان jarvis/dist/jarvis.apk موجود را نصب کن
```

---

## ۳. مسیر فقط‌گوشی (بدون لپ‌تاپ)

اگر APK را از قبل داری (فایل به گوشی منتقل شده)، کل نصب روی خود گوشی انجام می‌شود:

```bash
pkg install -y git
git clone https://github.com/Emad211/jarvis ~/hermes-control
sh ~/hermes-control/phone/.local/bin/jarvis-install
```

`jarvis-install` هشت مرحله دارد و **هر مرحله اگر از قبل انجام شده باشد رد می‌شود**:

| مرحله | چه می‌کند |
|---|---|
| ۱ | چک می‌کند داخل Termux است و `python3` هست |
| ۲ | بسته‌های Termux را نصب می‌کند: `python clang make pkg-config openssl libffi openssh android-tools tmux jq git curl termux-api` — **فقط آن‌هایی که نیستند** (نصب‌شده‌ها آپدیت نمی‌شوند) |
| ۳ | Hermes Agent را در `$PREFIX/lib/hermes-agent` نصب/به‌روز می‌کند (git clone → venv → `pip install -e '.[termux]' -c constraints-termux.txt`)؛ اگر venv سالم باشد (`from run_agent import AIAgent`) دست نمی‌زند |
| ۴ | `jarvisd.py`, `gwctl.py`, `hermes-svc`, `jarvis_fast.py` و هلپرهای `phone-*` را به `~/.local/bin` می‌گذارد؛ فایل یکسان را دست نمی‌زند و فایل متفاوت را اول `.bak-<تاریخ>` می‌کند |
| ۵ | `~/.hermes/config.yaml` را **فقط اگر نباشد** می‌سازد (River AI پیش‌فرض، Google AI Studio جایگزین) و YAML را با venv خود گوشی اعتبارسنجی می‌کند |
| ۶ | `~/.hermes/.env` را **فقط اگر نباشد** خالی (mode 600) می‌سازد؛ هیچ کلیدی نمی‌پرسد و نمی‌نویسد |
| ۷ | سرویس‌های tmux را اجرا می‌کند: `gwctl` و `jarvis` (و `agy`/`router` اگر نصب باشند)، بعد `/health` را چک می‌کند |
| ۸ | `~/.termux/boot/start.sh` (یا `jarvis.sh` اگر `start.sh` مال دیگری باشد) را برای شروع بعد از ریبوت می‌نویسد |

گزینه‌ها: `--dry-run`، `--port N`، `--skip-packages`، `--skip-hermes`، `--update-hermes`،
`--recreate-venv`، `--skip-services`، `--no-boot`، `-h`.

**دوبار اجرا کردن بی‌خطر است.** اجرای دوم: هیچ بسته‌ای نصب نمی‌کند، `config.yaml`/`.env` را
باز نمی‌کند، سرویس سالم را ری‌استارت نمی‌کند و در پایان می‌گوید «بدون تغییر».
(اندازه‌گیری واقعی روی گوشی: اجرای اول ۱۱۴ ثانیه، اجرای دوم ۱۳ ثانیه.)

کدهای خروج: `0` موفق · `1` خطای استفاده · `2` مشکل محیط · `3` نوشتن فایل · `4` نصب Hermes.

---

## ۴. گرفتن APK

- **از لپ‌تاپ:** `bash install.sh` (یا `pwsh -File jarvis/build.ps1 -Install`) — خودش می‌سازد و نصب می‌کند.
- **فایل آماده:** `jarvis/dist/jarvis.apk` را به گوشی منتقل کن و از فایل‌منیجر بازش کن
  (اجازهٔ «نصب از منبع نامشخص» برای همان فایل‌منیجر). بسته: `ir.emad.jarvis`.
  نسخهٔ v0.1 با کلید دیباگ امضا شده، پس Play Protect ممکن است هشدار بدهد → «Install anyway».
- اپ `hermes-control.apk` (سوپروایزر قدیمی) برای جارویس **لازم نیست**.

---

## ۵. کلید و اولین اجرا

بعد از نصب، اپ را باز کن؛ ویزارد اول‌بار فقط دو چیز می‌خواهد: **ارائه‌دهنده** و **کلید API**.

| ارائه‌دهنده | کلید از | یادداشت |
|---|---|---|
| **River AI** (پیش‌فرض) | <https://docs.river.ai> | مدل `deepseek-ai/DeepSeek-V4.1-Flash-1017`؛ سریع‌ترین مدل تست‌شده روی همین گوشی |
| Google AI Studio (جایگزین) | <https://aistudio.google.com/apikey> | رایگان با سهمیه؛ **برای میکروفون/صدا هم همین کلید لازم است** |
| OpenRouter / AgentRouter | openrouter.ai/keys · agentrouter.org | اگر کلیدشان را داشته باشی |

- کلید **فقط** روی گوشی، در `~/.hermes/.env` (mode 600) می‌نشیند؛ در اپ ذخیره نمی‌شود و
  در هیچ لاگی چاپ نمی‌شود.
- بدون کلید هم اپ باز می‌شود و `/health` سالم است، ولی مدل جواب نمی‌دهد.
- ریزجزئیات هر صفحه از ویزارد: [`first-run.md`](first-run.md).

---

## ۶. چطور مطمئن شوم کار می‌کند

در Termux:

```bash
curl -s http://127.0.0.1:8780/health          # {"ok":true,...,"warm":true,...}
curl -s http://127.0.0.1:8780/setup/state     # بعد از کلید: "needs_setup": false
sh ~/.local/bin/hermes-svc list               # gwctl running · jarvis running
```

در اپ: بالای صفحه باید **«آماده»** بنشیند (یعنی `warm: true`)، بعد یک پیام بفرست.
پیامی که ابزار لازم دارد (مثلاً «یک فایل لیست کن») باید کارت ابزار + جواب را در همان نوبت نشان دهد.

زمان‌های اندازه‌گیری‌شده روی گوشی سازنده: اولین توکن در نوبت گرم ۱.۸–۴ ثانیه،
اولین پیام بعد از ری‌استارت ۸–۹ ثانیه.

---

## ۷. عیب‌یابی

| نشانه | علت | کار |
|---|---|---|
| `install.sh`: `phone not reachable over ssh` | آی‌پی گوشی عوض شده، یا sshd خاموش است | `rm ~/.hermes-phone-ip` بعد دوباره؛ یا `--ip <آدرس>`؛ روی گوشی: `pgrep -x sshd \|\| sshd` |
| `ssh ... Connection refused` | Termux بسته شده یا sshd نیست | Termux را باز کن، `sshd`، بعد دوباره |
| `adb cannot reach <ip>:5555` | adb بی‌سیم وصل نیست | گوشی: Developer options → Wireless debugging → `adb pair <ip>:<port>`؛ یا `bash install.sh --skip-apk` |
| اپ: «به سرویس وصل نیست» | دیمن بالا نیست یا پورت دیگری است | `sh ~/.local/bin/hermes-svc status jarvis` · `tail -n 30 ~/.hermes/jarvis.log` · آدرس اپ را چک کن |
| `needs_setup: true` / مدل جواب نمی‌دهد | کلید وارد نشده | ویزارد اپ → ارائه‌دهنده + کلید → «تست» |
| `status: 0` در تست کلید (network) | گوشی به ارائه‌دهنده نمی‌رسد | River AI و OpenRouter از ایران معمولاً VPN می‌خواهند؛ AgentRouter نه. اگر VPN روشن است، `127.0.0.1` را از تونل بیرون بگذار (split tunneling) |
| `status: 429` (quota) | سهمیهٔ رایگان تمام شده | مدل سبک‌تر (`gemini-3.5-flash-lite`) یا کلید/ارائه‌دهندهٔ دیگر؛ برای Google صورتحساب را در AI Studio وصل کن |
| `status: 401/403` (auth) | کلید غلط یا منقضی | کلید را دوباره بچسبان (ویزارد → کلید) |
| «دیمن تا ۲۵ ثانیه جواب نداد» | venv خراب، یا Hermes ناقص | `sh ~/.local/bin/jarvis-install --recreate-venv` · `tail -n 40 ~/.hermes/jarvis.log` |
| بعد از `pkg upgrade` همه‌چیز خراب شد | آپدیت major پایتون، venv قدیمی را نامعتبر می‌کند | `sh ~/.local/bin/jarvis-install --recreate-venv` |
| بعد از ریبوت اپ وصل نمی‌شود | Termux:Boot نصب/باز نشده یا اندروید Termux را کشته | `ls ~/.termux/boot/` · اپ Termux:Boot را یک‌بار باز کن · Termux را از بهینه‌سازی باتری خارج کن |
| میکروفون متن نمی‌دهد | کلید Google نیست (STT با Gemini کار می‌کند) | ویزارد → Google AI Studio → کلید `GEMINI_API_KEY` |
| اکشن‌های سریع گوشی کار نمی‌کنند («تلگرام را باز کن») | مسیر سریع به adb/Shizuku نیاز دارد | [`product.md`](product.md) → بخش «چه چیزی هنوز نیست»؛ یک‌بار Wireless debugging یا Shizuku |

لاگ‌ها: `~/.hermes/jarvis.log` (دیمن) · `~/.hermes/gwctl.log` · `~/.hermes/hermes-svc.log`.

---

## ۸. به‌روزرسانی

```bash
cd ~/hermes-control && git pull
bash install.sh            # از لپ‌تاپ
# یا فقط سمت گوشی:
sh ~/hermes-control/phone/.local/bin/jarvis-install
```
`config.yaml` و `.env` دست‌نخورده می‌مانند؛ فقط فایل‌های عوض‌شده جایگزین می‌شوند و نسخهٔ
قبلی هر کدام با پسوند `.bak-<تاریخ>` نگه داشته می‌شود.

---

## ۹. امنیت

- `jarvisd` فقط روی `127.0.0.1:8780` و `gwctl` فقط روی `127.0.0.1:8777` گوش می‌دهند؛
  هیچ چیز روی LAN یا اینترنت باز نیست.
- **در v0.1 هیچ احراز هویتی نیست.** هر اپی که روی همین گوشی نصب شود می‌تواند به دیمن وصل
  شود و ابزارها را اجرا کند. این تصمیم آگاهانه است (همان مدل اعتماد gwctl) و در M5 با
  توکن عوض می‌شود.
- این دو پورت را با `ssh -R`، `adb forward` یا تونل به بیرون باز **نکن**.
- کلیدها فقط در `~/.hermes/.env` با مود `600` هستند؛ `jarvis-install` و `install.sh` هیچ
  کلیدی نمی‌پرسند، نمی‌نویسند و چاپ نمی‌کنند.
- `~/.termux/termux.properties` را فقط اگر از دکمهٔ بیدارباش اپ قدیمی استفاده می‌کنی
  `allow-external-apps=true` بگذار: آن تنظیم به اپ‌های دیگر اجازهٔ اجرای دستور در Termux می‌دهد.
