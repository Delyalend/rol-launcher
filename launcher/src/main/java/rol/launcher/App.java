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
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

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
    private Menu versionsMenu;
    private MenuItem versionsOpenItem;
    private Menu helpMenu;
    private MenuItem aboutItem;
    private MenuItem logItem;
    // main view
    private Label installedLabel;
    private Label latestLabel;
    private Label currentChangelogCaption;
    private Label currentChangelogLabel;
    private Button checkButton;
    private Button versionsButton;
    private Label welcomeLabel;
    private ImageView logoImage;
    private TextField statusLabel;
    private VBox updateBox;
    private Label updateLabel;
    private Label changelogCaption;
    private TextArea changelogArea;
    private Button updateButton;
    private Button installButton;
    private Button playButton;
    private Button verifyButton;
    private ProgressBar progressBar;

    // state
    private String latestVersionId;
    private List<String> latestChangelog = List.of();
    private boolean checking;
    private boolean busy;
    private Manifest lastManifest;
    private String lastDetectionPath = "";

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        try (var iconStream = getClass().getResourceAsStream("/rol/launcher/theme/icon.png")) {
            if (iconStream != null) stage.getIcons().add(new Image(iconStream));
        } catch (IOException e) {
            Log.error("Failed to load launcher icon", e);
        }
        try {
            if (SwitchJournal.recover(Path.of(settings.getGamePath()).toAbsolutePath().normalize())) {
                Log.info("Recovered an unfinished version switch transaction");
            }
        } catch (Exception e) {
            Log.error("Failed to recover version switch transaction", e);
        }
        I18n.setLocale(settings.getLanguage());
        Log.info("Launcher started, version-check URL: " + settings.getManifestUrl());
        try {
            if (!settings.getGamePath().isBlank()) VersionManager.recover(Path.of(settings.getGamePath()));
        } catch (Exception e) {
            Log.error("Switch recovery failed", e);
        }
        buildUi();
        applyI18n();
        stage.show();
        stage.sizeToScene();
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

        // Versions menu (populated from the manifest)
        versionsMenu = new Menu();
        versionsOpenItem = new MenuItem();
        versionsOpenItem.setOnAction(e -> openVersions());
        versionsMenu.getItems().add(versionsOpenItem);
        versionsMenu.setDisable(true);

        // Help menu
        aboutItem = new MenuItem();
        aboutItem.setOnAction(e -> showAbout());
        MenuItem openLogItem = new MenuItem();
        openLogItem.setOnAction(e -> openLog());
        helpMenu = new Menu();
        helpMenu.getItems().addAll(openLogItem, new SeparatorMenuItem(), aboutItem);
        logItem = openLogItem;

        menuBar = new MenuBar(fileMenu, languageMenu, versionsMenu, helpMenu);

        // Main view
        welcomeLabel = new Label();
        welcomeLabel.getStyleClass().add("page-title");
        Image logo = new Image(getClass().getResourceAsStream("/rol/launcher/theme/logo.png"));
        logoImage = new ImageView(logo);
        logoImage.setPreserveRatio(true);
        logoImage.setFitWidth(330);
        logoImage.getStyleClass().add("game-logo");
        installedLabel = new Label();
        installedLabel.getStyleClass().add("version-value");
        latestLabel = new Label();
        latestLabel.getStyleClass().add("muted-label");
        currentChangelogCaption = new Label();
        currentChangelogCaption.getStyleClass().add("section-caption");
        currentChangelogLabel = new Label();
        currentChangelogLabel.setWrapText(true);
        currentChangelogLabel.getStyleClass().add("current-changelog");
        checkButton = new Button();
        checkButton.setOnAction(e -> checkUpdates());
        checkButton.getStyleClass().add("secondary-button");
        versionsButton = new Button();
        versionsButton.setOnAction(e -> openVersions());
        versionsButton.getStyleClass().add("secondary-button");
        statusLabel = new TextField();
        statusLabel.getStyleClass().add("status-line");
        statusLabel.setEditable(false);
        statusLabel.setFocusTraversable(false);
        statusLabel.setStyle("-fx-background-color: transparent; "
                + "-fx-background-insets: 0; -fx-padding: 0; -fx-border-color: transparent;");

        updateLabel = new Label();
        updateLabel.getStyleClass().add("card-title");
        changelogCaption = new Label();
        changelogCaption.getStyleClass().add("section-caption");
        changelogArea = new TextArea();
        changelogArea.getStyleClass().add("changelog-area");
        changelogArea.setEditable(false);
        changelogArea.setWrapText(true);
        changelogArea.setPrefRowCount(5);
        changelogArea.setMaxWidth(Double.MAX_VALUE);
        updateButton = new Button();
        updateButton.setOnAction(e -> runUpdate());
        updateButton.getStyleClass().add("accent-button");
        updateBox = new VBox(10, updateLabel, changelogCaption, changelogArea);
        updateBox.getStyleClass().add("update-card");
        updateBox.setVisible(false);
        updateBox.setManaged(false);
        updateButton.setVisible(false);
        updateButton.setManaged(false);

        installButton = new Button();
        installButton.setOnAction(e -> runInstall());
        installButton.getStyleClass().add("accent-button");
        installButton.setVisible(false);
        installButton.setManaged(false);

        playButton = new Button();
        playButton.setOnAction(e -> play());
        playButton.getStyleClass().add("play-button");

        verifyButton = new Button();
        verifyButton.setOnAction(e -> runVerify());
        verifyButton.getStyleClass().add("secondary-button");
        verifyButton.setVisible(false);
        verifyButton.setManaged(false);

        progressBar = new ProgressBar(0);
        progressBar.getStyleClass().add("download-progress");
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        progressBar.setManaged(false);

        VBox header = new VBox(4, logoImage, welcomeLabel, versionsButton);
        header.getStyleClass().add("header-row");
        HBox actions = new HBox(10, playButton, updateButton, installButton, verifyButton, checkButton);
        actions.getStyleClass().add("action-row");
        VBox statusCard = new VBox(6, installedLabel, latestLabel,
                currentChangelogCaption, currentChangelogLabel, statusLabel);
        statusCard.getStyleClass().add("status-card");
        VBox center = new VBox(18, header, statusCard, actions, updateBox, progressBar);
        center.getStyleClass().add("page-content");
        center.setPadding(new Insets(16));

        BorderPane root = new BorderPane();
        root.setTop(menuBar);
        root.setCenter(center);
        Scene scene = new Scene(root);
        var css = getClass().getResource("/rol/launcher/launcher.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        stage.setScene(scene);
    }

    // ---------- actions ----------

    private void changeLanguage(Locale locale) {
        settings.setLanguage(locale);
        I18n.setLocale(locale);
        applyI18n();
        refreshState();
    }

    private void openSettings() {
        String oldGamePath = settings.getGamePath();
        SettingsDialog dialog = new SettingsDialog(settings);
        Optional<SettingsDialog.Result> result = dialog.showAndWait();
        result.ifPresent(r -> {
            settings.setManifestUrl(r.manifestUrl());
            settings.setGamePath(r.gamePath());
            if (!oldGamePath.equals(settings.getGamePath())) lastDetectionPath = "";
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
                Log.error("Manifest check failed", e);
                Platform.runLater(() -> {
                    checking = false;
                    statusLabel.setText(I18n.get("main.check.error", e.getMessage())
                            + " - " + I18n.get("main.error.hint", Log.file()));
                });
            }
        });
        worker.setDaemon(true);
        worker.start();
    }

    private void onManifestLoaded(Manifest manifest) {
        checking = false;
        lastManifest = manifest;
        Log.info("Manifest loaded, latest=" + manifest.latest());
        latestVersionId = manifest.latest();
        Map<String, Object> latest = manifest.latestVersion();
        latestChangelog = latest == null ? List.of() : Manifest.changelogOf(latest);
        renderInstalled();
        renderLatest();
        renderUpdateBox();
        renderInstallButton();
        renderVerifyButton();
        refreshVersionsMenu();
        stage.sizeToScene();
        if (!busy
                && settings.getInstalledVersion().isBlank()
                && !settings.getGamePath().isBlank()
                && !settings.getGamePath().equals(lastDetectionPath)) {
            detectExistingGame();
            return;
        }
        if (!busy) {
            statusLabel.setText(settings.getInstalledVersion().isBlank()
                    ? I18n.get("main.notInstalled")
                    : updateBox.isVisible() ? "" : I18n.get("main.check.uptodate"));
        }
    }

    private void renderInstallButton() {
        boolean installable = lastManifest != null
                && lastManifest.baseVersionFor(lastManifest.latest()) != null
                && settings.getInstalledVersion().isBlank()
                && GameRunner.findGameExe(settings.getGamePath()) == null;
        installButton.setVisible(installable);
        installButton.setManaged(installable);
    }

    private void renderVerifyButton() {
        boolean available = lastManifest != null
                && !settings.getInstalledVersion().isBlank()
                && !settings.getGamePath().isBlank();
        verifyButton.setVisible(available);
        verifyButton.setManaged(available);
    }

    /** Keeps the compact menu entry available; details live in a dedicated dialog. */
    private void refreshVersionsMenu() {
        versionsMenu.getItems().clear();
        versionsMenu.getItems().add(versionsOpenItem);
        if (lastManifest == null) {
            versionsMenu.setDisable(true);
            return;
        }
        versionsMenu.setDisable(busy);
    }

    private void openVersions() {
        if (lastManifest == null || busy) return;
        VersionsDialog dialog = new VersionsDialog(lastManifest,
                settings.getInstalledVersion(), this::confirmSwitch);
        dialog.initOwner(stage);
        dialog.showAndWait();
    }

    private void confirmSwitch(String targetId) {
        if (targetId.equals(settings.getInstalledVersion())) {
            return;
        }
        if (settings.getGamePath().isBlank()) {
            statusLabel.setText(I18n.get("main.noGamePath"));
            return;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(stage);
        var css = getClass().getResource("/rol/launcher/launcher.css");
        if (css != null) alert.getDialogPane().getStylesheets().add(css.toExternalForm());
        alert.setGraphic(null);
        alert.setTitle(I18n.get("menu.versions"));
        alert.setHeaderText(I18n.get("main.switch.confirm",
                settings.getInstalledVersion(), targetId));
        alert.setContentText(I18n.get("main.switch.note"));
        ButtonType switchButton = new ButtonType(I18n.get("main.switch.button"));
        ButtonType cancelButton = new ButtonType(I18n.get("main.switch.cancel"));
        alert.getButtonTypes().setAll(switchButton, cancelButton);
        alert.getDialogPane().lookupButton(switchButton).getStyleClass().add("accent-button");
        alert.getDialogPane().lookupButton(cancelButton).getStyleClass().add("secondary-button");
        if (alert.showAndWait().orElse(cancelButton) == switchButton) {
            runSwitch(targetId);
        }
    }

    private void runSwitch(String targetId) {
        if (busy || lastManifest == null) {
            return;
        }
        runTask(() -> new VersionManager(settings).switchTo(lastManifest, targetId, uiProgress()),
                () -> statusLabel.setText(I18n.get("main.switch.done", targetId)));
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
        runTask(() -> new VersionManager(settings).switchTo(
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

    private void runVerify() {
        if (busy || lastManifest == null) return;
        runTask(() -> new VersionManager(settings).verifyInstalled(lastManifest, uiProgress()),
                () -> statusLabel.setText(I18n.get("main.verify.done")));
    }

    /** Detects an already populated game folder without modifying its contents. */
    private void detectExistingGame() {
        if (busy || lastManifest == null) return;
        lastDetectionPath = settings.getGamePath();
        String[] detectedVersion = {null};
        statusLabel.setText(I18n.get("main.detecting"));
        runTask(() -> {
            detectedVersion[0] = new VersionManager(settings)
                    .detectVersion(lastManifest, uiProgress());
            if (detectedVersion[0] != null) {
                settings.setInstalledVersion(detectedVersion[0]);
            }
        }, () -> statusLabel.setText(detectedVersion[0] == null
                ? I18n.get("main.detect.none")
                : I18n.get("main.detect.found", detectedVersion[0])));
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
                    renderVerifyButton();
                    refreshVersionsMenu();
                    stage.sizeToScene();
                    onSuccess.run();
                });
            } catch (Exception e) {
                Log.error("Task failed", e);
                Platform.runLater(() -> {
                    setBusyUi(false);
                    busy = false;
                    statusLabel.setText(I18n.get("main.task.error", e.getMessage())
                            + " - " + I18n.get("main.error.hint", Log.file()));
                });
            }
        });
        worker.setDaemon(true);
        worker.start();
    }

    private void setBusyUi(boolean busy) {
        updateButton.setDisable(busy);
        installButton.setDisable(busy);
        verifyButton.setDisable(busy);
        checkButton.setDisable(busy);
        versionsButton.setDisable(busy || lastManifest == null);
        playButton.setDisable(busy
                || GameRunner.findGameExe(settings.getGamePath()) == null);
        versionsMenu.setDisable(busy || lastManifest == null || versionsMenu.getItems().isEmpty());
        versionsButton.setDisable(busy || lastManifest == null);
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
            Log.error("Failed to launch the game", e);
            statusLabel.setText(I18n.get("main.play.error", e.getMessage())
                    + " - " + I18n.get("main.error.hint", Log.file()));
        }
    }

    private void showAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION,
                I18n.get("about.text") + System.lineSeparator() + System.lineSeparator()
                        + I18n.get("main.log.path", Log.file()),
                ButtonType.OK);
        alert.setTitle(I18n.get("menu.about"));
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    private void openLog() {
        try {
            java.awt.Desktop.getDesktop().open(Log.file().toFile());
        } catch (Exception e) {
            Log.error("Failed to open the log file", e);
            statusLabel.setText(I18n.get("main.log.open.error", e.getMessage()));
        }
    }

    // ---------- state and rendering ----------

    private void refreshState() {
        renderInstalled();
        playButton.setDisable(GameRunner.findGameExe(settings.getGamePath()) == null);
        renderInstallButton();
        renderVerifyButton();
        if (settings.getManifestUrl().isBlank()) {
            statusLabel.setText(I18n.get("main.noManifestUrl"));
        }
    }

    private void renderInstalled() {
        String installed = settings.getInstalledVersion();
        installedLabel.setText(I18n.get("main.version",
                installed.isBlank() ? I18n.get("main.version.none") : installed));
        Map<String, Object> current = lastManifest == null || installed.isBlank()
                ? null : lastManifest.version(installed);
        List<String> changes = current == null ? List.of() : Manifest.changelogOf(current);
        boolean visible = current != null;
        currentChangelogCaption.setVisible(visible);
        currentChangelogCaption.setManaged(visible);
        currentChangelogLabel.setVisible(visible);
        currentChangelogLabel.setManaged(visible);
        currentChangelogLabel.setText(changes.isEmpty()
                ? I18n.get("main.current.changelog.none")
                : String.join("\n", changes.stream().map(change -> "• " + change).toList()));
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
        updateButton.setVisible(available);
        updateButton.setManaged(available);
        if (available) {
            updateLabel.setText(I18n.get("main.update.available", latestVersionId));
            changelogArea.setText(String.join("\n", latestChangelog));
        }
    }

    /** Re-applies all UI texts after a locale change. */
    private void applyI18n() {
        stage.setTitle(I18n.get("app.title"));
        welcomeLabel.setText(I18n.get("main.welcome"));
        fileMenu.setText(I18n.get("menu.file"));
        settingsItem.setText(I18n.get("menu.settings"));
        exitItem.setText(I18n.get("menu.exit"));
        languageMenu.setText(I18n.get("menu.language"));
        langEn.setText(I18n.get("settings.language.en"));
        langRu.setText(I18n.get("settings.language.ru"));
        langEn.setSelected("en".equals(I18n.getLocale().getLanguage()));
        langRu.setSelected("ru".equals(I18n.getLocale().getLanguage()));
        versionsMenu.setText(I18n.get("menu.versions"));
        versionsOpenItem.setText(I18n.get("versions.open"));
        helpMenu.setText(I18n.get("menu.help"));
        aboutItem.setText(I18n.get("menu.about"));
        logItem.setText(I18n.get("menu.openlog"));
        checkButton.setText(I18n.get("main.check"));
        versionsButton.setText(I18n.get("versions.open"));
        playButton.setText(I18n.get("main.play"));
        updateButton.setText(I18n.get("main.update.button"));
        installButton.setText(I18n.get("main.install.button"));
        verifyButton.setText(I18n.get("main.verify.button"));
        changelogCaption.setText(I18n.get("main.changelog"));
        currentChangelogCaption.setText(I18n.get("main.current.changelog"));
        renderInstalled();
        renderLatest();
        renderUpdateBox();
        if (lastManifest != null) refreshVersionsMenu();
        statusLabel.setText(checking ? I18n.get("main.checking") : "");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
