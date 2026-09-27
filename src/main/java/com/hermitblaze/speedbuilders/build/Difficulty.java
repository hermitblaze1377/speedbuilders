package com.hermitblaze.speedbuilders.build;

import java.text.Normalizer;
import java.util.Locale;

public enum Difficulty {
    FACIL("Fácil", "<green>"),
    MEDIO("Medio", "<gold>"),
    DIFICIL("Difícil", "<red>");

    private final String displayName;
    private final String color;

    Difficulty(String displayName, String color) {
        this.displayName = displayName;
        this.color = color;
    }

    public String displayName() {
        return displayName;
    }

    /** Nombre con color, en formato MiniMessage. */
    public String formatted() {
        return color + displayName + "</" + color.substring(1);
    }

    /** Acepta "facil", "Fácil", "DIFICIL", "difícil"... */
    public static Difficulty parse(String text) {
        if (text == null) {
            return null;
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT)
                .trim();
        for (Difficulty difficulty : values()) {
            if (difficulty.name().equals(normalized)) {
                return difficulty;
            }
        }
        return null;
    }
}
