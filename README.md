# G5 Companion

A standalone Android companion app for **DPVR G5** smart glasses. It replaces the vendor app: no account, no
telemetry, no cloud relay for your media. It talks to the glasses directly over Bluetooth LE and Wi-Fi Direct, and
lets you put the AI service of your choice behind the glasses' AI button.

Kotlin + Jetpack Compose · minSdk 31 · target/compile SDK 36 · built and tested on a Pixel 10 Pro.

> The G5 is **not** a HeyCyan/QCSDK device, even though it is often described as one. It runs DPVR's
> "Eyevue"-family firmware on a JieLi AC701N chip. See:
> - [docs/PROTOCOL.md](docs/PROTOCOL.md) for the wire protocol
> - [docs/FIRMWARE.md](docs/FIRMWARE.md) for on-device firmware decompilation, architecture, and voice subsystem findings
> - [docs/RCSP_AUTH.md](docs/RCSP_AUTH.md) for the JieLi RCSP signing/auth key reverse engineering procedure
> - [docs/VENDOR_APP.md](docs/VENDOR_APP.md) for analysis notes on the official DPVR app (`com.dpai.app`)

## Features

**Glasses tab**
- Scan and pair, then auto-reconnect from a foreground service (backoff from 5 s up to 60 s).
- Battery, charging state, model, and the count of files not yet synced.
- Photo, video and audio-recording controls.
- **Sync All Media**: pulls every file over Wi-Fi Direct into `DCIM/DPVR_G5` (photos and videos) and
  `Recordings/DPVR_G5` (audio). Each file is checked against the size the glasses report before it is saved.
  Files already on the phone are skipped.

**Gallery tab**
- Grid of synced media with a full-screen viewer. Swipe between items; videos and audio play in place.
- Viewer bar: back, share, delete.
- Long-press in the grid starts multi-select, with share, delete and cancel.
- Deleting only removes files from the phone, never from the glasses.

**AI & Settings tab**
- **AI service**: Gemini, Claude, OpenAI, or any OpenAI-compatible endpoint ("Custom": OpenRouter, Groq,
  Ollama/LM Studio on your LAN, …). Only one service is active at a time.
- **AI button / wake word**: takes a photo from the glasses, records your question from the glasses' mic, and reads the
  answer aloud.
  - Gemini receives the audio directly.
  - The other services get a transcript made on the phone, so the audio never leaves it.
- Every request includes the local date and time, plus your approximate location if you grant the permission. Gemini,
  Claude and OpenAI also have web search, so questions like "what's the weather?" work.
- **Gemini Live mode** (Gemini only): a continuous voice conversation with a photo from the glasses every few seconds.
- Typed questions, with or without a photo from the glasses.
- A shared system prompt, and history (stored on the phone only).
- Other settings:
  - Delete from glasses after sync (off by default).
  - Continuous video loop.
  - Firmware version and **Check for update**.
  - Theme (System / Light / Dark).
  - **Forget glasses**.

## Privacy

| Data | Where it goes |
|---|---|
| Photos, videos, recordings | Glasses → phone over Wi-Fi Direct. Never uploaded. |
| AI questions | Only to the service you selected, with your own API key. Includes the photo, the question (audio for Gemini, a transcript otherwise), time, and approximate location if granted. |
| API keys | Stored on the device, AES-GCM encrypted with a non-exportable Android Keystore key. |
| Firmware check | Only when you tap the button: firmware version + model code (`S3DPVR`) to DPVR's update server. No phone, MAC or account identifiers. |

## Comparison with the Official Vendor App (`com.dpai.app`)

G5 Companion was built from a clean-room reverse engineering of the official DPVR Android app
(`com.dpai.app` v1.1.9-gp, detailed in [docs/VENDOR_APP.md](docs/VENDOR_APP.md)). The table below summarizes
where the two take different design approaches; the vendor-app column reflects observations from that analysis.

| Dimension | G5 Companion | Official DPVR App (`com.dpai.app`) |
| :--- | :--- | :--- |
| **Privacy & telemetry** | Account-free and local: no login, tracking, analytics, or ads. | Requires account registration; sends device metadata (phone model, OS, MAC, device name) to vendor endpoints, some over plain HTTP. Includes ad SDKs. |
| **AI & voice backend** | Bring-your-own API key for Gemini (including full-duplex **Gemini Live** with opportunistic vision), Claude, OpenAI, or local/self-hosted LLMs (Ollama, LM Studio), plus offline on-device STT and **LiteRT-LM** intent routing. | Fixed to vendor cloud services with embedded credentials and proprietary engines (iFlytek AIKit). |
| **Media sync & storage** | Downloads are size-verified and saved to standard public directories (`DCIM/DPVR_G5`, `Recordings/DPVR_G5`); deleting from the glasses is opt-in. Native Compose gallery with pan/zoom. | Downloads into app-private cache and deletes files from the glasses automatically after transfer (`cmd=4003`). |
| **App footprint** | ~10–15 MB APK; native Android BLE, media, and audio APIs. | ~294 MB APK bundling Zego Express, libVLC, ffmpeg-kit, Firebase, iFlytek, and Qualcomm QNN/TFLite. |
| **Firmware & hardware safety** | Anonymous OTA version check only; intentionally does **not** flash over BLE, to avoid bricking hardware with no USB recovery path. | Attempts to push `.ufw` firmware over BLE (`0000ae00` RCSP) and Wi-Fi (`:5001`), which risks bricking on an interrupted transfer. |

