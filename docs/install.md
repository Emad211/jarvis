# نصب جارویس

مخزن: **https://github.com/Emad211/jarvis** (شاخهٔ `master`)

جارویس دو تکه است: یک **اپ اندروید** (`ir.emad.jarvis`) و یک **دیمن روی همان گوشی**
(`jarvisd` روی `127.0.0.1:8780`) که خودِ عامل Hermes را داخل گوشی گرم نگه می‌دارد.
هیچ سروری در کار نیست. معرفی یک‌صفحه‌ای: [`product.md`](product.md).

---

## ۱. چه چیزی خودکار است، چه چیزی دستِ خودت است

اسکریپت هر کاری را که از داخل Termux ممکن است انجام می‌دهد. سه چیز از داخل Termux
**ممکن نیست** — نصب اپ اندروید، دیالوگ جفت‌سازی adb، و کلیدِ روشن/خاموش تنظیمات.
برای همان‌ها یک جدول جدا گذاشته‌ایم؛ روی هم حدود **سه دقیقه** وقت می‌برد.

### خودکار (هیچ کاری لازم نیست)

| کار | دستور |
|---|---|
| نصب بسته‌های Termux (فقط آن‌هایی که نیستند) | `sh ~/.local/bin/jarvis-install` |
| نصب/به‌روزرسانی Hermes Agent + venv در `$PREFIX/lib/hermes-agent` | همان |
| گذاشتن `jarvisd`, `gwctl`, `hermes-svc`, `jarvis_fast`, `jarvis-doctor` در `~/.local/bin` | همان |
| `~/.hermes/config.yaml` (River AI پیش‌فرض، Google AI Studio جایگزین) — فقط اگر نباشد | همان |
| `~/.hermes/.env` خالی با مود ۶۰۰ — فقط اگر نباشد | همان |
| اجرای سرویس‌های tmux (`gwctl`, `jarvis`, `cron`) | همان |
| هوک شروع خودکار `~/.termux/boot/` (اگر اپ Termux:Boot نصب شده باشد) | همان |
| ساخت و نصب APK (اگر از لپ‌تاپ نصب می‌کنی) | `bash install.sh` |

### دستی — و دقیقاً چطور (~۳ دقیقه)

| کار | چرا دستی است | چطور |
|---|---|---|
| ۱. **Termux** از F‑Droid | از داخل چیزی که هنوز نصب نیست نمی‌شود نصب کرد | <https://f-droid.org/packages/com.termux/> |
| ۲. **Termux:Boot** (برای شروع خودکار بعد از ریبوت) | یک اپ اندروید است؛ اسکریپت نمی‌تواند APK نصب کند | <https://f-droid.org/packages/com.termux.boot/> — نصب کن و **یک‌بار بازش کن** (پوشهٔ `~/.termux/boot` آن‌جا ساخته می‌شود) |
| ۳. **Termux:API** (باتری/کلیپ‌بورد/اعلان) | همان | <https://f-droid.org/packages/com.termux.api/> — نصب و یک‌بار باز کردن |
| ۴. اجازهٔ «نصب از منبع نامشخص» | دیالوگ سیستم اندروید | موقع باز کردن `jarvis.apk` از فایل‌منیجر، اجازه بده و «Install anyway» را بزن |
| ۵. **کنترل گوشی**: جفت‌سازی adb | کد ۶ رقمی روی صفحه + Developer options؛ اسکریپت نه می‌تواند آن صفحه را باز کند نه کد را بخواند | بخش ۳ (یک‌بار، ~۱ دقیقه) |
| ۶. **Shizuku** (جایگزین راحت‌تر برای ۵) | اپ اندروید + یک Start | <https://f-droid.org/packages/moe.shizuku.privileged.api/> — بعد از نصب، داخل Shizuku «Start» را بزن (روش Wireless debugging را خودش نشان می‌دهد) |
| ۷. مستثنی‌کردن Termux از بهینه‌سازی باتری | toggle در تنظیمات اندروید | Settings → Apps → Termux → Battery → **Unrestricted** |
| ۸. کلید API | حساب کاربری است، نه فایل | River AI (پیش‌فرض) یا Google AI Studio — بخش ۵ |

