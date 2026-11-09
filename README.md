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

## নতুন ফিচার (বিল্ডারের অপশন থেকে চালু/বন্ধ)
| ফিচার | কী করে |
|---|---|
| Pull-to-refresh | পেজের উপরে থাকলে নিচে টেনে রিফ্রেশ। যে পেজে নিজস্ব স্ক্রল এরিয়া আছে সেখানে `Android.setPullRefresh(false)` |
| থিম | সিস্টেম / সবসময় লাইট / সবসময় ডার্ক। WebView-এর `prefers-color-scheme` এটা অনুসরণ করে; "জোর করে ডার্ক" চালালে ডার্ক থিম ছাড়া সাইটও কালো হয় |
| অ্যাপ লক | ফিঙ্গারপ্রিন্ট/ফেস/PIN (Android 10+), তার নিচে ফোনের PIN/প্যাটার্ন। ফোনে স্ক্রিন লক না থাকলে লক কাজ করে না |
| অফলাইন ক্যাশ | নেট না থাকলে WebView ক্যাশ থেকে আগে খোলা পেজ দেখায় (সার্ভারের `Cache-Control` বা Service Worker লাগে) |
| ডিপ লিংক | সার্ভারের ডোমেইনের লিংক ক্লিক করলে অ্যাপে খোলে (Android নিজে একবার জিজ্ঞেস করতে পারে) |
| শেয়ার টার্গেট | অন্য অ্যাপ থেকে টেক্সট/ছবি শেয়ার করলে অ্যাপে আসে; পেজে `androidshare` ইভেন্ট ও `window.__androidShare` |
| নেভিগেশন বার | নিচে সর্বোচ্চ ৫টি আইটেম (`নাম\|লিংক`, লিংক সম্পর্কিত হলেও চলবে) |
| লঞ্চার শর্টকাট | আইকনে লং-প্রেস মেনু, সর্বোচ্চ ৪টি |
| প্রিন্ট | `window.print()` ও `Android.print()` Android-এর প্রিন্ট ডায়ালগ খোলে (প্রিন্টার বা "PDF হিসেবে সেভ") |
| মিডিয়া প্লেয়ার | সেট করা এক্সটেনশনের লিংক (`mp3, mp4, m3u8` ...) ডাউনলোড না হয়ে ইন-বিল্ট প্লেয়ারে চলে: প্লে/পজ, সিক, ±সেকেন্ড, স্পিড, লুপ, অন্য অ্যাপে খোলা। Android যে ফরম্যাট ডিকোড করতে পারে না (যেমন avi, wmv, flv) সেখানে ডাউনলোড/অন্য অ্যাপে খোলার অপশন আসে |
| স্প্ল্যাশ | লোগো (কাস্টম সাইজ dp) অথবা ফুল স্ক্রিন: ফিট / ক্রপ / স্ট্রেচ; নাম ও স্পিনার আলাদা করে অন/অফ |
| বের হওয়ার মেসেজ | "দু'বার ব্যাক" চাপলে যে টোস্ট দেখায় তার লেখা; সাথে অফলাইন/লক/স্ক্যানার ইত্যাদির লেখাও বদলানো যায় |
| ব্যাকগ্রাউন্ড সার্ভিস | ফোরগ্রাউন্ড সার্ভিস নির্দিষ্ট সময় পরপর একটি URL পোল করে নোটিফিকেশন দেখায় (নিচে দেখুন) |

