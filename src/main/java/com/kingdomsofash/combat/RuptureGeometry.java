package com.kingdomsofash.combat;

/**
 * Exact geometry of Rupture: an oriented rectangle in the horizontal plane that starts at the caster's feet,
 * extends {@code range} blocks forward and {@code width/2} blocks to each side, tested against the target's
 * real bounding box with the separating-axis theorem (not an unrestricted radius), plus a vertical window.
 *
 * <p>Minecraft yaw convention: forward = (-sin(yaw), cos(yaw)) on (x, z).</p>
 */
public final class RuptureGeometry {
    private static final double EPS = 1e-9;

    private RuptureGeometry() {
    }

    public static double[] forward(double yawDegrees) {
        double r = Math.toRadians(yawDegrees);
        return new double[]{-Math.sin(r), Math.cos(r)};
    }

    /**
     * @return true if the axis-aligned box overlaps the slash volume.
     */
    public static boolean hits(double px, double py, double pz, double yawDegrees,
                               double range, double width, double below, double above,
                               double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        // vertical window
        if (maxY < py - below - EPS || minY > py + above + EPS) return false;

        double[] f = forward(yawDegrees);
        double fx = f[0], fz = f[1];
        double lx = fz, lz = -fx; // lateral unit vector (perpendicular, right-handed on the XZ plane)
        double half = width / 2.0;

        // rectangle corners
        double[][] rect = new double[4][];
        rect[0] = new double[]{px + lx * -half, pz + lz * -half};
        rect[1] = new double[]{px + lx * half, pz + lz * half};
        rect[2] = new double[]{px + lx * half + fx * range, pz + lz * half + fz * range};
        rect[3] = new double[]{px + lx * -half + fx * range, pz + lz * -half + fz * range};
        double[][] box = {{minX, minZ}, {maxX, minZ}, {maxX, maxZ}, {minX, maxZ}};

        double[][] axes = {{1, 0}, {0, 1}, {fx, fz}, {lx, lz}};
        for (double[] a : axes) {
            double aMin = Double.POSITIVE_INFINITY, aMax = Double.NEGATIVE_INFINITY;
            double bMin = Double.POSITIVE_INFINITY, bMax = Double.NEGATIVE_INFINITY;
            for (double[] c : rect) {
                double d = c[0] * a[0] + c[1] * a[1];
                aMin = Math.min(aMin, d);
                aMax = Math.max(aMax, d);
            }
            for (double[] c : box) {
                double d = c[0] * a[0] + c[1] * a[1];
                bMin = Math.min(bMin, d);
                bMax = Math.max(bMax, d);
            }
            if (aMax < bMin - EPS || bMax < aMin - EPS) return false;
        }
        return true;
    }
}
