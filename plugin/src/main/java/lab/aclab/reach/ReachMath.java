package lab.aclab.reach;

/**
 * Reach dogrulamasinin saf matematigi. Bukkit'e bagimli degildir, bu yuzden
 * sunucu acmadan unit test edilebilir.
 */
public final class ReachMath {

    private ReachMath() {
    }

    /**
     * Bir noktadan eksen-hizali kutuya (AABB) olan en kisa mesafe.
     *
     * <p>Anticheat'te merkeze olan mesafeyi olcmek klasik bir yanlis pozitif
     * kaynagidir: uzun bir yaratigin ayagina mesru sekilde vuran oyuncu,
     * merkeze 4 blok uzakta gorunur. Vanilla da isabeti kutuya gore hesaplar,
     * dolayisiyla dogrulama da kutuya gore yapilmalidir.
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
