# اولین اجرا (first run) — چیزی که اپ باید پیاده کند

این فایل قراردادِ ویزارد اول‌بار اپ `ir.emad.jarvis` است: هر مرحله، رشته‌های فارسی،
و **دقیقاً** همان درخواست‌هایی که به `jarvisd` می‌فرستد.

قاعدهٔ ثابت معماری: **اپ فقط با `jarvisd` روی `127.0.0.1:8780` حرف می‌زند** (نه با `gwctl`).
هر چیزی که در بخش «needs daemon work» آمده، یعنی هنوز در دیمن نیست.

---

## ۰. فهرست نقطه‌های موجود (از `phone/.local/bin/jarvisd.py`)

این‌ها امروز واقعاً وجود دارند؛ اسم هیچ‌کدام را از خودت درنیاور.

| متد و مسیر | بدنهٔ درخواست | پاسخ |
|---|---|---|
| `GET /health` | — | `{ok, version, warm, page, sessions}` |
| `GET /catalog` | — | `{models:[{provider,model,label}], aliases:[…], current, toolsets:[…], presets:{fast:[…]}, reasoning:[…], chips:[…], personas:[…]}` |
| `POST /session/new` | `{}` | `{session:"<id>"}` |
| `GET /sessions` | — | `{sessions:[{id,title,turns,busy,updated,model}]}` |
| `GET /session/{sid}` | — | `{session,title,model,reasoning,busy,messages:[{role,text,ts,ms?}],seq}` · ۴۰۴ `{error}` |
| `POST /session/{sid}/turn` | `{text}` | `{ok:true,seq}` · `{ok:false,error:"empty"}` · ۴۰۹ `{ok:false,error:"busy"}` · ۴۰۴ |
| `POST /session/{sid}/cancel` | — | `{ok:true}` |
| `POST /session/{sid}/model` | `{model,provider}` | `{ok:true,model}` |
| `POST /session/{sid}/reasoning` | `{effort}` | `{ok:true,reasoning}` |
| `GET /session/{sid}/events?from=N` | — | `text/event-stream`؛ هر رویداد `id: <seq>` + `event: <kind>` + `data: {seq,kind,ts,data}` |
| `POST /ask/{ask_id}` | `{value}` | `{ok:bool}` |
| `POST /voice/in` | بدنهٔ خام WAV (۱۶ kHz) | `{ok:bool, text}` · `{ok:false,error:"empty"}` |
| `POST /voice/out` | `{text}` | `{ok:true, url:"/audio/jv_….mp3", seconds}` · `{ok:false,error}` |
| `GET /audio/{name}` | — | `audio/mpeg` |
| `POST /fast` | `{intent, args:{…}}` | نتیجهٔ `jarvis_fast.run` (بدون مدل، ~۱۰۰–۳۰۰ms) |
| `GET /media/{name}` | — | `image/png` (از `~/shots`) |
| `GET /` | — | `{name:"jarvisd", version, docs:"/docs"}` |

انواع رویداد SSE: `text.delta · thinking.delta · tool.start · tool.end · tool.progress ·
status · ask · turn.start · turn.end · error · usage`.

نکته: docstring خود `jarvisd.py` با کد کمی عقب است — `/fast` و `/media` در کد هستند و
در docstring نیامده‌اند. مرجع، همان decoratorها هستند.

---

## ۱. چک‌لیست ویزارد

هر مرحله: رشته‌های فارسی + فراخوانی + شرط عبور.

### مرحلهٔ ۰ — اتصال به دیمن

- **UI:** «در حال اتصال…» → «آماده» / «سرویس بالا نیست».
- اول یک فیلد کوچک: **پورت دیمن** (پیش‌فرض `8780`) تا آدرس پایه ساخته شود
  (`http://127.0.0.1:<port>`). این تنها چیزی است که از سرویس نمی‌آید، چون قبل از اتصال لازم است.
- فراخوانی: `GET /health` (تا ۵ بار، هر ۱ ثانیه).
  - `ok:true` و `warm:true` → «آماده» و برو مرحلهٔ ۱.
  - `ok:true` و `warm:false` → «دارد گرم می‌شود…» (همان صفحه، بدون خطا؛ جایگزین: `status` با `kind:"ttft"`).
  - خطای شبکه → صفحهٔ راهنما: «Termux را باز کن و بزن: `sh ~/.local/bin/hermes-svc start jarvis`» + دکمهٔ «تلاش دوباره».
