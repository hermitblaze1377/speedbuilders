package com.hermitblaze.speedbuilders.game;

import com.hermitblaze.speedbuilders.arena.Platform;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

import java.util.UUID;

public final class GamePlayer {

    private final UUID uuid;
    private final String name;
    private final PlayerSnapshot snapshot;
    private Platform platform;
    private boolean alive;
    private boolean finished;
    private double percent;
    private long finishMillis;
    private int points;
    private int place;
    private TextDisplay hologram;

    public GamePlayer(Player player, PlayerSnapshot snapshot) {
        this.uuid = player.getUniqueId();
        this.name = player.getName();
        this.snapshot = snapshot;
    }

    /** Jugador conectado o {@code null}. */
    public Player player() {
        return Bukkit.getPlayer(uuid);
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public PlayerSnapshot snapshot() {
        return snapshot;
    }

    public Platform platform() {
        return platform;
    }

    public void setPlatform(Platform platform) {
        this.platform = platform;
    }

    public boolean isAlive() {
        return alive;
    }

    public void setAlive(boolean alive) {
        this.alive = alive;
    }

    public boolean isFinished() {
        return finished;
    }

    public double percent() {
        return percent;
    }

    public void setPercent(double percent) {
        this.percent = percent;
    }

    public long finishMillis() {
        return finishMillis;
    }

    public void finish(long millis) {
        this.finished = true;
        this.finishMillis = millis;
        this.percent = 100;
    }

    public int points() {
        return points;
    }

    public void addPoints(int amount) {
        this.points += amount;
    }

    /** Puesto final en la partida (1 = ganador, 0 = aún en juego). */
    public int place() {
        return place;
    }

    public void setPlace(int place) {
        this.place = place;
    }

    public TextDisplay hologram() {
        return hologram;
    }

    public void setHologram(TextDisplay hologram) {
        this.hologram = hologram;
    }

    public void removeHologram() {
        if (hologram != null) {
            hologram.remove();
            hologram = null;
        }
    }

    public void resetRound() {
        finished = false;
        percent = 0;
        finishMillis = 0;
    }
}
