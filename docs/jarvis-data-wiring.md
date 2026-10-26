# Wiring contract: `Notif.java` + `Data.java` → `Bridge.java`

Written for the lead to paste into `Bridge.java`'s `switch` and `MainActivity`'s permission flow.
Nothing here is applied — I only own `Notif.java`, `Data.java`, `AndroidManifest.xml`.

## 1. Exact intents

`Bridge.act()` already has `activity` and `args`. Each row is one `case` in the existing switch.

| intent | args | success payload | where it lives |
|---|---|---|---|
| `notif_list` | `package?` (string), `since_id?` (long), `limit?` (int, ≤40) | `{ok, intent, items:[{id,package,app,title,text,time,ongoing}], count, since_id, active_count, last}` | `Notif.handle` |
| `notif_clear` | — | `{ok, intent, dismissed, kept}` | `Notif.handle` |
| `calendar_today` | — | `{ok, intent, items:[{id,title,begin,end,all_day,location,begin_iso,end_iso}], count, from, to, days}` | `Data.handle(activity, …)` |
| `calendar_range` | `days?` (int, 1..31, default 7) | same as above with `days` | `Data.handle` |
| `contacts_search` | `query` (string, **required**) | `{ok, intent, items:[{id,name}], count, query}` | `Data.handle` |
| `sms_recent` | `limit?` (int, ≤20, default 20) | `{ok, intent, items:[{id,from,text,date,date_iso,read}], count, limit}` | `Data.handle` |
| `apps_list` | `query?` (string) | `{ok, intent, items:[{package,label,activity}], count, query, truncated}` | `Data.handle` |

`since_id` semantics: pass back the `since_id` from the previous `notif_list` to get only what
arrived after it. `0`/absent = full list. It is a **monotonic counter**, not the system's
notification id, so it stays meaningful across reposts.

