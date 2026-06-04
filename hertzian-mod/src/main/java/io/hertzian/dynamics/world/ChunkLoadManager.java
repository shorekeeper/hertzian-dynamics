package io.hertzian.dynamics.world;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;

import io.hertzian.dynamics.HertzianDynamics;

/**
 * Keeps the chunk of an actively broadcasting transmitter or relay
 * loaded so it goes on ticking when no player is nearby.
 *
 * <p>
 * Why this exists
 * A transmitter is simulated only while its chunk sits in the server's
 * loaded tile entity list, which the RadioTickHandler walks each tick.
 * Past a player's view distance that chunk unloads, the transmitter
 * stops ticking, its audio source stops feeding it, and the emission it
 * registered drains to silence within a fraction of a second. That is
 * the "signal appears then vanishes after teleport" symptom: long range
 * radio is impossible in Minecraft without forcing the source to keep
 * running. This manager wraps ForgeChunkManager tickets so a broadcast
 * block force loads a small area around itself and stays on air.
 *
 * <p>
 * Bounded footprint
 * One ticket per broadcasting block, each forcing the 3x3 chunk area
 * around it (enough to cover the 8 block radius a test tone block uses
 * to feed the transmitter). ForgeChunkManager caps total tickets per
 * mod, so a player spamming transmitters cannot load the whole world.
 *
 * <p>
 * Persistence
 * Tickets survive a save through ForgeChunkManager, which replays them
 * on world load via the LoadingCallback. The callback re-forces the
 * stored area from the ticket's mod data so the block's chunk loads and
 * the block resumes ticking, at which point the block re-adopts the
 * ticket on its first tick (request is idempotent).
 */
public final class ChunkLoadManager implements ForgeChunkManager.LoadingCallback {

    private static final Map<String, Ticket> TICKETS = new HashMap<>();

    private ChunkLoadManager() {}

    /** Singleton callback installed once from the mod init. */
    public static final ChunkLoadManager CALLBACK = new ChunkLoadManager();

    private static String key(World world, int x, int y, int z) {
        return world.provider.dimensionId + ":" + x + ":" + y + ":" + z;
    }

    /**
     * Force load the area around the block if it is not already held.
     * Idempotent: a second call with an existing ticket is a no-op, so
     * a block may call this every tick while broadcasting.
     */
    public static void request(World world, int x, int y, int z) {
        if (world == null || world.isRemote) return;
        String k = key(world, x, y, z);
        if (TICKETS.containsKey(k)) return;
        Ticket ticket = ForgeChunkManager
            .requestTicket(HertzianDynamics.INSTANCE, world, ForgeChunkManager.Type.NORMAL);
        if (ticket == null) {
            HertzianDynamics.LOGGER
                .warn("Chunk ticket request denied for ({}, {}, {}); broadcast range limited to loaded area", x, y, z);
            return;
        }
        NBTTagCompound data = ticket.getModData();
        data.setInteger("x", x);
        data.setInteger("y", y);
        data.setInteger("z", z);
        forceArea(ticket, x, z);
        TICKETS.put(k, ticket);
        HertzianDynamics.LOGGER.info("Broadcast chunk ticket acquired at ({}, {}, {})", x, y, z);
    }

    /** Release the area around the block. No-op if nothing was held. */
    public static void release(World world, int x, int y, int z) {
        if (world == null) return;
        Ticket ticket = TICKETS.remove(key(world, x, y, z));
        if (ticket != null) {
            ForgeChunkManager.releaseTicket(ticket);
            HertzianDynamics.LOGGER.info("Broadcast chunk ticket released at ({}, {}, {})", x, y, z);
        }
    }

    private static void forceArea(Ticket ticket, int x, int z) {
        int cx = x >> 4;
        int cz = z >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ForgeChunkManager.forceChunk(ticket, new ChunkCoordIntPair(cx + dx, cz + dz));
            }
        }
    }

    @Override
    public void ticketsLoaded(List<Ticket> tickets, World world) {
        for (Ticket ticket : tickets) {
            NBTTagCompound data = ticket.getModData();
            if (!data.hasKey("x")) {
                ForgeChunkManager.releaseTicket(ticket);
                continue;
            }
            int x = data.getInteger("x");
            int y = data.getInteger("y");
            int z = data.getInteger("z");
            forceArea(ticket, x, z);
            TICKETS.put(key(world, x, y, z), ticket);
        }
    }
}
