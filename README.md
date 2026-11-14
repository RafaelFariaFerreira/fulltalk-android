<div align="center">

<img src="docs/logo.svg" width="72" alt="fulltalk dev logo">

# fulltalk dev for Android

**code is cheap, show me the talk.**

A floating microphone for your phone. Tap it, speak, and the text lands in whatever
field you are typing in. The speech is transcribed on the device: nothing leaves it.

[![CI](https://github.com/RafaelFariaFerreira/fulltalk-android/actions/workflows/ci.yml/badge.svg)](https://github.com/RafaelFariaFerreira/fulltalk-android/actions/workflows/ci.yml)
![Kotlin](https://img.shields.io/badge/kotlin-2.4-7F52FF)
![Android](https://img.shields.io/badge/android-8.0%2B-3DDC84)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

<img src="docs/hero.png" alt="fulltalk dev on Android: the setup screen, the bubble listening over a messaging app, and the transcription pasted into the message field" width="100%">

</div>

This is the Android companion of [fulltalk dev](https://github.com/RafaelFariaFerreira/fulltalk-dev),
the desktop push-to-talk dictation app. Same idea, same model, same rules, rebuilt for a
touch screen.

## How it works

The app is a single bubble that floats over every other app:

| Gesture | What it does |
|---|---|
| **Tap** | Starts recording. Tap again to stop and transcribe. |
| **Hold** | Records while your finger is down, transcribes when you let go. |
| **Double tap** | Opens the app (setup, settings, recent transcriptions). |
| **Drag** | Moves the bubble. It remembers where you left it. |

The bubble is grey while idle, violet while it listens or transcribes, and amber when
something went wrong. A short note appears beside it to say what happened
("Texto colado", "Nada foi captado").

When the transcription is ready it always goes to the clipboard. If a text field has
focus, it is also pasted there, at the cursor. The last five transcriptions stay in the
app, one tap to copy again.

## Privacy and permissions

| Permission | Why |
|---|---|
| **Microphone** | To hear you. It is only open while the bubble is violet. |
| **Accessibility service** | Two things, and nothing else: it lets the bubble's window sit on top of other apps, and it tells the app which text field has focus so the transcription can be pasted there. The service does not listen to accessibility events and does not read, store or send anything that is on screen. |
| **Internet** | Used once, to download the speech model (670 MB). After that the app works offline. |

Recognition runs on the phone's CPU with [NVIDIA Parakeet TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3)
through [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx). There is no account, no API
key and no server. Dictated text is excluded from cloud backup and device transfer.

## Install

There is no store release yet: build it and install it over USB.

Requirements: JDK 17 or newer and the Android SDK (platform 37, build tools). Android
Studio brings both; from a terminal, set `ANDROID_HOME` to the SDK.

```sh
git clone https://github.com/RafaelFariaFerreira/fulltalk-android.git
cd fulltalk-android
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

The first build downloads sherpa-onnx (49 MB) and checks it against a pinned SHA-256.

Then open **fulltalk dev** on the phone and follow its three steps:

1. **Modelo de voz**: download the model. An interrupted download resumes, and every file
   is verified against its size and SHA-256 before it is used.
2. **Microfone**: allow the microphone.
3. **Bolinha flutuante**: turn fulltalk dev on under Settings → Accessibility.

The bubble appears in the top-right corner. Put the cursor in any text field and tap it.

## Architecture

```
core/   pure Kotlin, no Android: the rules. Runs and is tested on a plain JVM.
app/    Android: the bubble, the microphone, the model, the screen.
```

| Module | Responsibility | Tests |
|---|---|---|
| [`core`](core) | Session state machine, bubble gesture recognition, history, text clean-up, silence gate, paste-or-clipboard policy, verified model installer | 60 unit tests |
| [`app`](app) | Accessibility service with the floating bubble, `AudioRecord` capture, sherpa-onnx transcriber, model download, Compose screen | exercised on an emulator (see below) |

Some decisions worth calling out:

- **The flow is a pure state machine.** [`Session`](core/src/main/kotlin/dev/fulltalk/core/Session.kt)
  takes inputs (`Tapped`, `HoldStarted`, …) and answers with effects (`StartCapture`,
  `Deliver`, …). Every rule, such as "letting go does not stop a recording that a tap
  started", is a unit test with no microphone, model or screen involved. It is a port of
  the desktop app's Rust state machine.
- **One finger, four meanings.** [`BubbleGesture`](core/src/main/kotlin/dev/fulltalk/core/BubbleGesture.kt)
  tells tap, double tap, hold and drag apart. The first tap of a double tap acts at once,
  so a single tap never waits for a timeout; the second one cancels the recording the
  first one started instead of transcribing a fraction of a second.
- **An accessibility overlay, not "draw over other apps".** The bubble's window is of
  type `TYPE_ACCESSIBILITY_OVERLAY`, which the accessibility service is allowed to add by
  itself. That saves a second scary permission, and the window is never focusable, so
  the field you are typing in keeps the focus and the keyboard.
- **No toasts.** Android drops toasts from apps that are in the background without the
  notification permission, and the bubble always is. Messages are drawn in a small
  window of their own beside the bubble.
- **Pinned downloads.** The model comes from a pinned Hugging Face revision and
  sherpa-onnx from a pinned release; both are checked against SHA-256 sums that live in
  the source.

## Testing

```sh
./gradlew :core:test        # 60 unit tests, plain JVM
./gradlew :app:lintDebug    # Android lint, warnings are errors
```

Emulators rarely have a usable microphone, so debug builds accept a recording in its
place. Everything else stays real: the touches, the state machine, the model and the
paste.

```sh
adb push speech.wav /data/local/tmp/                        # 16 kHz, mono, 16-bit PCM
adb shell run-as dev.fulltalk.app cp /data/local/tmp/speech.wav files/
adb shell am broadcast -a dev.fulltalk.app.debug.DICTATE \
    --es path /data/user/0/dev.fulltalk.app/files/speech.wav
# the next recording made with the bubble uses that file
```

Avoid `uiautomator dump` while testing: it suspends every accessibility service, the
bubble included.

## Status

✅ verified on an emulator (Android 15, x86_64) · 🧪 implemented, not yet verified

| | |
|---|---|
| Model download with resume and verification | ✅ |
| Bubble over other apps, never taking focus | ✅ |
| Tap, hold, double tap and drag | ✅ |
| Microphone opened from the bubble while another app is in front | ✅ (the system allows it; the emulator itself hears only silence) |
| Transcription and paste into the focused field | ✅ with an injected recording |
| Speed and memory on a real phone | 🧪 |
| Real speech through a real microphone | 🧪 |

On the emulator, which runs the model on a software-emulated CPU, loading takes about
half a minute and 4 s of speech take about 7 s to transcribe. Those numbers say little
about a phone; measuring one is the next step.

Known limits: the model needs a phone with plenty of memory (it uses about 800 MB on
the desktop), and two dictations in a row are pasted without a space between them.

## Credits

- [NVIDIA Parakeet TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) (CC BY 4.0), in the int8 ONNX export packaged by [csukuangfj](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8)
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) and [ONNX Runtime](https://onnxruntime.ai)
- [Jetpack Compose](https://developer.android.com/compose)

## License

[MIT](LICENSE) © Rafael Faria Ferreira
