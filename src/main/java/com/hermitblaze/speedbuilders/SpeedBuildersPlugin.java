package com.hermitblaze.speedbuilders;

import com.hermitblaze.speedbuilders.arena.Arena;
import com.hermitblaze.speedbuilders.build.BuildManager;
import com.hermitblaze.speedbuilders.build.RecordManager;
import com.hermitblaze.speedbuilders.command.SpeedBuildersCommand;
import com.hermitblaze.speedbuilders.config.Messages;
import com.hermitblaze.speedbuilders.config.Settings;
import com.hermitblaze.speedbuilders.game.Game;
import com.hermitblaze.speedbuilders.listener.GameListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

public final class SpeedBuildersPlugin extends JavaPlugin {

    private Settings settings;
    private Messages messages;
    private BuildManager builds;
    private RecordManager records;
    private Arena arena;
    private Game game;

    @Override
    public void onEnable() {
        migrateFromV1();
        saveDefaultConfig();
        messages = new Messages(this);
        builds = new BuildManager(this);
        records = new RecordManager(this);
        arena = new Arena(this);
        reloadAll();

        game = new Game(this);
        game.start();

        getServer().getPluginManager().registerEvents(new GameListener(this), this);

        SpeedBuildersCommand executor = new SpeedBuildersCommand(this);
        PluginCommand command = getCommand("speedbuilders");
        if (command != null) {
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        // Mete en la arena a quienes ya estaban conectados (por ejemplo tras un /reload).
        getServer().getScheduler().runTask(this, game::joinAll);

        getLogger().info("SpeedBuilders 2 activado con " + builds.all().size() + " construcciones.");
    }

    @Override
    public void onDisable() {
        if (game != null) {
            game.shutdown();
        }
        if (records != null) {
            records.save();
        }
    }

    /**
     * SpeedBuilders 2 usa la carpeta plugins/SpeedBuilders2/. Si existe la de la versión
     * anterior (plugins/SpeedBuilders/), se copian la arena, los récords y las construcciones
     * propias. config.yml y mensajes.yml se generan nuevos.
     */
    private void migrateFromV1() {
        File oldFolder = new File(getDataFolder().getParentFile(), "SpeedBuilders");
        if (!oldFolder.isDirectory() || getDataFolder().exists()) {
            return;
        }
        Path target = getDataFolder().toPath();
        try {
            Files.createDirectories(target);
            for (String name : new String[]{"arena.yml", "records.yml"}) {
                Path source = oldFolder.toPath().resolve(name);
                if (Files.isRegularFile(source)) {
                    Files.copy(source, target.resolve(name));
                }
            }
            Path oldBuilds = oldFolder.toPath().resolve("construcciones");
            if (Files.isDirectory(oldBuilds)) {
                try (Stream<Path> paths = Files.walk(oldBuilds)) {
                    for (Path path : (Iterable<Path>) paths::iterator) {
                        Path destination = target.resolve(oldFolder.toPath().relativize(path).toString());
                        if (Files.isDirectory(path)) {
                            Files.createDirectories(destination);
                        } else {
                            Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            }
            getLogger().info("Se copiaron la arena, los récords y las construcciones de plugins/SpeedBuilders/. "
                    + "Ya puedes borrar esa carpeta y el .jar antiguo.");
        } catch (IOException ex) {
            getLogger().warning("No se pudieron copiar los datos de la versión anterior: " + ex.getMessage());
        }
    }

    /** Recarga config.yml, mensajes.yml, construcciones.yml y arena.yml. */
    public void reloadAll() {
        reloadConfig();
        settings = Settings.from(getConfig(), getLogger());
        messages.reload();
        builds.load(settings.zoneHeight());
        records.load();
        arena.load();
    }

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public BuildManager builds() {
        return builds;
    }

    public RecordManager records() {
        return records;
    }

    public Arena arena() {
        return arena;
    }

    public Game game() {
        return game;
    }
}
