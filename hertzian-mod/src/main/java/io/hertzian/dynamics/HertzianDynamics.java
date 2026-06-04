package io.hertzian.dynamics;

import net.minecraftforge.common.MinecraftForge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import io.hertzian.dynamics.player.RadioGearEvents;
import io.hertzian.dynamics.proxy.CommonProxy;
import io.hertzian.dynamics.tick.RadioTickHandler;
import io.hertzian.dynamics.world.VoxelSyncListener;
import io.hertzian.dynamics.world.WorldRfState;

/**
 * Forge mod entry point. Wires the lifecycle stages:
 *
 * <ul>
 * <li>preInit: register blocks, items, tile entities.</li>
 * <li>init: install event listeners for chunk and block change
 * events, the radio gear property lifecycle, the network channel,
 * the GUI handler, and the chunk loading callback.</li>
 * <li>postInit: client audio subsystem setup happens in the proxy.</li>
 * <li>serverStarting: build per-world {@link WorldRfState}
 * instances; start the tick handler.</li>
 * <li>serverStopping: shut down the tick handler and free every
 * native handle. Failure to close handles here would leak
 * Vulkan resources between worlds in dev mode.</li>
 * </ul>
 */
@Mod(
        modid = HertzianRefs.MODID,
        name = HertzianRefs.NAME,
        version = HertzianRefs.VERSION,
        dependencies = HertzianRefs.DEPENDENCIES,
        acceptedMinecraftVersions = "[1.7.10]")
public final class HertzianDynamics {

    public static final Logger LOGGER = LogManager.getLogger(HertzianRefs.NAME);

    @Mod.Instance(HertzianRefs.MODID)
    public static HertzianDynamics INSTANCE;

    @SidedProxy(clientSide = HertzianRefs.CLIENT_PROXY, serverSide = HertzianRefs.SERVER_PROXY)
    public static CommonProxy proxy;

    /**
     * Network channel. Code fills it with concrete packet
     * registrations; the field exists now so other subsystems can
     * borrow it without forward-referencing through nulls.
     */
    public static SimpleNetworkWrapper network;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("Hertzian Dynamics preInit");
        HertzianConfig.load(event.getSuggestedConfigurationFile());
        io.hertzian.dynamics.world.StationLibrary.setDirectory(event.getModConfigurationDirectory());
        ModBlocks.register();
        ModItems.register();
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        LOGGER.info("Hertzian Dynamics init");
        io.hertzian.dynamics.net.NetworkHandler.register();
        cpw.mods.fml.common.network.NetworkRegistry.INSTANCE
                .registerGuiHandler(this, new io.hertzian.dynamics.gui.GuiHandler());
        net.minecraftforge.common.ForgeChunkManager
                .setForcedChunkLoadingCallback(this, io.hertzian.dynamics.world.ChunkLoadManager.CALLBACK);
        MinecraftForge.EVENT_BUS.register(new VoxelSyncListener());

        // Radio gear property lifecycle. The handler listens on both
        // buses: the Forge bus for entity construction and respawn cloning,
        // the FML bus for player connection events.
        RadioGearEvents gearEvents = new RadioGearEvents();
        MinecraftForge.EVENT_BUS.register(gearEvents);
        cpw.mods.fml.common.FMLCommonHandler.instance()
                .bus()
                .register(gearEvents);

        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        LOGGER.info("Hertzian Dynamics postInit");
        proxy.postInit(event);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        LOGGER.info("Server starting: bringing up rf-core");
        WorldRfState.installFor(event.getServer());
        RadioTickHandler.register();
        // Operator command for the realism neutral compute knobs.
        event.registerServerCommand(new io.hertzian.dynamics.command.CommandHertzianCompute());
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        LOGGER.info("Server stopping: tearing down rf-core");
        RadioTickHandler.unregister();
        io.hertzian.dynamics.audio.AudioSubsystem audio = io.hertzian.dynamics.audio.AudioSubsystem.get();
        if (audio != null) audio.shutdown();
        WorldRfState.disposeAll();
    }
}