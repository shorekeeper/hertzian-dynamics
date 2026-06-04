/*
 * Hertzian Dynamics rf-core C ABI.
 *
 * Hand maintained. The Rust side in rf-jni must match every
 * declaration here byte for byte; the Java side mirrors the
 * structs in io.hertzian.dynamics.core.internal.Layouts.
 *
 * Calling convention
 * ------------------
 * Every function returns int32_t; zero on success, negative on
 * error. Out parameters are passed by pointer. Strings are
 * null-terminated UTF-8.
 *
 * Lifetimes
 * ---------
 * Every constructor that yields a void* handle has a matching
 * destructor. Handles are opaque on the caller side; never
 * dereference them directly. Mixing handles between RfCore
 * instances is undefined behaviour.
 *
 * Thread safety
 * -------------
 * One RfCore + one SpectrumManager pair is single threaded. The
 * caller is responsible for serialising calls. Many independent
 * RfCore pairs may coexist in different threads.
 */

#ifndef HD_RF_CORE_H
#define HD_RF_CORE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Current ABI revision. Bump on any breaking change. */
#define HD_ABI_VERSION 1

/* Error codes. */
#define HD_OK                  0
#define HD_ERR_NULL           -1
#define HD_ERR_INVALID_ARG    -2
#define HD_ERR_OUT_OF_RANGE   -3
#define HD_ERR_NOT_FOUND      -4
#define HD_ERR_VK_FAILED      -5
#define HD_ERR_INVALID_SHADER -6
#define HD_ERR_INVALID_STATE  -7
#define HD_ERR_PANIC          -100
#define HD_ERR_UNKNOWN        -999

/* Opaque handles. */
typedef void* hd_core_t;
typedef void* hd_manager_t;
typedef void* hd_grid_t;
typedef void* hd_materials_t;
typedef void* hd_iono_t;

/* IQ sample, layout matching rf_core::types::iq::Iq. */
typedef struct {
    float i;
    float q;
} hd_iq_t;

/* Modulation discriminants, matching rf_core::types::modulation::Modulation. */
#define HD_MOD_CW    0u
#define HD_MOD_AM    1u
#define HD_MOD_NFM   2u
#define HD_MOD_WFM   3u
#define HD_MOD_USB   4u
#define HD_MOD_LSB   5u
#define HD_MOD_NOISE 6u

/* Compute workload identifiers for hd_core_set_compute_policy and
 * hd_core_compute_stats. */
#define HD_WORKLOAD_ZOOM_DFT 0u

/* Compute backend modes for hd_core_set_compute_policy. */
#define HD_COMPUTE_MODE_AUTO 0u
#define HD_COMPUTE_MODE_CPU  1u
#define HD_COMPUTE_MODE_GPU  2u

/* Compute workload identifiers. */
#define HD_WORKLOAD_ZOOM_DFT    0u
#define HD_WORKLOAD_PROPAGATION 1u

/* Spectrum chunk header, matching rf_core::types::spectrum::SpectrumChunkHeader. */
typedef struct {
    double   center_hz;
    float    sample_rate_hz;
    float    bandwidth_hz;
    uint32_t sample_count;
    uint32_t sequence;
    uint64_t server_tick;
    float    noise_floor_w;
    float    signal_power_w;      /* pre-AGC received signal power, W */
} hd_chunk_header_t;

/*
 * Per-emission descriptor passed to register_emission.
 *
 * Layout: 64 bytes total, alignment 8. carrier_hz comes first so
 * the f64 alignment is satisfied at offset 0; everything that
 * follows is 4 byte wide, giving a dense layout with no internal
 * padding holes.
 */
typedef struct {
    double   carrier_hz;          /* offset  0 */
    uint32_t modulation;          /* offset  8: HD_MOD_*  */
    float    bandwidth_hz;        /* offset 12 */
    float    pos_x;               /* offset 16 */
    float    pos_y;               /* offset 20 */
    float    pos_z;               /* offset 24 */
    float    vel_x;               /* offset 28 */
    float    vel_y;               /* offset 32 */
    float    vel_z;               /* offset 36 */
    float    tx_power_w;          /* offset 40 */
    float    antenna_gain;        /* offset 44 */
    uint32_t pcm_capacity;        /* offset 48: ring size, ignored for jammers */
    uint32_t jam_profile;         /* offset 52: 0=audio, 1=continuous, 2=pulsed */
    float    jam_rate_hz;         /* offset 56: pulsed noise gating rate */
    float    jam_sigma;           /* offset 60: noise standard deviation */
} hd_emission_desc_t;

