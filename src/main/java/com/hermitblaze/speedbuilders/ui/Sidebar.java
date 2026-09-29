package com.hermitblaze.speedbuilders.ui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.List;
import java.util.Objects;

/**
 * Scoreboard lateral sin parpadeos: cada línea es el prefijo de un equipo.
 * Solo envía las líneas que cambiaron, para no saturar la red con muchos jugadores.
 */
public final class Sidebar {

    private static final int MAX_LINES = 15;

    private final Scoreboard scoreboard;
    private final Objective objective;
    private final Component[] current = new Component[MAX_LINES];
    private int shown;

    public Sidebar(Component title) {
        this.scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        this.objective = scoreboard.registerNewObjective("speedbuilders", Criteria.DUMMY, title);
        this.objective.setDisplaySlot(DisplaySlot.SIDEBAR);
    }

    public Scoreboard scoreboard() {
        return scoreboard;
    }

    public void update(List<Component> lines) {
        int size = Math.min(lines.size(), MAX_LINES);
        for (int i = 0; i < size; i++) {
            String entry = entry(i);
            Team team = scoreboard.getTeam("linea" + i);
            if (team == null) {
                team = scoreboard.registerNewTeam("linea" + i);
                team.addEntry(entry);
            }
            Component line = lines.get(i);
            if (!Objects.equals(current[i], line)) {
                team.prefix(line);
                current[i] = line;
            }
            if (i >= shown || size != shown) {
                objective.getScore(entry).setScore(size - i);
            }
        }
        for (int i = size; i < shown; i++) {
            scoreboard.resetScores(entry(i));
            current[i] = null;
        }
        shown = size;
    }

    /** Entrada invisible y única para cada línea. */
    private static String entry(int index) {
        return "§" + Integer.toHexString(index) + "§r";
    }
}
