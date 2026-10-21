# Samsung mic diagnostics (SM-A536E, One UI 8, Android 16)

Session-specific findings. Re-run the commands on another device; the values will differ.

## Identity
- `ro.product.model` = SM-A536E, `ro.product.device` = a53x, `ro.board.platform` = universal8825, `ro.hardware` = s5e8825
- `ro.build.version.oneui` = 80000
- `ro.bootloader` = A536EXXSMGZE1

## Input ports (from `dumpsys media.audio_policy`)
- Built-In Mic (AUDIO_DEVICE_IN_BUILTIN_MIC, position bottom)
- Built-In Back Mic (AUDIO_DEVICE_IN_BACK_MIC)
- Built-In 2 Mic (AUDIO_DEVICE_IN_2MIC, both)
- Telephony Rx, Remote Submix In, BT SCO Headset Mic, USB Headset In, FM Tuner, Aux In (HDMI)
- Primary input mixport lists the built-in mics as sources. No BT or USB input was active.

## Config files
- `/vendor/etc/audio_policy_configuration.xml`: devicePorts for all input types
- `/vendor/etc/mixer_paths.xml`: mic paths `dev-main-mic`, `dev-2nd-mic`, `dev-3rd-mic`, `dev-dual-mic`, which set `DMIC1 Switch` and `DMIC2 Switch`. These are the real hardware routing controls.
- `/vendor/etc/mixer_gains.xml`, `/vendor/etc/audio_board_info.xml`: not yet examined.

## Commands used (all via rish)
- `settings get secure audio_device_inventory` returned 9 entries. The first two are `2,,...` (built-in) and `4,,...` (other built-in).
- `settings get system mic_mode_enable` = 0 (not muting)
- `dumpsys audio | grep -i "mic mute"` = all false (no hardware or software mute)
- `dumpsys media.audio_flinger` shows `Input device: 0 (AUDIO_DEVICE_NONE)` when idle. This is normal.
- `dumpsys audio | grep -i "rec update"` lists recording clients; Telegram (`org.telegram.messenger`) opened the mic with `src:MIC not silenced` during voice notes.

## Test results
- Termux:API recording (`termux-microphone-record`, 8 kHz mono AAC) gave mean -72 dB, peak sample abs 78 of 32768. Effectively no signal reached the recorder. Reproduced twice.
- Two Telegram voice notes (`/data/.../.hermes/cache/audio/*.ogg`): mean -70 and -72 dB, max -47 and -56 dB. Also silent.
- The `audio_device_inventory` change did not fix this.

## Symptom from the user (unresolved)
- The user reports the mic starts working after a phone call (the call path resets it), but Telegram voice notes stay silent until then. Not yet reproduced: the device has no SIM, so a real cellular call is impossible. A VoIP call (WhatsApp or Telegram video call) was proposed as the next test. Result unknown.

## Dialer codes
- `*#0283#` and `*#0289#` in the Samsung dialer show "Not registered on network" on this device (no SIM). Loopback test unavailable.

## Open questions for next time
- Whether DMIC1 or DMIC2 is physically dead. Check by toggling `mixer_paths.xml` controls with a real mixer tool. `tinymix` is not in Termux repos (`pkg install tinymix` fails). Try a sideloaded static binary or the Samsung `/vendor/bin` mixer if one exists.
- Whether the user's broken mic is mic1 (DMIC1, main) with mic2 (DMIC2) still working. Test each path separately before choosing a default.
- Whether a VoIP call changes the input route enough to fix recordings (repeat the ffmpeg baseline before and after).
