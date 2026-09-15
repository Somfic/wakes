package sh.somfic.wherestherum.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import sh.somfic.wherestherum.Wakes;
import sh.somfic.wherestherum.WakesConfig;
import sh.somfic.wherestherum.render.WakesTime;
import sh.somfic.wherestherum.wave.WakesDepth;
import sh.somfic.wherestherum.wave.WakesWaveFunction;

/**
 * Ambient ocean audio driven by the live wave field.
 *
 * <h2>What it produces</h2>
 * <ul>
 *   <li>A continuous <b>wave wash</b> bed ({@link WaveWashSoundInstance}) whose
 *       gain tracks the local wave amplitude envelope, so a flat-calm shelf is
 *       near silent and a thunderstorm over deep water is loud.</li>
 *   <li>Occasional <b>hull creaks</b> — low-pitched wooden groans — while the
 *       player is standing on (or riding) something that the wave field is
 *       currently tilting hard.</li>
 * </ul>
 *
 * <h2>Why the amplitude envelope and not the instantaneous wave height</h2>
 * The obvious implementation — sample {@link WakesWaveFunction#waveHeight} at
 * the player and use {@code |h|} as the volume — is wrong, and audibly so. The
 * instantaneous height crosses zero twice per wave period, so the bed would
 * pump to silence several times a second even in a full gale. What we actually
 * want is the *envelope*: how big the waves around here can get. That is a pure
 * function of weather and depth, and it is already spelled out inside
 * {@code waveHeight} as the three amplitude terms. We recompute exactly those
 * terms here (see {@link #waveEnvelope}) and deliberately keep them in lock-step
 * with the wave function — if someone retunes the amplitudes there, the sound
 * must be retuned here too, or storms will stop getting louder.
 *
 * <h2>Why creaks use the wave slope</h2>
 * A vessel rocks because its bow and stern sit at different wave heights, i.e.
 * because of the surface *gradient*, not its height. Sampling the wave field a
 * few blocks either side of the player and differencing gives that gradient
 * without touching Sable's SubLevel internals, without needing the player's
 * vehicle to be a physics object at all, and without any cross-thread reads of
 * the physics state. It is an approximation — it does not know the ship's
 * heading or length — but it is robust, and for "creak when it is rough" that
 * is the right trade.
 *
 * <h2>Threading and lifetime</h2>
 * Everything here runs on the client thread from {@code ClientTickEvent.Post}
 * only. The static loop reference is the one piece of mutable state, and it is
 * defensively reset whenever the level identity changes (dimension swap,
 * disconnect, singleplayer reload), because {@code SoundEngine.stopAll} drops
 * its ticking-sound list on world unload <em>without</em> marking our instance
 * stopped — leaving a live-looking reference that would never play again. The
 * {@code isActive} watchdog below catches that same class of failure generally.
 */
public final class WakesAmbientSound {

    /** Loop bed. {@code ambient.underwater.loop} is the only vanilla asset that
     *  is a genuinely seamless, wide-band water rumble; heard from above water
     *  and pitched down slightly it reads as distant surf rather than as the
     *  submerged sound it is normally used for. */
    private static final SoundEvent WASH_LOOP = SoundEvents.AMBIENT_UNDERWATER_LOOP;

    /** Short water lap layered on top of the bed at random intervals, so the
     *  ocean is not a single unchanging drone. Vanilla uses this for water
     *  blocks, so it is already "water lapping" in the player's ear. */
    private static final SoundEvent WASH_LAP = SoundEvents.WATER_AMBIENT;

    // Timber groans are no longer a hardcoded pool here. Vanilla has no creak
    // asset, and the wooden hinge sounds that were used originally kept their
    // latch click even pitched down — they read as a door, not as a ship. The
    // sound is now chosen at runtime by WakesSoundRegistry.creak() from the
    // configurable `sound.creakSounds` list, which by default prefers the Sounds
    // mod's block.ice.stress (a sustained groan of material under strain) and
    // falls back to our own vanilla-only alias. See WakesSoundRegistry.

    /** Divisor that maps the raw amplitude envelope onto [0, 1]. Calm deep
     *  ocean lands around 0.35, a full thunderstorm saturates at 1.0. */
    private static final double ENVELOPE_FULL_SCALE = 6.5;

