# WebShell Builder

একটি Android অ্যাপ (`builder`) যা সার্ভার অ্যাড্রেস, অ্যাপ নাম, প্যাকেজ নেম, আইকন, স্প্ল্যাশ ও পারমিশন নিয়ে
**সাইন করা APK** তৈরি করে। জেনারেটেড অ্যাপ (`template`) হলো একটি WebView-ভিত্তিক ব্রাউজার অ্যাপ।

## বিল্ড করার নিয়ম
1. Android Studio (Koala+ / JDK 17) দিয়ে এই ফোল্ডার খুলুন, Gradle sync করুন।
2. `./gradlew :builder:assembleDebug` — এটি নিজে থেকে `template` বিল্ড করে `builder`-এর ভেতরে `assets/template.apk` হিসেবে ঢুকিয়ে দেয়।
3. `builder/build/outputs/apk/debug/builder-debug.apk` ফোনে ইনস্টল করুন।

## কিভাবে কাজ করে
- বিল্ডার ফোনেই টেমপ্লেট APK-এর বাইনারি `AndroidManifest.xml` প্যাচ করে (প্যাকেজ, নাম, ভার্সন, অনির্বাচিত পারমিশন বাদ)।
- `assets/config.json` (সার্ভার URL, রং, স্প্ল্যাশ, অপশন), আইকন PNG ও স্প্ল্যাশ ছবি ঢোকায়।
- ZIP আবার লেখে (resources.arsc ৪-বাইট অ্যালাইন্ড) এবং `apksig` দিয়ে v1+v2+v3 সাইন করে।
- সাইনিং কী প্রতি প্যাকেজ নেমের জন্য একবার তৈরি হয়ে বিল্ডারের private storage-এ থাকে; একই প্যাকেজ আবার বানালে আগের অ্যাপ আপডেট হবে (ভার্সন কোড বাড়াতে হবে)।
  **বিল্ডার আনইনস্টল করলে কী হারাবে।**

## জেনারেটেড অ্যাপের সুবিধা
- স্প্ল্যাশ স্ক্রিন (ছবি/নাম/রং/সময়), পেজ লোড হলে ফেড আউট।
- ইন্টারনেট/সার্ভার না থাকলে অ্যানিমেটেড লোডিং স্ক্রিন; নেট ফিরলে বা প্রতি ৫ সেকেন্ডে নিজে রিট্রাই।
- স্মার্ট ব্যাক: ফুলস্ক্রিন ভিডিও → পপআপ → পেজের `window.onAndroidBack()` → WebView history → দু'বার ব্যাক চেপে বের হওয়া।
- ক্যামেরা/মাইক (getUserMedia), লোকেশন, ফাইল আপলোড (ক্যামেরা ক্যাপচারসহ), ডাউনলোড, JS alert/confirm/prompt, `window.open` পপআপ (OAuth লগইন), `tel:` `sms:` `mailto:` `geo:` `intent:` লিংক।
- নির্বাচিত পারমিশনই শুধু ম্যানিফেস্টে থাকে।

## JavaScript ব্রিজ (`window.Android`)
`toast(msg)`, `call(num)`, `dial(num)`, `sms(num, body)`, `sendSms(num, body)`, `mail(to, subject, body)`,
`openApp(packageOrUri)`, `openUrl(url)`, `share(text)`, `vibrate(ms)`, `copy(text)`, `notify(title, text)`,
`flashlight(bool)`, `keepScreenOn(bool)`, `setOrientation("portrait"|"landscape"|"auto")`,
`hasPermission(key)`, `requestPermission(key)`, `openSettings()`, `reload()`, `exitApp()`, `getDeviceInfo()`.

ব্রিজ শুধু কনফিগ করা সার্ভারের হোস্ট থেকে লোড হওয়া পেজের কল গ্রহণ করে।

পেজে ব্যাক বাটন ধরতে: `window.onAndroidBack = () => { if (modalOpen) { closeModal(); return true; } return false; }`

## সীমাবদ্ধতা
- `blob:` ডাউনলোড সাপোর্টেড নয়। WebView-তে Web Notification/Bluetooth API নেই (`Android.notify` ব্যবহার করুন)।
- ক্যামেরা ক্যাপচার (`<input capture>`) Android 10+ এ।
- `resources.arsc`-এর ভেতরের প্যাকেজ নাম বদলানো হয় না (ইনস্টল ও রানে সমস্যা হয় না)।
- Google Play-তে দিতে চাইলে নিজের কী ও Play-এর নীতি (যেমন QUERY_ALL_PACKAGES, SMS/CALL) দেখে নিন।

## GitHub Actions (অটো বিল্ড)
`.github/workflows/android.yml` — GitHub-এ push/PR করলেই **debug APK** ও **signed release APK** তৈরি হয়ে
Actions → Artifacts-এ আসে। প্রতিটি push-এ (PR ছাড়া) নিজে থেকেই **GitHub Release** তৈরি হয় (ট্যাগ `v1.0.<run নম্বর>`), দুটো APK সংযুক্ত থাকে। নিজের ভার্সন চাইলে `v2.0.0` ট্যাগ push করুন।

### স্থায়ী সাইনিং কী (প্রস্তাবিত)
Secrets না দিলে CI একটি কী নিজে বানিয়ে Actions cache-এ রাখে ও পরের বিল্ডে সেটাই ব্যবহার করে। তবে cache মুছে গেলে (যেমন ৭ দিন অব্যবহৃত থাকলে) নতুন কী হয় এবং তখন পুরনো ইনস্টলের উপর আপডেট হবে না। তাই স্থায়ী কী Secrets-এ দেওয়াই নিরাপদ।
```bash
keytool -genkeypair -v -keystore release.jks -alias myalias -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks   # macOS: base64 -i release.jks
```
Repo → Settings → Secrets and variables → Actions-এ যোগ করুন:
`KEYSTORE_BASE64` (উপরের আউটপুট), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`।
`release.jks` কখনো repo-তে commit করবেন না (`.gitignore`-এ আছে) এবং ব্যাকআপ রাখুন।
