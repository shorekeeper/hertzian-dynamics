package io.hertzian.dynamics.tick;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import io.hertzian.dynamics.HertzianDynamics;
import io.hertzian.dynamics.tile.*;
import io.hertzian.dynamics.world.HandheldRadioRegistry;
import io.hertzian.dynamics.world.WorldRfState;

/**
 * Server tick handler for every radio device in the world.
 *
 * <p>
 * Each server tick this scans the loaded tile entities of every
 * server world, groups the radio devices it finds, and drives them
 * through a fixed sequence of phases against their world's
 * {@link WorldRfState}. The handler keeps no persistent registry of
 * devices: it discovers them by scanning the world's live tile entity
 * list every tick. Scanning rather than maintaining a registry trades a
 * little per-tick work for robustness, because there is no add/remove
 * bookkeeping that can fall out of sync with chunk loads, block breaks,
 * or tile entity recreation on reload.
 *
 * <p>
 * Phase ordering
 * --------------
 * The order the phases run in is dictated by the data dependencies
 * between devices, not by convenience:
 *
 * <ol>
 * <li><b>Sources.</b> Audio source blocks (microphone, test tone,
 * telegraph key) push their samples into the audio ring of the
 * nearest transmitter. This runs first so the audio is in the ring
 * before the transmitter drains it in the same tick.</li>
 * <li><b>Emitters.</b> Jammers and transmitters register or refresh
 * their rf-core emission slots and push their audio. This runs
 * before the receive phase so receivers mix against this tick's
 * emissions rather than last tick's.</li>
 * <li><b>Services.</b> Receivers, the spectrum analyzer, the teletype,
 * and the data-mode transceivers (RTTY, DTMF, SSTV) mix a chunk and
 * produce their output. Data-mode transceivers also feed a
 * transmitter here; that audio is consumed on the next tick, which
 * is the intended one-tick path for them.</li>
 * <li><b>Relays.</b> Relays both receive and re-emit, so they run last:
 * every receiver and service this tick sees the relay's output from
 * the previous tick, giving relayed audio one uniform tick of
 * latency regardless of block placement order.</li>
 * <li><b>Handhelds.</b> Player-held radios are not tile entities, so
 * they are driven separately from the player list at the end.</li>
 * </ol>
 *
 * <p>
 * Allocation
 * --------
 * Minecraft 1.7.10 game logic runs single-threaded on the server thread,
 * and worlds are processed one at a time inside {@link #onServerTick},
 * so a single {@link TickBatch} is reused across every world and every
 * tick. It is cleared at the start of each world rather than reallocated,
 * which keeps the hot path free of the dozen list allocations the earlier
 * implementation paid each tick.
 *
 * <p>
 * Adding a device
 * --------
 * A new radio block is wired into the loop by two edits in this file:
 * a case in {@link TickBatch#classify} that files it into the right
 * phase bucket, and a case in the matching dispatch method
 * ({@link #pumpSource}, {@link #serviceTick}) that calls its tick method.
 * If the device holds rf-core slots, also add its class to
 * {@link #RF_SLOT_HOLDERS} so a stale instance is cleaned up.
 */
public final class RadioTickHandler {

    /**
     * Samples per chunk produced per tick per receiver. At the engine
     * rate of 48 kHz this is 50 ms, exactly one server tick of audio.
     */
    public static final int CHUNK_SAMPLES = 2400;

    /**
     * Tile entity classes that register emission or receiver slots in
     * {@link WorldRfState}. A stale (invalidated) instance of one of
     * these found in the tick scan has its slots released, which guards
     * against the leak where a block removed mid-tick left a frozen
     * carrier behind. The classes are all final, so an exact-class set
     * membership test is both correct and cheaper than an instanceof
     * chain.
     */
    private static final Set<Class<?>> RF_SLOT_HOLDERS = Set.of(
        TileRadioTransmitter.class,
        TileRadioReceiver.class,
        TileJammer.class,
        TileSpectrumAnalyzer.class,
        TileRelay.class,
        TileTeletype.class,
        TileRttyTerminal.class,
        TileDtmfPad.class,
        TileSstvStation.class);

    private static final RadioTickHandler INSTANCE = new RadioTickHandler();

    private boolean registered = false;

    /**
     * Reusable per-world scratch. Safe to share across worlds and ticks
     * because the server processes them sequentially on one thread; it is
     * reset at the start of each world.
     */
    private final TickBatch batch = new TickBatch();

    private RadioTickHandler() {}

    public static void register() {
        if (!INSTANCE.registered) {
            FMLCommonHandler.instance()
                .bus()
                .register(INSTANCE);
            INSTANCE.registered = true;
        }
    }

