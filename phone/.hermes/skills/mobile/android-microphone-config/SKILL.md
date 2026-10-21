---
name: android-microphone-config
description: Diagnose and fix Android mic input (Samsung) via Shizuku rish; verify by recording.
version: 1.0.0
platforms: [android]
---

# Android Microphone Configuration

Use when you need to change the default microphone input device on an Android device using Shizuku (rish), for example when the primary microphone is broken and you want to use the secondary microphone as default for all applications.

## Status
UNPROVEN on Samsung SM-A536E. The `audio_device_inventory` edit is written and read back correctly, but a real recording after the edit was still silent. Do not present it as a fix. Run the Verification steps first.

## Why it was tried
Android devices store microphone routing information in the `audio_device_inventory` setting under secure settings. This setting contains a pipe-separated list of audio devices, where each device specification includes fields indicating whether it's the default device.

## Steps

1. **Get current audio device inventory**
   ```bash
   RISH_APPLICATION_ID=com.termux rish -c 'settings get secure audio_device_inventory'
   ```

2. **Understand the format**
   The output is a string like:
   ```
   2,,1,0,0,-1,0|4,,1,0,0,8,0|7,00:13:04:85:46:BA,0,0,0,32,0|8,00:13:04:85:46:BA,1,0,0,128,0|...
   ```
   Each section (separated by `|`) represents an audio device:
   - First field: device type
   - Second field: address (often empty for built-in mics)
   - Third field: is_default (1 = default, 0 = not default)
   - Remaining fields: other device properties

3. **Identify microphone devices**
   Typically, the first two sections represent the primary and secondary microphones:
   - Section starting with `2,`: Primary microphone
   - Section starting with `4,`: Secondary microphone

4. **Modify to set secondary mic as default**
   - Set the third field (is_default) of the first section to `0`
   - Set the third field (is_default) of the second section to `1`
   - Keep all other sections unchanged

5. **Apply the new setting**
   ```bash
   RISH_APPLICATION_ID=com.termux rish -c "settings put secure audio_device_inventory 'YOUR_MODIFIED_STRING'"
   ```

6. **Verify the change**
   ```bash
   RISH_APPLICATION_ID=com.termux rish -c 'settings get secure audio_device_inventory'
   ```

## Example
If the current value is:
```
2,,1,0,0,-1,0|4,,1,0,0,8,0|...
```

Change it to:
```
2,,0,0,0,-1,0|4,,1,0,0,8,0|...
```

## Notes
- This change affects all applications that use the system default microphone
- A reboot may be required for some apps to recognize the change
- The exact device type numbers (2, 4, etc.) may vary by device model
- On Samsung devices, 2 and 4 are commonly used for the built-in microphones
- If you don't see immediate improvement, try identifying which sections correspond to microphones by looking for sections with empty address fields (second field)

## Verification (required before saying it works)
Never tell the user the mic is fixed based only on `settings get` returning the new value. Test with a real recording:

1. Grant record permission to the recording app (Termux:API example):
   `RISH_APPLICATION_ID=com.termux rish -c "pm grant com.termux.api android.permission.RECORD_AUDIO"`
2. Record 3 seconds with the default input:
   `termux-microphone-record -d 3 -f <file>` (writes to /storage/emulated/0/ and reports the path)
3. Measure the level with ffmpeg:
   `ffmpeg -hide_banner -i <file> -af volumedetect -f null - 2>&1 | grep -E "mean_volume|max_volume"`
   - Speech in a quiet room should be roughly -35 to -20 dB mean. Around -70 dB mean with max under -45 dB means silence (no usable input).
4. Telegram voice messages can be checked the same way with ffmpeg on the cached .ogg file.
5. `termux-microphone-record` writes to `/storage/emulated/0/TermuxAudioRecording_<time>.m4a`. Find the newest with `ls -t /storage/emulated/0/TermuxAudioRecording_*.m4a | head -1`. Without the RECORD_AUDIO grant it fails with "Please grant the following permission".

## Level numbers can mislead (lesson from a real session)
- A level of -30 to -45 dB mean can be pure noise. Mechanical noise (for example from a loose connector) can read like speech in volumedetect. A level alone does not prove usable speech.
- Ask the user to confirm by listening or by reporting whether the voice is intelligible. Trust their report over the number.
- Practical check: record once with the phone still, then once while the user gently moves the charging cable or the phone. If the level only rises when something moves, the fault is a connection, not a usable input.
- Restart changes the baseline. After a reboot, a recording was fully silent (max -91 dB) even though earlier ones were not. Re-run the baseline after every restart.

