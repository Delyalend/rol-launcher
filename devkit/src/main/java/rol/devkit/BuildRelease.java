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
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Сборка релиза: update-пакеты и обновление манифеста.
 *
 * Сканирует папку новой версии игры и для КАЖДОЙ старой версии из манифеста
 * собирает update-пакет (изменённые + добавленные файлы, внутри служебный
 * .rol-removed.txt со списком удалённых). Добавляет запись новой версии
 * в manifest.json и ставит её в "latest".
 *
 * Пакет самодостаточен: лаунчер распаковывает его поверх установки,
 * удаляет файлы из .rol-removed.txt и сверяет SHA-256 с манифестом.
 *
 * Аргументы (после folder):
 *   --version vX.Y.Z        обязательный
 *   --changelog файл.txt    строки файла = пункты чейнджлога
 *   --manifest путь.json    по умолчанию releases/manifest.json
 *   --out папка             по умолчанию release
 *   --recent N              пакеты только для последних N версий
 *   --base ч1,ч2            тома базового архива (ребейз): посчитает size+sha256
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

        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            switch (a) {
                case "--version" -> version = args.get(++i);
                case "--changelog" -> changelogFile = args.get(++i);
                case "--manifest" -> manifestPath = args.get(++i);
                case "--out" -> outDir = args.get(++i);
                case "--recent" -> recent = Integer.parseInt(args.get(++i));
                case "--base" -> baseCsv = args.get(++i);
                default -> {
                    if (folder == null && !a.startsWith("--")) folder = a;
                    else throw new IllegalArgumentException("неизвестный аргумент: " + a);
                }
            }
        }
        if (folder == null || version == null) {
            throw new IllegalArgumentException("нужны: <папка_новой_игры> и --version vX.Y.Z");
        }
        if (!version.matches("^v[0-9A-Za-z][0-9A-Za-z._-]*$")) {
            throw new IllegalArgumentException("некорректный id версии: " + version
                    + " (ожидается вида v0.2.0)");
        }

        Path gameRoot = Path.of(folder).toAbsolutePath().normalize();
        if (!Files.isDirectory(gameRoot)) {
            throw new IllegalArgumentException("Папка не найдена: " + gameRoot);
        }

        // манифест: существующий или новый
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
                    throw new IllegalArgumentException("Версия " + version + " уже есть в манифесте");
                }
            }
        } else {
            manifest = new LinkedHashMap<>();
            manifest.put("schema_version", 1L);
            versions = new ArrayList<>();
            manifest.put("versions", versions);
        }

        Map<String, Object> newFiles = Snapshot.scan(gameRoot);

        // чейнджлог
        List<String> changelog = new ArrayList<>();
        if (changelogFile != null) {
            for (String line : Files.readAllLines(Path.of(changelogFile), StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (!t.isEmpty()) changelog.add(t);
            }
        }
        if (changelog.isEmpty()) changelog.add("Обновление");

        // update-пакеты для старых версий
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
            updatesFrom.put(oldId, upd);
        }

        // запись новой версии
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", version);
        entry.put("date", Instant.now().toString());
        entry.put("changelog", changelog);
        if (baseCsv != null) {
            entry.put("base", baseParts(baseCsv));
        }
        entry.put("updates_from", updatesFrom);
        entry.put("files", newFiles);
        versions.add(entry);

        manifest.put("latest", version);
        Files.writeString(manifestFile, Json.write(manifest), StandardCharsets.UTF_8);

        // сводка
        System.out.println("Релиз " + version + " собран:");
        System.out.println("  файлов в игре: " + newFiles.size());
        for (Map.Entry<String, Object> u : updatesFrom.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) u.getValue();
            System.out.println("  " + m.get("file") + " ("
                    + human(((Number) m.get("size")).longValue()) + ") для " + u.getKey());
        }
        System.out.println("  манифест: " + manifestFile + " (latest=" + version + ")");
        System.out.println("  пакеты в: " + out.toAbsolutePath());
    }

    /** Zip: изменённые + добавленные файлы + .rol-removed.txt со списком удалённых. */
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
            throw new IllegalArgumentException("Файл из списка не найден: " + relPath);
        }
        zip.putNextEntry(new ZipEntry(relPath));
        Files.copy(p, zip);
        zip.closeEntry();
    }

    private static Map<String, Object> baseParts(String csv)
            throws IOException, NoSuchAlgorithmException {
        List<Map<String, Object>> parts = new ArrayList<>();
        for (String part : csv.split(",")) {
            String name = part.trim();
            if (name.isEmpty()) continue;
            Path p = Path.of(name);
            if (!Files.isRegularFile(p)) {
                throw new IllegalArgumentException("Том базы не найден: " + name);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("file", p.getFileName().toString());
            m.put("size", Files.size(p));
            m.put("sha256", sha256Hex(Files.readAllBytes(p)));
            parts.add(m);
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("--base передан, но тома не найдены");
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
        if (bytes < 1024) return bytes + " Б";
        if (bytes < 1024 * 1024) return String.format("%.1f КБ", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f МБ", bytes / (1024.0 * 1024));
        return String.format("%.2f ГБ", bytes / (1024.0 * 1024 * 1024));
    }
}
