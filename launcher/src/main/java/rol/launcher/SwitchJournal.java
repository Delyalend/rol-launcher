package rol.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.UUID;

/** Durable recovery record for a game-directory switch. */
final class SwitchJournal {

    enum State { PREPARING, OLD_MOVED, NEW_MOVED, COMMITTED }

    final Path file;
    final Path game;
    final Path staging;
    final Path backup;
    final String targetVersion;
    State state;

    private SwitchJournal(Path file, Path game, Path staging, Path backup,
                          String targetVersion, State state) {
        this.file = file;
        this.game = game;
        this.staging = staging;
        this.backup = backup;
        this.targetVersion = targetVersion;
        this.state = state;
    }

    static SwitchJournal create(Path game, Path staging, Path backup, String target) throws IOException {
        Path file = game.resolveSibling(game.getFileName() + ".rol-transaction.properties");
        SwitchJournal journal = new SwitchJournal(file, game, staging, backup, target, State.PREPARING);
        journal.save();
        return journal;
    }

    static SwitchJournal load(Path game) throws IOException {
        Path file = game.resolveSibling(game.getFileName() + ".rol-transaction.properties");
        if (!Files.isRegularFile(file)) return null;
        Properties p = new Properties();
        try (var in = Files.newInputStream(file)) { p.load(in); }
        return new SwitchJournal(file, Path.of(p.getProperty("game")),
                Path.of(p.getProperty("staging")), Path.of(p.getProperty("backup")),
                p.getProperty("targetVersion"), State.valueOf(p.getProperty("state")));
    }

    void setState(State state) throws IOException {
        this.state = state;
        save();
    }

    void save() throws IOException {
        Properties p = new Properties();
        p.setProperty("game", game.toString());
        p.setProperty("staging", staging.toString());
        p.setProperty("backup", backup.toString());
        p.setProperty("targetVersion", targetVersion);
        p.setProperty("state", state.name());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp-" + UUID.randomUUID());
        try (var out = Files.newOutputStream(tmp)) { p.store(out, "RoLauncher switch transaction"); }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Best-effort startup recovery. Before the live directory is moved, the
     * old installation is untouched; after OLD_MOVED/NEW_MOVED, restore the
     * backup rather than guessing whether the staged target was complete.
     */
    static boolean recover(Path game) throws IOException {
        SwitchJournal journal = load(game);
        if (journal == null) return false;
        switch (journal.state) {
            case PREPARING -> {
                deleteTree(journal.staging);
                journal.delete();
            }
            case OLD_MOVED, NEW_MOVED -> {
                if (Files.exists(journal.game)) deleteTree(journal.game);
                if (Files.exists(journal.backup)) {
                    Files.move(journal.backup, journal.game, StandardCopyOption.ATOMIC_MOVE);
                }
                deleteTree(journal.staging);
                journal.delete();
            }
            case COMMITTED -> {
                deleteTree(journal.backup);
                deleteTree(journal.staging);
                journal.delete();
            }
        }
        return true;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    void delete() throws IOException { Files.deleteIfExists(file); }
}
