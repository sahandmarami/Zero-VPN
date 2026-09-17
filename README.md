# Zero VPN

<div align="center">

**Zero VPN** — Fast, secure & private VPN client for Android, powered by the official Xray core.

اپلیکیشن **Zero VPN** — کلاینت VPN سریع و امن برای اندروید با هسته رسمی Xray

</div>

---

## ✨ Features | امکانات

- **Real ping + country at the top** — the connect card shows the selected server, its exit country (with flag) and the real ping in milliseconds. با یک نگاه: پینگ واقعی، کشور خروجی (با پرچم) و سرور انتخاب‌شده در بالای صفحه
- **One-tap connect button at the top** of the config section. دکمه اتصال بزرگ در بالای بخش کانفیگ
- **Liquid-gooey connect button** — native Compose re-implementation of the liquid-gooey metaball effect with spring physics. دکمه اتصال با افکت مایع/گویی
- **Multiple configs & easy switching** — import via vmess:// vless:// trojan:// ss:// links, QR code, clipboard, files or subscription URLs. پشتیبانی از کانفیگ‌های نامحدود و سابسکریپشن
- **Real ping test for all configs** — test everything, sort by results, connect to the best. تست پینگ واقعی همه کانفیگ‌ها و مرتب‌سازی بر اساس نتیجه
- **Auto core update on every launch** — the app checks the official [XTLS/Xray-core](https://github.com/XTLS/Xray-core) releases every time it opens and tells you when a newer core is available; CI keeps rebuilding the APK with the latest core automatically. بررسی خودکار نسخه جدید هسته Xray در هر بار باز شدن برنامه
- **Persian & English UI**. رابط کاربری فارسی و انگلیسی
- Clean, dark-first Material 3 design. طراحی تمیز و مدرن

## 📲 Installation | نصب

1. Grab the latest APK from [Releases](https://github.com/sahandmarami/Zero-VPN/releases) — pick `universal` or your device ABI (`arm64-v8a` for most modern phones). آخرین APK را از بخش Releases دانلود کنید (برای اکثر گوشی‌های جدید `arm64-v8a`)
2. Allow "Install unknown apps" for your browser/file manager. اجازه نصب از منابع ناشناس را بدهید
3. Open Zero VPN → add your config → tap **Connect**. برنامه را باز کنید، کانفیگ را اضافه کنید و دکمه اتصال را بزنید

## 🔄 How core auto-update works | نحوه آپدیت خودکار هسته

- On every launch the app queries `releases/latest` of the official XTLS/Xray-core repository and compares it with the embedded core version. در هر بار اجرا، نسخه هسته داخلی با آخرین ریلیز رسمی Xray-core مقایسه می‌شود
- This repository's GitHub Actions workflow rebuilds the APK against the latest official core binding (daily check + on push), so updates always carry the newest core. ورک‌فلو CI این ریپو به‌صورت خودکار APK را با جدیدترین هسته بازسازی می‌کند
- When a newer build is available the app shows an update banner on launch with a direct download. با وجود نسخه جدید، بنر آپدیت مستقیم نمایش داده می‌شود

## 🛠️ Building from source | بیلد از سورس

```bash
git clone --recurse-submodules https://github.com/sahandmarami/Zero-VPN.git
cd Zero-VPN

# 1) build the hev tunnel native lib (needs Android NDK)
export NDK_HOME=/path/to/android-ndk
bash compile-hevtun.sh
cp -r libs V2rayNG/app/

# 2) fetch the official Xray core binding
mkdir -p V2rayNG/app/libs
curl -L -o V2rayNG/app/libs/libv2ray.aar \
  https://github.com/2dust/AndroidLibXrayLite/releases/download/v26.9.9/libv2ray.aar

# 3) build
cd V2rayNG
./gradlew assemblePlaystoreRelease \
  -Pandroid.injected.signing.store.file=/path/to/keystore.jks \
  -Pandroid.injected.signing.store.password=*** \
  -Pandroid.injected.signing.key.alias=*** \
  -Pandroid.injected.signing.key.password=***
```

## 📄 License | مجوز

This project is a fork of [v2rayNG](https://github.com/2dust/v2rayNG) and remains licensed under **GPL-3.0**. All credits for the original VPN engine, config parsing and service plumbing go to the 2dust/v2rayNG contributors. The Zero VPN branding, UI palette, top connect card, liquid-gooey button and core auto-update pipeline are additions on top of that great base.

این پروژه شاخه‌ای از v2rayNG است و تحت مجوز GPL-3.0 منتشر می‌شود.
