# اولین اجرا — ویزارد اپ جارویس

این فایل کاری است که اپ `ir.emad.jarvis` در اولین اجرا انجام می‌دهد: هر صفحه، رشته‌های
فارسی، و همان درخواست‌هایی که به `jarvisd` می‌زند. قرارداد کامل API:
[`jarvis-api.md`](jarvis-api.md). قاعدهٔ ثابت: **اپ فقط با `jarvisd` روی `127.0.0.1:8780`
حرف می‌زند** و هیچ کلیدی را در خودش نگه نمی‌دارد.

مسیر ویزارد (۶ صفحه، هر کدام «رد کردن» دارد):

```
اتصال → ارائه‌دهنده → کلید → تست → مدل → میکروفون → تمام
```

---

## ۱. اتصال

- **UI:** فیلد کوچک «پورت دیمن» (پیش‌فرض `8780`) + «در حال اتصال…».
  تنها چیزی که از سرویس نمی‌آید همین پورت است، چون قبل از اتصال لازم است.
  نصب‌کننده پورت را چاپ می‌کند؛ پیش‌فرض ۸۷۸۰.
- **فراخوانی:** `GET /health` — تا ۵ بار، هر ۱ ثانیه.
  - `{ok:true, warm:true}` → «آماده» → صفحهٔ ۲.
  - `{ok:true, warm:false}` → «دارد گرم می‌شود…» (نه خطا؛ چند ثانیه صبر کن).
  - خطای شبکه → «به سرویس وصل نیست» + راهنما:
    «در Termux بزن: `sh ~/.local/bin/hermes-svc start jarvis`» + دکمهٔ «تلاش دوباره».
- **بعد از اتصال:** `GET /setup/state` → اگر `needs_setup:false` بود، ویزارد را رد کن و
  مستقیم برو به چت.

## ۲. ارائه‌دهنده

- **UI:** «کدام ارائه‌دهنده؟» — کارت‌ها از `providers[]` ساخته می‌شوند؛ پیش‌فرض روی
  ردیفی که `default: true` دارد (River AI) می‌نشیند.
  هر کارت: `label`، `note`، دکمهٔ «گرفتن کلید» (`key_url`)، و اگر `has_key:true` بود
  «کلید ذخیره‌شده (${key_length} کاراکتر)» — **هیچ‌وقت خود کلید**.
- **فراخوانی:** `GET /setup/state`
  ```json
  {"ok":true,"port":8780,"needs_setup":true,
   "config":{"exists":true,"provider":"river","model":"deepseek-ai/DeepSeek-V4.1-Flash-1017"},
   "runtime":{"warm":true,"sessions":0},
   "providers":[{"id":"river","label":"River AI","key_env":"RIVER_API_KEY",
                 "key_url":"https://docs.river.ai","kind":"openai","voice":false,
                 "base_url":"https://api.river.ai/v1","default":true,
                 "note":"DeepSeek V4.1 Flash — پیش‌فرض جارویس","has_key":false,"key_length":0,
                 "models":[{"id":"deepseek-ai/DeepSeek-V4.1-Flash-1017","label":"DeepSeek V4.1 Flash 1017",
                            "tools":true,"vision":true}]}]}
  ```
- `voice:true` فقط روی Google AI Studio است؛ در صفحهٔ ۶ به کار می‌آید.

## ۳. کلید

- **UI:** «کلید API را بچسبان» + «کلید فقط روی همین گوشی در `~/.hermes/.env` ذخیره می‌شود
  و در اپ نمی‌ماند.» فیلد `textPassword`؛ بعد از ارسال پاک شود.
  کلید را در `SharedPreferences`، لاگ، یا crash report **ننویس**.
- **فراخوانی:** `POST /setup/key` با `{"provider":"river","key":"…"}`
- پاسخ‌ها:

  | کد | بدنه | پیام اپ |
  |---|---|---|
  | ۲۰۰ | `{ok:true, provider, key_env, key_length, agents_cleared}` | «ذخیره شد (${key_length} کاراکتر)» |
  | ۴۰۰ | `error:"unknown_provider"` | «ارائه‌دهندهٔ ناشناخته؛ صفحه را دوباره باز کن» |
  | ۴۰۰ | `error:"empty_key"` | «کلید خالی است» |
  | ۴۰۰ | `error:"key_has_whitespace"` | «کلید فاصله دارد؛ کامل و یک‌تکه بچسبان» |
  | ۴۰۰ | `error:"key_has_quotes"` | «کلید کوتیشن دارد؛ بدون `"` بچسبان» |
  | ۴۰۳ | `error:"key_env_not_allowed"` | «این ارائه‌دهنده پشتیبانی نمی‌شود» |
  | ۵۰۰ | `error:"env_write_failed"` | «نوشتن `.env` نشد؛ فضای ذخیره را چک کن» |

- `agents_cleared:true` یعنی عامل گرم دور ریخته شد و پیام بعدی کلید تازه را می‌خواند.

## ۴. تست

- **UI:** دکمهٔ «تست کن» + نتیجه در یک خط: «جواب داد در ۱.۲ ثانیه» /
  «سهمیه تمام است (429)» / «به شبکه نمی‌رسد — VPN را چک کن» / «کلید پذیرفته نشد».
- **فراخوانی:** `POST /setup/test` با `{"provider":"river","model":"…"}` (`model` اختیاری).
- پاسخ (همیشه ۲۰۰؛ خطای ارائه‌دهنده، خطای دیمن نیست):
  ```json
  {"id":"river","provider":"river","ok":true,"status":200,"ms":1180,
   "model":"deepseek-ai/DeepSeek-V4.1-Flash-1017","reply":"ok"}
  {"id":"river","provider":"river","ok":false,"status":429,"ms":890,
   "error":"quota","detail":"…","model":"…"}
  ```
