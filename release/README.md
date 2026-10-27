# Zippy Releases

## 📦 Current Release Line

- **Version:** 1.0 (versionCode 1)
- **Application ID:** `com.toolsetstudio.zippy`
- **Minimum OS:** Android 7.0 (API 24)
- **Target OS:** Android 16 (API 36)
- **Architecture:** Universal APK (ARM64, ARMv7, x86_64)

Build artifacts are intentionally **not committed** to this repository. APKs are attached to [GitHub Releases](https://github.com/abhishekgt5360/zippy/releases) instead.

---

## 🚀 Building a Release APK

Release builds require an upload keystore. Create one (or supply your own) and configure it in `app/build.gradle.kts` / `keystore.properties` before building:

```bash
# Debug build (signed with the debug key)
./gradlew assembleDebug

# Release build (requires a configured signing keystore)
./gradlew assembleRelease
```

The output is written to:

```
app/build/outputs/apk/release/Zippy-<version>.apk
```

> [!IMPORTANT]
> Never commit `.jks`, `.keystore`, `keystore.properties`, or `google-services.json`. These are already covered by `.gitignore`.

---

## 💎 Design & Visual Highlights

- **Strict 3-Color Cinema Palette:**
  - Zippy Crimson (`#EE1E50` / `#FF2A55`)
  - True OLED Pitch Black (`#000000`)
  - Crisp Pure White (`#FFFFFF`)
- **Apple iOS Liquid-Glass UI:** Translucent specular borders (`LiquidGlassBorderBrush`), ambient sheens, and fluid spring physics on all touch elements.
- **Edge-to-Edge Experience:** Seamless integration with system status and navigation bars with cutout-safe insets.

---

## ⚡ Performance & Streaming Engine

- **AndroidX Media3 ExoPlayer:** Adaptive 1080p streaming with instant seeking.
- **Picture-in-Picture (PiP):** Floating screen mode with automatic audio shutoff when the window is dismissed.
- **Subtitles & Multi-Audio:** UTF-8 SRT subtitle engine with auto-selected default tracks and 250ms sync intervals.
- **Background Downloads:** Android `DownloadManager` integration, saving media to `Downloads/Zippy/`.
- **Ultra-Low RAM Footprint:** Smooth 60fps operation even on 2GB RAM devices without GPU blur strain.

---

## 🛠️ Verification

After downloading an APK from the Releases page, verify its integrity against the SHA-256 hash published with that release:

```powershell
Get-FileHash .\Zippy-1.0.apk -Algorithm SHA256
```
