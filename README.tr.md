# OutOfSight

<img src="assets/logo.png" alt="OutOfSight logo" width="96">

Üs bulmayı durduran bir Paper eklentisi. Minecraft'ın kendi anti-xray'i bu hileyi
kapsamıyor.

[English README](README.md)

## Sorun

Paper anti-xray ile geliyor ve cevherlerde işe yarıyor. Test sunucusunda
`engine-mode: 2` ile ölçüldüğünde X-ray kullanan biri yaklaşık 196.000 "cevher"
görüyor, bunların 215'i gerçek. Kabaca 900'de 1.

Sandıklarda durum farklı. Sandık chunk paketine iki kez giriyor: bir kez blok
state olarak, bir kez de ayrı bir block entity listesindeki kayıt olarak.
Anti-xray blok state'leri üzerinde çalıştığı için o liste koordinatlarıyla
birlikte olduğu gibi çıkıyor.

Aynı deney, tek bir taş kabuğa mühürlenmiş bir sandık ve bir elmas cevheri,
ikisi de varsayılan `hidden-blocks` listesinde, her engine mode için ayrı:

```
engine-mode 1
  chest        block=hidden(stone)                block-entity=LEAKED
  diamond ore  block=hidden(stone)                block-entity=none

engine-mode 2
  chest        block=LEAKED(chest)                block-entity=LEAKED
  diamond ore  block=hidden(deepslate_copper_ore) block-entity=none
```

Cevher iki modda da gizleniyor, sandık ise iki modda da konumunu ele veriyor.
Mode 1 en azından bloğu taşa çeviriyor, cevherler için daha güçlü olan mode 2 onu
bile yapmıyor. Block ESP ve chunk tarayıcıları bu boşluktan yararlanıyor.

| Hedef | engine-mode 1 | engine-mode 2 |
|---|---|---|
| Cevherler | gizleniyor | gizleniyor, 900'de 1 gerçek |
| Sandık, spawner, varil | blok gizli, konum sızıyor | ikisi de sızıyor |

## Eklenti ne yapıyor

### Kalkan

Varsayılan olarak kapalı. Kalkanı açana kadar eklenti oyuncularının gördüğü hiçbir
şeyi değiştirmiyor. Denetim ise kalkan açık da olsa kapalı da olsa tek bir pakete
dokunmadan çalışıyor.

Herkese açmadan önce `shield.test-mode`, kalkanı yalnızca `outofsight.shielded`
iznine sahip oyunculara uyguluyor. `/outofsight testme` çalıştır, çık-gir yap ve
kendi kaplarını hâlâ bulup açabildiğini kontrol et. Başka kimse etkilenmiyor.
Küçük sunucuların çoğunda izin eklentisi olmadığı için o komut izni kendisi
veriyor.

Oyuncunun göremediği kapları giden chunk paketinden çıkarıyor. Block entity
kaydını atıyor ve blok state'ini komşu bir blokla değiştiriyor. İkisi birden
gerekli: sadece kaydı atmak bloğu yine sandık olarak çizdirir, sadece bloğu
değiştirmek ise konumu ham listede okunur bırakır.

Varsayılan cevap "hayır". Bir kap ancak ana iş parçacığı "bu oyuncu bunu
görebilir" dedikten sonra gönderiliyor. Bunun için oyuncunun yeterince yakın olması
ve arada engel bulunmaması gerekiyor. Ağ iş parçacığı dünyayı okuyamadığı için
yalnızca bu kabın bu oyuncuya daha önce verilip verilmediğine bakıyor.

Teslimatı iki şey belirliyor:

1. Mesafe. Üs bulma tanımı gereği uzak menzilli bir saldırı, çünkü hile chunk'ları
   render mesafesine kadar yükleyip içindeki her kabı bir anda okuyor. Vanilla
   istemciler zaten bu mesafenin çok ötesindeki block entity'leri çizmiyor, yani
   uzaktakileri göndermemek dürüst oyuncuya bir şey kaybettirmiyor.
