package rol.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates install and update flows: download → apply → verify →
 * record the new version in the local state.
 */
public final class VersionManager {

    /** Progress callback: stage tokens + numeric progress (total -1 = unknown). */
    public interface Progress {
        Progress NONE = new Progress() {
            @Override public void stage(String stage) { }
            @Override public void progress(long done, long total) { }
        };

        void stage(String stage);

        void progress(long done, long total);
    }

    public static final String STAGE_DOWNLOAD = "download";
    public static final String STAGE_EXTRACT = "extract";
    public static final String STAGE_APPLY = "apply";
    public static final String STAGE_VERIFY = "verify";

    private final SettingsManager settings;

    public VersionManager(SettingsManager settings) {
        this.settings = settings;
    }

    public static Path cacheDir() {
        return SettingsManager.settingsDir().resolve("cache");
    }

    /** Fresh install from the base archive volumes of the latest version. */
    public void installBase(Manifest manifest, Progress progress)
            throws IOException, InterruptedException, NoSuchAlgorithmException {
        Map<String, Object> latest = manifest.latestVersion();
        if (latest == null) {
            throw new IOException("Manifest has no versions");
        }
        List<Map<String, Object>> parts = Manifest.basePartsOf(latest);
        if (parts.isEmpty()) {
            throw new IOException("No base archive in the manifest");
        }
        if (settings.getGamePath().isBlank()) {
            throw new IOException("Game folder is not set in the settings");
        }
        Path gameDir = Path.of(settings.getGamePath());
        Files.createDirectories(gameDir);

        progress.stage(STAGE_DOWNLOAD);
        List<Path> volumes = new ArrayList<>();
        for (Map<String, Object> part : parts) {
            String file = (String) part.get("file");
            Path local = downloadEntry(part, file, progress);
            verifyPart(local, part);
            volumes.add(local);
        }

        progress.stage(STAGE_EXTRACT);
        Updater.extractBase(volumes.toArray(Path[]::new), gameDir, progress::progress);

        progress.stage(STAGE_VERIFY);
        @SuppressWarnings("unchecked")
        Map<String, Object> files = (Map<String, Object>) latest.get("files");
        Updater.removeExtras(gameDir, files, null);
        List<String> problems = Updater.verify(gameDir, files, null, progress::progress);
        if (!problems.isEmpty()) {
            throw new IOException("Verification failed: " + summarize(problems));
        }
        settings.setInstalledVersion(manifest.latest());
    }

    /**
     * Switches the installed game to the target version, in place:
     * forward jumps use a single update package; backward or far jumps
     * rebuild from the base archive of the nearest base version (or the
     * target's own base) and then apply a package up to the target.
     */
    public void switchTo(Manifest manifest, String targetId, Progress progress)
            throws IOException, InterruptedException, NoSuchAlgorithmException {
        String current = settings.getInstalledVersion();
        if (targetId.equals(current)) {
            return;
        }
        Map<String, Object> target = manifest.version(targetId);
        if (target == null) {
            throw new IOException("Version is not in the manifest: " + targetId);
        }
        if (settings.getGamePath().isBlank()) {
            throw new IOException("Game folder is not set in the settings");
        }
        Path gameDir = Path.of(settings.getGamePath());

        // forward: one update package hop
        if (Manifest.updateOf(target, current) != null) {
            updateTo(manifest, targetId, progress);
            return;
        }

        // backward or far jump: rebuild from a base archive
        Map<String, Object> baseVersion = findBaseVersion(manifest, targetId);
        if (baseVersion == null) {
            throw new IOException("No base archive available for version " + targetId);
        }
        boolean baseIsTarget = Manifest.idOf(baseVersion).equals(targetId);

        progress.stage(STAGE_DOWNLOAD);
        List<Path> volumes = new ArrayList<>();
        for (Map<String, Object> part : Manifest.basePartsOf(baseVersion)) {
            String file = (String) part.get("file");
            Path local = downloadEntry(part, file, progress);
            verifyPart(local, part);
            volumes.add(local);
        }

        progress.stage(STAGE_EXTRACT);
        Updater.extractBase(volumes.toArray(Path[]::new), gameDir, progress::progress);

        if (!baseIsTarget) {
            Map<String, Object> upd = Manifest.updateOf(target, Manifest.idOf(baseVersion));
            if (upd == null) {
                throw new IOException("No update path from " + Manifest.idOf(baseVersion)
                        + " to " + targetId);
            }
            progress.stage(STAGE_DOWNLOAD);
            String file = (String) upd.get("file");
            Path pkg = downloadEntry(upd, file, progress);
            verifyPart(pkg, upd);
            progress.stage(STAGE_APPLY);
            Updater.applyPackage(pkg, gameDir, progress::progress);
        }

        // sweep files that do not belong to the target version
        @SuppressWarnings("unchecked")
        Map<String, Object> files = (Map<String, Object>) target.get("files");
        Updater.removeExtras(gameDir, files, null);

        progress.stage(STAGE_VERIFY);
        List<String> problems = Updater.verify(gameDir, files, null, progress::progress);
        if (!problems.isEmpty()) {
            throw new IOException("Verification failed: " + summarize(problems));
        }
        settings.setInstalledVersion(targetId);
    }

