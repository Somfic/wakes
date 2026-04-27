package dev.wakes.wave;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * Samples our own wave field at a grid of points across a contraption's footprint
 * and fits a least-squares plane through them. Returns heave (mean offset), pitch,
 * and roll in radians.
 *
 * Plane fit: solve for (a, b, c) in h = a*x + b*z + c via the normal equations
 * of the 3-column design matrix. Closed form, no iteration.
 */
public final class HullPlane {

    public static final HullPlane FLAT = new HullPlane(0, 0, 0);

    public final double heave;
    public final double pitchRad;   // rotation about world X axis (nose up/down)
    public final double rollRad;    // rotation about world Z axis (port/starboard)

    public HullPlane(double heave, double pitchRad, double rollRad) {
        this.heave = heave;
        this.pitchRad = pitchRad;
        this.rollRad = rollRad;
    }

    public boolean isFlat() {
        return heave == 0 && pitchRad == 0 && rollRad == 0;
    }

    /**
     * @param footprint world-space AABB of the contraption's hull (xz used)
     * @param time      simulation time in ticks (gametime + partialTick)
     * @param pointsPerAxis grid resolution; clamped to [1, 9]
     */
    public static HullPlane sample(Level level, AABB footprint, double time, int pointsPerAxis) {
        int n = Math.max(1, Math.min(9, pointsPerAxis));
        if (n == 1) {
            double cx = (footprint.minX + footprint.maxX) * 0.5;
            double cz = (footprint.minZ + footprint.maxZ) * 0.5;
            double h = WakesWaveFunction.waveHeight(cx, cz, time, 0.0, 1.0);
            return new HullPlane(h, 0, 0);
        }

        // Normal equations: [[Σx², Σxz, Σx],
        //                    [Σxz, Σz², Σz],
        //                    [Σx,  Σz,  N ]] * [a,b,c]ᵀ = [Σxh, Σzh, Σh]ᵀ
        // We center x,z about the footprint midpoint so a,b decouple cleanly from c.
        double cx = (footprint.minX + footprint.maxX) * 0.5;
        double cz = (footprint.minZ + footprint.maxZ) * 0.5;
        double dx = (footprint.maxX - footprint.minX) / (n - 1);
        double dz = (footprint.maxZ - footprint.minZ) / (n - 1);

        double sxx = 0, szz = 0, sxz = 0, sx = 0, sz = 0;
        double sxh = 0, szh = 0, sh = 0;
        int N = 0;

        for (int i = 0; i < n; i++) {
            double x = footprint.minX + i * dx - cx;
            double xWorld = x + cx;
            for (int j = 0; j < n; j++) {
                double z = footprint.minZ + j * dz - cz;
                double zWorld = z + cz;
                double h = WakesWaveFunction.waveHeight(xWorld, zWorld, time, 0.0, 1.0);
                sxx += x * x;
                szz += z * z;
                sxz += x * z;
                sx  += x;
                sz  += z;
                sxh += x * h;
                szh += z * h;
                sh  += h;
                N++;
            }
        }

        // With centered coords, sx == sz == 0 (exactly, on a regular grid),
        // so the system reduces to:
        //   sxx*a + sxz*b = sxh
        //   sxz*a + szz*b = szh
        //   N*c = sh
        double det = sxx * szz - sxz * sxz;
        if (det == 0) {
            return new HullPlane(sh / N, 0, 0);
        }
        double a = (sxh * szz - szh * sxz) / det;     // ∂h/∂x  (slope along world X)
        double b = (szh * sxx - sxh * sxz) / det;     // ∂h/∂z  (slope along world Z)
        double c = sh / N;                            // mean h at the centroid

        // Plane h(x,z) = a*x + b*z + c. The corresponding rotation that lifts
        // the hull onto this plane is small-angle:
        //   pitch (around X) = -∂h/∂z  (positive ∂h/∂z means stern rises)
        //   roll  (around Z) = +∂h/∂x  (positive ∂h/∂x means starboard rises)
        return new HullPlane(c, -b, a);
    }
}
