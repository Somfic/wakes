package sh.somfic.wherestherum.buoyancy;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import sh.somfic.wherestherum.WakesConfig;
import sh.somfic.wherestherum.wave.WakesDepth;
import sh.somfic.wherestherum.wave.WakesWaveFunction;

/**
 * Makes loose entities — boats, swimmers, drowning cows — ride the rendered
 * wave surface instead of the flat vanilla sea.
 *
 * <p>All the logic lives here rather than in
 * {@code mixin.vanilla.EntityBuoyancyMixin} so it can be read, reasoned about
 * and (if ever needed) called from somewhere else without going through a
 * mixin. The mixin is a three-line trampoline.
 *
 * <h2>Why velocity matching and not a position spring</h2>
 * The obvious implementation — "compute the wave surface Y, spring the entity
 * toward it" — is a trap here, for two separate reasons.
 *
 * <p>First, we do not know an entity's correct resting Y. A boat floats with
 * its hull straddling the surface, a swimming player with its eyes just above
 * it, a sinking mob somewhere below. Targeting an absolute Y would apply a
 * permanent bias to every entity whose natural waterline is not exactly the
 * number we picked, levitating some and drowning others.
 *
 * <p>Second, and worse, vanilla already runs its own position servo. A boat's
 * {@code floatBoat()} caches a flat {@code waterLevel} and pushes the hull back
 * toward it every tick, damping vertical velocity by 25% while submerged. A
 * spring of ours pulling toward a different Y is then two controllers with two
 * different setpoints fighting over one axis — the classic recipe for a
 * visible buzzing oscillation, which is exactly what this feature must not do.
 *
 * <p>So we never state a position target at all. Instead we compute the
 * <em>vertical velocity of the water surface under the entity</em> and nudge
 * the entity's vertical velocity a fraction of the way toward it. Vanilla
 * keeps owning where the entity sits relative to the water; we only add the
 * swell's own up-and-down motion on top. With no setpoint there is nothing to
 * fight over, and the correction is bounded by construction: the target is a
 * wave velocity of order 0.05–0.2 blocks/tick, and we move at most
 * {@link #MAX_DV} of the way there per tick.
 *
 * <h2>Why the derivative follows the entity</h2>
 * The surface velocity is taken as a total derivative, not a partial one:
 * {@code h(x + vx, z + vz, t + 1) - h(x, z, t)}. A boat sailing into a swell
 * meets crests faster than a moored one does, and sampling {@code ∂h/∂t} at a
 * fixed column would miss that entirely — the boat would bob at the wave's
 * period regardless of its own speed, which reads as wrong the moment you put
 * a sail up.
 */
public final class WaveBuoyancy {

    /**
     * Fraction of the remaining velocity error applied per tick at strength
     * 1.0. Chosen so an entity converges on the surface velocity over roughly
     * five ticks: fast enough to read as riding the wave, slow enough that the
     * incidental damping of the entity's <em>own</em> vertical motion (a player
     * swimming up, a boat settling) stays in the range of ordinary water drag.
     */
    private static final double BASE_GAIN = 0.2;

    /** Hard ceiling on the per-tick velocity change, in blocks/tick². Vanilla
     *  gravity is 0.08, so this stays visibly below "a second gravity" even
     *  with the strength slider at its maximum. The clamp is the thing that
     *  makes a mis-tuned wave function a non-event rather than a launcher. */
    private static final double MAX_DV = 0.05;

    /** Sanity clamp on the sampled surface velocity itself, blocks/tick. The
     *  wave function is shared with the GLSL and may be retuned; a nonsense
     *  value there must not turn into a nonsense force here. */
    private static final double MAX_WAVE_VELOCITY = 0.4;

    /** Half-height of the band around sea level in which we bother to look at
     *  an entity at all. Peak wave amplitude in a thunderstorm is ~7 blocks
     *  (sub + swell + chop), so ±8 covers every crest and trough while
     *  rejecting anything in a mountain lake or a cave river outright. */
    private static final int SURFACE_BAND = 8;

    private WaveBuoyancy() {}

