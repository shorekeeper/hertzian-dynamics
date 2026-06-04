package io.hertzian.dynamics.player;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.event.entity.EntityEvent.EntityConstructing;
import net.minecraftforge.event.entity.player.PlayerEvent.Clone;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent.PlayerChangedDimensionEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent.PlayerRespawnEvent;
import io.hertzian.dynamics.net.PacketRadioGearSync;

/**
 * Lifecycle wiring for the radio gear property. Attaches the property to
 * every player on both sides, carries it across respawn, and pushes a
 * client sync on login, respawn and dimension change so the client copy
 * is populated before the player can transmit.
 *
 * The instance is registered on both the Forge event bus (entity and
 * clone events) and the FML event bus (player connection events).
 */
public final class RadioGearEvents {

    @SubscribeEvent
    public void onEntityConstructing(EntityConstructing event) {
        if (event.entity instanceof EntityPlayer) {
            if (event.entity.getExtendedProperties(HertzianPlayerRadio.ID) == null) {
                event.entity.registerExtendedProperties(HertzianPlayerRadio.ID, new HertzianPlayerRadio());
            }
        }
    }

    @SubscribeEvent
    public void onClone(Clone event) {
        // Carry the gear across respawn. Treated as kept rather than
        // dropped because it is worn equipment, not loose inventory.
        HertzianPlayerRadio oldGear = HertzianPlayerRadio.get(event.original);
        HertzianPlayerRadio newGear = HertzianPlayerRadio.get(event.entityPlayer);
        if (oldGear != null && newGear != null) {
            NBTTagCompound tag = new NBTTagCompound();
            oldGear.saveNBTData(tag);
            newGear.loadNBTData(tag);
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            PacketRadioGearSync.sendTo((EntityPlayerMP) event.player);
        }
    }

    @SubscribeEvent
    public void onRespawn(PlayerRespawnEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            PacketRadioGearSync.sendTo((EntityPlayerMP) event.player);
        }
    }

    @SubscribeEvent
    public void onChangedDimension(PlayerChangedDimensionEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            PacketRadioGearSync.sendTo((EntityPlayerMP) event.player);
        }
    }
}
