# RoLauncher Project Status

Last updated: 2026-09-06

## Current state

The project is a Java 21 Maven multi-module project for distributing and
updating a modified Rise of Legends installation.

Repository: https://github.com/Delyalend/rol-launcher
Branch: `main`

Published releases:

- `v0.1.0` — full base game archive in two split ZIP volumes.
- `v0.2.0` — camera zoom-out patch, update package only (~4 MB).
- `launcher-v0.1.0` — portable launcher ZIP with native `RoLauncher.exe`
  and bundled runtime.

Manifest URL baked into the launcher:

```text
https://raw.githubusercontent.com/Delyalend/rol-launcher/main/releases/manifest.json
```

## What has been implemented

### Developer tools (`devkit`)

- `snapshot` — scans a folder and writes file sizes + SHA-256 hashes.
- `diff` — compares two snapshots.
- `repack` — embeds mod XML files into Rise of Legends `.big` archives.
- `patch` — replaces one text entry in the three relevant `.big` copies.
- `build-base` — streams a complete game folder into split ZIP volumes:
  `base.zip.001`, `.002`, etc. Default volume size is 1900 MB.
- `build-release` — scans a new game version, builds update packages from
  every existing version, writes `.rol-removed.txt`, and updates the manifest.
- All tools are Java; `tools/repack_big.py` remains as a legacy reference.

### Launcher (`launcher`)

- JavaFX UI with English and Russian localization via UTF-8 resource bundles.
- Settings dialog:
  - language;
  - manifest URL override;
  - game folder selection.
- Manifest checking via `java.net.http`.
- Main screen shows installed/latest versions and changelog.
- Game launch via `legends.exe`.
- Downloading with HTTP Range resume and `.part` files.
- Verified download cache in `%APPDATA%\RoLauncher\cache`:
  size + SHA-256 are checked before network access.
- Fresh installation from split ZIP base volumes.
- Applying update packages and deleting files from `.rol-removed.txt`.
- Full SHA-256 verification after installation/update.
- In-place version switching:
  - forward: one direct update package;
  - backward/far jump: rebuild from the nearest base version and apply a package.
- Error logging to `%APPDATA%\RoLauncher\launcher.log`, with stack traces,
  rotation at 1 MB, selectable status text, and Help → Open log file.
- Portable native Windows package built with `jlink` + `jpackage`.
- Packaged runtime MUST contain `jdk.crypto.ec` and `jdk.crypto.cryptoki`.
  Without `jdk.crypto.ec`, GitHub HTTPS fails with
  `SSLHandshakeException: Received fatal alert: handshake_failure`.

## The first real patch

The first real game patch is `v0.2.0`:

- source: `mod/Data/gamecamera.xml`;
- standard camera max distance: `7500` → `20000`;
- extended camera max distance (C key): `8600` → `40000`;
- start zoom distance: `7500` → `12000`;
- patched three `multiplayer_data.big` copies;
- update package contains only:
  - `BIGS/multiplayer_data.big`;
  - `BIGS/patch8/multiplayer_data.big`;
  - `BIGS/patches/patch8/multiplayer_data.big`.

The update was installed and tested successfully by the user. The camera
zoom-out change works in the game.

## Important release locations

Local project:

```text
C:\root\RoLauncher
```

Release artifacts:

```text
C:\root\RoLauncher\release
```

Portable launcher build:

```text
C:\root\RoLauncher\launcher\target\RoLauncher-portable-latest.zip
```

Game source used for the base v0.1.0 release:

```text
C:\root\Rise Of Legends
```

## Current stopping point

The update pipeline and portable launcher are working in production:

1. brother downloads `launcher-v0.1.0`;
2. launcher connects to GitHub over HTTPS;
3. launcher downloads and installs v0.1.0 from the two base volumes;
4. launcher detects v0.2.0;
5. launcher downloads the ~4 MB camera update;
6. launcher applies and verifies it;
7. game camera can zoom farther out.

The latest uncommitted feature is download-cache verification, committed as
`70ef5ab`. The working tree was clean at the last check.

## Recommended next steps

### Priority 1 — launcher distribution/update

- Publish the newest portable launcher build after cache changes as a new
  launcher release, for example `launcher-v0.1.1`.
- Tell the brother to replace the previous launcher ZIP.
- Consider building a real Windows installer (`.msi` or `.exe`) with
  `jpackage`; portable ZIP already works.
- Later add launcher self-update using a separate launcher release metadata
  section in the manifest.

### Priority 2 — real version/update testing

- Make another small game/mod change and publish `v0.3.0`.
- Verify update from v0.2.0 → v0.3.0.
- Verify version switching forward and backward on real game files.
- Verify cache hits: repeated update/version switch should not redownload
  files whose size and SHA-256 match.

### Priority 3 — quality improvements

- Show download size before update.
- Improve progress display with current bytes and total bytes.
- Add a visible "Verify installation" button.
- Add an "Open game folder" button.
- Add richer launcher logging around cache hits and package application.
- Add launcher icon and Windows metadata.
- Consider code signing if distributing beyond a few trusted users.

## Known architecture decisions

- GitHub Releases stores large artifacts; the repository stores source,
  manifests, tools and launcher code.
- The full game is never trimmed from the base release.
- Base archives are split ZIP byte volumes, not 7z, so the launcher can
  extract them with the JDK only.
- Update packages are regular ZIP files.
- Each update package is generated directly from every previous version,
  avoiding long update chains.
- `.big` compiled (`bxml`) entries must not be replaced with text; doing so
  crashes the game at startup.
- The packaged launcher is modular and must be launched with the JavaFX
  module path, not a plain classpath.

## Useful commands

Build Java modules:

```bash
cd C:\root\RoLauncher
JAVA_HOME="C:\root\javas" C:\root\portable-tools\apache-maven-3.9.9\bin\mvn.cmd -q package
```

Build base archive:

```bash
java -cp out rol.devkit.Main build-base "C:\root\Rise Of Legends" \
  --out release --name base-vX.Y.Z.zip --volume 1900m
```

Build a release:

```bash
java -cp out rol.devkit.Main build-release "C:\root\Rise Of Legends" \
  --version vX.Y.Z --manifest releases/manifest.json --out release \
  --changelog release/changelog-vX.Y.Z.txt \
  --base-url https://github.com/Delyalend/rol-launcher/releases/download/vX.Y.Z
```

Create a launcher release image (must include crypto modules):

```bash
mvn -q -pl launcher -am package
jlink --module-path "<JavaFX win jars>;launcher/target/launcher-0.1.0-SNAPSHOT.jar" \
  --add-modules rol.launcher,jdk.crypto.ec,jdk.crypto.cryptoki \
  --output launcher/target/RoLauncherReleaseImage \
  --launcher RoLauncher=rol.launcher/rol.launcher.App \
  --strip-debug --no-header-files --no-man-pages
jpackage --type app-image --name RoLauncher --app-version 0.1.1 \
  --runtime-image launcher/target/RoLauncherReleaseImage \
  --module rol.launcher/rol.launcher.App --dest launcher/target/jpackage-release
```
