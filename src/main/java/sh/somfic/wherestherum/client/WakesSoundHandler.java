package sh.somfic.wherestherum.client;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import sh.somfic.wherestherum.Wakes;
import sh.somfic.wherestherum.sound.WakesAmbientSound;

/**
 * Event plumbing for the ambient ocean audio. All of the actual decisions live
 * in {@link WakesAmbientSound}; this class exists only to own the subscriptions
 * so the sound logic has no dependency on the event bus and stays trivially
 * testable.
 *
 * <h2>Why a tick event and not a render event</h2>
 * The wave state is refreshed per frame by {@link WakesClientEvents}, so it is
 * tempting to drive the audio there too. That would be a mistake: the sound
 * engine itself only re-reads a ticking instance's volume once per client tick,
 * so per-frame updates buy nothing but burn wave samples at 100-300 Hz on the
 * render thread. Worse, the one-shot throttles (creaks, laps) are expressed in
 * ticks, and at a variable frame rate their rate would depend on the player's
 * GPU. {@code ClientTickEvent.Post} is both sufficient and frame-rate stable.
 *
 * <h2>Why Post and not Pre</h2>
 * Post runs after the player entity and the client level have been ticked, so
 * the position and vehicle state we read are this tick's, not last tick's. On
 * Pre the creak gradient would trail the player by one tick — inaudible, but
 * there is no reason to be wrong.
 */
@EventBusSubscriber(modid = Wakes.MOD_ID, value = Dist.CLIENT)
public final class WakesSoundHandler {

    private WakesSoundHandler() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        // Never let an audio fault take down the client tick loop — a thrown
        // exception here would crash the game over an ambience nicety. The
        // logging inside WakesAmbientSound is throttled for the same reason.
        try {
            WakesAmbientSound.tick();
        } catch (Throwable t) {
            Wakes.LOG.warn("Wakes: ambient sound tick failed, muting", t);
            try {
                WakesAmbientSound.silence();
            } catch (Throwable ignored) {
                // Nothing useful left to do; the loop will be dropped on the
                // next level change regardless.
            }
        }
    }

    /**
     * Fade the bed out when the client leaves a level.
     *
     * {@code WakesAmbientSound.tick} already notices a level identity change on
     * its own, but only on the next tick — and during a disconnect there may
     * not be one before the sound engine is torn down. Asking for the fade here
     * means the ordinary case (walking through a portal, going back to the
     * title screen) ends on a fade rather than on a hard channel stop.
     */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (!event.getLevel().isClientSide()) return;
        if (Minecraft.getInstance().level != event.getLevel()) return;
        WakesAmbientSound.silence();
    }
}
