<div align="center">

<img src="Zippy%20icon/logo/zippy-logo-256.png" width="120" alt="Zippy logo" />

# 🎬 Zippy

**Ultra-Lightweight Android Streaming Client**  
*Built with Jetpack Compose, Media3 ExoPlayer, and an iOS-inspired Liquid-Glass UI*

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.0-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-7.0%2B%20(API%2024%2B)-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Media3](https://img.shields.io/badge/Media3-ExoPlayer-E50914?style=for-the-badge&logo=youtube&logoColor=white)](https://developer.android.com/guide/topics/media/media3)
[![License: MIT / Modding](https://img.shields.io/badge/License-MIT%20%2F%20Modding-blue?style=for-the-badge)](LICENSE)

<br/>

[⬇️ Releases](https://github.com/abhishekgt5360/zippy/releases) • [✨ Features](#-key-features) • [🎨 Liquid-Glass UI](#-liquid-glass-design-system) • [🛠️ Modding Guide](#-client-modding--customization) • [🚀 Build from Source](#-building-from-source)

</div>

---

## 🌟 Overview

**Zippy** is an open-source, ultra-lightweight Android streaming client crafted to deliver a modern cinematic experience inspired by Apple TV and Netflix aesthetics. It is built on a custom **iOS Liquid-Glass UI framework** that combines translucent specular highlights, fluid spring physics, and edge-to-edge transparency — with zero GPU overhead, so it runs smoothly on both flagship devices and low-RAM legacy hardware (2GB RAM, Android 7.0+).

The client connects to the high-speed **CinePulse Cloud Network** (`movieapi.zarhosting.dpdns.org`) out of the box, letting developers and modders jump straight into customizing themes, player gestures, and layout components without any backend setup.

> Zippy is a rebranded fork of [CinePulse](https://github.com/ImAhmedZarir/cinepulse) by Ahmed Ishtiaque Zarir. The application ID is `com.toolsetstudio.zippy`; the Kotlin namespace intentionally remains `com.example` to preserve Room schemas, Moshi codegen, and ProGuard rules.

---

## ✨ Key Features

- **💎 Apple iOS Liquid-Glass Design:** Translucent frosted surfaces, multi-stop specular borders, ambient light sheens, and fluid spring physics on tap.
- **🎯 Strict 3-Color Cinema Palette:**
  - **Zippy Crimson (`#EE1E50` / `#FF2A55`):** High-energy accent for actions and focus states.
  - **True OLED Pitch Black (`#000000`):** Battery-saving deep contrast across scaffolds, sheets, and backdrops.
  - **Crisp Pure White (`#FFFFFF`):** High-readability typography, specular borders, and sharp vector iconography.
- **⚡ Ultra-Low RAM & Tiny APK Footprint:** Roughly **~3.9 MB**, with optimized ProGuard rules and no bloated dependencies.
- **🎥 Modern Streaming Engine (AndroidX Media3 ExoPlayer):**
  - Adaptive 1080p video streaming.
  - Picture-in-Picture (PiP) floating window with clean lifecycle state handling.
  - Multi-language subtitle parser (UTF-8 encoding) with auto-selection and 250ms sync intervals.
  - Responsive playback controls, seek gestures, and audio track selectors.
- **📥 Background Download Manager:** Reliable background downloading integrated with Android's system `DownloadManager`, saving media to `Downloads/Zippy/`.
- **📑 Virtualized Episode Pagination:** Long anime and TV series with hundreds of episodes are chunked dynamically into 30-episode pages for instant load times and zero memory spikes.
- **💾 Local SQLite Database (Room):** Offline watch history, resumable playback progress, and a personal watchlist stored locally on-device.
- **🧭 Rebuilt Browse Experience:** Theme-aware artwork, collapsible filter controls, and a Clear Watching header that opens the full history list.

---

## 🎨 Liquid-Glass Design System

Unlike conventional blur implementations that degrade performance on older Mali and Adreno GPUs, Zippy uses a calibrated multi-layer composite system:

1. **Specular Glass Edge (`LiquidGlassBorderBrush`):** A custom vertical brush gradient (`#55FFFFFF` top to `#08FFFFFF` bottom) providing realistic light refraction along borders.
2. **Inner Ambient Sheen (`LiquidGlassSheenBrush`):** Linear specular overlays reflecting ambient light across card surfaces.
3. **Fluid Spring Mechanics (`GlassSpringSpec`):** Interactive buttons, chips, and poster cards use bouncy damping physics (`DampingRatioMediumBouncy` + `StiffnessMedium`) for tactile feedback.
4. **OLED Contrast Layering:** Deep black glass containers keep content vivid with no muddy gray tones.

---

## 🛠️ Client Modding & Customization

Zippy is open-sourced to encourage community modding, custom themes, and client-side innovation.

### What You Can Mod
- **Themes & Colors:** Edit [`app/src/main/java/com/example/ui/theme/Color.kt`](app/src/main/java/com/example/ui/theme/Color.kt) to create AMOLED neon themes, cyberpunk palettes, or custom glass shades.
- **Player Controls:** Customize [`app/src/main/java/com/example/ui/player/VideoPlayer.kt`](app/src/main/java/com/example/ui/player/VideoPlayer.kt) with custom scrubbers, gesture controls, or audio visualizers.
- **Glass Components:** Add or restyle cards, badges, and pills in [`app/src/main/java/com/example/ui/components/LiquidGlassComponents.kt`](app/src/main/java/com/example/ui/components/LiquidGlassComponents.kt).
- **Navigation & Layout:** Reorganize home tabs, hero carousels, or catalog filters.

### 🌐 Zero-Setup Backend Connectivity
All mods work out of the box. The client connects to the centralized **CinePulse Cloud Media Network** (`movieapi.zarhosting.dpdns.org`); no API tokens, databases, or server deployments are required.

> [!NOTE]
> *To protect cloud infrastructure integrity and prevent abuse, backend server protocols, reverse engineering, and internal API documentation are reserved and proprietary. Please focus modifications and contributions on the Android client application.*

---

## 🚀 Building from Source

### Prerequisites
- **JDK:** OpenJDK 17 or 21
- **Android SDK:** Compile SDK 36, Min SDK 24 (Android 7.0+)
- **Gradle:** 9.3.1 (included via the Gradle Wrapper)

### Quick Start
```bash
# Clone the repository
git clone https://github.com/abhishekgt5360/zippy.git
cd zippy

# Build Debug APK
./gradlew assembleDebug

# Build Release APK (requires your own signing keystore)
./gradlew assembleRelease
```

The output APK is written to:

```
app/build/outputs/apk/release/Zippy-<version>.apk
```

---

## 📦 Project Architecture

```
zippy/
├── app/
│   ├── src/main/java/com/example/
│   │   ├── MainActivity.kt               # Edge-to-edge, PiP & window lifecycle
│   │   ├── MainViewModel.kt              # App state & playback controller
│   │   ├── data/
│   │   │   ├── local/                    # Room DB (history & watchlist)
│   │   │   ├── model/                    # Kotlin Moshi data models
│   │   │   └── repository/               # Repository layer with offline caching
│   │   ├── ui/
│   │   │   ├── components/               # Liquid Glass primitives, hero, cards
│   │   │   ├── player/                   # ExoPlayer engine, subtitles, gestures
│   │   │   ├── screens/                  # Home, Browse, Detail, Search, Settings
│   │   │   └── theme/                    # Color tokens, brushes, typography
│   │   └── util/                         # Download helper, SecureVault, device utils
│   ├── build.gradle.kts                  # App dependencies & signing config
│   └── proguard-rules.pro                # Code & resource shrinking
├── gradle/                               # Version catalogs & wrapper
├── release/
│   └── README.md                         # Release notes
└── LICENSE                               # Client Modding License
```

---

## 🏷️ Keywords & Topics

`android-streaming-app` • `streaming-client` • `movie-streaming` • `video-streaming` • `media-streaming` • `exoplayer-streaming` • `media3-exoplayer` • `hls-streaming` • `jetpack-compose` • `material3` • `kotlin` • `anime-streaming` • `video-player`

---

## 📄 License & Terms

This project is licensed under the **MIT / CinePulse Client Modding License** — see the [LICENSE](LICENSE) file for complete details. The original copyright notice belongs to Ahmed Ishtiaque Zarir (ImAhmedZarir) and is preserved as required.

You are welcome to mod, customize, theme, and fork the client application. Respect the CinePulse cloud media network and enjoy streaming.

<br/>

<div align="center">
  <sub>Zippy maintained by <a href="https://github.com/abhishekgt5360">INSP Abhishek Prasad</a> • based on CinePulse by <a href="https://github.com/ImAhmedZarir">Ahmed Ishtiaque Zarir</a></sub>
</div>
