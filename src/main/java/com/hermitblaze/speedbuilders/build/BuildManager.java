package com.hermitblaze.speedbuilders.build;

import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Normalizer;
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

/**
 * Carga las construcciones de tres fuentes (las posteriores reemplazan a las anteriores si
 * comparten id):
 * <ol>
 *   <li>Las incluidas en el plugin (construcciones.yml dentro del .jar).</li>
 *   <li>plugins/SpeedBuilders2/construcciones.yml, si existe (construcciones propias).</li>
 *   <li>La carpeta plugins/SpeedBuilders2/construcciones/ con subcarpetas facil/, medio/ y
 *       dificil/: archivos .schem (WorldEdit/FAWE) o .yml de una construcción cada uno.</li>
 * </ol>
 */
public final class BuildManager {

    private static final String FILE_NAME = "construcciones.yml";
    private static final String FOLDER_NAME = "construcciones";
    private static final String PALETTE_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789#$%&*+=?!";

    /** Propiedades que dependen del entorno y no se exigen al comparar construcciones guardadas. */
    private static final Set<String> IGNORED_PROPERTIES = Set.of(
            "waterlogged", "shape", "distance", "north", "south", "east", "west", "up", "down",
            "powered", "snowy", "age", "stage", "power", "note", "instrument", "occupied",
            "attached", "triggered", "moisture", "in_wall", "open");

    private final JavaPlugin plugin;
    private final File folder;
    private final Map<String, Build> builds = new LinkedHashMap<>();
    private int builtIn;
    private int fromFolder;

