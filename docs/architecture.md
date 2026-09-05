# Update system architecture

## Principle

The server stores three things:

1. **Base archive** — a full copy of the game, split into 7z volumes
   (GitHub limit: 2 GB per file; our game is ~2.9 GB → two volumes of ~1.5 GB).
   Needed only for the first install, updated rarely.
2. **Update packages** — `update-vA-vB.zip` containing *only the changed*
   files (the folder structure of the game is preserved inside). Small.
3. **Manifest** — `releases/manifest.json` in the repository. Describes all
   versions: date, changelog, base volumes, update-package chains and SHA-256
   of every installed game file for integrity checks.

The client (launcher) decides on its own what to download:

```
manifest.json (raw.githubusercontent.com, no API limits)
   │
   ├─ no game installed     → download base volumes, extract
   ├─ game is v0.1.0        → download update-v0.1.0-v0.2.0.zip
   ├─ game is v0.0.9        → download update-v0.0.9-v0.1.2.zip (single hop)
   └─ version matches       → download nothing
```

After applying a package the launcher verifies SHA-256 of every file against
the manifest — corrupted/incomplete files are re-downloaded.

## Why not the GitHub API

The GitHub API has a hard limit: 60 requests per hour without a token —
launchers of several players would hit it quickly. `manifest.json` lives in
the repository and is served via a raw link (`raw.githubusercontent.com`)
without such limits, CDN-cached. Release files (`*.7z`, `*.zip`) are also
served via `objects.githubusercontent.com` without practical download caps.

## Version switching

The launcher stores:

```
<launcher data folder>/
├── base/                  # extracted base archive (read-only)
├── cache/                 # downloaded update packages
└── versions/              # built versions (hard links / file copies)
```

Switching = building the target version from `base/` by applying packages
into a new `versions/` folder. For the last 2-3 versions it takes seconds
(packages are small). Periodically (about every 10 versions) we do a
"rebase": publish a new base archive, keeping the chains short.

## Manifest format

An example schema lives in `releases/manifest.json`. Key fields:

- `schema_version` — schema version (launcher compatibility)
- `latest` — id of the latest version
- `versions[]`:
  - `id`, `date`, `changelog`
  - `base.parts[]` — base archive volumes of this version (file, size, SHA-256)
  - `updates_from` — map "from which version to download which package"
    (package: file, size, SHA-256)
  - `files` — SHA-256 of every installed file (integrity checks)

An update package is self-contained: the zip contains changed/added files
(the folder structure is preserved) and a special `.rol-removed.txt` — the
list of files to delete, one per line. Launcher algorithm: extract the
package → delete files from the list → verify SHA-256 of all files against
the target version in the manifest.

The manifest and packages are produced by `devkit build-release`; the
manifest should never be edited by hand.

## Launcher (JavaFX)

Stack: Java 21 LTS, JavaFX, Maven (`javafx-maven-plugin`).
The project is a Maven multi-module build: module `devkit` (developer CLI
tools: snapshot/diff/release builds) and module `launcher` (the app itself).
Everything is Java — the Python `.big` repacker stays as a legacy reference
only.

### Launcher distribution

The launcher is a regular Java app packaged into a native Windows installer:

1. **jlink** builds a trimmed JRE from only the modules the app actually
   needs (~40-50 MB instead of ~300 MB full).
2. **jpackage** packs the app + JRE into an `.msi`/`.exe` installer
   (or a folder with `RoLauncher.exe` for a portable version).
   Players do not need Java installed.

The built installer is published to the same GitHub Releases
(`RoLauncher-setup-0.1.0.exe`) — players download it manually once.

### Launcher self-update

The manifest also carries the launcher version: `launcher_version`. On
startup the launcher compares it with the latest one from the manifest and,
if a new version exists, downloads the fresh installer (or jar) and
restarts itself after the update. Classic self-update scheme; as a first
step a simple "download and run the installer" flow is fine.

## Update package application (launcher side)

```
1. Read target version from manifest.json
2. Download update package for the installed version
3. Verify package SHA-256 from updates_from entry
4. Extract package over the game folder
5. Delete files listed in .rol-removed.txt
6. Verify SHA-256 of every file against the target version's files map
7. Record the new installed version locally
```
