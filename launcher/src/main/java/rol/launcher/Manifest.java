package rol.launcher;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validated view over releases/manifest.json.
 */
public final class Manifest {

    private static final Pattern HASH = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+(?:\\.[A-Za-z0-9._-]+)*");
    private final Map<String, Object> data;

    public Manifest(Map<String, Object> data) {
        validate(data);
        this.data = data;
    }

    public String latest() { return (String) data.get("latest"); }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> versions() {
        return (List<Map<String, Object>>) (List<?>) data.get("versions");
    }

    public Map<String, Object> version(String id) {
        for (Map<String, Object> v : versions()) if (id.equals(v.get("id"))) return v;
        return null;
    }

    public Map<String, Object> latestVersion() { return version(latest()); }

    @SuppressWarnings("unchecked")
    public static List<String> changelogOf(Map<String, Object> version) {
        return (List<String>) (List<?>) version.getOrDefault("changelog", List.of());
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> basePartsOf(Map<String, Object> version) {
        Object base = version.get("base");
        if (base == null) return List.of();
        Object parts = ((Map<String, Object>) base).get("parts");
        return parts == null ? List.of() : (List<Map<String, Object>>) (List<?>) parts;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> updateOf(Map<String, Object> version, String fromId) {
        Object updates = version.get("updates_from");
        if (updates == null) return null;
        return (Map<String, Object>) ((Map<String, Object>) updates).get(fromId);
    }

    public static String dateOf(Map<String, Object> version) { return (String) version.getOrDefault("date", ""); }
    public static String idOf(Map<String, Object> version) { return (String) version.get("id"); }

    @SuppressWarnings("unchecked")
    private static void validate(Map<String, Object> root) {
        if (root == null) fail("root is missing");
        Number schema = number(root.get("schema_version"));
        if (schema == null || schema.intValue() != 1) fail("unsupported schema_version");
        String latest = string(root.get("latest"));
        Object rawVersions = root.get("versions");
        if (!(rawVersions instanceof List<?>)) fail("versions must be a non-empty array");
        List<?> list = (List<?>) rawVersions;
        if (list.isEmpty()) fail("versions must be a non-empty array");
        Set<String> ids = new HashSet<>();
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?>)) fail("version entry must be an object");
            Map<String, Object> version = (Map<String, Object>) raw;
            String id = string(version.get("id"));
            if (!ids.add(id)) fail("duplicate version: " + id);
            validateFiles(version.get("files"), id);
            validateArtifacts(version.get("base"), id);
            validateUpdates(version.get("updates_from"), ids, id);
        }
        if (!ids.contains(latest)) fail("latest version is not present: " + latest);
    }

    @SuppressWarnings("unchecked")
    private static void validateFiles(Object raw, String version) {
        if (!(raw instanceof Map<?, ?>)) fail("files missing for " + version);
        Map<?, ?> map = (Map<?, ?>) raw;
        if (map.isEmpty()) fail("files missing for " + version);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            safePath(string(entry.getKey()));
            if (!(entry.getValue() instanceof Map<?, ?>)) fail("invalid file metadata: " + entry.getKey());
            Map<?, ?> meta = (Map<?, ?>) entry.getValue();
            Number size = number(meta.get("s"));
            String hash = string(meta.get("h"));
            if (size == null || size.longValue() < 0 || !HASH.matcher(hash).matches()) fail("invalid file metadata: " + entry.getKey());
        }
    }

    @SuppressWarnings("unchecked")
    private static void validateArtifacts(Object raw, String version) {
        if (raw == null) return;
        if (!(raw instanceof Map<?, ?>)) fail("invalid base for " + version);
        Map<?, ?> base = (Map<?, ?>) raw;
        if (!(base.get("parts") instanceof List<?>)) fail("invalid base for " + version);
        List<?> parts = (List<?>) base.get("parts");
        if (parts.isEmpty()) fail("invalid base for " + version);
        for (Object item : parts) {
            if (!(item instanceof Map<?, ?>)) fail("invalid base part");
            Map<?, ?> part = (Map<?, ?>) item;
            safeArtifact(string(part.get("file")));
            Number size = number(part.get("size"));
            if (size == null || size.longValue() <= 0 || !HASH.matcher(string(part.get("sha256"))).matches()) fail("invalid base checksum");
            validateUrl(part.get("url"));
        }
    }

    @SuppressWarnings("unchecked")
    private static void validateUpdates(Object raw, Set<String> seen, String version) {
        if (raw == null) return;
        if (!(raw instanceof Map<?, ?>)) fail("invalid updates_from for " + version);
        Map<?, ?> updates = (Map<?, ?>) raw;
        for (Map.Entry<?, ?> entry : updates.entrySet()) {
            string(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?>)) fail("invalid update artifact");
            Map<?, ?> artifact = (Map<?, ?>) entry.getValue();
            safeArtifact(string(artifact.get("file")));
            Number size = number(artifact.get("size"));
            if (size == null || size.longValue() <= 0 || !HASH.matcher(string(artifact.get("sha256"))).matches()) fail("invalid update checksum");
            validateUrl(artifact.get("url"));
        }
    }

    private static void validateUrl(Object raw) {
        if (raw == null) return;
        try {
            URI uri = URI.create(string(raw));
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) fail("artifact URL must use HTTPS");
        } catch (IllegalArgumentException e) { fail("invalid artifact URL"); }
    }

    private static void safePath(String path) {
        if (path.isBlank() || path.startsWith("/") || path.matches("^[A-Za-z]:.*") || path.contains("..") || path.indexOf('\0') >= 0) fail("unsafe path: " + path);
    }

    private static void safeArtifact(String name) {
        if (!SAFE_NAME.matcher(name).matches()) fail("unsafe artifact name: " + name);
    }

    private static String string(Object value) {
        if (!(value instanceof String)) fail("missing string value");
        String s = (String) value;
        if (s.isBlank()) fail("missing string value");
        return s;
    }

    private static Number number(Object value) { return value instanceof Number n ? n : null; }
    private static void fail(String message) { throw new IllegalArgumentException("Invalid manifest: " + message); }
}
