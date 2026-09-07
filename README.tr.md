# OutOfSight, Minecraft anticheat laboratuvarı

Yerel bir test ortamı ve iki savunma modülü. Amaç, iki farklı hile sınıfının
neden **temelden farklı** savunmalar gerektirdiğini çalışan kodla göstermek.

## Neden iki ayrı modül

| | Bilgi hileleri (X-ray, Block ESP, chunk finder) | Eylem hileleri (reach, aura, speed) |
|---|---|---|
| Ne yapar | Sunucunun **zaten gönderdiği** veriyi çizer | Sunucuya **imkansız paket** gönderir |
| Paket akışında iz | Yok, hile client'ın RAM'inde olup biter | Var, fizik çiğnenir |
| Tespit edilebilir mi | **Hayır.** Hiçbir istatistik yakalayamaz | Evet |
| Gerçek savunma | Veriyi hiç göndermemek | Ping telafili doğrulama |

"Tespit edilemiyor" iddiasının büyük kısmı ilk sütundan gelir, ve o sütun için
doğru cevap daha iyi bir dedektör değil, veriyi kesmektir.

## Kurulum

- `server/`, Paper 1.21.11 (build 132), **sadece 127.0.0.1'e bağlı**, offline mode
- `server/plugins/packetevents-spigot-2.13.0.jar`, paket erişimi
- `server/plugins/outofsight-0.1.0.jar`, bu proje
- `plugin/`, Gradle kaynak projesi

Paper'ın anti-xray'i açıldı (`config/paper-world-defaults.yml`):

```yaml
anticheat:
  anti-xray:
    enabled: true        # varsayilan: false  <- cogu sunucuda kapali
    engine-mode: 2       # sahte cevher uretir, gercekleri gurultuye gomer
    max-block-height: 128
    lava-obscures: true
```

Yedek: `config/paper-world-defaults.yml.bak`

## Modüller

### XrayAudit, sızıntı denetimi
Giden `CHUNK_DATA` paketlerini yakalar ve **dünyanın gerçek içeriğiyle** karşılaştırır.
Paketin iki ayrı kanalını da denetler:

1. **Blok state'leri**, anti-xray'in obfuscate ettiği yer
2. **Block entity listesi**, sandık/spawner konumlarını *ayrıca* bildirir; blok
   verisi karartılsa bile burada açıkta kalabilir

Ayrımlar:

| Metrik | Anlamı |
|---|---|
| tür-eşleşen sızıntı | Doğru yerde **doğru tür** → gerçek bilgi sızıntısı |
| · açıkta | Mağara duvarında; oyuncu zaten görür, kaçınılmaz |
| · gömülü | Taşın içinde; asıl açık |
| yanlış tür | Yerinde başka cevher görünüyor → obfuscation çalışmış |
| sahte | Taştan uydurulmuş cevher → gürültü |
| sinyal/gürültü | X-ray'in gördüğü cevherin yüzde kaçı gerçek |

Tür karşılaştırması şart: engine-mode 2 her gömülü cevheri listeden *rastgele
başkasıyla* değiştirir. Sadece "burada bir cevher var mı" diye bakmak %58 gibi
sahte bir sızıntı oranı üretir, ilk ölçümde tam olarak bu hataya düşüldü.

### ReachCheck, ping telafili mesafe doğrulaması
Üç bilinçli tercih, üçü de yanlış pozitifi önlemek için:

1. Mesafe hedefin **kutusuna** ölçülür, merkezine değil
2. Hem saldıran hem kurban, saldıranın gecikmesi kadar geri sarılır; aradaki
   **en yakın** an esas alınır (şüphe oyuncunun lehine)
3. Tek ihlal ceza doğurmaz, ihlaller birikir, temiz geçen sürede sönümlenir

Menzil sabit değil, oyuncunun `ENTITY_INTERACTION_RANGE` niteliğinden okunur.
Canlı testte bunun karşılığı görüldü: creative'de izin **5.03**, survival'da
**3.03**. Sabit 3.0 yazılsaydı her creative vuruşu ihlal sayılırdı.

Tüm canlı varlıkları izler (sadece oyuncuları değil), aura çoğunlukla mob'lara
vurur, ve tek kişiyle test edilebilmesi de buna bağlı.

## Çalıştırma

```
./server/run.sh
```

Minecraft 1.21.11 ile `localhost`'a bağlan:

- `/outofsight xray 16`, girer girmez dolar (sunucu görüş mesafesindeki tüm chunk'ları
  tek seferde gönderir), dolaşmaya gerek yok
