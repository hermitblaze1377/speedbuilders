package com.hermitblaze.speedbuilders.game;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.Collection;

/** Estado del jugador antes de entrar, para devolvérselo intacto al salir. */
public final class PlayerSnapshot {

    private final ItemStack[] contents;
    private final Location location;
    private final GameMode gameMode;
    private final double health;
    private final int food;
    private final float saturation;
    private final int level;
    private final float exp;
    private final boolean allowFlight;
    private final boolean flying;
    private final Collection<PotionEffect> effects;

    private PlayerSnapshot(Player player) {
        this.contents = cloneItems(player.getInventory().getContents());
        this.location = player.getLocation().clone();
        this.gameMode = player.getGameMode();
        this.health = player.getHealth();
        this.food = player.getFoodLevel();
        this.saturation = player.getSaturation();
        this.level = player.getLevel();
        this.exp = player.getExp();
        this.allowFlight = player.getAllowFlight();
        this.flying = player.isFlying();
        this.effects = new ArrayList<>(player.getActivePotionEffects());
    }

    public static PlayerSnapshot capture(Player player) {
        return new PlayerSnapshot(player);
    }

    public Location location() {
        return location.clone();
    }

    public void restore(Player player, boolean teleport) {
        player.getInventory().setContents(cloneItems(contents));
        player.setGameMode(gameMode);
        AttributeInstance maxHealth = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        double max = maxHealth != null ? maxHealth.getValue() : 20.0;
        player.setHealth(Math.max(0.5, Math.min(health, max)));
        player.setFoodLevel(food);
        player.setSaturation(saturation);
        player.setLevel(level);
        player.setExp(exp);
        player.setAllowFlight(allowFlight);
        player.setFlying(allowFlight && flying);
        player.setFireTicks(0);
        player.setFallDistance(0f);
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        player.addPotionEffects(effects);
        if (teleport) {
            player.teleport(location);
        }
    }

    private static ItemStack[] cloneItems(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            copy[i] = items[i] == null ? null : items[i].clone();
        }
        return copy;
    }
}
