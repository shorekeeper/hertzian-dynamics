# Hertzian Dynamics

A physically grounded radio simulation for Minecraft 1.7.10. Frequencies, modulation, propagation loss, terrain absorption, ionospheric skywave, Doppler shift and a thermal noise floor are computed from first principles rather than scripted. Two radios separated by a hill behave the way two radios separated by a hill behave: the link degrades with distance, closes when terrain blocks the first Fresnel zone, reopens on a shortwave band when the ionosphere supports it, and carries audible static that tracks the real signal to noise ratio.

The mod ships voice radios, broadcast stations, telegraphy (CW), RTTY, DTMF selective calling, slow-scan television, a wideband spectrum analyzer and a noise jammer. Every device tunes a real carrier frequency in hertz and selects a real modulation mode, and every device shares one simulated radio ether per world.

## Architecture

The project is a Rust workspace plus a Gradle Minecraft mod, joined across the JVM foreign function boundary.

```
hertzian-dynamics/
  rf-core/        Headless RF and DSP engine (Rust). No JNI, no Minecraft.
  rf-jni/         C ABI surface over rf-core, built as a cdylib.
  hertzian-mod/   Forge 1.7.10 mod (Java 21), calls the cdylib through FFM.
```

### rf-core

The engine knows nothing about Minecraft. It models the radio ether as a set of emissions and receivers in metric world space and produces complex baseband chunks for each receiver. Its subsystems:

- **dsp**: numerically controlled oscillators, windowed-sinc and Hilbert FIR filters, biquad and Butterworth IIR cascades, a radix-2 FFT, a zoom DFT, a Gaussian noise source, and the full set of modulators and demodulators (CW, AM, narrow and wide FM, USB, LSB, noise).
- **propagation**: Friis free-space loss, a 3D DDA voxel raycaster for material absorption, Deygout multiple knife-edge diffraction, an effective-Earth radio horizon, a diurnal ionospheric lookup table with the secant-law maximum usable frequency and ITU-R P.372 style D-layer absorption, Rayleigh multipath fading, and Doppler shift. These compose into one `PropagationSolver`.
- **spectrum**: the interference mixer that frequency-translates every reachable emission into a receiver passband, sums them, injects a physical noise floor and runs a per-receiver automatic gain control.
- **compute**: a backend selector that runs the heavy workloads (the zoom DFT, the batched absorption raycast) on a Vulkan compute device when one is present, and on the CPU otherwise. The two backends are validated against each other at startup and produce the same result to floating point rounding, so backend choice never changes the simulation.
- **vulkan**: a thin compute-only wrapper over `ash` (instance, device selection, memory, descriptor pool, pipeline cache, command submission).

### rf-jni

A flat C ABI declared in `rf-jni/include/rf_core.h`. Every exported function returns a negative error code on failure, takes opaque handles for the engine objects, and wraps its body in a panic guard so a Rust panic cannot unwind across the boundary. The struct layouts are mirrored on the Java side and checked at class load time against the C header.

### hertzian-mod

The Forge mod owns the world integration: tile entities for every radio block, the per-tick scheduler that drives them in dependency order, the voxel grid synchroniser that mirrors block changes into the engine, the network packets, the OpenAL playback path, and the instrument-panel GUIs. The engine runs server-side; demodulated PCM and analyzer frames are shipped to nearby clients.

## The physics

### Free-space loss

Path loss follows the Friis transmission equation. In decibels with distance in metres and frequency in hertz the loss is `20 log10(d) + 20 log10(f) - 147.55`, where the constant is `20 log10(4 pi / c)`. The metres-and-hertz form is used directly rather than the kilometres-and-megahertz shorthand so the engine never converts units on the hot path.

### Terrain absorption

Each occupied voxel carries a material with an attenuation in decibels per metre at a reference frequency and a power-law frequency dependence. The exponent encodes the dominant loss regime: a good conductor scales as the square root of frequency through the skin effect, a dielectric with a roughly constant loss tangent scales about linearly. A ray from transmitter to receiver is walked one voxel at a time by the Amanatides and Woo digital differential analyser, accumulating absorption until it reaches the receiver or exceeds a budget that marks the path opaque.

### Obstruction and the Fresnel zone

A single line-of-sight ray cannot decide whether terrain blocks a link, because radio diffracts. The solver casts a bundle of rays filling the first Fresnel ellipsoid, whose radius at the midpoint is `0.5 sqrt(lambda d)`, and weights their transmission toward the centre of the zone. The rays bend so they converge at both endpoints, which means a transmitter sealed inside metal blocks the entire bundle and closes the path, while a small obstacle beside the line only dims the signal as the surrounding zone stays partly clear.

### Radio horizon

A flat voxel world has no horizon, so a ground wave would otherwise run forever. The curvature model restores one analytically and adds it to the ground-wave path. Atmospheric refraction is folded in through the standard 4/3 effective-Earth radius, giving a single-antenna horizon of `sqrt(2 k Re h)`, the familiar `4.12 sqrt(h)` kilometres for an antenna height in metres. A link reaches the sum of its two antenna horizons before the bulge between them rises into the path; beyond that the loss climbs with a deep-shadow slope that scales as `f^(1/3) ae^(-2/3)`, so a higher band and a smaller world both steepen the fall. Lowering the configured Earth radius pulls the horizon in for a compact world without otherwise touching the physics.

### Ionospheric skywave