/* Per-receiver configuration. */
typedef struct {
    double   tuned_hz;            /* offset  0 */
    float    bandwidth_hz;        /* offset  8 */
    uint32_t modulation;          /* offset 12: HD_MOD_*  */
    float    antenna_gain;        /* offset 16 */
    float    pos_x;               /* offset 20 */
    float    pos_y;               /* offset 24 */
    float    pos_z;               /* offset 28 */
    float    vel_x;               /* offset 32 */
    float    vel_y;               /* offset 36 */
    float    vel_z;               /* offset 40 */
    float    agc_target;          /* offset 44; zero = engine default */
    float    agc_attack_seconds;  /* offset 48; zero = engine default */
    float    agc_release_seconds; /* offset 52; zero = engine default */
    float    agc_max_gain;        /* offset 56; zero = engine default */
    float    agc_min_gain;        /* offset 60; zero = engine default */
    float    noise_figure_db;     /* offset 64; receiver noise figure, dB */
    uint32_t noise_environment;   /* offset 68; 0 quiet rural, 3 city */
} hd_receiver_config_t;            /* 72 bytes total */

/* Per-workload compute dispatch counters. */
typedef struct {
    uint64_t cpu_calls;
    uint64_t gpu_calls;
    uint64_t fallback_calls;  /* GPU attempted, rejected, CPU ran instead */
    uint32_t last_backend;    /* 0 cpu, 1 gpu */
    uint32_t gpu_available;   /* 1 if a GPU path exists for this workload */
} hd_compute_stats_t;

/* ABI version probe. Returns HD_ABI_VERSION. */
int32_t hd_abi_version(uint32_t* out_version);

/* Library version string (null terminated, owned by the library). */
const char* hd_version_string(void);

/* Flags for hd_core_create_ex. */
#define HD_CORE_FLAG_ENABLE_GPU 1u

/* RfCore lifecycle. */
int32_t hd_core_create(hd_core_t* out_handle);

/* RfCore lifecycle with explicit flags. flags bit 0 (HD_CORE_FLAG_ENABLE_GPU)
 * builds the GPU compute backend; clearing it forces CPU. GPU init failure
 * is non fatal and falls back to CPU. */
int32_t hd_core_create_ex(uint32_t flags, hd_core_t* out_handle);

int32_t hd_core_destroy(hd_core_t handle);

/* Set the backend policy for one workload. mode is HD_COMPUTE_MODE_*;
 * min_work is the Auto threshold in work units (sample_count*bins for the
 * zoom DFT) and is ignored for the forced modes. Forcing GPU with no GPU
 * path falls back to CPU at call time. Selecting a backend never changes
 * the numerical result. */
int32_t hd_core_set_compute_policy(
    hd_core_t core,
    uint32_t workload,
    uint32_t mode,
    uint64_t min_work
);

/* Read per-workload dispatch counters into *out_stats. */
int32_t hd_core_compute_stats(
    hd_core_t core,
    uint32_t workload,
    hd_compute_stats_t* out_stats
);

/* Voxel grid. */
int32_t hd_grid_create(float voxel_size_m, hd_grid_t* out_handle);
int32_t hd_grid_destroy(hd_grid_t handle);
int32_t hd_grid_set_voxel(hd_grid_t handle, int32_t x, int32_t y, int32_t z, uint16_t material_id);

/* Material table. */
int32_t hd_materials_create_defaults(hd_materials_t* out_handle);
int32_t hd_materials_destroy(hd_materials_t handle);
int32_t hd_materials_register(
    hd_materials_t handle,
    uint16_t material_id,
    float atten_db_per_m_at_ref,
    float reference_frequency_hz,
    float scaling_exponent,
    float pivot_frequency_hz
);