- بعد از اتصال، `GET /setup/state` را صدا بزن (بخش ۲): اگر `needs_setup:false` بود، ویزارد را رد کن و مستقیم برو به چت.

### مرحلهٔ ۱ — انتخاب ارائه‌دهنده

- **UI:** «کدام ارائه‌دهندهٔ مدل؟» با ۴ گزینه و لینک گرفتن کلید:
  Google AI Studio / OpenRouter / Groq / AgentRouter.
- فراخوانی: `GET /setup/state` → آرایهٔ `providers[]` (شامل `has_key`, `key_url`, `models[]`, `voice`).
- قبل از این مرحله هیچ چیزی از کلید نمی‌پرسیم؛ اگر `has_key:true` بود، همان را پیش‌فرض انتخاب کن
  و اجازه بده کاربر رد کند («کلید قبلی نگه داشته شود»).

### مرحلهٔ ۲ — چسباندن کلید

- **UI:** «کلید API را بچسبان» + راهنما «کلید فقط روی همین گوشی، در `~/.hermes/.env` ذخیره می‌شود و
  در اپ نگه داشته نمی‌شود».
- فیلد را با `inputType=textPassword` بگذار؛ کلید را **نه** در لاگ، **نه** در `SharedPreferences`،
  **نه** در crash report ننویس. بعد از ارسال، فیلد را پاک کن.
- فراخوانی: `POST /setup/key` با `{provider, key}`.
- عبور: `ok:true`؛ طول برگشتی را نشان بده: «کلید ذخیره شد (۳۹ کاراکتر)».

### مرحلهٔ ۳ — دکمهٔ «تست»

- **UI:** دکمهٔ «تست کن» + نتیجه: «جواب داد در ۱.۲ ثانیه» / «سهمیه تمام است (429)» /
  «به شبکه نمی‌رسد — VPN را روشن کن».
- فراخوانی: `POST /setup/test` با `{provider, model}` (بدون `model` = مدل پیش‌فرض همان ارائه‌دهنده).
- نگاشت خطاها:
  - `ok:true` → برو بعدی.
  - `status:401/403` → «کلید پذیرفته نشد؛ دوباره بچسبان».
  - `status:429` → «سهمیهٔ رایگان تمام شده؛ مدل سبک‌تر یا ارائه‌دهندهٔ دیگر».
  - `status:0` → «شبکه/VPN».
  - `ok:false` بدون `status` → پیام `error` را نشان بده.

### مرحلهٔ ۴ — انتخاب مدل

- **UI:** «کدام مدل؟» — لیست از `GET /setup/state` → `providers[].models[]`؛ مدل پیش‌فرض از پیش انتخاب شده.
- فراخوانی ۱ (پیش‌فرض برای گفتگوهای بعدی): `POST /setup/model` با `{provider, model}`.
- فراخوانی ۲ (برای همین گفتگو، فقط اگر از پیشفرض فرق دارد): `GET /catalog` برای `presets.fast`
  و `POST /session/{sid}/model` با `{model, provider}` — بعد از `POST /session/new`.
- عبور: `ok:true`.

### مرحلهٔ ۵ — میکروفون

- مجوز `RECORD_AUDIO` را در همین مرحله بگیر (اپ امروز آن را موقع فشردن دکمهٔ میک می‌گیرد؛
  برای ویزارد بهتر است قبلش).
- **UI:** «میکروفون را امتحان کن» + دکمهٔ «۲ ثانیه ضبط کن» + «رد کردن».
- فراخوانی: `POST /voice/in` با بدنهٔ خام WAV (PCM 16-bit، ۱۶ kHz، mono — همان چیزی که
  `AudioRecord` امروز می‌دهد).
- عبور: `ok:true` و `text` غیرخالی؛ متن را نشان بده.
- `ok:false`: «صدا شنیده نشد» یا اگر کلید Gemini نیست: «برای تبدیل صدا به متن کلید Gemini لازم است»
  (دیمن فقط `GEMINI_API_KEY` یا `GOOGLE_AI_STUDIO_API_KEY` را می‌خواند). «رد کردن» همیشه فعال باشد.

### مرحلهٔ ۶ — پایان

- `POST /session/new` → `{session:"<id>"}`
- اتصال SSE: `GET /session/{sid}/events?from=0`
- `GET /health` برای نمایش «آماده».
- **UI:** «تمام. اولین پیام را بفرست.»
- ذخیرهٔ محلی: `base` = `http://127.0.0.1:<port>`، `session` = شناسه، `setup_done` = `true`،
  `model` = مدل انتخاب‌شده، `effort` = `none` (کلیدهای prefs امروز اپ: `base`, `session`, `model`, `effort`).

