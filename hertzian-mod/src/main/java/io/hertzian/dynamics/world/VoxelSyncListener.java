package io.hertzian.dynamics.world;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.ChunkEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import io.hertzian.dynamics.HertzianDynamics;
import io.hertzian.dynamics.core.VoxelGrid;
import io.hertzian.dynamics.tile.*;

/**
 * Keeps the per-world {@link VoxelGrid} in sync with the Minecraft
 * world. Three event sources feed it:
 *
 * <ol>
 * <li>Chunk load: bulk write every solid column at the time of
 * load so the voxel grid starts agreeing with the world.</li>
 * <li>Block place / break: spot update for that voxel.</li>
 * <li>Server tick: bounded sweep of pending chunks queued during
 * load if the bulk write spilled past one tick's budget.</li>
 * </ol>
 *
 * <p>
 * Y range: 1.7.10 worlds have y in [0, 255]. We mirror the full
 * range. Bulk writes process one 16 x 16 column at a time, which
 * gives 256 calls into the native {@code hd_grid_set_voxel} per
 * column; the call is cheap (single hashmap insert into the
 * chunked grid) so a fresh chunk costs a few thousand calls. The
 * tick budget caps this at 64 columns per tick to keep tick time
 * predictable on big chunk-load storms (world generation, player
 * teleports).
 */
public final class VoxelSyncListener {

    private static final int MAX_COLUMNS_PER_TICK = 64;

    /**
     * Pending column queue. Entries are int packed (dim, cx, cz).
     * A plain ArrayDeque would also work, but the int-packed form
     * avoids per-entry object allocation on a hot path that fires
     * once per loaded chunk.
     */
    private final java.util.ArrayDeque<long[]> pending = new java.util.ArrayDeque<>();

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (event.world.isRemote) return;
        WorldRfState state = WorldRfState.forWorld(event.world);
        if (state == null) return;
        Chunk chunk = event.getChunk();
        ChunkCoordIntPair pair = chunk.getChunkCoordIntPair();
        // Queue the chunk; the tick handler drains the queue.
        pending.add(new long[] { event.world.provider.dimensionId, pair.chunkXPos, pair.chunkZPos });
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        // Release rf-core slots for any radio tile in the unloading
        // chunk. Without this the slot map kept the emission id alive
        // after the tile stopped ticking, leaving a ghost carrier (FM)
        // or a stuck receiver registration on the band. Broadcasting
        // blocks force load their chunk, so this only ever fires for
        // tiles that genuinely went away.
        if (event.world.isRemote) return;
        WorldRfState state = WorldRfState.forWorld(event.world);
        if (state == null) return;
        Chunk chunk = event.getChunk();
        for (Object o : chunk.chunkTileEntityMap.values()) {
            if (!(o instanceof TileEntity)) continue;
            TileEntity te = (TileEntity) o;
            if (te instanceof TileRadioTransmitter || te instanceof TileRadioReceiver
                || te instanceof TileJammer
                || te instanceof TileSpectrumAnalyzer
                || te instanceof TileRelay
                || te instanceof TileTeletype
                || te instanceof TileTeletype
                || te instanceof TileRttyTerminal
                || te instanceof TileDtmfPad
                || te instanceof TileSstvStation) {
                state.releaseSlotsAt(te.xCoord, te.yCoord, te.zCoord);
            }
        }
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.world.isRemote) return;
        WorldRfState state = WorldRfState.forWorld(event.world);
        if (state == null) return;
        // Voxel grid update for radio propagation.
        state.grid()
            .setVoxel(event.x, event.y, event.z, BlockMaterialMap.AIR);
        // Slot release: if this block carried an emission or
        // receiver registration, free the slot now. Releasing
        // unconditionally is safe because the slot map silently
        // ignores unknown positions.
        state.releaseSlotsAt(event.x, event.y, event.z);
        ChunkLoadManager.release(event.world, event.x, event.y, event.z);
        HertzianDynamics.LOGGER.info(
            "BlockBreak event at ({}, {}, {}) dim={}",
            event.x,
            event.y,
            event.z,
            event.world.provider.dimensionId);
        // Also drop any client-side AL voice bound to this slot.
        // The bridge tolerates unknown keys; calling it here keeps
        // the lifecycle paired with the slot release.
        io.hertzian.dynamics.audio.ClientAudioBridge
            .unregister(event.world.provider.dimensionId, event.x, event.y, event.z);
    }

    @SubscribeEvent
    public void onBlockPlace(BlockEvent.PlaceEvent event) {
        if (event.world.isRemote) return;
        WorldRfState state = WorldRfState.forWorld(event.world);
        if (state == null) return;
        int id = BlockMaterialMap.forBlock(event.block);
        state.grid()
            .setVoxel(event.x, event.y, event.z, id);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        int budget = MAX_COLUMNS_PER_TICK;
        while (budget > 0 && !pending.isEmpty()) {
            long[] entry = pending.poll();
            int dim = (int) entry[0];
            int cx = (int) entry[1];
            int cz = (int) entry[2];
            WorldRfState state = WorldRfState.forDimension(dim);
            if (state == null) continue;
            World world = findWorld(dim);
            if (world == null) continue;
            Chunk chunk = world.getChunkFromChunkCoords(cx, cz);
            if (chunk == null) continue;
            uploadChunk(state.grid(), chunk);
            budget--;
        }
    }

    private static World findWorld(int dim) {
        net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
        if (server == null) return null;
        for (World w : server.worldServers) {
            if (w != null && w.provider.dimensionId == dim) return w;
        }
        return null;
    }

    /**
     * Bulk write one chunk into the voxel grid. Walks every (x, z)
     * column inside the chunk and probes the vertical column with
     * {@link Chunk#getBlock(int, int, int)}. Air voxels are not
     * written: the grid defaults to air for unmapped voxels, so we
     * save 90+% of the calls.
     */
    private static void uploadChunk(VoxelGrid grid, Chunk chunk) {
        int baseX = chunk.xPosition << 4;
        int baseZ = chunk.zPosition << 4;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = baseX + lx;
                int wz = baseZ + lz;
                int topY = Math.min(255, chunk.getHeightValue(lx, lz) + 1);
                for (int y = 0; y <= topY; y++) {
                    Block b = chunk.getBlock(lx, y, lz);
                    int id = BlockMaterialMap.forBlock(b);
                    if (id != BlockMaterialMap.AIR) {
                        grid.setVoxel(wx, y, wz, id);
                    }
                }
            }
        }
    }
}
