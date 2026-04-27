package sh.somfic.wherestherum.client;

import sh.somfic.wherestherum.ModCompat;
import sh.somfic.wherestherum.Wakes;
import sh.somfic.wherestherum.render.WakesDepthTexture;
import sh.somfic.wherestherum.render.WakesTime;
import sh.somfic.wherestherum.wave.WakesDepth;
// Sodium imports removed — we now reload via Iris.reload() reflectively.
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Drives the wave time uniform once per render frame, so the GPU shader and the
 * CPU contraption sampler always read the same "now". Subtick interpolation
 * (gametime + partialTick) keeps the wave smooth between server ticks.
 */
@EventBusSubscriber(modid = Wakes.MOD_ID, value = Dist.CLIENT)
public final class WakesClientEvents {

    private WakesClientEvents() {}

    /** When Iris is installed but no pack is active at boot, Sodium ends up
     *  using a pre-compiled chunk shader that bypassed our patcher. Force one
     *  Sodium pipeline reload after the world has loaded so our patched
     *  block_layer_opaque.vsh actually gets compiled and bound. */
    private static boolean wakes$initialReloadDone = false;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) return;   // fires once per frame, before world geometry
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        WakesTime.setTime(level.getGameTime() + partial);
        Camera cam = event.getCamera();
        Vec3 p = cam.getPosition();
        WakesTime.setCamera(p.x, p.y, p.z);

        // Combine rain + thunder for storm intensity. Thunder bumps it past 1.0 so
        // amplitude really jumps during an actual thunderstorm.
        float rain = level.getRainLevel(partial);
        float thunder = level.getThunderLevel(partial);
        WakesTime.setWeather(Math.min(1.5f, rain + thunder * 0.5f));

        // Fallback depth at the camera position — used when a vertex falls outside
        // the depth-texture coverage range. The real per-vertex depth comes from
        // u_WakesDepthMap (rasterized & uploaded by WakesDepthTexture).
        WakesTime.setDepthFactor(WakesDepth.factorAt(level, p.x, p.z));
        WakesDepthTexture.refreshIfNeeded();
    }

    /** Trigger the one-time Iris reload from a tick handler, NOT from inside a
     *  RenderLevelStageEvent. {@code Iris.reload()} destroys the active pipeline
     *  (including its DepthTexture); doing that mid-frame leaves later passes
     *  with stale GL handles → "Tried to use a destroyed GlResource". */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (wakes$initialReloadDone) return;
        if (!ModCompat.IRIS_LOADED || !ModCompat.SODIUM_LOADED) return;
        if (Minecraft.getInstance().level == null) return;
        wakes$initialReloadDone = true;
        wakes$forceIrisReload();
    }

    /** When Iris is loaded — even without an active shader pack — its
     *  VanillaRenderingPipeline captures Sodium's chunk programs that were
     *  compiled BEFORE our shader-source mixin had a chance to patch them.
     *  Calling Iris.reload() re-creates the pipeline, which re-compiles
     *  Sodium's programs, which now go through our patcher. Equivalent to
     *  the user pressing the shader-toggle keybind once. */
    private static void wakes$forceIrisReload() {
        try {
            Class<?> irisClass = Class.forName("net.irisshaders.iris.Iris");
            irisClass.getMethod("reload").invoke(null);
            Wakes.LOG.info("Wakes: forced Iris pipeline reload to pick up wave-shader patch.");
        } catch (Throwable t) {
            Wakes.LOG.warn("Wakes: failed to force Iris reload", t);
        }
    }
}
