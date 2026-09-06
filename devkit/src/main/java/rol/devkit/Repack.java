package rol.devkit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Repacking of Rise of Legends .big archives (port of tools/repack_big.py).
 *
 * .big format (WAR-BUILDER): header + entry block
 * [u32 N][u32 N][u16 0][u8 0], entry = [u32 len][UTF-16 name][u32 0][u32 0]
 * [u32 offset][u32 size][u32 0][u32 ts][u32 len][UTF-16 ext][u16 0];
 * at offset: [u32 zsize][zlib|raw-deflate stream].
 *
 * Rules (learned the hard way):
 *  - compiled entries (ext="bxml", starting with 01 00 00 00) must NOT be
 *    touched — text inside them crashes the game on startup;
 *  - the mod is injected only into text entries; missing files are added as
 *    new text entries;
 *  - entries that fail to decompress are preserved as-is.
 */
final class Repack {

    private Repack() {}

    static final class Entry {
        String name;
        String ext;
        long ts;
        long size;
        byte[] raw;      // compressed bytes as stored in the archive
        byte[] payload;  // decompressed content (null if not decompressable)
        boolean keepRaw;

        Entry(String name, String ext, long ts, long size, byte[] raw, byte[] payload) {
            this.name = name;
            this.ext = ext;
            this.ts = ts;
            this.size = size;
            this.raw = raw;
            this.payload = payload;
        }
    }

    /** Run: repack &lt;game_dir&gt; &lt;pristine_dir&gt; [backup_dir] */
    static void run(String gameDir, String pristineDir, String backupDir) throws IOException {
        Path game = Path.of(gameDir).toAbsolutePath().normalize();
        Path pristine = Path.of(pristineDir).toAbsolutePath().normalize();
        Path backup = backupDir == null
                ? game.getParent().resolve("_bigs_backup_original")
                : Path.of(backupDir);
        Files.createDirectories(backup);

        Map<String, Path> modFiles = new TreeMap<>();
        collectLoose(game.resolve("Data"), game, modFiles);

        String[][] targets = {
                {"BIGS", "mod_data.big"},
                {"BIGS/patch8", "mod_data.big"},
                {"BIGS/patches/patch8", "mod_data.big"},
        };
        for (String[] t : targets) {
            Path src = pristine.resolve(t[0]).resolve(t[1]);
            Path dst = game.resolve(t[0]).resolve(t[1]);
            if (!Files.exists(src)) {
                System.out.println("SKIP (no source): " + src);
                continue;
            }
            rebuild(src, dst, backup, modFiles);
        }
    }

    private static void rebuild(Path src, Path dst, Path backup, Map<String, Path> modFiles)
            throws IOException {
        byte[] data = Files.readAllBytes(src);
        ParseResult parsed = parse(data);
        if (parsed == null) {
            System.out.println("ERROR: cannot parse " + src);
            return;
        }
        List<Entry> entries = parsed.entries;
        Files.copy(src, backup.resolve("orig_" + src.getFileName()),
                StandardCopyOption.REPLACE_EXISTING);

        int bxml = 0, replaced = 0;
        for (Entry e : entries) {
            if (e.payload != null && e.payload.length >= 4
                    && e.payload[0] == 1 && e.payload[1] == 0
                    && e.payload[2] == 0 && e.payload[3] == 0) {
                e.keepRaw = true; // never touch compiled entries
                bxml++;
                continue;
            }
            Path loose = modFiles.get(norm(e.name));
            if (loose != null) {
                e.payload = Files.readAllBytes(loose);
                e.raw = null;
                e.size = e.payload.length;
                e.ts = mtime(loose);
                replaced++;
            } else {
                e.keepRaw = true;
            }
        }

        int added = 0;
        for (Map.Entry<String, Path> m : modFiles.entrySet()) {
            boolean present = entries.stream()
                    .anyMatch(e -> !e.keepRaw && norm(e.name).equals(m.getKey()));
            if (present) {
                continue;
            }
            String archName = "." + '\\' + m.getKey().replace('/', '\\');
            byte[] content = Files.readAllBytes(m.getValue());
            Entry e = new Entry(archName, "", mtime(m.getValue()),
                    content.length, null, content);
            e.keepRaw = false;
            entries.add(e);
            added++;
        }

        byte[] out = build(data, parsed.block2Start, entries);
        Files.createDirectories(dst.getParent());
        Files.write(dst, out);
        ParseResult check = parse(out);
        boolean ok = check != null && check.entries.size() == entries.size();
        System.out.println(dst + ": entries=" + entries.size()
                + " (bxml untouched=" + bxml + ", text replaced=" + replaced
                + ", new=" + added + ") verify=" + (ok ? "OK" : "FAIL"));
    }