اگر ۲، ۳، ۵/۶ را انجام ندهی، جارویس **کار می‌کند** ولی: بعد از ریبوت خودش بالا نمی‌آید،
باتری/کلیپ‌بورد/اعلان ندارد، و «اپ را باز کن / تپ کن / اسکرین‌شات بگیر» را انجام نمی‌دهد.
چت، صدا، فایل‌ها و کارهای زمان‌بندی‌شده مستقل از این‌ها کار می‌کنند.

---

## ۲. یک دستور (لپ‌تاپ + گوشی)

گوشی را به همان Wi‑Fi لپ‌تاپ وصل کن، در Termux یک‌بار `pkg install -y openssh && sshd` را
بزن، بعد روی لپ‌تاپ:

```bash
git clone https://github.com/Emad211/jarvis ~/hermes-control && cd ~/hermes-control
bash install.sh
```

`install.sh` این کارها را می‌کند و در هر مرحله، اگر پیش‌نیازی نبود، **صریح** می‌گوید چه کم است:

1. وجود `adb`، `ssh`، `tar` (و `pwsh` اگر باید APK ساخته شود) را یک‌جا چک می‌کند؛
2. آدرس گوشی را پیدا می‌کند: `--ip` ← `$PHONE_IP` ← کش `~/.hermes-phone-ip` ← `adb devices` ←
   اسکن LAN با `pc/ph.sh` (آی‌پی گوشی DHCP است و جابه‌جا می‌شود)؛
3. ssh (پورت ۸۰۲۲) و adb (پورت ۵۵۵۵) را **قبل از هر تغییری** تست می‌کند؛
4. `phone/` را به `$HOME` گوشی می‌فرستد (`config.yaml` هرگز پوش نمی‌شود)؛
5. روی گوشی `jarvis-install` را اجرا می‌کند و بعد `jarvis-doctor` را (که می‌گوید چه چیزی
   هنوز دستی مانده)؛
6. APK را می‌سازد و نصب می‌کند: `pwsh -File jarvis/build.ps1 -Install`.

```bash
bash install.sh --dry-run        # فقط برنامه را چاپ می‌کند، کاری نمی‌کند
bash install.sh --ip 10.0.0.7    # آدرس ثابت (اسکن را رد می‌کند)
bash install.sh --port 8781      # پورت دیگری برای دیمن
bash install.sh --skip-apk       # فقط سمت گوشی
bash install.sh --no-build       # همان jarvis/dist/jarvis.apk موجود را نصب کن
```

---

## ۳. کنترل گوشی: جفت‌سازی adb (یک‌بار، ~۱ دقیقه)

اگر این را انجام ندهی هیچ چیز نمی‌شکند؛ فقط اکشن‌های گوشی کند (از راه Shizuku، ~۳ ثانیه) یا
خاموش می‌مانند. دقیقاً همین:

```bash
# ۱) گوشی: Settings → About phone → روی «Build number» هفت بار بزن
# ۲) Settings → System → Developer options → Wireless debugging → روشن
# ۳) در همان صفحه: «Pair device with pairing code» → یک IP:PORT و یک کد ۶ رقمی نشان می‌دهد
#    در Termux (یا روی لپ‌تاپ) همان لحظه بزن:
adb pair 192.168.1.23:37129 123456
# ۴) برگرد به صفحهٔ «Wireless debugging»: بالای صفحه یک IP:PORT دیگر است
adb connect 192.168.1.23:39201
adb devices            # باید خروجی «device» بدهد
```

