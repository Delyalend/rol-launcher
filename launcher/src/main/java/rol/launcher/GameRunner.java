package rol.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Launches the game executable from the selected game folder.
 */
public final class GameRunner {

    public static final String GAME_EXE = "legends.exe";

    private static volatile Process launchedProcess;

    private GameRunner() {}

    /** Returns the launcher-started process if it is still alive. */
    public static Optional<Process> runningProcess() {
        Process process = launchedProcess;
        if (process != null && process.isAlive()) return Optional.of(process);
        launchedProcess = null;
        return Optional.empty();
    }

    /** Returns true if the launcher started a game process that is still alive. */
    public static boolean isRunning() {
        return runningProcess().isPresent();
    }

    /** Returns the game executable path if the folder contains it, otherwise null. */
    public static Path findGameExe(String gamePath) {
        if (gamePath == null || gamePath.isBlank()) {
            return null;
        }
        Path exe = Path.of(gamePath).resolve(GAME_EXE);
        return Files.isRegularFile(exe) ? exe : null;
    }

    /** Starts legends.exe with the game folder as the working directory. */
    public static void launch(String gamePath) throws IOException {
        Path exe = findGameExe(gamePath);
        if (exe == null) {
            throw new IOException("Game executable not found in " + gamePath);
        }
        launchedProcess = new ProcessBuilder(exe.toString())
                .directory(exe.getParent().toFile())
                .start();
    }
}
