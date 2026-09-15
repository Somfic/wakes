package sh.somfic.wherestherum.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import sh.somfic.wherestherum.Wakes;
import sh.somfic.wherestherum.wake.WakeTicker;

/**
 * Drives {@link WakeTicker} once per client tick.
 *
 * Separate from {@link WakesClientEvents} on purpose: that class owns the
 * per-frame wave uniforms and the one-shot Iris reload, and it runs inside
 * {@code RenderLevelStageEvent}. Wake emission must <em>not</em> live there —
 * a render-stage handler fires once per frame, so on a 144 Hz client it would
 * emit seven times as much foam as on a 20 Hz one and the wake would change
 * density with the frame rate. Ticks are the only clock that gives the same
 * result on every machine.
 *
 * {@code ClientTickEvent.Post} rather than {@code Pre}: entity positions and
 * their {@code xOld} history are only consistent after the level has ticked, and
 * {@link WakeTicker} differences exactly those two to get velocity.
 *
 * Everything else — the pause check, the null level, the config, the budget —
 * is inside {@link WakeTicker}, so that the failure modes stay next to the code
 * that can trip them.
 */
@EventBusSubscriber(modid = Wakes.MOD_ID, value = Dist.CLIENT)
public final class WakeClientEvents {

    /** Wake emission gives up permanently after this many exceptions, so a
     *  systematic fault logs a handful of lines instead of twenty per second. */
    private static final int MAX_FAILURES = 5;
    private static int failures = 0;

    private WakeClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (failures >= MAX_FAILURES) return;
        try {
            WakeTicker.tick();
        } catch (Throwable t) {
            failures++;
            // A cosmetic particle effect must never take the client tick loop
            // down with it. Log once per occurrence and carry on; the alternative
            // is an unplayable world because foam maths divided by zero.
            Wakes.LOG.error("Wakes: wake emission failed this tick", t);
        }
    }
}
