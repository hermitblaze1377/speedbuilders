package com.hermitblaze.speedbuilders.listener;

import com.hermitblaze.speedbuilders.SpeedBuildersPlugin;
import com.hermitblaze.speedbuilders.game.Game;
import com.hermitblaze.speedbuilders.game.GamePlayer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Slab;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

public final class GameListener implements Listener {

    private final SpeedBuildersPlugin plugin;

    public GameListener(SpeedBuildersPlugin plugin) {
        this.plugin = plugin;
    }

    private Game game() {
        return plugin.game();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        GamePlayer gp = game().player(event.getPlayer());
        if (gp == null) {
            return;
        }
        if (!game().canModify(gp, event.getBlockPlaced())) {
            event.setCancelled(true);
            return;
        }
        game().scheduleCheck(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        GamePlayer gp = game().player(player);
        if (gp == null) {
            return;
        }
        Block block = event.getBlock();
        if (!game().canModify(gp, block)) {
            event.setCancelled(true);
            return;
        }
        // El bloque vuelve al inventario en lugar de soltarse al suelo.
        event.setDropItems(false);
        event.setExpToDrop(0);
        BlockData data = block.getBlockData();
        Material item = data.getPlacementMaterial();
        if (!item.isAir() && item.isItem()) {
            int amount = data instanceof Slab slab && slab.getType() == Slab.Type.DOUBLE ? 2 : 1;
            player.getInventory().addItem(new ItemStack(item, amount));
        }
        game().scheduleCheck(player);
    }

    /** Romper bloques de la propia zona es instantáneo, como en el Speed Builders original. */
    @EventHandler(ignoreCancelled = true)
    public void onDamage(BlockDamageEvent event) {
        GamePlayer gp = game().player(event.getPlayer());
        if (gp != null && game().canModify(gp, event.getBlock())) {
            event.setInstaBreak(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && game().player(player) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && game().player(player) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (game().player(event.getPlayer()) != null) {
            event.setCancelled(true);
        }
    }

    /** Impide alejarse de la plataforma y rescata a quien cae al vacío. */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        GamePlayer gp = game().player(event.getPlayer());
        if (gp == null) {
            return;
        }
        Location corrected = game().restrictMove(event.getPlayer(), gp, from, to);
        if (corrected != null) {
            event.setTo(corrected);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        if (game().isArenaBlock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /** Todos entran a la arena al conectarse (con un pequeño retraso para que carguen). */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                game().join(player);
            }
        }, 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        game().leave(event.getPlayer());
    }
}
