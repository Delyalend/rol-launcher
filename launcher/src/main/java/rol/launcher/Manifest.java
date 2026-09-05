package rol.launcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Typed view over the parsed version manifest (releases/manifest.json).
 * The underlying structure is a plain Map produced by the internal JSON
 * parser; schema is documented in docs/architecture.md.
 */
public final class Manifest {

    private final Map<String, Object> data;

    public Manifest(Map<String, Object> data) {
        this.data = data;
    }

    public String latest() {
        return (String) data.get("latest");
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> versions() {
        return (List<Map<String, Object>>) (List<?>) data.getOrDefault("versions", List.of());
    }

    /** Version entry by id, or null. */
    public Map<String, Object> version(String id) {
        for (Map<String, Object> v : versions()) {
            if (id.equals(v.get("id"))) {
                return v;
            }
        }
        return null;
    }

    public Map<String, Object> latestVersion() {
        return version(latest());
    }

    @SuppressWarnings("unchecked")
    public static List<String> changelogOf(Map<String, Object> version) {
        return (List<String>) (List<?>) version.getOrDefault("changelog", List.of());
    }

    /** Base archive volumes of a version: [{file, size, sha256}, ...], empty if none. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> basePartsOf(Map<String, Object> version) {
        Object base = version.get("base");
        if (base == null) {
            return List.of();
        }
        Object parts = ((Map<String, Object>) base).get("parts");
        return parts == null ? List.of() : (List<Map<String, Object>>) (List<?>) parts;
    }

    /** Update package for upgrading to this version from the given one, or null. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> updateOf(Map<String, Object> version, String fromId) {
        Object updates = version.get("updates_from");
        if (updates == null) {
            return null;
        }
        return (Map<String, Object>) ((Map<String, Object>) updates).get(fromId);
    }

    public static String dateOf(Map<String, Object> version) {
        return (String) version.getOrDefault("date", "");
    }

    public static String idOf(Map<String, Object> version) {
        return (String) version.get("id");
    }
}
