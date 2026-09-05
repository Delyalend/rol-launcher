package rol.launcher;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Launcher entry point: main screen with installed/latest version,
 * update check and game launch.
 *
 * Planned next (see README.md and docs/architecture.md):
 *   Downloader     — downloads with progress and resume
 *   Updater        — applies update packages, verifies SHA-256
 *   VersionManager — installed versions and switching
 */
public class App extends Application {

    private final SettingsManager settings = new SettingsManager();

    private Stage stage;
    // menus
    private MenuBar menuBar;
    private Menu fileMenu;
    private MenuItem settingsItem;
    private MenuItem exitItem;
    private Menu languageMenu;
    private RadioMenuItem langEn;
    private RadioMenuItem langRu;
    private Menu helpMenu;
    private MenuItem aboutItem;
    // main view
    private Label installedLabel;
    private Label latestLabel;
    private Button checkButton;
    private Label statusLabel;
    private VBox updateBox;
    private Label updateLabel;
    private Label changelogCaption;
    private TextArea changelogArea;
    private Button updateButton;
    private Button installButton;
    private Button playButton;
    private ProgressBar progressBar;

    // state
    private String latestVersionId;
    private List<String> latestChangelog = List.of();
    private boolean checking;
    private boolean busy;
    private Manifest lastManifest;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        I18n.setLocale(settings.getLanguage());
        buildUi();
        applyI18n();
        stage.show();
        refreshState();
        if (!settings.getManifestUrl().isBlank()) {
            checkUpdates();
        }
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

        // Main view
        installedLabel = new Label();
        latestLabel = new Label();
        checkButton = new Button();
        checkButton.setOnAction(e -> checkUpdates());
        statusLabel = new Label();
        statusLabel.setWrapText(true);

        updateLabel = new Label();
        updateLabel.setStyle("-fx-font-weight: bold");
        changelogCaption = new Label();
        changelogArea = new TextArea();
        changelogArea.setEditable(false);
        changelogArea.setWrapText(true);
        changelogArea.setPrefRowCount(5);
        changelogArea.setMaxWidth(480);
        updateButton = new Button();
        updateButton.setOnAction(e -> runUpdate());
        updateBox = new VBox(6, updateLabel, changelogCaption, changelogArea, updateButton);
        updateBox.setVisible(false);
        updateBox.setManaged(false);

        installButton = new Button();
        installButton.setOnAction(e -> runInstall());
        installButton.setVisible(false);
        installButton.setManaged(false);

        playButton = new Button();
        playButton.setOnAction(e -> play());

        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        progressBar.setManaged(false);

        VBox center = new VBox(10, installedLabel, latestLabel, checkButton,
                statusLabel, updateBox, installButton, playButton, progressBar);
        center.setPadding(new Insets(16));

