package io.hertzian.dynamics.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.hertzian.dynamics.audio.EnvironmentMixer;
import paulscode.sound.SoundBuffer;
import paulscode.sound.Source;
import paulscode.sound.Vector3D;

/**
 * Taps the start of every LWJGL OpenAL source so the radio's environment
 * mixer sees the same decoded PCM the game is about to play. The source
 * already carries its decoded SoundBuffer, world position and gain, so the
 * tap is a read only snapshot handed to the mixer; nothing about playback
 * is altered.
 *
 * <p>
 * Confirm the inject target against your paulscode build. The base
 * Source defines play; if SourceLWJGLOpenAL declares more than one play
 * overload, pin the descriptor in the method selector. The fields read here
 * are public on the paulscode Source in the 1.7.10 bundle.
 */
@Mixin(targets = "paulscode.sound.libraries.SourceLWJGLOpenAL", remap = false)
public abstract class MixinSourceLWJGLOpenAL {

    @Inject(method = "play", at = @At("HEAD"), require = 0, remap = false)
    private void hertzian$tap(CallbackInfo ci) {
        Source self = (Source) (Object) this;
        SoundBuffer buf = self.soundBuffer;
        if (buf == null || buf.audioData == null) return;
        Vector3D p = self.position;
        double x = p != null ? p.x : 0.0;
        double y = p != null ? p.y : 0.0;
        double z = p != null ? p.z : 0.0;
        EnvironmentMixer.get()
            .onSourcePlay(buf.audioData, buf.audioFormat, self.gain, x, y, z, self.toLoop, self.toStream);
    }
}