Below 30 MHz a closed ground path can reopen by reflecting off the F2 layer. A 24-entry diurnal table of the F2 critical frequency, selected by a solar-activity preset, drives the maximum usable frequency through the secant law `MUF = foF2 / cos(theta_i)` at the reflection geometry. A frequency above the MUF escapes to space and the path stays closed; below it the signal pays non-deviative D-layer absorption that follows the inverse-square frequency law `1 / (f + fL)^2` and the Chapman law in the solar zenith angle, so the lower bands close in daylight while the higher bands open. The hop geometry feeds a virtual path length back into the free-space loss.

### Noise floor

The injected noise is the full operating noise of the receiving system, not the bare thermal limit. Its power is the thermal reference `k T0 B`, about -174 dBm/Hz, scaled by the system noise factor `F_external + F_receiver - 1`. The receiver factor comes from the radio's noise figure; the external factor comes from an ITU-R P.372 man-made noise curve keyed to a site category (quiet rural through city), with the galactic background taken whenever it exceeds the man-made level. A better receiver hears a weaker signal, and the same receiver hears a higher floor in a city than in open country.

### Modulation

The modulators produce complex baseband centred on DC; carrier placement and Doppler are applied per receiver by the mixer. AM is double-sideband with a carrier at 0.8 modulation index. FM integrates the audio into a phase and is recovered by the differential `arg(z[n] conj z[n-1])` discriminator, with matched pre-emphasis at the transmitter and de-emphasis at the receiver. SSB builds the analytic signal through a windowed Hilbert FIR. CW shapes the keying with a raised-cosine envelope to suppress key clicks. FM carries a squelch gate driven by the envelope coefficient of variation, which closes to silence on an empty channel rather than passing the full-scale noise an open FM discriminator produces.

## Devices

- **Radio transmitter and receiver**: voice and data on any mode. The transmitter doubles as a broadcast station that streams `.qoa` audio files with a playlist, and force-loads its chunk while on air so a distant link stays alive.
- **Handheld radio**: a portable transceiver carried in a dedicated gear slot, with push-to-talk or voice activation, squelch, volume, sidetone and roger beep. Several models bound the band, channel grid, power steps, supported modes and receiver noise figure.
- **Antenna**: stacked sections raise the effective antenna height and add gain, 1.5 dB per section to a 12 dB cap.
- **Relay**: receives on one frequency and re-emits on another, with feedback protection and an input squelch.
- **Spectrum analyzer**: a zoom-DFT panadapter with a viridis waterfall, adaptive integration, AGC bypass for a stable noise floor, and a click-to-tune crosshair.
- **Telegraph key and receiver**: send and decode Morse, with an adaptive slicer that tracks the operator's speed.
- **RTTY terminal**: 45.45 baud Baudot with a 170 Hz mark/space shift on 2125 and 2295 Hz tones.
- **DTMF pad**: the telephone keypad with sequential-tone selective calling that drives a redstone output on a code match.
- **SSTV station**: a compact 96 by 96 sequential-RGB slow-scan mode that transmits built-in test patterns, a held map, or a top-down terrain snapshot, and decodes incoming frames line by line.
- **Test tone**: a built-in source for verification, including a UVB-76 buzzer model on 4625 kHz USB.
- **Jammer**: wideband continuous or pulsed noise that denies a band to nearby receivers.

## Building

The build needs a Rust toolchain (for the engine and the cdylib), a Java 21 toolchain (for the mod), and a Vulkan SDK that supplies a GLSL to SPIR-V compiler (`glslc` or `glslangValidator`) for the compute shaders.

The Gradle build orchestrates the native build: it invokes `cargo build --release -p rf-jni`, copies the resulting `librf_jni.so` / `librf_jni.dylib` / `rf_jni.dll` into the mod resources under `native/<os>-<arch>/`, then packages the mod.

```
cd hertzian-mod
./gradlew build        # builds the cdylib, copies it in, then builds the mod
./gradlew runClient    # launches a dev client
```

The mod targets the JVM with the foreign function and memory API, so it requires Java 21 launched with `--enable-preview --enable-native-access=ALL-UNNAMED`, or Java 22 and later. The lwjgl3ify mod that the runtime depends on already runs on a Java 21+ JVM, which is the deployment target.

The Rust engine can be built and tested on its own, independent of Minecraft. The `rf-core/examples` directory holds runnable demonstrations of each subsystem (AM and FM round trips, an FFT spectrum, free-space loss versus distance, obstacle diffraction, day-versus-night skywave, a jammer against a voice signal) that dump WAV and CSV files for offline inspection.

## System requirements

The engine runs on the CPU by default and only uses a GPU for the spectrum analyzer DFT and the batched absorption raycast, both of which fall back to the CPU automatically when no Vulkan device is present. A discrete GPU helps a server hosting several active spectrum analyzers at once and is otherwise optional. The figures below assume a small multiplayer server with a handful of active radios and one or two analyzers; a single-player world is lighter on every axis.

| Component | Minimum | Recommended |
| --- | --- | --- |
| CPU | 4 cores, e.g. Intel Core i5-4460 or AMD FX-6300 | 8 cores, e.g. Intel Core i5-12400 or AMD Ryzen 5 5600 |
| RAM | 6 GB total, 3 GB for the JVM heap | 16 GB total, 4 to 6 GB for the JVM heap |
| GPU | None; CPU fallback handles every workload | Vulkan 1.2 device, e.g. NVIDIA GTX 1650 or AMD RX 6500 XT, for offloaded analyzer and raycast |
| Storage | 500 MB for the mod, engine and a small world | 2 GB, faster disk for `.qoa` station libraries |
| Java | 21 with `--enable-preview` and `--enable-native-access=ALL-UNNAMED` | 22 or later |
| OS | 64-bit Windows, Linux or macOS with a Vulkan loader present | Same, with an up-to-date GPU driver providing the Vulkan ICD |
