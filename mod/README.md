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

## Enabling CTW heroes in Skirmish/LAN

Campaign heroes are not enabled for regular Skirmish merely by adding
`HERO3`, `HERO4`, etc. to `Data/tribes/*.xml`. The game discovers regular
heroes from their unit definitions in `unitrules.xml`, and each definition
must have the faction bitmask set on the base unit and all of its level
variants:

```text
Alin  = 0001
Cuotl = 0100
Vinci = 1000
```

`0000` means that the unit belongs to no faction and it will not appear in
Skirmish, even when the XML parses correctly. For a CTW hero, copy the unit
definitions from `Data/tribes/ctw/CTWHeroes.xml` into `Data/unitrules.xml`,
remove empty duplicate `CODETAG` elements where required, and set the
correct `TRIBE_MASK` on every level (normally five definitions per hero).

The archive step is important. The installation contains multiple patch
layers, not just the three common archives. Apply the resulting
`Data/unitrules.xml` to every `mod_data.big` and every
`multiplayer_data.big` under `BIGS/`, `BIGS/patch8/`, and
`BIGS/patches/*/`. Always make a backup before patching and verify by
extracting one archive from an older patch layer as well as the newest one.

The tested safe hero set is:

- Vinci: Battaglion, Carlini, Distruzio, Venza;
- Alin: Andromolek, Arri, Belisari;
- Cuotl: Kakoolha, Yontash.

Do not import the full campaign craft/spell section blindly. Campaign-only
spell chains can reference unavailable entries and cause startup errors such
as `Glass Prison Shard ... in SPELLS`. Keep the hero unit definitions and
faction masks separate from campaign spell migration, which must be tested
hero by hero.

### Enabling hero abilities

The hero unit definitions alone are not enough: the ability tree is read from
matching entries in `Data/craftrules.xml`. The multiplayer archive normally
does not contain the campaign-only ability entries, so a selected hero can
appear in the hero list with an empty ability panel.

For the enabled set, copy only the entries whose `WHERE` field belongs to the
selected heroes into the multiplayer craftrules data. Translate these legacy
campaign labels to the current unit names:

- `Adromolek, the Dark Prince` → `Andromolek, Vizier of Al-Rukh`;
- `Carlini, Sergeant of Miana` → `Carlini, General of Miana`;
- `Venza, Lieutenant of Miana` → `Venza, Commander of Pirata`.

The tested patch contains 112 ability entries for Andromolek, Arri, Belisari,
Battaglion, Carlini, Distruzio, Venza, Kakoolha and Yontash. Do not copy the
entire campaign craftrules file: that imports excluded campaign variants and
can reintroduce invalid campaign-only spell references. Apply the same
craftrules replacement to every `mod_data.big` and `multiplayer_data.big`
layer, including `BIGS/patches/*`.

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
