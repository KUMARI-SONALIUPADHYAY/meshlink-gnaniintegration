# Gnani Prisma and Timbre configuration

MeshLink uses two independent online API clients: `PrismaApiClient` for speech-to-text and `TimbreApiClient` for text-to-speech. Neither is part of BLE discovery, local chat, relay, or SOS. Offline emergency communication continues when the key is absent or the network is unavailable.

## Add your key locally (Windows / Android Studio)

1. Open the MeshLink project root — the folder containing `settings.gradle.kts`.
2. Open the existing root `local.properties` file. **Keep the existing `sdk.dir=...` line unchanged.**
3. Add this line on a new line, replacing the placeholder with your own key (do not add quotation marks):

   ```properties
   gnani.api.key=PASTE_YOUR_GNANI_KEY_HERE
   ```

4. Save `local.properties`. It is excluded by `.gitignore`; do not commit or share it.
5. In Android Studio choose **File → Sync Project with Gradle Files**, then **Build → Rebuild Project** (debug variant). Install the newly built debug app. A previously built APK does not pick up a newly added key. Release variants intentionally do not contain the Gnani credential.
6. Open MeshLink in **Online** mode with a validated internet connection. Open **AI Voice**. Record a short voice note, run Prisma transcription, then enter a short sentence and run Timbre synthesis. Play the generated WAV.

You do not need to send the key to anyone or paste it into Kotlin files. The build also accepts `GNANI_API_KEY` as a local environment variable or Gradle property, but `local.properties` is the simplest Android Studio workflow.

## Credential handling and security boundary

- Kotlin source contains no API key literal. The credential is read by Gradle from the ignored root `local.properties` (or a local environment/Gradle property).
- Both clients send the documented `X-API-Key-ID` header. The credential is never written to app logs or included in user-facing error text.
- `.gitignore` excludes `local.properties`, `.env` variants, `secrets.properties`, and common keystore files.
- **Important:** the current direct-to-provider prototype injects the local key into `BuildConfig` at build time. Anyone who obtains and inspects that APK can extract the key. `local.properties` prevents accidental source-control commits; it does not make a client-embedded secret cryptographically secret. Do not distribute this APK publicly with a privileged key.
- For production or public challenge distribution, put the Gnani key in a server-side secret manager and have an authenticated backend proxy call Prisma/Timbre. The Android app should receive only short-lived app/user authorization, never the Gnani key.

## Verified API contracts

### Prisma (speech-to-text)

- `POST https://api.vachana.ai/stt/v3`
- Header: `X-API-Key-ID: <your-api-key>`
- Multipart fields: `audio_file`, `language_code`; MeshLink sets `format=transcribe`.
- Success body is JSON and includes `success` and `transcript`.
- MeshLink accepts M4A/MP4, WAV, MP3, AAC, OGG, and FLAC file extensions and rejects unknown formats before upload. Provider limits and supported codec details can change; keep clips short.

### Timbre v2.5 (text-to-speech)

Verified against Gnani's official [Timbre REST reference](https://docs.gnani.ai/api/TTS/tts-inference) and [voice catalog](https://docs.gnani.ai/api/TTS/available-voices):

- Method and endpoint: `POST https://api.vachana.ai/api/v1/tts/inference`.
- Authentication: `X-API-Key-ID: <your-api-key>` (same locally configured key used by Prisma; the provider must enable TTS permission).
- Request: JSON with required `text`, `voice`, `model` (`timbre-v2.5`), `language`, `speed`, and `audio_config`.
- MeshLink requests WAV with `linear_pcm`, mono, 16-bit (`sample_width=2`), 48 kHz. The API returns raw binary audio, not a JSON transcript/envelope. The app checks the HTTP status, documented WAV content type, response size, and RIFF/WAVE signature.
- Timbre v2.5 supports Hindi (`hi-IN`), English (`en-IN`), Tamil (`ta-IN`), Telugu (`te-IN`), Kannada (`kn-IN`), Malayalam (`ml-IN`), Marathi (`mr-IN`), Punjabi (`pa-IN`), Bengali (`bn-IN`), Gujarati (`gu-IN`) and Hinglish (`hi-en`). Each v2.5 voice has a preferred language. MeshLink only offers catalogued voice/language pairs and rejects mismatches locally before upload.
- The app's 4,000-character text and 25 MB returned-audio limits are MeshLink safety guardrails, not published provider limits.
- Generated WAV files are saved in the app-private `files/generated_speech` directory and indexed in Room by the originating MeshLink message ID. They are not saved in the evictable cache.
- Playback supports play, pause, resume and replay using the existing native `MediaPlayer` wrapper.

The request shapes, authentication header, and response expectations were checked against Gnani's official API documentation and its published Prisma/Timbre API examples. See [Prisma REST reference](https://docs.gnani.ai/api/STT/speech-to-text), [Timbre REST reference](https://docs.gnani.ai/api/TTS/tts-inference), and [Gnani API documentation index](https://docs.gnani.ai/llms.txt).

## Error handling

MeshLink distinguishes missing credentials, invalid keys (HTTP 401), permission/credit errors (402/403), rate limits (429), unsupported media (413/415 and unknown local extensions), server failures (5xx), timeouts/network failures, and malformed responses. Error messages are deliberately bounded and do not include request headers or the API key.

