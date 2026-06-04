package io.hertzian.dynamics.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

import io.hertzian.dynamics.HertzianDynamics;
import io.hertzian.dynamics.core.ComputeStats;
import io.hertzian.dynamics.core.Ionosphere;
import io.hertzian.dynamics.core.MaterialTable;
import io.hertzian.dynamics.core.RfCore;
import io.hertzian.dynamics.core.SpectrumManager;
import io.hertzian.dynamics.core.VoxelGrid;

/**
 * Per-server-world bundle of rf-core resources. Owns one {@link RfCore},
 * one {@link SpectrumManager}, one {@link VoxelGrid}, one
 * {@link MaterialTable} and one {@link Ionosphere}, plus the
 * registration slots that bind block positions to rf-core ids.
 *
 * <p>
 * Slot architecture
 * ------------------------------------------------
 * <p>
 * The slot map is building binding ids to packed block
 * coordinates instead of to TE instances. The TE becomes a pure
 * reporter: it calls {@link #getOrRegisterEmission} or
 * {@link #getOrRegisterReceiver} each tick with the parameters it
 * wants, and the slot map either returns the existing id (when the
 * parameters still match) or rotates to a new id (when they do
 * not). Unregistration happens only on the explicit
 * {@code BlockEvent.BreakEvent} signal handled by
 * {@link VoxelSyncListener}, which is the one event Minecraft
 * fires that genuinely means "the block is gone".
 */
public final class WorldRfState implements AutoCloseable {

    private static final Map<Integer, WorldRfState> STATES = new HashMap<>();

    private final RfCore core;
    private final SpectrumManager manager;
    private final VoxelGrid grid;
    private final MaterialTable materials;
    private final Ionosphere ionosphere;

    /** Emission slots keyed by packed block coordinates. */
    private final Map<Long, EmissionSlot> emissionSlots = new HashMap<>();
    /** Receiver slots keyed by packed block coordinates. */
    private final Map<Long, ReceiverSlot> receiverSlots = new HashMap<>();
    /** Demodulator slots keyed identically to receiver slots. */
    private final Map<Long, io.hertzian.dynamics.core.ChunkDemodulator> demodulatorSlots = new HashMap<>();

    /**
     * Resolve or create the demodulator for this receiver slot.
     * Recreated whenever {@link #forceRetuneReceiverAt} fires.
     */
    public io.hertzian.dynamics.core.ChunkDemodulator getOrCreateDemodulator(long key,
        io.hertzian.dynamics.core.Modulation modulation, float sampleRateHz) {
        io.hertzian.dynamics.core.ChunkDemodulator d = demodulatorSlots.get(key);
        if (d == null || d.modulation() != modulation) {
            d = new io.hertzian.dynamics.core.ChunkDemodulator(modulation, sampleRateHz);
            demodulatorSlots.put(key, d);
        }
        return d;
    }

    private WorldRfState(RfCore core, SpectrumManager manager, VoxelGrid grid, MaterialTable materials,
        Ionosphere ionosphere) {
        this.core = core;
        this.manager = manager;
        this.grid = grid;
        this.materials = materials;
        this.ionosphere = ionosphere;
    }

    public static void installFor(MinecraftServer server) {
        for (World w : server.worldServers) {
            if (w == null) continue;
            int dim = w.provider.dimensionId;
            STATES.computeIfAbsent(dim, WorldRfState::buildFor);
        }
    }

