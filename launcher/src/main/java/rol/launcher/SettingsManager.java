package rol.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Loads and saves launcher settings from
 * %APPDATA%\RoLauncher\settings.properties.
 */
public final class SettingsManager {

    public static final String KEY_LANGUAGE = "language";
    private static final String FILE_NAME = "settings.properties";

    private final Properties props = new Properties();

    public SettingsManager() {
        Path file = settingsFile();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                // fall back to defaults
            }
        }
    }

    public Locale getLanguage() {
        return "ru".equalsIgnoreCase(props.getProperty(KEY_LANGUAGE))
                ? Locale.of("ru") : Locale.ENGLISH;
    }

    public void setLanguage(Locale locale) {
        props.setProperty(KEY_LANGUAGE, locale.getLanguage());
        save();
    }

    private void save() {
        Path file = settingsFile();
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "RoLauncher settings");
            }
        } catch (IOException e) {
            // keep settings in memory only
        }
    }

    public static Path settingsDir() {
        String appData = System.getenv("APPDATA");
        Path base = appData != null && !appData.isBlank()
                ? Path.of(appData) : Path.of(System.getProperty("user.home"));
        return base.resolve("RoLauncher");
    }

    private static Path settingsFile() {
        return settingsDir().resolve(FILE_NAME);
    }
}
