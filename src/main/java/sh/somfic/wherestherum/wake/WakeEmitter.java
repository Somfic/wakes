package sh.somfic.wherestherum.wake;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import sh.somfic.wherestherum.render.WakesTime;
import sh.somfic.wherestherum.wave.WakesDepth;
import sh.somfic.wherestherum.wave.WakesWaveFunction;

/**
 * Turns "a vessel is at (x, z) moving at (vx, vz)" into foam and spray particles
 * on the water surface. Pure geometry + particle spawning; it never decides
 * <em>which</em> vessels get a wake — {@link WakeTicker} owns that, along with
 * the global per-tick particle budget that every call here draws from.
 *
 * <h2>Why a V and not a trail</h2>
 * Particles emitted straight along the travel axis read as exhaust, not as a
 * wake. A real ship wake is a Kelvin pattern: two divergent arms leaving the
 * hull at a half-angle of about 19.5 degrees regardless of speed. We reproduce
 * only that outline — each particle is placed a random distance back along one
 * of the two arms, offset sideways by {@code back * tan(19.5 deg)} — which is
 * enough for the eye to read "wake" at a glance. Reproducing the transverse
 * waves inside the V would cost an order of magnitude more particles for
 * something nobody looks at.
 *
 * <h2>Two particle types, and why these two</h2>
 * <ul>
 *   <li>{@link ParticleTypes#SPLASH} for airborne spray. It is the particle
 *       vanilla {@code Boat} itself throws off its paddles, so it already looks
 *       right at a waterline, it is short-lived (10-40 ticks) and it falls back
 *       into the sea under gravity. <b>Hazard:</b> {@code SplashParticle} only
 *       honours the xd/zd we pass when the yd we pass is exactly {@code 0.0} —
 *       any non-zero yd makes it discard our horizontal velocity and use its own
 *       random upward pop. So spray is always spawned with {@code yd == 0} and
 *       the particle supplies its own lift.</li>
 *   <li>{@link ParticleTypes#BUBBLE} for the churned foam that lingers in the
 *       water behind the hull. {@code BubbleParticle} deletes itself the moment
 *       it is not inside a water block, which is a feature here: if we misjudge
 *       the surface it silently vanishes instead of hanging in mid-air.</li>
 * </ul>
 * Rejected: {@code CLOUD} — {@code PlayerCloudParticle#tick} drags itself toward
 * the Y of any player within 2 blocks, so foam beside a boat would visibly get
 * sucked onto the pilot's feet. {@code WHITE_ASH} — its tint is 0xBAAEC2, a
 * lavender-grey, and it drifts <em>downward</em> like volcanic fallout.
 *
 * <h2>Why the two types sit at different heights</h2>
 * The wave field in this mod is a <em>shader vertex displacement</em>: the
 * visible surface moves, the actual water blocks never do. Spray is a free
 * particle, so it can and should be placed on the displaced visual surface
 * ({@link WakesWaveFunction#waveHeight}) or it will float above/below the water
 * the player can see. Foam bubbles cannot: a bubble lifted onto a +2 block wave
 * crest lands in an air block and is destroyed on its first tick. Bubbles are
 * therefore pinned inside the real water block at {@link #FOAM_Y}, below any
 * crest, and ignore the wave field entirely.
 */
public final class WakeEmitter {

    /** Top face of the topmost water block (y = 62) minus a hair, so spray
     *  spawned on a perfectly flat sea starts just at the surface. */
    private static final double WATER_TOP = WakesDepth.SEA_LEVEL - 1 + 0.9;

    /** Foam sits here, inside block y = 62, which {@link WakesDepth#factorAt}
     *  has already confirmed is water. Deliberately not wave-displaced — see
     *  the class javadoc. */
    private static final double FOAM_Y = WakesDepth.SEA_LEVEL - 1 + 0.35;

    /** tan(19.5 deg) — the Kelvin wake half-angle. */
    private static final double KELVIN_TAN = 0.354;

    /** Hard clamp on how far the wave field may move spray vertically. A storm
     *  sample can exceed 4 blocks; spray that high stops reading as a wake and
     *  starts reading as a geyser. */
    private static final double MAX_WAVE_OFFSET = 2.5;

    private WakeEmitter() {}