    private static WorldRfState buildFor(int dimensionId) {
        HertzianDynamics.LOGGER.info("Initialising rf-core for dimension {}", dimensionId);
        // Build the core with the configured compute backend. The GPU path
        // is optional; if Vulkan or the GPU pipelines are unavailable the
        // core still comes up and runs every workload on the CPU.
        RfCore core = RfCore.create(io.hertzian.dynamics.HertzianConfig.gpuEnabled);
        // Apply the configured zoom DFT backend policy. This is realism
        // neutral; it only selects where the analyzer DFT runs.
        core.setComputePolicy(
            RfCore.WORKLOAD_ZOOM_DFT,
            io.hertzian.dynamics.HertzianConfig.zoomDftMode,
            io.hertzian.dynamics.HertzianConfig.zoomDftAutoThreshold);
        io.hertzian.dynamics.core.ComputeStats cs = core.computeStats(RfCore.WORKLOAD_ZOOM_DFT);
        core.setComputePolicy(
            RfCore.WORKLOAD_ZOOM_DFT,
            io.hertzian.dynamics.HertzianConfig.zoomDftMode,
            io.hertzian.dynamics.HertzianConfig.zoomDftAutoThreshold);
        core.setComputePolicy(
            RfCore.WORKLOAD_PROPAGATION,
            io.hertzian.dynamics.HertzianConfig.propagationMode,
            io.hertzian.dynamics.HertzianConfig.propagationAutoThreshold);
        io.hertzian.dynamics.core.ComputeStats zs = core.computeStats(RfCore.WORKLOAD_ZOOM_DFT);
        io.hertzian.dynamics.core.ComputeStats ps = core.computeStats(RfCore.WORKLOAD_PROPAGATION);
        HertzianDynamics.LOGGER.info(
            "rf-core compute for dim {}: gpuEnabled={}, zoomDFT gpu={}, raycast gpu={}",
            dimensionId,
            io.hertzian.dynamics.HertzianConfig.gpuEnabled,
            zs.gpuAvailable(),
            ps.gpuAvailable());

        SpectrumManager manager = SpectrumManager.create(core);
        // Push the curvature knobs from the mod config into the native
        // solver so the radio horizon takes effect for every mix in this
        // world.
        manager.setCurvature(
            io.hertzian.dynamics.HertzianConfig.modelCurvature,
            io.hertzian.dynamics.HertzianConfig.earthRadiusM,
            io.hertzian.dynamics.HertzianConfig.earthKFactor,
            io.hertzian.dynamics.HertzianConfig.groundRefM);
        VoxelGrid grid = VoxelGrid.create(1.0f);
        MaterialTable materials = MaterialTable.createDefaults();
        Ionosphere iono = Ionosphere.create(solarActivityFromConfig());
        return new WorldRfState(core, manager, grid, materials, iono);
    }

    /** Map the configured solar activity code to the rf-core preset. */
    private static Ionosphere.SolarActivity solarActivityFromConfig() {
        switch (io.hertzian.dynamics.HertzianConfig.solarActivity) {
            case 0:
                return Ionosphere.SolarActivity.LOW;
            case 2:
                return Ionosphere.SolarActivity.HIGH;
            case 1:
            default:
                return Ionosphere.SolarActivity.MEDIUM;
        }
    }

    public static WorldRfState forDimension(int dimensionId) {
        return STATES.get(dimensionId);
    }

    public static WorldRfState forWorld(World world) {
        return world == null ? null : forDimension(world.provider.dimensionId);
    }

    public static void disposeAll() {
        for (WorldRfState s : STATES.values()) {
            try {
                s.close();
            } catch (Throwable t) {
                HertzianDynamics.LOGGER.error("Failed to dispose rf-core state", t);
            }
        }
        STATES.clear();
    }

    /**
     * Apply a compute backend policy to the cores of every loaded world.
     * Realism neutral: it only selects where the workload runs. Used by
     * the runtime configurator (server command) so a policy change takes
     * effect without a world reload. Failures on one world are logged and
     * do not stop the others.
     */
    public static synchronized void setComputePolicyAll(int workload, int mode, long minWork) {
        for (WorldRfState s : STATES.values()) {
            try {
                s.core.setComputePolicy(workload, mode, minWork);
            } catch (Throwable t) {
                HertzianDynamics.LOGGER.warn("setComputePolicy failed for a world", t);
            }
        }
    }

