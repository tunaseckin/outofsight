package lab.aclab.shield;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sahte gomulu sandik konumlari uretir.
 *
 * <p>Gizleme "bulamazsin" der; tuzak "buldugunu sandigin sey de yalan" der.
 * Ikisi birlikte, us arayanin elindeki veriyi kullanilamaz hale getirir.
 *
 * <p>Tuzak dogasi geregi yalnizca hileciye gorunur: tasin icine gomulu bir
 * sandigi mesru oyuncu goremez, cunku gorus hatti yoktur. Hile ise yuklu tum
 * chunk'lari birden okur. Savunmanin dayandigi asimetri budur - mesru oyun
 * yereldir, hile kureseldir.
 *
 * <p>Konumlar chunk koordinatindan deterministik uretilir. Rastgele uretilseydi
 * ayni chunk her gonderildiginde tuzak yer degistirir, bu da hem titreme
 * yaratir hem de sunucunun veri uydurdugunu dogrudan ele verirdi.
 */
public final class DecoyService {

    private final int perChunk;
    private final long seed;

    /**
     * Sandik block entity'sinin kayit numarasi.
     *
     * <p>Sabit yazmak yerine gercek paketlerden ogrenilir: bu numara surum
     * arasinda degisir ve yanlis deger sessizce bozuk paket uretirdi. Ogrenene
     * kadar tuzak konmaz.
     */
    private final AtomicInteger chestTypeId = new AtomicInteger(-1);

    /**
     * Her kacinci chunk'a tuzak konacagi.
     *
     * <p>Her chunk'a koymak gereksiz: hilecinin verisini kullanilamaz kilmak icin
     * tuzaklarin seyrek olmasi yeter. Buna karsilik her chunk'a koymak, her
     * paketin yeniden serialize edilmesi demektir - asil maliyet oradadir.
     */
    private final int chunkInterval;

    public DecoyService(int perChunk, long seed, int configuredChestType, int chunkInterval) {
        this.perChunk = perChunk;
        this.seed = seed;
        this.chunkInterval = Math.max(1, chunkInterval);
        if (configuredChestType >= 0) {
            chestTypeId.set(configuredChestType);
        }
    }

    public boolean enabled() {
        return perChunk > 0;
    }

    public boolean ready() {
        return chestTypeId.get() >= 0;
    }

    public int chestTypeId() {
        return chestTypeId.get();
    }

    /** Gercek bir sandik block entity'si gorulunce numarasini ogrenir. */
    public void learnChestType(int typeId) {
        chestTypeId.compareAndSet(-1, typeId);
    }

    /**
     * Bir chunk icin aday tuzak konumlari (chunk-yerel x, dunya y, chunk-yerel z).
     *
     * <p>Aday, cunku bu asamada blok verisine bakilmaz: konumun gercekten gomulu
     * olup olmadigini paketi elinde tutan taraf dogrular.
     */
    /** Bu chunk tuzak tasiyacak mi? Deterministik, yani her gonderimde ayni. */
    public boolean carriesDecoys(int chunkX, int chunkZ) {
        if (!enabled()) {
            return false;
        }
        long h = seed ^ (chunkX * 0x9E3779B97F4A7C15L) ^ (chunkZ * 0xC2B2AE3D27D4EB4FL);
        h ^= (h >>> 33);
        return Math.floorMod(h, chunkInterval) == 0;
    }

    public List<int[]> candidates(int chunkX, int chunkZ, int minY, int maxY) {
        if (!carriesDecoys(chunkX, chunkZ)) {
            return List.of();
        }
        Random random = new Random(seed
                + chunkX * 341873128712L
                + chunkZ * 132897987541L);

        List<int[]> out = new ArrayList<>();
        // Her tuzak icin birkac aday uret; ilk gecerli olan kullanilir.
        for (int i = 0; i < perChunk * 6; i++) {
            int lx = 2 + random.nextInt(12);
            int lz = 2 + random.nextInt(12);
            int y = minY + 8 + random.nextInt(Math.max(1, maxY - minY - 8));
            out.add(new int[]{lx, y, lz});
        }
        return out;
    }

    public int perChunk() {
        return perChunk;
    }
}
