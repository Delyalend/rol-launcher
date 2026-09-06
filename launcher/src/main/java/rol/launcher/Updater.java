package rol.launcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import rol.launcher.Downloader.ProgressListener;

/**
 * Applying updates and installing the base game, plus SHA-256 verification.
 *
 * Base archive: a regular zip split into byte volumes (base.zip.001,
 * base.zip.002, ...). Concatenating the volumes in order yields a valid
 * zip, so extraction works with the JDK only. Update packages are plain
 * zips with a special .rol-removed.txt entry listing files to delete.
 */
public final class Updater {

    public static final String REMOVED_ENTRY = ".rol-removed.txt";

    private Updater() {}

    /** Paths of all regular file entries in the package (without .rol-removed.txt). */
    public static List<String> packagePaths(Path packageZip) throws IOException {
        List<String> paths = new ArrayList<>();
        try (ZipFile zf = new ZipFile(packageZip.toFile(), StandardCharsets.UTF_8)) {
            Set<String> seen = new HashSet<>();
            for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements(); ) {
                String name = e.nextElement().getName();
                if (!name.equals(REMOVED_ENTRY) && !name.endsWith("/")) {
                    safeRelative(name);
                    if (!seen.add(name)) throw new IOException("Duplicate archive entry: " + name);
                    paths.add(name);
                }
            }
        }
        return paths;
    }

    /**
     * Extracts an update package over the game folder, then deletes the
     * files listed in .rol-removed.txt.
     */
    public static void applyPackage(Path packageZip, Path gameDir, ProgressListener listener)
            throws IOException {
        List<String> removed = new ArrayList<>();
        List<ZipEntry> entries = new ArrayList<>();
        try (ZipFile zf = new ZipFile(packageZip.toFile(), StandardCharsets.UTF_8)) {
            for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements(); ) {
                entries.add(e.nextElement());
            }
            int done = 0;
            Set<String> seen = new HashSet<>();
            for (ZipEntry entry : entries) {
                String name = entry.getName();
                if (!seen.add(name)) throw new IOException("Duplicate archive entry: " + name);
                if (name.equals(REMOVED_ENTRY)) {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(
                            zf.getInputStream(entry), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            line = line.trim();
                            if (!line.isEmpty()) {
                                safeRelative(line);
                                removed.add(line);
                            }
                        }
                    }
                } else if (!entry.isDirectory()) {
                    Path dest = safeResolve(gameDir, name);
                    Files.createDirectories(dest.getParent());
                    try (InputStream in = zf.getInputStream(entry)) {
                        Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                done++;
                if (listener != null) {
                    listener.onProgress(done, entries.size());
                }
            }
        }
        for (String rel : removed) {
            Files.deleteIfExists(safeResolve(gameDir, rel));
        }
    }

    /**
     * Extracts the base game from split zip volumes (concatenated in order).
     * Progress is indeterminate (the total is not known in advance).
     */
    public static void extractBase(Path[] volumes, Path gameDir, ProgressListener listener)
            throws IOException {
        List<InputStream> streams = new ArrayList<>();
        for (Path volume : volumes) {
            streams.add(Files.newInputStream(volume));
        }
        Enumeration<InputStream> sequence = Collections.enumeration(streams);
        if (listener != null) {
            listener.onProgress(0, -1);
        }
        try (ZipInputStream zip = new ZipInputStream(
                new SequenceInputStream(sequence), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                Path dest = safeResolve(gameDir, entry.getName());
                Files.createDirectories(dest.getParent());
                Files.copy(zip, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Verifies files against the manifest's files map.
     * If filter is not null, only the given paths are checked.
     * Returns the list of problems (missing / size mismatch / hash mismatch).
     */
    public static List<String> verify(Path gameDir, Map<String, Object> files,
                                      Set<String> filter, ProgressListener listener)
            throws IOException, NoSuchAlgorithmException {
        List<Map.Entry<String, Object>> targets = files.entrySet().stream()
                .filter(e -> filter == null || filter.contains(e.getKey()))
                .toList();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        List<String> problems = new ArrayList<>();
        int done = 0;
        for (Map.Entry<String, Object> e : targets) {
            String rel = e.getKey();
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) e.getValue();
            Path p = gameDir.resolve(rel);
            if (!Files.isRegularFile(p)) {
                problems.add("missing: " + rel);
            } else {
                long size = ((Number) meta.get("s")).longValue();
                String hash = (String) meta.get("h");
                if (Files.size(p) != size) {
                    problems.add("size mismatch: " + rel);
                } else if (!sha256Hex(p).equals(hash)) {
                    problems.add("hash mismatch: " + rel);
                }
            }
            done++;
            if (listener != null) {
                listener.onProgress(done, targets.size());
            }
        }
        return problems;
    }

    /**
     * Deletes files inside gameDir that are not listed in the target
     * version's files map (leftovers after rebuilding from a base archive).
     */
    public static void removeExtras(Path gameDir, Map<String, Object> files,
                                    ProgressListener listener) throws IOException {
        removeExtras(gameDir, files, Set.of(), listener);
    }

    public static void removeExtras(Path gameDir, Map<String, Object> files,
                                    Set<String> preservedPrefixes, ProgressListener listener) throws IOException {
        try (var walk = Files.walk(gameDir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String rel = gameDir.relativize(p).toString().replace('\\', '/');
                boolean preserved = preservedPrefixes.stream().anyMatch(prefix ->
                        rel.equals(prefix) || rel.startsWith(prefix + "/"));
                if (!files.containsKey(rel) && !preserved) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private static Path safeResolve(Path root, String name) throws IOException {
        String safe = safeRelative(name);
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path result = normalizedRoot.resolve(safe).normalize();
        if (!result.startsWith(normalizedRoot)) {
            throw new IOException("Archive path escapes target directory: " + name);
        }
        Path current = normalizedRoot;
        Path relative = normalizedRoot.relativize(result);
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Archive path crosses a symbolic link: " + name);
            }
        }
        return result;
    }

    private static String safeRelative(String name) throws IOException {
        if (name == null || name.isBlank() || name.indexOf('\0') >= 0) {
            throw new IOException("Invalid archive path");
        }
        String normalized = name.replace('\\', '/');
        Path path = Path.of(normalized);
        if (path.isAbsolute() || normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            throw new IOException("Absolute archive path is not allowed: " + name);
        }
        for (Path part : path) {
            if (part.toString().equals("..")) {
                throw new IOException("Parent traversal is not allowed: " + name);
            }
        }
        return normalized;
    }

    public static String sha256Hex(Path p) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
