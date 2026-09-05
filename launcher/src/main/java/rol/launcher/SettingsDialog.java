package rol.launcher;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.DirectoryChooser;
import javafx.util.StringConverter;

import java.io.File;
import java.util.Locale;

/**
 * Settings dialog: language, manifest URL and game folder.
 * Returns a SettingsResult on OK, null on cancel.
 */
public final class SettingsDialog extends Dialog<SettingsDialog.Result> {

    /** Values chosen in the dialog. */
    public record Result(Locale locale, String manifestUrl, String gamePath) {}

    private final ComboBox<Locale> languageBox = new ComboBox<>();
    private final TextField manifestUrlField = new TextField();
    private final TextField gamePathField = new TextField();

    public SettingsDialog(SettingsManager settings) {
        setTitle(I18n.get("settings.title"));

        languageBox.setItems(FXCollections.observableArrayList(
                Locale.ENGLISH, Locale.of("ru")));
        languageBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(Locale locale) {
                return I18n.get("settings.language." + locale.getLanguage());
            }

            @Override
            public Locale fromString(String string) {
                throw new UnsupportedOperationException();
            }
        });
        languageBox.setValue(settings.getLanguage());

        manifestUrlField.setText(settings.getManifestUrl());
        gamePathField.setText(settings.getGamePath());

        Button browseButton = new Button(I18n.get("settings.browse"));
        browseButton.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(I18n.get("settings.gamePath"));
            if (!gamePathField.getText().isBlank()) {
                File current = new File(gamePathField.getText());
                if (current.isDirectory()) {
                    chooser.setInitialDirectory(current);
                }
            }
            File picked = chooser.showDialog(getDialogPane().getScene().getWindow());
            if (picked != null) {
                gamePathField.setText(picked.getAbsolutePath());
            }
        });

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));
        grid.add(new Label(I18n.get("settings.language")), 0, 0);
        grid.add(languageBox, 1, 0);
        grid.add(new Label(I18n.get("settings.manifestUrl")), 0, 1);
        grid.add(manifestUrlField, 1, 1);
        grid.add(new Label(I18n.get("settings.gamePath")), 0, 2);
        grid.add(gamePathField, 1, 2);
        grid.add(browseButton, 2, 2);
        manifestUrlField.setPrefWidth(380);

        getDialogPane().setContent(grid);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        setResultConverter(buttonType -> buttonType == ButtonType.OK
                ? new Result(languageBox.getValue(), manifestUrlField.getText(), gamePathField.getText())
                : null);
    }
}
