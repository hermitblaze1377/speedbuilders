package com.hermitblaze.speedbuilders.build;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Convierte un .schem (formato Sponge v1, v2 o v3, el de WorldEdit 7 y FAWE) en las capas
 * de una construcción. Se recorta el aire sobrante y, si la base es menor de 5x5, se centra.
 */
final class SchematicLoader {

    private SchematicLoader() {
    }

    @SuppressWarnings("unchecked")
    static BlockData[][][] load(File file, int maxHeight) throws IOException {
        Map<String, Object> root = NbtReader.read(file);
        Map<String, Object> schematic = root.get("Schematic") instanceof Map<?, ?> inner
                ? (Map<String, Object>) inner : root;

        int width = number(schematic, "Width");
        int height = number(schematic, "Height");
        int length = number(schematic, "Length");

        Map<String, Object> palette;
        Object data;
        if (schematic.get("Blocks") instanceof Map<?, ?> blocks) {
            palette = (Map<String, Object>) blocks.get("Palette");
            data = blocks.get("Data");
        } else {
            palette = (Map<String, Object>) schematic.get("Palette");
            data = schematic.get("BlockData");
        }
        if (palette == null || !(data instanceof byte[] bytes)) {
            throw new IOException("formato .schem no reconocido (usa WorldEdit 7 o FAWE)");
        }

        int max = 0;
        for (Object value : palette.values()) {
            max = Math.max(max, ((Number) value).intValue());
        }
        BlockData[] states = new BlockData[max + 1];
        for (Map.Entry<String, Object> entry : palette.entrySet()) {
            BlockData state;
            try {
                state = Bukkit.createBlockData(entry.getKey());
            } catch (IllegalArgumentException ex) {
                throw new IOException("bloque no compatible con esta versión: " + entry.getKey());
            }
            Material material = state.getMaterial();
            if (material.isAir() || material == Material.STRUCTURE_VOID) {
                continue;
            }
            states[((Number) entry.getValue()).intValue()] = Bukkit.createBlockData(BuildManager.sanitize(state));
        }

        // Decodificación de los índices (varint), orden x + z*ancho + y*ancho*largo.
        int volume = width * height * length;
        BlockData[] blocks = new BlockData[volume];
        int index = 0;
        int position = 0;
        while (position < bytes.length && index < volume) {
            int value = 0;
            int shift = 0;
            byte current;
            do {
                current = bytes[position++];
                value |= (current & 0x7F) << shift;
                shift += 7;
                if (shift > 35) {
                    throw new IOException("datos de bloques corruptos");
                }
            } while ((current & 0x80) != 0 && position < bytes.length);
            blocks[index++] = value < states.length ? states[value] : null;
        }

        // Caja mínima que contiene bloques.
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = -1, maxY = -1, maxZ = -1;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    if (blocks[x + z * width + y * width * length] != null) {
                        minX = Math.min(minX, x);
                        minY = Math.min(minY, y);
                        minZ = Math.min(minZ, z);
                        maxX = Math.max(maxX, x);
                        maxY = Math.max(maxY, y);
                        maxZ = Math.max(maxZ, z);
                    }
                }
            }
        }
        if (maxX < 0) {
            throw new IOException("el .schem está vacío");
        }
        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;
        int sizeZ = maxZ - minZ + 1;
        if (sizeX > Build.SIZE || sizeZ > Build.SIZE) {
            throw new IOException("mide " + sizeX + "x" + sizeZ + " de base; debe ser como máximo 5x5");
        }
        if (sizeY > maxHeight) {
            throw new IOException("mide " + sizeY + " de alto; el máximo es " + maxHeight
                    + " (plataformas.altura-zona)");
        }

        int offsetX = (Build.SIZE - sizeX) / 2;
        int offsetZ = (Build.SIZE - sizeZ) / 2;
        BlockData[][][] layers = new BlockData[sizeY][Build.SIZE][Build.SIZE];
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    int source = (minX + x) + (minZ + z) * width + (minY + y) * width * length;
                    layers[y][offsetZ + z][offsetX + x] = blocks[source];
                }
            }
        }
        return layers;
    }

    private static int number(Map<String, Object> map, String key) throws IOException {
        if (!(map.get(key) instanceof Number number)) {
            throw new IOException("falta '" + key + "' en el .schem");
        }
        return number.intValue() & 0xFFFF;
    }
}
