# OutOfSight

Üs bulmayı durduran bir Paper eklentisi. Minecraft'ın kendi anti-xray'inin
kapsamadığı hile yeteneği bu.

[English README](README.md)

## Sorun

Paper anti-xray ile geliyor ve cevherlerde işe yarıyor. Test sunucusunda
`engine-mode: 2` ile ölçüldüğünde X-ray kullanan biri yaklaşık 196.000 "cevher"
görüyor, bunların 215'i gerçek. Kabaca 900'de 1.

Sandıklar başka bir hikâye. Sandık chunk paketine iki kez giriyor: bir kez blok
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

Cevher her iki modda da gizleniyor. Sandık her iki modda da konumunu ele veriyor.
Mode 1 en azından bloğu taşa çeviriyor, cevherler için daha güçlü olan mode 2 onu
bile yapmıyor.

Block ESP ve chunk tarayıcıları tam o boşlukta çalışıyor.

| Hedef | engine-mode 1 | engine-mode 2 |
|---|---|---|
| Cevherler | gizleniyor | gizleniyor, 900'de 1 gerçek |
| Sandık, spawner, varil | blok gizli, konum sızıyor | ikisi de sızıyor |

## Eklenti ne yapıyor

### Kalkan

Varsayılan olarak kapalı. Bu eklentiyi kurmak, sen kalkanı açana kadar
oyuncularının gördüğü hiçbir şeyi değiştirmiyor, ve denetim tek bir pakete bile
dokunmadan iki durumda da çalışıyor.

Herkese açmadan önce `shield.test-mode`, kalkanı yalnızca `outofsight.shielded`
iznine sahip oyunculara uyguluyor. `/outofsight testme` çalıştır, çık-gir yap, ve
kendi kaplarını hâlâ bulup açabildiğini kontrol et. Başka kimse etkilenmiyor.
Küçük sunucuların çoğunda izin eklentisi olmadığı için o komut izni kendisi
veriyor.

Oyuncunun göremediği kapları giden chunk paketinden çıkarıyor. Block entity
kaydını atıyor ve blok state'ini komşu bir blokla değiştiriyor. İkisi birden
gerekli: sadece kaydı atmak bloğu yine sandık olarak çizdirir, sadece bloğu
değiştirmek ise konumu ham listede okunur bırakır.

Kural varsayılan olarak reddetmek. Bir kap ancak ana iş parçacığı "bu oyuncu bunu
görebilir" dedikten sonra gönderiliyor, ki bu da yeterince yakın olmayı ve arada
engel bulunmamasını gerektiriyor. Ağ iş parçacığı dünyayı okuyamadığı için kendi
başına cevaplayabileceği tek soruyu soruyor: bu kap bu oyuncuya verildi mi?

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

Işın yalnızca henüz teslim edilmemiş kaplar için atılıyor, ve yeri değişmemiş bir
oyuncu değişmemiş bir dünyada tamamen atlanıyor. Elli sandıklı bir üsse girmek
elli ışının bedelini bir kez ödetiyor, sonrasında hiç.

Olaylar her değişikliği yakalayamıyor, çünkü komut, WorldEdit, piston ve akan su
hiçbir `BlockBreakEvent` üretmiyor. Daha yavaş bir tarama yakın chunk'ların block
entity'lerini yeniden okuyor, bu da hem hiçbir yerde bildirilmemiş kapları buluyor
hem artık var olmayanları düşürüyor. Yanlışlıkla gizli kalan bir kap, oyuncunun
eşyasını kaybetmesi demek; tarama bunun için var.

### Tuzaklar (opsiyonel, varsayılan kapalı)

Sunucu sahte gömülü sandıklar yerleştiriyor, böylece üs arayan biri altmış blok
kazıp hiçbir şey bulamıyor. Birkaç kez böyle olunca hilenin verisi üzerine hareket
etmeye değmez hale geliyor.