- این کار **یک‌بار** است؛ ولی اندروید پورت را با هر ریبوت/خاموش‌روشن عوض می‌کند، پس بعد از
  ریبوت ممکن است `adb connect` را تکرار کنی. `jarvis-doctor` هر بار می‌گوید وصل است یا نه.
- مسیر سریع جارویس امروز آدرس `127.0.0.1:5555` را می‌خواهد. اگر با Shizuku کار کنی،
  یک‌بار این را بزن تا adbd روی ۵۵۵۵ بنشیند و بعد از هر ریبوت هم `hermes-svc boot` خودش
  همین کار را می‌کند:
  ```bash
  rish -c "setprop service.adb.tcp.port 5555; stop adbd; start adbd"
  adb connect 127.0.0.1:5555
  ```
- **Shizuku** به‌جای همهٔ این‌ها: نصب از F‑Droid → داخلش Start → جارویس خودش `rish` را
  برمی‌دارد. هزینه‌اش سرعت است (هر اکشن ~۳ ثانیه، نه ~۹۲ میلی‌ثانیه).

---

## ۴. فقط با گوشی (بدون لپ‌تاپ)

اگر `jarvis.apk` را از قبل داری (فایل به گوشی منتقل شده):

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
| ۴ | `jarvisd.py`, `gwctl.py`, `hermes-svc`, `jarvis_fast.py`, `jarvis-doctor` و هلپرهای `phone-*` را به `~/.local/bin` می‌گذارد؛ فایل یکسان را دست نمی‌زند و فایل متفاوت را اول `.bak-<تاریخ>` می‌کند |
| ۵ | `~/.hermes/config.yaml` را **فقط اگر نباشد** می‌سازد و YAML را با venv خود گوشی اعتبارسنجی می‌کند |
| ۶ | `~/.hermes/.env` را **فقط اگر نباشد** خالی (mode 600) می‌سازد؛ هیچ کلیدی نمی‌پرسد و نمی‌نویسد |
| ۷ | سرویس‌های tmux را اجرا می‌کند: `gwctl` و `jarvis` (و `agy`/`router` اگر نصب باشند)، بعد `/health` را چک می‌کند |
| ۸ | `~/.termux/boot/start.sh` (یا `jarvis.sh` اگر `start.sh` مال دیگری باشد) را می‌نویسد |
| پایان | `jarvis-doctor` را اجرا می‌کند: چه چیزی آماده است و چه چیزی مانده |

گزینه‌ها: `--dry-run`، `--port N`، `--skip-packages`، `--skip-hermes`، `--update-hermes`،
`--recreate-venv`، `--skip-services`، `--no-boot`، `-h`.

**دوبار اجرا کردن بی‌خطر است.** اجرای دوم: هیچ بسته‌ای نصب نمی‌کند، `config.yaml`/`.env` را
باز نمی‌کند، سرویس سالم را ری‌استارت نمی‌کند و در پایان می‌گوید «بدون تغییر».
(اندازه‌گیری واقعی روی گوشی: اجرای اول ۱۱۴ ثانیه، اجرای دوم ۱۳ ثانیه.)

کدهای خروج: `0` موفق · `1` خطای استفاده · `2` مشکل محیط · `3` نوشتن فایل · `4` نصب Hermes.

---

## ۵. کلید و اولین اجرا

بعد از نصب، اپ را باز کن؛ ویزارد اول‌بار فقط دو چیز می‌خواهد: **ارائه‌دهنده** و **کلید API**.

| ارائه‌دهنده | کلید از | یادداشت |
|---|---|---|
| **River AI** (پیش‌فرض) | <https://docs.river.ai> | مدل `deepseek-ai/DeepSeek-V4.1-Flash-1017` |
| Google AI Studio (جایگزین) | <https://aistudio.google.com/apikey> | رایگان با سهمیه؛ **برای میکروفون/صدا هم لازم است** |
| AvalAI | <https://avalai.ir> | تنها ارائه‌دهنده‌ای که از شبکهٔ موبایل ایران **بدون VPN** جواب داد |
| OpenRouter | <https://openrouter.ai/keys> | مدل‌های `:free` |

