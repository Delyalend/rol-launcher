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
            Path local = Downloader.download(fileUrl(file), cacheDir(), file,
                    progress::progress);
            verifyPart(local, part);
            volumes.add(local);
        }

        progress.stage(STAGE_EXTRACT);
        Updater.extractBase(volumes.toArray(Path[]::new), gameDir, progress::progress);

        progress.stage(STAGE_VERIFY);
        @SuppressWarnings("unchecked")
        Map<String, Object> files = (Map<String, Object>) latest.get("files");
        List<String> problems = Updater.verify(gameDir, files, null, progress::progress);
        if (!problems.isEmpty()) {
            throw new IOException("Verification failed: " + summarize(problems));
        }
        settings.setInstalledVersion(manifest.latest());
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
        Path pkg = Downloader.download(fileUrl(file), cacheDir(), file, progress::progress);
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
