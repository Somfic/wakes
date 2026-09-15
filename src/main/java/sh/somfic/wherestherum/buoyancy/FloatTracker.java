package sh.somfic.wherestherum.buoyancy;

import sh.somfic.wherestherum.Wakes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the buoyant blocks inside a Sable SubLevel.
 *
 * <h2>Why this exists instead of a Sable floating_material</h2>
 * Aeronautics' levitite is a {@code sable:floating_material} — a static JSON of
 * lift plus friction coefficients. Sable's {@code FloatingBlockController} turns
 * those friction terms into a {@code frictionTorque}, which is what kills a
 * vessel's rocking. There is no way to ask for "lift but no rotational drag"
 * from that JSON, and no way to make it conditional on being underwater, because
 * the material is evaluated without reference to the parent level's fluids.
 *
 * So we apply the lift ourselves, as pure point forces with no damping term of
 * any kind beyond an explicit vertical one. Rotation is left entirely alone.
 *
 * <h2>Coordinate frames</h2>
 * A SubLevel's blocks are REAL blocks in the parent ServerLevel, living out in
 * the plot region (chunks ~10000+). Positions cached here are in that global
 * plot frame, which is also the frame the mass tracker reports its centre of
 * mass in — so they are directly usable as application points for
 * {@code QueuedForceGroup.applyAndRecordPointForce}. To ask "is this block
 * underwater" we must instead transform through the SubLevel's pose to get the
 * block's true world position — see the mixin.
 *
 * <h2>Cost</h2>
 * Scanning every physics substep would be wildly too expensive, so the block
 * list is cached and refreshed on a fixed tick interval. Section-level
 * {@code maybeHas} checks let whole 16³ sections be skipped without touching
 * individual blocks, which matters because ship plots are mostly empty air.
 */
public final class FloatTracker {

    /** Blocks that generate buoyancy — the full-cube coral blocks by default. */
    public static final TagKey<Block> FLOATS = TagKey.create(
            Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("wherestherum", "floats"));

    /** Ticks between full rescans of a SubLevel's block list. Block edits during
     *  the gap (a cannonball taking out planking) are picked up on the next scan. */
    private static final int SCAN_INTERVAL_TICKS = 20;

    private static final Map<Integer, State> STATES = new ConcurrentHashMap<>();

    /** Per-SubLevel cache. Keyed by Sable's runtime id. */
    public static final class State {
        /** Plot-frame positions of blocks currently in {@link #FLOATS}. */
        private List<BlockPos> floats = List.of();

        // NEVER is a sentinel meaning "hasn't scanned yet". It must not be fed
        // through (gameTime - lastScanTick): gameTime - Long.MIN_VALUE overflows
        // long and wraps NEGATIVE, so an interval test written that way reads as
        // "not elapsed" forever and the scan never runs once. Compare against
        // NEVER explicitly instead of relying on the arithmetic.
        static final long NEVER = Long.MIN_VALUE;
        private long lastScanTick = NEVER;

        public List<BlockPos> floats() { return floats; }
        public boolean isEmpty() { return floats.isEmpty(); }
    }

    private FloatTracker() {}

    public static State get(int runtimeId) {
        return STATES.computeIfAbsent(runtimeId, k -> new State());
    }

    public static void forget(int runtimeId) {
        STATES.remove(runtimeId);
    }

    /** Refresh the cached block list if the scan interval has elapsed. */
    public static void maybeRescan(State state, ServerLevel level, long gameTime, LevelPlot plot) {
        boolean firstScan = state.lastScanTick == State.NEVER;
        if (!firstScan && gameTime - state.lastScanTick < SCAN_INTERVAL_TICKS) return;
        state.lastScanTick = gameTime;

        List<BlockPos> floats = new ArrayList<>();

        // Chunks must come from the PLOT, not level.getChunkSource(). Sable keeps
        // plot chunks in its own PlotChunkHolder map, so getChunkNow() returns null
        // for every one of them — an earlier version of this scan did exactly that
        // and silently found zero blocks, producing zero lift with no error.
        //
        // LevelPlot.getChunkHolder bounds-checks its argument against
        // [0, 1<<logSize), so getChunk() wants PLOT-LOCAL chunk coords, while
        // getChunkMin()/getChunkMax() report GLOBAL ones. We walk the local range
        // and read each chunk's real position back off the chunk itself, which
        // keeps every BlockPos we store in the global plot frame.
        ChunkPos min = plot.getChunkMin();
        ChunkPos max = plot.getChunkMax();
        int spanX = max.x - min.x;
        int spanZ = max.z - min.z;

        for (int lx = 0; lx <= spanX; lx++) {
            for (int lz = 0; lz <= spanZ; lz++) {
                LevelChunk chunk = plot.getChunk(new ChunkPos(lx, lz));
                if (chunk == null) continue;
                ChunkPos gp = chunk.getPos();
                int cx = gp.x;
                int cz = gp.z;

                LevelChunkSection[] sections = chunk.getSections();
                for (int si = 0; si < sections.length; si++) {
                    LevelChunkSection section = sections[si];
                    if (section == null || section.hasOnlyAir()) continue;
                    // Skip the whole 16^3 section unless it might hold something
                    // we care about. Ship plots are mostly air, so this is the
                    // difference between a cheap scan and a stall.
                    if (!section.maybeHas(s -> s.is(FLOATS))) continue;

                    int baseY = chunk.getMinBuildHeight() + (si << 4);
                    for (int y = 0; y < 16; y++) {
                        for (int x = 0; x < 16; x++) {
                            for (int z = 0; z < 16; z++) {
                                BlockState s = section.getBlockState(x, y, z);
                                if (!s.is(FLOATS)) continue;
                                floats.add(new BlockPos((cx << 4) + x, baseY + y, (cz << 4) + z));
                            }
                        }
                    }
                }
            }
        }

        // Log the FIRST scan unconditionally, then only on change. Logging only
        // on change made a scan that found nothing indistinguishable from a scan
        // that never ran — the exact ambiguity that hid an earlier bug here. A
        // zero now positively means "ran, found no buoyant blocks".
        if (firstScan || floats.size() != state.floats.size()) {
            Wakes.LOG.info("Wakes: sublevel buoyancy scan — {} floating block(s) (chunks {}x{})",
                    floats.size(), spanX + 1, spanZ + 1);
        }

        state.floats = floats;
    }
}