2. Görüş hattı. Tek başına mesafe, tepede duran birinin altındaki üssün içeriğini
   toplamasına izin veriyor. Oyuncunun gözünden atılan bir ışın arada bir şey olup
   olmadığını çözüyor.

Altı komşusu da katı olan bir blokta görüş hattı olamaz, o yüzden bu ucuz test
önce çalışıyor ve taşa mühürlü olanlarda ışını atlıyor. Tek başına gömülülük kötü
bir kural olurdu, çünkü açılabilen her sandığın üstünde hava var.

Işın yalnızca henüz teslim edilmemiş kaplar için atılıyor. Yerinden kıpırdamayan
bir oyuncu, dünyada da bir şey değişmediyse tamamen atlanıyor. Elli sandıklı bir
üste dolaşırken ışın atılıyor, durduğun anda duruyor.

Olaylar her değişikliği yakalayamıyor, çünkü komut, WorldEdit, piston ve akan su
hiçbir `BlockBreakEvent` üretmiyor. Daha yavaş bir tarama yakın chunk'ların block
entity'lerini yeniden okuyor. Böylece hiçbir olayın bildirmediği kapları buluyor,
artık var olmayanları da listeden düşürüyor. Yanlışlıkla gizli kalan bir kap
oyuncunun eşyasını kaybetmesi demek, tarama bunu önlemek için var.

### Depolu araçlar

Sandıklı minecart, hopper'lı minecart ve sandıklı tekneler blok değil varlık,
yukarıdaki kalkan onları hiç görmüyor. Storage ESP onları yine de çiziyor ve bir
farmın altındaki hopper minecart sırası bir base'i sandık kadar açık gösteriyor.

Onlarda da varsayılan cevap "hayır": bir varlık oyuncuya ancak oyuncunun gözünden
çıkan ışın ona ulaştığında gönderiliyor. Mesafenin rolü yok, açıkta duran her şey
istemcinin çizdiği mesafeye kadar görünür kalıyor. Paper'ın `hideEntity`'si
tracker'ı susturuyor ve eklenti başına çalıştığı için aynı varlığı gizleyip
gösteren başka eklentilerle çakışmıyor. Tracker'ın en ilk spawn paketini
durduramadığı için henüz gösterilmemiş varlıklarda o paketi bir paket dinleyicisi
düşürüyor. Birinin bindiği araç hiç gizlenmiyor, yoksa oyuncu havada süzülüyor
görünürdü. Oyuncular listeye eklenemiyor: bir oyuncuyu gizlemek onu tab
listesinden de siliyor.

`shield.protected-entities` başka türler de alıyor. Collectible ESP'ye karşı
`item_frame`, `glow_item_frame` ve `armor_stand` eklemeye değer; karar yalnızca
görüş hattına bağlı olduğu için açıktaki harita duvarları ve dükkan vitrinleri
etkilenmiyor.

### Ayar danışmanı

X-ray ve netherite finder'a karşı korumayı Paper'ın anti-xray'i sağlıyor ve o da
kapalı geliyor. Varsayılan `hidden-blocks` listesinde
`ancient_debris`, `spawner`, `barrel` ve `trapped_chest` de yok. Açılışta ve
`/outofsight advise` ile eklenti `config/paper-world-defaults.yml` dosyasını ve her
dünyanın `paper-world.yml` dosyasını okuyup açık kalanları raporluyor. Hiçbir
ayarı değiştirmiyor.

### Tuzaklar (opsiyonel, varsayılan kapalı)

Sunucu sahte gömülü sandıklar yerleştiriyor, böylece üs arayan biri altmış blok
kazıp hiçbir şey bulamıyor. Birkaç kez böyle olunca hilenin verisi üzerine hareket
etmeye değmez hale geliyor.