    // ---------- parsing and building ----------

    private record ParseResult(int block2Start, List<Entry> entries) {}

    private static ParseResult parse(byte[] b) {
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        for (int start = 0x14; start < 0x800; start++) {
            if (start + 8 > b.length) continue;
            int n1 = bb.getInt(start);
            int n2 = bb.getInt(start + 4);
            if (n1 <= 0 || n1 >= 1000 || n1 != n2) continue;
            int off = start + 8 + 3;
            List<Entry> entries = new ArrayList<>(n1);
            boolean ok = true;
            for (int i = 0; i < n1; i++) {
                NameRead nr = readName(b, off);
                if (nr == null) { ok = false; break; }
                off = nr.next;
                if (off + 28 > b.length) { ok = false; break; }
                long z1 = Integer.toUnsignedLong(bb.getInt(off));
                long z2 = Integer.toUnsignedLong(bb.getInt(off + 4));
                long foff = Integer.toUnsignedLong(bb.getInt(off + 8));
                long fsize = Integer.toUnsignedLong(bb.getInt(off + 12));
                long z3 = Integer.toUnsignedLong(bb.getInt(off + 16));
                long ts = Integer.toUnsignedLong(bb.getInt(off + 20));
                off += 24;
                NameRead extRead = readName(b, off);
                if (extRead == null) { ok = false; break; }
                off = extRead.next + 2; // u16 dummy
                if (foff + 4 > b.length) { ok = false; break; }
                long zsize = Integer.toUnsignedLong(bb.getInt((int) foff));
                if (zsize == 0 || foff + 4 + zsize > b.length) { ok = false; break; }
                byte[] raw = new byte[(int) zsize];
                System.arraycopy(b, (int) foff + 4, raw, 0, (int) zsize);
                byte[] payload = inflate(raw);
                if (payload != null && payload.length != fsize) { ok = false; break; }
                entries.add(new Entry(nr.name, extRead.name, ts, fsize, raw, payload));
            }
            if (ok && !entries.isEmpty()) {
                return new ParseResult(start, entries);
            }
        }
        return null;
    }

    private record NameRead(String name, int next) {}

    private static String readNameStr(byte[] b, int off) {
        if (off + 4 > b.length) return null;
        int len = ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (len > 500 || off + 4 + len * 2 > b.length) return null;
        return new String(b, off + 4, len * 2, StandardCharsets.UTF_16LE);
    }

    private static NameRead readName(byte[] b, int off) {
        String s = readNameStr(b, off);
        if (s == null) return null;
        int len = ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        return new NameRead(s, off + 4 + len * 2);
    }

    private static byte[] inflate(byte[] raw) {
        for (boolean nowrap : new boolean[]{false, true}) {
            Inflater inf = new Inflater(nowrap);
            try {
                inf.setInput(raw);
                ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(raw.length * 2, 64));
                byte[] buf = new byte[1 << 16];
                while (!inf.finished()) {
                    int n = inf.inflate(buf);
                    if (n == 0) {
                        if (inf.needsInput() || inf.needsDictionary()) {
                            throw new DataFormatException("incomplete");
                        }
                    }
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            } catch (DataFormatException e) {
                // try the next variant
            } finally {
                inf.end();
            }
        }
        return null;
    }

    private static byte[] build(byte[] original, int block2Start, List<Entry> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(original, 0, block2Start); // header and reference block as in the original
        writeInt(out, entries.size());
        writeInt(out, entries.size());
        writeShort(out, 0);
        out.write(0);

        List<byte[]> table = new ArrayList<>(entries.size());
        List<byte[]> payloads = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            byte[] nm = e.name.getBytes(StandardCharsets.UTF_16LE);
            byte[] ex = e.ext.getBytes(StandardCharsets.UTF_16LE);
            byte[] comp = e.keepRaw && e.raw != null ? e.raw : deflate(e.payload);
            ByteArrayOutputStream t = new ByteArrayOutputStream();
            writeInt(t, nm.length / 2);
            t.writeBytes(nm);
            writeInt(t, 0);
            writeInt(t, 0);
            writeInt(t, 0); // offset — filled in below
            writeInt(t, Math.toIntExact(e.size));
            writeInt(t, 0);
            writeInt(t, Math.toIntExact(e.ts));
            writeInt(t, ex.length / 2);
            t.writeBytes(ex);
            writeShort(t, 0);
            table.add(t.toByteArray());
            payloads.add(comp);
        }

