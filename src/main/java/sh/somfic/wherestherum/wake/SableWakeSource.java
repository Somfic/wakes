package sh.somfic.wherestherum.wake;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import sh.somfic.wherestherum.Wakes;
import sh.somfic.wherestherum.wave.WakesDepth;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wakes for assembled Create Aeronautics / Sable vessels.
 *
 * <h2>Why this is a separate class</h2>
 * Every symbol in here is a Sable type. Sable is a {@code compileOnly}
 * dependency, so on an installation without it these classes do not exist and
 * touching them throws {@link NoClassDefFoundError}. Keeping them out of
 * {@link WakeTicker} means the JVM only ever has to resolve them when the
 * guarded {@code invokestatic} in {@code WakeTicker} actually executes — which
 * happens only behind {@code ModCompat.SABLE_LOADED}. Merging this back into
 * WakeTicker would crash the client the moment it verified that class.
 *
 * <h2>Why no mixin</h2>
 * Sable already exposes everything needed, read-only and public:
 * {@code SubLevelContainer.getContainer(ClientLevel)} hands back the client
 * container and {@code ClientSubLevelContainer#getAllSubLevels()} enumerates the
 * loaded ships. Nothing here writes to a SubLevel or hooks its render path, so
 * the existing {@code ClientSubLevelMixin} is untouched and no new mixin is
 * needed.
 *
 * <h2>Why the bounding box and not the pose</h2>
 * {@code logicalPose()} is expressed in Sable's own sub-level frame and its
 * relationship to world coordinates depends on the ship's plot allocation.
 * {@code boundingBox()} is unambiguously world-space — the existing
 * {@code ClientSubLevelMixin} already treats it as such when it builds an
 * {@link net.minecraft.world.phys.AABB} from it — so the box centre is the one
 * position here that cannot be misinterpreted.
 *
 * <h2>Why a private position history</h2>
 * A SubLevel is not an {@link net.minecraft.world.entity.Entity} and has no
 * {@code xOld}. {@code lastPose()} exists but is updated by Sable's own
 * bookkeeping on a schedule we do not control, so a wake driven by it would
 * stutter. Instead the box centre is remembered per ship UUID between ticks and
 * differenced here. The map is cleared on a level change, and defensively when
 * it grows past {@link #MAX_TRACKED}, which costs at most one wakeless tick.
 */
public final class SableWakeSource {

    /** Ships are large and visible from much further out than a rowing boat. */
    private static final double RADIUS = 160.0;
    private static final double RADIUS_SQ = RADIUS * RADIUS;

    /** A hull this big gets a correspondingly wider allowance than a boat. */
    private static final int MAX_PER_SHIP = 4;

    /** Ships examined per tick. */
    private static final int MAX_SHIPS = 6;

    /** If the history ever grows past this the world has changed under us in a
     *  way we did not notice; drop it rather than leak. */
    private static final int MAX_TRACKED = 64;

    private static final Map<UUID, double[]> LAST_CENTRE = new HashMap<>();
    private static ClientLevel trackedLevel = null;

    /** Set once if Sable is present but its API does not look the way we
     *  compiled against — a version bump, most likely. One warning, then we stop
     *  trying, rather than throwing on every tick forever. */
    private static boolean disabled = false;

    private SableWakeSource() {}

    /**
     * @param budget particles still unspent this tick
     * @return particles consumed
     */
    public static int emitShipWakes(ClientLevel level, RandomSource rng, Vec3 camPos,
                                    double density, double minSpeed, int budget) {
        if (disabled) return 0;
        try {
            return emitUnsafe(level, rng, camPos, density, minSpeed, budget);
        } catch (Throwable t) {
            disabled = true;
            LAST_CENTRE.clear();
            Wakes.LOG.warn("Wakes: ship wakes disabled — Sable sub-level API not as expected", t);
            return 0;
        }
    }

    private static int emitUnsafe(ClientLevel level, RandomSource rng, Vec3 camPos,
                                  double density, double minSpeed, int budget) {
        if (trackedLevel != level) {
            trackedLevel = level;
            LAST_CENTRE.clear();
        }
        if (LAST_CENTRE.size() > MAX_TRACKED) LAST_CENTRE.clear();

        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return 0;
        List<ClientSubLevel> ships = container.getAllSubLevels();
        if (ships == null || ships.isEmpty()) return 0;

        int spent = 0;
        int examined = 0;
        for (ClientSubLevel ship : ships) {
            if (spent >= budget || examined >= MAX_SHIPS) break;
            if (ship == null || ship.isRemoved()) continue;

            BoundingBox3dc bb = ship.boundingBox();
            if (bb == null) continue;

            // Vertical gate first: an airship at cruising altitude must not leave
            // foam on the sea beneath it. The hull has to straddle the waterline.
            if (bb.minY() > WakesDepth.SEA_LEVEL || bb.maxY() < WakesDepth.SEA_LEVEL - 2) continue;

            double cx = (bb.minX() + bb.maxX()) * 0.5;
            double cz = (bb.minZ() + bb.maxZ()) * 0.5;

            double dx = cx - camPos.x, dz = cz - camPos.z;
            if (dx * dx + dz * dz > RADIUS_SQ) continue;

            UUID id = ship.getUniqueId();
            if (id == null) continue;

            double[] prev = LAST_CENTRE.get(id);
            LAST_CENTRE.put(id, new double[]{cx, cz});
            if (prev == null) continue;            // first sighting: no velocity yet

            double vx = cx - prev[0];
            double vz = cz - prev[1];
            double speed = Math.sqrt(vx * vx + vz * vz);
            if (speed < minSpeed) continue;
            // Assembly, disassembly and plot reallocation can teleport the box.
            if (speed > 4.0) continue;

            examined++;

            float depth = WakesDepth.factorAt(level, cx, cz);
            if (depth <= 0.0f) continue;

            // Half-extents. Which axis is "beam" depends on heading, and the
            // hull is rarely axis-aligned anyway, so the smaller half-extent is
            // taken as the beam and the larger as the length. Clamped, because a
            // 200-block freighter would otherwise scatter its V so far behind
            // itself that it stops reading as one wake.
            double hx = (bb.maxX() - bb.minX()) * 0.5;
            double hz = (bb.maxZ() - bb.minZ()) * 0.5;
            double halfWidth = Math.min(8.0, Math.max(0.5, Math.min(hx, hz)));
            double halfLength = Math.min(16.0, Math.max(halfWidth, Math.max(hx, hz)));

            int want = (int) Math.ceil(density * Math.min(3.0, speed / Math.max(minSpeed, 0.01)));
            if (want < 1) want = 1;
            if (want > MAX_PER_SHIP) want = MAX_PER_SHIP;
            if (want > budget - spent) want = budget - spent;

            spent += WakeEmitter.emit(level, rng, cx, cz, vx, vz,
                    halfWidth, halfLength, want, depth);
        }
        return spent;
    }
}
