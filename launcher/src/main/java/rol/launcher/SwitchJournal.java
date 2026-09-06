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

    void delete() throws IOException { Files.deleteIfExists(file); }
}
