package com.hermitblaze.speedbuilders.game;

import com.hermitblaze.speedbuilders.SpeedBuildersPlugin;
import com.hermitblaze.speedbuilders.arena.Arena;
import com.hermitblaze.speedbuilders.arena.Platform;
import com.hermitblaze.speedbuilders.arena.PlatformLayout;
import com.hermitblaze.speedbuilders.arena.Similarity;
import com.hermitblaze.speedbuilders.build.Build;
import com.hermitblaze.speedbuilders.build.Difficulty;
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
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
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

import static com.hermitblaze.speedbuilders.config.Messages.ph;
import static com.hermitblaze.speedbuilders.config.Messages.phComponent;
import static com.hermitblaze.speedbuilders.config.Messages.phParsed;

/**
 * Flujo de una partida:
 * ESPERANDO → INICIANDO (cuenta atrás) → [MEMORIZANDO → CONSTRUYENDO → EVALUANDO] x rondas → FINALIZADO.
 */
public final class Game {

    private static final Title.Times TIMES = Title.Times.times(
            Duration.ofMillis(200), Duration.ofMillis(2200), Duration.ofMillis(500));
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yy");
    private static final String BAR_CHAR = "▌";
    private static final int BAR_LENGTH = 20;

    private final SpeedBuildersPlugin plugin;
    private final Map<UUID, GamePlayer> players = new LinkedHashMap<>();
    private final Map<UUID, Sidebar> sidebars = new HashMap<>();
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
    private int round;
    private int ticksLeft;
    private int phaseTicks;
    private int toEliminate;
    private int quota;
    private int startingPlayers;
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
        reset();
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

    /** ¿Hay jugadores o una partida en marcha? */
    public boolean isActive() {
        return !players.isEmpty() || state != GameState.ESPERANDO;
    }

    public GamePlayer player(Player player) {
        return players.get(player.getUniqueId());
    }

    // ------------------------------------------------------------------
    // Entrar / salir
    // ------------------------------------------------------------------

    public void join(Player player) {
        Messages m = messages();
        if (players.containsKey(player.getUniqueId())) {
            m.send(player, "ya-en-partida");
            return;
        }
        if (!arena().isReady()) {
            m.send(player, "arena-no-lista");
            return;
        }
        if (state == GameState.FINALIZADO) {
            m.send(player, "partida-terminando");
            return;
        }
        boolean running = state.isRunning();
        if (!running && players.size() >= settings().maxPlayers()) {
            m.send(player, "partida-llena");
            return;
        }

        GamePlayer gp = new GamePlayer(player, PlayerSnapshot.capture(player));
        players.put(player.getUniqueId(), gp);
        Sidebar sidebar = new Sidebar(m.get("scoreboard.titulo"));
        sidebars.put(player.getUniqueId(), sidebar);
        player.setScoreboard(sidebar.scoreboard());
        player.showBossBar(bossBar);

        if (running) {
            gp.setAlive(false);
            makeSpectator(player);
            m.send(player, "unido-espectador");
            updateSidebars();
            return;
        }

        gp.setAlive(true);
        prepareLobby(player);
        broadcast("unido", ph("jugador", player.getName()), ph("actual", players.size()),
                ph("maximo", settings().maxPlayers()));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);