    /** The target version itself if it has a base, else the nearest older
     * version (in manifest order) that has base parts; null if none. */
    private Map<String, Object> findBaseVersion(Manifest manifest, String targetId) {
        List<Map<String, Object>> versions = manifest.versions();
        int targetIdx = -1;
        for (int i = 0; i < versions.size(); i++) {
            if (Manifest.idOf(versions.get(i)).equals(targetId)) {
                targetIdx = i;
                break;
            }
        }
        if (targetIdx < 0) {
            return null;
        }
        for (int i = targetIdx; i >= 0; i--) {
            Map<String, Object> v = versions.get(i);
            if (!Manifest.basePartsOf(v).isEmpty()) {
                return v;
            }
        }
        return null;
    }

    /** Update the installed game from its current version to the target. */
    public void updateTo(Manifest manifest, String targetVersion, Progress progress)
            throws IOException, InterruptedException, NoSuchAlgorithmException {
        String current = settings.getInstalledVersion();
        Map<String, Object> target = manifest.version(targetVersion);
        if (target == null) {
            throw new IOException("Version is not in the manifest: " + targetVersion);
        }
        Map<String, Object> upd = Manifest.updateOf(target, current);
        if (upd == null) {
            throw new IOException("No update path from " + current + " to " + targetVersion);
        }
        if (settings.getGamePath().isBlank()) {
            throw new IOException("Game folder is not set in the settings");
        }

        progress.stage(STAGE_DOWNLOAD);
        String file = (String) upd.get("file");
        Path pkg = downloadEntry(upd, file, progress);
        verifyPart(pkg, upd);

        Path gameDir = Path.of(settings.getGamePath());
        Set<String> touched = new HashSet<>(Updater.packagePaths(pkg));

        progress.stage(STAGE_APPLY);
        Updater.applyPackage(pkg, gameDir, progress::progress);

        progress.stage(STAGE_VERIFY);
        @SuppressWarnings("unchecked")
        Map<String, Object> files = (Map<String, Object>) target.get("files");
        List<String> problems = Updater.verify(gameDir, files, touched, progress::progress);
        if (!problems.isEmpty()) {
            throw new IOException("Verification failed: " + summarize(problems));
        }
        settings.setInstalledVersion(targetVersion);
    }

    private static void verifyPart(Path local, Map<String, Object> meta)
            throws IOException, NoSuchAlgorithmException {
        long wantSize = ((Number) meta.get("size")).longValue();
        String wantHash = (String) meta.get("sha256");
        if (Files.size(local) != wantSize) {
            throw new IOException("Size mismatch for " + local.getFileName());
        }
        if (!Updater.sha256Hex(local).equals(wantHash)) {
            throw new IOException("Checksum mismatch for " + local.getFileName());
        }
    }

    private Path downloadEntry(Map<String, Object> entry, String file, Progress progress)
            throws IOException, InterruptedException {
        long size = ((Number) entry.get("size")).longValue();
        String hash = (String) entry.get("sha256");
        return Downloader.download(entryUrl(entry, file), cacheDir(), file,
                progress::progress, size, hash);
    }

    /** Download URL: the explicit "url" field wins, otherwise next-to-manifest. */
    private String entryUrl(Map<String, Object> entry, String file) {
        Object url = entry.get("url");
        return (url != null && !url.toString().isBlank()) ? url.toString() : fileUrl(file);
    }

    /** Files live next to the manifest: manifest URL base + file name. */
    private String fileUrl(String file) {
        String manifestUrl = settings.getManifestUrl();
        int idx = manifestUrl.lastIndexOf('/');
        return (idx >= 0 ? manifestUrl.substring(0, idx + 1) : "") + file;
    }

    private static String summarize(List<String> problems) {
        String head = String.join("; ", problems.subList(0, Math.min(5, problems.size())));
        return problems.size() > 5 ? head + "; ..." : head;
    }
}
