# MeshLink multilingual AI emergency voice workflow

## Current pipeline

1. **Voice input** — the AI Voice tab can record locally or import an existing supported audio file. The source recording is retained in app-private storage and linked to a stable message ID.
2. **Prisma v2.5** — uploads the selected audio in Online Mode using the existing Gnani key and stores the original recognized transcript in Room. Empty/no-speech results do not erase the source recording.
3. **Evon v3.3** — the configured self-hosted OpenAI-compatible inference endpoint receives the transcript and message ID. A constrained extraction prompt requests a JSON emergency report. MeshLink parses and validates the JSON locally (type, severity, bounded strings, nullable location/count, bounded instruction/warning arrays). The transcription remains unchanged. The suggested response and analysis are marked AI-generated and require human review.
4. **Translation** — the model card establishes multilingual Indic comprehension/chat, but does not publish a dedicated translation benchmark or guarantee translation fidelity for emergency-critical text. MeshLink therefore does not silently treat Evon as a verified translation engine. A separate Google Cloud Translation Basic v2 adapter is provided for explicit user-requested translation.
5. **Timbre v2.5** — translated report/response text can be sent to the existing Timbre client with a voice from the published catalog for the selected target language. The generated WAV is saved separately and linked to the original message ID.

## Evon model and deployment boundary

Evon v3.3 is an open-weight 30B total / approximately 3.5B active hybrid Mamba-Transformer MoE model. It is not a hosted endpoint included with the Prisma/Timbre API key. The model card documents Linux serving using Transformers >= 5.3.0, vLLM >= 0.12.0 or SGLang and lists NVIDIA H100 80 GB, H200 and A100 hardware. It supports English, Hindi, Bengali, Telugu, Tamil, Marathi, Gujarati, Kannada, Malayalam, Odia and Punjabi. It is intended for multilingual chat, comprehension, extraction and reasoning; no dedicated translation reliability benchmark is documented. High-stakes outputs require human oversight.

Configure a real TLS-protected inference endpoint in root `local.properties`:

```properties
evon.base.url=https://YOUR_TLS_INFERENCE_HOST/v1
evon.model=gnani/gnani-evon-v3.3-30B-A3B
evon.api.key=YOUR_OPTIONAL_BEARER_TOKEN
```

The server must expose the OpenAI-compatible `POST /v1/chat/completions` route (configure `evon.base.url` with the `/v1` prefix). The model card examples use the repository model identifier `gnani/gnani-evon-v3.3-30B-A3B`; use a different model string only if your server is configured with a matching served alias. The Android app does not download or host Evon.

## Google Cloud Translation Basic v2 configuration

The Android integration follows Google's documented REST contract:

- `POST https://translation.googleapis.com/language/translate/v2`
- JSON body parameters are supplied as documented query fields: `q`, `target`, `format=text`, optional `source`; the API key is passed as the documented `key` query parameter.
- The response is parsed from `data.translations[0].translatedText`.

Enable **Cloud Translation API** and billing in a Google Cloud project. Create an API key restricted to the Cloud Translation API and, where practical, restrict its usage by application/backend policy. Add this to the root `local.properties`, preserving the existing `sdk.dir` and Gnani/Evon entries:

```properties
translation.api.key=YOUR_GOOGLE_CLOUD_TRANSLATION_API_KEY
```

The key is read by Gradle and injected into local debug builds only. It is not a production secret: a key embedded in an APK can be extracted. Public distribution should move translation behind an authenticated backend with server-side credentials. Release builds receive an empty key.

The translation UI currently exposes languages that have matching Timbre v2.5 voice catalog entries: English, Hindi, Bengali, Telugu, Tamil, Marathi, Gujarati, Kannada, Malayalam and Punjabi. Translation can support additional Google language codes, but speech generation is restricted to the published Timbre voice/language pairs. Odia is not offered as a speakable target because the current Timbre v2.5 catalog in this app has no Odia voice.

## Offline retry queue

Network failures during Prisma, Evon, translation or Timbre processing are stored in the Room `ai_workflow_queue`, keyed by the original message ID. It stores the local source-audio path and current stage so the original recording remains available. When a validated network becomes available in Online Mode, MeshLink retries eligible pending stages. The queue never participates in SOS delivery or local BLE message routing. Provider errors other than connectivity failures remain visible for user action.

## Safety

- Evon extraction and suggested instructions are AI-generated, not verified facts.
- The app does not contact emergency services, dispatch responders, or infer unmentioned facts.
- The user must review the report before sharing it over the local mesh.
- SOS, BLE text/voice transfer, relay and local recording remain independent of all hosted AI services.