---

## ۲. needs daemon work

این چهار نقطه در `jarvisd.py` **وجود ندارند**. پیشنهاد شکل دقیقشان این است.

قاعده‌های مشترک:
- کلید هرگز در پاسخ برنمی‌گردد؛ فقط `has_key` و `key_length`.
- نوشتن در `~/.hermes/.env` اتمیک با مود `600` (temp + `os.replace` + `chmod 600`) و بقیهٔ خطوط
  دست‌نخورده؛ همان کاری که `gwctl.set_env` می‌کند.
- هر چهار نقطه بدون session هم کار کنند (ویزارد قبل از ساخت گفتگو صدا می‌زند).
- خطاها همیشه `{ok:false, error:"<کد ماشین‌خوان>", detail:"<متن کوتاه>"}`.

### ۲.۱ `GET /setup/state`

ورودی: ندارد. پاسخ (۲۰۰):

```json
{
  "ok": true,
  "port": 8780,
  "version": "0.1",
  "needs_setup": false,
  "config": {"exists": true, "provider": "google_ai_studio", "model": "gemini-3.5-flash"},
  "runtime": {"hermes": true, "fastapi": true, "warm": true, "stt": true},
  "providers": [
    {
      "id": "google_ai_studio",
      "label": "Google AI Studio (Gemini)",
      "key_env": "GOOGLE_AI_STUDIO_API_KEY",
      "key_url": "https://aistudio.google.com/apikey",
      "has_key": true,
      "key_length": 39,
      "voice": true,
      "models": [
        {"id": "gemini-3.5-flash", "label": "Gemini 3.5 Flash", "tools": true, "vision": true},
        {"id": "gemini-3.5-flash-lite", "label": "Gemini 3.5 Flash Lite", "tools": true, "vision": true}
      ]
    }
  ]
}
```

- `needs_setup` = هیچ کلیدی برای `config.provider` در `.env` نیست (یا `config.yaml` وجود ندارد).
- منبع مدل‌ها: همان منطق `catalog()` (کلیدهای `providers.<id>.models` در `config.yaml`)، ولی
  **برای همهٔ ارائه‌دهنده‌های شناخته‌شده**، نه فقط آن‌هایی که در config هستند — وگرنه ویزارد نمی‌تواند
  انتخاب ارائه‌دهنده بدهد.
- `has_key` از خواندن `.env` (مثل `read_env`) بدون برگرداندن مقدار.

### ۲.۲ `POST /setup/key`

درخواست: `{"provider": "google_ai_studio", "key": "AIza…"}`

پاسخ ۲۰۰:

```json
{"ok": true, "provider": "google_ai_studio", "key_env": "GOOGLE_AI_STUDIO_API_KEY",
 "key_length": 39, "saved_at": 1761431234.5, "agents_cleared": true}
```

خطاها:
- ۴۰۰ `{ok:false, error:"unknown_provider"}` — شناسهٔ ناشناخته.
- ۴۰۰ `{ok:false, error:"empty_key"}` / `{ok:false, error:"key_has_whitespace"}` / `{ok:false, error:"key_has_quotes"}`.
- ۴۰۳ `{ok:false, error:"key_env_not_allowed"}` — نام متغیر باید `*_API_KEY` یا `*_TOKEN` باشد
  (همان قاعدهٔ `gwctl.set_env`).
- ۵۰۰ `{ok:false, error:"env_write_failed", detail:"…"}`.

عوارض جانبی: کلید را trim کن، در `.env` بنویس (۶۰۰)، و `session.agent = None` برای همهٔ
نشست‌ها تا عامل گرم با کلید تازه بازساخته شود (`agents_cleared`).

### ۲.۳ `POST /setup/test`

درخواست: `{"provider": "google_ai_studio", "model": "gemini-3.5-flash", "prompt": "بگو pong"}`

پاسخ موفق (۲۰۰، حتی وقتی ارائه‌دهنده خطا داده باشد — خطای ارائه‌دهنده ≠ خطای دیمن):

```json
{"ok": true, "provider": "google_ai_studio", "model": "gemini-3.5-flash",
 "status": 200, "ms": 1180, "reply": "pong", "usage": {"total_tokens": 14}}
```

پاسخ وقتی ارائه‌دهنده رد کرد:

