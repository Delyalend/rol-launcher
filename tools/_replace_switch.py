from pathlib import Path
p=Path(r'C:\root\RoLauncher\launcher\src\main\java\rol\launcher\VersionManager.java')
t=p.read_text(encoding='utf-8')
start=t.index('    public void switchTo(')
end=t.index('    /** The target version itself', start)
new='''    public void switchTo(Manifest manifest, String targetId, Progress progress)
            throws IOException, InterruptedException, NoSuchAlgorithmException {
        String current = settings.getInstalledVersion();
        if (targetId.equals(current)) return;
        Map<String, Object> target = manifest.version(targetId);
        if (target == null) throw new IOException("Version is not in the manifest: " + targetId);
        if (settings.getGamePath().isBlank()) throw new IOException("Game folder is not set in the settings");
        Path gameDir = Path.of(settings.getGamePath()).toAbsolutePath().normalize();
        Path staging = gameDir.resolveSibling(gameDir.getFileName() + ".rol-staging-" + UUID.randomUUID());
        Path backup = gameDir.resolveSibling(gameDir.getFileName() + ".rol-backup-" + UUID.randomUUID());
        Files.createDirectories(staging);
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
            if (Files.isDirectory(gameDir)) {
                Files.move(gameDir, backup, StandardCopyOption.ATOMIC_MOVE);
                movedOld = true;
            }
            Files.move(staging, gameDir, StandardCopyOption.ATOMIC_MOVE);
            settings.setInstalledVersion(targetId);
            deleteTree(backup);
        } catch (Exception e) {
            if (movedOld && !Files.exists(gameDir) && Files.exists(backup)) Files.move(backup, gameDir, StandardCopyOption.ATOMIC_MOVE);
            deleteTree(staging);
            throw e;
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

'''
p.write_text(t[:start]+new+t[end:], encoding='utf-8', newline='\n')
