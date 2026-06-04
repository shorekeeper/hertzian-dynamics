package io.hertzian.dynamics.tile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;

import io.hertzian.dynamics.HertzianDynamics;
import io.hertzian.dynamics.core.Modulation;
import io.hertzian.dynamics.core.QoaAudioSource;
import io.hertzian.dynamics.core.SpectrumManager;
import io.hertzian.dynamics.core.StationIdEncoder;
import io.hertzian.dynamics.world.ChunkLoadManager;
import io.hertzian.dynamics.world.StationLibrary;
import io.hertzian.dynamics.world.WorldRfState;

/**
 * Tile entity for the radio transmitter block. Slot-driven (the
 * rf-core emission id lives in {@link WorldRfState}) with PTT-style
 * squelch on top.
 *
 * <p>
 * PTT squelch
 * --------------------------------
 * Real broadcast FM and AM transmitters keep a carrier on air
 * continuously, even when the audio input is silent. This is
 * mathematically correct behaviour and rf-core reflects it: an
 * FM modulator fed zero PCM produces a constant complex baseband
 * sample that shows up on the spectrum analyzer as a stationary
 * bright line in the centre of the receiver passband. An AM
 * modulator with modulation index > 0 fed zero PCM produces a
 * constant envelope of 1, which is the carrier itself.
 *
 * <p>
 * In the game context this is confusing, so the transmitter behaves
 * like a walkie-talkie: if no meaningful audio has been pushed for
 * {@link #SQUELCH_HOLD_TICKS} ticks (750 ms at 20 Hz), the rf-core
 * emission slot is released and the transmitter goes off air. The next
 * push of non-zero audio brings the slot back.
 *
 * <p>
 * Station playlist
 * --------------------------------
 * The station player gained a playlist and a play order. The playlist
 * is an ordered list of track names persisted in NBT and synced to the
 * client for the GUI side panel. {@link #shuffle} selects between linear
 * order (advance through the list, wrap if {@link #loopTrack} is on) and
 * random order (pick any entry on each track end). {@link #playlistIndex}
 * is the cursor: a value of zero or more means playback is following the
 * playlist, and minus one means a single track was loaded directly from
 * the browser and the old single-track loop or stop behaviour applies.
 */
public final class TileRadioTransmitter extends TileEntity {

    /**
     * How long the transmitter keeps emitting after the last
     * meaningful audio chunk. 15 ticks = 750 ms.
     */
    private static final int SQUELCH_HOLD_TICKS = 15;

    /**
     * Amplitude threshold below which audio is considered silence
     * for squelch purposes.
     */
    private static final float SILENCE_THRESHOLD = 0.005f;

    private final List<float[]> pendingAudio = new ArrayList<>();

    private double carrierHz = 145_000_000.0;
    private float bandwidthHz = 15_000.0f;
    private float txPowerW = 5.0f;
    private float antennaGain = 1.0f;
    private Modulation modulation = Modulation.NARROW_FM;

    /**
     * When true the transmitter force loads its chunk so it keeps
     * ticking and transmitting while no player is nearby.
     */
    private boolean broadcasting = false;

    public boolean broadcasting() {
        return broadcasting;
    }

    public void setBroadcasting(boolean v) {
        this.broadcasting = v;
        markDirty();
        if (worldObj == null || worldObj.isRemote) return;
        if (v) {
            ChunkLoadManager.request(worldObj, xCoord, yCoord, zCoord);
        } else {
            ChunkLoadManager.release(worldObj, xCoord, yCoord, zCoord);
        }
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
    }

    private long lastMeaningfulAudioTick = Long.MIN_VALUE;

    public void enqueueAudio(float[] samples) {
        if (samples == null || samples.length == 0) return;
        pendingAudio.add(samples);
        if (worldObj != null && !worldObj.isRemote && hasMeaningfulAudio(samples)) {
            lastMeaningfulAudioTick = worldObj.getTotalWorldTime();
        }
    }

    private static boolean hasMeaningfulAudio(float[] samples) {
        int step = Math.max(1, samples.length / 16);
        for (int i = 0; i < samples.length; i += step) {
            if (Math.abs(samples[i]) > SILENCE_THRESHOLD) return true;
        }
        return false;
    }

    public double carrierHz() {
        return carrierHz;
    }

    public float bandwidthHz() {
        return bandwidthHz;
    }

    public float txPowerW() {
        return txPowerW;
    }

    public Modulation modulation() {
        return modulation;
    }

    /** Transmitter operating mode. NORMAL is fed by source blocks; STATION self-generates QOA audio. */
    public enum Mode {
        NORMAL,
        STATION
    }

