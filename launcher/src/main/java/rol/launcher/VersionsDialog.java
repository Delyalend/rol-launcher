package rol.launcher;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.stage.Screen;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** A readable version browser; actions are delegated to the main application. */
public final class VersionsDialog extends Dialog<Void> {

    private final Manifest manifest;

    public VersionsDialog(Manifest manifest, String installed, Consumer<String> onSelect) {
        this.manifest = manifest;
        setTitle(I18n.get("versions.title"));
        getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);
        addLauncherStylesheet();

        VBox list = new VBox(12);
        list.getStyleClass().add("version-list");
        List<Map<String, Object>> versions = new ArrayList<>(manifest.versions());
        Collections.reverse(versions);
        for (Map<String, Object> version : versions) {
            list.getChildren().add(card(version, installed, onSelect));
        }
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setMaxHeight(Screen.getPrimary().getVisualBounds().getHeight() * 0.72);
        scroll.getStyleClass().add("version-scroll");
        getDialogPane().setContent(scroll);
    }

    private void addLauncherStylesheet() {
        var css = getClass().getResource("/rol/launcher/launcher.css");
        if (css != null) getDialogPane().getStylesheets().add(css.toExternalForm());
    }

    private VBox card(Map<String, Object> version, String installed, Consumer<String> onSelect) {
        String id = Manifest.idOf(version);
        boolean current = id.equals(installed);
        String mode = current ? I18n.get("versions.current")
                : (!installed.isBlank() && Manifest.updateOf(version, installed) != null
                ? I18n.get("versions.direct") : I18n.get("versions.rebuild"));
        long download = current ? 0 : manifestDownload(version, installed);
        long freeSpace = Manifest.installedSizeOf(version) + download;

        Label title = new Label((current ? "• " : "") + id);
        title.getStyleClass().add("version-title");
        Label latest = new Label(id.equals(manifest.latest()) ? I18n.get("versions.latest") : "");
        latest.getStyleClass().add("version-badge");
        latest.setVisible(!latest.getText().isBlank());
        latest.setManaged(!latest.getText().isBlank());
        Label installedBadge = new Label(current ? I18n.get("versions.installed") : "");
        installedBadge.getStyleClass().add("version-badge");
        installedBadge.getStyleClass().add("installed-badge");
        installedBadge.setVisible(current);
        installedBadge.setManaged(current);
        Label date = new Label(I18n.get("versions.date", formatDate(Manifest.dateOf(version))));
        Label method = new Label(I18n.get("versions.mode", mode));
        Label size = new Label(I18n.get("versions.size", formatBytes(download), formatBytes(freeSpace)));
        HBox meta = new HBox(18, date, method, size);
        meta.getStyleClass().add("version-meta");

        List<String> changes = Manifest.changelogOf(version);
        VBox changesBox = new VBox(3);
        Label changesCaption = new Label(I18n.get("versions.changelogCaption"));
        changesCaption.getStyleClass().add("section-caption");
        changesBox.getChildren().add(changesCaption);
        if (changes.isEmpty()) {
            changesBox.getChildren().add(new Label(I18n.get("versions.noChangelog")));
        } else {
            for (String change : changes) {
                Label line = new Label("• " + change);
                line.setWrapText(true);
                changesBox.getChildren().add(line);
            }
        }

        Button action = new Button(current ? I18n.get("versions.installed") : I18n.get("versions.switch"));
        action.getStyleClass().add(current ? "secondary-button" : "accent-button");
        action.setVisible(!current);
        action.setManaged(!current);
        action.setOnAction(e -> {
            action.setDisable(true);
            close();
            // Let the modal window finish closing before opening the confirmation
            // dialog. Opening nested windows from this event can crash Glass on
            // Windows/JBR (EXCEPTION_ACCESS_VIOLATION in glass.dll).
            Platform.runLater(() -> onSelect.accept(id));
        });

        HBox top = new HBox(10, title, latest, installedBadge);
        HBox.setHgrow(latest, Priority.ALWAYS);
        VBox card = new VBox(8, top, meta, changesBox, action);
        card.getStyleClass().add("version-card");
        if (current) card.getStyleClass().add("current");
        card.setPadding(new Insets(16));
        return card;
    }

    private long manifestDownload(Map<String, Object> version, String installed) {
        return manifest.downloadSizeFor(Manifest.idOf(version), installed);
    }

    private static String formatDate(String raw) {
        if (raw == null || raw.isBlank()) return I18n.get("versions.unknown");
        try {
            return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                    .withLocale(I18n.getLocale()).withZone(ZoneId.systemDefault())
                    .format(Instant.parse(raw));
        } catch (Exception ignored) {
            return raw;
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
        return unit == 0 ? String.format(Locale.ROOT, "%d %s", bytes, units[unit])
                : String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}
