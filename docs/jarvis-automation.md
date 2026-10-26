# اتوماسیون: وقتی/آنگاه

«وقتی X شد، Y را انجام بده.» ماژول `jarvisd_automation` همین است و در دیمن اجرا می‌شود؛
اپ فقط با شش endpoint آن حرف می‌زند.

فایل: `phone/.local/bin/jarvisd_automation.py` · داده: `~/.hermes/jarvis/automations.json` ·
لاگ اجراها: `~/.hermes/jarvis/automation_log.jsonl`

---

## ۱. API

| متد | مسیر | بدنه | پاسخ |
|---|---|---|---|
| GET | `/automations` | — | `[{id, name, trigger, action, enabled, pending, note, last_run, last_status, last_ms, runs, created, cron_job, next_run}]` |
| POST | `/automations` | `{name, trigger, action, enabled?}` | `{ok, id, pending, note}` |
| PATCH | `/automations/{id}` | هر کدام از فیلدهای بالا | `{ok, pending, note}` |
| DELETE | `/automations/{id}` | — | `{ok}` (کار کرون و اسکریپتش هم پاک می‌شود) |
| POST | `/automations/{id}/run` | `{}` | نتیجهٔ همان اجرا: `{ok, id, trigger:"manual", ms, at, result:{…}}` |
| GET | `/automations/{id}/log?n=20` | — | `{ok, id, entries:[{at, trigger, action, ok, ms, result}]}` |

خطاها: `{ok:false, error, detail}` با ۴۰۰ (`bad_cron`, `bad_package`, `bad_trigger_kind`,
`bad_intent`, `bad_args`, `bad_prompt`, `bad_text`, `bad_id`) یا ۴۰۴ `not_found` یا
۴۰۹ `too_many`.

`pending: true` یعنی «این اتوماسیون تعریف شده و با دست اجرا می‌شود، ولی الان خودش
نماد اذیت نمیکند» — دلیلش همیشه در `note` هست. هیچ اتوماسیونی بیصدا مرده اعلام نمیشود.

---

## ۲. تریگرها

```jsonc
{"kind":"time",         "cron":"*/1 * * * *"}                       // هر دقیقه
{"kind":"app_open",     "package":"org.telegram.messenger"}
{"kind":"notification", "package":"com.whatsapp", "match":"سلام"}   // match اختیاری
{"kind":"manual"}                                                    // فقط با /run
```

| نوع | امروز کار میکند؟ |
|---|---|
| `time` | ✓ به یک کار `hermes cron` ترجمه میشود (پایین) |
| `app_open` | ✓ اگر adb لوکال (`127.0.0.1:5555`) یا Shizuku/rish جواب بدهد؛ وگرنه `pending` |
| `notification` | ✗ تا وقتی اپ `NotificationListenerService` بگیرد → همیشه `pending` |
| `manual` | ✓ |

## ۳. اکشنها

```jsonc
{"intent":"volume", "args":{"action":"down"}}     // هر ۱۶ intent در jarvis_fast.py
{"intent":"battery"}
{"kind":"agent_prompt", "prompt":"خلاصهٔ امروز را بگو"}
{"kind":"notify", "text":"آب را یادت نرود"}
```

- `direction` هم برای `volume` پذیرفته میشود (به `action` تبدیل میشود).
- `agent_prompt` یک نوبت کامل عامل با `hermes -z` است (۱۳–۱۲۰ ثانیه اندازهگیری شده،
  بودجه ۲۴۰ ثانیه). **محدودکردن ابزارها در آن نوبت امروز اجبار نمیشود** — پرامپت است
  که میگوید چه کند. اگر لازم شد، فلگ ابزار باید در همین ماژول اضافه شود.
- `notify` اول `termux-toast`، بعد `termux-notification`، و اگر هیچکدام نبود فقط در لاگ
  دیمن مینویسد (`channel: "log"`).

## ۴. زمانبندی: چرا کار کرون

یک اتوماسیون `time` = **یک** کار `hermes cron` که با `--no-agent --script` ساخته میشود.
اسکریپت (که خود ماژول مینویسد و بازنویسی میکند) فقط یک `POST` برمیدارد:

```
~/.hermes/jarvis/automation_<id>.sh  →  curl -X POST 127.0.0.1:8780/automations/<id>/run
```

یعنی زمانبند Hermes تصمیم میگیرد *کِی*، دیمن تصمیم میگیرد *چی*. سه فایده: هیچ
زمانبند دومی در پروژه نیست؛ کارها در استور خود Hermes (`~/.hermes/cron/jobs.json`)
هستند و بعد از ریبوت میمانند؛ و هر اجرا از یک مسیر واحد میگذرد (پس لاگ و شمارنده یکی است).

اگر نسخهٔ Hermes این فلگها را قبول نکند، ماژول سه شکل دستور را به ترتیب امتحان میکند و
در صورت شکست، اتوماسیون را با `pending: true` و متن خطا در `note` نگه میدارد — هرگز
بیصدا نمیگذارد. خروجی اسکریپتها (خطاهای curl) در
`~/.hermes/jarvis/automation_cron.log` جمع میشود.

