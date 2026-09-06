package rol.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.UUID;
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

    /** Recover an unfinished switch before the launcher starts using the game. */
    public static void recover(Path gameDir) throws IOException {
        SwitchJournal journal = SwitchJournal.load(gameDir);
        if (journal == null) return;
        Log.error("Recovering unfinished switch: " + journal.state, null);
        if (journal.state == SwitchJournal.State.OLD_MOVED
                && !Files.exists(journal.game) && Files.exists(journal.backup)) {
            Files.move(journal.backup, journal.game, StandardCopyOption.ATOMIC_MOVE);
        } else if (journal.state == SwitchJournal.State.NEW_MOVED
                && Files.exists(journal.game)) {
            deleteTree(journal.backup);
        }
        deleteTree(journal.staging);
        journal.delete();
    }

    /** Fresh install from the base archive volumes of the latest version. */
    public void installBase(Manifest manifest, Progress progress)
            throws IOException, InterruptedException, NoSuchAlgorithmException {
        Map<String, Object> latest = manifest.latestVersion();
        if (latest == null) {
            throw new IOException("Manifest has no versions");
        }
        Map<String, Object> baseVersion = manifest.baseVersionFor(manifest.latest());
        if (baseVersion == null) throw new IOException("No base archive in the manifest");
        List<Map<String, Object>> parts = Manifest.basePartsOf(baseVersion);
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

        String baseId = Manifest.idOf(baseVersion);
        if (!baseId.equals(manifest.latest())) {
            Map<String, Object> update = Manifest.updateOf(latest, baseId);
            if (update == null) {
                throw new IOException("No update path from " + baseId + " to " + manifest.latest());
            }
            progress.stage(STAGE_APPLY);
            Updater.applyPackage(downloadEntry(update, (String) update.get("file"), progress),
                    gameDir, progress::progress);
        }

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

    /** Verifies the currently recorded installation without changing any files. */
    public void verifyInstalled(Manifest manifest, Progress progress)
            throws IOException, NoSuchAlgorithmException {
        String installed = settings.getInstalledVersion();
        if (installed.isBlank()) throw new IOException("No installed version recorded");
        if (settings.getGamePath().isBlank()) throw new IOException("Game folder is not set in the settings");
        Map<String, Object> version = manifest.version(installed);
        if (version == null) throw new IOException("Installed version is not in the manifest: " + installed);
        @SuppressWarnings("unchecked")
        Map<String, Object> files = (Map<String, Object>) version.get("files");
        Path gameDir = Path.of(settings.getGamePath());
        if (!Files.isDirectory(gameDir)) throw new IOException("Game folder does not exist");
        progress.stage(STAGE_VERIFY);
        List<String> problems = Updater.verify(gameDir, files, null, progress::progress);
        if (!problems.isEmpty()) throw new IOException("Verification failed: " + summarize(problems));
    }

    /** Finds an exact manifest version in an existing game folder, read-only. */
    public String detectVersion(Manifest manifest, Progress progress)
            throws IOException, NoSuchAlgorithmException {
        if (settings.getGamePath().isBlank()) return null;
        Path gameDir = Path.of(settings.getGamePath());
        if (!Files.isDirectory(gameDir)) return null;
        List<Map<String, Object>> versions = new ArrayList<>(manifest.versions());
        java.util.Collections.reverse(versions);
        for (Map<String, Object> version : versions) {
            @SuppressWarnings("unchecked")
            Map<String, Object> files = (Map<String, Object>) version.get("files");
            progress.stage(STAGE_VERIFY);
            List<String> problems = Updater.verify(gameDir, files, null, progress::progress);
            if (problems.isEmpty()) return Manifest.idOf(version);
        }
        return null;
    }

    /**
     * Switches the installed game to the target version using a sibling
     * staging directory. The live installation is untouched until the
     * staged target is fully verified.
     */
    public void switchTo(Manifest manifest, String targetId, Progress progress)
            throws IOException, InterruptedException, NoSuchAlgorithmException {
        String current = settings.getInstalledVersion();
        if (targetId.equals(current)) return;
        Map<String, Object> target = manifest.version(targetId);
        if (target == null) throw new IOException("Version is not in the manifest: " + targetId);
        if (settings.getGamePath().isBlank()) throw new IOException("Game folder is not set in the settings");
        Path gameDir = Path.of(settings.getGamePath()).toAbsolutePath().normalize();
        if (!GameRunner.canModifyInstallation(gameDir.toString())) {
            throw new IOException("The game is running; close it before switching versions");
        }
        Path staging = gameDir.resolveSibling(gameDir.getFileName() + ".rol-staging-" + UUID.randomUUID());
        Path backup = gameDir.resolveSibling(gameDir.getFileName() + ".rol-backup-" + UUID.randomUUID());
        Files.createDirectories(staging);
        SwitchJournal journal = SwitchJournal.create(gameDir, staging, backup, targetId);
        boolean movedOld = false;
        try {
            Map<String, Object> direct = Manifest.updateOf(target, current);
            @SuppressWarnings("unchecked") Map<String, Object> files = (Map<String, Object>) target.get("files");
            if (direct != null && Files.isDirectory(gameDir)) {
                progress.stage(STAGE_EXTRACT);
                copyTree(gameDir, staging);
                progress.stage(STAGE_DOWNLOAD);
                Path pkg = downloadEntry(direct, (String) direct.get("file"), progress);
                progress.stage(STAGE_APPLY);
                Updater.applyPackage(pkg, staging, progress::progress);
            } else {
                Map<String, Object> baseVersion = findBaseVersion(manifest, targetId);
                if (baseVersion == null) throw new IOException("No base archive available for version " + targetId);
                progress.stage(STAGE_DOWNLOAD);
                List<Path> volumes = new ArrayList<>();
                for (Map<String, Object> part : Manifest.basePartsOf(baseVersion)) {
                    volumes.add(downloadEntry(part, (String) part.get("file"), progress));
                }
                progress.stage(STAGE_EXTRACT);
                Updater.extractBase(volumes.toArray(Path[]::new), staging, progress::progress);
                preserveUserData(gameDir, staging);
                String baseId = Manifest.idOf(baseVersion);
                if (!baseId.equals(targetId)) {
                    Map<String, Object> upd = Manifest.updateOf(target, baseId);
                    if (upd == null) throw new IOException("No update path from " + baseId + " to " + targetId);
                    progress.stage(STAGE_APPLY);
                    Updater.applyPackage(downloadEntry(upd, (String) upd.get("file"), progress), staging, progress::progress);
                }
            }
            Updater.removeExtras(staging, files, null);
            progress.stage(STAGE_VERIFY);
            List<String> problems = Updater.verify(staging, files, null, progress::progress);
            if (!problems.isEmpty()) throw new IOException("Verification failed: " + summarize(problems));
            if (GameRunner.isRunning()) throw new IOException("The game is still running");
            if (Files.isDirectory(gameDir)) {
                Files.move(gameDir, backup, StandardCopyOption.ATOMIC_MOVE);
                movedOld = true;
                journal.setState(SwitchJournal.State.OLD_MOVED);
            }
            Files.move(staging, gameDir, StandardCopyOption.ATOMIC_MOVE);
            journal.setState(SwitchJournal.State.NEW_MOVED);
            settings.setInstalledVersion(targetId);
            journal.setState(SwitchJournal.State.COMMITTED);
            journal.delete();
            deleteTree(backup);
        } catch (Exception e) {
            if (movedOld && !Files.exists(gameDir) && Files.exists(backup)) Files.move(backup, gameDir, StandardCopyOption.ATOMIC_MOVE);
            deleteTree(staging);
            try { journal.delete(); } catch (IOException ignored) { }
            throw e;
        }
    }

    private static final List<String> USER_DATA_DIRS = List.of(
            "custom maps", "savegames", "profiles", "screenshots", "replays");

    private static void preserveUserData(Path live, Path staging) throws IOException {
        if (!Files.isDirectory(live)) return;
        for (String relative : USER_DATA_DIRS) {
            Path source = live.resolve(relative);
            if (Files.exists(source)) copyTree(source, staging.resolve(relative));
        }
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        try (var walk = Files.walk(source)) {
            for (Path p : walk.toList()) {
                Path target = destination.resolve(source.relativize(p));
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
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

    /**
     * Download URL: the explicit "url" field wins. For a raw GitHub manifest,
     * fall back to the matching GitHub Release asset before trying next-to-manifest.
     * This keeps old manifests usable when binary packages are published as release assets.
     */
    private String entryUrl(Map<String, Object> entry, String file) {
        Object url = entry.get("url");
        if (url != null && !url.toString().isBlank()) return url.toString();
        String releaseUrl = githubReleaseAssetUrl(file);
        return releaseUrl != null ? releaseUrl : fileUrl(file);
    }

    private String githubReleaseAssetUrl(String file) {
        String manifestUrl = settings.getManifestUrl();
        String prefix = "https://raw.githubusercontent.com/";
        if (!manifestUrl.startsWith(prefix)) return null;

        String rest = manifestUrl.substring(prefix.length());
        String[] parts = rest.split("/");
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) return null;

        int marker = file.lastIndexOf("-v");
        if (marker < 0 || !file.endsWith(".zip")) return null;
        String tag = file.substring(marker + 1, file.length() - 4);
        if (!tag.matches("v[0-9A-Za-z][0-9A-Za-z._-]*")) return null;

        return "https://github.com/" + parts[0] + "/" + parts[1]
                + "/releases/download/" + tag + "/" + file;
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
