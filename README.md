# D-FON — Kişisel Telefon Asistanı

Telefon ayarlarını yazarak ya da sesle değiştirmeni sağlayan Android uygulaması. Arkada Claude yapay zekası çalışır.

## İndir
**[D-FON.apk (son sürüm)](https://github.com/acelik912-ux/D-FON/releases/latest/download/D-FON.apk)**

Her `main` güncellemesinde GitHub Actions APK'yı otomatik derler ve yeni bir sürüm yayınlar.

## Kurulum
1. Yukarıdaki linkten APK'yı indir, aç. "Bilinmeyen kaynaklardan yükleme" uyarısı çıkarsa izin ver.
2. Uygulamada **⚙** → console.anthropic.com'dan aldığın API anahtarını yapıştır.
3. **İzinler** düğmesinden iki izni ver: *Sistem ayarlarını değiştirme* ve *Rahatsız Etmeyin erişimi*.

## Neler yapabilir
| Doğrudan değiştirir | Ayar ekranını açar (Android kısıtı) |
|---|---|
| Parlaklık, otomatik parlaklık | Wi-Fi, Bluetooth, mobil veri, hotspot |
| Ekran zaman aşımı, otomatik döndürme | Karanlık mod, gece ışığı, yazı boyutu |
| Ses seviyeleri (medya, zil, alarm…) | Duvar kağıdı, uçak modu, konum |
| Zil modu, Rahatsız Etmeyin, fener | Pil, depolama, uygulamalar, dil… |

### Uygulamalar ve dosyalar
- Yüklü her uygulamayı adıyla açar ("WhatsApp'ı aç", "Word'ü aç")
- 📎 ile ya da "dosya seç" diyerek dosya eklenir
- Word → PDF, resimler → PDF, metin → PDF
- PDF → Word, PDF → resim (PNG), PDF özetleme/çeviri
- Dosyayı istenen uygulamada açma veya paylaşma
- Çıktılar: `İndirilenler/D-FON` ve `Resimler/D-FON`

Gereksinim: Android 10 veya üstü.

Örnek komutlar: "Parlaklığı %30 yap", "Gece moduna geç", "Telefonu sessize al", "Feneri aç", "Wi-Fi'ı aç".

## Kod
- `PhoneTools.kt` — telefonda çalışan araçlar (yeni yetenek buraya eklenir)
- `ClaudeClient.kt` — Claude API bağlantısı
- `MainActivity.kt` — sohbet ekranı, sesli komut, ayarlar
