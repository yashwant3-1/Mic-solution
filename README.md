# Wireless Collar Mic 🎙️ ➡️ 🔊

A native Android application written in **Kotlin** and the **Android SDK** that turns your smartphone into a wireless lapel/collar microphone transmitter. 

The phone captures live, low-latency audio from an external **3.5 mm wired lapel/collar microphone** and routes it in real-time to a connected **Bluetooth A2DP audio receiver** (such as a Bluetooth speaker, soundbar, PA system, or wireless audio receiver adapter).

---

## 📋 Table of Contents
- [App Overview](#app-overview)
- [Audio Architecture & Bluetooth Routing](#audio-architecture--bluetooth-routing)
- [Hardware Connection & Pinout Guide](#hardware-connection--pinout-guide)
- [Android SDK & Permissions](#android-sdk--permissions)
- [Project Structure](#project-structure)
- [How to Build from Terminal (Windows PowerShell)](#how-to-build-from-terminal-windows-powershell)
- [Testing & Validation Notice](#testing--validation-notice)

---

## 🎙️ App Overview

- **Beginner-Friendly UI**: Clear visual indicators for Bluetooth connection status and microphone source.
- **Start / Stop MIC Control**: High-contrast, single-tap master streaming toggle button with live streaming indicator.
- **Automatic Device Detection**: Automatically discovers paired Bluetooth audio sinks and monitors connection state.
- **Audio Source Auto-Priority**: Automatically detects and prioritizes external 3.5mm TRRS wired collar mics with fallback to the built-in microphone.
- **Live VU Meter & Gain Control**: Visual real-time decibel/amplitude meter with an adjustable boost slider (1.0x to 3.0x).
- **Foreground Streaming Service**: Keeps audio active even when the screen is locked or another app is opened.

---

## ⚙️ Audio Architecture & Bluetooth Routing

### Why NOT `BluetoothSocket` (RFCOMM)?
A common mistake in mobile audio streaming is attempting to open a generic `BluetoothSocket` using SPP UUID (`00001101-0000-1000-8000-00805F9B34FB`) to connect to a Bluetooth speaker. 
- Standard Bluetooth audio receivers and speakers are **A2DP Sinks** (Advanced Audio Distribution Profile over AVDTP/L2CAP), not SPP servers.
- A generic RFCOMM socket will fail with `read failed, socket might closed or timeout, read -1`.

### Supported Android Audio Routing Design
In Android, Bluetooth A2DP audio output is managed by the system's `AudioPolicyManager` and `AudioManager`:
1. **Audio Input**: An `AudioRecord` instance captures raw 16-bit PCM audio at **48,000 Hz** (the native hardware sample rate on modern Android chips, eliminating software resampling delay). If an external 3.5mm mic is attached, `AudioRecord.setPreferredDevice(wiredDevice)` directs capture specifically to `AudioDeviceInfo.TYPE_WIRED_HEADSET`.
2. **Audio Output**: An `AudioTrack` instance operates in `MODE_STREAM` using `AudioAttributes.USAGE_MEDIA` and `AudioAttributes.CONTENT_TYPE_SPEECH`. On Android 6.0+ (API 23+), `AudioTrack.setPreferredDevice(a2dpDevice)` explicitly assigns output to `AudioDeviceInfo.TYPE_BLUETOOTH_A2DP`.
3. **Low-Latency Audio Pump**: A dedicated worker thread with `Process.THREAD_PRIORITY_URGENT_AUDIO` continuously pipes PCM chunks (512 frames = ~10.6ms) from `AudioRecord` into `AudioTrack`, applying smooth digital gain clamping to prevent harsh distortion.
4. **Bluetooth Profile Management**: `BluetoothProfile.ServiceListener` binds to `BluetoothProfile.A2DP` to query bonded audio devices, track connection state, and detect changes via system broadcasts.

---

## 🔌 Hardware Connection & Pinout Guide

Smartphone 3.5 mm audio jacks use the **CTIA/AHJ (TRRS - 4 poles)** standard:
- **Tip**: Left Audio (Output)
- **Ring 1**: Right Audio (Output)
- **Ring 2**: Ground
- **Sleeve**: Microphone (Input)

> 💡 **Important Connection Tip:**
> - Collar/lavalier mics intended for cameras often have a **3-pole (TRS)** connector. When plugged into a phone, TRS mics will not activate the microphone ring.
> - Always use a **4-pole (TRRS)** collar microphone or connect a **TRS-to-TRRS adapter** (e.g., Boya BY-CIP2 or Rode SC4). The app UI will immediately display **"3.5mm External Collar Mic (Preferred)"** in green once correctly recognized.

---

## 📱 Android SDK & Permissions

| Parameter | Value | Details |
|---|---|---|
| **Target SDK** | `API 34` (Android 14) | Fully compliant with Android 14 foreground service types (`microphone`). |
| **Minimum SDK** | `API 26` (Android 8.0 Oreo) | Supports `AudioDeviceInfo`, `AudioRecord.setPreferredDevice`, `AudioTrack.setPreferredDevice`, Notification Channels. Covers >95% of active devices. |
| **Language** | Kotlin `1.9.22` | Standard Kotlin Android project. |

### Permissions Handled at Runtime:
- `RECORD_AUDIO`: Required for microphone access.
- `BLUETOOTH_CONNECT`: (Android 12+, API 31+) Required to query bonded devices and connection state.
- `BLUETOOTH_SCAN`: (Android 12+, API 31+) Uses `neverForLocation` flag.
- `BLUETOOTH` & `BLUETOOTH_ADMIN`: Legacy permissions for Android 11 and below.
- `FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_MICROPHONE`: Allows continuous streaming during presentations.
- `POST_NOTIFICATIONS`: (Android 13+, API 33+) Displays notification controls.

---

## 📂 Project Structure

```
WirelessCollarMic/
├── build.gradle.kts                     # Root Gradle build script
├── settings.gradle.kts                  # Root Gradle settings
├── gradle.properties                    # JVM & AndroidX settings
├── gradlew.bat                          # Windows Gradle Wrapper
├── gradlew                              # Unix/macOS Gradle Wrapper
├── gradle/
│   └── wrapper/
│       └── gradle-wrapper.properties   # Gradle 8.5 distribution URL
└── app/
    ├── build.gradle.kts                 # Application build config (SDK 34 / minSdk 26)
    ├── proguard-rules.pro               # Proguard configuration
    └── src/
        └── main/
            ├── AndroidManifest.xml      # Permissions, activities, and foreground service
            ├── java/com/wirelesscollarmic/app/
            │   ├── MainActivity.kt              # Main UI, permissions, user interaction
            │   ├── AudioStreamingEngine.kt      # Low-latency AudioRecord -> AudioTrack pipeline
            │   ├── BluetoothReceiverManager.kt  # A2DP proxy, bonded device detection & status
            │   ├── AudioDeviceManager.kt        # 3.5mm wired mic detection (AudioDeviceCallback)
            │   └── WirelessMicService.kt        # Foreground microphone service
            └── res/
                ├── layout/
                │   └── activity_main.xml        # Beginner-friendly UI layout
                ├── values/
                │   ├── colors.xml               # Modern slate dark theme colors
                │   ├── strings.xml              # Localized strings and descriptions
                │   └── themes.xml               # Material components theme
                ├── drawable/                    # Status badges, card backgrounds, vectors
                └── mipmap-anydpi-v26/           # Adaptive app launcher icons
```

---

## 🛠️ How to Build from Terminal (Windows PowerShell)

You can build this project completely from **Windows PowerShell** without opening Android Studio.

### Prerequisites (Installed once):
1. **Java Development Kit (JDK 17)**:
   Install OpenJDK 17 via `winget` in PowerShell:
   ```powershell
   winget install Microsoft.OpenJDK.17
   ```
   Or set your existing JDK path:
   ```powershell
   $env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.x"
   $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
   ```

2. **Android Command-Line Tools & SDK**:
   If Android SDK is not yet installed, download the command-line tools from the [official Android Developer site](https://developer.android.com/studio#command-line-tools-only) or install via winget:
   ```powershell
   # Set your Android SDK Root directory
   $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
   ```
   Install the required platform and build tools using `sdkmanager`:
   ```powershell
   cmdline-tools\latest\bin\sdkmanager.bat "platforms;android-34" "build-tools;34.0.0"
   ```

3. **Accept Android SDK Licenses**:
   ```powershell
   cmdline-tools\latest\bin\sdkmanager.bat --licenses
   ```

---

### Step-by-Step Terminal Build Commands

Open **PowerShell** and navigate to the project directory:

```powershell
cd c:\Users\yash123\OneDrive\Pictures\agent
```

#### 1. Verify Java Environment
```powershell
java -version
```
*(Ensure it outputs OpenJDK 17 or compatible version)*

#### 2. Set Local SDK Path
Create `local.properties` in the project root pointing to your Android SDK (replace with your actual SDK path if different):
```powershell
Set-Content -Path "local.properties" -Value "sdk.dir=$($env:LOCALAPPDATA -replace '\\', '/')/Android/Sdk"
```

#### 3. Build the Debug APK
Run Gradle to compile and assemble the debug package:
```powershell
# Using the installed Gradle or Gradle wrapper:
gradle.bat assembleDebug
```
*Or using the included wrapper:*
```powershell
.\gradlew.bat assembleDebug
```

#### 4. Locate the Generated APK
Upon successful compilation, your debug APK will be located at:
```powershell
app\build\outputs\apk\debug\app-debug.apk
```

#### 5. Install onto Connected Android Device via ADB
Enable **USB Debugging** on your Android device, plug it into your computer via USB, and run:
```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

---

## ⚠️ Testing & Validation Notice

> **Hardware Testing Requirement**:
> Because Android audio routing and Bluetooth profiles involve physical hardware HALs and peripheral firmware:
> - **Bluetooth A2DP Audio Routing**: Requires a physical Bluetooth audio receiver / speaker paired with the test phone. Android Virtual Devices (emulators) do not simulate physical Bluetooth audio hardware or external 3.5mm TRRS mic jack impedance sensing.
> - **3.5mm Wired Lapel Mic**: Requires a physical 4-pole TRRS microphone or TRS-to-TRRS adapter connected to the phone's 3.5mm jack or USB-C audio adapter.
> - The application handles device disconnection, reconnection, gain clamping, and fallback gracefully on physical hardware.
