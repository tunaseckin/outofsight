package io.github.tunaseckin.outofsight.reach;

/**
 * Pure geometry for reach validation. No Bukkit dependency, so it can be unit
 * tested without starting a server.
 */
public final class ReachMath {

    private ReachMath() {
    }

    /**
     * Shortest distance from a point to an axis-aligned bounding box.
     *
     * <p>Measuring to the centre instead is a classic source of false positives:
     * a player legitimately hitting a tall mob's feet appears four blocks from
     * its centre. Vanilla resolves hits against the box, so validation has to as
     * well.
     */
    public static double distanceToBox(double px, double py, double pz,
                                       double minX, double minY, double minZ,
                                       double maxX, double maxY, double maxZ) {
        double dx = Math.max(Math.max(minX - px, 0.0), px - maxX);
        double dy = Math.max(Math.max(minY - py, 0.0), py - maxY);
        double dz = Math.max(Math.max(minZ - pz, 0.0), pz - maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
