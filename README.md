# Heart Rate Monitor App

A professional Android app that measures your heart rate using your phone's camera and flash, powered by photoplethysmography (PPG) technology.

![Version](https://img.shields.io/badge/version-2.0-blue)
![Android](https://img.shields.io/badge/Android-7.0%2B-green)
![License](https://img.shields.io/badge/license-Educational-orange)

## Features

### Core Measurement
- **Automatic Pulse Detection** - No buttons needed, just place your finger
- **Real-time Heartbeat Sound** - Hear your pulse like a medical device
- **Live Heart Animation** - Visual pulse synchronized with your heartbeat
- **Camera-in-Heart Design** - See your finger through the heart circle
- **Real-time BPM Display** - Updates every second during measurement
- **Smart Finger Detection** - Automatically detects when finger is placed or removed

### New in v2.0
- **ECG Waveform Visualization** - Real-time ECG-style graph showing your heartbeat pattern
- **Lottie Animations** - Smooth, professional heartbeat animations during measurement
- **Measurement History** - Track all your past readings with statistics
- **Statistics Dashboard** - View average, min, max BPM over the last 7 days
- **Local Database** - All measurements saved securely on your device

### UI/UX
- **Mute Control** - Toggle heartbeat sound on/off
- **Premium UI** - Beautiful gradient backgrounds and glowing effects
- **Fast Detection** - Minimal lag between actual pulse and feedback
- **Partial Readings** - Shows estimate even if measurement interrupted
- **Smart Validation** - Ensures accurate baseline calibration

## How It Works

### The Science: Photoplethysmography (PPG)

1. **Flash LED** illuminates your finger from behind
2. **Blood absorbs light** - absorption changes with each heartbeat
3. **Camera detects** subtle brightness variations (your pulse!)
4. **Algorithm analyzes** the signal to find peaks
5. **Calculates BPM** from time between peaks

### The Experience

1. **Open app** - Camera flash turns on, calibration begins
2. **Wait 1 second** - "Ready! Place your finger..."
3. **Place finger** on rear camera - Automatic detection!
4. **ECG waveform** appears - Watch your heartbeat in real-time
5. **Hear & see** - BEEP + Heart pulses on each beat
6. **Watch BPM** - Updates in real-time as it measures
7. **Final reading** - Accurate heart rate after 10 seconds, automatically saved!

## Usage

### Simple 3-Step Process

1. **Calibration** (1 second)
   - Keep finger OFF camera
   - App establishes baseline brightness
   - Shows "Ready!" when done

2. **Measurement** (10 seconds)
   - Place finger firmly on rear camera
   - ECG waveform and Lottie animation activate
   - Hear beeps and see heart pulse with each beat
   - BPM number updates every second
   - Progress: 0% - 100%

3. **Result**
   - Final heart rate displayed
   - Measurement automatically saved to history
   - Remove finger or measure again!

### Controls

| Button | Location | Function |
|--------|----------|----------|
| Speaker | Top-left | Mute/unmute heartbeat sound |
| History | Top-right | View measurement history & stats |
| Camera View | Center | Shows your finger inside the heart circle |

## History & Statistics

Access your measurement history by tapping the chart icon (top-right).

### Statistics Dashboard
- **AVG** - Average BPM over last 7 days
- **MIN** - Lowest recorded BPM
- **MAX** - Highest recorded BPM
- **TOTAL** - Total number of measurements

### History List
- View all past measurements with date/time
- See confidence level for each reading
- Long-press to delete individual measurements
- Clear all history with the delete button

## Tips for Best Results

- Keep finger OFF during initial 1-second calibration
- Cover camera lens completely with your fingertip
- Press gently but firmly (too hard restricts blood flow)
- Keep finger and phone steady
- Use in normal lighting (not pitch black)
- Ensure your hands are warm (cold reduces blood flow)

## Technical Details

### Advanced Algorithm Features

- **Adaptive Peak Detection** - Uses standard deviation, not fixed thresholds
- **Bandpass Filtering** - High-pass removes drift, low-pass removes noise
- **Gaussian Smoothing** - Clean signal for accurate peak detection
- **Median Calculation** - Better outlier resistance than average
- **Real-time Updates** - Shows HR every second (not just at end)
- **Multi-stage Validation** - Calibration stability checks
- **Smart Finger Removal Detection** - Tracks brightness changes to detect when finger is lifted

### Tech Stack

| Component | Technology |
|-----------|------------|
| Language | Kotlin |
| Camera | AndroidX CameraX (Camera2 API) |
| UI | Material Components 3, ConstraintLayout |
| Animations | Lottie 6.3.0 |
| Database | Room 2.6.1 |
| Async | Kotlin Coroutines |
| Sound | ToneGenerator |
| Build | Gradle 9.2.1, AGP 8.7.3 |

### Dependencies

```kotlin
// CameraX
implementation("androidx.camera:camera-camera2:1.3.1")
implementation("androidx.camera:camera-lifecycle:1.3.1")
implementation("androidx.camera:camera-view:1.3.1")

// Lottie for animations
implementation("com.airbnb.android:lottie:6.3.0")

// Room for local database
implementation("androidx.room:room-runtime:2.6.1")
implementation("androidx.room:room-ktx:2.6.1")
ksp("androidx.room:room-compiler:2.6.1")

// Coroutines & Lifecycle
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")

// Material Design
implementation("com.google.android.material:material:1.11.0")
```

## Building & Installation

### Debug Build (Development)

```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk

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
- AAB: `app/build/outputs/bundle/release/app-release.aab`
- APK: `app/build/outputs/apk/release/app-release.apk`

## Project Structure

```
app/src/main/
├── java/com/example/heartratemonitor/
│   ├── MainActivity.kt           # Main screen with PPG measurement
│   ├── HistoryActivity.kt        # History screen with stats
│   ├── ECGWaveformView.kt        # Custom ECG visualization view
│   └── data/
│       └── HeartRateDatabase.kt  # Room database & entities
├── res/
│   ├── layout/
│   │   ├── activity_main.xml     # Main UI with camera-in-heart
│   │   ├── activity_history.xml  # History screen layout
│   │   └── item_history.xml      # History list item
│   ├── drawable/                 # 15+ custom drawables
│   │   ├── gradient_background.xml
│   │   ├── heart_glow_border.xml
│   │   ├── ecg_background.xml
│   │   ├── stats_card_background.xml
│   │   └── ...
│   ├── raw/                      # Lottie animations
│   │   ├── heartbeat.json        # Heart pulse animation
│   │   └── loading_pulse.json    # ECG loading animation
│   └── values/
│       └── themes.xml            # Material theme + dialog styles
└── AndroidManifest.xml           # Permissions & activities
```

## UI Design

- **Gradient Background** - Sophisticated diagonal dark gradient
- **Camera-in-Heart** - Live camera feed inside heart circle with glowing border
- **ECG Waveform** - Green ECG-style graph with grid background
- **Lottie Heart** - Smooth pulsing animation during measurement
- **Glowing Effects** - Red glow on heart, green glow on pulse indicator
- **Stats Cards** - Color-coded statistics (green/blue/red/gold)
- **Dark Theme** - Easy on the eyes, medical device aesthetic

## Algorithm Performance

| Metric | Value |
|--------|-------|
| Detection Time | 1s baseline + 1s finger detection |
| Measurement Duration | 10 seconds (300 frames @ 30fps) |
| Update Frequency | BPM updates every 1 second |
| Heartbeat Lag | ~0.1 seconds |
| Accuracy Range | 40-200 BPM (validated) |
| Extended Range | 30-220 BPM (with warning) |
| Minimum Data | Shows reading with 3+ seconds of data |

## Requirements

- Android 7.0 (API 24) or higher
- Rear camera with flash LED
- ~5 MB storage space

## Development Requirements

- Android Studio Arctic Fox or later
- JDK 11 or higher
- Android SDK 34
- Gradle 9.2.1

## Important Notes

### Disclaimer

- **Not a medical device** - For wellness and fitness only
- **Not FDA approved** - Not for diagnosis or treatment
- **Consult professionals** - For medical concerns, see a doctor
- **Educational purpose** - Technology demonstration

### Privacy

- **Local storage only** - All data stays on your device
- **No internet required** - Works completely offline
- **No data collection** - We don't collect any information
- **Camera only** - Used solely for heart rate detection

### Known Limitations

- Requires rear camera with flash LED
- Accuracy may vary by device
- Movement reduces accuracy
- Room lighting can affect calibration

## Future Enhancements

- [ ] Heart rate variability (HRV) analysis
- [ ] Export readings (CSV, PDF)
- [ ] Multiple user profiles
- [ ] Share results
- [ ] Trends and insights
- [ ] Widgets for quick access
- [ ] Wear OS companion app

## Contributing

This is a personal project, but suggestions are welcome! Open an issue or PR.

## License

This project is for educational and personal use.

## Author

Built with Kotlin & Android

## Acknowledgments

- PPG technology based on research in optical heart rate monitoring
- Lottie animations by Airbnb
- UI design inspired by modern medical devices
- Built with AndroidX CameraX library

---

**Made with care**

*For questions or issues, please open a GitHub issue.*
