package rol.devkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Read-only inspection and extraction of WAR-BUILDER .big archives. */
final class BigArchiveTool {

    private BigArchiveTool() {}

    static void list(String archive) throws IOException {
        Repack.ParseResult parsed = read(archive);
        for (Repack.Entry entry : parsed.entries()) {
            String state = entry.payload == null ? "compressed/raw" : "decompressed";
            System.out.printf("%10d  %-14s  %s%n", entry.size, state, clean(entry.name));
        }
        System.out.println("Entries: " + parsed.entries().size());
    }

    static void extract(String archive, String output, List<String> filters) throws IOException {
        Repack.ParseResult parsed = read(archive);
        Path destination = Path.of(output).toAbsolutePath().normalize();
        Files.createDirectories(destination);
        int written = 0;
        int skipped = 0;
        for (Repack.Entry entry : parsed.entries()) {
            String relative = clean(entry.name);
            if (!matches(relative, filters)) continue;
            if (entry.payload == null) {
                System.out.println("SKIP (cannot decompress): " + relative);
                skipped++;
                continue;
            }
            Path target = destination.resolve(relative).normalize();
            if (!target.startsWith(destination)) {
                throw new IOException("Archive entry escapes output directory: " + entry.name);
            }
            Files.createDirectories(target.getParent());
            Files.write(target, entry.payload);
            System.out.printf("EXTRACTED %s (%d bytes)%n", relative, entry.payload.length);
            written++;
        }
        System.out.println("Extracted: " + written + ", skipped: " + skipped);
    }

    private static Repack.ParseResult read(String archive) throws IOException {
        Path path = Path.of(archive).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IOException("Archive not found: " + path);
        Repack.ParseResult parsed = Repack.parse(Files.readAllBytes(path));
        if (parsed == null) throw new IOException("Cannot parse .big archive: " + path);
        return parsed;
    }

    private static boolean matches(String entry, List<String> filters) {
        if (filters.isEmpty()) return true;
        String normalized = entry.toLowerCase(Locale.ROOT);
        for (String filter : filters) {
            String wanted = clean(filter).toLowerCase(Locale.ROOT);
            if (normalized.equals(wanted) || normalized.endsWith("/" + wanted)) return true;
        }
        return false;
    }

    private static String clean(String name) {
        String result = name.replace('\\', '/');
        while (result.startsWith("./")) result = result.substring(2);
        while (result.startsWith("/")) result = result.substring(1);
        return result;
    }
}
