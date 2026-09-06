package rol.devkit;

import java.util.List;

/**
 * RoL DevKit — developer CLI tools.
 *
 * Commands:
 *   snapshot &lt;folder&gt; [file.json]   — folder snapshot: paths, sizes, SHA-256
 *   diff &lt;old.json&gt; &lt;new.json&gt; — compare two snapshots
 *   repack &lt;game_dir&gt; &lt;pristine_dir&gt; [backup_dir] — embed mod into .big
 *   build-release &lt;folder&gt; --version vX.Y.Z [--changelog f] [--manifest f]
 *                 [--out dir] [--recent N] [--base a,b] — update packages + manifest
 *   build-base &lt;folder&gt; [--out dir] [--name base.zip] [--volume 1900m] — split-zip volumes
 *
 * Build without Maven:  javac -d out devkit/src/main/java/rol/devkit/*.java
 * Run:                  java -cp out rol.devkit.Main snapshot "C:\path\to\game"
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
            return;
        }
        try {
            switch (args[0]) {
                case "snapshot" -> {
                    if (args.length < 2) { usage(); return; }
                    String out = args.length > 2 ? args[2] : "snapshot.json";
                    Snapshot.save(args[1], out);
                    System.out.println("Snapshot saved: " + out);
                }
                case "diff" -> {
                    if (args.length < 3) { usage(); return; }
                    List<String> report = Snapshot.diff(args[1], args[2]);
                    report.forEach(System.out::println);
                }
                case "repack" -> {
                    if (args.length < 3) { usage(); return; }
                    String backup = args.length > 3 ? args[3] : null;
                    Repack.run(args[1], args[2], backup);
                }
                case "build-release" -> {
                    if (args.length < 3) { usage(); return; }
                    BuildRelease.run(List.of(args).subList(1, args.length));
                }
                case "build-base" -> {
                    if (args.length < 2) { usage(); return; }
                    BaseBuilder.run(List.of(args).subList(1, args.length));
                }
                case "patch" -> {
                    if (args.length < 6) { usage(); return; }
                    // patch <game_dir> <pristine_dir> <big> <entry> <file> [backup_dir]
                    java.nio.file.Path game = java.nio.file.Path.of(args[1]);
                    java.nio.file.Path pristine = java.nio.file.Path.of(args[2]);
                    java.nio.file.Path backup = args.length > 6
                            ? java.nio.file.Path.of(args[6])
                            : game.getParent().resolve("_bigs_backup_original");
                    java.nio.file.Files.createDirectories(backup);
                    byte[] content = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(args[5]));
                    Repack.patch(pristine, game, backup, args[3], args[4], content);
                }
                default -> usage();
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void usage() {
        System.out.println("""
                RoL DevKit
                  snapshot <folder> [file.json]     — folder snapshot (sizes + SHA-256)
                  diff <old.json> <new.json>        — compare two snapshots
                  repack <game> <pristine> [backup] — embed extracted mod into .big
                  build-release <folder> --version vX.Y.Z [--changelog f] [--manifest f]
                                [--out dir] [--recent N] [--base a,b]
                  build-base <folder> [--out dir] [--name base.zip] [--volume 1900m]
                """);
    }
}
