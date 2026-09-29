package com.hermitblaze.speedbuilders.arena;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;

/**
 * Reparte las plataformas de forma simétrica alrededor del centro: un solo
 * círculo cuando son pocas, o anillos concéntricos cuando son muchas
 * (cada anillo con sus islas a la misma distancia angular).
 */
public final class PlatformLayout {

    private PlatformLayout() {
    }

    /**
     * @param center          bloque central de la arena
     * @param count           número de plataformas
     * @param islandSize      lado de cada isla en bloques
     * @param gap             bloques libres entre islas vecinas
     * @param minRadius       radio del círculo más cercano al centro
     * @param maxSingleRadius radio máximo antes de pasar a anillos concéntricos
     */
    public static List<Location> compute(Location center, int count, int islandSize, int gap,
                                         int minRadius, int maxSingleRadius) {
        List<Location> centers = new ArrayList<>(count);
        if (count <= 0) {
            return centers;
        }
        if (count == 1) {
            centers.add(center.getBlock().getLocation());
            return centers;
        }
        // Distancia mínima entre centros de islas vecinas. Las islas son cuadradas, así que en
        // diagonal (45°) hace falta sqrt(2) veces más distancia; +1 por el redondeo a bloques.
        double spacing = (islandSize + gap) * Math.sqrt(2) + 1;
        // Además los anillos deben estar lo bastante lejos del centro para no tapar al espectador.
        double innerRadius = Math.max(minRadius, spacing);

        List<Integer> ringCounts = new ArrayList<>();
        List<Double> ringRadii = new ArrayList<>();
        double single = Math.max(innerRadius, spacing / (2 * Math.sin(Math.PI / count)));
        if (single <= maxSingleRadius) {
            ringCounts.add(count);
            ringRadii.add(single);
        } else {
            int remaining = count;
            double radius = innerRadius;
            while (remaining > 0) {
                int capacity = Math.max(1, (int) Math.floor(2 * Math.PI * radius / spacing));
                int amount = Math.min(capacity, remaining);
                ringCounts.add(amount);
                ringRadii.add(radius);
                remaining -= amount;
                radius += spacing;
            }
        }

        for (int ring = 0; ring < ringCounts.size(); ring++) {
            int amount = ringCounts.get(ring);
            double radius = ringRadii.get(ring);
            // Los anillos alternos se desfasan medio paso para que las islas no queden alineadas.
            double offset = ring % 2 == 0 ? 0 : Math.PI / amount;
            for (int i = 0; i < amount; i++) {
                double angle = -Math.PI / 2 + offset + (2 * Math.PI * i) / amount;
                int x = center.getBlockX() + (int) Math.round(radius * Math.cos(angle));
                int z = center.getBlockZ() + (int) Math.round(radius * Math.sin(angle));
                centers.add(new Location(center.getWorld(), x, center.getBlockY(), z));
            }
        }
        return centers;
    }
}
