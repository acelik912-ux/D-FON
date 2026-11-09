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
| İnternet | "hava durumu ara", "YouTube'da … izle", "trt.net.tr" |

Komutlar "ve" ile birleştirilebilir: "parlaklık 20 ve sessize al". Son 20 komut "🕘 Geçmiş" düğmesinde durur.

Çıktılar `İndirilenler/D-FON` ve `Resimler/D-FON` klasörlerine kaydedilir. Gereksinim: Android 10+.

## Kod
- `CommandEngine.kt` — Türkçe komutları anlayan kural motoru (yeni komut buraya eklenir)
- `PhoneTools.kt` — telefon ayarları
- `ClockTools.kt` — alarm, zamanlayıcı, saat ve tarih
- `FileTools.kt` — uygulamalar, dosyalar, PDF/Word dönüştürme (PdfBox-Android)
- `MainActivity.kt` — ekran, sesli komut, uygulama seçimi