Tuzağı yalnızca hileci görür. Normal oyuncu sadece yakınındakini görür, hile ise
her şeyi birden okur. Taşa mühürlü bir bloğun normal oyuncuya görüş hattı yok, oysa hile yüklü her chunk'ı bir
anda okuyor. Tek risk oyuncunun rastgele kazarken birine denk gelmesi, o yüzden
tuzaklar oyuncu yaklaştıkça sessizce gerçek bloğa dönüyor. Chunk paketleri 100+
blok mesafeden gönderiliyor, düzeltme ise 3 chunk içinde çalışıyor. Oyuncu bir
tuzağa ulaşamadan o düzelmiş oluyor.

Sandıkların yaklaşınca kaybolduğunu fark eden bir hileci yalnızca yakındakine
güvenmeye başlıyor. Normal bir oyuncu da zaten sadece o kadarını görüyor.

Konumlar deterministik. Rastgele üretilenler chunk her yeniden gönderildiğinde yer
değiştirir, bu da hem titrer hem sunucunun veri uydurduğunu belli eder.

Tuzaklar dört chunk'ta bire konuyor. Seyrek tuzaklar veriyi aynı ölçüde
zehirliyor ve maliyetin çoğu dokunduğun her paketi yeniden serialize
etmekten geliyor. Seyreltme bunu paketlerin %100'ünden %28'ine düşürüyor.

### Denetim

`XrayAudit` giden chunk paketlerini gerçek dünyayla karşılaştırıp neyin sızdığını
blok türüne göre raporluyor. Oyuncunun zaten görebileceği açık sızıntıları, asıl
sorun olan gömülü olanlardan ayırıyor. İki kanal da inceleniyor: blok state'leri ve
block entity listesi.

Türleri karşılaştırmak gerekiyor, çünkü `engine-mode: 2` her gömülü cevheri listeden *rastgele
başka* bir cevherle değiştiriyor, dolayısıyla sadece "burada cevher var mı" diye
sormak %58 gibi sahte bir sızıntı oranı veriyor. Bu proje tam olarak o hatayı yaptı
ve sonra düzeltti.

### Reach kontrolü

