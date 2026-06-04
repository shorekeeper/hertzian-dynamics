package io.hertzian.dynamics.core;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import io.hertzian.dynamics.core.internal.Native;

/**
 * Sparse voxel grid backed by Rust's {@code ChunkedVoxelGrid}.
 * Reads return air for any unmapped voxel; writes materialise the
 * containing chunk on demand.
 */
public final class VoxelGrid implements AutoCloseable {

    private final MemorySegment handle;
    private boolean closed = false;

    private VoxelGrid(MemorySegment handle) {
        this.handle = handle;
    }

    /**
     * Allocate a fresh grid with the given voxel side length, in
     * metres. The world map's "1 block = 1 metre" convention makes
     * 1.0f the canonical value for Minecraft worlds.
     */
    public static VoxelGrid create(float voxelSizeMetres) {
        Native.ensureLoaded(null);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
            int rc = (int) Native.HD_GRID_CREATE.invokeExact(voxelSizeMetres, out);
            ErrorCode.throwIfError(rc, "hd_grid_create");
            return new VoxelGrid(out.get(ValueLayout.ADDRESS, 0L));
        } catch (HertzianException e) {
            throw e;
        } catch (Throwable t) {
            throw new HertzianException(ErrorCode.UNKNOWN, "grid create failed", t);
        }
    }

    /** Place a material id at integer voxel coordinates. */
    public void setVoxel(int x, int y, int z, int materialId) {
        checkOpen();
        if ((materialId & 0xFFFF0000) != 0) {
            throw new IllegalArgumentException("material id out of u16 range: " + materialId);
        }
        try {
            int rc = (int) Native.HD_GRID_SET_VOXEL.invokeExact(handle, x, y, z, (short) materialId);
            ErrorCode.throwIfError(rc, "hd_grid_set_voxel");
        } catch (HertzianException e) {
            throw e;
        } catch (Throwable t) {
            throw new HertzianException(ErrorCode.UNKNOWN, "grid set_voxel failed", t);
        }
    }

    MemorySegment handle() {
        checkOpen();
        return handle;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            int rc = (int) Native.HD_GRID_DESTROY.invokeExact(handle);
            ErrorCode.throwIfError(rc, "hd_grid_destroy");
        } catch (HertzianException e) {
            throw e;
        } catch (Throwable t) {
            throw new HertzianException(ErrorCode.UNKNOWN, "grid destroy failed", t);
        }
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("VoxelGrid has been closed");
    }
}
