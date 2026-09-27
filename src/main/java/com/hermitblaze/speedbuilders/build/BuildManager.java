package com.hermitblaze.speedbuilders.build;

import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Carga y guarda las construcciones de construcciones.yml. */
public final class BuildManager {

    private static final String FILE_NAME = "construcciones.yml";
    private static final String PALETTE_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789#$%&*+=?!";

    /** Propiedades que dependen del entorno y no se exigen al comparar construcciones guardadas. */
    private static final Set<String> IGNORED_PROPERTIES = Set.of(
            "waterlogged", "shape", "distance", "north", "south", "east", "west", "up", "down",
            "powered", "snowy", "age", "stage", "power", "note", "instrument", "occupied",
            "attached", "triggered", "moisture", "in_wall", "open");

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, Build> builds = new LinkedHashMap<>();
    private YamlConfiguration yaml = new YamlConfiguration();

    public BuildManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource(FILE_NAME, false);
        }
        yaml = YamlConfiguration.loadConfiguration(file);
        builds.clear();

        ConfigurationSection root = yaml.getConfigurationSection("construcciones");
        if (root == null) {
            plugin.getLogger().warning(FILE_NAME + " no tiene la sección 'construcciones'.");
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            try {
                Build build = parse(id.toLowerCase(Locale.ROOT), section);
                builds.put(build.id(), build);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Construcción '" + id + "' ignorada: " + ex.getMessage());
            }
        }
    }

    public Collection<Build> all() {
        return Collections.unmodifiableCollection(builds.values());
    }

    public Build get(String id) {
        return id == null ? null : builds.get(id.toLowerCase(Locale.ROOT));
    }

    /**
     * Elige una construcción aleatoria de la dificultad indicada que no se haya usado
     * en la partida. Si no quedan, usa cualquiera sin repetir y, como último recurso, cualquiera.
     */
    public Build pick(Difficulty preferred, Set<String> used, Random random) {
        List<Build> pool = new ArrayList<>();
        for (Build build : builds.values()) {
            if (build.difficulty() == preferred && !used.contains(build.id())) {
                pool.add(build);
            }
        }
        if (pool.isEmpty()) {
            for (Build build : builds.values()) {
                if (!used.contains(build.id())) {
                    pool.add(build);
                }
            }
        }
        if (pool.isEmpty()) {
            pool.addAll(builds.values());
        }
        return pool.isEmpty() ? null : pool.get(random.nextInt(pool.size()));
    }

    /** Guarda una construcción capturada del mundo y la deja disponible al instante. */
    public Build save(String id, String name, Difficulty difficulty, BlockData[][][] captured) throws IOException {
        id = id.toLowerCase(Locale.ROOT);
        Map<String, Character> palette = new LinkedHashMap<>();
        BlockData[][][] layers = new BlockData[captured.length][Build.SIZE][Build.SIZE];
        List<List<String>> rawLayers = new ArrayList<>();

        for (int y = 0; y < captured.length; y++) {
            List<String> rows = new ArrayList<>();
            for (int z = 0; z < Build.SIZE; z++) {
                StringBuilder row = new StringBuilder();
                for (int x = 0; x < Build.SIZE; x++) {
                    BlockData data = captured[y][z][x];
                    if (data == null) {
                        row.append('.');
                        continue;
                    }
                    String clean = sanitize(data);
                    Character symbol = palette.get(clean);
                    if (symbol == null) {
                        if (palette.size() >= PALETTE_CHARS.length()) {
                            throw new IllegalStateException("La construcción tiene demasiados bloques distintos.");
                        }
                        symbol = PALETTE_CHARS.charAt(palette.size());
                        palette.put(clean, symbol);
                    }
                    row.append(symbol);
                    layers[y][z][x] = Bukkit.createBlockData(clean);
                }
                rows.add(row.toString());
            }
            rawLayers.add(rows);
        }

        ConfigurationSection section = yaml.createSection("construcciones." + id);
        section.set("nombre", name);
        section.set("dificultad", difficulty.name());
        ConfigurationSection paletteSection = section.createSection("paleta");
        palette.forEach((data, symbol) -> paletteSection.set(String.valueOf(symbol), data));
        section.set("capas", rawLayers);
        yaml.save(file);

        Build build = new Build(id, name, difficulty, layers);
        builds.put(id, build);
        return build;
    }

    private Build parse(String id, ConfigurationSection section) {
        String name = section.getString("nombre", id);
        Difficulty difficulty = Difficulty.parse(section.getString("dificultad", "MEDIO"));
        if (difficulty == null) {
            throw new IllegalArgumentException("dificultad inválida (usa FACIL, MEDIO o DIFICIL)");
        }

        ConfigurationSection paletteSection = section.getConfigurationSection("paleta");
        if (paletteSection == null) {
            throw new IllegalArgumentException("falta la sección 'paleta'");
        }
        Map<Character, BlockData> palette = new HashMap<>();
        for (String key : paletteSection.getKeys(false)) {
            if (key.length() != 1) {
                throw new IllegalArgumentException("la clave de paleta '" + key + "' debe ser un solo carácter");
            }
            String value = paletteSection.getString(key);
            try {
                palette.put(key.charAt(0), Bukkit.createBlockData(value));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("bloque inválido '" + value + "'");
            }
        }

        List<?> rawLayers = section.getList("capas");
        if (rawLayers == null || rawLayers.isEmpty()) {
            throw new IllegalArgumentException("falta la lista 'capas'");
        }
        BlockData[][][] layers = new BlockData[rawLayers.size()][Build.SIZE][Build.SIZE];
        for (int y = 0; y < rawLayers.size(); y++) {
            if (!(rawLayers.get(y) instanceof List<?> rows) || rows.size() != Build.SIZE) {
                throw new IllegalArgumentException("la capa " + (y + 1) + " debe tener 5 filas");
            }
            for (int z = 0; z < Build.SIZE; z++) {
                String row = String.valueOf(rows.get(z));
                if (row.length() != Build.SIZE) {
                    throw new IllegalArgumentException("la fila " + (z + 1) + " de la capa " + (y + 1)
                            + " debe tener 5 caracteres");
                }
                for (int x = 0; x < Build.SIZE; x++) {
                    char symbol = row.charAt(x);
                    if (symbol == '.' || symbol == ' ') {
                        continue;
                    }
                    BlockData data = palette.get(symbol);
                    if (data == null) {
                        throw new IllegalArgumentException("el símbolo '" + symbol + "' no está en la paleta");
                    }
                    layers[y][z][x] = data;
                }
            }
        }
        return new Build(id, name, difficulty, layers);
    }

    /** Quita propiedades que no dependen del jugador y fija las hojas como persistentes. */
    static String sanitize(BlockData data) {
        String full = data.getAsString();
        int open = full.indexOf('[');
        if (open < 0) {
            return full;
        }
        String base = full.substring(0, open);
        List<String> kept = new ArrayList<>();
        for (String property : full.substring(open + 1, full.length() - 1).split(",")) {
            String key = property.split("=", 2)[0];
            if (IGNORED_PROPERTIES.contains(key)) {
                continue;
            }
            kept.add(key.equals("persistent") ? "persistent=true" : property);
        }
        return kept.isEmpty() ? base : base + "[" + String.join(",", kept) + "]";
    }
}
