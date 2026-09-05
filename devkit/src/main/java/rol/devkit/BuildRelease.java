package rol.devkit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Release build: update packages and manifest update.
 *
 * Scans the new game folder and, for EVERY old version in the manifest,
 * builds an update package (changed + added files; a special
 * .rol-removed.txt inside holds the deletion list). Adds the new version
 * entry to manifest.json and sets it as "latest".
 *
 * A package is self-contained: the launcher extracts it over the
 * installation, deletes files from .rol-removed.txt and verifies SHA-256
 * against the manifest.
 *
 * Arguments (after folder):
 *   --version vX.Y.Z        required
 *   --changelog file.txt    lines of the file become changelog items
 *   --manifest path.json    default: releases/manifest.json
 *   --out folder            default: release
 *   --recent N              packages only for the last N versions
 *   --base v1,v2            base archive volumes (rebase): size+sha256 are computed
 */
final class BuildRelease {

    private static final String REMOVED_ENTRY = ".rol-removed.txt";

    private BuildRelease() {}

    static void run(List<String> args) throws IOException, NoSuchAlgorithmException {
        String folder = null;
        String version = null;
        String changelogFile = null;
        String manifestPath = "releases/manifest.json";
        String outDir = "release";
        int recent = -1;
        String baseCsv = null;
        String baseUrl = null;

        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            switch (a) {
                case "--version" -> version = args.get(++i);
                case "--changelog" -> changelogFile = args.get(++i);
                case "--manifest" -> manifestPath = args.get(++i);
                case "--out" -> outDir = args.get(++i);
                case "--recent" -> recent = Integer.parseInt(args.get(++i));
                case "--base" -> baseCsv = args.get(++i);
                case "--base-url" -> baseUrl = args.get(++i);
                default -> {
                    if (folder == null && !a.startsWith("--")) folder = a;
                    else throw new IllegalArgumentException("unknown argument: " + a);
                }
            }
        }
        if (folder == null || version == null) {
            throw new IllegalArgumentException("required: <new_game_folder> and --version vX.Y.Z");
        }
        if (!version.matches("^v[0-9A-Za-z][0-9A-Za-z._-]*$")) {
            throw new IllegalArgumentException("invalid version id: " + version
                    + " (expected like v0.2.0)");
        }

        Path gameRoot = Path.of(folder).toAbsolutePath().normalize();
        if (!Files.isDirectory(gameRoot)) {
            throw new IllegalArgumentException("Folder not found: " + gameRoot);
        }

        // manifest: existing or new
        Path manifestFile = Path.of(manifestPath);
        Map<String, Object> manifest;
        List<Map<String, Object>> versions;
        if (Files.exists(manifestFile)) {
            manifest = Json.parse(Files.readString(manifestFile, StandardCharsets.UTF_8));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> v = (List<Map<String, Object>>) (List<?>) manifest.get("versions");
            versions = v;
            for (Map<String, Object> old : versions) {
                if (version.equals(old.get("id"))) {
                    throw new IllegalArgumentException("Version " + version + " already exists in the manifest");
                }
            }
        } else {
            manifest = new LinkedHashMap<>();
            manifest.put("schema_version", 1L);
            versions = new ArrayList<>();
            manifest.put("versions", versions);
        }

        Map<String, Object> newFiles = Snapshot.scan(gameRoot);

