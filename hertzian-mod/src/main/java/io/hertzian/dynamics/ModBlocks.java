package io.hertzian.dynamics;

import net.minecraft.block.Block;

import cpw.mods.fml.common.registry.GameRegistry;
import io.hertzian.dynamics.block.*;
import io.hertzian.dynamics.item.ItemBlockTinted;
import io.hertzian.dynamics.tile.*;

/**
 * Block registration.
 * Transmitter, receiver, antenna section, jammer, microphone.
 * Each block ships with its own item form via {@link ItemBlockTinted}
 * so creative tab inventory rendering picks up the block icon.
 */
public final class ModBlocks {

    public static Block radioTransmitter;
    public static Block radioReceiver;
    public static Block antenna;
    public static Block jammer;
    public static Block microphone;
    public static Block testTone;
    public static Block spectrumAnalyzer;
    public static Block relay;
    public static Block teletype;
    public static Block telegraphKey;
    public static Block rttyTerminal;
    public static Block dtmfPad;
    public static Block sstvStation;

    private ModBlocks() {}

    public static void register() {
        radioTransmitter = new BlockRadioTransmitter();
        radioReceiver = new BlockRadioReceiver();
        antenna = new BlockAntenna();
        jammer = new BlockJammer();
        microphone = new BlockMicrophone();
        testTone = new BlockTestTone();
        spectrumAnalyzer = new BlockSpectrumAnalyzer();
        relay = new BlockRelay();
        teletype = new io.hertzian.dynamics.block.BlockTeletype();
        telegraphKey = new io.hertzian.dynamics.block.BlockTelegraphKey();
        rttyTerminal = new io.hertzian.dynamics.block.BlockRttyTerminal();
        dtmfPad = new io.hertzian.dynamics.block.BlockDtmfPad();
        sstvStation = new io.hertzian.dynamics.block.BlockSstvStation();

        GameRegistry.registerBlock(radioTransmitter, ItemBlockTinted.class, "radio_transmitter");
        GameRegistry.registerBlock(radioReceiver, ItemBlockTinted.class, "radio_receiver");
        GameRegistry.registerBlock(antenna, ItemBlockTinted.class, "antenna");
        GameRegistry.registerBlock(jammer, ItemBlockTinted.class, "jammer");
        GameRegistry.registerBlock(microphone, ItemBlockTinted.class, "microphone");
        GameRegistry.registerBlock(testTone, ItemBlockTinted.class, "test_tone");
        GameRegistry.registerBlock(relay, ItemBlockTinted.class, "relay");
        GameRegistry.registerBlock(teletype, ItemBlockTinted.class, "teletype");
        GameRegistry.registerBlock(telegraphKey, ItemBlockTinted.class, "telegraph_key");
        GameRegistry.registerBlock(rttyTerminal, ItemBlockTinted.class, "rtty_terminal");
        GameRegistry.registerBlock(dtmfPad, ItemBlockTinted.class, "dtmf_pad");
        GameRegistry.registerBlock(sstvStation, ItemBlockTinted.class, "sstv_station");

        GameRegistry.registerTileEntity(TileRadioTransmitter.class, "hertzian.radio_transmitter");
        GameRegistry.registerTileEntity(TileRadioReceiver.class, "hertzian.radio_receiver");
        GameRegistry.registerTileEntity(TileAntenna.class, "hertzian.antenna");
        GameRegistry.registerTileEntity(TileJammer.class, "hertzian.jammer");
        GameRegistry.registerTileEntity(TileMicrophone.class, "hertzian.microphone");
        GameRegistry.registerTileEntity(TileTestTone.class, "hertzian.test_tone");
        GameRegistry.registerBlock(spectrumAnalyzer, ItemBlockTinted.class, "spectrum_analyzer");
        GameRegistry.registerTileEntity(TileSpectrumAnalyzer.class, "hertzian.spectrum_analyzer");
        GameRegistry.registerTileEntity(io.hertzian.dynamics.tile.TileTeletype.class, "hertzian.teletype");
        GameRegistry.registerTileEntity(TileRelay.class, "hertzian.relay");
        GameRegistry.registerTileEntity(io.hertzian.dynamics.tile.TileTelegraphKey.class, "hertzian.telegraph_key");
        GameRegistry.registerTileEntity(io.hertzian.dynamics.tile.TileRttyTerminal.class, "hertzian.rtty_terminal");
        GameRegistry.registerTileEntity(io.hertzian.dynamics.tile.TileDtmfPad.class, "hertzian.dtmf_pad");
        GameRegistry.registerTileEntity(io.hertzian.dynamics.tile.TileSstvStation.class, "hertzian.sstv_station");
    }
}