- کلید **فقط** روی گوشی، در `~/.hermes/.env` (mode 600) می‌نشیند؛ در اپ ذخیره نمی‌شود و
  در هیچ لاگی چاپ نمی‌شود.
- بدون کلید هم اپ باز می‌شود و `/health` سالم است، ولی مدل جواب نمی‌دهد.
- ریزجزئیات هر صفحه از ویزارد: [`first-run.md`](first-run.md).

---

## ۶. «چه چیزی مانده؟» — `jarvis-doctor`

هر وقت خواستی بدانی کجای کاری:

```bash
sh ~/.local/bin/jarvis-doctor          # گزارش کامل، یک خط برای هر چیز
sh ~/.local/bin/jarvis-doctor --fix    # + تعمیرهای بی‌خطر (بستهٔ termux-api،
                                       #   سرویس خوابیده، تلاش adb connect)
```

به ترتیب چک می‌کند: خودِ Termux و `pkg` → Termux:Boot → Termux:API → کنترل گوشی
(adb، بعد rish/Shizuku) → کلید API → دیمن → cron → بهینه‌سازی باتری.
هر موردی که درست نباشد یک خط `!` با **دستور یا مسیر دقیق** می‌گیرد، و خط آخر یکی از این دو است:

```
آماده است — اپ جارویس را باز کن و پیام اول را بفرست.
این سه کار مانده: Termux:Boot، کنترل گوشی (adb یا Shizuku)، مستثنی‌کردن باتری
```

روی گوشی تازه **انتظار می‌رود** دو-سه مورد بمانَد؛ این شکست نصب نیست.

---

## ۷. چطور مطمئن شوم کار می‌کند

```bash
curl -s http://127.0.0.1:8780/health          # {"ok":true,...,"warm":true,...}
curl -s http://127.0.0.1:8780/setup/state     # بعد از کلید: "needs_setup": false
sh ~/.local/bin/hermes-svc list               # gwctl running · jarvis running · cron running
```

در اپ: بالای صفحه باید **«آماده»** بنشیند (یعنی `warm: true`)، بعد یک پیام بفرست.
پیامی که ابزار لازم دارد باید کارت ابزار + جواب را در همان نوبت نشان دهد.

زمان‌های اندازه‌گیری‌شده روی گوشی سازنده: اولین توکن در نوبت گرم ۱.۸–۴ ثانیه،
اولین پیام بعد از ری‌استارت ۸–۹ ثانیه.

---

## ۸. عیب‌یابی