    public static void unregister() {
        if (INSTANCE.registered) {
            FMLCommonHandler.instance()
                .bus()
                .unregister(INSTANCE);
            INSTANCE.registered = false;
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        io.hertzian.dynamics.net.ServerThreadQueue.drain();
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return;
        }
        for (World world : server.worldServers) {
            if (world == null) {
                continue;
            }
            WorldRfState state = WorldRfState.forWorld(world);
            if (state == null) {
                continue;
            }
            tickWorld(world, state);
        }
    }

    private void tickWorld(World world, WorldRfState state) {
        batch.reset();
        if (!batch.collect(world, state)) {
            return;
        }
        RadioTickContext ctx = new RadioTickContext(
            world,
            state,
            world.getTotalWorldTime(),
            computeLocalHour(world),
            CHUNK_SAMPLES,
            batch.transmitters);
        batch.run(ctx);
        tickHandhelds(ctx);
    }

    /**
     * Drive every player-held radio in the world. Handhelds live on the
     * player, not in the tile entity list, so they are not part of the
     * tick batch and are stepped here from the player list.
     */
    private static void tickHandhelds(RadioTickContext ctx) {
        for (Object obj : ctx.world().playerEntities) {
            if (obj instanceof EntityPlayerMP player) {
                HandheldRadioRegistry.tickPlayer(player, ctx.worldTime(), ctx.localHour());
            }
        }
    }

    /**
     * Convert the world's time-of-day to a local hour in 0..24.
     * Minecraft tick 0 corresponds to local 06:00, and 24000 ticks make
     * one day. The conversion mirrors {@code world_tick_to_hour} in
     * rf-core but is replicated here to avoid one FFI round trip per tick
     * per world.
     */
    private static float computeLocalHour(World world) {
        long timeOfDay = world.getWorldTime() % 24000L;
        if (timeOfDay < 0) {
            timeOfDay += 24000L;
        }
        double phase = timeOfDay / 24000.0;
        double hour = phase * 24.0 + 6.0;
        return (float) (hour % 24.0);
    }

    /**
     * Per-source dispatch for the source phase. The audio source tiles do
     * not share a method signature, so the phase routes each to its own
     * pump method by type. The pumped samples land in the nearest
     * transmitter's ring, found from {@link RadioTickContext#transmitters()}.
     */
    private static void pumpSource(TileEntity te, RadioTickContext ctx) {
        switch (te) {
            case TileMicrophone mic ->
                    mic.pumpInto(ctx.transmitters(), ctx.state());
            case TileTestTone tone ->
                    tone.pumpInto(ctx.transmitters(), ctx.state(), ctx.chunkSamples());
            case TileTelegraphKey key ->
                    key.pumpInto(ctx.transmitters(), ctx.state(), ctx.chunkSamples());
            default -> {
                // Not a source; classification should never file one here.
            }
        }
    }

    /**
     * Per-service dispatch for the service phase. Receivers, the analyzer,
     * the teletype and the data-mode transceivers each mix and produce
     * output through their own tick method, routed by type here.
     */
    private static void serviceTick(TileEntity te, RadioTickContext ctx) {
        switch (te) {
            case TileRadioReceiver rx ->
                    rx.pullChunk(ctx.state(), ctx.chunkSamples(), ctx.worldTime(), ctx.localHour());
            case TileSpectrumAnalyzer analyzer ->
                    analyzer.tickScan(ctx.state(), ctx.worldTime(), ctx.localHour());
            case TileTeletype teletype ->
                    teletype.tick(ctx.state(), ctx.chunkSamples(), ctx.worldTime(), ctx.localHour());
            case TileRttyTerminal rtty ->
                    rtty.serverTick(ctx.state(), ctx.transmitters(), ctx.chunkSamples(), ctx.worldTime(), ctx.localHour());
            case TileDtmfPad dtmf ->
                    dtmf.serverTick(ctx.state(), ctx.transmitters(), ctx.chunkSamples(), ctx.worldTime(), ctx.localHour());
            case TileSstvStation sstv ->
                    sstv.serverTick(ctx.state(), ctx.transmitters(), ctx.chunkSamples(), ctx.worldTime(), ctx.localHour());
            default -> {
                // Not a service; classification should never file one here.
            }
        }
    }

    /**
     * Immutable per-world, per-tick context handed to every phase. Bundles
     * the values the device tick methods need so they no longer travel as
     * four loose parameters through the handler. The transmitter list is
     * the live batch list shared as a read-only resource; phases must not
     * mutate it.
     */
    private record RadioTickContext(World world, WorldRfState state, long worldTime, float localHour, int chunkSamples,
        List<TileRadioTransmitter> transmitters) {}

