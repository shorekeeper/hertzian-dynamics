package io.hertzian.dynamics.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Java-side smoke test for the FFM bindings.
 *
 * <p>
 * Disabled by default because it requires the cdylib to be
 * on disk. Set HD_NATIVE_LIB_PATH to the librf_core.so / .dll /
 * .dylib file produced by `cargo build` in rf-jni, then run
 * `./gradlew test`. The test exercises the full register/mix
 * path and asserts non-trivial sample energy.
 */
@EnabledIfEnvironmentVariable(named = "HD_NATIVE_LIB_PATH", matches = ".+")
class SmokeTest {

    @Test
    void registerEmissionAndMixOneChunk() {
        // Force the loader to use the explicit path. The internal
        // Native.ensureLoaded already accepts a Path override; we
        // route through a setter-style helper below if needed.
        System.setProperty("hd.native.path", System.getenv("HD_NATIVE_LIB_PATH"));

        try (RfCore core = RfCore.create();
            SpectrumManager manager = SpectrumManager.create(core);
            VoxelGrid grid = VoxelGrid.create(1.0f);
            MaterialTable materials = MaterialTable.createDefaults();
            Ionosphere iono = Ionosphere.create(Ionosphere.SolarActivity.MEDIUM)) {

            int em = manager.registerAudioEmission(
                SpectrumManager.EmissionParameters
                    .of(Modulation.NARROW_FM, 145_000_000.0, 15_000.0f, 0f, 5f, 0f, 5.0f));
            assertTrue(em > 0);

            float[] tone = new float[4096];
            for (int i = 0; i < tone.length; i++) {
                tone[i] = (float) (0.7 * Math.sin(2 * Math.PI * 1000.0 * i / 48000.0));
            }
            manager.pushAudio(em, tone);

            int rx = manager.registerReceiver(
                SpectrumManager.ReceiverParameters.of(145_000_000.0, 15_000.0f, Modulation.NARROW_FM, 50f, 5f, 0f));

            SpectrumChunk chunk = manager.mix(grid, materials, iono, rx, 4096, 0L, 12.0f);
            assertEquals(4096, chunk.sampleCount());
            double sum = 0.0;
            for (int k = 0; k < chunk.sampleCount(); k++) {
                float i = chunk.samples()[2 * k];
                float q = chunk.samples()[2 * k + 1];
                sum += Math.sqrt(i * i + q * q);
            }
            double avg = sum / chunk.sampleCount();
            assertTrue(avg > 0.05, "average magnitude too low: " + avg);
        }
    }
}
