package rol.launcher;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.Locale;
import java.util.Optional;

/**
 * Launcher entry point.
 *
 * Planned next (see README.md and docs/architecture.md):
 *   ManifestClient — downloads releases/manifest.json
 *   Downloader     — downloads with progress and resume
 *   Updater        — applies update packages, verifies SHA-256
 *   VersionManager — installed versions and switching
 *   GameRunner     — launches legends.exe
 */
public class App extends Application {

    private final SettingsManager settings = new SettingsManager();

    private Stage stage;
    private MenuBar menuBar;
    private Menu fileMenu;
    private MenuItem settingsItem;
    private MenuItem exitItem;
    private Menu languageMenu;
    private RadioMenuItem langEn;
    private RadioMenuItem langRu;
    private Menu helpMenu;
    private MenuItem aboutItem;
    private Label welcomeLabel;
    private Label versionLabel;
    private Button playButton;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        I18n.setLocale(settings.getLanguage());
        buildUi();
        applyI18n();
        stage.show();
    }

    private void buildUi() {
        // File menu
        settingsItem = new MenuItem();
        settingsItem.setOnAction(e -> openSettings());
        exitItem = new MenuItem();
        exitItem.setOnAction(e -> Platform.exit());
        fileMenu = new Menu();
        fileMenu.getItems().addAll(settingsItem, new SeparatorMenuItem(), exitItem);

        // Language menu (quick switch)
        ToggleGroup langGroup = new ToggleGroup();
        langEn = new RadioMenuItem();
        langEn.setToggleGroup(langGroup);
        langEn.setOnAction(e -> changeLanguage(Locale.ENGLISH));
        langRu = new RadioMenuItem();
        langRu.setToggleGroup(langGroup);
        langRu.setOnAction(e -> changeLanguage(Locale.of("ru")));
        languageMenu = new Menu();
        languageMenu.getItems().addAll(langEn, langRu);

        // Help menu
        aboutItem = new MenuItem();
        aboutItem.setOnAction(e -> showAbout());
        helpMenu = new Menu();
        helpMenu.getItems().add(aboutItem);

        menuBar = new MenuBar(fileMenu, languageMenu, helpMenu);

        // Main content (placeholder until install/update screens arrive)
        welcomeLabel = new Label();
        versionLabel = new Label();
        playButton = new Button();
        playButton.setDisable(true);
        VBox center = new VBox(12, welcomeLabel, versionLabel, playButton);

        BorderPane root = new BorderPane();
        root.setTop(menuBar);
        root.setCenter(center);
        stage.setScene(new Scene(root, 520, 340));
    }

    private void changeLanguage(Locale locale) {
        settings.setLanguage(locale);
        I18n.setLocale(locale);
        applyI18n();
    }

    private void openSettings() {
        SettingsDialog dialog = new SettingsDialog(I18n.getLocale());
        Optional<Locale> result = dialog.showAndWait();
        result.ifPresent(this::changeLanguage);
    }

    private void showAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, I18n.get("about.text"), ButtonType.OK);
        alert.setTitle(I18n.get("menu.about"));
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    /** Re-applies all UI texts after a locale change. */
    private void applyI18n() {
        stage.setTitle(I18n.get("app.title"));
        fileMenu.setText(I18n.get("menu.file"));
        settingsItem.setText(I18n.get("menu.settings"));
        exitItem.setText(I18n.get("menu.exit"));
        languageMenu.setText(I18n.get("menu.language"));
        langEn.setText(I18n.get("settings.language.en"));
        langRu.setText(I18n.get("settings.language.ru"));
        langEn.setSelected("en".equals(I18n.getLocale().getLanguage()));
        langRu.setSelected("ru".equals(I18n.getLocale().getLanguage()));
        helpMenu.setText(I18n.get("menu.help"));
        aboutItem.setText(I18n.get("menu.about"));
        welcomeLabel.setText(I18n.get("main.welcome"));
        versionLabel.setText(I18n.get("main.version.none"));
        playButton.setText(I18n.get("main.play"));
    }

    public static void main(String[] args) {
        launch(args);
    }
}