    /**
     * Reusable grouping of the radio devices found in one world's tick
     * scan. Devices are filed into phase buckets by {@link #classify}, and
     * {@link #run} steps the buckets in dependency order. Transmitters are
     * kept in their own list because they are both the emit-phase devices
     * and the shared resource the source and data-mode phases look up the
     * nearest transmitter from.
     */
    private static final class TickBatch {

        final List<TileRadioTransmitter> transmitters = new ArrayList<>();
        final List<TileEntity> sources = new ArrayList<>();
        final List<TileJammer> jammers = new ArrayList<>();
        final List<TileEntity> services = new ArrayList<>();
        final List<TileRelay> relays = new ArrayList<>();

        private final List<TileEntity> snapshot = new ArrayList<>();

        void reset() {
            transmitters.clear();
            sources.clear();
            jammers.clear();
            services.clear();
            relays.clear();
            snapshot.clear();
        }

        /**
         * Snapshot the world's loaded tile entities and file the radio
         * devices among them into phase buckets. Returns false if the
         * snapshot could not be taken, in which case this world is skipped
         * for the tick.
         *
         * <p>
         * The snapshot copy exists because the tick methods invoked
         * later can break blocks, which mutates the live
         * {@code loadedTileEntityList} and would otherwise throw a
         * concurrent modification exception mid-iteration. A stale
         * (invalidated) slot-holding device found in the snapshot has its
         * rf-core slots released here, catching the case where a block was
         * removed earlier in the same tick before its slots were freed.
         */
        boolean collect(World world, WorldRfState state) {
            try {
                @SuppressWarnings("unchecked")
                List<TileEntity> loaded = world.loadedTileEntityList;
                snapshot.addAll(loaded);
            } catch (Exception e) {
                HertzianDynamics.LOGGER.warn("Tile entity snapshot failed", e);
                return false;
            }

            int invalidReleased = 0;
            for (TileEntity te : snapshot) {
                if (te.isInvalid()) {
                    if (RF_SLOT_HOLDERS.contains(te.getClass())) {
                        state.releaseSlotsAt(te.xCoord, te.yCoord, te.zCoord);
                        invalidReleased++;
                    }
                    continue;
                }
                classify(te);
            }

            if (invalidReleased > 0) {
                HertzianDynamics.LOGGER.info(
                    "Filtered {} invalid tile entit{} from snapshot, slots released",
                    invalidReleased,
                    invalidReleased == 1 ? "y" : "ies");
            }
            return true;
        }

        /**
         * File one tile entity into the phase bucket for its kind.
         * Non-radio tile entities fall through the default and are
         * ignored. This is the single place a new device kind is mapped to
         * a phase.
         */
        private void classify(TileEntity te) {
            switch (te) {
                case TileRadioTransmitter transmitter -> transmitters.add(transmitter);
                case TileJammer jammer -> jammers.add(jammer);
                case TileRelay relay -> relays.add(relay);

                case TileMicrophone mic -> sources.add(mic);
                case TileTestTone tone -> sources.add(tone);
                case TileTelegraphKey key -> sources.add(key);

                case TileRadioReceiver rx -> services.add(rx);
                case TileSpectrumAnalyzer analyzer -> services.add(analyzer);
                case TileTeletype teletype -> services.add(teletype);
                case TileRttyTerminal rtty -> services.add(rtty);
                case TileDtmfPad dtmf -> services.add(dtmf);
                case TileSstvStation sstv -> services.add(sstv);

                default -> {
                    // Not a radio device (chests, furnaces, antenna
                    // sections, and so on): nothing to do.
                }
            }
        }

        /**
         * Run the collected devices through the phase sequence described
         * on {@link RadioTickHandler}.
         */
        void run(RadioTickContext ctx) {
            // Phase 1: audio sources feed the nearest transmitter.
            for (TileEntity source : sources) {
                pumpSource(source, ctx);
            }

            // Phase 2: emitters register or refresh their slots and push
            // audio. Jammers before transmitters is arbitrary within the
            // phase; both must precede the receive phase.
            for (TileJammer jammer : jammers) {
                jammer.tickSlot(ctx.state());
            }
            for (TileRadioTransmitter transmitter : transmitters) {
                transmitter.tickAudio(ctx.state());
            }

            // Phase 3: receivers and other services mix and produce output.
            for (TileEntity service : services) {
                serviceTick(service, ctx);
            }

            // Phase 4: relays re-emit last, so every listener sees relayed
            // audio with one uniform tick of latency.
            for (TileRelay relay : relays) {
                relay.tick(ctx.state(), ctx.chunkSamples(), ctx.worldTime(), ctx.localHour());
            }
        }
    }
}
