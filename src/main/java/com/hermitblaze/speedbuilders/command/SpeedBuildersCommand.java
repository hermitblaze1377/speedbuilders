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

public final class SpeedBuildersCommand implements TabExecutor {

    private static final String PERM_PLAY = "speedbuilders.jugar";
    private static final String PERM_ADMIN = "speedbuilders.admin";
    private static final List<String> PLAYER_SUBS = List.of("unirse", "salir", "ayuda");
    private static final List<String> ADMIN_SUBS = List.of("setcentro", "setlobby", "iniciar", "detener",
            "generar", "limpiar", "pegar", "guardar", "construcciones", "recargar");

    private final SpeedBuildersPlugin plugin;

    public SpeedBuildersCommand(SpeedBuildersPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messages m = plugin.messages();
        Game game = plugin.game();
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "unirse", "entrar", "join" -> {
                Player player = requirePlayer(sender, PERM_PLAY);
                if (player != null) {
                    game.join(player);
                }
            }
            case "salir", "leave" -> {
                Player player = requirePlayer(sender, PERM_PLAY);
                if (player != null) {
                    game.leave(player, false);
                }
            }
            case "ayuda", "help" -> help(sender);
            case "setcentro" -> {
                Player player = requirePlayer(sender, PERM_ADMIN);
                if (player != null) {
                    // El centro es el bloque sobre el que está parado el administrador.
                    Location floor = player.getLocation().clone().subtract(0, 1, 0);
                    plugin.arena().setCenter(floor);
                    m.send(player, "centro-establecido", ph("x", floor.getBlockX()), ph("y", floor.getBlockY()),
                            ph("z", floor.getBlockZ()));
                }
            }
            case "setlobby" -> {
                Player player = requirePlayer(sender, PERM_ADMIN);
                if (player != null) {
                    plugin.arena().setLobby(player.getLocation());
                    m.send(player, "lobby-establecido");
                }
            }
            case "iniciar", "start" -> {
                if (requirePermission(sender, PERM_ADMIN)) {
                    m.send(sender, game.forceStart() ? "partida-forzada" : "no-se-puede-iniciar");
                }
            }
            case "detener", "stop" -> {
                if (requirePermission(sender, PERM_ADMIN)) {
                    m.send(sender, game.stop() ? "partida-detenida-admin" : "no-hay-partida");
                }
            }
            case "generar" -> generate(sender, args);
            case "limpiar" -> {
                if (requirePermission(sender, PERM_ADMIN)) {
                    game.clearPreview();
                    m.send(sender, "plataformas-limpias");
                }
            }
            case "pegar" -> paste(sender, args);
            case "guardar" -> save(sender, args);
            case "construcciones", "lista" -> list(sender);
            case "recargar", "reload" -> {
                if (requirePermission(sender, PERM_ADMIN)) {
                    if (game.isActive()) {
                        m.send(sender, "no-recargar-en-partida");
                    } else {
                        plugin.reloadAll();
                        m.send(sender, "recargado", ph("cantidad", plugin.builds().all().size()));
                    }
                }
            }
            default -> m.send(sender, "comando-desconocido");
        }
        return true;
    }

    private void help(CommandSender sender) {
        Messages m = plugin.messages();
        sender.sendMessage(m.get("ayuda.jugador"));
        if (sender.hasPermission(PERM_ADMIN)) {
            sender.sendMessage(m.get("ayuda.admin"));
        }
    }

    private void generate(CommandSender sender, String[] args) {
        Messages m = plugin.messages();
        if (!requirePermission(sender, PERM_ADMIN)) {
            return;
        }
        int count = plugin.settings().maxPlayers();
        if (args.length > 1) {
            try {
                count = Integer.parseInt(args[1]);
            } catch (NumberFormatException ex) {
                m.send(sender, "numero-invalido");
                return;
            }
        }
        count = Math.max(1, Math.min(64, count));
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
        Player player = requirePlayer(sender, PERM_ADMIN);
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
        Player player = requirePlayer(sender, PERM_ADMIN);
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
        if (!requirePermission(sender, PERM_ADMIN)) {
            return;
        }
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

    private Player requirePlayer(CommandSender sender, String permission) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "solo-jugadores");
            return null;
        }
        return requirePermission(sender, permission) ? player : null;
    }

    private boolean requirePermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        plugin.messages().send(sender, "sin-permiso");
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(PLAYER_SUBS);
            if (sender.hasPermission(PERM_ADMIN)) {
                options.addAll(ADMIN_SUBS);
            }
        } else if (sender.hasPermission(PERM_ADMIN)) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (args.length == 2 && sub.equals("pegar")) {
                plugin.builds().all().forEach(build -> options.add(build.id()));
            } else if (args.length == 2 && sub.equals("generar")) {
                options.addAll(List.of("4", "8", "12", "16"));
            } else if (args.length == 3 && sub.equals("guardar")) {
                for (Difficulty difficulty : Difficulty.values()) {
                    options.add(difficulty.name().toLowerCase(Locale.ROOT));
                }
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(prefix)).collect(Collectors.toList());
    }
}
