# Google on this key — probe results (2026-10-26, phone, free tier)

Tool: `~/.local/bin/google-probe all` (from this repo: `phone/.local/bin/google-probe`).
Tiny inputs, real API calls, key read from `~/.hermes/.env`. Never prints the key.

| Feature | Verdict | Evidence |
|---|---|---|
| Chat | **works** | `gemini-3.5-flash` → `ok`; `gemini-2.5-flash` also works (429-free today) |
| TTS | **works, Persian verified** | `gemini-3.8-flash-tts` → 72 894-byte audio response, 1 audio part. Full bot path (`/agent/test` → `tts` tool) wrote a 4.25 s mp3 in 52 s; feeding that mp3 back to `gemini-3.5-transcribe` returned «سلام، اما جان. این تست صدای گوگل است.» — equality with the input shows the Persian is intelligible ("اماد جان" came back as "اما جان": the voice is Kore, not the ElevenLabs Hooman voice). |
| STT | **works** | `gemini-3.5-transcribe` + real speech → `"sala"`; Persian mp3 → full sentence (above) |
| Image gen | **blocked** | every image model `429` "exceeded your current quota" |
| Music (Lyria) | **blocked** | `lyria-3-clip-preview`, `lyria-3.5` → `429` quota |
| Computer-use agent | **blocked** | `gemini-2.5-computer-use-preview-10-2025` → `429` quota |
| Antigravity agent | **needs `environment`** | Interactions API: "Missing required field 'environment'" (not a quota error) |
| Deep research | **blocked** | `429` "limit: 0 requests per day on Free Tier" for `deep-research-pro-preview` |

Notes
- All 60 models are *listed* on the key; listing is free and says nothing about quota.
  Image / Lyria / computer-use / deep-research are paid-tier only.
- `429` here is a project quota, not a bad key: chat, TTS and STT on the same key work.
- Afternoon (`Asia/Tehran`) is the free tier's busiest window; chat 503s were seen at
  14:00 the same day, and the gateway's fallback chain absorbs them.
- Hermes already exposes these: `tts` toolset enabled with `tts.provider: gemini`,
  `stt.provider: gemini` (`stt.language: fa`), `image_gen` toolset enabled but has no
  Google provider plugin yet (only fal / openai / krea / deepinfra / openrouter / xai).