    private Mode mode = Mode.NORMAL;
    private String stationName = "";
    private final StationIdEncoder stationEncoder = new StationIdEncoder();

    // Pre-emphasis one-zero state (the inverse of the receiver de-emphasis).
    private float preEmphPrev = 0f;
    private static final float DEEMPH_ALPHA = 0.294f;

    // Station playback state.
    private String selectedTrack = "";
    private boolean playing = false;
    private boolean loopTrack = true;
    private double savedPositionSeconds = 0.0;
    private transient QoaAudioSource source;
    private transient boolean sourceDirty = true;
    private transient int lastAntennaSections = -1;

    // Playlist state.
    private final List<String> playlist = new ArrayList<>();
    private boolean shuffle = false;
    private int playlistIndex = -1;
    private final Random playlistRng = new Random();

    public Mode mode() {
        return mode;
    }

    public String stationName() {
        return stationName;
    }

    public String selectedTrack() {
        return selectedTrack;
    }

    public boolean playing() {
        return playing;
    }

    public boolean loopTrack() {
        return loopTrack;
    }

    public double positionSeconds() {
        return source != null ? source.positionSeconds() : savedPositionSeconds;
    }

    public double durationSeconds() {
        return source != null ? source.durationSeconds() : 0.0;
    }

    /** Live playlist; read-only for callers. */
    public List<String> playlist() {
        return playlist;
    }

    public boolean shuffle() {
        return shuffle;
    }

    public int playlistIndex() {
        return playlistIndex;
    }

    public void setMode(Mode m) {
        this.mode = m;
        if (m == Mode.STATION) setBroadcasting(true);
        markDirty();
        syncToClient();
    }

    public void setStationName(String name) {
        this.stationName = name == null ? "" : name;
        stationEncoder.setName(this.stationName);
        markDirty();
        syncToClient();
    }

    public void selectTrack(String track) {
        this.selectedTrack = track == null ? "" : track;
        this.savedPositionSeconds = 0.0;
        this.sourceDirty = true;
        // A direct browser pick leaves playlist playback; minus one means
        // "single track" so the loop or stop behaviour applies on end.
        this.playlistIndex = -1;
        markDirty();
        syncToClient();
    }

    public void setPlaying(boolean p) {
        this.playing = p;
        markDirty();
        syncToClient();
    }

    public void setLoopTrack(boolean l) {
        this.loopTrack = l;
        markDirty();
        syncToClient();
    }

    public void seekSeconds(double s) {
        this.savedPositionSeconds = Math.max(0, s);
        if (source != null) source.seekSeconds(savedPositionSeconds);
        markDirty();
    }

    public void setShuffle(boolean s) {
        this.shuffle = s;
        markDirty();
        syncToClient();
    }

    public void addToPlaylist(String track) {
        if (track == null || track.isEmpty()) return;
        playlist.add(track);
        markDirty();
        syncToClient();
    }

    public void removeFromPlaylist(int idx) {
        if (idx >= 0 && idx < playlist.size()) {
            playlist.remove(idx);
            if (playlistIndex >= playlist.size()) playlistIndex = playlist.size() - 1;
            markDirty();
            syncToClient();
        }
    }

    public void clearPlaylist() {
        playlist.clear();
        playlistIndex = -1;
        markDirty();
        syncToClient();
    }

    /** Begin playlist playback from its first (linear) or a random (shuffle) entry. */
    public void startPlaylist() {
        if (playlist.isEmpty()) return;
        playlistIndex = shuffle ? playlistRng.nextInt(playlist.size()) : 0;
        selectedTrack = playlist.get(playlistIndex);
        savedPositionSeconds = 0.0;
        sourceDirty = true;
        playing = true;
        markDirty();
        syncToClient();
    }

    /**
     * Move the playlist cursor on track end. Linear advances by one and
     * wraps to the start only when looping; shuffle jumps to any entry.
     */
    private void advancePlaylist() {
        if (playlist.isEmpty()) {
            playing = false;
            markDirty();
            return;
        }
        int next;
        if (shuffle) {
            next = playlistRng.nextInt(playlist.size());
        } else {
            next = playlistIndex + 1;
            if (next >= playlist.size()) {
                if (loopTrack) {
                    next = 0;
                } else {
                    playing = false;
                    markDirty();
                    syncToClient();
                    return;
                }
            }
        }
        playlistIndex = next;
        selectedTrack = playlist.get(next);
        savedPositionSeconds = 0.0;
        sourceDirty = true;
        markDirty();
        syncToClient();
    }

    private void syncToClient() {
        if (worldObj != null && !worldObj.isRemote) {
            worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
        }
    }

