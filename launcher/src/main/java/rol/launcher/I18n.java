package rol.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

/**
 * Central access to localized strings. All UI texts must go through here,
 * never hardcoded in the code.
 *
 * Bundle base name: rol/launcher/strings (strings.properties = English
 * default, strings_ru.properties = Russian).
 *
 * Note: ResourceBundle.Control is NOT supported in named modules
 * (UnsupportedOperationException), so bundles are loaded manually —
 * UTF-8 via PropertyResourceBundle(Reader) with a simple fallback chain:
 * the locale bundle first, then the English base.
 */
public final class I18n {

    private static final String BASE = "rol/launcher/strings";

    private static Locale locale = Locale.ENGLISH;
    private static List<ResourceBundle> chain = new ArrayList<>();

    private I18n() {}

    public static void setLocale(Locale locale) {
        I18n.locale = locale;
        List<ResourceBundle> bundles = new ArrayList<>();
        load(BASE + "_" + locale.getLanguage() + ".properties", bundles);
        load(BASE + ".properties", bundles); // English fallback, always last
        if (bundles.isEmpty()) {
            throw new IllegalStateException("No string bundles found for " + BASE);
        }
        chain = bundles;
    }

    public static String get(String key) {
        for (ResourceBundle bundle : chain) {
            if (bundle.containsKey(key)) {
                return bundle.getString(key);
            }
        }
        throw new MissingResourceException("Key not found: " + key, I18n.class.getName(), key);
    }

    /** Same as get(key), but formats {0}, {1}, ... placeholders with the args. */
    public static String get(String key, Object... args) {
        return MessageFormat.format(get(key), args);
    }

    public static Locale getLocale() {
        return locale;
    }

    private static void load(String resource, List<ResourceBundle> out) {
        // Class.getResourceAsStream (not ClassLoader!) — the classloader does not
        // see resources of named modules, but the module itself always can.
        try (InputStream in = I18n.class.getResourceAsStream("/" + resource)) {
            if (in == null) {
                return;
            }
            out.add(new PropertyResourceBundle(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            // skip unreadable bundle
        }
    }
}
