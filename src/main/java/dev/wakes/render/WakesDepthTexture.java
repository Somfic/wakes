package dev.wakes.render;

import dev.wakes.Wakes;
import dev.wakes.wave.WakesDepth;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Single-channel R8 texture holding water depth values in blocks (capped at 127),
 * centered on the player and refreshed when the player crosses cells. Sampled in
 * the wave shader to scale wave amplitude per-vertex by local depth, so shorelines
 * stay calm even when the player is in deep water far away.
 *
 * Lives in OpenGL state for the lifetime of the client. Single-threaded — only
 * touched from the render thread.
 */
public final class WakesDepthTexture {

    /** Texture resolution per axis. */
    public static final int SIZE = 64;

    /** World blocks per texel. SIZE × CELL = total coverage in blocks. */
    public static final int CELL = 8;

    /** Total side length in blocks. */
    public static final int RANGE = SIZE * CELL;

    /** Re-rasterize only when the player has moved this many blocks. The texture
     *  covers RANGE × RANGE blocks centred on the player; as long as they stay
     *  inside their cell-of-cells, sampled depths don't change. Bigger threshold =
     *  less work but stale shoreline edge nearer to player. */
    private static final int REFRESH_DISTANCE = CELL * 4;   // 32 blocks

    /** Texture unit slot. Sodium uses 0 (block) and 1 (light); 4 is well clear. */
    public static final int TEXTURE_UNIT = 4;

    private static int textureId = -1;
    private static double lastOriginX = Double.NaN;
    private static double lastOriginZ = Double.NaN;
    private static final ByteBuffer pixelBuffer = ByteBuffer.allocateDirect(SIZE * SIZE).order(ByteOrder.nativeOrder());

    private WakesDepthTexture() {}

    /** World-space XZ of the texture's lower-left corner. Updated on each refresh. */
    public static double originX() { return lastOriginX; }
    public static double originZ() { return lastOriginZ; }

    public static int id() {
        if (textureId == -1) {
            textureId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            // Initialize empty so the texture is valid even before first refresh.
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, SIZE, SIZE, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        }
        return textureId;
    }

    /**
     * If the player has moved enough since last refresh, re-rasterize the depth
     * map centered on the player and upload to GPU. Cheap when no refresh needed
     * (just a distance check).
     */
    public static void refreshIfNeeded() {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null || mc.player == null) return;

        double px = mc.player.getX();
        double pz = mc.player.getZ();

        // Origin = lower-left corner of the texture in world coords.
        double newOriginX = Math.floor(px - RANGE * 0.5);
        double newOriginZ = Math.floor(pz - RANGE * 0.5);

        if (Double.isFinite(lastOriginX)
            && Math.abs(newOriginX - lastOriginX) < REFRESH_DISTANCE
            && Math.abs(newOriginZ - lastOriginZ) < REFRESH_DISTANCE) {
            return;   // close enough to last refresh, skip
        }

        try {
            rasterizeAndUpload(level, newOriginX, newOriginZ);
            lastOriginX = newOriginX;
            lastOriginZ = newOriginZ;
        } catch (Throwable t) {
            Wakes.LOG.warn("Failed to refresh wake depth texture", t);
        }
    }

    private static void rasterizeAndUpload(Level level, double originX, double originZ) {
        pixelBuffer.clear();
        for (int j = 0; j < SIZE; j++) {
            for (int i = 0; i < SIZE; i++) {
                // Sample at the centre of each cell in world coords.
                double x = originX + (i + 0.5) * CELL;
                double z = originZ + (j + 0.5) * CELL;
                // factorAt returns 0..1; remap to byte [0, 255].
                float factor = WakesDepth.factorAt(level, x, z);
                int byteVal = Math.max(0, Math.min(255, Math.round(factor * 255f)));
                pixelBuffer.put((byte) byteVal);
            }
        }
        pixelBuffer.flip();

        int prevBound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id());
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, SIZE, SIZE,
                GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, pixelBuffer);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevBound);
    }
}