    /** Peak gain of the wash bed before the user's config multiplier. Kept low
     *  on purpose: this sits under the biome ambience and the rain loop all the
     *  time, and anything louder becomes fatiguing within a minute. */
    private static final float WASH_PEAK_VOLUME = 0.55f;

    /** Horizontal offset used to difference the wave field for the gradient.
     *  Roughly a quarter of the shortest swell wavelength — small enough to be
     *  a local slope, large enough not to be dominated by chop. */
    private static final double SLOPE_SAMPLE_DIST = 6.0;

    /**
     * Gradient window, in blocks of rise per block of run, mapped onto
     * [0, 1] rocking intensity.
     *
     * These are measured, not guessed: sampling the wave field over deep water
     * gives a mean gradient of ~0.04 in flat calm, ~0.08 in rain and ~0.15 in a
     * thunderstorm, peaking around 0.33. So the floor sits just above the calm
     * mean — a ship at anchor creaks only on the occasional larger set — and
     * the ceiling sits near the storm mean so heavy weather spends most of its
     * time at full intensity rather than brushing it once a minute. Retune
     * these if the wave amplitudes in { WakesWaveFunction} change, or
     * creaking will quietly stop happening.
     */
    private static final double SLOPE_MIN = 0.05;
    private static final double SLOPE_MAX = 0.22;

    /** Vertical band around sea level in which ocean audio is audible at all.
     *  Without this the bed follows the player into a cave under the seabed
     *  (the depth column above them is still water) and up to build height. */
    private static final double SURFACE_BAND = 40.0;

    /** Consecutive ticks the engine may report our live loop as inactive before
     *  we assume it was killed behind our back and re-queue it. 60 ticks is
     *  comfortably longer than the one-tick queue latency plus the 20-tick
     *  grace window {@code SoundEngine} keeps in {@code soundDeleteTime}. */
    private static final int INACTIVE_TICKS_BEFORE_RESET = 60;

    private static final RandomSource RANDOM = RandomSource.create();

    private static WaveWashSoundInstance waveLoop;
    private static ClientLevel boundLevel;
    private static int inactiveTicks;
    private static int creakCooldown;
    private static int lapCooldown;
    private static long lastWarnMs;

    private WakesAmbientSound() {}

    /** Called once per client tick. Cheap: a handful of trig-heavy wave samples
     *  and one depth column walk, all at 20 Hz. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;

        // Level identity changed (or went away): drop the old loop reference
        // rather than trying to keep it alive across the unload.
        if (level != boundLevel) {
            boundLevel = level;
            forget();
        }

        if (level == null || player == null || mc.isPaused()) {
            silence();
            return;
        }

        // Config is CLIENT-type; reading it before the file has been loaded
        // throws, which on a bad startup order would spam the log every tick.
        if (!WakesConfig.SPEC.isLoaded() || !WakesConfig.SOUND_ENABLED.get()) {
            silence();
            return;
        }

        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();

        // Depth at the player's own column rather than the camera's: the camera
        // can be swung out in third person or be up in the sky during a
        // cinematic, and the sea should follow the body, not the lens.
        float depth = WakesDepth.factorAt(level, px, pz);
        if (depth <= 0.0f) {
            silence();
            return;
        }

        // Fade out with vertical distance from the surface. Squared so the band
        // is generous near the water and collapses quickly beyond it.
        double dy = Math.abs(py - WakesDepth.SEA_LEVEL);
        float surfaceFactor = (float) Mth.clamp(1.0 - dy / SURFACE_BAND, 0.0, 1.0);
        surfaceFactor *= surfaceFactor;
        if (surfaceFactor <= 0.0f) {
            silence();
            return;
        }

        float weather = WakesTime.getWeather();
        float time = WakesTime.getTime();

        double envelope = waveEnvelope(weather, depth);
        float intensity = (float) Mth.clamp(envelope / ENVELOPE_FULL_SCALE, 0.0, 1.0);

        tickWash(mc, intensity, surfaceFactor, px, pz);
        tickCreak(mc, player, time, weather, depth, px, py, pz);
    }

    /** Fade the bed out and reset the one-shot throttles. Safe to call every
     *  tick: it is idempotent once the fade has been requested. Used both by
     *  the out-of-range paths here and by the handler on world unload. */
    public static void silence() {
        if (waveLoop != null && !waveLoop.isFadingOut()) {
            waveLoop.fadeOutAndStop();
        }
        inactiveTicks = 0;
        creakCooldown = 0;
        lapCooldown = 0;
    }

