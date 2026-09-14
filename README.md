<div align="center">

# 字幕眼鏡 Android Demo

即時語音轉文字、語者分離、字幕顯示與手勢辨識的 Android 原型。

![Android](https://img.shields.io/badge/Android-API%2028%2B-3DDC84?logo=android&logoColor=white)
![Java](https://img.shields.io/badge/Java-11-blue)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF?logo=kotlin&logoColor=white)
![Speechmatics](https://img.shields.io/badge/Speechmatics-Realtime-orange)
![MediaPipe](https://img.shields.io/badge/MediaPipe-Gesture%20recognition-4285F4)

![INMO Air 3 smart glasses](inmoair3.png)

[Overview](#overview) · [Features](#features) · [Quick start](#quick-start) · [Configuration](#configuration) · [Troubleshooting](#troubleshooting)

</div>

---

## Overview

This project is an Android demo for a smart-glasses-style subtitle workflow. It records microphone audio, streams it to Speechmatics for real-time transcription and speaker diarization, then displays the transcript with speaker labels and colors.

The app also integrates a separate MediaPipe gesture-recognition module. While recording, gesture detection can run in the background and optionally announce gesture results through Android Text-to-Speech.

### Recent fixes

- Runtime config injection: API key and region are now entered in Settings, no manual file editing
- Security hardening: cleartext traffic disabled, backup rules exclude voice data and API key
- Audio partial-read slicing: recorder now dispatches only the bytes actually written each iteration
- Gesture recognition: back-camera frames are no longer mirrored, ImageProxy double-close fixed

## Features

| Feature                 | What it does                                                               | Main files                                                            |
| ----------------------- | -------------------------------------------------------------------------- | --------------------------------------------------------------------- |
| Real-time transcription | Streams 16 kHz mono microphone audio to Speechmatics WebSocket             | `AudioRecorder.java`, `SpeechmaticsClient.java`                       |
| Speaker diarization     | Separates speakers and maps labels such as `S1`, `S2`, `S3`                | `SpeechmaticsClient.java`, `AppConfig.java`                           |
| Speaker enrollment      | Records local samples and calls Speechmatics speaker enrollment APIs       | `SpeakerEnrollmentActivity.java`, `SpeechmaticsEnrollmentClient.java` |
| Subtitle display        | Shows transcript text with speaker color separation                        | `MainActivity.java`, `TranscriptListAdapter.java`                     |
| Transcript management   | Saves, lists, renames, deletes and reloads transcript text files           | `TranscriptManager.java`, `SettingsActivity.java`                     |
| Gesture recognition     | Runs MediaPipe gesture recognition from the `:gesture` module              | `gesture/src/main/java/.../GestureBackgroundRunner.kt`                |
| Text-to-Speech          | Announces gesture output or other configured results                       | `MainActivity.java`, `SettingsActivity.java`                          |
| Settings screen         | Adjusts language, font size, TTS engine, speaker names and output behavior | `SettingsActivity.java`                                               |

## Architecture

```mermaid
flowchart LR
    Mic[Microphone] --> Recorder[AudioRecorder]
    Recorder --> Speechmatics[Speechmatics realtime WebSocket]
    Speechmatics --> Client[SpeechmaticsClient]
    Client --> UI[MainActivity subtitles]
    UI --> Transcripts[Local transcript files]

    Camera[Camera] --> Gesture[MediaPipe gesture module]
    Gesture --> Overlay[Gesture status overlay]
    Gesture --> TTS[Android Text-to-Speech]
```

## Tech stack

| Area                | Technology                                        |
| ------------------- | ------------------------------------------------- |
| App platform        | Android app module `:app`                         |
| Gesture module      | Android library module `:gesture`                 |
| Languages           | Java 11, Kotlin 2.1.0                             |
| Build system        | Gradle, Android Gradle Plugin 8.13.0              |
| Speech API          | Speechmatics real-time WebSocket and speaker APIs |
| Networking          | OkHttp 4.10.0                                     |
| JSON parsing        | Gson 2.10.1                                       |
| UI                  | AppCompat, Material Components, ConstraintLayout  |
| Camera and gestures | CameraX 1.4.2, MediaPipe Tasks Vision 0.10.29     |

## Project structure

```text
.
├── app/                         # Main Android app
│   └── src/main/
│       ├── java/hk/edu/hkmu/speakerdiarazationdemo/
│       └── res/raw/
│           └── config_default.json  # Tracked defaults (placeholder key only)
├── gesture/                     # MediaPipe gesture-recognition library module
│   └── src/main/assets/
│       └── gesture_recognizer.task
├── gradle/libs.versions.toml    # Version catalog
├── settings.gradle.kts          # Includes :app and :gesture
└── inmoair3.png                 # README image
```

## Requirements

- Android Studio with Android Gradle Plugin 8.13.0 support
- JDK 17 (required to run Gradle/AGP 8.x; the app compiles to Java 11 bytecode)
- Android SDK:
  - `compileSdk = 36`
  - `minSdk = 28`
  - `targetSdk = 28`
- A Speechmatics API key
- Android device or emulator with microphone permission
- Camera permission and camera hardware for gesture mode

## Configuration

The app uses a two-layer config model:

- **Defaults file** (`app/src/main/res/raw/config_default.json`): shipped with the repo, contains only a placeholder API key. Programmers may edit this to change default values without touching code.
- **Runtime settings** (in-app): end users enter their API key and select the server region in the app's Settings screen. These persist in app-private SharedPreferences and take effect on the next recording or enrollment.

The defaults file ships with only a placeholder:

```json
{
  "api_key": "YOUR_SPEECHMATICS_API_KEY",
  "language": "yue",
  "region": "us",
  "timeout_seconds": 1.5,
  "operating_point": "enhanced",
  "max_delay_mode": "flexible",
  "enable_partials": true,
  "diarization": "speaker",
  "max_speakers": 10,
  "speaker_sensitivity": 0.6,
  "end_of_utterance_silence_trigger": 0.8,
  "audio_filter_volume_threshold": 0,
  "speaker_names": {
    "S1": "s1",
    "S2": "s2",
    "S3": "s3",
    "S4": "s4",
    "S5": "s5"
  }
}
```

To set or change the API key and region, open the app and go to **設定 → Speechmatics 連線設定**. Enter your API key, toggle show/hide as needed, and select the region (自動（全球）/歐洲 (eu)/美國 (us)/澳洲 (au)). Changes apply on the next recording or enrollment.

**First run:** recording and enrollment are blocked with a toast until an API key is entered in Settings.

> **Warning:** Never put a real API key into `config_default.json` — that file is committed to the repository. The in-app Settings screen is the only place to enter a real key.

## Quick start

1. Open this repository in Android Studio.
2. Run Gradle Sync.
3. Build and install the app from Android Studio, or run:

```powershell
.\gradlew.bat :app:assembleDebug
```

On macOS or Linux:

```bash
./gradlew :app:assembleDebug
```

On first launch, grant microphone permission. Grant camera permission only if you want to use gesture recognition.

If Android Studio opens the wrong module, select the `app` run configuration before installing the demo.

## Common commands

| Task                   | Windows command                      |
| ---------------------- | ------------------------------------ |
| Build debug APK        | `.\gradlew.bat :app:assembleDebug`   |
| Run unit tests         | `.\gradlew.bat test`                 |
| Run lint               | `.\gradlew.bat lint`                 |
| Run Gradle checks      | `.\gradlew.bat check`                |
| Run instrumented tests | `.\gradlew.bat connectedAndroidTest` |

`connectedAndroidTest` requires a connected Android device or running emulator.

## Usage notes

- The main screen records audio and displays live subtitles.
- The settings screen controls language, font size, TTS engine, speaker setup and transcript management.
- Speaker enrollment records sample audio locally before calling the Speechmatics speaker API.
- Saved transcripts are stored in the app-private files directory.
- Gesture recognition uses `gesture/src/main/assets/gesture_recognizer.task`.

## Troubleshooting

| Problem                            | Check                                                                         |
| ---------------------------------- | ----------------------------------------------------------------------------- |
| App shows 請先於設定輸入 API 金鑰  | Enter your Speechmatics API key in Settings → Speechmatics 連線設定.          |
| Speechmatics connection fails      | Check the API key in Settings, the selected region, and network connectivity. |
| No microphone input                | Grant microphone permission and test on a device with microphone hardware.    |
| Gesture recognition does not start | Grant camera permission and confirm the device has an available camera.       |
| TTS does not speak                 | Check Android Text-to-Speech settings and installed voice data.               |
| Build cannot find SDK              | Open Android Studio SDK Manager and install API 36 plus required build tools. |

## Security and privacy notes

- Microphone audio is streamed to Speechmatics for transcription.
- Speaker enrollment audio and transcripts are handled locally by the app.
- The API key is entered in-app and stored in app-private SharedPreferences, excluded from cloud backup and device transfer. `config_default.json` ships only a placeholder.
- Cleartext traffic is disabled and backup rules exclude voice samples, transcripts, and speaker data.

## CI/CD

Two GitHub Actions workflows run automatically:

| Workflow  | Trigger             | What it does                                                                              |
| --------- | ------------------- | ----------------------------------------------------------------------------------------- |
| `CI`      | push to `main`, PRs | Builds the debug APK, runs lint, uploads the APK as a workflow artifact                   |
| `Release` | pushing a tag `v*`  | Builds a signed release APK and attaches it to a GitHub Release with auto-generated notes |

### Releasing

```bash
git tag v1.0.1
git push origin v1.0.1
```

The release workflow builds `assembleRelease` with the version injected from the tag (`v1.0.1` -> `versionName 1.0.1`), verifies the APK signature, and publishes the release.

### Signing setup (one-time)

The release keystore is committed at `app/release.keystore`, but it is **password-protected** and the password never touches Git:

1. Generate the keystore and a strong passphrase locally:

```bash
# Generate a 512-bit random passphrase (paste it into the secret below, do not commit it)
openssl rand -base64 64 | tr -d '\n'

# Generate the keystore (answer the prompts; keep a copy of the keystore + passphrase offline)
keytool -genkeypair -v -keystore app/release.keystore \
  -alias speakerdemo -keyalg RSA -keysize 2048 -validity 10000
```

2. Store the credentials as GitHub Actions secrets (Settings -> Secrets and variables -> Actions):

| Secret              | Value                                       |
| ------------------- | ------------------------------------------- |
| `KEYSTORE_PASSWORD` | the generated passphrase                    |
| `KEY_ALIAS`         | `speakerdemo` (or whatever alias you chose) |

3. Commit the password-protected keystore:

```bash
git add app/release.keystore
git commit -m "Add project release keystore"
```

Note: the released APK contains the placeholder API key (users enter their own API key in the Settings screen on first run). The real Speechmatics key never leaves your machine.

## Gesture model

The checked-in MediaPipe gesture model lives here:

```text
gesture/src/main/assets/gesture_recognizer.task
```

To replace it, copy the new `.task` model to that path and rebuild the app.
