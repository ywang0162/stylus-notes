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

- **Minimal track-and-show drawing** — one finger (or stylus) draws constant-width
  ink, rendered as smooth quadratic curves through the touch points. No
  prediction, pressure, or other gimmicks — it just tracks the finger and shows
  the line, with each segment baked once so rendering stays fast.
- **Two-finger pinch to zoom, drag to pan** — zoom in for detail or out for the
  overview; one finger keeps drawing. Dragging past the bottom grows the note by
  another page, so pages flow down as you write (no "add page" button).
- **Home screen** — all your notes are listed as a thumbnail grid. Tap to open,
  tap **+** to start a new one, long-press to rename or delete.
- **Optional palm rejection** — a "stylus only" toggle ignores finger/palm
  touches for pen-only writing.
- **Pen + eraser**, 4 ink colors (black / blue / red / green), 3 pen widths,
  **undo / redo**, clear.
- **Export to gallery** — saves the whole note as a PNG to `Pictures/StylusNotes`.

Each note is stored as **vector strokes** (JSON) in the app's private storage,
with a small thumbnail for the home grid, so notes stay tiny and survive
restarts. Nothing leaves the device unless you export a PNG.

## Project layout

```
app/src/main/
├── AndroidManifest.xml
├── java/com/stylusnotes/app/
│   ├── HomeActivity.kt     # launcher: notes grid, new/rename/delete
│   ├── NotesAdapter.kt     # RecyclerView adapter for the notes grid
│   ├── NoteActivity.kt     # the editor: toolbar, autosave, thumbnail, export
│   ├── DrawingView.kt      # scrolling canvas: finger/stylus input, two-finger pan, pages
│   ├── Stroke.kt           # vector stroke model (document coordinates)
│   └── NotesRepository.kt  # per-note persistence (JSON) + thumbnails + PNG export
└── res/                    # layouts, icons, theme
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
  adb shell am start -n com.stylusnotes.app/.HomeActivity --display <displayId>
  ```

## Tuning

Most feel-related knobs live in `DrawingView.kt`:

- `widthVariation` (0 = constant width, 1 = full speed/pressure-driven; default 0.85)
- `smoothing` (input jitter damping for finger writing; default 0.4)
- `slowDpPerMs` / `fastDpPerMs` / `taperMin` — the speed→width mapping
- `strokeWidth` / `eraserWidth`
- `stylusOnly` (default `false` — finger drawing on; set true for palm rejection)

A "page" is one screen tall; the document grows a page each time you pull past
the bottom with two fingers. Pen widths and ink colors are defined in
`NoteActivity.kt` (`widthsDp` and the `selectColor` calls).

## Requirements

- minSdk 29 (Android 10), targetSdk/compileSdk 34. The Thor runs well above this.