- نگاشت `error` → پیام:

  | error | معنی | پیام |
  |---|---|---|
  | `network` (status 0) | به ارائه‌دهنده نرسید | «VPN/شبکه» — River و OpenRouter از ایران معمولاً VPN؛ AgentRouter نه |
  | `auth` (401/403) | کلید غلط | بازگشت به صفحهٔ ۳ |
  | `quota` (429) | سهمیه | «سهمیهٔ رایگان تمام شده؛ مدل سبک‌تر یا ارائه‌دهندهٔ دیگر» |
  | `bad_model` (404) | نام مدل | صفحهٔ ۵ |
  | `no_key` | کلید ذخیره نشده | بازگشت به صفحهٔ ۳ |
  | `no_model` | مدل خالی | صفحهٔ ۵ |

## ۵. مدل

- **UI:** «کدام مدل؟» — لیست از `providers[].models[]` همان ارائه‌دهنده؛ پیش‌فرض: اولین مدل.
- **فراخوانی ۱ (پیش‌فرض همهٔ گفتگوهای بعدی):** `POST /setup/model`
  ```json
  {"provider":"river","model":"deepseek-ai/DeepSeek-V4.1-Flash-1017"}
  // → {"ok":true,"provider":"river","model":"…","previous":{"provider":"…","model":"…"},
  //    "restart_required":false,"detail":"…"}
  ```
  این همان کاری است که `POST /providers/default` می‌کند (`{"id":"river","model":"…"}`) و
  داخلش `hermes config set model.provider/model.default` را می‌زند + از `config.yaml` بکاپ می‌گیرد.
- **فراخوانی ۲ (فقط برای همین گفتگو):** بعد از ساخت نشست:
  `POST /session/{sid}/model` با `{"model":"…","provider":"…"}`.

## ۶. میکروفون

- مجوز `RECORD_AUDIO` را همین‌جا بگیر (نه موقع اولین فشردن میک).
- **UI:** «میکروفون را امتحان کن» + «۲ ثانیه ضبط» + «رد کردن».
- **فراخوانی:** `POST /voice/in` با بدنهٔ خام WAV (PCM 16-bit، ۱۶ kHz، mono — همان
  `AudioRecord` فعلی) → `{"text":"…"}` (کد فعلی `{"ok":true,"text":"…"}` هم می‌دهد).
- عبور: `text` غیرخالی. اگر خالی/خطا بود:
  «صدا شنیده نشد» یا اگر کلید Google نیست: «تبدیل صدا به متن به کلید Google AI Studio نیاز دارد
  (STT روی Gemini است)» — و «رد کردن» همیشه فعال.

## ۷. تمام

1. `POST /session/new` → شناسهٔ گفتگو (`{"id":"…"}` در قرارداد؛ دیمن فعلی `{"session":"…"}` —
   هر دو کلید را بخوان).
2. `GET /session/{sid}/events?since=0` — یا در دیمن فعلی `?from=0` (جدول پایین).
3. `GET /health` → «آماده».
4. ذخیرهٔ محلی: `base` = `http://127.0.0.1:<port>`، `session`، `model`، `effort`، `setup_done`.
5. **UI:** «تمام. اولین پیام را بفرست.»

---

## اختلاف قرارداد و کد فعلی دیمن

این‌ها را یا دیمن باید همراستا کند یا اپ هر دو شکل را بخواند (تا امروز هر دو سمت باید
تحمل داشته باشد):

| مورد | قرارداد (`jarvis-api.md`) | کد فعلی `jarvisd.py` |
|---|---|---|
| ساخت نشست | `POST /session/new` → `{id}` | → `{session}` |
| رویدادها | `GET /session/{sid}/events?since=<seq>` | `?from=<seq>` |
| بدنهٔ turn | `POST /session/{sid}/turn {text}` → `{ok, queued}` | → `{ok, seq}` |
| مدل نشست | `POST /session/{sid}/model {model}` | `{model, provider}` را هم می‌پذیرد |
| مسیر سریع | `POST /fast {text}` | `{intent, args}` |
| صدا | `POST /voice/in` → `{text, ms, model}` | → `{ok, text}` |
| وضعیت صدا | `GET /voice/status` | **پیاده نشده** |
| پرسونا/کرون/اسکیل/MCP/پلاگین/سرویس/لاگ | در قرارداد هست | **پیاده نشده** (به‌جز `/setup/*` و `/providers*`) |

بقیهٔ مسیر ویزارد (`/setup/state`, `/setup/key`, `/setup/test`, `/setup/model`) **در کد
هست** و همان شکل قرارداد را دارد.

---

## چیزهایی که اپ باید تضمین کند

- هیچ کلیدی در حافظهٔ دائمی اپ (SharedPreferences/فایل/بکاپ) نوشته نشود؛ مسیر کلید فقط
  `POST /setup/key` → `~/.hermes/.env` روی گوشی است.
- کلید در `Log`، crash report یا analytics نرود؛ درخواست/پاسخ صفحهٔ ۳ لاگ نشود.
- هر صفحه «رد کردن» داشته باشد؛ اپ بدون کلید هم باز شود و از تنظیمات بشود دوباره ویزارد را آورد.
- پورت از تنظیمات قابل تغییر باشد و `base` بعد از تغییر ذخیره شود.
- بعد از پایان ویزارد، `warm` در `/health` نمایش داده شود و SSE با `from=0` باز بماند؛
  در قطع اتصال همان مسیر backoff فعلی دوباره `/health` بزند.