### ব্যাকগ্রাউন্ড সার্ভিস: পোল URL-এর ফরম্যাট
সার্ভার JSON দেবে: `[{"id":"42","title":"নতুন মেসেজ","text":"রহিম লিখেছে...","url":"/chat/7"}]`
(বা `{"notifications":[...]}`)। `id` দিয়ে একই নোটিফিকেশন দ্বিতীয়বার দেখানো আটকানো হয়; URL-এ `{lastId}` লিখলে শেষ দেখানো `id` বসে যায়।
লগইন কুকি WebView থেকেই যায়। ট্যাপ করলে `url` পেজে অ্যাপ খোলে।
**মনে রাখুন:** এটা Firebase (FCM) পুশ নয়। ফোন Doze-এ গেলে বা কোম্পানির ব্যাটারি সেভার অ্যাপ মেরে দিলে দেরি হতে পারে; `Android.openBatterySettings()` দিয়ে ব্যাটারি অপটিমাইজেশন থেকে বাদ দিতে বলা যায়।
Play Store-এর জন্য এই সার্ভিস উপযুক্ত নয়।

## JavaScript ব্রিজ (`window.Android`)
`toast(msg)`, `call(num)`, `dial(num)`, `sms(num, body)`, `sendSms(num, body)`, `mail(to, subject, body)`,
`openApp(packageOrUri)`, `openUrl(url)`, `share(text)`, `vibrate(ms)`, `copy(text)`, `notify(title, text)`,
`flashlight(bool)`, `keepScreenOn(bool)`, `setOrientation("portrait"|"landscape"|"auto")`,
`hasPermission(key)`, `requestPermission(key)`, `openSettings()`, `reload()`, `exitApp()`, `getDeviceInfo()`.

**নতুন ফাংশন:** `scanQR()`, `biometricAuth(title)`, `saveFile(name, mime, base64)`, `shareFile(name, mime, base64)`,
`getContacts()` (কন্টাক্ট পারমিশন লাগে), `getClipboard()`, `getBattery()`, `getNetworkType()`, `setStatusBarColor("#RRGGBB")`,
`setPullRefresh(bool)`, `isDarkMode()`, `startBackground()`, `stopBackground()`, `openBatterySettings()`, `print()`।

ফলাফল ফেরত আসে এই কলব্যাকে (পেজে ফাংশন বানিয়ে রাখুন):
```js
window.onQRResult = (text, format) => { /* text == null হলে বাতিল */ };
window.onBiometricResult = (ok, reason) => { /* reason: ok | denied | unavailable */ };
window.onFileSaved = (name) => {};
window.onThemeChanged = (dark) => {};      // অথবা window.addEventListener('androidtheme', e => e.detail.dark)
window.addEventListener('androidshare', e => { /* e.detail = {text, subject, files:[{name,type,dataUrl}]} */ });
```
`scanQR()` ক্যামেরা পারমিশন চালু থাকলে কাজ করে (QR, EAN, Code128, PDF417 ইত্যাদি)।

ব্রিজ শুধু কনফিগ করা সার্ভারের হোস্ট থেকে লোড হওয়া পেজের কল গ্রহণ করে।

পেজে ব্যাক বাটন ধরতে: `window.onAndroidBack = () => { if (modalOpen) { closeModal(); return true; } return false; }`

## সীমাবদ্ধতা
- `blob:` ও `data:` ডাউনলোড এখন Downloads ফোল্ডারে সেভ হয় (বড় ফাইলে লিংকটি `URL.revokeObjectURL` দিয়ে আগেই বাতিল করা হলে ব্যর্থ হতে পারে)। WebView-তে Web Notification/Bluetooth API নেই (`Android.notify` ব্যবহার করুন)।
- Firebase (FCM) পুশ নেই: এর জন্য প্রতিটি অ্যাপে `google-services.json` ও Firebase লাইব্রেরি কম্পাইল করে ঢোকাতে হয়, কিন্তু এই বিল্ডার তৈরি-করা টেমপ্লেট প্যাচ করে APK বানায়। বিকল্প হিসেবে ব্যাকগ্রাউন্ড সার্ভিস আছে।
- QR স্ক্যানারের জন্য টেমপ্লেটে `zxing-core` (শুধু Java, ম্যানিফেস্টে কিছু যোগ করে না) আছে, তাই আগের মতো "কোনো লাইব্রেরি নেই" নয়।
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
