package com.hermitblaze.speedbuilders.game;

public enum GameState {
    ESPERANDO(false, "<gray>Esperando"),
    MEMORIZANDO(true, "<yellow>Memorizando"),
    CONSTRUYENDO(true, "<green>Construyendo"),
    EVALUANDO(true, "<light_purple>Evaluando"),
    FINALIZADO(true, "<gold>Finalizado");

    private final boolean running;
    private final String label;

    GameState(boolean running, String label) {
        this.running = running;
        this.label = label;
    }

    /** {@code true} cuando ya se generaron las plataformas. */
    public boolean isRunning() {
        return running;
    }

    public String label() {
        return label;
    }
}
