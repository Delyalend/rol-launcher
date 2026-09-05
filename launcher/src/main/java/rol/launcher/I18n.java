package rol.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

/**
 * Central access to localized strings. All UI texts must go through here,
 * never hardcoded in the code.
 *
 * Bundle base name: rol/launcher/strings (strings.properties = English
 * default, strings_ru.properties = Russian).
 */
public final class I18n {

    private static ResourceBundle bundle;

    private I18n() {}

    public static void setLocale(Locale locale) {
        bundle = ResourceBundle.getBundle("rol/launcher/strings", locale, new Utf8Control());
    }

    public static String get(String key) {
        return bundle.getString(key);
    }

    public static Locale getLocale() {
        return bundle.getLocale();
    }

    /**
     * ResourceBundle.Control that reads .properties files as UTF-8 —
     * the default control assumes ISO-8859-1, which breaks Russian texts.
     */
    private static final class Utf8Control extends ResourceBundle.Control {
        @Override
        public ResourceBundle newBundle(String baseName, Locale locale, String format,
                                        ClassLoader loader, boolean reload)
                throws IOException {
            String bundleName = toBundleName(baseName, locale);
            String resourceName = toResourceName(bundleName, "properties");
            try (InputStream in = loader.getResourceAsStream(resourceName)) {
                if (in == null) {
                    return null; // let the parent chain handle it (fallback to English)
                }
                return new PropertyResourceBundle(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        }
    }
}
