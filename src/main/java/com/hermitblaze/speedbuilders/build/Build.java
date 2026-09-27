package com.hermitblaze.speedbuilders.build;

import org.bukkit.Material;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.TrapDoor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Construcción de 5x5 de base. Las capas se guardan como [y][z][x]:
 * y = altura desde el suelo, z = de norte a sur, x = de oeste a este.
 * Un valor {@code null} es aire.
 */
public final class Build {

    public static final int SIZE = 5;

    private final String id;
    private final String name;
    private final Difficulty difficulty;
    private final BlockData[][][] layers;
    private final Map<Material, Integer> materials;

    public Build(String id, String name, Difficulty difficulty, BlockData[][][] layers) {
        this.id = id;
        this.name = name;
        this.difficulty = difficulty;
        this.layers = layers;
        this.materials = Collections.unmodifiableMap(countMaterials(layers));
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Difficulty difficulty() {
        return difficulty;
    }

    public int height() {
        return layers.length;
    }

    public BlockData blockAt(int x, int y, int z) {
        if (y < 0 || y >= layers.length || x < 0 || x >= SIZE || z < 0 || z >= SIZE) {
            return null;
        }
        return layers[y][z][x];
    }

    /** Objetos que necesita el jugador para replicar la construcción. */
    public Map<Material, Integer> materials() {
        return materials;
    }

    public int blockCount() {
        return materials.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static Map<Material, Integer> countMaterials(BlockData[][][] layers) {
        Map<Material, Integer> counts = new LinkedHashMap<>();
        for (BlockData[][] layer : layers) {
            for (BlockData[] row : layer) {
                for (BlockData data : row) {
                    if (data == null) {
                        continue;
                    }
                    // La mitad superior de puertas y plantas altas no es un objeto aparte.
                    if (data instanceof Bisected bisected
                            && !(data instanceof Stairs)
                            && !(data instanceof TrapDoor)
                            && bisected.getHalf() == Bisected.Half.TOP) {
                        continue;
                    }
                    if (data instanceof Bed bed && bed.getPart() == Bed.Part.HEAD) {
                        continue;
                    }
                    Material item = data.getPlacementMaterial();
                    if (item.isAir() || !item.isItem()) {
                        continue;
                    }
                    int amount = data instanceof Slab slab && slab.getType() == Slab.Type.DOUBLE ? 2 : 1;
                    counts.merge(item, amount, Integer::sum);
                }
            }
        }
        return counts;
    }
}