Tuzak yalnızca hileciye görünür, çünkü meşru oyun yerel, hile küresel. Taşa
mühürlü bir bloğun normal oyuncuya görüş hattı yok, oysa hile yüklü her chunk'ı bir
anda okuyor. Tek risk oyuncunun rastgele kazarken birine denk gelmesi, o yüzden
tuzaklar oyuncu yaklaştıkça sessizce gerçek bloğa dönüyor. Chunk paketleri 100+
blok mesafeden gönderiliyor ve düzeltme 3 chunk içinde çalışıyor, bu da birine
ulaşacak kadar yaklaşmaya fırsat bırakmıyor.

Sandıkların yaklaşınca kaybolduğunu fark eden bir hileci yalnızca yakındakine
güvenmeye başlıyor, ki meşru oyuncunun gördüğü de zaten o kadar.

Konumlar deterministik. Rastgele üretilenler chunk her yeniden gönderildiğinde yer
değiştirir, bu da hem titrer hem sunucunun veri uydurduğunu belli eder.

Tuzaklar her chunk'a değil, dört chunk'ta bire konuyor. Seyrek tuzaklar veriyi aynı
ölçüde zehirliyor, ve maliyetin çoğu dokunduğun her paketi yeniden serialize
etmekten geliyor. Seyreltme bunu paketlerin %100'ünden %28'ine düşürüyor.

### Denetim

`XrayAudit` giden chunk paketlerini gerçek dünyayla karşılaştırıp neyin sızdığını
blok türüne göre raporluyor. Oyuncunun zaten görebileceği açık sızıntıları, asıl
sorun olan gömülü olanlardan ayırıyor. İki kanal da inceleniyor: blok state'leri ve
block entity listesi.

Tür karşılaştırması şart: `engine-mode: 2` her gömülü cevheri listeden *rastgele
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
| `/outofsight reachdebug` | Her vuruşun ölçülen mesafesini yazar |
| `/outofsight reachsim <d>` | Reach eşiğinin nerede olduğunu gösterir |
| `/outofsight stress <n>` | Yük testi için yoğun bir gömülü sandık alanı kurar |
| `/outofsight perf` / `perfreset` | Maliyet ölçümleri |

## Performans

Chunk yüklenmesini zorlamak için sürekli ışınlanan botlarla ölçüldü. Normal
oyundan çok daha ağır bir yük, saniyede yaklaşık 400 ila 700 chunk paketi.

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
kaldı ve hiç istisna oluşmadı.

## Dürüst sınırlar

- Yeniden serialize etme maliyeti ölçülmedi. Sayaçlar yalnızca bu eklentinin kendi
  kodunu kapsıyor, paketin yeniden yazılması ise dinleyiciler bittikten sonra
  PacketEvents içinde oluyor. Tuzakları açmak daha çok pakete dokunuyor ve bunu
  artırıyor. İş ağ iş parçacıklarında olduğu için TPS etkilenmedi, ama elde ölçülmüş
  bir rakam yok.
- 40 bota kadar, tek dünyada test edildi. 100+ oyunculu bir sunucu ölçülmedi.
- Sandık block entity kayıt numarası canlı paketlerden öğreniliyor. Bu başarısız
  olursa `shield.decoy-block-entity-type` elle verilebilir.
- Bir kap görüş alanına `deliver-interval-ticks` kadar geç giriyor, varsayılanda
  saniyenin çeyreği. Sunucunda göze çarpıyorsa düşür.
- Reach kontrolü gerçek bir anticheat'in yerini tutmuyor. Hareket simülasyonu yok,
  dolayısıyla fly, speed ve noslow tespiti de yok.
- `engine-mode: 2` kendi başına CPU harcıyor. Açmadan önce tick süreni ölç.

## Doğrulama harness'ı

`harness/` klasöründe istemci gözüyle davranışı doğrulayan başsız istemciler var.
Burada işe yarayan tek bakış açısı o, çünkü sunucu tarafındaki denetim ham tamponu
okuyor ve kalkanın değişikliklerini hiç görmüyor.

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

Birim testler reach matematiğini kapsıyor, ağırlıkla yanlış pozitif senaryolarına
odaklı:

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
