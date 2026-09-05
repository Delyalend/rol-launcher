package rol.launcher;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * Скелет лаунчера.
 *
 * Дальше по плану (см. README.md и docs/architecture.md):
 *   ManifestClient — загрузка releases/manifest.json
 *   Downloader     — скачивание с прогрессом и докачкой
 *   Updater        — применение update-пакетов, проверка SHA-256
 *   VersionManager — установленные версии и переключение
 *   GameRunner     — запуск legends.exe
 */
public class App extends Application {

    @Override
    public void start(Stage stage) {
        Label label = new Label("RoLauncher — скелет приложения");
        StackPane root = new StackPane(label);
        stage.setScene(new Scene(root, 480, 300));
        stage.setTitle("RoLauncher");
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