        int tableLen = 0;
        for (byte[] t : table) tableLen += t.length;
        long dataOff = (long) out.size() + tableLen;
        long cur = dataOff;
        for (int i = 0; i < entries.size(); i++) {
            byte[] t = table.get(i);
            // offset is stored in t at position 4 + nameLen + 8
            int nmLen = entries.get(i).name.length() * 2;
            putInt(t, 4 + nmLen + 8, Math.toIntExact(cur));
            out.writeBytes(t);
            cur += 4 + payloads.get(i).length;
        }
        for (byte[] p : payloads) {
            writeInt(out, p.length);
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    private static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(9);
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        while (!d.finished()) {
            out.write(buf, 0, d.deflate(buf));
        }
        d.end();
        return out.toByteArray();
    }

    /**
     * Replaces one text entry in a big file (all three copies: BIGS,
     * BIGS\\patch8, BIGS\\patches\\patch8) with the given content.
     * Refuses to touch compiled (bxml) entries.
     */
    public static void patch(Path pristineDir, Path gameDir, Path backup,
                             String bigName, String entryName, byte[] content)
            throws IOException {
        String[][] targets = {
                {"BIGS", bigName},
                {"BIGS/patch8", bigName},
                {"BIGS/patches/patch8", bigName},
        };
        for (String[] t : targets) {
            Path src = pristineDir.resolve(t[0]).resolve(t[1]);
            Path dst = gameDir.resolve(t[0]).resolve(t[1]);
            if (!Files.exists(src)) {
                System.out.println("SKIP (no source): " + src);
                continue;
            }
            byte[] data = Files.readAllBytes(src);
            ParseResult parsed = parse(data);
            if (parsed == null) {
                throw new IOException("cannot parse " + src);
            }
            Files.copy(src, backup.resolve("orig_" + src.getFileName()),
                    StandardCopyOption.REPLACE_EXISTING);
            String key = norm(entryName);
            boolean replaced = false;
            for (Entry e : parsed.entries) {
                e.keepRaw = true; // untouched entries keep their original bytes
                if (norm(e.name).equals(key)) {
                    if (e.payload != null && e.payload.length >= 4 && e.payload[0] == 1) {
                        throw new IOException("refusing to patch compiled entry: " + e.name);
                    }
                    e.payload = content;
                    e.raw = null;
                    e.size = content.length;
                    e.keepRaw = false;
                    replaced = true;
                }
            }
            if (!replaced) {
                throw new IOException("entry not found: " + entryName + " in " + src);
            }
            byte[] out = build(data, parsed.block2Start, parsed.entries);
            Files.createDirectories(dst.getParent());
            Files.write(dst, out);
            ParseResult check = parse(out);
            System.out.println("patched " + dst + " (" + (check != null ? "OK" : "FAIL") + ")");
        }
    }

    // ---------- helpers ----------

    private static void collectLoose(Path dataDir, Path gameRoot, Map<String, Path> modFiles)
            throws IOException {
        try (var walk = Files.walk(dataDir)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                String name = p.getFileName().toString();
                if (!name.toLowerCase(Locale.ROOT).endsWith(".xml")) continue;
                String key = norm(gameRoot.relativize(p).toString());
                // these files live in multiplayer_data.big, and the schema is not needed at runtime
                if (key.equals("data/how_to_play.xml")
                        || key.equals("data/resourcerules_strings.xml")
                        || key.equals("data/unitrules.xsd")) {
                    continue;
                }
                modFiles.put(key, p);
            }
        }
    }

    private static String norm(String name) {
        String s = name.replace('\\', '/').toLowerCase(Locale.ROOT);
        while (s.startsWith("./")) s = s.substring(2);
        return s;
    }

    private static long mtime(Path p) throws IOException {
        return Files.getLastModifiedTime(p).to(TimeUnit.SECONDS);
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
        out.write((v >> 16) & 0xff);
        out.write((v >> 24) & 0xff);
    }

    private static void writeShort(ByteArrayOutputStream out, int v) {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
    }

    private static void putInt(byte[] arr, int off, int v) {
        arr[off] = (byte) (v & 0xff);
        arr[off + 1] = (byte) ((v >> 8) & 0xff);
        arr[off + 2] = (byte) ((v >> 16) & 0xff);
        arr[off + 3] = (byte) ((v >> 24) & 0xff);
    }
}
