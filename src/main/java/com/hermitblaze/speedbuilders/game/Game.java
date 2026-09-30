package com.hermitblaze.speedbuilders.game;

import com.hermitblaze.speedbuilders.SpeedBuildersPlugin;
import com.hermitblaze.speedbuilders.arena.Arena;
import com.hermitblaze.speedbuilders.arena.Platform;
import com.hermitblaze.speedbuilders.arena.PlatformLayout;
import com.hermitblaze.speedbuilders.arena.Similarity;
import com.hermitblaze.speedbuilders.build.Build;
import com.hermitblaze.speedbuilders.build.Difficulty;
import com.hermitblaze.speedbuilders.build.RecordManager;
import com.hermitblaze.speedbuilders.config.Messages;
import com.hermitblaze.speedbuilders.config.Settings;
import com.hermitblaze.speedbuilders.ui.Sidebar;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.hermitblaze.speedbuilders.config.Messages.ph;
import static com.hermitblaze.speedbuilders.config.Messages.phComponent;
import static com.hermitblaze.speedbuilders.config.Messages.phParsed;

/**
 * Flujo de una partida:
 * ESPERANDO (lobby, hasta /sb iniciar) → [MEMORIZANDO → CONSTRUYENDO → EVALUANDO] x rondas → FINALIZADO.
 *
 * Todos los jugadores conectados están en la arena. Al terminar, vuelven al lobby
 * para la siguiente partida.
 */
public final class Game {

    public static final String PERM_ADMIN = "speedbuilders.admin";

    private static final Title.Times TIMES = Title.Times.times(
            Duration.ofMillis(200), Duration.ofMillis(2200), Duration.ofMillis(500));
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yy");
    private static final String BAR_CHAR = "▌";
    private static final int BAR_LENGTH = 20;
    /** Líneas de la tabla de resultados en el chat; el resto se resume. */
    private static final int RESULT_LINES = 10;
    /** Nombres que se listan como eliminados en el chat antes de resumir. */
    private static final int ELIMINATED_NAMES = 15;
    private static final int TOP_SIZE = 5;

