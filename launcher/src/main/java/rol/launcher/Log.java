package rol.launcher;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;

/**
 * Simple file logger: %APPDATA%\RoLauncher\launcher.log.
 * Appends timestamped lines and full stack traces; rotates to
 * launcher.log.old once the file exceeds 1 MB. No external dependencies.
 */
public final class Log {

    private static final long MAX_SIZE = 1024 * 1024;

    private Log() {}

    public static Path file() {
        return SettingsManager.settingsDir().resolve("launcher.log");
    }

    public static synchronized void info(String message) {
        write("INFO", message, null);
    }

    public static synchronized void error(String message, Throwable throwable) {
        write("ERROR", message, throwable);
    }

    private static void write(String level, String message, Throwable throwable) {
        try {
            Path file = file();
            rotate(file);
            Files.createDirectories(file.getParent());
            String line = LocalDateTime.now() + " [" + level + "] " + message
                    + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (throwable != null) {
                StringWriter sw = new StringWriter();
                throwable.printStackTrace(new PrintWriter(sw));
                Files.writeString(file, sw.toString(), StandardCharsets.UTF_8,
                        StandardOpenOption.APPEND);
            }
        } catch (IOException ignored) {
            // logging must never break the app
        }
    }

    private static void rotate(Path file) throws IOException {
        if (Files.exists(file) && Files.size(file) > MAX_SIZE) {
            Files.move(file, file.resolveSibling("launcher.log.old"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
