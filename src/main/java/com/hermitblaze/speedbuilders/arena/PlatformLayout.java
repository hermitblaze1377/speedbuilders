package com.hermitblaze.speedbuilders.arena;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;

/** Reparte las plataformas en círculo, de forma simétrica, alrededor del centro. */
public final class PlatformLayout {

    private PlatformLayout() {
    }

    /**
     * @param center     bloque central de la arena
     * @param count      número de plataformas
     * @param islandSize lado de cada isla en bloques
     * @param gap        bloques de separación mínima entre islas vecinas
     * @param minRadius  radio mínimo del círculo
     */
    public static List<Location> compute(Location center, int count, int islandSize, int gap, int minRadius) {
        List<Location> centers = new ArrayList<>(count);
        if (count <= 0) {
            return centers;
        }
        if (count == 1) {
            centers.add(center.getBlock().getLocation());
            return centers;
        }
        // La cuerda entre dos islas vecinas debe dejar 'gap' bloques libres (+1 por el redondeo).
        double chord = islandSize + gap + 1;
        double radius = Math.max(minRadius, chord / (2 * Math.sin(Math.PI / count)));
        for (int i = 0; i < count; i++) {
            double angle = -Math.PI / 2 + (2 * Math.PI * i) / count;
            int x = center.getBlockX() + (int) Math.round(radius * Math.cos(angle));
            int z = center.getBlockZ() + (int) Math.round(radius * Math.sin(angle));
            centers.add(new Location(center.getWorld(), x, center.getBlockY(), z));
        }
        return centers;
    }
}
