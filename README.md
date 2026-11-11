# D-FON — Kişisel Telefon Asistanı

Telefonunu yazarak ya da sesle yönetmeni sağlayan Android uygulaması.

- **Tamamen telefonda çalışır.** Yapay zeka servisine, hesaba ya da sunucuya bağlı değildir.
- **Ücretsizdir.** API anahtarı, abonelik, reklam yok.
- **İnternet izni yoktur.** D-FON hiçbir yere veri gönderemez. "İnternette ara" komutları telefonunun kendi tarayıcısında açılır.

## İndir
**[D-FON.apk (son sürüm)](https://github.com/acelik912-ux/D-FON/releases/latest/download/D-FON.apk)**

Her `main` güncellemesinde GitHub Actions APK'yı otomatik derler ve yeni sürüm yayınlar.

## Kurulum
1. APK'yı indir, aç. "Bilinmeyen kaynaklardan yükleme" uyarısında izin ver.
2. **İzinler** → *Sistem ayarlarını değiştirme* ve *Rahatsız Etmeyin erişimi* ver.
3. **Uygulamalar** → D-FON'un açabileceği uygulamaları işaretle.

## Komutlar
Uygulamada `yardım` yazınca tam liste çıkar. Örnekler:

| Konu | Örnek |
|---|---|
| Ekran | "parlaklık 40", "ekranı karart", "ekran 2 dakikada kapansın" |
| Ses | "medya sesi 50", "sessize al", "titreşime al", "rahatsız etmeyi aç" |
| Diğer | "feneri aç", "gece modu", "durum" |
| Saat | "alarm 7:30", "sabah 6'da uyandır", "5 dakika zamanlayıcı", "alarmları göster", "saat kaç", "bugün günlerden ne" |
| Ayar ekranları | "Wi-Fi", "Bluetooth", "karanlık mod", "yazı boyutu" |
| Uygulamalar | "WhatsApp'ı aç" (sadece izinli olanlar) |
| Dosyalar | "PDF'i Word'e çevir", "Word'ü PDF yap", "resimleri PDF yap", "PDF'leri birleştir", "PDF'i resme çevir" |
| Yazıdan belge | "PDF yap: …", "Word yap: …" |
| Sesli okuma | "PDF'i oku", "Word'ü oku", "dosyayı oku", "oku: …", "ekrandaki yazıyı oku", "dur" |
| İnternet | "hava durumu ara", "YouTube'da … izle", "trt.net.tr" |

Komutlar "ve" ile birleştirilebilir: "parlaklık 20 ve sessize al". Son 20 komut "🕘 Geçmiş" düğmesinde durur.

**Sesli komut:** Sohbet ekranının ortasındaki büyük 🎤'ya dokun ve söyle; söylediğin anında yapılır.

**Sesle uyandırma ("Bir iki üç fon"):** Alttaki **👂 Uyandırma** düğmesiyle aç. Ekran açık ve kilitsizken "Bir iki üç fon" deyince D-FON açılıp dinlemeye başlar; "Bir iki üç fon, YouTube'u aç" dersen komutu doğrudan yapar. Başka uygulamadayken öne gelebilmesi için *Diğer uygulamaların üzerinde göster* izni gerekir. Ekran kapalı/kilitliyken mikrofon kullanılmaz; duyulanlar kaydedilmez. Samsung'da Ayarlar → Uygulamalar → D-FON → Pil → *Kısıtlamasız* seçmek önerilir.

**Seçilen yazıyı okutma:** Herhangi bir uygulamada yazı seç → menüde **D-FON ile oku**. Paylaş menüsünden de gönderebilirsin.

**Ekrandaki yazıyı okutma:** Ayarlar → Erişilebilirlik → Yüklü uygulamalar → **D-FON Ekran Okuma**'yı aç. Ekranın kenarında çıkan küçük düğmeye basınca o an ekrandaki yazıyı okur, tekrar basınca susar. Samsung "kısıtlı ayar" derse: Ayarlar → Uygulamalar → D-FON → ⋮ → *Kısıtlı ayarlara izin ver*.

Okuma telefonun kendi seslendirme motoruyla yapılır; Türkçe ses paketi yüklüyse internet gerekmez.

Çıktılar `İndirilenler/D-FON` ve `Resimler/D-FON` klasörlerine kaydedilir. Gereksinim: Android 10+.

## Kod
- `CommandEngine.kt` — Türkçe komutları anlayan kural motoru (yeni komut buraya eklenir)
- `PhoneTools.kt` — telefon ayarları
- `ClockTools.kt` — alarm, zamanlayıcı, saat ve tarih
- `Voice.kt`, `WakeService.kt` — sesli komut ve "bir iki üç fon" ile uyandırma
- `Speaker.kt`, `ReadTextActivity.kt`, `ScreenReadService.kt` — sesli okuma
- `FileTools.kt` — uygulamalar, dosyalar, PDF/Word dönüştürme (PdfBox-Android)
- `MainActivity.kt` — ekran, sesli komut, uygulama seçimi