    /**
     * Gather the compute dispatch counters for one workload across every
     * loaded world, one human readable line per dimension. For the
     * diagnostics command.
     */
    public static synchronized List<String> describeComputeAll(int workload) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<Integer, WorldRfState> e : STATES.entrySet()) {
            try {
                ComputeStats cs = e.getValue().core.computeStats(workload);
                out.add(
                    String.format(
                        "dim %d: gpu=%d cpu=%d fallback=%d lastGpu=%b gpuAvail=%b",
                        e.getKey(),
                        cs.gpuCalls(),
                        cs.cpuCalls(),
                        cs.fallbackCalls(),
                        cs.lastBackendGpu(),
                        cs.gpuAvailable()));
            } catch (Throwable t) {
                out.add("dim " + e.getKey() + ": stats unavailable");
            }
        }
        return out;
    }

    public RfCore core() {
        return core;
    }

    public SpectrumManager manager() {
        return manager;
    }

    public VoxelGrid grid() {
        return grid;
    }

    public MaterialTable materials() {
        return materials;
    }

    public Ionosphere ionosphere() {
        return ionosphere;
    }

    /**
     * Pack three block coordinates into a single long usable as a
     * hash map key. The layout uses 26 bits for X and Z (range
     * -33 554 432 to +33 554 431, well beyond the 1.7.10 world
     * border at 30 million) and 12 bits for Y (range -2 048 to
     * +2 047, beyond the 0..255 height range).
     */
    public static long packPos(int x, int y, int z) {
        return (((long) x) & 0x3FFFFFFL) | ((((long) z) & 0x3FFFFFFL) << 26) | ((((long) y) & 0xFFFL) << 52);
    }

    /**
     * Resolve the emission slot for the given block position.
     *
     *
     * <p>
     * The slot cache is keyed by block position alone. Once a slot is
     * present, it is reused forever (until the block is broken or
     * the caller asks for an explicit retune via
     * {@link #forceRetuneEmissionAt}). Parameter changes from
     * legitimate sources (the tuning knob, the gui)
     * go through the explicit retune path and the next pullChunk
     * sees an empty slot and re-registers cleanly.
     */
    public int getOrRegisterEmission(long key, SpectrumManager.EmissionParameters params, boolean jammer,
        int jamProfile, float jamRateHz, float jamSigma) {
        EmissionSlot slot = emissionSlots.get(key);
        if (slot != null) {
            return slot.id;
        }
        int newId = jammer ? manager.registerJammer(params, jamProfile, jamRateHz, jamSigma)
            : manager.registerAudioEmission(params);
        emissionSlots.put(key, new EmissionSlot(newId));
        HertzianDynamics.LOGGER
            .info("Emission slot {} -> id {} ({}) NEW", Long.toHexString(key), newId, jammer ? "jammer" : "audio");
        return newId;
    }

    /**
     * Resolve the receiver slot for the given block position.
     * See {@link #getOrRegisterEmission} for the design rationale.
     */
    public int getOrRegisterReceiver(long key, SpectrumManager.ReceiverParameters params) {
        ReceiverSlot slot = receiverSlots.get(key);
        if (slot != null) {
            return slot.id;
        }
        int newId = manager.registerReceiver(params);
        receiverSlots.put(key, new ReceiverSlot(newId));
        HertzianDynamics.LOGGER.info("Receiver slot {} -> id {} NEW", Long.toHexString(key), newId);
        return newId;
    }

    /**
     * Drop the emission slot for the given position so the next
     * pullChunk-like call re-registers with the current TE
     * parameters. Called by transmitter and jammer setters when a
     * field that the rf-core needs to reflect changes (carrier
     * frequency, modulation, power level).
     */
    public void forceRetuneEmissionAt(int x, int y, int z) {
        long key = packPos(x, y, z);
        EmissionSlot slot = emissionSlots.remove(key);
        if (slot != null) {
            try {
                manager.unregisterEmission(slot.id);
            } catch (Throwable t) {
                HertzianDynamics.LOGGER.warn("forceRetuneEmissionAt unregister failed", t);
            }
            demodulatorSlots.remove(key);
            HertzianDynamics.LOGGER
                .info("Emission slot {} RELEASED (was id {}, retune requested)", Long.toHexString(key), slot.id);
        }
    }

    /**
     * Release only the emission slot at this position, leaving any
     * receiver slot and demodulator in place. Used by the relay, which
     * holds a receiver and an emission at the same coordinates and needs
     * to stop transmitting on input squelch without tearing down its
     * receive side.
     */
    public void releaseEmissionAt(int x, int y, int z) {
        long key = packPos(x, y, z);
        EmissionSlot slot = emissionSlots.remove(key);
        if (slot != null) {
            try {
                manager.unregisterEmission(slot.id);
            } catch (Throwable t) {
                HertzianDynamics.LOGGER.warn("releaseEmissionAt unregister failed", t);
            }
        }
    }

    /**
     * Counterpart of {@link #forceRetuneEmissionAt} for receivers.
     * Called by the tuning knob and by receiver setters.
     */
    public void forceRetuneReceiverAt(int x, int y, int z) {
        long key = packPos(x, y, z);
        ReceiverSlot slot = receiverSlots.remove(key);
        if (slot != null) {
            try {
                manager.unregisterReceiver(slot.id);
            } catch (Throwable t) {
                HertzianDynamics.LOGGER.warn("forceRetuneReceiverAt unregister failed", t);
            }
            HertzianDynamics.LOGGER
                .info("Receiver slot {} RELEASED (was id {}, retune requested)", Long.toHexString(key), slot.id);
        }
    }

    public void releaseSlotsAt(int x, int y, int z) {
        long key = packPos(x, y, z);
        EmissionSlot es = emissionSlots.remove(key);
        if (es != null) {
            try {
                manager.unregisterEmission(es.id);
            } catch (Throwable t) {
                HertzianDynamics.LOGGER.warn("emission slot release failed", t);
            }
            HertzianDynamics.LOGGER
                .info("Emission slot {} RELEASED on block break (was id {})", Long.toHexString(key), es.id);
        }
        ReceiverSlot rs = receiverSlots.remove(key);
        if (rs != null) {
            try {
                manager.unregisterReceiver(rs.id);
            } catch (Throwable t) {
                HertzianDynamics.LOGGER.warn("receiver slot release failed", t);
            }
            HertzianDynamics.LOGGER
                .info("Receiver slot {} RELEASED on block break (was id {})", Long.toHexString(key), rs.id);
        }
        demodulatorSlots.remove(key);
    }

    /**
     * Release an emission slot identified by its raw key. Used by
     * the handheld registry which keys slots by salted player UUID
     * rather than by block position. Returns true if a slot was
     * released. Idempotent and silent on unknown keys.
     */
    public boolean releaseEmissionByKey(long key) {
        EmissionSlot slot = emissionSlots.remove(key);
        if (slot == null) return false;
        try {
            manager.unregisterEmission(slot.id);
        } catch (Throwable t) {
            HertzianDynamics.LOGGER.warn("releaseEmissionByKey unregister failed", t);
        }
        HertzianDynamics.LOGGER.info("Emission slot {} RELEASED by key (was id {})", Long.toHexString(key), slot.id);
        return true;
    }

    /**
     * Release a receiver slot identified by its raw key. Counterpart
     * to {@link #releaseEmissionByKey} for player handhelds.
     */
    public boolean releaseReceiverByKey(long key) {
        ReceiverSlot slot = receiverSlots.remove(key);
        demodulatorSlots.remove(key);
        if (slot == null) return false;
        try {
            manager.unregisterReceiver(slot.id);
        } catch (Throwable t) {
            HertzianDynamics.LOGGER.warn("releaseReceiverByKey unregister failed", t);
        }
        HertzianDynamics.LOGGER.info("Receiver slot {} RELEASED by key (was id {})", Long.toHexString(key), slot.id);
        return true;
    }

    @Override
    public void close() {
        emissionSlots.clear();
        receiverSlots.clear();
        demodulatorSlots.clear();
        manager.close();
        ionosphere.close();
        materials.close();
        grid.close();
        core.close();
    }

    /** Slot record for an emission. Kept lightweight; reused. */
    private static final class EmissionSlot {

        final int id;

        EmissionSlot(int id) {
            this.id = id;
        }
    }

    /** Slot record for a receiver. */
    private static final class ReceiverSlot {

        final int id;

        ReceiverSlot(int id) {
            this.id = id;
        }
    }
}
