package dev.wakes.render;

/**
 * Per-frame uniforms pushed into Sodium's chunk shader. Both the time scalar
 * and the camera world-position vector are needed: Sodium's vertex shader works
 * in camera-relative coordinates, so we add the camera position back to recover
 * absolute world (x, z) for wave sampling.
 *
 * Same time value is read by the CPU contraption sampler so GPU and CPU agree
 * on "now".
 */
public final class WakesTime {

    private static volatile float currentTime = 0f;
    private static volatile double camX = 0.0;
    private static volatile double camY = 0.0;
    private static volatile double camZ = 0.0;

    private WakesTime() {}

    public static void setTime(float ticks) { currentTime = ticks; }
    public static float getTime() { return currentTime; }

    public static void setCamera(double x, double y, double z) {
        camX = x; camY = y; camZ = z;
    }
    public static float getCamX() { return (float) camX; }
    public static float getCamY() { return (float) camY; }
    public static float getCamZ() { return (float) camZ; }
}
