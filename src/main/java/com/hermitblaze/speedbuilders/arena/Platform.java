package com.hermitblaze.speedbuilders.arena;

import com.hermitblaze.speedbuilders.build.Build;
import com.hermitblaze.speedbuilders.config.Settings;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * Isla de un jugador: una zona de construcción de 5x5 rodeada por un borde decorativo.
 * <pre>
 *   E B B B B B E    E = esquina, B = borde
 *   B A A A A A B    A = anillo
 *   B A Z Z Z A B    Z = zona de construcción 5x5
 * </pre>
 */
public final class Platform {

    /** Media zona: la zona de construcción va de -2 a +2 respecto al centro. */
    public static final int HALF = Build.SIZE / 2;

    private final World world;
    private final int cx;
    private final int floorY;
    private final int cz;
    private final int border;
    private final int height;

    public Platform(Location center, Settings settings) {
        this.world = center.getWorld();
        this.cx = center.getBlockX();
        this.floorY = center.getBlockY();
        this.cz = center.getBlockZ();
        this.border = settings.border();
        this.height = settings.zoneHeight();
    }

    public int radius() {
        return HALF + border;
    }

    public int floorY() {
        return floorY;
    }

    /** Genera la isla y deja libre el espacio de encima. */
    public void build(Settings settings) {
        int r = radius();
        Material base = settings.baseMaterial();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int ring = Math.max(Math.abs(dx), Math.abs(dz));
                Material top;
                if (ring <= HALF) {
                    top = settings.zoneMaterial();
                } else if (ring == r) {
                    top = Math.abs(dx) == r && Math.abs(dz) == r ? settings.cornerMaterial() : settings.edgeMaterial();
                } else {
                    top = settings.ringMaterial();
                }
                world.getBlockAt(cx + dx, floorY, cz + dz).setType(top, false);
                if (!base.isAir()) {
                    world.getBlockAt(cx + dx, floorY - 1, cz + dz).setType(base, false);
                }
                for (int y = 1; y <= height + 2; y++) {
                    world.getBlockAt(cx + dx, floorY + y, cz + dz).setType(Material.AIR, false);
                }
            }
        }
    }

    /** Elimina la isla por completo. */
    public void destroy() {
        int r = radius();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int y = -1; y <= height + 2; y++) {
                    world.getBlockAt(cx + dx, floorY + y, cz + dz).setType(Material.AIR, false);
                }
            }
        }
    }

    /** Destrucción con efectos, usada al eliminar a un jugador. */
    public void explode() {
        Location center = center();
        world.spawnParticle(Particle.EXPLOSION_LARGE, center, 8, 2.5, 0.8, 2.5, 0);
        world.spawnParticle(Particle.CLOUD, center, 80, 3, 0.6, 3, 0.05);
        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 0.9f);
        destroy();
    }

    /** Efecto cuando la construcción de muestra desaparece. */
    public void vanishEffect() {
        Location zone = center().add(0, height / 2.0, 0);
        world.spawnParticle(Particle.CLOUD, zone, 50, 1.4, height / 3.0, 1.4, 0.02);
        world.spawnParticle(Particle.END_ROD, zone, 20, 1.6, height / 3.0, 1.6, 0.01);
        world.playSound(zone, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1f, 1.2f);
    }

    /** Efecto cuando el jugador completa la construcción. */
    public void celebrate() {
        Location zone = center().add(0, 1.5, 0);
        world.spawnParticle(Particle.VILLAGER_HAPPY, zone, 40, 1.8, 1.5, 1.8, 0);
        world.spawnParticle(Particle.TOTEM, zone, 60, 1, 1, 1, 0.3);
    }

    /** Centro de la zona, a la altura de los pies. */
    public Location center() {
        return new Location(world, cx + 0.5, floorY + 1, cz + 0.5);
    }

    /** Punto de aparición: en el lado sur, mirando hacia la zona. */
    public Location spawn() {
        return new Location(world, cx + 0.5, floorY + 1, cz + HALF + 1.5, 180f, 30f);
    }

    public boolean contains(Location location) {
        if (location.getWorld() == null || !location.getWorld().equals(world)) {
            return false;
        }
        int r = radius();
        return Math.abs(location.getBlockX() - cx) <= r
                && Math.abs(location.getBlockZ() - cz) <= r
                && location.getBlockY() >= floorY - 1
                && location.getBlockY() <= floorY + height + 3;
    }

    /**
     * ¿La ubicación está dentro del área permitida alrededor de la isla?
     * Se permite alejarse {@code margin} bloques del borde y subir un poco por encima de la zona.
     */
    public boolean withinLeash(Location location, int margin) {
        if (location.getWorld() == null || !location.getWorld().equals(world)) {
            return false;
        }
        double limit = radius() + margin + 0.5;
        return Math.abs(location.getX() - (cx + 0.5)) <= limit
                && Math.abs(location.getZ() - (cz + 0.5)) <= limit
                && location.getY() <= floorY + height + 5;
    }

    /** Punto sobre la zona donde se muestra el holograma de progreso. */
    public Location hologramLocation() {
        return new Location(world, cx + 0.5, floorY + height + 2.2, cz + 0.5);
    }

    /** ¿El bloque está dentro de la zona de construcción de 5x5? */
    public boolean inZone(Block block) {
        if (!block.getWorld().equals(world)) {
            return false;
        }
        int dy = block.getY() - floorY;
        return Math.abs(block.getX() - cx) <= HALF
                && Math.abs(block.getZ() - cz) <= HALF
                && dy >= 1 && dy <= height;
    }

    private Block zoneBlock(int x, int y, int z) {
        return world.getBlockAt(cx - HALF + x, floorY + 1 + y, cz - HALF + z);
    }

    public void clearZone() {
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < Build.SIZE; z++) {
                for (int x = 0; x < Build.SIZE; x++) {
                    zoneBlock(x, y, z).setType(Material.AIR, false);
                }
            }
        }
    }

    public void paste(Build build) {
        clearZone();
        int layers = Math.min(build.height(), height);
        for (int y = 0; y < layers; y++) {
            for (int z = 0; z < Build.SIZE; z++) {
                for (int x = 0; x < Build.SIZE; x++) {
                    BlockData data = build.blockAt(x, y, z);
                    if (data != null) {
                        zoneBlock(x, y, z).setBlockData(data, false);
                    }
                }
            }
        }
    }

    /**
     * Compara la zona con la construcción. Cuenta cada posición donde hay algo
     * (en el objetivo o en la réplica). Bloque sobrante o faltante = 0 puntos.
     * <ul>
     *   <li>{@code strict = false}: basta con que sea el mismo bloque (1 punto); la
     *       orientación y demás estados (escaleras, troncos, conexiones...) se ignoran,
     *       porque dependen de cómo se colocan y podían impedir llegar al 100 %.</li>
     *   <li>{@code strict = true}: bloque exacto = 1 punto; mismo bloque con otra
     *       orientación = 0.5 puntos.</li>
     * </ul>
     */
    public Similarity compare(Build build, boolean strict) {
        double score = 0;
        int relevant = 0;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < Build.SIZE; z++) {
                for (int x = 0; x < Build.SIZE; x++) {
                    BlockData target = build.blockAt(x, y, z);
                    BlockData actual = zoneBlock(x, y, z).getBlockData();
                    boolean actualAir = actual.getMaterial().isAir();
                    if (target == null) {
                        if (!actualAir) {
                            relevant++;
                        }
                        continue;
                    }
                    relevant++;
                    if (!actualAir && actual.getMaterial() == target.getMaterial()) {
                        score += !strict || target.matches(actual) ? 1 : 0.5;
                    }
                }
            }
        }
        if (relevant == 0) {
            return new Similarity(100, true);
        }
        double percent = score / relevant * 100.0;
        return new Similarity(percent, score >= relevant);
    }

    /** Copia lo que hay en la zona, recortando las capas vacías superiores. */
    public BlockData[][][] capture() {
        BlockData[][][] layers = new BlockData[height][Build.SIZE][Build.SIZE];
        int top = 0;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < Build.SIZE; z++) {
                for (int x = 0; x < Build.SIZE; x++) {
                    BlockData data = zoneBlock(x, y, z).getBlockData();
                    if (!data.getMaterial().isAir()) {
                        layers[y][z][x] = data;
                        top = y + 1;
                    }
                }
            }
        }
        BlockData[][][] trimmed = new BlockData[top][][];
        System.arraycopy(layers, 0, trimmed, 0, top);
        return trimmed;
    }
}
