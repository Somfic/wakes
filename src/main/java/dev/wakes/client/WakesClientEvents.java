package dev.wakes.client;

import dev.wakes.Wakes;
import dev.wakes.render.WakesDepthTexture;
import dev.wakes.render.WakesTime;
import dev.wakes.wave.WakesDepth;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Drives the wave time uniform once per render frame, so the GPU shader and the
 * CPU contraption sampler always read the same "now". Subtick interpolation
 * (gametime + partialTick) keeps the wave smooth between server ticks.
 */
@EventBusSubscriber(modid = Wakes.MOD_ID, value = Dist.CLIENT)
public final class WakesClientEvents {

    private WakesClientEvents() {}

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
}