    public void setCarrierHz(double hz) {
        this.carrierHz = hz;
        markDirty();
        requestRetune();
    }

    public void setBandwidthHz(float hz) {
        this.bandwidthHz = hz;
        markDirty();
        requestRetune();
    }

    public void setTxPowerW(float watts) {
        this.txPowerW = watts;
        markDirty();
        requestRetune();
    }

    public void setModulation(Modulation m) {
        this.modulation = m;
        markDirty();
        requestRetune();
    }

    private void requestRetune() {
        if (worldObj == null || worldObj.isRemote) return;
        WorldRfState state = WorldRfState.forWorld(worldObj);
        if (state != null) {
            state.forceRetuneEmissionAt(xCoord, yCoord, zCoord);
        }
    }

    public void tickAudio(WorldRfState state) {
        if (worldObj == null || worldObj.isRemote) return;
        long now = worldObj.getTotalWorldTime();

        if (broadcasting) {
            ChunkLoadManager.request(worldObj, xCoord, yCoord, zCoord);
        }

        if (mode == Mode.STATION) {
            tickStation(state);
            return;
        }

        boolean squelched = (now - lastMeaningfulAudioTick) > SQUELCH_HOLD_TICKS;
        if (squelched) {
            state.releaseSlotsAt(xCoord, yCoord, zCoord);
            pendingAudio.clear();
            return;
        }
        int id = registerEmission(state);
        if (id < 0) {
            pendingAudio.clear();
            return;
        }
        SpectrumManager mgr = state.manager();
        try {
            for (float[] buf : pendingAudio) {
                mgr.pushAudio(id, conditionFrame(buf));
            }
        } catch (Throwable t) {
            HertzianDynamics.LOGGER.error("transmitter pushAudio failed", t);
        } finally {
            pendingAudio.clear();
        }
    }

    /**
     * Station mode tick: keep a carrier on air, decode the next slice of
     * the selected track, condition it and push it. At the end of a track
     * the playlist advances if one is being followed; otherwise the old
     * single-track loop or stop applies. The carrier is never squelched
     * so the station ID stays decodable during silent passages.
     */
    private void tickStation(WorldRfState state) {
        int id = registerEmission(state);
        if (id < 0) return;
        int n = io.hertzian.dynamics.tick.RadioTickHandler.CHUNK_SAMPLES;
        float[] frame = new float[n];
        if (playing) {
            try {
                ensureSource();
                if (source != null) {
                    boolean full = source.read48kMono(frame, n);
                    savedPositionSeconds = source.positionSeconds();
                    if (!full || source.atEnd()) {
                        if (playlistIndex >= 0 && !playlist.isEmpty()) {
                            advancePlaylist();
                        } else if (loopTrack) {
                            source.seekSeconds(0);
                            savedPositionSeconds = 0;
                        } else {
                            playing = false;
                            markDirty();
                        }
                    }
                }
            } catch (Throwable t) {
                HertzianDynamics.LOGGER
                    .error("station playback failed at ({}, {}, {}); stopping track", xCoord, yCoord, zCoord, t);
                playing = false;
                source = null;
                sourceDirty = true;
                java.util.Arrays.fill(frame, 0f);
                markDirty();
            }
        }
        try {
            state.manager()
                .pushAudio(id, conditionFrame(frame));
        } catch (Throwable t) {
            HertzianDynamics.LOGGER.error("station pushAudio failed", t);
        }
    }

    private void ensureSource() {
        if (!sourceDirty && source != null) return;
        if (source != null) {
            source = null;
        }
        sourceDirty = false;
        Path p = null;
        File f = StationLibrary.resolve(selectedTrack);
        if (f != null) p = f.toPath();
        if (p == null) return;
        try {
            source = new QoaAudioSource(p);
            if (savedPositionSeconds > 0) source.seekSeconds(savedPositionSeconds);
        } catch (IOException e) {
            HertzianDynamics.LOGGER.warn("failed to open station track {}", selectedTrack, e);
            source = null;
        }
    }

    private int registerEmission(WorldRfState state) {
        int sections = io.hertzian.dynamics.world.AntennaSupport.countAbove(worldObj, xCoord, yCoord, zCoord);
        if (sections != lastAntennaSections) {
            lastAntennaSections = sections;
            state.forceRetuneEmissionAt(xCoord, yCoord, zCoord);
        }
        float effGain = antennaGain * io.hertzian.dynamics.world.AntennaSupport.gainFromSections(sections);
        float effY = yCoord + 0.5f + sections;

        SpectrumManager.EmissionParameters params = new SpectrumManager.EmissionParameters(
            modulation,
            xCoord + 0.5f,
            effY,
            zCoord + 0.5f,
            0f,
            0f,
            0f,
            txPowerW,
            effGain,
            carrierHz,
            bandwidthHz,
            16_384);
        long key = WorldRfState.packPos(xCoord, yCoord, zCoord);
        try {
            return state.getOrRegisterEmission(key, params, false, 0, 0f, 0f);
        } catch (Throwable t) {
            HertzianDynamics.LOGGER.error("transmitter slot resolution failed", t);
            return -1;
        }
    }

