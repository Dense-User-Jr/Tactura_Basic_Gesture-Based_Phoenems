# GestureComm — Pixel Watch 2 Gesture-to-Phoneme MVP

A Wear OS app that classifies 6 wrist gestures, supports 2-step gesture
chaining, maps resolved patterns to phoneme-like tokens, predicts likely words,
and outputs both text and audio directly on the watch.

---

## Project structure

```
GestureComm/
├── wear/          ← Wear OS app (sideloaded to Pixel Watch 2)
│   └── src/main/kotlin/com/gesturecomm/
│       ├── gestures/
│       │   ├── GestureDefinitions.kt   ← phrases live here — easy to edit
│       │   ├── GestureClassifier.kt    ← threshold logic, tunable constants
│       │   └── GestureService.kt       ← foreground service, sensor loop
│       ├── output/
│       │   └── WatchTts.kt             ← watch audio output
│       └── ui/
│           └── MainActivity.kt         ← Compose UI
```

---

## Quick-start (30 minutes to demo-ready)

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or newer
- Pixel Watch 2 with Developer Mode enabled
- Wear OS debugging enabled on the watch

### 1 — Enable Developer Mode on Pixel Watch 2
1. Settings → System → About → tap **Build Number** 7 times
2. Settings → Developer Options → ADB Debugging → ON
3. Settings → Developer Options → Debug over Wi-Fi → ON
   (note the IP address shown, e.g. `192.168.1.42:5555`)

### 2 — Build and install

Open the project in Android Studio.

**Watch app:**
```
adb connect 192.168.1.42:5555          # use your watch's IP
adb -s 192.168.1.42:5555 install wear/build/outputs/apk/debug/wear-debug.apk
```
Or just press ▶ in Android Studio with the watch selected as target device.

### 3 — Grant permissions
On the watch, when prompted: allow **Body Sensors** permission.

---

## Gesture tokens

The app recognizes 6 base gestures and also resolves a handful of 2-gesture
combos into additional phoneme-like tokens.

### Base gestures

| # | Gesture | How to do it | Base token |
|---|---------|--------------|------------|
| 1 | **Flick Up** | Snap wrist sharply upward | `AH` |
| 2 | **Flick Down** | Snap wrist sharply downward | `EH` |
| 3 | **Twist Right** | Rotate forearm clockwise ~90° | `S` |
| 4 | **Twist Left** | Rotate forearm counter-clockwise ~90° | `T` |
| 5 | **Shake** | Shake wrist left-right 2–3 times | `K` |
| 6 | **Double Tap** | Tap the watch face or touch screen twice quickly | `N` |

### Derived tokens

| Gesture sequence | Token |
|------------------|-------|
| Flick Up + Twist Right | `SH` |
| Flick Up + Twist Left | `CH` |
| Twist Right + Double Tap | `R` |
| Twist Left + Double Tap | `L` |
| Shake + Flick Up | `P` |
| Shake + Flick Down | `B` |
| Flick Down + Twist Right | `M` |
| Flick Down + Twist Left | `D` |
| Twist Right + Flick Up | `W` |
| Twist Left + Flick Up | `Y` |

### Sequence examples
- Flick Up + Twist Right -> `SH`
- Flick Up + Twist Left -> `CH`
- Twist Right + Double Tap -> `R`
- Twist Left + Double Tap -> `L`
- Shake + Flick Up -> `P`
- Shake + Flick Down -> `B`

**Practice tip:** Each gesture needs ~0.5–1 second of committed motion.
Flicks should be sharp snaps, not slow swings. Twists need ~90° of rotation.
Double tap can be entered as a physical watch-face tap or a screen tap.

---

## Customising tokens and predictions

Open `wear/src/main/kotlin/com/gesturecomm/gestures/GestureDefinitions.kt`.

- Edit `PhoneticLexicon.tokenMap` for single and 2-gesture token mappings.
- Edit `PhoneticPredictor.dictionary` to tune top word suggestions.

This is the fastest way to adapt demo vocabulary without retraining models.

---

## Tuning gesture sensitivity

If gestures misfire or don't trigger, open `GestureClassifier.kt`.
Each detector has a clearly labelled threshold constant at the top:

```kotlin
// FLICK_UP — increase if firing accidentally, decrease if not triggering
private fun detectFlickUp(): Gesture? {
    val THRESHOLD = 18f   // ← m/s²
```

**Misfiring too often** → increase the threshold value
**Not triggering** → decrease the threshold value

Typical adjustment range: ±3–5 units.

---

## Architecture overview

```
Pixel Watch 2
─────────────────────────────────
SensorManager (50 Hz)
  │ accel + gyro events
  ▼
GestureClassifier (threshold based)
  │ single gesture events
  ▼
GestureService (foreground)
  ├── Sequence resolver (max 2 gestures)
  ├── Gesture pattern -> phoneme token
  ├── Token stream -> word prediction (beam-like ranking)
  ├── Broadcast -> MainActivity (pending, token, prediction, candidates)
  └── WatchTts.speak(committedWord)
```

No internet required. Entirely local. Works on airplane mode.

---

## Troubleshooting

**Watch not appearing as ADB target**
- Confirm Wi-Fi debugging is enabled on the watch
- Ensure phone and watch are on the same Wi-Fi network
- Try: `adb disconnect` then `adb connect <watch-ip>:5555`

**Gestures not triggering**
- Grant Body Sensors permission on the watch
- Check the foreground notification is visible (means service is running)
- Make gestures more deliberate — slower, larger motions work best during tuning

**Watch not speaking**
- Verify watch volume is up and not muted
- Ensure a TTS voice is available on the watch
- Restart the app once after first install to initialize TTS cleanly

**Wrong gestures firing**
- Flick Up and Flick Down can sometimes overlap — increase threshold to 22f
- Twist detection relies on gyro-Z; if the watch is worn on right wrist, CW/CCW may be inverted — swap the positive/negative threshold signs in `detectTwistCW` / `detectTwistCCW`

---

## Extending after the presentation

- **More words:** Expand `PhoneticPredictor.dictionary`.
- **More tokens:** Add or edit 2-gesture combinations in `PhoneticLexicon.tokenMap`.
- **TFLite model:** Replace `GestureClassifier` with a TFLite `Interpreter` for higher accuracy and more gesture types
- **Watch-only TTS:** Use `android.speech.tts.TextToSpeech` directly on the watch for offline-only demos (lower quality voice but no phone needed)
