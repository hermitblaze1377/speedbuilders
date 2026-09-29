package com.hermitblaze.speedbuilders.command;

import com.hermitblaze.speedbuilders.SpeedBuildersPlugin;
import com.hermitblaze.speedbuilders.arena.Platform;
import com.hermitblaze.speedbuilders.build.Build;
import com.hermitblaze.speedbuilders.build.Difficulty;
import com.hermitblaze.speedbuilders.config.Messages;
import com.hermitblaze.speedbuilders.game.Game;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static com.hermitblaze.speedbuilders.config.Messages.ph;
import static com.hermitblaze.speedbuilders.config.Messages.phParsed;

/**
 * /sb: solo comandos de administración. Los jugadores entran a la arena
 * automáticamente al conectarse.
 */
public final class SpeedBuildersCommand implements TabExecutor {

    private static final String PERM_ADMIN = "speedbuilders.admin";
    private static final List<String> SUBCOMMANDS = List.of("iniciar", "detener", "editar", "setcentro",
            "setlobby", "generar", "limpiar", "pegar", "guardar", "construcciones", "recargar", "ayuda");

    private final SpeedBuildersPlugin plugin;

    public SpeedBuildersCommand(SpeedBuildersPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messages m = plugin.messages();
        if (!sender.hasPermission(PERM_ADMIN)) {
            m.send(sender, "sin-permiso");
            return true;
        }
        Game game = plugin.game();
        if (args.length == 0) {
            help(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "iniciar", "start" -> m.send(sender, game.forceStart() ? "partida-forzada" : "no-se-puede-iniciar");
            case "detener", "stop" -> m.send(sender, game.stop() ? "partida-detenida-admin" : "no-hay-partida");
            case "editar" -> {
                Player player = requirePlayer(sender);
                if (player != null) {
                    m.send(player, game.toggleEditor(player) ? "modo-editor-activado" : "modo-editor-desactivado");
                }
            }
            case "setcentro" -> {
                Player player = requirePlayer(sender);
                if (player != null) {
                    // El centro es el bloque sobre el que está parado el administrador.
                    Location floor = player.getLocation().clone().subtract(0, 1, 0);
                    plugin.arena().setCenter(floor);
                    m.send(player, "centro-establecido", ph("x", floor.getBlockX()), ph("y", floor.getBlockY()),
                            ph("z", floor.getBlockZ()));
                    afterArenaChange(player);
                }
            }
            case "setlobby" -> {
                Player player = requirePlayer(sender);
                if (player != null) {
                    plugin.arena().setLobby(player.getLocation());
                    m.send(player, "lobby-establecido");
                    afterArenaChange(player);
                }
            }
            case "generar" -> generate(sender, args);
            case "limpiar" -> {
                game.clearPreview();
                m.send(sender, "plataformas-limpias");
            }
            case "pegar" -> paste(sender, args);
            case "guardar" -> save(sender, args);
            case "construcciones", "lista" -> list(sender);
            case "recargar", "reload" -> {
                if (game.isRunning()) {
                    m.send(sender, "no-recargar-en-partida");
                } else {
                    plugin.reloadAll();
                    game.joinAll();
                    m.send(sender, "recargado", ph("cantidad", plugin.builds().all().size()));
                }
            }
            case "ayuda", "help" -> help(sender);
            default -> m.send(sender, "comando-desconocido");
        }
        return true;
    }

    /**
     * Mientras configura, el administrador queda en modo editor para no ser llevado al lobby.
     * Cuando la arena ya está lista, entran todos los demás conectados.
     */
    private void afterArenaChange(Player player) {
        Game game = plugin.game();
        if (game.enterEditor(player)) {
            plugin.messages().send(player, "modo-editor-activado");
        }
        if (plugin.arena().isReady()) {
            game.joinAll();
        }
    }

    private void help(CommandSender sender) {
        sender.sendMessage(plugin.messages().get("ayuda"));
    }

    private void generate(CommandSender sender, String[] args) {
        Messages m = plugin.messages();
        int count = plugin.settings().maxPlayers();
        if (args.length > 1) {
            try {
                count = Integer.parseInt(args[1]);
            } catch (NumberFormatException ex) {
                m.send(sender, "numero-invalido");
                return;
            }
        }
        count = Math.max(1, Math.min(plugin.settings().maxPlayers(), count));
        if (plugin.arena().center() == null) {
            m.send(sender, "arena-no-lista");
        } else if (plugin.game().generatePreview(count)) {
            m.send(sender, "plataformas-generadas", ph("cantidad", count));
        } else {
            m.send(sender, "no-generar-en-partida");
        }
    }

    private void paste(CommandSender sender, String[] args) {
        Messages m = plugin.messages();
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            m.send(player, "uso-pegar");
            return;
        }
        Build build = plugin.builds().get(args[1]);
        if (build == null) {
            m.send(player, "construccion-no-existe", ph("id", args[1]));
            return;
        }
        Platform platform = plugin.game().platformAt(player.getLocation());
        if (platform == null) {
            m.send(player, "no-en-plataforma");
            return;
        }
        platform.paste(build);
        m.send(player, "construccion-pegada", ph("construccion", build.name()));
    }

    private void save(CommandSender sender, String[] args) {
        Messages m = plugin.messages();
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length < 4) {
            m.send(player, "uso-guardar");
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_-]+")) {
            m.send(player, "id-invalido");
            return;
        }
        Difficulty difficulty = Difficulty.parse(args[2]);
        if (difficulty == null) {
            m.send(player, "dificultad-invalida");
            return;
        }
        String name = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
        Platform platform = plugin.game().platformAt(player.getLocation());
        if (platform == null) {
            m.send(player, "no-en-plataforma");
            return;
        }
        BlockData[][][] captured = platform.capture();
        if (captured.length == 0) {
            m.send(player, "zona-vacia");
            return;
        }
        try {
            Build build = plugin.builds().save(id, name, difficulty, captured);
            m.send(player, "construccion-guardada", ph("id", build.id()), ph("construccion", build.name()),
                    ph("bloques", build.blockCount()));
        } catch (IOException | IllegalStateException ex) {
            m.send(player, "error-guardar", ph("error", ex.getMessage()));
        }
    }

    private void list(CommandSender sender) {
        Messages m = plugin.messages();
        m.send(sender, "lista-cabecera", ph("cantidad", plugin.builds().all().size()));
        for (Difficulty difficulty : Difficulty.values()) {
            String names = plugin.builds().all().stream()
                    .filter(build -> build.difficulty() == difficulty)
                    .map(build -> build.name() + " (" + build.id() + ")")
                    .collect(Collectors.joining(", "));
            sender.sendMessage(m.get("lista-linea", phParsed("dificultad", difficulty.formatted()),
                    ph("construcciones", names.isEmpty() ? "-" : names)));
        }
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        plugin.messages().send(sender, "solo-jugadores");
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            return List.of();
        }
        List<String> options = new ArrayList<>();
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            options.addAll(SUBCOMMANDS);
        } else if (args.length == 2 && sub.equals("pegar")) {
            plugin.builds().all().forEach(build -> options.add(build.id()));
        } else if (args.length == 2 && sub.equals("generar")) {
            options.addAll(List.of("8", "16", "32", "64", "128"));
        } else if (args.length == 3 && sub.equals("guardar")) {
            for (Difficulty difficulty : Difficulty.values()) {
                options.add(difficulty.name().toLowerCase(Locale.ROOT));
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(prefix)).collect(Collectors.toList());
    }
}
