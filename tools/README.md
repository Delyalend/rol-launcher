# Tools

The main tools live in the Java `devkit/` module: `snapshot`, `diff`,
`repack`, `build-release`. This folder is for legacy scripts only.

| File | Purpose | Status |
|---|---|---|
| `repack_big.py` | Python version of `.big` repacking | legacy: ported to `devkit repack`, kept as reference |

## devkit quick start (Java)

```
# without Maven:
javac -d out devkit/src/main/java/rol/devkit/*.java
java -cp out rol.devkit.Main snapshot "C:\path\to\game" snapshot-v0.2.0.json
java -cp out rol.devkit.Main diff snapshot-v0.1.0.json snapshot-v0.2.0.json
java -cp out rol.devkit.Main repack "C:\modded_game" "C:\pristine_game"

# with Maven:
mvn -q package
java -jar devkit/target/devkit-0.1.0-SNAPSHOT.jar snapshot "C:\path\to\game"
```

`repack` overwrites the 3 copies of `mod_data.big` in the game folder
(`BIGS\`, `BIGS\patch8\`, `BIGS\patches\patch8\`); originals are copied to
the backup folder (by default `_bigs_backup_original` next to the game).
Compiled (bxml) entries are never touched.

## Inspecting and extracting `.big` archives

The devkit also has read-only archive commands. List an archive:

```
java -cp out rol.devkit.Main list-big "C:\path\to\game\BIGS\interface.big"
```

Extract all entries, or only named assets:

```
java -cp out rol.devkit.Main extract-big "C:\path\to\game\BIGS\interface.big" out\interface
java -cp out rol.devkit.Main extract-big "C:\path\to\game\BIGS\interface.big" out\ui menu_buttons.tga gold_buttons.tga
```

The extractor validates output paths and never modifies the source archive.

Game UI files named `.tga` are DDS images internally. Convert them to PNG
for JavaFX with:

```
java -cp out rol.devkit.Main convert-image out\ui\art\interface\buttons\gold_buttons.tga launcher-theme\gold_buttons.png
```

The converter supports the uncompressed 32-bit UI textures and DXT5 textures
used by the original interface archive.

Create a Windows icon with all common sizes embedded:

```
java -cp out rol.devkit.Main create-icon launcher/src/main/resources/rol/launcher/theme/logo.png launcher/RoLauncher.ico
```
