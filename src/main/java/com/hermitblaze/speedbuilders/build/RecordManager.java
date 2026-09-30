package com.hermitblaze.speedbuilders.build;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Récords históricos (top 5 de tiempos) de cada construcción, guardados en records.yml. */
public final class RecordManager {

    public static final int TOP = 5;

    /** Un tiempo registrado. */
    public record Entry(UUID uuid, String name, long millis) {
    }

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, List<Entry>> records = new HashMap<>();
    private boolean dirty;

    public RecordManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "records.yml");
    }

    public void load() {
        records.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("records");
        if (root == null) {
            return;
        }
        for (String buildId : root.getKeys(false)) {
            List<Entry> entries = new ArrayList<>();
            for (Map<?, ?> raw : root.getMapList(buildId)) {
                try {
                    UUID uuid = UUID.fromString(String.valueOf(raw.get("uuid")));
                    String name = String.valueOf(raw.get("jugador"));
                    long millis = Long.parseLong(String.valueOf(raw.get("tiempo")));
                    entries.add(new Entry(uuid, name, millis));
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Récord inválido en records.yml (" + buildId + ") ignorado.");
                }
            }
            entries.sort(Comparator.comparingLong(Entry::millis));
            records.put(buildId, entries);
        }
    }

    public void save() {
        if (!dirty) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        records.forEach((buildId, entries) -> {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Entry entry : entries) {
                Map<String, Object> map = new HashMap<>();
                map.put("jugador", entry.name());
                map.put("uuid", entry.uuid().toString());
                map.put("tiempo", entry.millis());
                list.add(map);
            }
            yaml.set("records." + buildId, list);
        });
        try {
            yaml.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().severe("No se pudo guardar records.yml: " + ex.getMessage());
        }
    }

    public List<Entry> top(String buildId) {
        return Collections.unmodifiableList(records.getOrDefault(buildId, List.of()));
    }

    /**
     * Registra un tiempo. Cada jugador aparece una sola vez (con su mejor marca).
     *
     * @return el puesto que ocupa en el top (1-5), o 0 si no entró o no mejoró su marca
     */
    public int submit(String buildId, UUID uuid, String name, long millis) {
        List<Entry> entries = new ArrayList<>(records.getOrDefault(buildId, List.of()));
        for (Entry entry : entries) {
            if (entry.uuid().equals(uuid) && entry.millis() <= millis) {
                return 0;
            }
        }
        entries.removeIf(entry -> entry.uuid().equals(uuid));
        Entry mine = new Entry(uuid, name, millis);
        entries.add(mine);
        entries.sort(Comparator.comparingLong(Entry::millis));
        if (entries.size() > TOP) {
            entries = new ArrayList<>(entries.subList(0, TOP));
        }
        int position = entries.indexOf(mine) + 1;
        if (position > 0) {
            records.put(buildId, entries);
            dirty = true;
        }
        return position;
    }
}
