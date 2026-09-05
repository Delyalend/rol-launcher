package rol.devkit;

import java.util.List;

/**
 * RoL DevKit — CLI-инструменты разработчика.
 *
 * Команды:
 *   snapshot <папка> [файл.json]   — снимок папки: пути, размеры, SHA-256
 *   diff <старый.json> <новый.json> — сравнение двух снимков
 *   repack <папка_игры> <эталонная_папка> [папка_бэкапов] — вшить мод в .big
 *   build-release <папка> --version vX.Y.Z [--changelog f] [--manifest f]
 *                 [--out dir] [--recent N] [--base a,b] — update-пакеты и манифест
 *
 * Сборка без Maven:  javac -d out devkit/src/main/java/rol/devkit/*.java
 * Запуск:            java -cp out rol.devkit.Main snapshot "C:\path\to\game"
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
                    System.out.println("Снимок сохранён: " + out);
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
                default -> usage();
            }
        } catch (Exception e) {
            System.err.println("Ошибка: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void usage() {
        System.out.println("""
                RoL DevKit
                  snapshot <папка> [файл.json]    — снимок папки (размеры + SHA-256)
                  diff <старый.json> <новый.json> — сравнение двух снимков
                  repack <игра> <эталон> [бэкапы] — вшить распакованный мод в .big
                """);
    }
}