        BorderPane root = new BorderPane();
        root.setTop(menuBar);
        root.setCenter(center);
        stage.setScene(new Scene(root, 560, 400));
    }

    // ---------- actions ----------

    private void changeLanguage(Locale locale) {
        settings.setLanguage(locale);
        I18n.setLocale(locale);
        applyI18n();
        refreshState();
    }

    private void openSettings() {
        SettingsDialog dialog = new SettingsDialog(settings);
        Optional<SettingsDialog.Result> result = dialog.showAndWait();
        result.ifPresent(r -> {
            settings.setManifestUrl(r.manifestUrl());
            settings.setGamePath(r.gamePath());
            if (!I18n.getLocale().equals(r.locale())) {
                changeLanguage(r.locale());
            } else {
                refreshState();
            }
            if (!settings.getManifestUrl().isBlank()) {
                checkUpdates();
            }
        });
    }

    private void checkUpdates() {
        if (checking) {
            return;
        }
        checking = true;
        statusLabel.setText(I18n.get("main.checking"));
        Thread worker = new Thread(() -> {
            try {
                Manifest manifest = ManifestClient.fetch(settings.getManifestUrl());
                Platform.runLater(() -> onManifestLoaded(manifest));
            } catch (Exception e) {
                Platform.runLater(() -> {
                    checking = false;
                    statusLabel.setText(I18n.get("main.check.error", e.getMessage()));
                });
            }
        });
        worker.setDaemon(true);
        worker.start();
    }

    private void onManifestLoaded(Manifest manifest) {
        checking = false;
        lastManifest = manifest;
        latestVersionId = manifest.latest();
        Map<String, Object> latest = manifest.latestVersion();
        latestChangelog = latest == null ? List.of() : Manifest.changelogOf(latest);
        renderLatest();
        renderUpdateBox();
        renderInstallButton();
        if (!busy) {
            statusLabel.setText(updateBox.isVisible() ? "" : I18n.get("main.check.uptodate"));
        }
    }

    private void renderInstallButton() {
        boolean installable = lastManifest != null
                && !Manifest.basePartsOf(lastManifest.latestVersion()).isEmpty()
                && settings.getInstalledVersion().isBlank()
                && GameRunner.findGameExe(settings.getGamePath()) == null;
        installButton.setVisible(installable);
        installButton.setManaged(installable);
    }

    // ---------- update and install ----------

    private VersionManager.Progress uiProgress() {
        return new VersionManager.Progress() {
            @Override
            public void stage(String stage) {
                Platform.runLater(() -> statusLabel.setText(
                        I18n.get("main.stage." + stage)));
            }

            @Override
            public void progress(long done, long total) {
                Platform.runLater(() -> {
                    if (total <= 0) {
                        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                    } else {
                        progressBar.setProgress((double) done / total);
                    }
                });
            }
        };
    }

    private void runUpdate() {
        if (busy || lastManifest == null || latestVersionId == null) {
            return;
        }
        runTask(() -> new VersionManager(settings).updateTo(
                lastManifest, latestVersionId, uiProgress()),
                () -> statusLabel.setText(I18n.get("main.update.done", latestVersionId)));
    }

    private void runInstall() {
        if (busy || lastManifest == null) {
            return;
        }
        runTask(() -> new VersionManager(settings).installBase(lastManifest, uiProgress()),
                () -> statusLabel.setText(I18n.get("main.install.done", latestVersionId)));
    }

    /** Runs a blocking task on a background thread with busy UI state. */
    private void runTask(ThrowingRunnable task, Runnable onSuccess) {
        busy = true;
        setBusyUi(true);
        Thread worker = new Thread(() -> {
            try {
                task.run();
                Platform.runLater(() -> {
                    setBusyUi(false);
                    busy = false;
                    renderInstalled();
                    renderUpdateBox();
                    renderInstallButton();
                    onSuccess.run();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    setBusyUi(false);
                    busy = false;
                    statusLabel.setText(I18n.get("main.task.error", e.getMessage()));
                });
            }
        });
        worker.setDaemon(true);
        worker.start();
    }

    private void setBusyUi(boolean busy) {
        updateButton.setDisable(busy);
        installButton.setDisable(busy);
        checkButton.setDisable(busy);
        playButton.setDisable(busy
                || GameRunner.findGameExe(settings.getGamePath()) == null);
        progressBar.setVisible(busy);
        progressBar.setManaged(busy);
        progressBar.setProgress(0);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private void play() {
        try {
            GameRunner.launch(settings.getGamePath());
        } catch (IOException e) {
            statusLabel.setText(I18n.get("main.play.error", e.getMessage()));
        }
    }

    private void showAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, I18n.get("about.text"), ButtonType.OK);
        alert.setTitle(I18n.get("menu.about"));
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    // ---------- state and rendering ----------

    private void refreshState() {
        renderInstalled();
        playButton.setDisable(GameRunner.findGameExe(settings.getGamePath()) == null);
        renderInstallButton();
        if (settings.getManifestUrl().isBlank()) {
            statusLabel.setText(I18n.get("main.noManifestUrl"));
        }
    }

    private void renderInstalled() {
        String installed = settings.getInstalledVersion();
        installedLabel.setText(I18n.get("main.version",
                installed.isBlank() ? I18n.get("main.version.none") : installed));
    }

    private void renderLatest() {
        latestLabel.setText(I18n.get("main.latest",
                latestVersionId == null ? I18n.get("main.latest.unknown") : latestVersionId));
    }

    private void renderUpdateBox() {
        boolean available = latestVersionId != null
                && !settings.getInstalledVersion().isBlank()
                && !latestVersionId.equals(settings.getInstalledVersion());
        updateBox.setVisible(available);
        updateBox.setManaged(available);
        if (available) {
            updateLabel.setText(I18n.get("main.update.available", latestVersionId));
            changelogArea.setText(String.join("\n", latestChangelog));
        }
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
        checkButton.setText(I18n.get("main.check"));
        playButton.setText(I18n.get("main.play"));
        updateButton.setText(I18n.get("main.update.button"));
        installButton.setText(I18n.get("main.install.button"));
        changelogCaption.setText(I18n.get("main.changelog"));
        renderInstalled();
        renderLatest();
        renderUpdateBox();
        statusLabel.setText(checking ? I18n.get("main.checking") : "");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
