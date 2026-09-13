# EN–PL Dictionary for Android — Kotlin MVP 0.2

Minimal offline StarDict reader written in Kotlin. The APK contains **no dictionary data**.

## Dictionary folder

On first launch tap **Folder** and select a directory with one or more StarDict dictionaries. A dictionary is recognized when the same directory contains a matching trio:

- `name.ifo`
- `name.idx`
- `name.dict`

Subfolders are scanned recursively (up to 6 levels). The app persists read access through Android's Storage Access Framework, so no broad storage permission is needed. Multiple dictionaries can live in the selected folder; exact matches are shown one after another with the dictionary name as a heading.

Compressed `.dict.dz` files are not supported yet; use uncompressed `.dict`.

## Features

- Kotlin / Android SDK 35, minSdk 23.
- Reads StarDict directly from the selected SAF folder; no extraction and no bundled ZIP.
- Multiple StarDict dictionaries in one selected tree.
- Exact lookup plus prefix suggestions.
- Full dark/light rendering; dictionary HTML colors are remapped for dark mode.
- Clickable `bword://...` links.
- Offline Android TTS buttons for UK (`en-GB`) and US (`en-US`).
- External lookup entry point intended for BOOX NeoReader / ColorDict-compatible callers.
- Accepts `colordict.intent.action.SEARCH`, Android `ACTION_SEARCH`, `PROCESS_TEXT`, and `SEND`.
- No INTERNET permission; non-`bword://` links are blocked.

## Local Windows build

Requirements:

1. JDK 17.
2. Android SDK with API 35 (Android Studio is the easiest way to install it).
3. PowerShell 5+ / PowerShell 7.

From the project directory:

```powershell
.\build.ps1
```

Optional clean build:

```powershell
.\build.ps1 -Clean
```

Release variant (unsigned unless you add signing configuration):

```powershell
.\build.ps1 -Variant Release
```

The script uses `gradlew.bat` when present, then a system `gradle`, and otherwise downloads Gradle 8.9 into `.tools`. A successful debug build is copied to:

`ENPLDictionary-debug.apk`

## BOOX / NeoReader integration

The exported `LookupActivity` declares `colordict.intent.action.SEARCH` and also accepts the common Android text/search intents. Whether a specific BOOX firmware lists it in NeoReader's third-party dictionary chooser still needs verification on-device.