```json
{"ok": false, "provider": "google_ai_studio", "model": "gemini-3.5-flash",
 "status": 429, "ms": 890, "error": "quota", "detail": "RESOURCE_EXHAUSTED: …"}
```

- `status` عدد HTTP واقعی ارائه‌دهنده است؛ `status:0` = شبکه/دی‌ان‌اس (یعنی VPN خاموش است).
- `error` یکی از `quota` · `auth` · `network` · `bad_model` · `unknown_provider` · `no_key`.
- درخواست واقعی: یک `POST` کوچک به همان `base` ارائه‌دهنده با `max_tokens≈16` — همان کاری که
  `gwctl.test_model` می‌کند (فایل `phone/.local/bin/gwctl.py`، تابع `test_model`) و همین حالا
  روی `127.0.0.1:8777/model/test` جواب می‌دهد. **بهتر است در خود `jarvisd` پیاده شود**
  تا ویزارد به بالا بودن `gwctl` وابسته نباشد؛ اگر می‌خواهی تکرار نکنی، پروکسی به
  `http://127.0.0.1:8777/model/test` با تایم‌اوت ۳ ثانیه و `status:0` در صورت خطای شبکه.
- مدل‌های `anthropic_messages` (AgentRouter) باید با همان مسیر و هدرها صدا زده شوند
  (`api_mode` + `extra_headers` در config) — منطقش در `gwctl.test_model` هست.

### ۲.۴ `POST /setup/model`

درخواست: `{"provider": "openrouter", "model": "minimax/minimax-m3.1-flash:free"}`

پاسخ ۲۰۰:

```json
{"ok": true, "provider": "openrouter", "model": "minimax/minimax-m3.1-flash:free",
 "previous": {"provider": "google_ai_studio", "model": "gemini-3.5-flash"},
 "restart_required": false}
```

- این «مدل پیش‌فرض برای گفتگوهای بعدی» است (`model.provider` + `model.default` در `config.yaml`)،
  نه مدل یک نشست. مدل یک نشست همچنان `POST /session/{sid}/model` است.
- نوشتن با پشتیبان‌گیری: `config.yaml` → `hcontrol-backups/config-<زمان>.yaml` قبل از تغییر
  (همان الگوی `gwctl.set_fallback`).
- خطا: ۴۰۰ `{ok:false, error:"unknown_provider"|"unknown_model"}` · ۵۰۰ `{ok:false, error:"config_write_failed"}`.

### ۲.۵ (اختیاری، تصمیم با Lead) `POST /setup/apply`

اگر معلوم شد `AIAgent` کلید را در زمان import می‌خواند و `agents_cleared` کافی نیست، یک نقطه
برای «اعمال کامل» لازم می‌شود:

```json
// request
{"restart": true}
// 200
{"ok": true, "restarted": true, "via": "hermes-svc restart jarvis"}
```

پیاده‌سازی پیشنهادی: `subprocess.Popen(["sh", os.path.expanduser("~/.local/bin/hermes-svc"),
"restart", "jarvis"])` بعد از یک `sleep` کوتاه، و پاسخ را قبل از مرگ پروسه بفرست
(یا پاسخ `{"ok":true,"restarted":false,"hint":"apk را دوباره وصل کن"}` و اپ خودش صبر کند).
این نقطه **اختیاری** است؛ چهار نقطهٔ ۲.۱–۲.۴ اجباری‌اند.

---

## ۳. چیزهایی که اپ باید تضمین کند

- هیچ کلیدی در حافظهٔ دائمی اپ (`SharedPreferences`/فایل/بکاپ) نوشته نشود؛ مسیر کلید
  فقط `POST /setup/key` → `~/.hermes/.env` است.
- هیچ کلیدی در `Log.d`/crash report نرود؛ درخواست و پاسخ کلید را لاگ نکن.
- ویزارد باید از هر مرحله «رد کردن» داشته باشد؛ اپ باید بدون کلید هم باز شود و بشود بعداً از
  تنظیمات سراغش برگشت.
- بعد از پایان ویزارد، وضعیت `warm` در `GET /health` نمایش داده شود (مثل حالا) و اتصال SSE
  با `from=0` برقرار بماند؛ در قطع اتصال، همان مسیر `onStreamLost` فعلی دوباره `/health` بزند.
- اگر کاربر پورت را عوض کرد، آدرس پایه در prefs (`base`) به‌روز شود و ویزارد از همان استفاده کند.