    /** Drop the reference without touching the engine. Only correct when the
     *  engine has already discarded the instance (world unload, watchdog). */
    private static void forget() {
        waveLoop = null;
        inactiveTicks = 0;
    }

    // ---------------------------------------------------------------- wash bed

    private static void tickWash(Minecraft mc, float intensity, float surfaceFactor,
                                 double px, double pz) {
        SoundManager soundManager = mc.getSoundManager();
        float configVolume = (float) (double) WakesConfig.SOUND_WAVE_VOLUME.get();

        if (waveLoop == null || waveLoop.isStopped()) {
            // isStopped() also covers our own fade-out having completed, so a
            // player who walks back to the coast gets a fresh loop.
            waveLoop = new WaveWashSoundInstance(WASH_LOOP);
            inactiveTicks = 0;
            soundManager.play(waveLoop);
        } else if (waveLoop.isFadingOut()) {
            // Was on its way out but the player came back into range before it
            // reached silence. Cheaper and smoother to start a replacement once
            // the old one dies than to try to reverse a fade mid-flight.
            return;
        } else if (!soundManager.isActive(waveLoop)) {
            // Watchdog: SoundEngine.stopAll() clears its ticking list without
            // marking instances stopped, so a resource reload or world change
            // can leave us holding a reference to a loop that will never sound
            // again. If it stays inactive long enough, assume that happened.
            if (++inactiveTicks > INACTIVE_TICKS_BEFORE_RESET) {
                warnThrottled("wave loop went inactive without stopping — re-queueing");
                forget();
                return;
            }
        } else {
            inactiveTicks = 0;
        }

        if (waveLoop == null) return;

        float volume = WASH_PEAK_VOLUME * intensity * surfaceFactor * configVolume;
        // Heavier seas sit lower: big swell is low-frequency. Also breaks up the
        // "this is the underwater loop" association for anyone who knows it.
        float pitch = Mth.lerp(intensity, 1.05f, 0.75f);
        waveLoop.setTarget(volume, pitch);

        // Sparse one-shot laps on top of the bed. Rate scales with intensity so
        // calm water gets an occasional slap and a storm gets a steady wash.
        if (volume > 0.02f && --lapCooldown <= 0) {
            lapCooldown = (int) Mth.lerp(intensity, 90.0f, 25.0f) + RANDOM.nextInt(40);
            double a = RANDOM.nextDouble() * Math.PI * 2.0;
            double r = 3.0 + RANDOM.nextDouble() * 6.0;
            soundManager.play(new SimpleSoundInstance(
                WASH_LAP, SoundSource.AMBIENT,
                Mth.clamp(0.25f * intensity * configVolume, 0.0f, 1.0f),
                0.7f + RANDOM.nextFloat() * 0.3f,
                RANDOM,
                px + Math.cos(a) * r,
                WakesDepth.SEA_LEVEL,
                pz + Math.sin(a) * r));
        }
    }

    // ------------------------------------------------------------------ creaks

    private static void tickCreak(Minecraft mc, LocalPlayer player,
                                  float time, float weather, float depth,
                                  double px, double py, double pz) {
        if (creakCooldown > 0) {
            creakCooldown--;
            return;
        }
        if (!isOnDeck(player)) {
            return;
        }

        float rock = rockIntensity(px, pz, time, weather, depth);
        if (rock <= 0.0f) {
            // Still throttle, so a calm sea does not re-run the four wave
            // samples every single tick for nothing.
            creakCooldown = 20;
            return;
        }

        float configVolume = (float) (double) WakesConfig.SOUND_CREAK_VOLUME.get();
        float volume = Mth.clamp((0.12f + 0.28f * rock) * configVolume, 0.0f, 1.0f);
        if (volume <= 0.0f) {
            creakCooldown = 40;
            return;
        }

        // Our own event, which sounds.json aliases onto the three vanilla
        // toggles Mojang describes as creaking (door, fence gate, trapdoor) —
        // including the trapdoor, which the original pool here missed. Going
        // through our event rather than the vanilla ones means subtitle users
        // read "Timbers creak" instead of "Fence Gate creaks" on a ship with no
        // fence gate, and the random pick across the pool happens in sounds.json.
        SoundEvent creak = WakesSoundRegistry.creak();
        // Well below 1.0: at natural pitch these read unmistakably as a door.
        float pitch = 0.50f + RANDOM.nextFloat() * 0.22f;
        // Scatter the source around the player so creaks come from "the ship"
        // rather than from inside their skull.
        double a = RANDOM.nextDouble() * Math.PI * 2.0;
        double r = 2.0 + RANDOM.nextDouble() * 5.0;
        mc.getSoundManager().play(new SimpleSoundInstance(
            creak, SoundSource.AMBIENT, volume, pitch, RANDOM,
            px + Math.cos(a) * r,
            py + RANDOM.nextDouble() * 2.0 - 0.5,
            pz + Math.sin(a) * r));

        // Rougher seas creak more often. Randomised so the interval never
        // becomes a metronome, which is what kills ambience faster than volume.
        creakCooldown = (int) Mth.lerp(rock, 140.0f, 45.0f) + RANDOM.nextInt(40);
    }

