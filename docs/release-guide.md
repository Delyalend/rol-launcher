# Release guide

## One-time setup

1. Create a repository on GitHub (private or public) and connect it to the
   `RoLauncher/` folder.
2. Keep a "reference" game install folder (full game, no mod applied on top
   of the base rules — the mod reaches the game **only through built
   releases**, see below about `mod/`).

## Release cycle (to be automated further)

### 1. Mod edits

All changes are made in `mod/` (rule XMLs, nations, scripts, maps).
This is where the **sources** live — extracted files in the game's folder
structure: `mod/Data/...`, `mod/rules/...`.

### 2. .big repacking

If game rules changed, a new `mod_data.big` is built from the extracted mod
files with devkit:

```
java -jar devkit/target/devkit-0.1.0-SNAPSHOT.jar repack "C:\modded_game" "C:\pristine_game"
```

Important: `.big` archives contain two kinds of entries — compiled (bxml)
and text. The tool injects the mod **only into text entries**; compiled
entries must not be touched (the game crashes on startup otherwise).

### 3. Release build (update packages + manifest)

One command does everything: scans the new game folder, builds update
packages from EVERY old version in the manifest (players never accumulate
package chains), updates the manifest and sets the new version as `latest`:

```
java -jar devkit/target/devkit-0.1.0-SNAPSHOT.jar build-release "C:\path\to\game" \
    --version v0.2.0 --changelog changelog.txt \
    --manifest releases/manifest.json --out release
```

`release/` will contain `update-v0.1.0-v0.2.0.zip` and so on (inside each
package: changed and added files + a special `.rol-removed.txt` with the
deletion list). To build packages only for the last N versions: `--recent 3`.

Optionally save a snapshot of the new version for the archive/inspection
(not required for the release itself):

```
java -jar devkit/target/devkit-0.1.0-SNAPSHOT.jar snapshot "C:\path\to\game" releases/snapshots/vX.Y.Z.json
```

### 4. Base archive (rarely, on rebase)

Full game copy as a ZIP split into 1900 MB byte volumes (GitHub limit —
2 GB per file; the launcher concatenates the volumes and extracts with the
JDK). Built by devkit, streaming, no temp files:

```
java -jar devkit/target/devkit-0.1.0-SNAPSHOT.jar build-base "C:\path\to\game" \
    --out release --name base-v0.2.0.zip --volume 1900m
```

Produces `base-v0.2.0.zip.001`, `.002`… Attach them to the version via
`--base "release\base-v0.2.0.zip.001,release\base-v0.2.0.zip.002"`.
(Alternative: `7z a -tzip -v1900m base-v0.2.0.zip "C:\path\to\game\*"`
produces the same volume layout.)

### 5. Publishing

1. Create a GitHub Release with tag `vX.Y.Z`:
   - attach the update packages from `release/` (and base volumes, if rebased);
   - changelog goes into the release description.
2. Commit the updated `releases/manifest.json` to `main` — that is the
   "notification": launchers will see the new version on their next check.

## Rules

- Never touch `.big` files manually — only through `devkit repack`.
- Never trim anything from the base game version — the base is always full.
- The manifest is the single source of truth about versions; changes outside
  it do not count as a release.
- Before publishing, always verify: extract the update package into a clean
  game copy and run the game.