        if (state == GameState.ESPERANDO && players.size() >= settings().minPlayers()) {
            startCountdown();
        } else if (state == GameState.INICIANDO && players.size() >= settings().maxPlayers() && ticksLeft > 200) {
            ticksLeft = 200;
            broadcast("partida-completa");
        }
        updateSidebars();
    }

    public void leave(Player player, boolean quit) {
        GamePlayer gp = players.remove(player.getUniqueId());
        if (gp == null) {
            if (!quit) {
                messages().send(player, "no-en-partida");
            }
            return;
        }
        boolean wasAlive = gp.isAlive();
        restore(player, gp);
        if (!quit) {
            messages().send(player, "has-salido");
        }

        if (!state.isRunning()) {
            broadcast("salio", ph("jugador", gp.name()), ph("actual", players.size()),
                    ph("maximo", settings().maxPlayers()));
            if (state == GameState.INICIANDO && players.size() < settings().minPlayers()) {
                state = GameState.ESPERANDO;
                broadcast("cuenta-cancelada");
            }
            return;
        }
        if (players.isEmpty()) {
            reset();
            return;
        }
        if (!wasAlive || state == GameState.FINALIZADO) {
            return;
        }
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

    // ------------------------------------------------------------------
    // Control de administrador
    // ------------------------------------------------------------------

    /** Inicia ya, sin esperar al mínimo de jugadores (útil para probar). */
    public boolean forceStart() {
        if (state.isRunning() || players.isEmpty()) {
            return false;
        }
        beginGame();
        return true;
    }

    public boolean stop() {
        if (players.isEmpty() && platforms.isEmpty() && state == GameState.ESPERANDO) {
            return false;
        }
        broadcast("partida-detenida");
        reset();
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
        for (Location location : PlatformLayout.compute(center, count, s.islandSize(), s.gap(), s.minRadius())) {
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
            case INICIANDO -> {
                ticksLeft--;
                if (ticksLeft <= 0) {
                    beginGame();
                } else if (ticksLeft % 20 == 0) {
                    countdownSecond(ticksLeft / 20);
                }
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
                        actionBarAll(messages().get("actionbar.memorizando", ph("segundos", seconds())));
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
                    reset();
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
        }
    }

    private int seconds() {
        return Math.max(0, (ticksLeft + 19) / 20);
    }

    // ------------------------------------------------------------------
    // Fases
    // ------------------------------------------------------------------

    private void startCountdown() {
        state = GameState.INICIANDO;
        ticksLeft = phaseTicks = settings().countdownSeconds() * 20;
        broadcast("cuenta-regresiva", ph("segundos", settings().countdownSeconds()));
        playAll(Sound.BLOCK_NOTE_BLOCK_PLING, 1f);
    }

    private void countdownSecond(int secondsLeft) {
        if (secondsLeft == 30 || secondsLeft == 20 || secondsLeft == 10 || secondsLeft <= 5) {
            broadcast("cuenta-regresiva", ph("segundos", secondsLeft));
        }
        if (secondsLeft <= 5) {
            for (GamePlayer gp : players.values()) {
                Player p = gp.player();
                if (p != null) {
                    title(p, "titulo.cuenta", "titulo.cuenta-sub", ph("segundos", secondsLeft));
                }
            }
            playAll(Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f + (5 - secondsLeft) * 0.15f);
        }
    }

    private void beginGame() {
        clearPreview();
        Settings s = settings();
        List<GamePlayer> list = new ArrayList<>(players.values());
        Collections.shuffle(list, random);
        List<Location> centers = PlatformLayout.compute(arena().center(), list.size(),
                s.islandSize(), s.gap(), s.minRadius());

        for (int i = 0; i < list.size(); i++) {
            GamePlayer gp = list.get(i);
            Platform platform = new Platform(centers.get(i), s);
            platform.build(s);
            platforms.add(platform);
            gp.setPlatform(platform);
            gp.setAlive(true);
            Player p = gp.player();
            if (p != null) {
                p.teleport(platform.spawn());
                prepareBuilder(p);
            }
        }
        startingPlayers = list.size();
        round = 0;
        usedBuilds.clear();
        broadcast("partida-iniciada", ph("rondas", s.maxRounds()));
        playAll(Sound.BLOCK_BEACON_ACTIVATE, 1f);
        nextRound();
    }

    private void nextRound() {
        Settings s = settings();
        round++;
        qualified.clear();
        currentBuild = plugin.builds().pick(difficultyFor(round), usedBuilds, random);
        if (currentBuild == null) {
            broadcast("sin-construcciones");
            reset();
            return;
        }
        usedBuilds.add(currentBuild.id());
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
        for (GamePlayer gp : players.values()) {
            Player p = gp.player();
            if (p != null) {
                title(p, "titulo.ronda", "titulo.ronda-sub", resolvers);
            }
        }
        playAll(Sound.BLOCK_NOTE_BLOCK_BELL, 1.2f);
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

    private void evaluate(GamePlayer gp) {
        Similarity similarity = gp.platform().compare(currentBuild);
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

        gp.platform().celebrate();
        Player p = gp.player();
        if (p != null) {
            p.getInventory().clear();
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
            title(p, "titulo.perfecto", "titulo.perfecto-sub", ph("tiempo", time), ph("posicion", position));
        }
        broadcast("completado", ph("jugador", gp.name()), ph("tiempo", time), ph("posicion", position),
                ph("clasificados", quota));
        playAll(Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.6f);

        boolean allDone = alivePlayers().stream().allMatch(GamePlayer::isFinished);
        if (qualified.size() >= quota || allDone) {
            endRound();
        }
    }

    private void endRound() {
        state = GameState.EVALUANDO;
        ticksLeft = phaseTicks = settings().evaluationSeconds() * 20;

        List<GamePlayer> alive = alivePlayers();
        for (GamePlayer gp : alive) {
            if (!gp.isFinished()) {
                gp.setPercent(gp.platform().compare(currentBuild).percent());
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

        // Eliminaciones escalonadas para darles dramatismo.
        int gen = generation;
        int delay = 30;
        for (GamePlayer gp : eliminated) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (gen == generation && players.get(gp.uuid()) == gp) {
                    eliminateEffects(gp);
                }
            }, delay);
            delay += 8;
        }
        ticksLeft = phaseTicks = Math.max(ticksLeft, delay + 20);
    }

    private void showResults(List<GamePlayer> ranking, List<GamePlayer> eliminated) {
        broadcastRaw("resultados.cabecera", ph("ronda", round), ph("construccion", currentBuild.name()));
        int position = 1;
        for (GamePlayer gp : ranking) {
            String key;
            if (gp.isFinished()) {
                key = "resultados.completado";
            } else if (eliminated.contains(gp)) {
                key = "resultados.eliminado";
            } else {
                key = "resultados.salvado";
            }
            broadcastRaw(key, ph("posicion", position++), ph("jugador", gp.name()),
                    ph("tiempo", formatSeconds(gp.finishMillis())), ph("porcentaje", formatPercent(gp.percent())));
        }
        broadcastRaw("resultados.pie", ph("vivos", ranking.size() - eliminated.size()));
    }

    private void eliminateEffects(GamePlayer gp) {
        if (gp.platform() != null) {
            gp.platform().explode();
        }
        broadcast("eliminado", ph("jugador", gp.name()), ph("porcentaje", formatPercent(gp.percent())));
        Player p = gp.player();
        if (p != null) {
            title(p, "titulo.eliminado", "titulo.eliminado-sub", ph("porcentaje", formatPercent(gp.percent())));
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
        actionBarAll(Component.empty());

        if (champion == null) {
            broadcastRaw("sin-ganador");
            for (GamePlayer gp : players.values()) {
                Player p = gp.player();
                if (p != null) {
                    title(p, "titulo.sin-ganador", "titulo.sin-ganador-sub");
                }
            }
            return;
        }

        broadcastRaw("ganador", ph("jugador", champion.name()), ph("ronda", round));
        if (settings().announceWinner()) {
            Component global = messages().prefixed("ganador-global", ph("jugador", champion.name()));
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!players.containsKey(online.getUniqueId())) {
                    online.sendMessage(global);
                }
            }
        }
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

    /** Devuelve a todos a su estado original y limpia la arena. */
    public void reset() {
        generation++;
        for (GamePlayer gp : new ArrayList<>(players.values())) {
            Player p = gp.player();
            if (p != null) {
                restore(p, gp);
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
        currentBuild = null;
        winner = null;
    }

    // ------------------------------------------------------------------
    // Reglas de construcción (usadas por el listener)
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
        Location location = block.getLocation();
        return platformAt(location) != null;
    }

    /** Si el jugador cae al vacío, vuelve a su plataforma o al lobby. */
    public void handleFall(Player player, GamePlayer gp, Location to) {
        if (state.isRunning()) {
            if (gp.isAlive() && gp.platform() != null && to.getY() < gp.platform().floorY() - 8) {
                player.teleport(gp.platform().spawn());
                player.setFallDistance(0f);
            }
            return;
        }
        Location lobby = arena().lobby();
        if (lobby != null && lobby.getWorld() != null && lobby.getWorld().equals(to.getWorld())
                && to.getY() < lobby.getY() - 30) {
            player.teleport(lobby);
            player.setFallDistance(0f);
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
        player.teleport(arena().spectatorPoint());
    }

    private void restore(Player player, GamePlayer gp) {
        player.hideBossBar(bossBar);
        sidebars.remove(player.getUniqueId());
        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        Location lobby = arena().lobby();
        boolean toLobby = settings().returnToLobby() && lobby != null;
        gp.snapshot().restore(player, !toLobby);
        if (toLobby) {
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

    private void actionBarAll(Component component) {
        for (GamePlayer gp : players.values()) {
            Player p = gp.player();
            if (p != null) {
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
                name = m.get("bossbar.esperando", ph("actual", players.size()),
                        ph("minimo", s.minPlayers()), ph("maximo", s.maxPlayers()));
                progress = (float) players.size() / s.minPlayers();
                color = BossBar.Color.YELLOW;
            }
            case INICIANDO -> {
                name = m.get("bossbar.iniciando", ph("segundos", seconds()));
                color = BossBar.Color.GREEN;
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
                name = m.get("bossbar.evaluando");
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
            if (sidebar != null) {
                sidebar.update(sidebarLines(gp));
            }
        }
    }

    private List<Component> sidebarLines(GamePlayer gp) {
        Messages m = messages();
        Settings s = settings();
        List<String> raw = new ArrayList<>();
        raw.add("<dark_gray>" + LocalDate.now().format(DATE) + " <gray>• <dark_gray>Speed Builders");
        raw.add("");
        if (!state.isRunning()) {
            raw.add("<gray>Jugadores: <white>" + players.size() + "<dark_gray>/<gray>" + s.maxPlayers());
            raw.add("<gray>Mínimo: <white>" + s.minPlayers());
            raw.add("");
            raw.add(state == GameState.INICIANDO
                    ? "<gray>Inicia en: <green>" + seconds() + "s"
                    : "<yellow>Esperando jugadores...");
        } else {
            raw.add("<gray>Ronda: <white>" + round + "<dark_gray>/<gray>" + s.maxRounds());
            raw.add("<gray>Vivos: <green>" + aliveCount());
            raw.add("");
            if (currentBuild != null) {
                raw.add("<gray>Construcción:");
                raw.add(" <aqua>" + m.escape(currentBuild.name()));
                raw.add("<gray>Dificultad: " + currentBuild.difficulty().formatted());
                raw.add("");
            }
            raw.add("<gray>Estado: " + state.label());
            if (state == GameState.CONSTRUYENDO) {
                raw.add("<gray>Clasificados: <green>" + qualified.size() + "<dark_gray>/<gray>" + quota);
                if (gp.isAlive()) {
                    raw.add("<gray>Tu similitud: <yellow>" + formatPercent(gp.percent()) + "%");
                }
            }
            if (!gp.isAlive()) {
                raw.add("<red>✘ Espectador");
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