## Building

Requires JDK 17 and Android SDK 36 (set `sdk.dir` in `local.properties`, which is ignored by git).

```sh
./gradlew :app:assembleDebug          # build
./gradlew :app:installDebug           # install on a connected phone (USB debugging on)
./gradlew :app:testDebugUnitTest      # unit tests
```

The release build is minified and, for now, signed with the debug key. Set up a real signing config before
distributing it.

## Using it

1. Open the app and grant Bluetooth, nearby devices, notifications, microphone, media and (optionally) location.
2. **Glasses → Scan for glasses**, then tap your G5 (for example "DPVR G5-0347"). Glasses already bonded to the phone are
   listed even if they aren't advertising.
3. **AI & Settings**: pick a service, open its settings, paste an API key and tap **Save**.
4. Press the AI button on the glasses or say the wake word, then ask your question.
5. **Glasses → Sync All Media** to pull media. Phone Wi-Fi must be on and the hotspot off. Accept the Wi-Fi Direct
   prompt if Android shows one.

## Code map

| File | Role |
|---|---|
| `Eyevue.kt` | G5 BLE frame format: build, parse/reassemble, AI-photo stream assembler |
| `Glasses.kt` | Native GATT client: scan, connect, reconnect, commands, status, events |
| `GlassesService.kt` | Foreground service; routes the AI button to the assistant |
| `MediaSync.kt` | Wi-Fi Direct + HTTP media download, verification, MediaStore, optional delete from glasses |
| `Assistant.kt` | AI button flow: photo + glasses-mic recording, context (time/place), history, text-to-speech |
| `Llm.kt` | Gemini (google-genai), Claude (Anthropic Java SDK), OpenAI (Responses API), OpenAI-compatible |
| `Speech.kt` | Tiered speech recognition (on-device, system recognizer, Whisper API fallback) |
| `GeminiLive.kt` | Gemini Live bidirectional WebSocket session with opportunistic POV vision & context compression |
| `GlassesCommandRouter.kt` | Screen-off BAL-compliant intent router with multi-tier hardware fallback (Tensor/GPU/CPU) |
| `IntentRuleStore.kt` | Custom intent rules and template management with URI/extra argument substitution |
| `ActionDispatcher.kt` | Dispatches actions, app launches, photo-sharing intents, and lockscreen feedback |
| `MusicAppManager.kt` | Music player discovery, package resolution, and audio query execution |
| `CompanionDeviceHelper.kt` | CompanionDeviceManager (CDM) association for background execution exemptions |
| `Firmware.kt` | Firmware update check against DPVR's server |
| `Prefs.kt` | Settings, providers, Keystore-encrypted API keys, theme |
| `OggOpus.kt` | Wraps raw Opus in Ogg, for `.opus` files from glasses that record them (the G5 records WAV) |
| `ui/` | Compose screens: Glasses, Gallery, AI & Settings |

## Status

Verified on hardware (DPVR G5, firmware Bluetooth 2.0.2 / camera 1.3.6):
- Connect and auto-reconnect.
- Battery and the unsynced-file count.
- Photo, video and audio controls.
- AI button.
- Sync of photos, videos and WAV recordings.
- Gallery.
- Firmware check.
- AI answers from OpenAI with the location context.

Not yet verified on hardware:
- **Delete from glasses after sync** (per-file HTTP delete, `cmd=4003`).
- **Continuous video loop** restarting when the glasses end a clip.
- On-device transcription for Claude, OpenAI and Custom.
- Gemini Live.

Firmware is only checked, never installed. Updates still go through the DPVR app, because a failed write can brick the
glasses.

## Credits

- The protocol was worked out from the DPVR app (`com.dpai.app`) and
  [FerSaiyan/Alternative-HeyCyan-App-and-SDK](https://github.com/FerSaiyan/Alternative-HeyCyan-App-and-SDK)
  (CyanBridge's Eyevue support).
- Vendor code and firmware are not included in this repository.