Failure shapes (all still HTTP 200 through `Bridge`'s existing `reply`):

| case | payload |
|---|---|
| listener not enabled | `{ok:false, error:"listener_off", needs:"notification_listener", items:[]}` |
| runtime permission missing | `{ok:false, error:"permission", needs:"android.permission.READ_CALENDAR" \| …READ_CONTACTS \| …READ_SMS, items:[], detail}` |
| empty result (allowed, nothing there) | `{ok:true, count:0, items:[]}` — never confusable with a denial |
| unknown intent | `{ok:false, error:"unknown_intent"}` |

Dispatch snippet (two lines each, drop-in):

```java
case "notif_list":
case "notif_clear":
    out = Notif.handle(intent, args);
    break;
case "calendar_today":
case "calendar_range":
case "contacts_search":
case "sms_recent":
case "apps_list":
    out = Data.handle(activity, intent, args);
    break;
```

Note `out` is already `{"ok":true,"intent":…}` before the switch; both `handle` methods
**overwrite** those keys via their own `put`, so either style works. Do not wrap them in the
`a11y == null` check that follows the switch — these intents do **not** need the accessibility
service, and `Bridge` currently sets `out.put("error","a11y_off")` after every branch. That
would mislabel a successful `apps_list` as `a11y_off`. Suggested guard:

```java
boolean needsA11y = !(intent.startsWith("notif_") || intent.startsWith("calendar_")
        || intent.equals("contacts_search") || intent.equals("sms_recent")
        || intent.equals("apps_list"));
if (a11y == null && needsA11y) {
    out.put("error", "a11y_off");
}
```

That is the one existing-code change I would ask for, and it is a behavioural fix, not cosmetic.

## 2. Exact permission names

| what | manifest | runtime request string |
|---|---|---|
| calendar | `android.permission.READ_CALENDAR` | `Manifest.permission.READ_CALENDAR` |
| contacts | `android.permission.READ_CONTACTS` | `Manifest.permission.READ_CONTACTS` |
| sms | `android.permission.READ_SMS` | `Manifest.permission.READ_SMS` |
| apps list | **none** | — (reads the launcher query only) |
| notifications | **none** (a special access) | — (Settings toggle, see below) |

Manifest already updated with all four lines, plus `QUERY_ALL_PACKAGES` (see §4).

These three are `dangerous` permissions, so `requestPermissions(...)` with a request code is the
right flow — same pattern already used for `RECORD_AUDIO` / `POST_NOTIFICATIONS` in
`MainActivity`. `MainActivity` has no permission plumbing in the file I read beyond
`askNotificationPermission()`, so this is new code on your side.

**Notifications are not a runtime permission** — there is no `requestPermissions` string for it.
`Notif.ready()` (`false` until the user enables it) is the only check, and the UI action must be:

```java
startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
```

Check readiness with `Notif.ready()`; `Notif.lastError()` returns `"listener_off"` after the user
turns it off and `""` once connected.

## 3. What the user sees when they deny (one line each)

| denied | what the app must show |
|---|---|
| `READ_CALENDAR` | "Jarvis can't see your calendar — grant Calendar access, or it can't answer «برنامه امروزم چیه»." |
| `READ_CONTACTS` | "Jarvis can't look up contacts — grant Contacts access, or it can't find «به امیرحسین زنگ بزن»." |
| `READ_SMS` | "Jarvis can't read messages — grant SMS access (read-only; it never sends or deletes)." |
| notification listener off | "Jarvis can't see notifications — turn on Notification access for Jarvis in Settings." |
| `apps_list` | nothing to deny — it always works. |

Because every failure carries `needs`, the app can drive the message from the JSON instead of
matching on intent names — that is why the field is on every error path, including a
`SecurityException` raised *after* the check (permission revoked mid-session).

## 4. Design notes worth knowing before you merge

- **Nothing is persisted.** `Notif` keeps a 40-entry in-memory ring and clears it in
  `onDestroy`. Notification text is the most sensitive data on the phone (2FA codes, bank
  alerts); writing it to a file would put it in reach of any backup or `run-as`. Consequence:
  the list is empty after the app is killed. If the agent wants history, it copies what it reads
  into its own store.
- **`QUERY_ALL_PACKAGES`** is in the manifest because Android 11+ package visibility would
  otherwise hide most apps from `queryIntentActivities`. It is a normal permission (no prompt),
  but Play Store policy treats it as sensitive. This app is self-installed, so it is fine; if it
  ever ships to Play, switch to a `<queries>` element with a launcher intent instead.
- **`Notif` cannot be tested without the toggle.** There is no way to fake a listener; on a
  fresh install `/act {"intent":"notif_list"}` returns `listener_off` until the user flips it,
  which is exactly the branch to exercise first.
- **`sms_recent` is read-only** by construction: only `Telephony.Sms.Inbox` is queried and no
  write/delete call exists anywhere in `Data`.
- **Contacts project only name + id.** No phone numbers or emails leave the phone from a search.

## 5. What I could NOT verify — read this before trusting the code

**I could not compile.** The shell tool on this host is dead for the whole session:

```
sandbox mode "workspace-workspace" is requested but no sandbox backend is usable on this host;
refusing to run the command unconfined. … Runner failure: windows-acl-run:
--temp is not an existing directory: C:\Users\EMADKA~1\AppData\Local\Temp\dsh-6BDE4S
```

That is a host-side sandbox temp-dir failure, not my command; retries and even `echo hi` fail
identically, and approval/escalation is disabled for my session, so I could not work around it.
So there is **no `javac` output to paste**, and the command you should run is:

```powershell
$AJ  = 'C:\Users\Public\Android\sdk\platforms\android-35\android.jar'
$JAVAC = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.16.8-hotspot\bin\javac.exe'
& $JAVAC -encoding UTF-8 --release 8 -Xlint:-options -classpath $AJ `
    -d jarvis\build\check jarvis\java\ir\emad\jarvis\Notif.java `
    jarvis\java\ir\emad\jarvis\Data.java
```

(The full `build.ps1` compiles all sources together, which is the real test; the above isolates
my two files. Both depend only on the Android SDK + `org.json`, which ships in `android.jar`.)

### The API calls I am least sure about, most risky first

1. **`CalendarContract.Instances.VISIBLE`** — I filter `VISIBLE=1`. The constant exists; I am
   confident of the name but not that every OEM provider accepts it as a *selection* argument
   (as opposed to a projection column). If it throws `IllegalArgumentException` on a device,
   drop the selection argument — `Data.handle` catches it and returns `{ok:false, error:
   "IllegalArgumentException"}`, so it degrades instead of crashing, but the feature would be
   dead. **Most likely thing to need a tweak.**
2. **`CalendarContract.Instances.EVENT_LOCATION`** — correct on API 35, but it is *not* in the
   older documents projection; on minSdk 26 it should still resolve. Low risk, javac will tell
   you instantly since it is a compile-time constant reference.
3. **`ContactsContract.CommonDataKinds.Contactables.CONTACT_ID`** — `Contactables` is the union
   view and exposes `DISPLAY_NAME` for sure; `CONTACT_ID` is inherited from `ContactsColumns`.
   If javac rejects it, substitute
   `ContactsContract.Contacts._ID` (the column is literally `_id` in both).
4. **`NotificationListenerService.cancelNotification(String key)`** — API 21+, fine for
   minSdk 26. I kept an `else` branch for the old 3-arg form behind an `SDK_INT >= 21` check that
   can never be false; harmless but you can delete it.
5. **`QUERY_ALL_PACKAGES`** — valid from API 30; on API 26 it is ignored (harmless).
6. `Context.checkSelfPermission` — needs API 23+; minSdk is 26, fine.

Everything else is plain `org.json` + `ContentResolver` + `PackageManager`, and the file
structure mirrors `A11y`/`Bridge` (static holder, `volatile live`, never-throws `handle`, `ok`
always set), so the risk is concentrated in the five column/constant names above.
