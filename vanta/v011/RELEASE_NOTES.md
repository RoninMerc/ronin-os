# Ronin Vanta Android 0.11.0

Release candidate goals:
- one conversation/composer for chat, code, images, video and Android app requests;
- automatic task-fit model routing on each request, with optional verified low-refusal preference;
- bounded technical model handover for unavailable models while preserving conversation state;
- no automatic provider-policy-refusal bypass;
- Forge source ZIP saved as soon as complete source exists;
- direct APK compilation of saved source through the configured Android build worker without requiring another LLM call;
- if the build worker is unavailable, preserve the full portable source ZIP rather than discarding the result;
- retain encrypted provider configuration and existing Vanta data across update installs.

This file is release metadata only. Build and device results are authoritative only after the Vanta 0.11 release gate passes.