    /**
     * "Is the player aboard something?" — answered without reaching into Sable.
     *
     * Riding any vehicle counts outright. Otherwise we accept standing on solid
     * ground at or above sea level while over open water, which is what being
     * on a deck looks like from here: a Sable SubLevel's blocks are real blocks
     * in the parent level, so the player genuinely is {@code onGround()} on
     * them. Swimming does not count — a swimmer has no hull to creak.
     */
    private static boolean isOnDeck(LocalPlayer player) {
        if (player.getVehicle() != null) return true;
        if (player.isInWater() || player.isSwimming()) return false;
        if (!player.onGround()) return false;
        return player.getY() >= WakesDepth.SEA_LEVEL - 1;
    }

    /**
     * Local wave-surface gradient, remapped to [0, 1].
     *
     * Central differences on both horizontal axes; the magnitude of the
     * resulting 2D gradient is how steeply whatever the player is standing on
     * is being tilted right now. Four wave samples per creak check, gated
     * behind the cooldown, so this costs essentially nothing.
     */
    private static float rockIntensity(double x, double z, float time, float weather, float depth) {
        double d = SLOPE_SAMPLE_DIST;
        double hxp = WakesWaveFunction.waveHeight(x + d, z, time, weather, depth);
        double hxn = WakesWaveFunction.waveHeight(x - d, z, time, weather, depth);
        double hzp = WakesWaveFunction.waveHeight(x, z + d, time, weather, depth);
        double hzn = WakesWaveFunction.waveHeight(x, z - d, time, weather, depth);
        double sx = (hxp - hxn) / (2.0 * d);
        double sz = (hzp - hzn) / (2.0 * d);
        double slope = Math.sqrt(sx * sx + sz * sz);
        return (float) Mth.clamp((slope - SLOPE_MIN) / (SLOPE_MAX - SLOPE_MIN), 0.0, 1.0);
    }

    /**
     * Peak wave amplitude for the current conditions, in blocks.
     *
     * <b>Mirrors the amplitude terms of {@link WakesWaveFunction#waveHeight}
     * exactly</b> — sub-swell, swell and chop, each gated by the same two-stage
     * depth ramp. Kept as a separate copy rather than calling into the wave
     * function because the wave function only exposes the instantaneous sum,
     * and the sum is useless as a loudness driver (see class javadoc). If the
     * constants there are retuned, retune these in lock-step.
     */
    private static double waveEnvelope(double weather, double depth) {
        double shallowFactor = smoothstep(0.0, 0.5, depth);
        double deepFactor = smoothstep(0.5, 1.0, depth);
        double subAmp = (1.2 + weather * 1.6) * deepFactor;
        double swellAmp = (1.0 + weather * 1.4) * shallowFactor;
        double chopAmp = (0.05 + weather * 0.45) * shallowFactor;
        return subAmp + swellAmp + chopAmp;
    }

    private static double smoothstep(double edge0, double edge1, double x) {
        double t = (x - edge0) / (edge1 - edge0);
        if (t <= 0.0) return 0.0;
        if (t >= 1.0) return 1.0;
        return t * t * (3.0 - 2.0 * t);
    }

    /** Throttled log channel. The watchdog path is the only caller, and if it
     *  ever fires every few ticks (another mod stopping all ambient sounds,
     *  say) an unthrottled warn would flood the log faster than anything else
     *  here. */
    private static void warnThrottled(String message) {
        long now = System.currentTimeMillis();
        if (now - lastWarnMs < 30_000L) return;
        lastWarnMs = now;
        Wakes.LOG.warn("Wakes: {}", message);
    }
}