Ping telafili bir vuruş mesafesi kontrolü, örnek olarak dahil edildi.
[Grim](https://grim.ac/) vanilla hareketini tick tick yeniden yazıyor ve buradaki
hiçbir şey ona yaklaşmıyor. Üç bilinçli tercih, üçü de meşru oyuncuyu işaretlememek
üzerine:

1. Mesafe hedefin merkezine değil, çevreleyen kutusuna ölçülüyor.
2. Saldıran ve kurban, saldıranın gecikmesi kadar geri sarılıyor ve o penceredeki
   en yakın an kullanılıyor, yani şüphe oyuncunun lehine yorumlanıyor.
3. Tek ihlal ceza doğurmuyor. İhlaller birikiyor ve sönümleniyor.

Menzil sabit yazılmak yerine oyuncunun `ENTITY_INTERACTION_RANGE` niteliğinden
okunuyor. Bu testte hemen kendini gösterdi: creative 5.03'e izin veriyor, survival
3.03'e. Sabit 3.0 yazılsaydı her creative vuruşu işaretlenirdi.

## Krypton tarzı istemcilerin hâlâ yapabildikleri

SMP sunucularında yaygın, ücretli bir Fabric hile istemcisi olan Krypton'un özellik
listesine göre kontrol edildi. Bilgi hilelerine karşı
bilgiyi hiç göndermemek gerekiyor. Eylem hileleri için gerçek bir anticheat lazım.

| Hile özelliği | Durum | Karşılayan |
|---|---|---|
| Storage ESP, block ESP, stash finder | Engelli | Kalkan |
| Spawner ESP / bildirici | Engelli | Kalkan (`spawner`), ayrıca anti-xray `hidden-blocks` |
| Sandıklı/hopper'lı minecart, sandıklı tekne | Engelli | Depolu araçlar |
| Collectible ESP: bannerlar | Engelli | Kalkan (`#banners`) |
| Collectible ESP: item frame, zırh askılığı | İsteğe bağlı | `protected-entities`'e ekle |
| X-ray, netherite finder | Paper anti-xray | `/outofsight advise` ayarları kontrol ediyor |
| Mob / varlık ESP | Moblar için isteğe bağlı | `protected-entities`; oyuncular asla gizlenmez |
| SUS chunk finder, seed tabanlı bulucular | Kısmen | Seed'i gizli tut; `advise` feature seed'leri kontrol ediyor |
| Hole, tunnel, stairs ESP, 1x1 delikler | Engellenemez | İstemci arazinin şeklini çizmek ve çarpışma için bilmek zorunda |
| KillAura, aim assist, crystal ve anchor aura | Burada yok | [Grim](https://grim.ac/) kullan |
| Auto totem | Burada yok | Sunucu tarafında güvenilir şekilde tespit edilemiyor |
| Speed, fly, elytra auto fly, auto mine | Burada yok | Grim kullan |

Hareket ve dövüş kontrolleri bilerek Grim'e bırakıldı. Grim vanilla hareketi tick
tick simüle ediyor. Kötü bağlantılı dürüst oyuncuları işaretlemeden bu hileleri
yakalamak için bu gerekiyor. Grim de PacketEvents üzerinde çalışıyor, bu eklentiyle
yan yana sorunsuz çalışıyor.

## Gereksinimler

- Paper 1.21.11
- [PacketEvents](https://github.com/retrooper/packetevents) 2.13.0+

## Komutlar

Hepsi `outofsight.admin` gerektiriyor (varsayılan olarak op). Kısayol: `/oos`.

| Komut | Ne yapar |
|---|---|
| `/outofsight xray [chunk]` | Giden chunk paketlerini sızıntı için denetler |
| `/outofsight hidechest` | Gömülü sandık + kontrol cevheri koyar, sonra çık-gir |
| `/outofsight shield` | Kalkanı açar/kapatır |
| `/outofsight testme` | Kalkanı sadece kendine uygular, test için |
| `/outofsight advise` | Paper'ın anti-xray ve seed ayarlarının açık bıraktıklarını raporlar |
| `/outofsight reachdebug` | Her vuruşun ölçülen mesafesini yazar |
| `/outofsight reachsim <d>` | Reach eşiğinin nerede olduğunu gösterir |
| `/outofsight stress <n>` | Yük testi için yoğun bir gömülü sandık alanı kurar |
| `/outofsight perf` / `perfreset` | Maliyet ölçümleri |

## Performans

Chunk yüklenmesini zorlamak için sürekli ışınlanan botlarla ölçüldü. Normal
oyundan çok daha ağır bir yük, saniyede yaklaşık 400 ila 700 chunk paketi.

Bu ölçümler depolu araç kalkanından önce yapıldı. Onun taraması ana thread'e
ek iş getiriyor ve bu iş tabloda henüz yok.

| Senaryo | Paket | Değiştirilen | Ağ (µs/paket) | Ana iş parçacığı | MSPT / TPS |
|---|---|---|---|---|---|
| 20 bot, boş dünya | 37 992 | 94 | 11.9 | 1.24 ms | 19.90 / 20.00 |
| 20 bot, 256 gömülü sandık | 25 838 | 866 | 14.9 | 0.84 ms | 8.94 / 20.00 |
| 40 bot, 256 gömülü sandık | 41 287 | 1 466 | 13.5 | 0.79 ms | 10.76 / 20.00 |
| 20 bot, tuzak açık (4'te 1) | 22 325 | 6 209 | 18.4 | 0.68 ms | 9.11 / 20.00 |
| 20 bot, görüş hattı kuralı | 21 380 | 1 807 | 19.9 | 0.44 ms | 10.30 / 20.00 |

Kalkan ağ iş parçacıklarında çalıştığı için TPS'e değil gecikmeye mal oluyor. 40
botta bu kabaca tek çekirdeğin %0.9'u. Ana iş parçacığındaki tek iş tarama, iki
saniyede bir 0.8 ms, yani 50 ms'lik tick bütçesinin %0.04'ü. TPS her turda 20.00
kaldı, hiç hata oluşmadı.

## Dürüst sınırlar

- Yeniden serialize etme maliyeti ölçülmedi. Sayaçlar yalnızca bu eklentinin kendi
  kodunu kapsıyor, paketin yeniden yazılması ise dinleyiciler bittikten sonra
  PacketEvents içinde oluyor. Tuzakları açmak daha çok pakete dokunuyor ve bunu
  artırıyor. İş ağ iş parçacıklarında olduğu için TPS etkilenmedi, ama elde ölçülmüş
  bir rakam yok.
- 40 bota kadar, tek dünyada test edildi. 100+ oyunculu bir sunucu ölçülmedi.
- Olay tetiklemeden konan kapları (komutlar, WorldEdit, shulker kutusu yerleştiren
  bir dispenser) tarama buluyor, tarama da sadece oyunculara yakın chunk'lara
  bakıyor. Bir oyuncu `sweep-radius-chunks` kadar yaklaşana dek daha uzaktaki
  oyunculara gidebiliyorlar.
- Sandık block entity kayıt numarası canlı paketlerden öğreniliyor. Bu başarısız
  olursa `shield.decoy-block-entity-type` elle verilebilir.
- Bir kap görüş alanına `deliver-interval-ticks` kadar geç giriyor, varsayılanda
  saniyenin çeyreği. Sunucunda göze çarpıyorsa düşür.
- Reach kontrolü gerçek bir anticheat'in yerini tutmuyor. Hareket simülasyonu yok,
  dolayısıyla fly, speed ve noslow tespiti de yok.
- `engine-mode: 2` kendi başına CPU harcıyor. Açmadan önce tick süreni ölç.

## Doğrulama harness'ı

CI her push'ta `harness/e2e.mjs` çalıştırıyor. Paper 1.21.11'i PacketEvents ile
başlatıyor, konsol komutlarıyla bilinen bir sahne kuruyor ve aldığı her chunk'ı,
blok güncellemesini ve varlık spawn'ını çözen başsız bir istemciyle bağlanıyor.
Gömülü, uzak ve açıkta duran sandıkları, uzunlamasına bakılan çift sandığı,
Nether'ı, gömülü ve görünür sandıklı minecart'ları kontrol ediyor. Sonra kalkanı
kapatıp aynı gömülü sandığın sızdığını doğruluyor.

Aşağıdaki script'ler yerel bir sunucuda elle çalıştırmak için. Davranışı istemcinin
gözünden kontrol ediyorlar. Burada işe yarayan tek bakış açısı bu, çünkü sunucu
tarafındaki denetim ham tamponu okuyor ve kalkanın değişikliklerini hiç görmüyor.

```
node relog.mjs <ad> <ms> ["komut"]       # baglan, komut calistir, cik
node inspect.mjs <x> <y> <z>             # chunk paketi o konumda ne tasiyor
node watch.mjs <x> <y> <z>               # kap sonradan teslim ediliyor mu
node dig.mjs <x> <y> <z>                 # duvari kir, ortaya cikisi izle
node decoytest.mjs                       # tuzak dagilimi, yakin ve uzak
node loadtest.mjs <bot> <saniye>         # yuk uretimi
```

## Derleme

```
cd plugin && gradle build
```

Birim testler reach matematiğini (ağırlıkla yanlış pozitif senaryolarını), kap
indeksini ve tuzak yerleşimini kapsıyor:

```
cd plugin && gradle test
```

## Teşekkür

Bir sorun bildiren ya da kendi sunucusunun gerçekte ne sızdırdığını paylaşan
test edenler burada anılıyor. Kodu değiştiren bir şey bildirdiysen kendini ekleyen
bir pull request aç, ya da issue üzerinde söyle, eklensin.

(henüz kimse yok)

## Dipnot

Kripton bir soy gaz. Onu bağ kurmaya zorlayabilecek kadar tepkin tek element flor.
Bu flor değil, ama gününü yine de mahvediyor.

## Lisans

GPL-3.0. PacketEvents GPL-3.0 ve bu eklenti ona bağlanıyor, dolayısıyla birleşik iş
de GPL-3.0 ve kaynağı onu alan herkese açık kalmak zorunda.
