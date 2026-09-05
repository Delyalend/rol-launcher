package rol.launcher;

import javafx.collections.FXCollections;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.Locale;

/**
 * Settings dialog: currently only language selection.
 * Returns the chosen locale on OK, null on cancel.
 */
public final class SettingsDialog extends Dialog<Locale> {

    private final ComboBox<Locale> languageBox = new ComboBox<>();

    public SettingsDialog(Locale current) {
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
        languageBox.setValue(current);

        VBox content = new VBox(8, new Label(I18n.get("settings.language")), languageBox);
        content.setPrefWidth(280);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        setResultConverter(buttonType ->
                buttonType == ButtonType.OK ? languageBox.getValue() : null);
    }
}
