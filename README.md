# RoLauncher — launcher and update system for Rise of Legends

Project for maintaining and distributing a modified version of
Rise of Nations: Rise of Legends (2006, Big Huge Games / Microsoft).

## Why

1. **Game distribution and updates.** The launcher downloads the current game
   version; when an update is released, it downloads only the changed files
   (hundreds of KB instead of 3 GB).
2. **Update notifications.** The launcher checks the manifest on startup and
   tells the player when an update is available.
3. **Version switching.** The base archive plus a chain of update packages is
   stored; any version can be rebuilt from the base by applying packages.
4. **Order in development.** Mod sources, tools and the version manifest live
   in git with full change history.

## How it works

```
[developer]                  [GitHub]                     [player]
edits mod in mod/
   │
   ├─ devkit repack ─ rebuilds .big archives of the game
   ├─ devkit build-release ─ builds update packages + manifest
   │
   ▼
GitHub Releases:
   • base-vX.Y.Z.7z.001/.002 — full game in volumes (first install)
   • update-vA-vB.zip        — changed files only
   • releases/manifest.json  — version manifest (stored in the repo)
   │
   ▼
Launcher (JavaFX):
   checks manifest.json → compares with installed version
   → downloads update packages → verifies SHA-256 → applies
   → launches legends.exe
```

There is no server side: everything is static — GitHub Releases for files
and a raw link to `manifest.json` from the repository
(not the GitHub API, which has a 60 requests/hour limit).

## Repository structure

| Folder | Contents |
|---|---|
| `mod/` | Mod sources: rule XMLs, nations, `.bhs` scripts, maps |
| `devkit/` | Java module: developer CLI tools (snapshots, diff, release builds) |
| `launcher/` | Java module: JavaFX launcher |
| `tools/` | Auxiliary scripts (Python `.big` repacker, legacy) |
| `releases/` | `manifest.json` — version manifest (source of truth for the launcher) |
| `docs/` | Architecture, release guide |

The project is a Maven multi-module build (`devkit` + `launcher`), Java 21
everywhere. The full game (2.9 GB) and built `.big` archives are **not**
stored in git — they live in GitHub Releases as artifacts.

## Roadmap

- [x] Project structure and documentation
- [x] Maven multi-module: `devkit` (CLI) + `launcher` (JavaFX skeleton)
- [x] `devkit snapshot` — game folder snapshot (files + SHA-256)
- [x] `devkit diff` — compare two snapshots (changed/added/removed)
- [x] `.big` archive repacking in Java (`devkit repack`, ported from Python)
- [x] `devkit build-release` — update packages for every version + manifest update
- [x] `devkit build-base` — split-zip base archive volumes
- [x] Launcher main screen: installed/latest version, update check, game launch
- [x] Launcher: download with resume, install from base volumes, apply update packages
- [x] Launcher: version switching (in place, forward packages / backward via base)
- [x] First game release (`v0.1.0`) published to GitHub Releases
- [x] First camera update (`v0.2.0`) published and installed through the launcher
- [x] First portable launcher release (`launcher-v0.1.0`) published
- [x] Launcher hardening release (`launcher-v0.1.1`) published
- [x] Modern UI and existing-folder detection release (`launcher-v0.1.2`) published

## Build

Requires JDK 21 and Maven (or an IDE with Maven support, e.g. IntelliJ IDEA).

```
mvn -q package                     # build both modules
java -jar devkit/target/devkit-*.jar snapshot "C:\path\to\game"
java -jar devkit/target/devkit-*.jar diff old.json new.json
java -jar devkit/target/devkit-*.jar repack "C:\modded_game" "C:\pristine_game"
java -jar devkit/target/devkit-*.jar build-release "C:\path\to\game" --version v0.2.0
java -jar devkit/target/devkit-*.jar build-base "C:\path\to\game" --out release --name base-v0.1.0.zip
```

`devkit` builds without Maven too:
`javac -d out devkit/src/main/java/rol/devkit/*.java`

## Conventions

- Versions: `vX.Y.Z` (semver), branches: `main` (stable) and `dev` (experiments)
- Files changed between versions are described only by `releases/manifest.json`
- The base game archive is never trimmed — it is a full copy of the original game
