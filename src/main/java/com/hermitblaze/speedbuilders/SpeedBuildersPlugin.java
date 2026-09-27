package com.hermitblaze.speedbuilders;

import com.hermitblaze.speedbuilders.arena.Arena;
import com.hermitblaze.speedbuilders.build.BuildManager;
import com.hermitblaze.speedbuilders.command.SpeedBuildersCommand;
import com.hermitblaze.speedbuilders.config.Messages;
import com.hermitblaze.speedbuilders.config.Settings;
import com.hermitblaze.speedbuilders.game.Game;
import com.hermitblaze.speedbuilders.listener.GameListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class SpeedBuildersPlugin extends JavaPlugin {

    private Settings settings;
    private Messages messages;
    private BuildManager builds;
    private Arena arena;
    private Game game;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new Messages(this);
        builds = new BuildManager(this);
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

        getLogger().info("SpeedBuilders activado con " + builds.all().size() + " construcciones.");
    }

    @Override
    public void onDisable() {
        if (game != null) {
            game.shutdown();
        }
    }

    /** Recarga config.yml, mensajes.yml, construcciones.yml y arena.yml. */
    public void reloadAll() {
        reloadConfig();
        settings = Settings.from(getConfig(), getLogger());
        messages.reload();
        builds.load();
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

    public Arena arena() {
        return arena;
    }

    public Game game() {
        return game;
    }
}