    /**
     * Called from {@code Entity.baseTick()} for every entity in the game, every
     * tick, on both logical sides. Everything before the depth lookup is an
     * early-out; see {@link #shouldSkip}.
     */
    public static void tick(Entity entity) {
        if (shouldSkip(entity)) return;

        Level level = entity.level();
        double x = entity.getX();
        double z = entity.getZ();

        // The expensive part, deliberately last: factorAt walks the water
        // column downward block by block. Everything above this line is field
        // reads and comparisons, so the ~99% of entities that are nowhere near
        // an ocean surface never reach it.
        float depth = WakesDepth.factorAt(level, x, z);
        if (depth <= 0f) return;   // no water, or too shallow for any wave

        double strength = strength();
        if (strength <= 0.0) return;

        double t = level.getGameTime();
        float weather = Math.min(1.5f,
                level.getRainLevel(1.0f) + level.getThunderLevel(1.0f) * 0.5f);

        Vec3 v = entity.getDeltaMovement();

        // Total derivative of the surface height along the entity's own path:
        // where the water will be under it next tick, minus where it is now.
        double h0 = WakesWaveFunction.waveHeight(x, z, t, weather, depth);
        double h1 = WakesWaveFunction.waveHeight(x + v.x, z + v.z, t + 1.0, weather, depth);
        double waveVelocity = clamp(h1 - h0, -MAX_WAVE_VELOCITY, MAX_WAVE_VELOCITY);

        double gain = clamp(BASE_GAIN * strength, 0.0, 0.5);
        double dv = clamp((waveVelocity - v.y) * gain, -MAX_DV, MAX_DV);
        if (dv == 0.0) return;

        entity.setDeltaMovement(v.x, v.y + dv, v.z);
    }

    /**
     * Cheap rejection tests, ordered cheapest-first. Every one of these is a
     * field read or an instanceof; none of them touch the world.
     */
    private static boolean shouldSkip(Entity entity) {
        if (!enabled()) return true;

        // In water, but not submerged. isInWater() is a plain boolean field
        // (wasTouchingWater) refreshed by updateInWaterStateAndDoFluidPushing
        // earlier in the same baseTick, so it is both free and current here.
        // isUnderWater() additionally requires the eyes to be under, which is
        // our "at the surface" test: a diver 10 blocks down has no business
        // being yanked around by the swell, and neither does anything in air.
        if (!entity.isInWater()) return true;
        if (entity.isUnderWater()) return true;

        // Passengers are carried by their vehicle's motion; nudging them
        // separately would shear them out of the seat. The vehicle itself gets
        // the force, and Sable ships are handled by ServerSubLevelMixin, not
        // by this at all.
        if (entity.isPassenger()) return true;

        if (entity.noPhysics || entity.isSpectator()) return true;

        // Wave height is only defined around the ocean surface; reject
        // everything outside the band before doing any world lookup.
        double y = entity.getY();
        if (y < WakesDepth.SEA_LEVEL - SURFACE_BAND || y > WakesDepth.SEA_LEVEL + SURFACE_BAND) {
            return true;
        }

        if (boatsOnly() && !(entity instanceof Boat)) return true;

        // Create contraptions are Entities, but they already ride the wave field
        // through PropellerBearingContraptionEntityMixin's render transform, and
        // assembled Sable ships get real forces in ServerSubLevelMixin. Nudging
        // them here as well would stack a second, independent bob on top of the
        // one they already have — the two are computed from the same wave field
        // but at different points in the frame, so they would not even agree.
        // One wave source per vessel.
        if (entity instanceof com.simibubi.create.content.contraptions.AbstractContraptionEntity) {
            return true;
        }

        // Player movement is client-authoritative: the server's copy of a
        // player has its position overwritten by the next move packet, so a
        // server-side nudge is discarded work at best and a rubber-band at
        // worst. Let the owning client apply it and the server accept it.
        return entity instanceof Player && !entity.level().isClientSide();
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }

    // ---------------------------------------------------------------------
    // Config access
    //
    // WakesConfig is registered as ModConfig.Type.CLIENT. Entity ticking also
    // happens on the server, and on a DEDICATED server that spec is never
    // loaded — ModConfigSpec.ConfigValue.get() then throws IllegalStateException
    // rather than handing back the declared default. Single player is fine
    // (the integrated server shares the client's JVM and its loaded config),
    // which is exactly what makes this the kind of bug you ship. An exception
    // escaping here would propagate out of Entity.baseTick and take down the
    // tick loop for every entity in the world, so each read falls back to the
    // value declared in WakesConfig. Same pattern as CoralBlockMixin.
    // ---------------------------------------------------------------------

    private static boolean enabled() {
        try {
            return WakesConfig.ENTITY_BUOYANCY.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    private static double strength() {
        try {
            return WakesConfig.ENTITY_BUOYANCY_STRENGTH.get();
        } catch (IllegalStateException notLoaded) {
            return 1.0;
        }
    }

    private static boolean boatsOnly() {
        try {
            return WakesConfig.ENTITY_BUOYANCY_BOATS_ONLY.get();
        } catch (IllegalStateException notLoaded) {
            return false;
        }
    }
}
