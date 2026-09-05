package rol.launcher;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * Launcher skeleton.
 *
 * Planned next (see README.md and docs/architecture.md):
 *   ManifestClient — downloads releases/manifest.json
 *   Downloader     — downloads with progress and resume
 *   Updater        — applies update packages, verifies SHA-256
 *   VersionManager — installed versions and switching
 *   GameRunner     — launches legends.exe
 */
public class App extends Application {

    @Override
    public void start(Stage stage) {
        Label label = new Label("RoLauncher — application skeleton");
        StackPane root = new StackPane(label);
        stage.setScene(new Scene(root, 480, 300));
        stage.setTitle("RoLauncher");
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