- `/outofsight reachdebug`, her vuruşun ölçülen mesafesini yazar. Meşru vuruşta da
  çıktı verir; paket yolunun çalıştığını hile yazmadan doğrulamanın yolu bu
- `/outofsight reachsim 3.5`, eşiği sentetik girdiyle göster
- `/outofsight hidechest`, chunk ortasına gömülü sandık + kontrol cevheri koy
- `/outofsight shield`, kalkanı aç/kapat (deneysel)

### Başsız test istemcisi (`harness/`)

```
node relog.mjs oos_bot 5000 "outofsight hidechest"   # baglan, komut, cik
node inspect.mjs 152 54 -408                        # chunk paketi o konumda ne tasiyor
node watch.mjs 152 54 -408                         # kap sonradan teslim ediliyor mu
```

Sunucu chunk'i ancak istemci bagliyken gonderir, yani her test turu bir
cik-gir gerektirir. Bot bunu otomatiklestirir; bozuk paketi de aninda
yakalar (ayristiramazsa hata verir).

Durdurmak: `./server/stop.sh`

## Ölçülen sonuçlar (16 chunk)

```
gercek=2598  tur-eslesen=215 (acikta=115, gomulu=100)
yanlis-tur=1361   sahte=194830   block-entity=2
sinyal/gurultu=0.11%
```

X-ray kullanan biri ~196.400 "cevher" görüyor, 215'i gerçek, **913'te bir**.
Anti-xray açıkken X-ray pratikte işlevsiz.

### Ana bulgu: anti-xray sandıkları korumuyor

Kontrollü deney (`/outofsight hidechest`), aynı taş kabuğun içine, her yönden kapalı,
ikisi de Paper'ın `hidden-blocks` listesinde olan iki blok:

```
Sandik        y=58  blok=SIZDI(chest)                      block-entity=SIZDI
Elmas cevheri y=60  blok=gizlendi(deepslate_redstone_ore)  block-entity=yok
```

Sandık chunk paketine **iki kez** girer: bir blok state olarak, bir de ayrı bir
block entity listesindeki kayıt olarak. Anti-xray blok state'leri üzerinde çalışır,
dolayısıyla o liste koordinatlarla birlikte olduğu gibi çıkar.

Aynı deney, iki engine mode için ayrı ayrı (kalkan kapalı):

```
engine-mode 1
  chest        block=hidden(stone)                block-entity=LEAKED
  diamond ore  block=hidden(stone)                block-entity=none

engine-mode 2
  chest        block=LEAKED(chest)                block-entity=LEAKED
  diamond ore  block=hidden(deepslate_copper_ore) block-entity=none
```

Cevher her iki modda da gizleniyor. Sandık her iki modda da konumunu ele veriyor.
Mode 1 en azından bloğu taşa çeviriyor; cevherler için daha güçlü olan mode 2 onu
bile yapmıyor.

