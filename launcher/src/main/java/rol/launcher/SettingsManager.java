package rol.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Properties;

/**
 * Loads and saves launcher settings from
 * %APPDATA%\RoLauncher\settings.properties.
 */
public final class SettingsManager {

    public static final String KEY_LANGUAGE = "language";
    public static final String KEY_MANIFEST_URL = "manifestUrl";
    public static final String KEY_GAME_PATH = "gamePath";
    public static final String KEY_INSTALLED_VERSION = "installedVersion";
    public static final String KEY_INSTALLED_PATH = "installedPath";
    private static final String FILE_NAME = "settings.properties";

    /** Baked-in manifest URL; the setting overrides it. */
    public static final String DEFAULT_MANIFEST_URL =
            "https://raw.githubusercontent.com/Delyalend/rol-launcher/main/releases/manifest.json";

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

    /** Raw manifest URL (raw.githubusercontent.com etc.); defaults to the
     * baked-in URL unless overridden in the settings. */
    public String getManifestUrl() {
        return props.getProperty(KEY_MANIFEST_URL, DEFAULT_MANIFEST_URL);
    }

    public void setManifestUrl(String url) {
        if (url == null || url.isBlank()) {
            props.remove(KEY_MANIFEST_URL);
        } else {
            props.setProperty(KEY_MANIFEST_URL, url.trim());
        }
        save();
    }

    /** Path to the game folder; empty = not selected. */
    public String getGamePath() {
        return props.getProperty(KEY_GAME_PATH, "");
    }

    public void setGamePath(String path) {
        String old = getGamePath();
        String normalized = path == null || path.isBlank() ? "" : normalizePath(path);
        if (!old.equals(normalized)) {
            props.setProperty(KEY_GAME_PATH, normalized);
            String recordedPath = props.getProperty(KEY_INSTALLED_PATH, "");
            if (!recordedPath.isBlank() && !recordedPath.equals(normalized)) {
                props.remove(KEY_INSTALLED_VERSION);
            }
        }
        save();
    }

    /** Locally recorded installed version id, valid only for the selected path. */
    public String getInstalledVersion() {
        String configured = getGamePath();
        String recorded = props.getProperty(KEY_INSTALLED_PATH, "");
        return !configured.isBlank() && configured.equals(recorded)
                ? props.getProperty(KEY_INSTALLED_VERSION, "") : "";
    }

    public void setInstalledVersion(String version) {
        if (version == null || version.isBlank()) {
            props.remove(KEY_INSTALLED_VERSION);
        } else {
            props.setProperty(KEY_INSTALLED_VERSION, version.trim());
            props.setProperty(KEY_INSTALLED_PATH, getGamePath());
        }
        save();
    }

    private static String normalizePath(String path) {
        return Path.of(path.trim()).toAbsolutePath().normalize().toString();
    }

    private void save() {
        Path file = settingsFile();
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(FILE_NAME + ".tmp");
            try (OutputStream out = Files.newOutputStream(temp,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                props.store(out, "RoLauncher settings");
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
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
