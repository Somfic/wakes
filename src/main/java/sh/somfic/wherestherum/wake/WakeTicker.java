package sh.somfic.wherestherum.wake;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Boat;
import sh.somfic.wherestherum.ModCompat;
import sh.somfic.wherestherum.WakesConfig;
import sh.somfic.wherestherum.wave.WakesDepth;

/**
 * Decides, once per client tick, which vessels get a wake and how much of the
 * global particle budget each one may spend. {@link WakeEmitter} does the
 * placement; this class does the culling and the accounting.
 *
 * <h2>The budget, and why it is global</h2>
 * A per-entity cap is not a cap. A harbour with twelve boats in it multiplies a
 * "reasonable" per-boat allowance by twelve and drops the frame rate exactly
 * when the scene is busiest. Everything here is therefore rationed out of one
 * pool:
 * <ul>
 *   <li>{@link #BASE_BUDGET} particles per tick at {@code density = 1.0},
 *       scaled linearly by {@link WakesConfig#WAKE_DENSITY} and hard-capped at
 *       {@link #MAX_BUDGET}. At the default density that is 16/tick = 320/s,
 *       roughly what heavy vanilla rain costs.</li>
 *   <li>{@link #MAX_SOURCES} vessels examined in depth per tick. Sources beyond
 *       that are skipped this tick and picked up on a later one; the eye cannot
 *       tell, and it bounds the worst case in a crowded port.</li>
 *   <li>{@link #RADIUS} blocks from the camera. Beyond that the particles are
 *       sub-pixel anyway.</li>
 * </ul>
 * {@code density = 0} short-circuits before any entity is touched, so turning
 * the feature down really does cost nothing rather than "costs less".
 *
 * <h2>Order of the checks</h2>
 * Deliberately cheapest-first, because this runs against every entity in the
 * render list. Field reads (removed / passenger / Y band) come before the
 * distance multiply, which comes before the velocity work, which comes before
 * {@link WakesDepth#factorAt} — the only genuinely expensive test, a column walk
 * of up to 34 block lookups. Reordering these is how this feature becomes a
 * performance bug.
 *
 * <h2>Why entity.xOld and not getDeltaMovement()</h2>
 * {@code getDeltaMovement} is meaningful for the local player, but for every
 * other entity the client receives position updates and <em>interpolates</em>
 * toward them; the delta-movement vector is frequently stale or zero. Vanilla
 * maintains {@code xOld/yOld/zOld} at the top of every {@code Entity.tick()},
 * so {@code getX() - xOld} is the distance the entity actually covered on the
 * client last tick — which is what the wake should follow — and it costs
 * nothing to read.
 */
public final class WakeTicker {

    /** Particles per tick at density 1.0. */
    private static final int BASE_BUDGET = 16;

    /** Ceiling regardless of density, so a config typo cannot melt the client. */
    private static final int MAX_BUDGET = 48;

    /** Vessels examined per tick, after the cheap culls. */
    private static final int MAX_SOURCES = 24;

    /** Camera radius for entity wakes, in blocks. */
    private static final double RADIUS = 64.0;
    private static final double RADIUS_SQ = RADIUS * RADIUS;

    /** Vertical band around sea level an entity must be in to be a candidate.
     *  Rejects everything flying, mining or standing on a hill for one double
     *  subtraction, which is why it runs first. */
    private static final double Y_BAND = 3.0;

    /** Per-vessel allowance, so one fast boat cannot drain the whole pool and
     *  leave every other vessel in the scene wakeless. */
    private static final int MAX_PER_SOURCE = 3;

    private WakeTicker() {}

    /** Called once per client tick from the event handler. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isPaused()) return;
        ClientLevel level = mc.level;
        if (level == null) return;

        if (!enabled()) return;
        double density = density();
        if (density <= 0.0) return;

        int budget = (int) Math.min(MAX_BUDGET, Math.round(BASE_BUDGET * density));
        if (budget <= 0) return;

        Entity cam = mc.getCameraEntity();
        if (cam == null) return;
        double camX = cam.getX(), camZ = cam.getZ();

        double minSpeed = minSpeed();
        RandomSource rng = level.getRandom();

        int sources = 0;
        for (Entity e : level.entitiesForRendering()) {
            if (budget <= 0 || sources >= MAX_SOURCES) break;

            // --- cheap culls, in increasing cost order ---
            if (e.isRemoved()) continue;
            // A passenger's wake is its vehicle's wake; emitting both doubles
            // the particle cost of every crewed boat for no visual gain.
            if (e.isPassenger()) continue;

            double ey = e.getY();
            if (ey < WakesDepth.SEA_LEVEL - Y_BAND || ey > WakesDepth.SEA_LEVEL + Y_BAND) continue;

            double ex = e.getX(), ez = e.getZ();
            double dx = ex - camX, dz = ez - camZ;
            if (dx * dx + dz * dz > RADIUS_SQ) continue;

            boolean isBoat = e instanceof Boat;
            // Anything that is not a boat has to actually be in the water to
            // throw foam — this is what keeps a sprinting player on a pier or a
            // chicken on a dock from leaving a wake.
            if (!isBoat && !e.isInWater()) continue;

            double vx = ex - e.xOld;
            double vz = ez - e.zOld;
            double speed = Math.sqrt(vx * vx + vz * vz);
            if (speed < minSpeed) continue;
            // A teleport or a chunk-boundary snap shows up as a huge one-tick
            // delta. Emitting on that paints a line of foam across the ocean.
            if (speed > 4.0) continue;

            sources++;

            float depth = WakesDepth.factorAt(level, ex, ez);
            if (depth <= 0.0f) continue;   // not water at all — nothing to foam

            // Particle count: faster and bigger vessels get more, but everything
            // is clamped so the pattern stays legible and the budget lasts.
            double sizeFactor = isBoat ? 1.0 : 0.5;
            int want = (int) Math.ceil(density * sizeFactor * Math.min(3.0, speed / Math.max(minSpeed, 0.01)));
            if (want < 1) want = 1;
            if (want > MAX_PER_SOURCE) want = MAX_PER_SOURCE;
            if (want > budget) want = budget;

            double halfWidth = Math.max(0.35, e.getBbWidth() * 0.5);
            double halfLength = Math.max(0.5, isBoat ? 0.8 : halfWidth);

            budget -= WakeEmitter.emit(level, rng, ex, ez, vx, vz,
                    halfWidth, halfLength, want, depth);
        }

        // Sable ships last, on purpose: they are few, they are large, and if the
        // budget has already been spent on a dozen boats right next to the
        // camera then that is the scene the player is looking at anyway.
        if (budget > 0 && ModCompat.SABLE_LOADED) {
            SableWakeSource.emitShipWakes(level, rng, cam.position(), density, minSpeed, budget);
        }
    }

    // --- config accessors -------------------------------------------------
    // WakesConfig is ModConfig.Type.CLIENT and this is client-side code, so
    // get() is normally safe. It still throws if a tick beats config load (the
    // first tick after a /reload, for instance), and a particle loop is the
    // wrong place to discover that — fall back to the declared defaults. Same
    // pattern as mixin/vanilla/CoralBlockMixin.

    private static boolean enabled() {
        try {
            return WakesConfig.WAKE_ENABLED.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    private static double density() {
        try {
            return WakesConfig.WAKE_DENSITY.get();
        } catch (IllegalStateException notLoaded) {
            return 1.0;
        }
    }

    private static double minSpeed() {
        try {
            return WakesConfig.WAKE_MIN_SPEED.get();
        } catch (IllegalStateException notLoaded) {
            return 0.04;
        }
    }
}
