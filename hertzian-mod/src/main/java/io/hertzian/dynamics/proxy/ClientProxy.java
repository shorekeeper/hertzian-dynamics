package io.hertzian.dynamics.proxy;

import net.minecraft.item.Item;
import net.minecraftforge.client.MinecraftForgeClient;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import io.hertzian.dynamics.ModBlocks;
import io.hertzian.dynamics.audio.AudioSubsystem;
import io.hertzian.dynamics.client.ClientAudioTickHandler;
import io.hertzian.dynamics.client.PttKeyHandler;
import io.hertzian.dynamics.client.render.ObjModelRegistry;
import io.hertzian.dynamics.client.render.RadioBlockItemRenderer;
import io.hertzian.dynamics.client.render.RadioBlockTESR;
import io.hertzian.dynamics.tile.*;

/**
 * Client side proxy. Hosts:
 * <ul>
 * <li>The audio subsystem and the client tick handler that
 * drives it.</li>
 * <li>The push-to-talk key binding.</li>
 * <li>Custom block rendering (TESR + IItemRenderer pair per
 * block kind, all driven by Wavefront OBJ models).</li>
 * </ul>
 *
 * <p>
 * OBJ rendering registration walks every block in the mod and
 * pairs the block's TileEntity class with a {@link RadioBlockTESR}
 * for in-world rendering, and the block's Item form with a
 * {@link RadioBlockItemRenderer} for inventory and held views.
 * Both use the same OBJ model and texture, so the silhouette is
 * identical across all views.
 *
 * <p>
 * Model kind and fallbacks
 * The model kind key matches the registry name passed to
 * {@code GameRegistry.registerBlock} in {@link ModBlocks}; the
 * registry resolves these to
 * {@code assets/hertzian/models/block/<kind>.obj} and
 * {@code assets/hertzian/textures/blocks/<kind>.png}. Two newer
 * blocks (the telegraph receiver and key) do not have a dedicated
 * mesh yet. They are bound to their own kind so a dedicated model
 * dropped in later is picked up with no code change, and a fallback
 * kind is registered so until then they borrow a stand-in mesh that
 * is visually distinct from the receiver rather than reusing the
 * receiver model.
 */
public final class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
        registerBlockRenderers();
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        PttKeyHandler.register();
        ClientAudioTickHandler.register();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new io.hertzian.dynamics.client.RadioHudOverlay());
    }

    @Override
    public void postInit(FMLPostInitializationEvent event) {
        super.postInit(event);
        AudioSubsystem.initOnce();
    }

    /**
     * Wire one TESR + IItemRenderer pair per block kind. The
     * registration runs in preInit so the bindings are in place
     * before any world starts ticking.
     */
    private static void registerBlockRenderers() {
        // Fallbacks for kinds without a dedicated model yet. The
        // telegraph receiver borrows the spectrum analyzer's instrument
        // mesh, the telegraph key borrows the microphone, both clearly
        // distinct from the plain receiver model.
        ObjModelRegistry.registerFallback("teletype", "spectrum_analyzer");
        ObjModelRegistry.registerFallback("telegraph_key", "microphone");
        ObjModelRegistry.registerFallback("rtty_terminal", "radio_receiver");
        ObjModelRegistry.registerFallback("dtmf_pad", "microphone");
        ObjModelRegistry.registerFallback("sstv_station", "spectrum_analyzer");

        bind("radio_transmitter", TileRadioTransmitter.class, ModBlocks.radioTransmitter);
        bind("radio_receiver", TileRadioReceiver.class, ModBlocks.radioReceiver);
        bind("antenna", TileAntenna.class, ModBlocks.antenna);
        bind("jammer", TileJammer.class, ModBlocks.jammer);
        bind("microphone", TileMicrophone.class, ModBlocks.microphone);
        bind("test_tone", TileTestTone.class, ModBlocks.testTone);
        bind("spectrum_analyzer", TileSpectrumAnalyzer.class, ModBlocks.spectrumAnalyzer);
        bind("relay", TileRelay.class, ModBlocks.relay);
        // Own kinds; fallback handles the missing mesh until a dedicated
        // teletype.obj / telegraph_key.obj is added.
        bind("teletype", TileTeletype.class, ModBlocks.teletype);
        bind("telegraph_key", TileTelegraphKey.class, ModBlocks.telegraphKey);
        bind("rtty_terminal", TileRttyTerminal.class, ModBlocks.rttyTerminal);
        bind("dtmf_pad", TileDtmfPad.class, ModBlocks.dtmfPad);
        bind("sstv_station", TileSstvStation.class, ModBlocks.sstvStation);
    }

    private static void bind(String kind, Class<? extends net.minecraft.tileentity.TileEntity> teClass,
        net.minecraft.block.Block block) {
        if (block == null) return;
        ClientRegistry.bindTileEntitySpecialRenderer(teClass, new RadioBlockTESR(kind));
        Item itemForm = Item.getItemFromBlock(block);
        if (itemForm != null) {
            MinecraftForgeClient.registerItemRenderer(itemForm, new RadioBlockItemRenderer(kind));
        }
    }
}
