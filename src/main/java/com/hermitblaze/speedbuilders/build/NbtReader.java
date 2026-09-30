package com.hermitblaze.speedbuilders.build;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Lector mínimo de archivos NBT (el formato de los .schem de WorldEdit/FAWE).
 * Devuelve compuestos como {@code Map<String, Object>} y listas como {@code List<Object>}.
 */
final class NbtReader {

    private static final int MAX_DEPTH = 64;

    private NbtReader() {
    }

    static Map<String, Object> read(File file) throws IOException {
        InputStream raw = new BufferedInputStream(new FileInputStream(file));
        raw.mark(2);
        int first = raw.read();
        int second = raw.read();
        raw.reset();
        // Los .schem van comprimidos con gzip (0x1f 0x8b), pero se aceptan también sin comprimir.
        InputStream stream = first == 0x1f && second == 0x8b ? new GZIPInputStream(raw) : raw;
        try (DataInputStream in = new DataInputStream(stream)) {
            byte type = in.readByte();
            if (type != 10) {
                throw new IOException("no es un archivo NBT válido");
            }
            in.readUTF();
            return readCompound(in, 0);
        }
    }

    private static Map<String, Object> readCompound(DataInputStream in, int depth) throws IOException {
        Map<String, Object> map = new HashMap<>();
        while (true) {
            byte type = in.readByte();
            if (type == 0) {
                return map;
            }
            String name = in.readUTF();
            map.put(name, readPayload(in, type, depth + 1));
        }
    }

    private static Object readPayload(DataInputStream in, byte type, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT demasiado anidado");
        }
        switch (type) {
            case 1:
                return in.readByte();
            case 2:
                return in.readShort();
            case 3:
                return in.readInt();
            case 4:
                return in.readLong();
            case 5:
                return in.readFloat();
            case 6:
                return in.readDouble();
            case 7: {
                byte[] bytes = new byte[checkedLength(in.readInt())];
                in.readFully(bytes);
                return bytes;
            }
            case 8:
                return in.readUTF();
            case 9: {
                byte elementType = in.readByte();
                int length = checkedLength(in.readInt());
                List<Object> list = new ArrayList<>(Math.min(length, 1024));
                for (int i = 0; i < length; i++) {
                    list.add(readPayload(in, elementType, depth + 1));
                }
                return list;
            }
            case 10:
                return readCompound(in, depth);
            case 11: {
                int[] ints = new int[checkedLength(in.readInt())];
                for (int i = 0; i < ints.length; i++) {
                    ints[i] = in.readInt();
                }
                return ints;
            }
            case 12: {
                long[] longs = new long[checkedLength(in.readInt())];
                for (int i = 0; i < longs.length; i++) {
                    longs[i] = in.readLong();
                }
                return longs;
            }
            default:
                throw new IOException("tipo NBT desconocido: " + type);
        }
    }

    private static int checkedLength(int length) throws IOException {
        // Una construcción de Speed Builders es diminuta; esto evita reservar memoria absurda.
        if (length < 0 || length > 16 * 1024 * 1024) {
            throw new IOException("tamaño NBT inválido: " + length);
        }
        return length;
    }
}
