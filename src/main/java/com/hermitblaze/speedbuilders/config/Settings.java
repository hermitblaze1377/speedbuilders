package com.hermitblaze.speedbuilders.config;

import com.hermitblaze.speedbuilders.arena.Platform;
import com.hermitblaze.speedbuilders.build.Difficulty;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumMap;
import java.util.Map;
import java.util.logging.Logger;

/** Valores de config.yml ya validados. */
public record Settings(
        int minPlayers,
        int maxPlayers,
        int countdownSeconds,
        int memorizeSeconds,
        int evaluationSeconds,
        int endingSeconds,
        Map<Difficulty, Integer> buildSeconds,
        int maxRounds,
        int eliminationPercent,
        int border,
        int gap,
        int minRadius,
        int zoneHeight,
        Material zoneMaterial,
        Material ringMaterial,
        Material edgeMaterial,
        Material cornerMaterial,
        Material baseMaterial,
        boolean returnToLobby,
        boolean announceWinner
) {

    public int buildSeconds(Difficulty difficulty) {
        return buildSeconds.getOrDefault(difficulty, 60);
    }

    /** Lado total de cada isla (zona de 5x5 + borde a cada lado). */
    public int islandSize() {
        return (Platform.HALF + border) * 2 + 1;
    }

    public static Settings from(FileConfiguration c, Logger log) {
        Map<Difficulty, Integer> buildSeconds = new EnumMap<>(Difficulty.class);
        buildSeconds.put(Difficulty.FACIL, Math.max(5, c.getInt("tiempos.construir.FACIL", 45)));
        buildSeconds.put(Difficulty.MEDIO, Math.max(5, c.getInt("tiempos.construir.MEDIO", 70)));
        buildSeconds.put(Difficulty.DIFICIL, Math.max(5, c.getInt("tiempos.construir.DIFICIL", 95)));

        int min = Math.max(1, c.getInt("jugadores.minimos", 2));
        int max = Math.max(min, c.getInt("jugadores.maximos", 16));

        return new Settings(
                min,
                max,
                Math.max(3, c.getInt("tiempos.cuenta-regresiva", 30)),
                Math.max(3, c.getInt("tiempos.memorizar", 20)),
                Math.max(2, c.getInt("tiempos.evaluacion", 6)),
                Math.max(3, c.getInt("tiempos.final", 10)),
                buildSeconds,
                Math.max(1, c.getInt("rondas.maximas", 10)),
                Math.min(90, Math.max(1, c.getInt("rondas.porcentaje-eliminacion", 25))),
                Math.max(1, c.getInt("plataformas.borde", 2)),
                Math.max(1, c.getInt("plataformas.separacion", 4)),
                Math.max(4, c.getInt("plataformas.radio-minimo", 12)),
                Math.min(10, Math.max(3, c.getInt("plataformas.altura-zona", 6))),
                material(c, "plataformas.materiales.zona", Material.WHITE_CONCRETE, log),
                material(c, "plataformas.materiales.anillo", Material.LIGHT_GRAY_CONCRETE, log),
                material(c, "plataformas.materiales.borde", Material.CYAN_CONCRETE, log),
                material(c, "plataformas.materiales.esquinas", Material.SEA_LANTERN, log),
                material(c, "plataformas.materiales.base", Material.GRAY_CONCRETE, log),
                c.getBoolean("al-terminar-ir-al-lobby", true),
                c.getBoolean("anunciar-ganador-global", true)
        );
    }

    private static Material material(FileConfiguration c, String path, Material def, Logger log) {
        String name = c.getString(path);
        if (name == null) {
            return def;
        }
        Material material = Material.matchMaterial(name);
        if (material == null || !material.isBlock()) {
            log.warning("Material inválido en '" + path + "': " + name + ". Se usará " + def.name());
            return def;
        }
        return material;
    }
}
