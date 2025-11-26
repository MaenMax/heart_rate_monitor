# ❤️ Heart Rate Monitor App

A professional Android app that measures your heart rate using your phone's camera and flash, powered by photoplethysmography (PPG) technology.

![Version](https://img.shields.io/badge/version-1.0-blue)
![Android](https://img.shields.io/badge/Android-7.0%2B-green)
![License](https://img.shields.io/badge/license-Educational-orange)

## ✨ Features

- 💓 **Automatic Pulse Detection** - No buttons! Just place your finger
- 🔊 **Real-time Heartbeat Sound** - Hear your pulse like a medical device
- 🎨 **Live Heart Animation** - Visual pulse synchronized with your heartbeat
- 📹 **Camera-in-Heart Design** - See your finger through the heart circle
- 📊 **Real-time BPM Display** - Updates every second during measurement
- 🔇 **Mute Control** - Toggle heartbeat sound on/off
- 💎 **Premium UI** - Beautiful gradient backgrounds and glowing effects
- ⚡ **Fast Detection** - Minimal lag between actual pulse and feedback
- 📈 **Partial Readings** - Shows estimate even if measurement interrupted
- ✅ **Smart Validation** - Ensures accurate baseline calibration

## 🎬 How It Works

### The Science: Photoplethysmography (PPG)

1. **Flash LED** illuminates your finger from behind
2. **Blood absorbs light** - absorption changes with each heartbeat
3. **Camera detects** subtle brightness variations (your pulse!)
4. **Algorithm analyzes** the signal to find peaks
5. **Calculates BPM** from time between peaks

### The Experience

1. **Open app** → Camera flash turns on, calibration begins
2. **Wait 1 second** → "Ready! Place your finger..."
3. **Place finger** on rear camera → Automatic detection!
4. **"PULSE" indicator** appears → Measurement starts
5. **Hear & see** → BEEP 🔊 + Heart pulses 💓 on each beat
6. **Watch BPM** → Updates in real-time as it measures
7. **Final reading** → Accurate heart rate after 10 seconds!

## 📱 Usage

### Simple 3-Step Process

1. **Calibration** (1 second)
   - Keep finger OFF camera
   - App establishes baseline brightness
   - Shows "Ready!" when done

2. **Measurement** (10 seconds)
   - Place finger firmly on rear camera
   - "PULSE" indicator appears (green badge)
   - Hear beeps and see heart pulse with each beat
   - BPM number updates every second
   - Progress: 0% → 100%

3. **Result**
   - Final heart rate displayed
   - Remove finger or measure again!

### Controls

- **🔊/🔇 Speaker Button** (top-left): Mute/unmute heartbeat sound
- **Camera View** (center): Shows your finger inside the heart circle

## 🎯 Tips for Best Results

- ✅ Keep finger OFF during initial 1-second calibration
- ✅ Cover camera lens completely with your fingertip
- ✅ Press gently but firmly (too hard restricts blood flow)
- ✅ Keep finger and phone steady
- ✅ Use in normal lighting (not pitch black)
- ✅ Ensure your hands are warm (cold reduces blood flow)

## 🛠️ Technical Details

### Advanced Algorithm Features

- **Adaptive Peak Detection** - Uses standard deviation, not fixed thresholds
- **Signal Smoothing** - Moving average filter reduces noise
- **Median Calculation** - Better outlier resistance than average
- **Real-time Updates** - Shows HR every second (not just at end)
- **Multi-stage Validation** - Calibration stability checks
- **Sustained Detection** - Prevents false positives from movements
- **Fast Response** - 2-frame window for minimal lag

### Implementation Highlights

- **Automatic Finger Detection**: Detects sustained brightness change (1 second)
- **Smart Interruption Handling**: Shows partial reading if measurement cut off
- **Synchronized Feedback**: Sound and animation trigger together
- **Peak Timing**: Detects actual peaks (not just increases) for accurate pulse rate
- **Fallback Calculations**: Multiple algorithms ensure a reading is shown

### Tech Stack

- **Language**: Kotlin
- **Camera**: AndroidX CameraX (Camera2 API)
- **UI**: Material Components 3, ConstraintLayout
- **Async**: Kotlin Coroutines
- **Sound**: ToneGenerator (NOTIFICATION stream)
- **Build**: Gradle 9.2.1, AGP 8.7.3

## 🏗️ Building & Installation

### Debug Build (Development)

```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device
adb install app/build/outputs/apk/debug/app-debug.apk

# Or install directly
./gradlew installDebug
```

### Release Build (Production)

```bash
# Build signed App Bundle for Play Store
./gradlew bundleRelease

# Build signed APK for direct distribution
./gradlew assembleRelease
```

**Output files**:
- AAB: `app/build/outputs/bundle/release/app-release.aab` (3.1 MB)
- APK: `app/build/outputs/apk/release/app-release.apk` (2.2 MB)

### Build Configuration

- **Signing**: Pre-configured with release keystore
- **ProGuard**: Enabled (code shrinking & optimization)
- **Resource Shrinking**: Enabled (removes unused resources)

## 📂 Project Structure

```
app/src/main/
├── java/com/example/heartratemonitor/
│   └── MainActivity.kt              # 770 lines - Complete PPG implementation
├── res/
│   ├── layout/
│   │   └── activity_main.xml        # Modern UI with camera-in-heart design
│   ├── drawable/
│   │   ├── gradient_background.xml  # App background gradient
│   │   ├── heart_glow_border.xml    # Heart circle with glow
│   │   ├── pulse_indicator_background.xml
│   │   ├── status_background.xml
│   │   └── ... (11 drawable resources)
│   ├── values/
│   │   └── themes.xml               # Material Components theme
│   └── mipmap-*/
│       └── ic_launcher.png          # App icons (all densities)
└── AndroidManifest.xml              # Camera permissions
```

## 🎨 UI Design Highlights

- **Gradient Background** - Sophisticated diagonal gradient
- **Camera-in-Heart** - Live camera feed shows inside heart circle with glowing border
- **Glowing Effects** - Red glow on heart, green glow on pulse indicator
- **Typography** - Multiple font weights, letter spacing, shadows
- **Elevation & Depth** - 3D appearance with shadows
- **Modern Title** - "❤️ HEART RATE" with decorative underline
- **Card Design** - Status text in elevated rounded card
- **Premium Feel** - Medical-grade professional appearance

## 📊 Algorithm Performance

- **Detection Time**: 1 second baseline + 1 second finger detection
- **Measurement Duration**: 10 seconds (300 frames @ 30fps)
- **Update Frequency**: BPM updates every 1 second during measurement
- **Heartbeat Lag**: ~0.1 seconds (minimal delay)
- **Accuracy Range**: 45-180 BPM (validated), 30-220 BPM (displayed with warning)
- **Minimum Data**: Shows reading with as few as 3 seconds of data

## 🔧 Development

### Requirements

- Android Studio Arctic Fox or later
- JDK 11 or higher
- Android SDK 34
- Gradle 9.2.1

### Dependencies

```kotlin
// CameraX
implementation("androidx.camera:camera-camera2:1.3.1")
implementation("androidx.camera:camera-lifecycle:1.3.1")
implementation("androidx.camera:camera-view:1.3.1")

// Coroutines
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

// Material Design
implementation("com.google.android.material:material:1.11.0")
```

### Clean Build

```bash
./gradlew clean
./gradlew assembleDebug -x lint -x test --no-configuration-cache
```

## 🚀 Publishing to Play Store

See `PLAY_STORE_PUBLISHING.md` for detailed instructions.

**Quick checklist**:
- ✅ Release AAB built and signed
- ✅ App icons prepared (all densities)
- ⚠️ Need privacy policy URL
- ⚠️ Need screenshots (4-5 recommended)
- ⚠️ Need feature graphic (1024x500)

## 📸 Screenshots

Take screenshots using:
```bash
adb shell screencap -p /sdcard/screenshot.png
adb pull /sdcard/screenshot.png
```

## ⚠️ Important Notes

### Disclaimer

- **Not a medical device** - For wellness and fitness only
- **Not FDA approved** - Not for diagnosis or treatment
- **Consult professionals** - For medical concerns, see a doctor
- **Educational purpose** - Technology demonstration

### Privacy

- **No data collection** - Everything processed locally
- **No internet required** - Works completely offline
- **No storage** - Results not saved (unless you screenshot)
- **Camera only** - Used solely for heart rate detection

### Known Limitations

- Requires rear camera with flash LED
- Accuracy may vary by device
- Movement reduces accuracy
- Room lighting can affect calibration
- Some skin tones may need adjustment

## 🎯 Future Enhancements

Potential improvements:
- [ ] Measurement history with charts
- [ ] Export readings (CSV, PDF)
- [ ] Heart rate variability (HRV) analysis
- [ ] Real-time signal graph display
- [ ] Multiple user profiles
- [ ] Trends and statistics
- [ ] Share results
- [ ] Dark/Light theme toggle
- [ ] Advanced filtering (Butterworth, FFT)

## 🤝 Contributing

This is a personal project, but suggestions are welcome! Open an issue or PR.

## 📄 License

This project is for educational and personal use.

## 👨‍💻 Author

Built with ❤️ using Android & Kotlin

## 🙏 Acknowledgments

- PPG technology based on research in optical heart rate monitoring
- UI design inspired by modern medical devices
- Built with AndroidX CameraX library

---

**Made with ❤️ and lots of ☕**

*For questions or issues, please open a GitHub issue.*
