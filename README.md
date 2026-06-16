# Stylus Notes

[![Build APK](https://github.com/ywang0162/stylus-notes/actions/workflows/build-apk.yml/badge.svg)](https://github.com/ywang0162/stylus-notes/actions/workflows/build-apk.yml)

A deliberately simple Android handwriting note-taking app, built for the
**AYN Thor's secondary (bottom) screen** with an active stylus. It's a single
full-screen drawing surface with a compact, horizontally-scrolling toolbar so
the whole UI fits a short, wide secondary display.

## Download

Grab the ready-to-install APK from the
**[latest release](https://github.com/ywang0162/stylus-notes/releases/tag/v1.0)**
(`stylus-notes-debug.apk`). It's rebuilt automatically by GitHub Actions on every
push to `main`. It's debug-signed, so on the device enable "install unknown apps"
for your browser/file manager before sideloading, or use `adb install`.

## Features

- **Pressure-sensitive ink** — line width follows stylus pressure (tunable).
- **Palm rejection** — "stylus only" mode ignores finger and palm touches so you
  can rest your hand on the screen while writing. Toggle it off to draw with a
  finger.
- **Low latency** — committed strokes are cached to a bitmap; only the live
  stroke is redrawn each frame, and the view requests unbuffered input.
- **Pen + eraser**, 4 ink colors (black / blue / red / green), 3 pen widths.
- **Undo / redo**, clear page.
- **Multi-page notebook** — flip between pages, add new pages. Pages auto-save.
- **Export to gallery** — saves the current page as a PNG to
  `Pictures/StylusNotes`.

Notes are stored as **vector strokes** (JSON, one file per page) in the app's
private storage, so they stay small and survive restarts. Nothing leaves the
device unless you export a PNG.

## Project layout

```
app/src/main/
├── AndroidManifest.xml
├── java/com/stylusnotes/app/
│   ├── MainActivity.kt     # toolbar wiring, page navigation, autosave, export
│   ├── DrawingView.kt      # custom canvas: stylus input, pressure, palm rejection
│   ├── Stroke.kt           # vector stroke model
│   └── NoteStorage.kt      # page persistence (JSON) + PNG export (MediaStore)
└── res/                    # layout, icons, theme
```

## Build

You need the Android SDK (API 34) and a JDK 17. The easiest path is Android
Studio; a CLI build also works.

### Option A — Android Studio (recommended)

1. **File → Open** and select this `StylusNotes` folder.
2. Let it sync Gradle (it will install any missing SDK bits and write
   `local.properties` automatically).
3. Plug in the Thor with USB debugging on, pick it as the target, and **Run**.

### Option B — command line

```bash
# Tell Gradle where your SDK is (or set ANDROID_HOME):
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

# Build a debug APK:
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk

# Install onto a connected device:
./gradlew installDebug
# or:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A Gradle wrapper (`./gradlew`, Gradle 8.7) is included, so you don't need a
system Gradle — only a JDK 17 on your PATH.

## Installing on the AYN Thor

1. On the Thor: **Settings → About → tap Build number 7×** to enable Developer
   Options, then enable **USB debugging**.
2. Connect it to your computer over USB and accept the debugging prompt.
3. `adb install -r app-debug.apk` (or just Run from Android Studio).

## Getting it onto the bottom screen

The app is a normal resizable, rotatable activity, so it adapts to whatever
resolution and orientation the secondary display uses — there's no hard-coded
screen size.

- **Easiest:** launch the app, then use the Thor's own screen-management gesture
  / quick-settings toggle to move or mirror the app to the bottom screen
  (dual-screen handhelds ship this in their system UI).
- **Via ADB**, you can launch it directly on a specific display. List displays,
  then start the activity on the secondary one:

  ```bash
  adb shell dumpsys display | grep -i "Display Id"      # find the bottom screen's id
  adb shell am start -n com.stylusnotes.app/.MainActivity --display <displayId>
  ```

## Tuning

Most feel-related knobs live in `DrawingView.kt`:

- `pressureSensitivity` (0 = constant width, 1 = fully pressure-driven; default 0.8)
- `strokeWidth` / `eraserWidth`
- `stylusOnly` (default `true` — palm rejection on)

Pen widths and ink colors are defined in `MainActivity.kt`
(`widthsDp` and the `selectColor` calls).

## Requirements

- minSdk 29 (Android 10), targetSdk/compileSdk 34. The Thor runs well above this.
