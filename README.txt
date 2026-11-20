# FluxAI Android 2.0

FluxAI is an Android Kotlin + Jetpack Compose app for selecting a chat area, reviewing privacy before upload, and generating reply suggestions with Groq.

## Build APK with GitHub Actions

1. Upload the contents of this folder to a GitHub repository.
2. Open **Actions → Build FluxAI APK → Run workflow** (or push to `main` / `master`).
3. Wait for the workflow and download the `FluxAI-debug-apk` artifact.
4. Install the APK on your Android phone.

A Groq key is **not required at build time**. Open FluxAI → Settings, paste your Groq API key, save it, and use **API anahtarını test et**. The key is stored in the app's private SharedPreferences on that device. A key shipped inside an APK can be extracted; for a public release, use an authenticated backend/proxy with rate limits rather than distributing one shared key.

## Included in this project version

- In-app Groq API key entry and live connection test
- Cool, Samimi and Özgüvenli reply generation
- Cautious interest estimate and joke/question/serious-topic classification in the analysis text
- Flirt intensity and short/medium/long response settings
- Custom AI instructions and custom reply-style text
- Rewrite / improve action on generated replies
- Draggable floating FluxAI shortcut with quick-capture and mode-selection menu
- Last mode preference, conversation profile presets, history search and favorites
- Red/black, black and AMOLED theme options
- PIN lock and Android biometric unlock prompt
- Privacy confirmation before sending a selected crop; optional in-memory screenshot cleanup after analysis
- Turkish and several output-language options

## Important limitations

- The privacy confirmation is a reminder, not automatic OCR/name/face anonymization. Check the crop yourself before sending it to Groq.
- The app's history and favorites are local. Advanced named favorite folders and fully editable per-contact profiles are not yet implemented; the current person profiles are presets.
- Flirting and interest analysis are estimates, not reliable measurements of another person's feelings. The prompt asks the model to acknowledge uncertainty and avoid pressure or manipulation.
- Biometric unlock uses the Android biometric prompt; availability depends on the device's enrolled biometrics and lock-screen configuration.
- The notification listener remains a placeholder and does not automatically read or send replies.
- Screen capture requires Android's system consent. Overlay permission is required for the floating shortcut.

## Development

The workflow uses JDK 17, Gradle 8.11.1, Android Gradle Plugin 8.9.2, Kotlin 2.1.20 and Android SDK 35.
