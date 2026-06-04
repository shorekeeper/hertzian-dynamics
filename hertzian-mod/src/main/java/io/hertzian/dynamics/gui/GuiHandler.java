package io.hertzian.dynamics.gui;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import cpw.mods.fml.common.network.IGuiHandler;
import io.hertzian.dynamics.inventory.ContainerRadioGear;
import io.hertzian.dynamics.player.HertzianPlayerRadio;
import io.hertzian.dynamics.tile.TileJammer;
import io.hertzian.dynamics.tile.TileRadioReceiver;
import io.hertzian.dynamics.tile.TileRadioTransmitter;
import io.hertzian.dynamics.tile.TileSpectrumAnalyzer;

/**
 * Single GUI handler for the whole mod. Routes by {@link GuiIds}.
 *
 * <p>
 * Most GUIs carry no server-side {@code Container} state, so the server
 * side returns null for them and Forge falls through to the client-only
 * GUI dispatch. The radio gear screen is the exception: it is a real
 * inventory backed by the player's {@link HertzianPlayerRadio} property,
 * so its server side returns a {@link ContainerRadioGear}.
 */
public final class GuiHandler implements IGuiHandler {

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        // The radio gear screen is inventory-backed and needs a real
        // Container on the server side. Every other GUI in the mod is
        // client-only, so returning null lets Forge dispatch the client
        // GUI without a server container. Returning a bare Object would
        // crash NetworkRegistry.getRemoteGuiContainer, which casts to
        // Container; null short-circuits that path safely.
        if (id == GuiIds.RADIO_GEAR) {
            return new ContainerRadioGear(player, HertzianPlayerRadio.get(player));
        }
        return null;
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        switch (id) {
            case GuiIds.RECEIVER: {
                TileEntity te = world.getTileEntity(x, y, z);
                if (te instanceof TileRadioReceiver) {
                    return new GuiRadioReceiver((TileRadioReceiver) te);
                }
                return null;
            }
            case GuiIds.TRANSMITTER: {
                TileEntity te = world.getTileEntity(x, y, z);
                if (te instanceof TileRadioTransmitter) {
                    return new GuiRadioTransmitter((TileRadioTransmitter) te);
                }
                return null;
            }
            case GuiIds.JAMMER: {
                TileEntity te = world.getTileEntity(x, y, z);
                if (te instanceof TileJammer) {
                    return new GuiJammer((TileJammer) te);
                }
                return null;
            }
            case GuiIds.SPECTRUM_ANALYZER: {
                TileEntity te = world.getTileEntity(x, y, z);
                if (te instanceof TileSpectrumAnalyzer) {
                    return new GuiSpectrumAnalyzer((TileSpectrumAnalyzer) te);
                }
                return null;
            }
            case GuiIds.HANDHELD_RADIO: {
                return new GuiHandheldRadio(player);
            }
            case GuiIds.RADIO_GEAR: {
                return new GuiRadioGear(player);
            }
            case GuiIds.RELAY: {
                TileEntity te = world.getTileEntity(x, y, z);
                if (te instanceof io.hertzian.dynamics.tile.TileRelay) {
                    return new GuiRelay((io.hertzian.dynamics.tile.TileRelay) te);
                }
                return null;
            }
            case GuiIds.TELETYPE: {
                TileEntity te = world.getTileEntity(x, y, z);
                if (te instanceof io.hertzian.dynamics.tile.TileTeletype) {
                    return new GuiTeletype((io.hertzian.dynamics.tile.TileTeletype) te);
                }
                return null;
            }
            case GuiIds.TELEGRAPH_KEY: {
                TileEntity te = world.getTileEntity(x, y, z);
                if (te instanceof io.hertzian.dynamics.tile.TileTelegraphKey) {
                    return new GuiTelegraphKey((io.hertzian.dynamics.tile.TileTelegraphKey) te);
                }
                return null;
            }
            case GuiIds.RTTY: {
                TileEntity te = world.getTileEntity(x, y, z);
                if (te instanceof io.hertzian.dynamics.tile.TileRttyTerminal)
                    return new GuiRttyTerminal((io.hertzian.dynamics.tile.TileRttyTerminal) te);
                return null;
            }
            case GuiIds.DTMF: {
                TileEntity te = world.getTileEntity(x, y, z);
                if (te instanceof io.hertzian.dynamics.tile.TileDtmfPad)
                    return new GuiDtmfPad((io.hertzian.dynamics.tile.TileDtmfPad) te);
                return null;
            }
            case GuiIds.SSTV: {
                TileEntity te = world.getTileEntity(x, y, z);
                if (te instanceof io.hertzian.dynamics.tile.TileSstvStation)
                    return new GuiSstvStation((io.hertzian.dynamics.tile.TileSstvStation) te);
                return null;
            }
            default:
                return null;
        }
    }
}
