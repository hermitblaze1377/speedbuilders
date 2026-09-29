package com.hermitblaze.speedbuilders.arena;

import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

/** Centro y lobby de la arena, guardados en arena.yml. */
public final class Arena {

    private final JavaPlugin plugin;
    private final File file;
    private Location center;
    private Location lobby;

    public Arena(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "arena.yml");
    }

    public void load() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        center = read(yaml, "centro");
        lobby = read(yaml, "lobby");
    }

    private Location read(YamlConfiguration yaml, String path) {
        try {
            Location location = yaml.getLocation(path);
            return location != null && location.getWorld() != null ? location : null;
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("No se pudo cargar '" + path + "' de arena.yml: " + ex.getMessage());
            return null;
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("centro", center);
        yaml.set("lobby", lobby);
        try {
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("No se pudo guardar arena.yml: " + ex.getMessage());
        }
    }

    public boolean isReady() {
        return center != null && lobby != null;
    }

    /** Bloque de suelo central: las plataformas se generan a esta altura. */
    public Location center() {
        return center == null ? null : center.clone();
    }

    public void setCenter(Location location) {
        Location block = location.getBlock().getLocation();
        block.setYaw(0f);
        block.setPitch(0f);
        this.center = block;
        save();
    }

    public Location lobby() {
        return lobby == null ? null : lobby.clone();
    }

    public void setLobby(Location location) {
        this.lobby = location.clone();
        save();
    }
}
