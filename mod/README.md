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
selected heroes into the multiplayer craftrules data. The `WHERE` value must
remain the internal `TYPENAME` key, not the display name from the `NAME` field:

- display `Andromolek, Vizier of Al-Rukh` → `WHERE=Adromolek, the Dark Prince`;
- display `Carlini, General of Miana` → `WHERE=Carlini, Sergeant of Miana`;
- display `Venza, Commander of Pirata` → `WHERE=Venza, Lieutenant of Miana`.

The tested patch contains 112 ability entries for Andromolek, Arri, Belisari,
Battaglion, Carlini, Distruzio, Venza, Kakoolha and Yontash. Do not copy the
entire campaign craftrules file: that imports excluded campaign variants and
can reintroduce invalid campaign-only spell references. Apply the same
craftrules replacement to every `mod_data.big` and `multiplayer_data.big`
layer, including `BIGS/patches/*`.

Some copied abilities use a `reference_name` that points to a shared base
craft which is not tied to a hero. Those base records must be copied too. For
example, Distruzio's abilities reference `Gamble`; the multiplayer file needs
the campaign record with `TYPENAME=Gamble` even though its `WHERE` is `None`.
After merging, validate that every non-empty `reference_name` resolves to a
`TYPENAME` in the same craftrules file. Otherwise the game reports an
`Invalid keyphrase ... in SPELLS` error while loading the rules.

The same check applies to `CHAIN`, `FROM` and `GRAFT`: campaign-only chains
must not be left in multiplayer abilities. In the tested set, Andromolek's
Fire Shard upgrades referenced `Glass Prison Shard`; those four `CHAIN`
values were changed to `None`, while the Fire Shard upgrades themselves were
kept available.

Abilities that create buildings also need their referenced building records in
`buildingrules.xml`. Carlini's Defensive Placement abilities use `Defense
Gun`, `Defense Gun 3` and `Defense Gun 4`; all three base building records
must be present in every multiplayer archive layer.

Some ability `DATA0` values are bonus keys rather than spells or buildings.
Carlini's Scope uses `Scope Bonus`, `Scope Bonus 3` and `Scope Bonus 4`; these
records belong in `techrules.xml` and must also be copied to every multiplayer
archive layer.

If a craft `DATA0` value names a unit effect, the corresponding unit records
are required in `unitrules.xml`. Carlini's Heroic Charge uses
`Carlini Heroic Charge` and `Carlini Heroic Charge 4`; both records must be
copied along with the hero unit definitions.

The game's diagnostic window is a runtime rules-parser error, not a process
crash. A still-running `legends.exe` therefore does not prove that loading
completed successfully. Before distributing a build, validate cross-file
dependencies and inspect the startup window for any `ОШИБКА ВНГ` dialog.
For Venza's Flyer Upgrade abilities, the required bonus records are
`Flyer Upgrade Bonus`, `Flyer Upgrade Bonus 3` and `Flyer Upgrade Bonus 4` in
`techrules.xml`.

Venza's Signal Tank Blimp abilities similarly require the `Tank Blimp` unit
record in `unitrules.xml`.

The public tooling situation is limited: the original BHG runtime compiler is
not available, but the community has preserved the official RoL modding guides
and BIG tools. The Rise of Legends guide pack includes the BHG Scripting Kit,
Modpack and BIG Archiver. For `.bhs` scripts, the modern Big Huge Script
Language Server for VS Code provides syntax and semantic diagnostics intended
to match the game compiler. Neither replaces the game's runtime validation of
cross-file XML keys, so this project still needs its own dependency preflight.

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
