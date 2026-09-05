package rol.devkit;

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
import java.util.stream.Stream;

/**
 * Снимок папки: относительные пути (с прямыми слешами), размеры и SHA-256.
 * Формат снимка:
 * {
 *   "schema": 1,
 *   "created": "2026-09-06T...",
 *   "root": "C:\\path\\to\\game",
 *   "files": { "legends.exe": {"s": 15548416, "h": "abcdef..."}, ... }
 * }
 */
final class Snapshot {

    private Snapshot() {}

    static void save(String folder, String outFile) throws IOException, NoSuchAlgorithmException {
        Path root = Path.of(folder).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Папка не найдена: " + root);
        }
        Map<String, Object> rootObj = new LinkedHashMap<>();
        rootObj.put("schema", 1L);
        rootObj.put("created", Instant.now().toString());
        rootObj.put("root", root.toString());
        rootObj.put("files", scan(root));
        Files.writeString(Path.of(outFile), Json.write(rootObj), StandardCharsets.UTF_8);
    }

    /**
     * Скан папки: относительный путь (прямые слеши) -> {s: размер, h: SHA-256}.
     * Пути отсортированы, результат детерминирован.
     */
    static Map<String, Object> scan(Path root) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        Map<String, Object> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("s", Files.size(p));
                entry.put("h", sha256(md, p));
                files.put(rel, entry);
            }
        }
        return files;
    }

    /** Сравнение двух снимков: возвращает человекочитаемый отчёт. */
    static List<String> diff(String oldFile, String newFile) throws IOException {
        Map<String, Object> old = Json.parse(Files.readString(Path.of(oldFile), StandardCharsets.UTF_8));
        Map<String, Object> fresh = Json.parse(Files.readString(Path.of(newFile), StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        Map<String, Object> oldFiles = (Map<String, Object>) old.get("files");
        @SuppressWarnings("unchecked")
        Map<String, Object> newFiles = (Map<String, Object>) fresh.get("files");

        List<String> changed = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        long changedBytes = 0;
        long addedBytes = 0;

        for (Map.Entry<String, Object> e : newFiles.entrySet()) {
            String path = e.getKey();
            Object oldEntry = oldFiles.get(path);
            if (oldEntry == null) {
                added.add(path);
                addedBytes += ((Number) ((Map<?, ?>) e.getValue()).get("s")).longValue();
            } else if (!hashOf(oldEntry).equals(hashOf(e.getValue()))) {
                changed.add(path);
                changedBytes += ((Number) ((Map<?, ?>) e.getValue()).get("s")).longValue();
            }
        }
        for (String path : oldFiles.keySet()) {
            if (!newFiles.containsKey(path)) {
                removed.add(path);
            }
        }

        List<String> report = new ArrayList<>();
        report.add("Изменено: " + changed.size() + " файлов (" + human(changedBytes) + ")");
        changed.forEach(p -> report.add("  M " + p));
        report.add("Добавлено: " + added.size() + " файлов (" + human(addedBytes) + ")");
        added.forEach(p -> report.add("  A " + p));
        report.add("Удалено: " + removed.size() + " файлов");
        removed.forEach(p -> report.add("  D " + p));
        report.add("Итого в update-пакет: " + human(changedBytes + addedBytes) + " (до сжатия)");
        return report;
    }

    private static String hashOf(Object entry) {
        return (String) ((Map<?, ?>) entry).get("h");
    }

    private static String sha256(MessageDigest md, Path p) throws IOException {
        md.reset();
        try (var in = Files.newInputStream(p)) {
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

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " Б";
        if (bytes < 1024 * 1024) return String.format("%.1f КБ", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f МБ", bytes / (1024.0 * 1024));
        return String.format("%.2f ГБ", bytes / (1024.0 * 1024 * 1024));
    }
}