    private float[] conditionFrame(float[] in) {
        int n = in.length;
        float[] f = new float[n];
        boolean clip = modulation == Modulation.AM || modulation == Modulation.NARROW_FM
            || modulation == Modulation.USB
            || modulation == Modulation.LSB;
        boolean preEmph = modulation == Modulation.NARROW_FM || modulation == Modulation.WIDE_FM;
        for (int i = 0; i < n; i++) {
            float s = in[i];
            if (clip) s = (float) Math.tanh(1.8 * s) * 0.92f;
            if (preEmph) {
                float pe = (s - (1f - DEEMPH_ALPHA) * preEmphPrev) / DEEMPH_ALPHA;
                preEmphPrev = s;
                s = pe;
            }
            f[i] = s;
        }
        stationEncoder.mixInto(f, n);
        for (int i = 0; i < n; i++) {
            if (f[i] > 1f) f[i] = 1f;
            else if (f[i] < -1f) f[i] = -1f;
        }
        return f;
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setDouble("carrierHz", carrierHz);
        tag.setFloat("bandwidthHz", bandwidthHz);
        tag.setFloat("txPowerW", txPowerW);
        tag.setFloat("antennaGain", antennaGain);
        tag.setBoolean("broadcasting", broadcasting);
        tag.setInteger("modulation", modulation.code());
        tag.setInteger("mode", mode.ordinal());
        tag.setString("stationName", stationName);
        tag.setString("track", selectedTrack);
        tag.setBoolean("playing", playing);
        tag.setBoolean("loopTrack", loopTrack);
        tag.setDouble("trackPos", savedPositionSeconds);
        // Playlist block.
        NBTTagList pl = new NBTTagList();
        for (String s : playlist) pl.appendTag(new NBTTagString(s));
        tag.setTag("playlist", pl);
        tag.setBoolean("shuffle", shuffle);
        tag.setInteger("playlistIndex", playlistIndex);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        if (tag.hasKey("carrierHz")) carrierHz = tag.getDouble("carrierHz");
        if (tag.hasKey("bandwidthHz")) bandwidthHz = tag.getFloat("bandwidthHz");
        if (tag.hasKey("txPowerW")) txPowerW = tag.getFloat("txPowerW");
        if (tag.hasKey("antennaGain")) antennaGain = tag.getFloat("antennaGain");
        if (tag.hasKey("broadcasting")) broadcasting = tag.getBoolean("broadcasting");
        if (tag.hasKey("mode")) {
            int o = tag.getInteger("mode");
            mode = (o == 1) ? Mode.STATION : Mode.NORMAL;
        }
        if (tag.hasKey("stationName")) stationName = tag.getString("stationName");
        stationEncoder.setName(stationName);
        if (tag.hasKey("track")) selectedTrack = tag.getString("track");
        if (tag.hasKey("playing")) playing = tag.getBoolean("playing");
        if (tag.hasKey("loopTrack")) loopTrack = tag.getBoolean("loopTrack");
        if (tag.hasKey("trackPos")) savedPositionSeconds = tag.getDouble("trackPos");
        // Playlist block.
        playlist.clear();
        if (tag.hasKey("playlist")) {
            NBTTagList pl = tag.getTagList("playlist", 8);
            for (int i = 0; i < pl.tagCount(); i++) playlist.add(pl.getStringTagAt(i));
        }
        shuffle = tag.getBoolean("shuffle");
        playlistIndex = tag.hasKey("playlistIndex") ? tag.getInteger("playlistIndex") : -1;
        sourceDirty = true;
        if (tag.hasKey("modulation")) {
            try {
                modulation = Modulation.fromCode(tag.getInteger("modulation"));
            } catch (IllegalArgumentException e) {
                modulation = Modulation.NARROW_FM;
            }
        }
    }

    @Override
    public Packet getDescriptionPacket() {
        NBTTagCompound tag = new NBTTagCompound();
        writeToNBT(tag);
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 0, tag);
    }

    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
        readFromNBT(pkt.func_148857_g());
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (worldObj != null && !worldObj.isRemote) {
            ChunkLoadManager.release(worldObj, xCoord, yCoord, zCoord);
        }
    }
}