    /**
     * Emits up to {@code count} particles for one vessel.
     *
     * @param cx,cz       world centre of the vessel, at the waterline
     * @param vx,vz       world velocity in blocks/tick (already known to exceed
     *                    the configured minimum speed)
     * @param halfWidth   half the vessel's beam, in blocks; sets how far apart
     *                    the two arms of the V start
     * @param halfLength  half the vessel's length, in blocks; where the bow is
     * @param count       how many particles this vessel is allowed this tick
     * @param depthFactor {@link WakesDepth#factorAt} at the vessel — used both
     *                    to sample the wave field consistently with the shader
     *                    and (by the caller) to reject dry land
     * @return how many particles were actually spawned, so the caller can debit
     *         the global budget by the real number
     */
    public static int emit(ClientLevel level, RandomSource rng,
                           double cx, double cz,
                           double vx, double vz,
                           double halfWidth, double halfLength,
                           int count, float depthFactor) {
        double speed = Math.sqrt(vx * vx + vz * vz);
        if (speed <= 1.0e-4) return 0;

        double dirX = vx / speed;
        double dirZ = vz / speed;
        // Left-hand perpendicular to the travel axis.
        double perpX = -dirZ;
        double perpZ = dirX;

        // How far back the V extends. Faster vessels drag a longer wake, but cap
        // it: an unbounded trail just spreads the same particle budget thinner
        // until the pattern stops reading as anything.
        double trail = Math.min(8.0, Math.max(1.5, speed * 30.0));

        float time = WakesTime.getTime();
        float weather = WakesTime.getWeather();

        int spawned = 0;
        for (int i = 0; i < count; i++) {
            // Alternate arms deterministically rather than randomly: with only
            // 1-3 particles per vessel per tick, a coin flip regularly puts
            // several ticks' worth of foam on one side and the V never forms.
            double side = ((i & 1) == 0) ? 1.0 : -1.0;

            // Bow spray: occasionally throw a splash off the front instead of
            // the arms. Only worth it above roughly twice the minimum speed,
            // where a hull is actually pushing a bow wave.
            boolean bow = speed > 0.12 && rng.nextFloat() < 0.2f;

            double px, pz, jetX, jetZ;
            if (bow) {
                double lateral = side * halfWidth * (0.4 + rng.nextDouble() * 0.6);
                px = cx + dirX * halfLength + perpX * lateral;
                pz = cz + dirZ * halfLength + perpZ * lateral;
                // Bow spray is thrown forward and outward — it is water the hull
                // is shouldering aside, not water it left behind.
                jetX = (dirX * 0.4 + perpX * side * 0.8) * speed;
                jetZ = (dirZ * 0.4 + perpZ * side * 0.8) * speed;
            } else {
                double t = rng.nextDouble();
                double back = halfLength + t * trail;
                double lateral = side * (halfWidth * 0.7 + back * KELVIN_TAN)
                        // A little scatter so the arms look like foam rather
                        // than two ruled lines of particles.
                        + side * (rng.nextDouble() - 0.5) * 0.5;
                px = cx - dirX * back + perpX * lateral;
                pz = cz - dirZ * back + perpZ * lateral;
                // Foam on an arm spreads outward and decays with distance back,
                // which is what makes the V look like it is opening up.
                double spread = (1.0 - t) * speed;
                jetX = perpX * side * spread * 0.9 - dirX * spread * 0.2;
                jetZ = perpZ * side * spread * 0.9 - dirZ * spread * 0.2;
            }

            if (!isWaterColumn(level, px, pz)) continue;

            // Spray rides the displaced visual surface; foam does not. See the
            // class javadoc for why they cannot share a height.
            if (bow || rng.nextFloat() < 0.45f) {
                double wave = WakesWaveFunction.waveHeight(px, pz, time, weather, depthFactor);
                if (wave > MAX_WAVE_OFFSET) wave = MAX_WAVE_OFFSET;
                else if (wave < -MAX_WAVE_OFFSET) wave = -MAX_WAVE_OFFSET;
                // yd MUST be 0.0 here or SplashParticle throws our xd/zd away.
                level.addParticle(ParticleTypes.SPLASH, px, WATER_TOP + wave, pz, jetX, 0.0, jetZ);
            } else {
                level.addParticle(ParticleTypes.BUBBLE, px, FOAM_Y, pz, jetX, 0.0, jetZ);
            }
            spawned++;
        }
        return spawned;
    }

    /**
     * Cheap per-particle guard: is the emission point actually over open water?
     *
     * The caller has already established that the <em>vessel</em> is over water,
     * but the arms of the V reach up to 8 blocks away and routinely swing over a
     * jetty, a beach or a neighbouring hull. Spray erupting out of a dock is the
     * single most obvious way this feature looks broken, so this check is worth
     * the one block lookup. Only the surface block is tested — depth has already
     * been decided for the vessel as a whole.
     */
    private static boolean isWaterColumn(ClientLevel level, double x, double z) {
        BlockPos pos = BlockPos.containing(x, WakesDepth.SEA_LEVEL - 1, z);
        // Skip unloaded chunks rather than forcing a load from a particle loop.
        if (!level.hasChunkAt(pos)) return false;
        FluidState fs = level.getFluidState(pos);
        return fs.is(Fluids.WATER) || fs.is(Fluids.FLOWING_WATER);
    }
}