    public BuildManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), FOLDER_NAME);
    }

    public void load(int maxHeight) {
        builds.clear();
        builtIn = 0;
        fromFolder = 0;

        InputStream bundled = plugin.getResource(FILE_NAME);
        if (bundled != null) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(bundled, StandardCharsets.UTF_8));
            builtIn = loadSections(yaml, "incluidas", maxHeight);
        }
        File custom = new File(plugin.getDataFolder(), FILE_NAME);
        if (custom.exists()) {
            loadSections(YamlConfiguration.loadConfiguration(custom), FILE_NAME, maxHeight);
        }
        prepareFolder();
        fromFolder = loadFolder(folder, null, maxHeight);

        plugin.getLogger().info("Construcciones: " + builds.size() + " en total (" + builtIn
                + " incluidas, " + fromFolder + " de la carpeta " + FOLDER_NAME + "/).");
    }

    private int loadSections(YamlConfiguration yaml, String source, int maxHeight) {
        ConfigurationSection root = yaml.getConfigurationSection("construcciones");
        if (root == null) {
            return 0;
        }
        int count = 0;
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            try {
                register(parse(id.toLowerCase(Locale.ROOT), section, null), maxHeight);
                count++;
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Construcción '" + id + "' (" + source + ") ignorada: " + ex.getMessage());
            }
        }
        return count;
    }

    /** Crea la carpeta con sus subcarpetas y un archivo de ayuda la primera vez. */
    private void prepareFolder() {
        for (Difficulty difficulty : Difficulty.values()) {
            File sub = new File(folder, difficulty.name().toLowerCase(Locale.ROOT));
            if (!sub.exists() && !sub.mkdirs()) {
                plugin.getLogger().warning("No se pudo crear la carpeta " + sub.getPath());
            }
        }
        File readme = new File(folder, "LEEME.txt");
        if (!readme.exists()) {
            try {
                Files.writeString(readme.toPath(), String.join(System.lineSeparator(),
                        "CONSTRUCCIONES PROPIAS DE SPEEDBUILDERS",
                        "",
                        "Pon aquí tus construcciones, dentro de la subcarpeta de su dificultad:",
                        "  facil/   medio/   dificil/",
                        "",
                        "Formatos admitidos:",
                        "  .schem  Schematic de WorldEdit 7 o FAWE (//copy y luego //schem save <nombre>).",
                        "          La base debe medir como máximo 5x5 y la altura no puede superar",
                        "          plataformas.altura-zona (6 por defecto). El aire sobrante se recorta",
                        "          y, si la base es menor, se centra. El nombre del archivo es el nombre",
                        "          que ven los jugadores: 'Casa de campo.schem' -> Casa de campo.",
                        "  .yml    Una construcción con el formato de capas (nombre, paleta, capas).",
                        "          /sb guardar crea estos archivos automáticamente.",
                        "",
                        "Después usa /sb recargar (sin partida en curso) para cargarlas.",
                        "Si un archivo tiene errores, la consola indica el motivo y se ignora."),
                        StandardCharsets.UTF_8);
            } catch (IOException ex) {
                plugin.getLogger().warning("No se pudo crear " + readme.getPath() + ": " + ex.getMessage());
            }
        }
    }

    /** Recorre la carpeta; la dificultad sale de la subcarpeta facil/, medio/ o dificil/. */
    private int loadFolder(File directory, Difficulty inherited, int maxHeight) {
        File[] files = directory.listFiles();
        if (files == null) {
            return 0;
        }
        int count = 0;
        for (File file : files) {
            if (file.isDirectory()) {
                Difficulty difficulty = Difficulty.parse(file.getName());
                count += loadFolder(file, difficulty != null ? difficulty : inherited, maxHeight);
                continue;
            }
            String name = file.getName();
            String lower = name.toLowerCase(Locale.ROOT);
            try {
                if (lower.endsWith(".schem")) {
                    if (inherited == null) {
                        throw new IllegalArgumentException("ponlo dentro de facil/, medio/ o dificil/");
                    }
                    String displayName = displayName(name.substring(0, name.length() - ".schem".length()));
                    BlockData[][][] layers = SchematicLoader.load(file, maxHeight);
                    register(new Build(idFrom(displayName), displayName, inherited, layers), maxHeight);
                    count++;
                } else if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
                    String base = name.substring(0, name.lastIndexOf('.'));
                    register(parse(idFrom(base), YamlConfiguration.loadConfiguration(file), inherited), maxHeight);
                    count++;
                }
            } catch (IOException | IllegalArgumentException ex) {
                plugin.getLogger().warning("Construcción '" + folder.toPath().relativize(file.toPath())
                        + "' ignorada: " + ex.getMessage());
            }
        }
        return count;
    }

    private void register(Build build, int maxHeight) {
        if (build.height() > maxHeight) {
            throw new IllegalArgumentException("mide " + build.height() + " de alto; el máximo es " + maxHeight);
        }
        if (build.blockCount() == 0) {
            throw new IllegalArgumentException("no tiene bloques");
        }
        builds.put(build.id(), build);
    }

    public Collection<Build> all() {
        return Collections.unmodifiableCollection(builds.values());
    }

    public File folder() {
        return folder;
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

    /**
     * Guarda una construcción capturada del mundo en construcciones/&lt;dificultad&gt;/&lt;id&gt;.yml
     * y la deja disponible al instante.
     */
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

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("nombre", name);
        yaml.set("dificultad", difficulty.name());
        ConfigurationSection paletteSection = yaml.createSection("paleta");
        palette.forEach((data, symbol) -> paletteSection.set(String.valueOf(symbol), data));
        yaml.set("capas", rawLayers);

        File directory = new File(folder, difficulty.name().toLowerCase(Locale.ROOT));
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("no se pudo crear " + directory.getPath());
        }
        yaml.save(new File(directory, id + ".yml"));

        Build build = new Build(id, name, difficulty, layers);
        builds.put(id, build);
        return build;
    }

    /**
     * Lee una construcción en formato de capas.
     *
     * @param fallback dificultad a usar si la sección no la indica (la de su subcarpeta)
     */
    private Build parse(String id, ConfigurationSection section, Difficulty fallback) {
        String name = section.getString("nombre", displayName(id));
        String rawDifficulty = section.getString("dificultad");
        Difficulty difficulty = rawDifficulty != null ? Difficulty.parse(rawDifficulty) : fallback;
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

    /** "casa_de-campo" → "Casa de campo". */
    private static String displayName(String base) {
        String text = base.replace('_', ' ').replace('-', ' ').trim().replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            return base;
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** "Casa de Campo" → "casa_de_campo" (sin tildes ni símbolos). */
    private static String idFrom(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized.isEmpty() ? "construccion" : normalized;
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