    private final SpeedBuildersPlugin plugin;
    private final Map<UUID, GamePlayer> players = new LinkedHashMap<>();
    private final Map<UUID, Sidebar> sidebars = new HashMap<>();
    private final Set<UUID> editors = new HashSet<>();
    private final List<Platform> platforms = new ArrayList<>();
    private final List<Platform> previewPlatforms = new ArrayList<>();
    private final List<GamePlayer> qualified = new ArrayList<>();
    private final Set<String> usedBuilds = new HashSet<>();
    private final Random random = new Random();
    private final BossBar bossBar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.YELLOW,
            BossBar.Overlay.NOTCHED_20);

    private BukkitTask task;
    private GameState state = GameState.ESPERANDO;
    private Build currentBuild;
    private GamePlayer winner;
    private Location spectatorPoint;
    private int round;
    private int ticksLeft;
    private int phaseTicks;
    private int toEliminate;
    private int quota;
    private int startingPlayers;
    private int layoutCount;
    private int generation;
    private long buildStartMillis;
    private long tickCounter;

    public Game(SpeedBuildersPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
        }
        reset(false);
        clearPreview();
    }

    private Settings settings() {
        return plugin.settings();
    }

    private Messages messages() {
        return plugin.messages();
    }

    private Arena arena() {
        return plugin.arena();
    }

    public GameState state() {
        return state;
    }

    /** ¿Hay una partida en marcha? */
    public boolean isRunning() {
        return state.isRunning();
    }

    public GamePlayer player(Player player) {
        return players.get(player.getUniqueId());
    }

    // ------------------------------------------------------------------
    // Entrada automática
    // ------------------------------------------------------------------

    /** Mete en la arena a todos los conectados que aún no están (y no son editores). */
    public void joinAll() {
        for (Player online : Bukkit.getOnlinePlayers()) {
            join(online);
        }
    }

    /**
     * Se llama al conectarse. Si la partida está en marcha entra como espectador;
     * si no, espera en el lobby a que un administrador la inicie.
     */
    public void join(Player player) {
        UUID uuid = player.getUniqueId();
        if (players.containsKey(uuid) || editors.contains(uuid) || !arena().isReady()
                || !player.hasPermission("speedbuilders.jugar")) {
            return;
        }
        Messages m = messages();
        GamePlayer gp = new GamePlayer(player, PlayerSnapshot.capture(player));
        players.put(uuid, gp);
        Sidebar sidebar = new Sidebar(m.get("scoreboard.titulo"));
        sidebars.put(uuid, sidebar);
        player.setScoreboard(sidebar.scoreboard());
        player.showBossBar(bossBar);

        if (state.isRunning()) {
            gp.setAlive(false);
            makeSpectator(player);
            m.send(player, "unido-espectador");
        } else {
            boolean full = aliveCount() >= settings().maxPlayers();
            gp.setAlive(!full);
            prepareLobby(player);
            if (full) {
                m.send(player, "partida-llena");
            } else {
                broadcast("unido", ph("jugador", player.getName()), ph("actual", aliveCount()),
                        ph("maximo", settings().maxPlayers()));
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            }
        }
        updateSidebars();
    }

    /** Se llama al desconectarse (o al pasar a modo editor). */
    public void leave(Player player) {
        GamePlayer gp = players.remove(player.getUniqueId());
        if (gp == null) {
            sidebars.remove(player.getUniqueId());
            return;
        }
        boolean wasAlive = gp.isAlive();
        restore(player, gp);

        if (!state.isRunning()) {
            if (wasAlive) {
                broadcast("salio", ph("jugador", gp.name()), ph("actual", aliveCount()),
                        ph("maximo", settings().maxPlayers()));
            }
            return;
        }
        gp.removeHologram();
        if (players.isEmpty()) {
            reset(true);
            return;
        }
        if (!wasAlive || state == GameState.FINALIZADO) {
            return;
        }
        gp.setPlace(aliveCount() + 1);
        gp.setAlive(false);
        qualified.remove(gp);
        if (gp.platform() != null) {
            gp.platform().explode();
        }
        broadcast("abandono", ph("jugador", gp.name()));

        int alive = aliveCount();
        if (alive == 0) {
            finish(null);
        } else if (alive == 1 && startingPlayers > 1) {
            finish(alivePlayers().get(0));
        } else if (state == GameState.CONSTRUYENDO && qualified.size() >= Math.min(quota, alive)) {
            endRound();
        }
    }

    /**
     * Modo editor para administradores: sale de la arena (recupera su inventario y modo
     * de juego) para poder configurar o construir. Devuelve {@code true} si quedó activado.
     */
    public boolean toggleEditor(Player player) {
        if (editors.remove(player.getUniqueId())) {
            sidebars.remove(player.getUniqueId());
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            join(player);
            return false;
        }
        enterEditor(player);
        return true;
    }

    /** Activa el modo editor si no lo estaba. Devuelve {@code true} si se acaba de activar. */
    public boolean enterEditor(Player player) {
        if (!editors.add(player.getUniqueId())) {
            return false;
        }
        leave(player);
        return true;
    }

    // ------------------------------------------------------------------
    // Control de administrador
    // ------------------------------------------------------------------

    /** Inicia la partida con los jugadores que esperan en el lobby. */
    public boolean forceStart() {
        if (state != GameState.ESPERANDO || aliveCount() == 0) {
            return false;
        }
        beginGame();
        return true;
    }

    public boolean stop() {
        if (!state.isRunning()) {
            return false;
        }
        broadcast("partida-detenida");
        reset(true);
        return true;
    }

    /** Genera plataformas de muestra para ver la distribución o guardar construcciones. */
    public boolean generatePreview(int count) {
        Location center = arena().center();
        if (state.isRunning() || center == null) {
            return false;
        }
        clearPreview();
        Settings s = settings();
        for (Location location : layout(center, count)) {
            Platform platform = new Platform(location, s);
            platform.build(s);
            previewPlatforms.add(platform);
        }
        return true;
    }

    public void clearPreview() {
        previewPlatforms.forEach(Platform::destroy);
        previewPlatforms.clear();
    }

    /** Plataforma (de muestra o de partida) en la que está la ubicación dada. */
    public Platform platformAt(Location location) {
        for (Platform platform : previewPlatforms) {
            if (platform.contains(location)) {
                return platform;
            }
        }
        for (Platform platform : platforms) {
            if (platform.contains(location)) {
                return platform;
            }
        }
        return null;
    }

    private List<Location> layout(Location center, int count) {
        Settings s = settings();
        return PlatformLayout.compute(center, count, s.islandSize(), s.gap(), s.minRadius(),
                s.maxSingleRingRadius());
    }

    // ------------------------------------------------------------------
    // Bucle principal (cada tick)
    // ------------------------------------------------------------------

    private void tick() {
        tickCounter++;
        if (players.isEmpty()) {
            return;
        }
        switch (state) {
            case ESPERANDO -> {
            }
            case MEMORIZANDO -> {
                ticksLeft--;
                if (ticksLeft <= 0) {
                    startBuilding();
                } else {
                    if (ticksLeft % 20 == 0 && ticksLeft <= 60) {
                        playAll(Sound.BLOCK_NOTE_BLOCK_HAT, 1.4f);
                    }
                    if (tickCounter % 10 == 0) {
                        actionBarAlive(messages().get("actionbar.memorizando", ph("segundos", seconds())));
                    }
                }
            }
            case CONSTRUYENDO -> {
                ticksLeft--;
                if (tickCounter % 4 == 0) {
                    updateBuilders();
                }
                if (state == GameState.CONSTRUYENDO) {
                    if (ticksLeft <= 0) {
                        endRound();
                    } else if (ticksLeft % 20 == 0 && ticksLeft <= 100) {
                        playAll(Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f);
                    }
                }
            }
            case EVALUANDO -> {
                ticksLeft--;
                if (ticksLeft <= 0) {
                    afterEvaluation();
                }
            }
            case FINALIZADO -> {
                ticksLeft--;
                if (ticksLeft % 15 == 0) {
                    celebrateWinner();
                }
                if (ticksLeft <= 0) {
                    reset(true);
                    return;
                }
            }
        }
        if (players.isEmpty()) {
            return;
        }
        if (tickCounter % 2 == 0) {
            updateBossBar();
        }
        if (tickCounter % 10 == 0) {
            updateSidebars();
            updateHolograms();
        }
    }

    private int seconds() {
        return Math.max(0, (ticksLeft + 19) / 20);
    }

    // ------------------------------------------------------------------
    // Fases
    // ------------------------------------------------------------------

    private void beginGame() {
        clearPreview();
        Settings s = settings();
        List<GamePlayer> competitors = new ArrayList<>(alivePlayers());
        Collections.shuffle(competitors, random);
        Location center = arena().center();
        List<Location> centers = layout(center, competitors.size());

        double farthest = 0;
        for (int i = 0; i < competitors.size(); i++) {
            GamePlayer gp = competitors.get(i);
            Platform platform = new Platform(centers.get(i), s);
            platform.build(s);
            platforms.add(platform);
            gp.setPlatform(platform);
            createHologram(gp);
            farthest = Math.max(farthest, centers.get(i).distance(center));
            Player p = gp.player();
            if (p != null) {
                p.teleport(platform.spawn());
                prepareBuilder(p);
            }
        }
        layoutCount = competitors.size();
        // Cuanto más grande la arena, más alto el punto de los espectadores.
        spectatorPoint = center.clone().add(0.5, Math.max(14, farthest * 0.6), 0.5);
        spectatorPoint.setPitch(farthest > 30 ? 90f : 70f);
        for (GamePlayer gp : players.values()) {
            Player p = gp.player();
            if (!gp.isAlive() && p != null) {
                makeSpectator(p);
            }
        }

        startingPlayers = competitors.size();
        round = 0;
        usedBuilds.clear();
        broadcast("partida-iniciada", ph("jugadores", startingPlayers), ph("rondas", s.maxRounds()));
        playAll(Sound.BLOCK_BEACON_ACTIVATE, 1f);
        nextRound();
    }

    /**
     * Cuando hay eliminados, reconstruye las islas de los que siguen en juego más cerca
     * del centro. Se hace de una vez al empezar la ronda (unas decenas de miles de bloques
     * con 128 jugadores, sin física), así que el impacto es un solo tick algo más pesado.
     */
    private void relayout() {
        List<GamePlayer> alive = alivePlayers();
        if (alive.isEmpty() || alive.size() >= layoutCount) {
            return;
        }
        Settings s = settings();
        Location center = arena().center();
        // Se conserva el orden angular para que cada jugador se mueva lo menos posible.
        alive.sort(Comparator.comparingDouble(gp -> angle(gp.platform(), center)));
        List<Location> centers = layout(center, alive.size());
        centers.sort(Comparator.comparingDouble(location -> angle(location, center)));

        platforms.forEach(Platform::destroy);
        platforms.clear();
        for (int i = 0; i < alive.size(); i++) {
            GamePlayer gp = alive.get(i);
            Platform platform = new Platform(centers.get(i), s);
            platform.build(s);
            platforms.add(platform);
            gp.setPlatform(platform);
            if (gp.hologram() != null) {
                gp.hologram().teleport(platform.hologramLocation());
            }
            Player p = gp.player();
            if (p != null) {
                p.teleport(platform.spawn());
            }
        }
        layoutCount = alive.size();
        broadcast("plataformas-reubicadas");
    }

    private static double angle(Platform platform, Location center) {
        return platform == null ? 0 : angle(platform.center(), center);
    }

    private static double angle(Location location, Location center) {
        return Math.atan2(location.getZ() - center.getZ(), location.getX() - center.getX());
    }

    private void nextRound() {
        Settings s = settings();
        round++;
        qualified.clear();
        currentBuild = plugin.builds().pick(difficultyFor(round), usedBuilds, random);
        if (currentBuild == null) {
            broadcast("sin-construcciones");
            reset(true);
            return;
        }
        usedBuilds.add(currentBuild.id());
        relayout();
        state = GameState.MEMORIZANDO;
        ticksLeft = phaseTicks = s.memorizeSeconds() * 20;

        TagResolver[] resolvers = {
                ph("ronda", round),
                ph("maximo", s.maxRounds()),
                ph("construccion", currentBuild.name()),
                phParsed("dificultad", currentBuild.difficulty().formatted()),
                ph("segundos", s.memorizeSeconds()),
                ph("bloques", currentBuild.blockCount())
        };
        for (GamePlayer gp : alivePlayers()) {
            gp.resetRound();
            gp.platform().paste(currentBuild);
            Player p = gp.player();
            if (p != null) {
                p.getInventory().clear();
                p.teleport(gp.platform().spawn());
                p.setFlying(false);
            }
        }
        broadcastRaw("anuncio-ronda", resolvers);
        showRecords();
        for (GamePlayer gp : players.values()) {
            Player p = gp.player();
            if (p != null) {
                title(p, "titulo.ronda", "titulo.ronda-sub", resolvers);
            }
        }
        playAll(Sound.BLOCK_NOTE_BLOCK_BELL, 1.2f);
    }

    /** Muestra en el chat el top 5 histórico de la construcción actual. */
    private void showRecords() {
        List<RecordManager.Entry> top = plugin.records().top(currentBuild.id());
        broadcastRaw("record.cabecera", ph("construccion", currentBuild.name()));
        if (top.isEmpty()) {
            broadcastRaw("record.vacio");
        }
        for (int i = 0; i < top.size(); i++) {
            RecordManager.Entry entry = top.get(i);
            broadcastRaw("record.linea", ph("posicion", i + 1), ph("jugador", entry.name()),
                    ph("tiempo", formatSeconds(entry.millis())));
        }
        broadcastRaw("record.pie");
    }

    private Difficulty difficultyFor(int roundNumber) {
        double progress = (double) roundNumber / settings().maxRounds();
        if (progress <= 0.3) {
            return Difficulty.FACIL;
        }
        return progress <= 0.7 ? Difficulty.MEDIO : Difficulty.DIFICIL;
    }

    private void startBuilding() {
        Settings s = settings();
        List<GamePlayer> alive = alivePlayers();
        int count = alive.size();
        if (count <= 1) {
            toEliminate = 0;
            quota = count;
        } else {
            // Se elimina el porcentaje configurado, pero siempre lo suficiente
            // para que la partida termine dentro del máximo de rondas.
            int roundsLeft = Math.max(1, s.maxRounds() - round + 1);
            int byPercent = (int) Math.ceil(count * s.eliminationPercent() / 100.0);
            int needed = (int) Math.ceil((count - 1) / (double) roundsLeft);
            toEliminate = Math.min(count - 1, Math.max(1, Math.max(byPercent, needed)));
            quota = count - toEliminate;
        }

        state = GameState.CONSTRUYENDO;
        ticksLeft = phaseTicks = s.buildSeconds(currentBuild.difficulty()) * 20;
        buildStartMillis = System.currentTimeMillis();

        for (GamePlayer gp : alive) {
            gp.platform().vanishEffect();
            gp.platform().clearZone();
            Player p = gp.player();
            if (p != null) {
                giveMaterials(p, currentBuild);
                title(p, "titulo.construir", "titulo.construir-sub", ph("clasificados", quota));
            }
        }
        broadcast("inicio-construccion", ph("clasificados", quota), ph("eliminados", toEliminate),
                ph("segundos", s.buildSeconds(currentBuild.difficulty())));
        playAll(Sound.ENTITY_PLAYER_LEVELUP, 1.4f);
    }

    private void updateBuilders() {
        for (GamePlayer gp : alivePlayers()) {
            if (state != GameState.CONSTRUYENDO) {
                return;
            }
            if (gp.isFinished()) {
                Player p = gp.player();
                if (p != null) {
                    p.sendActionBar(messages().get("actionbar.completado"));
                }
                continue;
            }
            evaluate(gp);
        }
    }

    /** Revisión inmediata tras colocar o romper un bloque. */
    public void scheduleCheck(Player player) {
        int gen = generation;
        Bukkit.getScheduler().runTask(plugin, () -> {
            GamePlayer gp = players.get(player.getUniqueId());
            if (gen == generation && gp != null && state == GameState.CONSTRUYENDO
                    && gp.isAlive() && !gp.isFinished()) {
                evaluate(gp);
            }
        });
    }

    private Similarity similarity(GamePlayer gp) {
        return gp.platform().compare(currentBuild, settings().strictOrientation());
    }

    private void evaluate(GamePlayer gp) {
        Similarity similarity = similarity(gp);
        gp.setPercent(similarity.percent());
        Player p = gp.player();
        if (p != null) {
            p.sendActionBar(messages().get("actionbar.construyendo",
                    phComponent("barra", progressBar(similarity.percent())),
                    phComponent("porcentaje", Component.text(formatPercent(similarity.percent()) + "%",
                            percentColor(similarity.percent()))),
                    ph("segundos", seconds())));
        }
        if (similarity.perfect()) {
            markFinished(gp);
        }
    }

    private void markFinished(GamePlayer gp) {
        gp.finish(System.currentTimeMillis() - buildStartMillis);
        qualified.add(gp);
        String time = formatSeconds(gp.finishMillis());
        int position = qualified.size();
        int points = settings().pointsFor(position);
        gp.addPoints(points);

        gp.platform().celebrate();
        Player p = gp.player();
        if (p != null) {
            p.getInventory().clear();
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
            title(p, "titulo.perfecto", "titulo.perfecto-sub", ph("tiempo", time), ph("posicion", position),
                    ph("puntos", points));
        }
        broadcast("completado", ph("jugador", gp.name()), ph("tiempo", time), ph("posicion", position),
                ph("clasificados", quota), ph("puntos", points));
        playAll(Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.6f);

        int recordPosition = plugin.records().submit(currentBuild.id(), gp.uuid(), gp.name(), gp.finishMillis());
        if (recordPosition == 1) {
            broadcast("record.nuevo", ph("jugador", gp.name()), ph("tiempo", time),
                    ph("construccion", currentBuild.name()));
            playAll(Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.5f);
        } else if (recordPosition > 1) {
            broadcast("record.entra-top", ph("jugador", gp.name()), ph("posicion", recordPosition),
                    ph("construccion", currentBuild.name()));
        }

        boolean allDone = alivePlayers().stream().allMatch(GamePlayer::isFinished);
        if (qualified.size() >= quota || allDone) {
            endRound();
        }
    }

    private void endRound() {
        state = GameState.EVALUANDO;
        int evaluationTicks = settings().evaluationSeconds() * 20;
        plugin.records().save();

        List<GamePlayer> alive = alivePlayers();
        for (GamePlayer gp : alive) {
            if (!gp.isFinished()) {
                gp.setPercent(similarity(gp).percent());
            }
            Player p = gp.player();
            if (p != null) {
                p.getInventory().clear();
            }
        }

        // Quien no completó la construcción queda eliminado. Si nadie la completó,
        // se salvan los que más se acercaron y cae el porcentaje de eliminación.
        List<GamePlayer> eliminated = new ArrayList<>();
        if (!qualified.isEmpty() || alive.size() <= 1) {
            for (GamePlayer gp : alive) {
                if (!gp.isFinished()) {
                    eliminated.add(gp);
                }
            }
        } else {
            List<GamePlayer> sorted = new ArrayList<>(alive);
            sorted.sort(Comparator.comparingDouble(GamePlayer::percent));
            eliminated.addAll(sorted.subList(0, Math.min(toEliminate, sorted.size())));
        }

        // Puesto final: el peor porcentaje ocupa el último lugar disponible.
        eliminated.sort(Comparator.comparingDouble(GamePlayer::percent));
        for (int i = 0; i < eliminated.size(); i++) {
            eliminated.get(i).setPlace(alive.size() - i);
        }

        List<GamePlayer> ranking = new ArrayList<>(qualified);
        alive.stream()
                .filter(gp -> !gp.isFinished())
                .sorted(Comparator.comparingDouble(GamePlayer::percent).reversed())
                .forEach(ranking::add);
        showResults(ranking, eliminated);

        for (GamePlayer gp : eliminated) {
            gp.setAlive(false);
        }
        playAll(Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f);

        // Las eliminaciones se reparten a lo largo de la fase de resultados.
        int gen = generation;
        int start = 40;
        int step = eliminated.isEmpty() ? 0
                : Math.max(1, Math.min(10, (evaluationTicks - start - 60) / eliminated.size()));
        int delay = start;
        for (GamePlayer gp : eliminated) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (gen == generation && players.get(gp.uuid()) == gp) {
                    eliminateEffects(gp);
                }
            }, delay);
            delay += step;
        }
        ticksLeft = phaseTicks = Math.max(evaluationTicks, delay + 40);
    }

    private void showResults(List<GamePlayer> ranking, List<GamePlayer> eliminated) {
        broadcastRaw("resultados.cabecera", ph("ronda", round), ph("construccion", currentBuild.name()));
        int shown = Math.min(RESULT_LINES, ranking.size());
        for (int i = 0; i < shown; i++) {
            GamePlayer gp = ranking.get(i);
            String key;
            if (gp.isFinished()) {
                key = "resultados.completado";
            } else if (eliminated.contains(gp)) {
                key = "resultados.eliminado";
            } else {
                key = "resultados.salvado";
            }
            broadcastRaw(key, ph("posicion", i + 1), ph("jugador", gp.name()),
                    ph("tiempo", formatSeconds(gp.finishMillis())), ph("porcentaje", formatPercent(gp.percent())),
                    ph("puntos", gp.points()));
        }
        if (ranking.size() > shown) {
            broadcastRaw("resultados.mas", ph("cantidad", ranking.size() - shown));
        }
        if (!eliminated.isEmpty()) {
            // De mejor a peor puesto: "Ana (#7), Luis (#8)..."
            List<GamePlayer> byPlace = new ArrayList<>(eliminated);
            byPlace.sort(Comparator.comparingInt(GamePlayer::place));
            String names = byPlace.stream()
                    .limit(ELIMINATED_NAMES)
                    .map(gp -> gp.name() + " (#" + gp.place() + ")")
                    .collect(Collectors.joining(", "));
            if (byPlace.size() > ELIMINATED_NAMES) {
                names += " +" + (byPlace.size() - ELIMINATED_NAMES);
            }
            broadcastRaw("resultados.eliminados", ph("lista", names), ph("cantidad", eliminated.size()));
        }
        broadcastRaw("resultados.pie", ph("vivos", ranking.size() - eliminated.size()),
                ph("eliminados", eliminated.size()));
    }

    private void eliminateEffects(GamePlayer gp) {
        gp.removeHologram();
        if (gp.platform() != null) {
            gp.platform().explode();
        }
        broadcast("eliminado", ph("jugador", gp.name()), ph("porcentaje", formatPercent(gp.percent())),
                ph("puesto", gp.place()));
        Player p = gp.player();
        if (p != null) {
            title(p, "titulo.eliminado", "titulo.eliminado-sub", ph("porcentaje", formatPercent(gp.percent())),
                    ph("puesto", gp.place()), ph("puntos", gp.points()));
            p.playSound(p.getLocation(), Sound.ENTITY_BLAZE_DEATH, 1f, 0.8f);
            makeSpectator(p);
        }
    }

    private void afterEvaluation() {
        List<GamePlayer> alive = alivePlayers();
        if (alive.isEmpty()) {
            finish(null);
        } else if (alive.size() == 1 && startingPlayers > 1) {
            finish(alive.get(0));
        } else if (round >= settings().maxRounds()) {
            GamePlayer best = qualified.stream().filter(GamePlayer::isAlive).findFirst()
                    .orElseGet(() -> alive.stream().max(Comparator.comparingDouble(GamePlayer::percent)).orElse(null));
            finish(best);
        } else {
            nextRound();
        }
    }

    private void finish(GamePlayer champion) {
        state = GameState.FINALIZADO;
        winner = champion;
        ticksLeft = phaseTicks = settings().endingSeconds() * 20;
        plugin.records().save();
        for (GamePlayer gp : players.values()) {
            gp.removeHologram();
            Player p = gp.player();
            if (p != null) {
                p.sendActionBar(Component.empty());
            }
        }

        // Puestos de quienes seguían en juego (solo si se llegó al máximo de rondas con varios vivos).
        List<GamePlayer> alive = alivePlayers();
        alive.remove(champion);
        alive.sort(Comparator.comparingDouble(GamePlayer::percent).reversed());
        int place = champion != null ? 2 : 1;
        for (GamePlayer gp : alive) {
            gp.setPlace(place++);
        }
        if (champion != null) {
            champion.setPlace(1);
            champion.addPoints(settings().winnerPoints());
        }

        if (champion == null) {
            broadcastRaw("sin-ganador");
            for (GamePlayer gp : players.values()) {
                Player p = gp.player();
                if (p != null) {
                    title(p, "titulo.sin-ganador", "titulo.sin-ganador-sub");
                }
            }
        } else {
            broadcastRaw("ganador", ph("jugador", champion.name()), ph("ronda", round),
                    ph("puntos", champion.points()));
            for (GamePlayer gp : players.values()) {
                Player p = gp.player();
                if (p == null) {
                    continue;
                }
                if (gp == champion) {
                    title(p, "titulo.victoria", "titulo.victoria-sub", ph("jugador", champion.name()));
                } else {
                    title(p, "titulo.ganador", "titulo.ganador-sub", ph("jugador", champion.name()));
                }
            }
            playAll(Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f);
        }
        showFinalRanking();
    }

    /** Top 5 final de la partida: puesto y puntos. */
    private void showFinalRanking() {
        List<GamePlayer> ranked = players.values().stream()
                .filter(gp -> gp.place() > 0)
                .sorted(Comparator.comparingInt(GamePlayer::place))
                .limit(TOP_SIZE)
                .toList();
        if (ranked.isEmpty()) {
            return;
        }
        broadcastRaw("final.cabecera");
        for (GamePlayer gp : ranked) {
            broadcastRaw("final.linea", ph("posicion", gp.place()), ph("jugador", gp.name()),
                    ph("puntos", gp.points()));
        }
        broadcastRaw("final.pie");
    }

    private void celebrateWinner() {
        if (winner == null) {
            return;
        }
        Player p = winner.player();
        if (p == null || !winner.isAlive()) {
            return;
        }
        Location location = p.getLocation().add(random.nextDouble() * 4 - 2, 1, random.nextDouble() * 4 - 2);
        Firework firework = location.getWorld().spawn(location, Firework.class);
        FireworkMeta meta = firework.getFireworkMeta();
        meta.addEffect(FireworkEffect.builder()
                .with(random.nextBoolean() ? FireworkEffect.Type.BALL_LARGE : FireworkEffect.Type.STAR)
                .withColor(Color.fromRGB(random.nextInt(0xFFFFFF)), Color.fromRGB(0xFFD84D))
                .withFade(Color.WHITE)
                .flicker(true)
                .trail(true)
                .build());
        meta.setPower(1);
        firework.setFireworkMeta(meta);
    }

    /**
     * Limpia la arena y devuelve a todos a su estado original.
     *
     * @param rejoin {@code true} para volver a meter a los conectados en el lobby
     *               (al terminar una partida); {@code false} al apagar el servidor
     */
    public void reset(boolean rejoin) {
        generation++;
        for (GamePlayer gp : new ArrayList<>(players.values())) {
            gp.removeHologram();
            Player p = gp.player();
            if (p != null) {
                restore(p, gp);
            }
        }
        for (UUID editor : editors) {
            Player p = Bukkit.getPlayer(editor);
            if (p != null) {
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
        }
        players.clear();
        sidebars.clear();
        qualified.clear();
        usedBuilds.clear();
        platforms.forEach(Platform::destroy);
        platforms.clear();
        state = GameState.ESPERANDO;
        round = 0;
        ticksLeft = 0;
        layoutCount = 0;
        currentBuild = null;
        winner = null;
        spectatorPoint = null;
        if (rejoin) {
            // Un tick después, para no volver a meter a quien se está desconectando.
            Bukkit.getScheduler().runTask(plugin, this::joinAll);
        }
    }

    // ------------------------------------------------------------------
    // Reglas de construcción y movimiento (usadas por el listener)
    // ------------------------------------------------------------------

    public boolean canModify(GamePlayer gp, Block block) {
        return state == GameState.CONSTRUYENDO
                && gp.isAlive()
                && !gp.isFinished()
                && gp.platform() != null
                && gp.platform().inZone(block);
    }

    /** ¿Pertenece el bloque a alguna plataforma? */
    public boolean isArenaBlock(Block block) {
        return platformAt(block.getLocation()) != null;
    }

    /**
     * Mantiene a los jugadores cerca de su plataforma y los rescata si caen al vacío.
     *
     * @return la ubicación a la que hay que devolverlo, o {@code null} si el movimiento es válido
     */
    public Location restrictMove(Player player, GamePlayer gp, Location from, Location to) {
        if (state.isRunning()) {
            Platform platform = gp.platform();
            if (!gp.isAlive() || platform == null) {
                return null;
            }
            if (to.getY() < platform.floorY() - 8) {
                player.setFallDistance(0f);
                return platform.spawn();
            }
            int margin = settings().leashDistance();
            if (!platform.withinLeash(to, margin)) {
                player.sendActionBar(messages().get("actionbar.limite"));
                if (platform.withinLeash(from, margin)) {
                    Location back = from.clone();
                    back.setYaw(to.getYaw());
                    back.setPitch(to.getPitch());
                    return back;
                }
                return platform.spawn();
            }
            return null;
        }
        Location lobby = arena().lobby();
        if (lobby != null && lobby.getWorld() != null && lobby.getWorld().equals(to.getWorld())
                && to.getY() < lobby.getY() - 30) {
            player.setFallDistance(0f);
            return lobby;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Paneles para operadores: /sb top y /sb progreso
    // ------------------------------------------------------------------

    /** Competidores ordenados por puntos (y luego por porcentaje de la ronda). */
    private List<GamePlayer> byPoints() {
        return players.values().stream()
                .filter(gp -> gp.platform() != null || gp.place() > 0)
                .sorted(Comparator.comparingInt(GamePlayer::points).reversed()
                        .thenComparing(GamePlayer::isAlive, Comparator.reverseOrder())
                        .thenComparing(Comparator.comparingDouble(GamePlayer::percent).reversed()))
                .toList();
    }

    public void sendTop(CommandSender sender) {
        Messages m = messages();
        if (!state.isRunning()) {
            m.send(sender, "no-hay-partida");
            return;
        }
        sender.sendMessage(m.get("tabla.cabecera", ph("ronda", round), ph("maximo", settings().maxRounds()),
                ph("vivos", aliveCount()),
                ph("construccion", currentBuild != null ? currentBuild.name() : "-")));
        List<GamePlayer> top = byPoints();
        for (int i = 0; i < Math.min(TOP_SIZE, top.size()); i++) {
            GamePlayer gp = top.get(i);
            sender.sendMessage(m.get("tabla.linea", ph("posicion", i + 1), ph("jugador", gp.name()),
                    ph("puntos", gp.points()), phComponent("estado", status(gp)),
                    phComponent("barra", progressBar(gp.percent())),
                    phComponent("porcentaje", Component.text(formatPercent(gp.percent()) + "%",
                            percentColor(gp.percent())))));
        }
        sender.sendMessage(m.get("tabla.pie"));
    }

    public void sendProgress(CommandSender sender) {
        Messages m = messages();
        if (!state.isRunning()) {
            m.send(sender, "no-hay-partida");
            return;
        }
        List<GamePlayer> alive = alivePlayers();
        alive.sort(Comparator.comparing(GamePlayer::isFinished).reversed()
                .thenComparing(Comparator.comparingDouble(GamePlayer::percent).reversed()));
        sender.sendMessage(m.get("progreso.cabecera", ph("vivos", alive.size()),
                ph("construccion", currentBuild != null ? currentBuild.name() : "-")));
        for (GamePlayer gp : alive) {
            sender.sendMessage(m.get("progreso.linea", ph("jugador", gp.name()), phComponent("estado", status(gp)),
                    phComponent("barra", progressBar(gp.percent())),
                    phComponent("porcentaje", Component.text(formatPercent(gp.percent()) + "%",
                            percentColor(gp.percent()))),
                    ph("puntos", gp.points())));
        }
    }

    private Component status(GamePlayer gp) {
        if (!gp.isAlive()) {
            return Component.text("✘", NamedTextColor.RED);
        }
        return gp.isFinished()
                ? Component.text("✔", NamedTextColor.GREEN)
                : Component.text("●", NamedTextColor.YELLOW);
    }

    // ------------------------------------------------------------------
    // Hologramas de progreso (solo visibles para operadores)
    // ------------------------------------------------------------------

    private void createHologram(GamePlayer gp) {
        gp.removeHologram();
        Location location = gp.platform().hologramLocation();
        TextDisplay display = location.getWorld().spawn(location, TextDisplay.class);
        display.setPersistent(false);
        display.setVisibleByDefault(false);
        display.setBillboard(Display.Billboard.CENTER);
        display.setShadowed(true);
        display.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
        display.text(hologramText(gp));
        gp.setHologram(display);
    }

    private Component hologramText(GamePlayer gp) {
        Messages m = messages();
        TagResolver[] resolvers = {
                ph("jugador", gp.name()),
                ph("puntos", gp.points()),
                ph("tiempo", formatSeconds(gp.finishMillis())),
                phComponent("barra", progressBar(gp.percent())),
                phComponent("porcentaje", Component.text(formatPercent(gp.percent()) + "%",
                        percentColor(gp.percent())))
        };
        return m.get(gp.isFinished() ? "holograma.completado" : "holograma.progreso", resolvers);
    }

    private void updateHolograms() {
        if (!state.isRunning()) {
            return;
        }
        List<Player> admins = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission(PERM_ADMIN)) {
                admins.add(online);
            }
        }
        for (GamePlayer gp : players.values()) {
            TextDisplay display = gp.hologram();
            if (display == null || !display.isValid()) {
                continue;
            }
            display.text(hologramText(gp));
            for (Player admin : admins) {
                if (!admin.canSee(display)) {
                    admin.showEntity(plugin, display);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Utilidades de jugador
    // ------------------------------------------------------------------

    private List<GamePlayer> alivePlayers() {
        List<GamePlayer> alive = new ArrayList<>();
        for (GamePlayer gp : players.values()) {
            if (gp.isAlive()) {
                alive.add(gp);
            }
        }
        return alive;
    }

    private int aliveCount() {
        int count = 0;
        for (GamePlayer gp : players.values()) {
            if (gp.isAlive()) {
                count++;
            }
        }
        return count;
    }

    private void heal(Player player) {
        player.getInventory().clear();
        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setLevel(0);
        player.setExp(0f);
        player.setFireTicks(0);
        player.setFallDistance(0f);
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
    }

    private void prepareLobby(Player player) {
        heal(player);
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(false);
        player.setFlying(false);
        Location lobby = arena().lobby();
        if (lobby != null) {
            player.teleport(lobby);
        }
    }

    private void prepareBuilder(Player player) {
        heal(player);
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(true);
        player.setFlying(false);
    }

    private void makeSpectator(Player player) {
        player.getInventory().clear();
        player.setGameMode(GameMode.SPECTATOR);
        Location point = spectatorPoint != null ? spectatorPoint : arena().center().add(0.5, 14, 0.5);
        player.teleport(point);
    }

    private void restore(Player player, GamePlayer gp) {
        player.hideBossBar(bossBar);
        sidebars.remove(player.getUniqueId());
        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        Location lobby = arena().lobby();
        gp.snapshot().restore(player, lobby == null);
        if (lobby != null) {
            player.teleport(lobby);
        }
    }

    private void giveMaterials(Player player, Build build) {
        for (Map.Entry<Material, Integer> entry : build.materials().entrySet()) {
            int remaining = entry.getValue();
            int stack = Math.max(1, entry.getKey().getMaxStackSize());
            while (remaining > 0) {
                int amount = Math.min(stack, remaining);
                player.getInventory().addItem(new ItemStack(entry.getKey(), amount));
                remaining -= amount;
            }
        }
    }

    // ------------------------------------------------------------------
    // Interfaz: mensajes, títulos, sonidos, bossbar y scoreboard
    // ------------------------------------------------------------------

    private void broadcast(String key, TagResolver... resolvers) {
        Component message = messages().prefixed(key, resolvers);
        for (GamePlayer gp : players.values()) {
            Player p = gp.player();
            if (p != null) {
                p.sendMessage(message);
            }
        }
    }

    private void broadcastRaw(String key, TagResolver... resolvers) {
        Component message = messages().get(key, resolvers);
        for (GamePlayer gp : players.values()) {
            Player p = gp.player();
            if (p != null) {
                p.sendMessage(message);
            }
        }
    }

    private void actionBarAlive(Component component) {
        for (GamePlayer gp : players.values()) {
            Player p = gp.player();
            if (p != null && gp.isAlive()) {
                p.sendActionBar(component);
            }
        }
    }

    private void playAll(Sound sound, float pitch) {
        for (GamePlayer gp : players.values()) {
            Player p = gp.player();
            if (p != null) {
                p.playSound(p.getLocation(), sound, 1f, pitch);
            }
        }
    }

    private void title(Player player, String titleKey, String subtitleKey, TagResolver... resolvers) {
        player.showTitle(Title.title(messages().get(titleKey, resolvers),
                messages().get(subtitleKey, resolvers), TIMES));
    }

    private Component progressBar(double percent) {
        int filled = (int) Math.round(Math.max(0, Math.min(100, percent)) / 100.0 * BAR_LENGTH);
        return Component.text(BAR_CHAR.repeat(filled), percentColor(percent))
                .append(Component.text(BAR_CHAR.repeat(BAR_LENGTH - filled), NamedTextColor.DARK_GRAY));
    }

    private static TextColor percentColor(double percent) {
        if (percent >= 100) {
            return NamedTextColor.GREEN;
        }
        if (percent >= 75) {
            return NamedTextColor.YELLOW;
        }
        return percent >= 40 ? NamedTextColor.GOLD : NamedTextColor.RED;
    }

    private static String formatPercent(double percent) {
        return String.valueOf((int) Math.floor(percent));
    }

    private static String formatSeconds(long millis) {
        return String.format(Locale.ROOT, "%.1f", millis / 1000.0);
    }

    private void updateBossBar() {
        Messages m = messages();
        Settings s = settings();
        Component name;
        float progress = phaseTicks > 0 ? (float) ticksLeft / phaseTicks : 1f;
        BossBar.Color color;
        switch (state) {
            case ESPERANDO -> {
                name = m.get("bossbar.esperando", ph("actual", aliveCount()), ph("maximo", s.maxPlayers()));
                progress = 1f;
                color = BossBar.Color.YELLOW;
            }
            case MEMORIZANDO -> {
                name = m.get("bossbar.memorizando", ph("construccion", currentBuild.name()),
                        ph("segundos", seconds()));
                color = BossBar.Color.YELLOW;
            }
            case CONSTRUYENDO -> {
                name = m.get("bossbar.construyendo", ph("construccion", currentBuild.name()),
                        ph("segundos", seconds()), ph("clasificados", qualified.size()), ph("cupo", quota));
                color = ticksLeft <= 200 ? BossBar.Color.RED : BossBar.Color.GREEN;
            }
            case EVALUANDO -> {
                name = m.get("bossbar.evaluando", ph("segundos", seconds()));
                color = BossBar.Color.PURPLE;
            }
            default -> {
                name = winner != null
                        ? m.get("bossbar.ganador", ph("jugador", winner.name()))
                        : m.get("bossbar.sin-ganador");
                progress = 1f;
                color = BossBar.Color.PINK;
            }
        }
        bossBar.name(name);
        bossBar.progress(Math.max(0f, Math.min(1f, progress)));
        bossBar.color(color);
    }

    private void updateSidebars() {
        for (GamePlayer gp : players.values()) {
            Sidebar sidebar = sidebars.get(gp.uuid());
            Player p = gp.player();
            if (sidebar != null && p != null) {
                sidebar.update(sidebarLines(p, gp));
            }
        }
        // Los editores (operadores fuera de la arena) también ven el panel durante la partida.
        if (!state.isRunning()) {
            return;
        }
        for (UUID editor : editors) {
            Player p = Bukkit.getPlayer(editor);
            if (p == null) {
                continue;
            }
            Sidebar sidebar = sidebars.computeIfAbsent(editor, id -> new Sidebar(messages().get("scoreboard.titulo")));
            if (p.getScoreboard() != sidebar.scoreboard()) {
                p.setScoreboard(sidebar.scoreboard());
            }
            sidebar.update(sidebarLines(p, null));
        }
    }

    private List<Component> sidebarLines(Player viewer, GamePlayer gp) {
        Messages m = messages();
        Settings s = settings();
        List<String> raw = new ArrayList<>();
        if (!state.isRunning()) {
            raw.add("<dark_gray>" + LocalDate.now().format(DATE) + " <gray>• <dark_gray>Speed Builders");
            raw.add("");
            raw.add("<gray>Jugadores: <white>" + aliveCount() + "<dark_gray>/<gray>" + s.maxPlayers());
            raw.add("");
            raw.add("<yellow>Esperando inicio...");
            if (gp != null && !gp.isAlive()) {
                raw.add("<red>Partida llena: espectador");
            }
        } else if (viewer.hasPermission(PERM_ADMIN)) {
            // Panel de operador: top 5 por puntos con el progreso de la ronda.
            raw.add("");
            raw.add("<gray>Ronda <white>" + round + "<dark_gray>/<gray>" + s.maxRounds()
                    + " <dark_gray>• <gray>Vivos <green>" + aliveCount());
            if (currentBuild != null) {
                raw.add("<aqua>" + m.escape(currentBuild.name()) + " <dark_gray>• " + state.label());
            }
            raw.add("");
            raw.add("<gold><bold>★ TOP 5 PUNTOS");
            List<GamePlayer> top = byPoints();
            for (int i = 0; i < TOP_SIZE; i++) {
                if (i >= top.size()) {
                    raw.add("<dark_gray>" + (i + 1) + ". -");
                    continue;
                }
                GamePlayer entry = top.get(i);
                String mark = !entry.isAlive() ? "<red>✘" : entry.isFinished() ? "<green>✔" : "<yellow>●";
                raw.add(mark + " <white>" + m.escape(entry.name()) + " <gold>" + entry.points()
                        + "pts <dark_gray>" + formatPercent(entry.percent()) + "%");
            }
        } else {
            raw.add("<dark_gray>" + LocalDate.now().format(DATE) + " <gray>• <dark_gray>Speed Builders");
            raw.add("");
            raw.add("<gray>Ronda: <white>" + round + "<dark_gray>/<gray>" + s.maxRounds());
            raw.add("<gray>Vivos: <green>" + aliveCount());
            if (gp != null) {
                raw.add("<gray>Tus puntos: <gold>" + gp.points());
            }
            raw.add("");
            if (currentBuild != null) {
                raw.add("<gray>Construcción:");
                raw.add(" <aqua>" + m.escape(currentBuild.name()));
                raw.add("<gray>Dificultad: " + currentBuild.difficulty().formatted());
            }
            raw.add("<gray>Estado: " + state.label());
            if (state == GameState.CONSTRUYENDO) {
                raw.add("<gray>Clasificados: <green>" + qualified.size() + "<dark_gray>/<gray>" + quota);
                if (gp != null && gp.isAlive()) {
                    raw.add("<gray>Tu similitud: <yellow>" + formatPercent(gp.percent()) + "%");
                }
            }
            if (gp != null && !gp.isAlive()) {
                raw.add(gp.place() > 0 ? "<red>✘ Eliminado <gray>(puesto #" + gp.place() + ")" : "<red>✘ Espectador");
            }
        }
        raw.add("");
        List<Component> lines = new ArrayList<>(raw.size() + 1);
        for (String line : raw) {
            lines.add(m.parse(line));
        }
        lines.add(m.get("scoreboard.pie"));
        return lines;
    }
}
