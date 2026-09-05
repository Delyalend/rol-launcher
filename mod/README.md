# Mod sources

This folder holds the **sources** of the game modifications: extracted rule
XMLs, nations, scripts and maps — in the same folder structure as the
installed game. This is the "source code": edits are made here, releases are
built from here.

## Structure (mirrors the game folders)

```
mod/
├── Data/            # rules.xml, unitrules.xml, tribes\... (nations), spells\
├── rules/           # .bhs scripts (AI, rules)
├── campaigns/       # .bhs campaign scripts
└── maps/            # maps (incl. those with new nations enabled)
```

## Important facts about the game (learned the hard way)

- All game data lives in `.big` archives (WAR-BUILDER format: UTF-16 names,
  zlib data). Game rules are in `BIGS\mod_data.big`.
- Rule files exist in the archive in **two forms**:
  - compiled (bxml) — used by multiplayer;
  - text — single player can override it with an extracted file from the
    `Data\` folder (the engine compares file timestamps).
- In multiplayer (even LAN) extracted files are ignored — changes must be
  embedded into the `.big` files themselves. Injection is only allowed into
  text entries: putting text into compiled entries crashes the game on
  startup.
- The "New Nations" mod (Motter) adds nations and scripts, but its
  distribution is missing some nations from the archives — that caused the
  "empty" spawns in LAN. Our releases must build archives via
  `devkit repack`, never copy ready-made `.big` files from third-party
  builds.
- The game itself rewrites `mod_data.big` on startup (updates its internal
  bookkeeping) — that is normal and does not affect integrity.

## What will NOT be here

- The full game copy (2.9 GB) — it lives in GitHub Releases.
- Built `.big` files — build artifacts, Releases only.

## Change workflow

1. Edit files here (or copy changed files from the game here).
2. Run `devkit repack` (`java -jar devkit/target/devkit-*.jar repack <modded_game> <pristine>`)
   — builds the new `mod_data.big`.
3. Test in the game (single player + LAN).
4. Commit to git — change history is visible to everyone.
5. Publish a release per `docs/release-guide.md`.
