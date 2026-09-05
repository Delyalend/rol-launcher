# Launcher (JavaFX)

Client application for installing, updating and launching the game.

## Stack

- Java 21 LTS, JavaFX
- Build: Maven + `javafx-maven-plugin`
- Windows packaging: `jlink` + `jpackage` → native `.exe`
  with a trimmed JRE (players do not need Java installed)

## Features (by screen)

1. **Main screen**
   - installed version and game path;
   - `manifest.json` check on startup → "Update vX.Y.Z available" banner;
   - buttons: "Play", "Update", "Select version", "Settings".
2. **Install**
   - "Download game" (downloads base archive volumes and extracts);
   - "Point to existing game folder" (attach without downloading);
   - download progress, resume after interruption.
3. **Versions**
   - list of versions from the manifest with changelogs;
   - switching: build the selected version from base + update packages.

## Update flow

```
manifest.json (raw.githubusercontent.com)
  → compare installed version with latest
  → download update packages (single hop, no chains)
  → verify SHA-256 of files against the manifest
  → extract over the installation
  → record the new version in the local state
```

## Module layout

```
launcher/
├── src/main/java/rol/launcher/
│   ├── App.java                 # JavaFX entry point, menu bar, main screen
│   ├── I18n.java                # bundle access, locale switching, {0} placeholders
│   ├── SettingsManager.java     # settings persistence (%APPDATA%\RoLauncher\)
│   ├── SettingsDialog.java      # settings window (language, manifest URL, game folder)
│   ├── Manifest.java            # typed view over the parsed manifest
│   ├── ManifestClient.java      # manifest.json download (java.net.http) and parsing
│   ├── Downloader.java          # downloads with progress and resume (HTTP Range)
│   ├── Updater.java             # update package application, split-zip base
│   │                            # extraction, SHA-256 verification
│   ├── VersionManager.java      # install/update orchestration, local version state
│   ├── GameRunner.java          # legends.exe launch
│   └── util/Json.java           # minimal JSON parser (no external dependencies)
└── src/main/resources/rol/launcher/
    ├── strings.properties       # English (default)
    └── strings_ru.properties    # Russian
```

The launcher is a **modular application** (module-info: requires
javafx.controls and java.net.http). JavaFX 11+ refuses to start in
classpath mode ("JavaFX runtime components are missing"), so module mode
is mandatory — IntelliJ runs it as `-m rol.launcher/rol.launcher.App`.
If third-party jars appear later, they go on the module path (automatic
modules). The base archive is a regular ZIP split into byte volumes
(`base.zip.001`, ...) — extraction needs only the JDK, no external
archive libraries.

## Localization

All UI texts go through `I18n.get("key")` — never hardcoded in code.
The bundles are `strings.properties` (English default) and
`strings_ru.properties` (Russian), loaded as UTF-8. To add a language:
create `strings_&lt;lang&gt;.properties`, add the locale to the menus in
`App` and the combo in `SettingsDialog`.

Language, manifest URL, game folder and the locally recorded installed
version are persisted in `%APPDATA%\RoLauncher\settings.properties`,
applied on startup. Quick switch: menu Language → English/Русский;
or File → Settings → Language.

On startup (and on "Check for updates") the launcher downloads the
manifest from the configured raw URL, compares the installed version with
`latest` and shows an update banner with the changelog. "Update" downloads
the package for the installed version (with resume), applies it (including
deletions from `.rol-removed.txt`) and verifies the touched files against
the manifest hashes. "Install game" downloads the split base volumes,
extracts them and verifies the whole installation. "Play" launches
`legends.exe` from the selected game folder (button enabled only when the
folder contains the executable). Downloads are cached in
`%APPDATA%\RoLauncher\cache`.

## Logging

All errors (with stack traces) and key events are written to
`%APPDATA%\RoLauncher\launcher.log` (rotates to `.old` at 1 MB).
The status line on the main screen is selectable — error text can be
copied. Help → Open log file opens the log directly; the About dialog
shows the log path.

Launcher state (installed version, paths) is stored locally in
`%APPDATA%\RoLauncher\`.

## Status

Design phase. Details in `docs/architecture.md`.
