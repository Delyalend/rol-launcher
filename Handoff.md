# RoLauncher Handoff

Read this file first when continuing work on the project.

## Project

RoLauncher is a Java 21 Maven multi-module project for distributing a
modified Rise of Legends game through GitHub Releases and a JavaFX launcher.

GitHub repository:

```text
https://github.com/Delyalend/rol-launcher
```

Current branch: `main`

The user prefers all source comments, README files, commit messages and
visible launcher text to be in English. The launcher itself supports English
and Russian through UTF-8 resource bundles.

## Current production state

Everything below is already working and was tested by the user:

1. A full v0.1.0 base game was built from:
   `C:\root\Rise Of Legends`
2. The base was published as two GitHub Release assets:
   - `base-v0.1.0.zip.001`
   - `base-v0.1.0.zip.002`
3. The v0.1.0 manifest is in:
   `releases/manifest.json`
4. The launcher has this baked-in manifest URL:

```text
https://raw.githubusercontent.com/Delyalend/rol-launcher/main/releases/manifest.json
```

5. v0.2.0 is published and tested by the user. It increases camera distance:
   - regular camera: 7500 → 20000;
   - extended camera, C key: 8600 → 40000;
   - initial zoom: 7500 → 12000.
6. The v0.2.0 update package is published in GitHub Release v0.2.0 and
   was successfully installed by the user's brother through the launcher.
7. The portable launcher release is published as:
   `launcher-v0.1.0`
8. The packaged executable must include `jdk.crypto.ec` and
   `jdk.crypto.cryptoki`. Omitting `jdk.crypto.ec` causes:
   `SSLHandshakeException: Received fatal alert: handshake_failure`
   against GitHub. A full JDK in IntelliJ hides this problem, so always test
   the packaged exe.

Releases page:

```text
https://github.com/Delyalend/rol-launcher/releases
```

## Latest commits

Recent history:

```text
70ef5ab Reuse verified downloads from the local cache
8fd74ba v0.2.0: increase camera zoom-out limits
3b4086f Fix TLS in the packaged exe: add jdk.crypto.ec to the runtime image
35aa82c Bake the manifest URL into the launcher
ad548b7 Add jlink/jpackage packaging config for the launcher
cf64f75 Fix download URLs: assets live in GitHub Releases, not next to the manifest
36795e1 Add file logging to the launcher
```

The working tree was clean at handoff time.

## Repository structure

```text
RoLauncher/
├── devkit/                 Java developer tools
├── launcher/               JavaFX launcher
├── mod/                    Mod source files
├── tools/                  Legacy Python reference scripts
├── releases/manifest.json  Real release manifest
├── releases/manifest.example.json
├── docs/                   Architecture and release guide
├── release/                Local release artifacts, ignored by git
├── README.md
├── PROJECT_STATUS.md
└── Handoff.md
```

## Devkit commands

The Java CLI is `rol.devkit.Main`.

```text
snapshot <folder> [file.json]
diff <old.json> <new.json>
repack <game_dir> <pristine_dir> [backup_dir]
patch <game_dir> <pristine_dir> <big_name> <entry_name> <replacement_file> [backup_dir]
build-base <game_folder> [--out dir] [--name base.zip] [--volume 1900m]
build-release <folder> --version vX.Y.Z [--changelog file]
              [--manifest file] [--out dir] [--recent N]
              [--base volume1,volume2] [--base-url release_asset_base_url]
```

Use Maven or compile the devkit directly. `out/` may contain local compiled
classes and is not source.

## Release workflow

### New game/mod version

1. Modify files under `mod/` or a working game copy.
2. If a `.big` text entry must change, use `devkit patch` or `devkit repack`.
   Never write text into a compiled `bxml` entry; the game crashes on startup.
3. Test single player and LAN.
4. Build `vX.Y.Z` with `build-release`.
5. Pass `--base-url` equal to:
   `https://github.com/Delyalend/rol-launcher/releases/download/vX.Y.Z`
   so base/update assets get absolute GitHub URLs in the manifest.
6. Commit and push `releases/manifest.json`.
7. Create GitHub Release `vX.Y.Z` and upload the generated update package(s).
8. Test the launcher update from the previous version.

### New launcher build

1. Build Java classes with Maven.
2. Build a jlink image containing:
   `rol.launcher,jdk.crypto.ec,jdk.crypto.cryptoki`.
3. Run jpackage to create the portable app image.
4. Zip the jpackage `RoLauncher/` directory.
5. Publish as a new release such as `launcher-v0.1.1`.
6. Test the exe itself, not only the IntelliJ run. Confirm the log contains
   `Manifest loaded, latest=...`.

## Launcher behavior

- Settings are stored in `%APPDATA%\RoLauncher\settings.properties`.
- Cache is `%APPDATA%\RoLauncher\cache`.
- Log is `%APPDATA%\RoLauncher\launcher.log`; old log rotates to
  `launcher.log.old` at 1 MB.
- Errors show a selectable message in the status field and full stack traces
  in the log.
- Help → Open log file opens the log.
- Downloader validates cached files by expected size + SHA-256 before making
  a request; corrupt files are deleted and redownloaded.
- Update packages are ZIPs containing changed files and optional
  `.rol-removed.txt`.
- Base archives are byte-split ZIP volumes; no 7z library is required.
- Version switching is in-place:
  forward uses one direct package; backward rebuilds from a base and applies
  the target package, then removes files not listed in the target manifest.

## Recommended next task

The next practical task is to publish a new launcher release containing the
cache verification commit (`70ef5ab`), for example `launcher-v0.1.1`, rebuild
the portable exe with crypto modules, and send that ZIP to the brother.

After that, make and publish a small real game v0.3.0 patch to validate:

- v0.2.0 → v0.3.0 update;
- cached package reuse;
- version switching forward/backward on real game files.

Potential later work:

- real `.msi`/`.exe` installer with shortcuts;
- launcher self-update;
- visible download size and better progress UI;
- verify-installation button;
- open-game-folder button;
- icon and Windows metadata;
- code signing for wider distribution.

## Important caution

Do not casually regenerate `releases/manifest.json` from an arbitrary game
folder. It contains 3921 real file hashes for v0.1.0 and v0.2.0 metadata.
When generating a new release, use the correct game copy and absolute
`--base-url`/asset URLs. Also remember that GitHub Release assets are NOT
next to the raw manifest URL; they need release download URLs.