| نشانه | علت | کار |
|---|---|---|
| `install.sh`: `phone not reachable over ssh` | آی‌پی گوشی عوض شده، یا sshd خاموش است | `rm ~/.hermes-phone-ip` بعد دوباره؛ یا `--ip <آدرس>`؛ روی گوشی: `pgrep -x sshd \|\| sshd` |
| `ssh ... Connection refused` | Termux بسته شده یا sshd نیست | Termux را باز کن، `sshd`، بعد دوباره |
| `adb cannot reach <ip>:5555` | adb بی‌سیم وصل نیست | بخش ۳ (`adb pair` → `adb connect`)؛ یا `bash install.sh --skip-apk` |
| `jarvis-doctor`: «کنترل گوشی … وصل نیست» | Wireless debugging خاموش است یا پورتش عوض شده | بخش ۳؛ یا Shizuku را Start کن |
| اپ: «به سرویس وصل نیست» | دیمن بالا نیست یا پورت دیگری است | `sh ~/.local/bin/hermes-svc status jarvis` · `tail -n 30 ~/.hermes/jarvis.log` · آدرس اپ را چک کن |
| `needs_setup: true` / مدل جواب نمی‌دهد | کلید وارد نشده | ویزارد اپ → ارائه‌دهنده + کلید → «تست» |
| `status: 0` در تست کلید (network) | گوشی به ارائه‌دهنده نمی‌رسد | River AI و OpenRouter از ایران معمولاً VPN می‌خواهند؛ AvalAI نه. اگر VPN روشن است، `127.0.0.1` را از تونل بیرون بگذار |
| `status: 429` (quota) | سهمیهٔ رایگان تمام شده | مدل سبک‌تر (`gemini-3.5-flash-lite`)، ارائه‌دهندهٔ دیگر، یا وصل‌کردن صورتحساب در AI Studio |
| `status: 401/403` (auth) | کلید غلط یا منقضی | کلید را دوباره بچسبان (ویزارد → کلید) |
| «دیمن تا ۲۵ ثانیه جواب نداد» | venv خراب، یا Hermes ناقص | `sh ~/.local/bin/jarvis-install --recreate-venv` · `tail -n 40 ~/.hermes/jarvis.log` |
| بعد از `pkg upgrade` همه‌چیز خراب شد | آپدیت major پایتون، venv قدیمی را نامعتبر می‌کند | `sh ~/.local/bin/jarvis-install --recreate-venv` |
| بعد از ریبوت اپ وصل نمی‌شود | Termux:Boot نصب/باز نشده یا اندروید Termux را کشته | `ls ~/.termux/boot/` · اپ Termux:Boot را یک‌بار باز کن · Termux را از بهینه‌سازی باتری خارج کن |
| میکروفون متن نمی‌دهد | کلید Google نیست (STT با Gemini کار می‌کند) | ویزارد → Google AI Studio → کلید `GEMINI_API_KEY` |
| کارهای زمان‌بندی‌شده اجرا نمی‌شوند | واحد `cron` خوابیده | `sh ~/.local/bin/hermes-svc start cron` · `hermes cron runs` |

لاگ‌ها: `~/.hermes/jarvis.log` (دیمن) · `~/.hermes/gwctl.log` · `~/.hermes/hermes-svc.log`.

---

## ۹. به‌روزرسانی

```bash
cd ~/hermes-control && git pull
bash install.sh            # از لپ‌تاپ
# یا فقط سمت گوشی:
sh ~/hermes-control/phone/.local/bin/jarvis-install
```
`config.yaml` و `.env` دست‌نخورده می‌مانند؛ فقط فایل‌های عوض‌شده جایگزین می‌شوند و نسخهٔ
قبلی هر کدام با پسوند `.bak-<تاریخ>` نگه داشته می‌شود.

---

## ۱۰. امنیت

- `jarvisd` فقط روی `127.0.0.1:8780` و `gwctl` فقط روی `127.0.0.1:8777` گوش می‌دهند؛
  هیچ چیز روی LAN یا اینترنت باز نیست.
- **در v0.1 هیچ احراز هویتی نیست.** هر اپی که روی همین گوشی نصب شود می‌تواند به دیمن وصل
  شود و ابزارها را اجرا کند. این تصمیم آگاهانه است و در M5 با توکن عوض می‌شود.
- این دو پورت را با `ssh -R`، `adb forward` یا تونل به بیرون باز **نکن**.
- کلیدها فقط در `~/.hermes/.env` با مود `600` هستند؛ `jarvis-install`، `install.sh` و
  `jarvis-doctor` هیچ کلیدی نمی‌پرسند، نمی‌نویسند و چاپ نمی‌کنند.
- `adb pair` یعنی به هر کسی که روی Wi‑Fi توست دسترسی شل بدهد؛ بعد از جفت‌سازی
  Wireless debugging را خاموش کن (جارویس فقط به `127.0.0.1` نیاز دارد).
- `~/.termux/termux.properties` را فقط اگر از دکمهٔ بیدارباش اپ قدیمی استفاده می‌کنی
  `allow-external-apps=true` بگذار: آن تنظیم به اپ‌های دیگر اجازهٔ اجرای دستور در Termux می‌دهد.
