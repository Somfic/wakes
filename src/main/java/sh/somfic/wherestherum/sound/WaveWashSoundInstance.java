package sh.somfic.wherestherum.sound;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

/**
 * The looping "wave wash" bed. One instance lives for as long as the player is
 * anywhere near open water; {@link WakesAmbientSound} nudges its target volume
 * and pitch every client tick and the sound engine re-reads both each tick.
 *
 * <h2>Why a tickable loop instead of repeated one-shots</h2>
 * A wave bed has to breathe continuously — swell does not arrive in discrete
 * events, and restarting a one-shot every second produces an audible seam plus
 * a phasing mess when two copies overlap. {@link AbstractTickableSoundInstance}
 * is the only vanilla path that lets us keep one OpenAL channel alive and
 * modulate its gain, which is exactly what "storms are louder" needs.
 *
 * <h2>Why the volume is smoothed here and not by the caller</h2>
 * The underlying driver (wave amplitude envelope, depth factor) can step
 * discontinuously — walking over a shelf edge flips the depth factor in one
 * tick, and teleporting flips it entirely. Feeding that straight to the channel
 * gain is a click. So the caller only ever sets a *target* and this class
 * exponentially approaches it, giving a ~1 s fade in both directions for free.
 * That same mechanism is what fades the loop out when the player leaves water,
 * and {@link #fadeOutAndStop()} reuses it rather than cutting the channel.
 *
 * <h2>Failure modes guarded here</h2>
 * <ul>
 *   <li>The sound engine refuses to start a sound whose computed volume is
 *       zero unless {@link #canStartSilent()} is true. We always start silent
 *       (target is applied on the first tick after {@code play}), so we must
 *       opt in — otherwise the loop is silently never created and the player
 *       hears nothing until they happen to be queued during a storm.</li>
 *   <li>{@code relative = true} plus {@link SoundInstance.Attenuation#NONE}
 *       pins the bed to the listener. A world-positioned loop would pan and
 *       doppler as the player turns, which for an all-around ocean reads as a
 *       bug. The sea is everywhere; it should not have a direction.</li>
 * </ul>
 */
public final class WaveWashSoundInstance extends AbstractTickableSoundInstance {

    /** Per-tick approach rate toward the target volume. 0.04 ≈ 1.2 s to cover
     *  95% of a step — slow enough that a depth-factor jump is inaudible, fast
     *  enough that sailing into a squall is felt within a couple of seconds. */
    private static final float VOLUME_LERP = 0.04f;

    /** Pitch moves slower still: pitch shifts are far more noticeable than gain
     *  shifts, and a fast sweep sounds like a tape warble rather than weather. */
    private static final float PITCH_LERP = 0.01f;

    private float targetVolume;
    private float targetPitch = 1.0f;

    /** Once set, the instance ignores further targets and dies at silence. */
    private boolean fadingOut;

    public WaveWashSoundInstance(SoundEvent event) {
        super(event, SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
        this.looping = true;
        this.delay = 0;
        this.volume = 0.0f;          // ramped up on the first tick
        this.pitch = 1.0f;
        this.relative = true;        // listener-locked: the ocean has no bearing
        this.attenuation = SoundInstance.Attenuation.NONE;
    }

    /**
     * @param volume post-config, post-clamp gain in [0, 1]
     * @param pitch  playback rate; below 1.0 deepens the wash for heavy seas
     */
    public void setTarget(float volume, float pitch) {
        if (this.fadingOut) return;
        this.targetVolume = Mth.clamp(volume, 0.0f, 1.0f);
        this.targetPitch = Mth.clamp(pitch, 0.5f, 2.0f);
    }

    /** Ask the loop to ramp to silence and then remove itself. Preferred over
     *  {@code SoundManager.stop} everywhere, because an abrupt channel stop on
     *  a wide-band noise bed is an audible pop. */
    public void fadeOutAndStop() {
        this.fadingOut = true;
        this.targetVolume = 0.0f;
    }

    public boolean isFadingOut() {
        return this.fadingOut;
    }

    @Override
    public boolean canStartSilent() {
        return true;   // see class javadoc — we always start at volume 0
    }

    @Override
    public void tick() {
        this.volume += (this.targetVolume - this.volume) * VOLUME_LERP;
        this.pitch += (this.targetPitch - this.pitch) * PITCH_LERP;

        // Snap the last sliver so a fade-out actually reaches zero instead of
        // asymptotically hanging around at 1e-4 forever, burning a channel.
        if (Math.abs(this.targetVolume - this.volume) < 0.002f) {
            this.volume = this.targetVolume;
        }

        if (this.fadingOut && this.volume <= 0.0f) {
            this.stop();
        }
    }
}