## ۵. `app_open`: چرا poll، و چه چیزی لازم دارد

بریج دسترسپذیری اپ (`127.0.0.1:8791/act`) هیچ intent «چه اپی جلوست» ندارد و
`{"intent":"screen"}` یک دامپ UI است — برای polling هر ۵ ثانیه گران و ناخواناست. به
جای ساختن intent جدید در اپ (این دور مجاز نبود)، دیمن از خودِ پلتفرم میپرسد:

```
adb -s 127.0.0.1:5555 shell "dumpsys activity activities | grep -m1 mResumedActivity"
rish -c "dumpsys activity activities | grep -m1 mResumedActivity"        # Shizuku
```

همان دو مسیری که `jarvis_fast.py` از قبل رتبهبندی میکند. فاصلهٔ poll ۵ ثانیه،
دوبارهپرسی مسیر هر ۶۰ ثانیه، و فقط روی **تغییر** اپ جلو اجرا میشود (نه هر ۵ ثانیه).
هزینهٔ اندازهگیریشده: adb ~۹۰ms، rish ~۳s. اگر هیچکدام جواب ندهد، اتوماسیونهای
`app_open` با `pending: true` و این یادداشت لیست میشوند: «برای دیدن اپ بازشده به adb یا
Shizuku نیاز است (jarvis-doctor میگوید کدام)».
مسیر بعدی که این را کامل میکند: یک intent سبک `foreground` در بریج اپ، یا
`NotificationListenerService` برای تریگر `notification`.

## ۶. ماندگاری و ریاستارت

- تعریفها در `~/.hermes/jarvis/automations.json` هستند (اتمیک: temp + replace).
- در استارت، ترد `_reconcile` هر ۵ دقیقه چک میکند: اتوماسیون `time` فعالی که کار
  کرونش در استور Hermes نیست، دوباره ساخته میشود. اگر استور خوانده نشود، هیچ کاری
  نمیکند (تا کار تکراری نسازد).
- لاگ `automation_log.jsonl` وقتی از ۱ مگابایت بگذرد به ۱۰۰۰ خط آخر کوتاه میشود.
- سقف: ۵۰ اتوماسیون.

---

## ۷. سیمکشی (یک خط، مال Lead)

در `jarvisd.py` (حدود خط ۶۹۵) فقط نام ماژول به تاپل اضافه شود:

```python
for _mod_name in ("jarvisd_personas", "jarvisd_tools", "jarvisd_ops", "jarvisd_voice",
                  "jarvisd_research", "jarvisd_bots", "jarvisd_automation"):
```

ماژول import-safe است (نه فایل میخواند، نه subprocess، نه شبکه، نه ترد در زمان import).
دو ترد داخلیاش daemon هستند و خطاهای خودشان را میبلعند.

---

## ۸. تست دستی (کپی-پیست در Termux)

```sh
B=http://127.0.0.1:8780
J() { python3 -c 'import sys,json;d=json.load(sys.stdin);print(d if isinstance(d,list) else d.get("id",d))'; }

# ۱) زمان: هر دقیقه، باتری
A1=$(curl -s -X POST $B/automations -H 'Content-Type: application/json' -d \
 '{"name":"باتری هر دقیقه","trigger":{"kind":"time","cron":"*/1 * * * *"},"action":{"intent":"battery"}}' | J)
# ۲) دستی: صدای کمتر
A2=$(curl -s -X POST $B/automations -H 'Content-Type: application/json' -d \
 '{"name":"صدای کمتر","trigger":{"kind":"manual"},"action":{"intent":"volume","args":{"direction":"down"}}}' | J)
# ۳) یک نوبت عامل
A3=$(curl -s -X POST $B/automations -H 'Content-Type: application/json' -d \
 '{"name":"یک جمله","trigger":{"kind":"manual"},"action":{"kind":"agent_prompt","prompt":"فقط بنویس: سلام از اتوماسیون"}}' | J)

curl -s $B/automations | python3 -m json.tool
curl -s -X POST $B/automations/$A2/run | python3 -m json.tool
time curl -s -X POST $B/automations/$A3/run | python3 -m json.tool

sleep 130        # دو تیک کرون
curl -s "$B/automations/$A1/log?n=2" | python3 -m json.tool

sh ~/.local/bin/hermes-svc restart jarvis; sleep 10    # باید بعد از ریاستارت هم بمانند
curl -s $B/automations | python3 -m json.tool

for i in $A1 $A2 $A3; do curl -s -X DELETE $B/automations/$i; echo; done
curl -s $B/automations            # باید [] باشد
```

برای دیدن کار کرون از سمت Hermes: `hermes cron list --all` (نامها با پیشوند
`automation: ` میآیند) و خطاهای اسکریپت در `~/.hermes/jarvis/automation_cron.log`.
