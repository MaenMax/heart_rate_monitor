# Heart Rate Monitor App

An Android app that measures heart rate using your phone's camera and flash, based on photoplethysmography (PPG) technology.

## How It Works

The app uses **photoplethysmography (PPG)**, a non-invasive optical technique to detect blood volume changes in the microvascular tissue:

1. **Place your finger** over the rear camera with the flash LED on
2. **Light absorption**: As your heart beats, blood flows through your finger, causing subtle changes in light absorption
3. **Signal analysis**: The camera captures these variations in brightness (luminance)
4. **Peak detection**: The app analyzes the signal to detect peaks corresponding to heartbeats
5. **Calculate BPM**: By measuring the time between peaks, it calculates beats per minute (BPM)

## Features

- ✅ Camera-based heart rate detection
- ✅ Real-time measurement with progress indicator
- ✅ Automatic flash control during measurement
- ✅ Clean, modern dark-themed UI
- ✅ Heart animation on successful reading
- ✅ 10-second measurement window for accuracy
- ✅ Validates readings (40-200 BPM range)

## Technical Implementation

### Algorithm Details

1. **Data Collection**: Captures 300 frames over 10 seconds (30 FPS)
2. **Signal Extraction**: Analyzes the Y-plane (luminance) from camera frames
3. **Normalization**: Removes DC component by subtracting mean
4. **Peak Detection**: 
   - Identifies local maxima above 50% threshold
   - Enforces minimum peak distance (prevents false detections)
5. **Heart Rate Calculation**: `BPM = 60 * FPS / average_peak_interval`

### Technologies Used

- **Kotlin** - Primary language
- **CameraX** - Modern camera API for Android
- **Material Design 3** - UI components
- **Coroutines** - Asynchronous operations
- **AndroidX** - Jetpack libraries

## Building the App

```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Build without lint/tests
./gradlew build -x lint -x test
```

The APK will be generated at:
```
app/build/outputs/apk/debug/app-debug.apk
```

## Usage Instructions

1. **Launch the app**
2. **Grant camera permission** when prompted
3. **Tap "Start Measuring"**
4. **Place your index finger** completely over the rear camera
   - Cover both the camera lens and flash LED
   - Press gently but firmly
   - Keep your finger still
5. **Wait 10 seconds** for measurement to complete
6. **View your heart rate** displayed in BPM

## Tips for Best Results

- 🔦 Use in a dimly lit room for better signal quality
- 👆 Keep your finger steady during measurement
- 💪 Press gently - too much pressure restricts blood flow
- 📱 Hold the phone steady
- 🔄 Retake measurement if result seems incorrect

## Accuracy

This app uses the same PPG technology found in fitness trackers and smartwatches. However:

- ⚠️ **Not a medical device** - For informational purposes only
- ⚠️ **May be less accurate** during movement or poor finger placement
- ⚠️ **Consult healthcare professionals** for medical advice

## Requirements

- **Minimum SDK**: Android 7.0 (API 24)
- **Target SDK**: Android 14 (API 34)
- **Permissions**: Camera access
- **Hardware**: Rear camera with flash/LED

## Project Structure

```
app/src/main/
├── java/com/example/heartratemonitor/
│   └── MainActivity.kt          # Main app logic & PPG algorithm
├── res/
│   ├── layout/
│   │   └── activity_main.xml    # UI layout
│   └── drawable/
│       └── circle_background.xml # Heart icon background
└── AndroidManifest.xml          # App configuration & permissions
```

## Future Enhancements

Potential improvements:
- 📊 Display real-time signal graph
- 💾 Save measurement history
- 📈 Show heart rate variability (HRV)
- 🎯 Improved signal processing (bandpass filter, FFT)
- 🌈 Skin tone compensation
- 📱 Support for front camera with screen flash

## License

This project is for educational and personal use.

## Disclaimer

This app is not intended for medical diagnosis or treatment. Always consult with healthcare professionals for medical advice.