| Hedef | engine-mode 1 | engine-mode 2 |
|---|---|---|
| Cevherler | gizleniyor | gizleniyor (913'te 1 gerçek) |
| Sandık / spawner / varil | blok gizli, **konum sızıyor** | **ikisi de sızıyor** |

Üs bulma (Block ESP, chunk finder) tam olarak bu boşlukta çalışır.

### Bulunan gerçek açık
`budding_amethyst`, `spawner`, `barrel`, `trapped_chest` Paper'ın varsayılan
`hidden-blocks` listesinde **yok**, yani hiç gizlenmiyorlar. Listeye eklendikten
sonra `budding_amethyst` sızıntısı 25 → 2'ye düştü (kalan 2 rastgele eşleşme).

## Kalkan

`BlockEntityShield`, tamamen gömülü sandık/spawner/fırın gibi blokların konumunu
giden chunk paketinden siler: block entity kaydını atar **ve** blok state'ini
komşusuyla değiştirir. İkisi birden şart, sadece kayıt silinirse `chest` bloğu
yine sandık olarak çizilir, sadece blok değişirse hile ham listedeki konumu okur.

### Kural: varsayılan reddet

Bir kap, ana iş parçacığı "bu oyuncu bunu görebilir" diye karar verene kadar
gönderilmiyor. Karar iki şeye bakıyor: **mesafe** ve **görüş hattı**. Ağ iş
parçacığı dünyayı okuyamadığı için, paket yalnızca kendi başına
cevaplayabileceği soruyu soruyor: bu kap bu oyuncuya verildi mi?

Önceki sürüm bir kabı yalnızca altı komşusu da katıysa gizliyordu. O kural
neredeyse hiçbir şeyi korumuyordu: açılabilen her sandığın üstünde hava vardır,
dolayısıyla hiçbir zaman "gömülü" sayılmaz. O test şimdi sadece ucuz bir ön
eleme: taşa gömülü blokta ışın hesabı hiç yapılmıyor.

Işın yalnızca henüz teslim edilmemiş kaplar için atılıyor, ve yeri değişmemiş bir
oyuncu değişmemiş bir dünyada tamamen atlanıyor.

### Neden dizin, neden paket değil

Gömülülük kararı ana iş parçacığında tutulan bir dizinden (`HiddenIndex`) okunur,
paketin kendisinden değil. İki sebep:

1. Paket tek bir chunk sütunu taşır; kenardaki bloğun komşuları elde olmaz ve
   konumların ~%23'ü karar dışı kalır.
2. Paketten okunan karar yalnızca paket gönderilirken geçerlidir. Oyuncu duvarı
   kırdığında sandığın artık görünür olduğunu haber verecek kimse olmaz.

Dizin ana iş parçacığında yazılır (dünya orada okunabilir), ağ iş parçacığında
okunur.

### Ortaya çıkarma

Gizlemek tek başına oyunu bozar: oyuncu duvarı kırıp sandığa ulaştığında sunucu
yalnızca kırılan bloğun güncellemesini gönderir, sandık istemcide taş kalır.
Bu yüzden blok her değiştiğinde komşuları yeniden değerlendirilir; görünür olan
konum dizinden düşürülür ve yakındaki oyunculara gerçek blok gönderilir.

Olaylar her değişikliği yakalayamaz, komut, WorldEdit, piston ve akan su hiçbir
`BlockBreakEvent` üretmez. Bu yüzden olaylara ek olarak periyodik bir tarama
çalışır ve **simetriktir**: hem yeni gömülenleri gizler hem açılanları ortaya
çıkarır. Yanlışlıkla gizli kalan bir sandık, oyuncunun eşyasını kaybetmesi
demektir; güvenlik ağı bunun içindir.

### Doğrulama (istemci gözüyle, `harness/`)

```
chunk ortasindaki sandik   -> GIZLENDI
chunk kosesindeki sandik   -> GIZLENDI   (kenar durumu)
duvar kirilinca            -> ORTAYA CIKTI
sonrasinda                 -> gorunur kaliyor
```

Sunucu tarafı denetim bunu **ölçemez**: kalkan `markForReEncode` ile işaretler,
paket ise tüm dinleyiciler bittikten sonra yeniden serialize edilir. Denetçi ham
tampondan okuduğu için hep orijinali görür. Tek geçerli bakış açısı istemcininki.

Biome verisi taşıyan kolona hiç dokunulmaz: veri kaybı riski varsa müdahale
etmemek, bozuk chunk göndermekten iyidir.

### Yapılandırma

`config.yml` içinde: `shield.enabled`, `reveal-range`, `sweep-interval-ticks`,
`sweep-radius-chunks` ve `protected-blocks` (sandık, spawner, fırın, huni,
işaret feneri, shulker kutusu, büyü masası…).

## Performans (botlarla ölçüldü)

Yerel test sunucusunda, sürekli ışınlanarak chunk yükleten botlarla. Normal
oyundan çok daha ağır bir yük: saniyede ~430-690 chunk paketi.

| Senaryo | Chunk paketi | Değiştirilen | Ağ (µs/paket) | Ana iş parçacığı | MSPT / TPS |
|---|---|---|---|---|---|
| 20 bot, boş dünya | 37 992 | 94 | 11.9 | 1.24 ms/tur | 19.90 / 20.00 |
| 20 bot, 256 gömülü sandık | 25 838 | 866 | 14.9 | 0.84 ms/tur | 8.94 / 20.00 |
| 40 bot, 256 gömülü sandık | 41 287 | 1 466 | 13.5 | 0.79 ms/tur | 10.76 / 20.00 |
| 20 bot, tuzak açık (4'te 1) | 22 325 | 6 209 | 18.4 | 0.68 ms/tur | 9.11 / 20.00 |

**Maliyetin nerede olduğu önemli.** Kalkan ağ iş parçacıklarında çalışır, yani
TPS'i değil ağ gecikmesini etkiler: 40 botta ~690 paket/s × 13.5 µs ≈ tek bir
çekirdeğin **%0.9'u**. TPS'i etkileyen tek kısım tarama ve o da ana iş
parçacığında 2 saniyede bir 0.8 ms, 50 ms'lik tick bütçesinin **%0.04'ü**.

Her üç turda da TPS 20.00, sıfır istisna. Yoğunluk arttıkça (94 → 1466
değiştirilen paket) paket başı maliyet neredeyse sabit kaldı.

### Tuzaklar (opsiyonel, varsayılan kapalı)

Gizleme "bulamazsın" der. Tuzak "bulduğunu sandığın da yalan" der. Sunucu, taşın
içine gömülü sahte sandıklar yerleştirir; üs arayan biri hileyle bir sandık görür,
kazar, hiçbir şey bulamaz. Birkaç kez olunca hilenin verisine güveni kalmaz.

**Tuzak doğası gereği sadece hileciye görünür.** Savunmanın dayandığı asimetri şu:
meşru oyun yereldir, hile küreseldir. Taşın içine gömülü bir bloğu normal oyuncu
göremez, görüş hattı yoktur. Hile ise yüklü tüm chunk'ları birden okur.

Tek risk, meşru oyuncunun rastgele kazarken tuzağa denk gelmesi. Bunun için
oyuncu yaklaştıkça tuzaklar sessizce gerçek bloğa döner. Chunk paketleri 100+
blok mesafeden gönderilir, düzeltme 3 chunk yarıçapında çalışır; kimse kazacak
kadar yaklaşamadan tuzak yok olur.

Ölçüm (`harness/decoytest.mjs`):

```
UZAK  (>3 chunk): 143 tuzak, 0'i temizlendi     <- hilecinin gordugu
YAKIN (<=3 chunk): 23 kayit, 22'si temizlendi   <- oyuncunun kazabilecegi
```

Hileci ne yaparsa yapsın kaybeder: yaklaşınca sandıkların kaybolduğunu fark
ederse "sadece yakındakine güveneyim" der, o da zaten normal oyuncunun gördüğü
kadardır, uzak menzilli üs bulma ölür.

İki tasarım detayı:

- **Konumlar deterministik.** Rastgele üretilseydi aynı chunk her gönderildiğinde
  tuzak yer değiştirir, bu hem titrer hem de sunucunun veri uydurduğunu ele verir.
- **Her chunk'a değil, 4 chunk'ta bire.** Zehirleme için seyrek olması yeter, ve
  asıl maliyet tuzağın kendisi değil, dokunulan her paketin yeniden serialize
  edilmesidir. Seyreltme bu oranı %100'den %28'e düşürüyor.

### Bilinen optimizasyon fırsatı

Baskın maliyet, her chunk paketinde tüm kolonun çözülmesi (~12 µs), dizinde o
chunk için kayıt olmasa bile ödeniyor. Chunk x/z'si ham tampondan okunup dizinde
kayıt yoksa çözme tamamen atlanabilir. Ölçülen değerler kabul edilebilir olduğu
için yapılmadı.

### Ölçümün sınırları

- **Yeniden serialize etme maliyeti ölçülmedi.** Sayaç yalnızca kalkanın kendi
  kodunu ölçer; paketin yeniden yazılması PacketEvents içinde, dinleyiciler
  bittikten sonra olur. Tuzak açıkken dokunulan paket oranı arttığı için bu
  maliyet de artar. TPS etkilenmedi (iş ağ iş parçacıklarında), ama ölçülmüş
  bir sayı veremiyorum.
- 40 bot, tek dünya. 100+ oyunculu bir sunucu ölçülmedi.
- Botlar ışınlanıyor, dolayısıyla chunk paketi hızı gerçek oyundan yüksek ,
  bu yönüyle ölçüm karamsar tarafta.
- Bellek: dizinde 261 kayıt, ölçülebilir bir yük oluşturmadı.

`/outofsight perf` ve `/outofsight perfreset` ile kendi sunucunda aynı ölçümü yapabilirsin.
`/outofsight stress <n>` yoğun bir "üs tarlası" kurar.

## Testler

```
cd plugin && gradle test
```

10 test; ağırlıkla yanlış pozitif senaryoları, uzun hedefin ayağına vurma,
geri sarmanın yüksek pingli meşru vuruşu kurtarması, veri yokken suçlama
yapılmaması.

## Dürüst sınırlar

- Performans 40 bota kadar ölçüldü; 100+ oyunculu bir sunucu denenmedi.
- Bu Grim'in yerine geçmez. [Grim](https://grim.ac/) vanilla fiziğini tick tick
  yeniden yazar; burada o yok. Reach, fizik gerektirmeyen en kolay eylem hilesi.
- Fly/speed/noslow için hareket simülasyonu gerekir, bu projede yok.
- ReachCheck eşikleri kodda gömülü; ihlaller kalıcı loglanmıyor.
- `engine-mode: 2` CPU maliyetlidir; SMP'nde açmadan önce tick süresini ölç.