## Offline behavior

Prisma and Timbre require internet access. When offline, AI voice actions are unavailable; BLE discovery, local text/voice messaging, relay, and SOS remain separate and available according to Android permissions and Bluetooth state.

## Evon model

Evon remains a separate server-hosted inference integration. It is not called from this Android app and no endpoint or credentials are fabricated. Integrate it only after a real inference host and authenticated backend contract are available.

## Prisma v2.5 voice-message transcription

MeshLink uses the documented REST file endpoint `POST https://api.vachana.ai/stt/v3` with the `X-API-Key-ID` header. The request is `multipart/form-data` with `audio_file`, `language_code`, and `format=transcribe`. MeshLink intentionally omits the `model` field because the official endpoint defaults to Prisma v2.5; explicitly selecting Prisma v3.0 would require a separate product decision. The request uses the stable MeshLink voice-message ID as `X-API-Request-ID` for correlation.

The REST endpoint accepts short clips up to 30 seconds and supports WAV, MP3, OGG, FLAC, AAC and M4A. MeshLink's recorder currently produces M4A/AAC and caps its own recording duration at 10 seconds. The app also applies a 25 MB local safety limit; this is an application guardrail, not a Gnani-published upload-size limit. Prisma's `transcript` response field is a string. An empty transcript is a valid no-speech result and is not treated as a transport failure.

Imported audio is copied into the app's private `files/voice_messages` directory. New recordings are also stored there rather than in the evictable cache. Prisma output is stored in the Room `voice_transcriptions` table keyed by the same logical message ID; it does not replace or delete the source audio asset. The database schema is upgraded from version 2 to 3 with a non-destructive table-creation migration.

The AI Voice screen is available for hosted requests only in Online Mode with validated internet. Local BLE messaging and SOS do not call Prisma and remain available offline. Full request/response and error contracts are documented by Gnani at https://docs.gnani.ai/api/STT/speech-to-text.

## Evon v3.3 self-hosted inference (optional)

Evon is an open-weight model, not a hosted REST service supplied by the Prisma/Timbre API key. MeshLink therefore does not invent a Gnani cloud endpoint. The Android client targets a user-provisioned vLLM/SGLang server exposing the OpenAI-compatible `/v1/chat/completions` route documented in Gnani's Evon model card. The app requires an HTTPS endpoint and leaves Evon unavailable when none is configured.

After deploying the model on supported Linux/NVIDIA infrastructure, add these optional entries to the root `local.properties` (preserve `sdk.dir` and `gnani.api.key`):

```properties
evon.base.url=https://YOUR_TLS_INFERENCE_HOST/v1
evon.model=gnani/gnani-evon-v3.3-30B-A3B
evon.api.key=YOUR_OPTIONAL_SERVER_BEARER_TOKEN
```

`evon.model` must match the model identifier accepted by your server. Gnani's model card examples use `gnani/gnani-evon-v3.3-30B-A3B`; use a custom served alias only when your server is configured for it. If your server does not require authentication, leave `evon.api.key` blank. If it does, MeshLink sends it as `Authorization: Bearer ...`. The token is injected only into a local debug build; release builds contain no Evon endpoint or token. Keep the debug APK private. For public distribution, use a server-side gateway and short-lived user authentication.

The current UI offers a concise multilingual emergency-message assistant. It is advisory only: it does not contact emergency services, establish facts, or make safety-critical decisions. The model card recommends human oversight for high-stakes use. The inference server must be provisioned separately; the Android app does not download or run the 30B model on-device. The model card lists Linux with NVIDIA H100 80 GB, H200 or A100-class hardware and vLLM ≥ 0.12.0 / SGLang as supported serving options.

## Timbre v2.5 message workflow

The AI Voice screen can use a Prisma transcript, an Evon response, or text pasted by the user (including text translated elsewhere). The selected text is synthesized by Timbre. If it belongs to the currently selected voice/emergency message, the generated WAV is linked to that same stable message ID; otherwise MeshLink creates a local generated-response ID. `generated_speech` Room records preserve the path, language, voice, model, and creation time. The source recording and Prisma transcript are not overwritten.

The app only enables hosted speech actions in Online Mode with a validated internet connection and configured Gnani key. Offline BLE, text/voice transfer, relay, and SOS paths do not call Timbre.

## Multilingual AI voice workflow and translation

See [MULTILINGUAL_WORKFLOW.md](MULTILINGUAL_WORKFLOW.md) for the Prisma → Evon → Google Cloud Translation → Timbre workflow, model deployment requirements, supported language boundary, retry behavior, and safety notes. To enable Google Cloud Translation locally, add `translation.api.key=...` to the ignored root `local.properties`. Evon still requires a separately deployed HTTPS vLLM/SGLang endpoint; the Gnani speech key does not provide hosted Evon inference.

Cloud synchronization and emergency coordination foundations are described in [CLOUD_SYNC_COORDINATION.md](CLOUD_SYNC_COORDINATION.md). A remote backend has not yet been configured, so queued cloud events are stored locally and are not uploaded.