/* Ionosphere. activity: 0 = Low, 1 = Medium, 2 = High. */
int32_t hd_iono_create(uint32_t activity, hd_iono_t* out_handle);
int32_t hd_iono_destroy(hd_iono_t handle);

/* Spectrum manager. */
int32_t hd_manager_create(hd_core_t core, hd_manager_t* out_handle);
int32_t hd_manager_destroy(hd_manager_t handle);

/* Earth curvature radio horizon. enabled: 0 off, non-zero on. Numeric
 * fields out of range leave the current value untouched. */
int32_t hd_manager_set_curvature(
    hd_manager_t handle,
    int32_t enabled,
    float earth_radius_m,
    float k_factor,
    float ground_ref_m
);

/* Emission lifecycle. Returned id is opaque; pair it with the
 * manager handle that produced it. */
int32_t hd_manager_register_emission(
    hd_manager_t handle,
    const hd_emission_desc_t* desc,
    uint32_t* out_emission_id
);
int32_t hd_manager_unregister_emission(hd_manager_t handle, uint32_t emission_id);
int32_t hd_manager_push_audio(
    hd_manager_t handle,
    uint32_t emission_id,
    const float* samples,
    uint64_t sample_count
);
int32_t hd_manager_set_emission_position(
    hd_manager_t handle,
    uint32_t emission_id,
    float x,
    float y,
    float z,
    float vx,
    float vy,
    float vz
);

/* Receiver lifecycle. */
int32_t hd_manager_register_receiver(
    hd_manager_t handle,
    const hd_receiver_config_t* config,
    uint32_t* out_receiver_id
);
int32_t hd_manager_unregister_receiver(hd_manager_t handle, uint32_t receiver_id);
int32_t hd_manager_set_receiver_position(
    hd_manager_t handle,
    uint32_t receiver_id,
    float x,
    float y,
    float z,
    float vx,
    float vy,
    float vz
);
int32_t hd_manager_set_receiver_tuning(
    hd_manager_t handle,
    uint32_t receiver_id,
    double tuned_hz,
    float bandwidth_hz
);

/* Mix one chunk into the caller-provided buffer.
 *
 * out_header is filled with the chunk header.
 * out_samples is a buffer of at least sample_count*sizeof(hd_iq_t)
 * bytes. The function does not allocate. */
int32_t hd_manager_mix_chunk(
    hd_manager_t handle,
    hd_grid_t grid,
    hd_materials_t materials,
    hd_iono_t iono,
    uint32_t receiver_id,
    uint32_t sample_count,
    uint64_t server_tick,
    float local_hour,
    hd_chunk_header_t* out_header,
    hd_iq_t* out_samples
);

/* Zoom DFT for the spectrum analyzer.
 *
 * samples: 2*sample_count interleaved f32 (I, Q, ...), the receiver
 *          baseband chunk.
 * out_db:  sample buffer of bins f32, per bin magnitude in dB.
 *
 * Runs on the GPU when the core was built with the GPU backend and the
 * request fits the pipeline buffers; otherwise on the CPU. Numerically
 * backend independent to f32 rounding. */
int32_t hd_zoom_dft(
    hd_core_t core,
    const float* samples,
    uint32_t sample_count,
    float span_hz,
    float fs_hz,
    uint32_t bins,
    float* out_db
);

/* Batched voxel absorption raycast.
 *
 * grid:      a chunked voxel grid handle (hd_grid_create).
 * materials: a material table handle.
 * queries:   8*query_count floats, packed per ray as
 *            [ox,oy,oz,_, tx,ty,tz,_].
 * out_db:    query_count floats, absorption dB per ray, capped at budget.
 *
 * Runs on the GPU when the core has the GPU raycast backend, the selected
 * policy permits it, and the grid fits the GPU mirror; otherwise on the
 * CPU. Numerically backend independent within the self-test tolerance. */
int32_t hd_raycast_batch(
    hd_core_t core,
    hd_grid_t grid,
    hd_materials_t materials,
    const float* queries,
    uint32_t query_count,
    double frequency_hz,
    float budget_db,
    float* out_db
);

#ifdef __cplusplus
}
#endif

#endif /* HD_RF_CORE_H */