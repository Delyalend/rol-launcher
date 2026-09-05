package rol.devkit;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the base archive: the game folder packed into a zip split into
 * byte volumes (base.zip.001, .002, ...). Streaming — no giant temp files.
 *
 * The launcher concatenates the volumes in order and extracts them with a
 * ZipInputStream, so a plain byte split is enough. Entries are STORED
 * (the game data is already compressed internally).
 *
 * Usage: build-base &lt;game_folder&gt; [--out dir] [--name base-v0.1.0.zip]
 *        [--volume 1900m]
 */
final class BaseBuilder {

    private BaseBuilder() {}

    static void run(List<String> args) throws IOException {
        String folder = null;
        String name = "base.zip";
        String outDir = "release";
        long volumeSize = 1900L * 1024 * 1024;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            switch (a) {
                case "--out" -> outDir = args.get(++i);
                case "--name" -> name = args.get(++i);
                case "--volume" -> volumeSize = parseSize(args.get(++i));
                default -> {
                    if (folder == null && !a.startsWith("--")) folder = a;
                    else throw new IllegalArgumentException("unknown argument: " + a);
                }
            }
        }
        if (folder == null) {
            throw new IllegalArgumentException("required: <game_folder>");
        }
        Path root = Path.of(folder).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Folder not found: " + root);
        }
        Files.createDirectories(Path.of(outDir));

        List<Path> files;
        try (var walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }

        SplittingOutputStream out = new SplittingOutputStream(Path.of(outDir), name, volumeSize);
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.setLevel(Deflater.NO_COMPRESSION);
            for (Path p : files) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                ZipEntry entry = new ZipEntry(rel);
                entry.setTime(Files.getLastModifiedTime(p).toMillis());
                zip.putNextEntry(entry);
                Files.copy(p, zip);
                zip.closeEntry();
            }
        }
        System.out.println("Base archive: " + files.size() + " files");
        for (Path v : out.volumes()) {
            System.out.println("  " + v + " (" + Files.size(v) + " bytes)");
        }
    }

    /** Parses sizes like 1900, 1900m, 1.8g. */
    static long parseSize(String s) {
        String t = s.trim().toLowerCase();
        long mult = 1;
        if (t.endsWith("kb")) {
            mult = 1024L;
            t = t.substring(0, t.length() - 2);
        } else if (t.endsWith("k")) {
            mult = 1024L;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("mb")) {
            mult = 1024L * 1024;
            t = t.substring(0, t.length() - 2);
        } else if (t.endsWith("m")) {
            mult = 1024L * 1024;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("g")) {
            mult = 1024L * 1024 * 1024;
            t = t.substring(0, t.length() - 1);
        }
        return (long) (Double.parseDouble(t) * mult);
    }

    /** Splits the stream into volume files named name.001, name.002, ... */
    static final class SplittingOutputStream extends OutputStream {

        private final Path dir;
        private final String baseName;
        private final long volumeSize;
        private final List<Path> volumes = new ArrayList<>();
        private OutputStream current;
        private long written;

        SplittingOutputStream(Path dir, String baseName, long volumeSize) throws IOException {
            this.dir = dir;
            this.baseName = baseName;
            this.volumeSize = volumeSize;
            openNext();
        }

        private void openNext() throws IOException {
            if (current != null) {
                current.close();
            }
            Path file = dir.resolve(String.format("%s.%03d", baseName, volumes.size() + 1));
            current = new BufferedOutputStream(Files.newOutputStream(file,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
            volumes.add(file);
            written = 0;
        }

        @Override
        public void write(int b) throws IOException {
            if (written >= volumeSize) {
                openNext();
            }
            current.write(b);
            written++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                if (written >= volumeSize) {
                    openNext();
                }
                int chunk = (int) Math.min(len, volumeSize - written);
                current.write(b, off, chunk);
                off += chunk;
                len -= chunk;
                written += chunk;
            }
        }

        @Override
        public void close() throws IOException {
            if (current != null) {
                current.close();
            }
        }

        List<Path> volumes() {
            return volumes;
        }
    }
}
