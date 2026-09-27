package com.hermitblaze.speedbuilders.config;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Mensajes de mensajes.yml en formato MiniMessage. */
public final class Messages {

    private final JavaPlugin plugin;
    private final MiniMessage mini = MiniMessage.miniMessage();
    private YamlConfiguration config = new YamlConfiguration();
    private Component prefix = Component.empty();

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "mensajes.yml");
        if (!file.exists()) {
            plugin.saveResource("mensajes.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);
        InputStream defaults = plugin.getResource("mensajes.yml");
        if (defaults != null) {
            config.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaults, StandardCharsets.UTF_8)));
        }
        prefix = mini.deserialize(config.getString("prefijo", ""));
    }

    public String raw(String key) {
        String value = config.getString(key);
        return value != null ? value : "<red>[Falta el mensaje: " + key + "]";
    }

    public Component get(String key, TagResolver... resolvers) {
        return mini.deserialize(raw(key), resolvers);
    }

    public Component parse(String miniMessage, TagResolver... resolvers) {
        return mini.deserialize(miniMessage, resolvers);
    }

    public Component prefixed(String key, TagResolver... resolvers) {
        return prefix.append(get(key, resolvers));
    }

    public void send(Audience audience, String key, TagResolver... resolvers) {
        audience.sendMessage(prefixed(key, resolvers));
    }

    public String escape(String text) {
        return mini.escapeTags(text);
    }

    /** Texto plano: no se interpretan etiquetas de MiniMessage. */
    public static TagResolver ph(String key, Object value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }

    /** Texto con formato MiniMessage. */
    public static TagResolver phParsed(String key, String value) {
        return Placeholder.parsed(key, value);
    }

    public static TagResolver phComponent(String key, Component value) {
        return Placeholder.component(key, value);
    }
}
