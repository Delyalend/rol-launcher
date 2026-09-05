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
│   ├── App.java                 # JavaFX entry point, menu bar, main view
│   ├── I18n.java                # ResourceBundle access, locale switching
│   ├── SettingsManager.java     # settings persistence (%APPDATA%\RoLauncher\)
│   ├── SettingsDialog.java      # settings window (language selection)
│   │   # planned:
│   ├── ManifestClient.java      # manifest.json download and parsing
│   ├── Downloader.java          # downloads with progress and resume
│   ├── Updater.java             # update package application, hash checks
│   ├── VersionManager.java      # installed versions, switching
│   └── GameRunner.java          # legends.exe launch
└── src/main/resources/rol/launcher/
    ├── strings.properties       # English (default)
    └── strings_ru.properties    # Russian
```

## Localization

All UI texts go through `I18n.get("key")` — never hardcoded in code.
The bundles are `strings.properties` (English default) and
`strings_ru.properties` (Russian), loaded as UTF-8. To add a language:
create `strings_&lt;lang&gt;.properties`, add the locale to the menus in
`App` and the combo in `SettingsDialog`.

Language is persisted in `%APPDATA%\RoLauncher\settings.properties`
(`language=en|ru`), applied on startup. Quick switch: menu
Language → English/Русский; or File → Settings → Language.

Launcher state (installed version, paths) is stored locally in
`%APPDATA%\RoLauncher\`.

## Status

Design phase. Details in `docs/architecture.md`.