## Symptom pattern: bottom mic silent, noise when the charging cable moves
Seen on SM-A536E. The bottom mic sits next to the charging port, so a cable that makes the level jump only when pushed points to a loose or damaged flex/connector, not to a setting.
- Baseline after a restart was fully silent (max -91 dB), so restarts do not fix it.
- Do not claim a software fix. Recommend an authorised Samsung repair, and in the meantime use a wired/Bluetooth headset mic or a circle video note for Telegram.
- Confirm with the user by listening to the recording. A volumedetect level that only rises with cable movement is noise, not speech.

## Per-app mic choice (limits found)
- Android lets apps choose their own input (`AudioRecord.setPreferredDevice`). The system default setting does not force every app to use the same mic.
- Telegram voice notes did not follow the phone's default-mic choice in the Telegram bug tracker (reported by other users on other devices; not verified here).
- No non-root app was found that forces Telegram to use the top mic. Do not promise this to the user.
- Gemini (via the Google app) claimed Cherrygram has a hidden "Microphone audio source → CAMCORDER" debug option and listed apps that select the top mic. These were NOT verified: web search confirmed Cherrygram exists (uz.unnarsx.cherrygram, unofficial client) but not that option. Always check a Gemini claim against a real source before giving it to the user.
- Official Telegram's "Microphone for voice messages" setting only offers the built-in mic or a headset (per Telegram translation strings), not the top mic.
- Workarounds to offer instead: Telegram circle (video note) recording, recording with Samsung Voice Recorder and sharing the file, or a wired or Bluetooth headset with a mic.

## Diagnostic order (use before changing any setting)
1. Identify the device and mic ports: `getprop ro.product.model`, then `dumpsys media.audio_policy | grep -i "Built-In"`.
2. Record a baseline with the default input (steps 1-3 of Verification). Keep the original `audio_device_inventory` string in a file before any edit.
3. Check mute state: `dumpsys audio | grep -i "mic mute"`, and `settings get system mic_mode_enable`.
4. Check the app's permission and the recording-activity log: `appops get <pkg> RECORD_AUDIO`, and `dumpsys audio | grep -i "rec update"`. The log shows which app opened the mic and when.
5. Only if the baseline is silent and no mute/permission problem exists, consider the policy/mixer route (see references).

## Reading app screens without a vision tool
If `vision_analyze` is not available in the session, read the UI from XML instead:
- `RISH_APPLICATION_ID=com.termux rish -c "uiautomator dump /sdcard/x.xml"`, then `grep -o 'text="[^"]*"'` or `grep -o 'content-desc="[^"]*"'` on the file (copy it out with `cp` if needed).
- `dumpsys window | grep -m1 mCurrentFocus` tells you which activity is in the foreground.
- To tap a button, read the element's `bounds` and tap its center with `input tap x y`.

## Dialer test codes (`*#0283#`, `*#0289#`)
- Typing `*#0283#` in the Samsung dialer and pressing call only opens the loopback test when the phone is registered on a cellular network. On a device without a SIM it shows "Not registered on network" (checked on SM-A536E). Do not rely on these codes in that situation; use the ffmpeg baseline instead.
- To enter the code via `am start -a android.intent.action.DIAL -d 'tel:*%23%230283%23%23'`, the `%23` encoding is needed. Verify the number field with `uiautomator dump` before pressing call.

## Pitfalls (observed on Samsung SM-A536E, One UI 8, Android 16)
- Flipping the `is_default` field in `audio_device_inventory` did NOT change the recorded level. The value was written and read back correctly, yet recordings stayed silent (about -70 dB mean). Treat this step as unproven on Samsung.
- Setting the field to `1` for the wrong device can make things worse. Always save the original string first and restore it if the test fails.
- `settings put global airplane_mode_on 1` was run by mistake during debugging. Always check `settings get global airplane_mode_on` after any `settings put` in a debugging session.
- `am force-stop` on a running app can get interrupted by the tool. Check the process state again before reporting that it ran.
- Other `settings put system mic_mode_enable 1` changes had no effect. Restore it to `0` afterwards.
- The setting lists under `settings list` show no voice-input-source or mic-selection key on this device. The mic-selection data lives in the audio policy (see references).
- `pkg install tinymix` fails on Termux (no package). Do not assume mixer control is available.

## Device diagnostics
See `references/samsung-mic-diagnostics.md` for the hardware inventory, audio policy input ports, and the commands used to check them.