        // changelog
        List<String> changelog = new ArrayList<>();
        if (changelogFile != null) {
            for (String line : Files.readAllLines(Path.of(changelogFile), StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (!t.isEmpty()) changelog.add(t);
            }
        }
        if (changelog.isEmpty()) changelog.add("Update");

        // update packages for old versions
        Path out = Path.of(outDir);
        Files.createDirectories(out);
        List<Map<String, Object>> oldVersions = recent > 0 && recent < versions.size()
                ? versions.subList(versions.size() - recent, versions.size())
                : versions;

        Map<String, Object> updatesFrom = new LinkedHashMap<>();
        for (Map<String, Object> old : oldVersions) {
            String oldId = (String) old.get("id");
            @SuppressWarnings("unchecked")
            Map<String, Object> oldFiles = (Map<String, Object>) old.get("files");
            byte[] zipBytes = buildUpdateZip(gameRoot, newFiles, oldFiles);
            String zipName = "update-" + oldId + "-" + version + ".zip";
            Files.write(out.resolve(zipName), zipBytes);

            Map<String, Object> upd = new LinkedHashMap<>();
            upd.put("file", zipName);
            upd.put("size", (long) zipBytes.length);
            upd.put("sha256", sha256Hex(zipBytes));
            if (baseUrl != null) {
                upd.put("url", baseUrl + "/" + zipName);
            }
            updatesFrom.put(oldId, upd);
        }

        // new version entry
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", version);
        entry.put("date", Instant.now().toString());
        entry.put("changelog", changelog);
        if (baseCsv != null) {
            entry.put("base", baseParts(baseCsv, baseUrl));
        }
        entry.put("updates_from", updatesFrom);
        entry.put("files", newFiles);
        versions.add(entry);

        manifest.put("latest", version);
        Files.writeString(manifestFile, Json.write(manifest), StandardCharsets.UTF_8);

        // summary
        System.out.println("Release " + version + " built:");
        System.out.println("  game files: " + newFiles.size());
        for (Map.Entry<String, Object> u : updatesFrom.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) u.getValue();
            System.out.println("  " + m.get("file") + " ("
                    + human(((Number) m.get("size")).longValue()) + ") from " + u.getKey());
        }
        System.out.println("  manifest: " + manifestFile + " (latest=" + version + ")");
        System.out.println("  packages in: " + out.toAbsolutePath());
    }

    /** Zip: changed + added files + .rol-removed.txt with the deletion list. */
    private static byte[] buildUpdateZip(Path gameRoot,
                                         Map<String, Object> newFiles,
                                         Map<String, Object> oldFiles)
            throws IOException, NoSuchAlgorithmException {
        List<String> changed = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();

        for (Map.Entry<String, Object> e : newFiles.entrySet()) {
            Object oldEntry = oldFiles.get(e.getKey());
            if (oldEntry == null) {
                added.add(e.getKey());
            } else if (!hashOf(oldEntry).equals(hashOf(e.getValue()))) {
                changed.add(e.getKey());
            }
        }
        for (String path : oldFiles.keySet()) {
            if (!newFiles.containsKey(path)) {
                removed.add(path);
            }
        }

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            zip.setLevel(9);
            if (!removed.isEmpty()) {
                zip.putNextEntry(new ZipEntry(REMOVED_ENTRY));
                for (String path : removed) {
                    zip.write((path + "\n").getBytes(StandardCharsets.UTF_8));
                }
                zip.closeEntry();
            }
            for (String path : added) {
                addToZip(zip, gameRoot, path, md);
            }
            for (String path : changed) {
                addToZip(zip, gameRoot, path, md);
            }
        }
        return bos.toByteArray();
    }

    private static void addToZip(ZipOutputStream zip, Path gameRoot, String relPath, MessageDigest md)
            throws IOException {
        Path p = gameRoot.resolve(relPath);
        if (!Files.isRegularFile(p)) {
            throw new IllegalArgumentException("File from the list not found: " + relPath);
        }
        zip.putNextEntry(new ZipEntry(relPath));
        Files.copy(p, zip);
        zip.closeEntry();
    }

    private static Map<String, Object> baseParts(String csv, String baseUrl)
            throws IOException, NoSuchAlgorithmException {
        List<Map<String, Object>> parts = new ArrayList<>();
        for (String part : csv.split(",")) {
            String name = part.trim();
            if (name.isEmpty()) continue;
            Path p = Path.of(name);
            if (!Files.isRegularFile(p)) {
                throw new IllegalArgumentException("Base volume not found: " + name);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("file", p.getFileName().toString());
            m.put("size", Files.size(p));
            m.put("sha256", sha256Hex(Files.readAllBytes(p)));
            if (baseUrl != null) {
                m.put("url", baseUrl + "/" + p.getFileName());
            }
            parts.add(m);
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("--base given but no volumes found");
        }
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("parts", parts);
        return base;
    }

    private static String hashOf(Object entry) {
        return (String) ((Map<?, ?>) entry).get("h");
    }

    private static String sha256Hex(byte[] data) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder(64);
        for (byte b : md.digest(data)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
