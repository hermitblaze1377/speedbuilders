package com.hermitblaze.speedbuilders.config;

import com.hermitblaze.speedbuilders.arena.Platform;
import com.hermitblaze.speedbuilders.build.Difficulty;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/** Valores de config.yml ya validados. */
public record Settings(
        int maxPlayers,
        int memorizeSeconds,
        int evaluationSeconds,
        int endingSeconds,
        Map<Difficulty, Integer> buildSeconds,
        int maxRounds,
        int eliminationPercent,
        boolean strictOrientation,
        List<Integer> positionPoints,
        int completionPoints,
        int winnerPoints,
        int leashDistance,
        int border,
        int gap,
        int minRadius,
        int maxSingleRingRadius,
        int zoneHeight,
        Material zoneMaterial,
        Material ringMaterial,
        Material edgeMaterial,
        Material cornerMaterial,
        Material baseMaterial
) {

    public int buildSeconds(Difficulty difficulty) {
        return buildSeconds.getOrDefault(difficulty, 45);
    }

    /** Puntos por completar la construcción en la posición dada (1 = primero). */
    public int pointsFor(int position) {
        if (position >= 1 && position <= positionPoints.size()) {
            return positionPoints.get(position - 1);
        }
        return completionPoints;
    }

    /** Lado total de cada isla (zona de 5x5 + borde a cada lado). */
    public int islandSize() {
        return (Platform.HALF + border) * 2 + 1;
    }

    public static Settings from(FileConfiguration c, Logger log) {
        Map<Difficulty, Integer> buildSeconds = new EnumMap<>(Difficulty.class);
        buildSeconds.put(Difficulty.FACIL, Math.max(5, c.getInt("tiempos.construir.FACIL", 30)));
        buildSeconds.put(Difficulty.MEDIO, Math.max(5, c.getInt("tiempos.construir.MEDIO", 45)));
        buildSeconds.put(Difficulty.DIFICIL, Math.max(5, c.getInt("tiempos.construir.DIFICIL", 60)));

        List<Integer> positionPoints = new ArrayList<>();
        for (Integer value : c.getIntegerList("puntos.por-posicion")) {
            positionPoints.add(Math.max(0, value));
        }
        if (positionPoints.isEmpty()) {
            positionPoints = List.of(10, 8, 6, 5, 4);
        }

        return new Settings(
                Math.max(1, c.getInt("jugadores.maximos", 128)),
                Math.max(3, c.getInt("tiempos.memorizar", 20)),
                Math.max(3, c.getInt("tiempos.evaluacion", 15)),
                Math.max(3, c.getInt("tiempos.final", 10)),
                buildSeconds,
                Math.max(1, c.getInt("rondas.maximas", 10)),
                Math.min(90, Math.max(1, c.getInt("rondas.porcentaje-eliminacion", 25))),
                c.getBoolean("similitud.exigir-orientacion", false),
                List.copyOf(positionPoints),
                Math.max(0, c.getInt("puntos.completar", 3)),
                Math.max(0, c.getInt("puntos.ganador", 25)),
                Math.max(1, c.getInt("plataformas.distancia-maxima", 3)),
                Math.max(1, c.getInt("plataformas.borde", 2)),
                Math.max(1, c.getInt("plataformas.separacion", 4)),
                Math.max(4, c.getInt("plataformas.radio-minimo", 12)),
                Math.max(10, c.getInt("plataformas.radio-maximo-un-anillo", 60)),
                Math.min(10, Math.max(3, c.getInt("plataformas.altura-zona", 6))),
                material(c, "plataformas.materiales.zona", Material.WHITE_CONCRETE, log),
                material(c, "plataformas.materiales.anillo", Material.LIGHT_GRAY_CONCRETE, log),
                material(c, "plataformas.materiales.borde", Material.CYAN_CONCRETE, log),
                material(c, "plataformas.materiales.esquinas", Material.SEA_LANTERN, log),
                material(c, "plataformas.materiales.base", Material.GRAY_CONCRETE, log)
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
