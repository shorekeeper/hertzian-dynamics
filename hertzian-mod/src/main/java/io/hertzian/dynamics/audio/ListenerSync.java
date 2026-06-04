package io.hertzian.dynamics.audio;

import java.nio.FloatBuffer;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;

import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;

/**
 * Syncs the AL listener to the client camera each tick. Without
 * this the 3D positional rolloff has no reference: every receiver
 * source would play as if the listener stood at the world origin
 * and never moved.
 *
 * <p>
 * LWJGL2 AL_ORIENTATION takes a 6-element FloatBuffer (forward
 * vector followed by up vector), so we keep one direct buffer and
 * refill it each tick rather than allocating per call.
 *
 * <p>
 * Position: live player entity location. Orientation: forward
 * vector derived from yaw/pitch, up vector fixed at world up.
 * Velocity: not synced. AL listener-side Doppler is disabled so it
 * cannot compound with the rf-core spectrum-side Doppler the
 * propagation solver already applies.
 */
final class ListenerSync {

    /** Direct 6-float buffer, refilled each tick. */
    private final FloatBuffer orientation = BufferUtils.createFloatBuffer(6);

    void update() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        EntityLivingBase view = mc.renderViewEntity;
        if (view == null) return;
        float x = (float) view.posX;
        float y = (float) view.posY;
        float z = (float) view.posZ;
        AL10.alListener3f(AL10.AL_POSITION, x, y, z);
        // Convert yaw/pitch to a forward vector. Minecraft uses
        // degrees and a left-handed-ish convention; AL uses a
        // right-handed system. The conversion below matches the way
        // the vanilla sound engine positions sources, so our radios
        // sound consistent with vanilla sound effects.
        double yawRad = Math.toRadians(view.rotationYaw);
        double pitchRad = Math.toRadians(view.rotationPitch);
        float fx = (float) (-Math.sin(yawRad) * Math.cos(pitchRad));
        float fy = (float) -Math.sin(pitchRad);
        float fz = (float) (Math.cos(yawRad) * Math.cos(pitchRad));
        orientation.clear();
        orientation.put(fx)
            .put(fy)
            .put(fz);
        orientation.put(0f)
            .put(1f)
            .put(0f);
        orientation.flip();
        AL10.alListener(AL10.AL_ORIENTATION, orientation);
        // Doppler disabled at the AL listener level: AL doppler is
        // a per-sample frequency shift that would compound with the
        // rf-core Doppler the spectrum mixer already applies.
        AL10.alListener3f(AL10.AL_VELOCITY, 0f, 0f, 0f);
    }
}
