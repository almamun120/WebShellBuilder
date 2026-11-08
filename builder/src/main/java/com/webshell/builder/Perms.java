package com.webshell.builder;

/** Selectable permission groups shown in the builder UI. */
final class Perms {
    private Perms() {}

    static final class Group {
        final String key;
        final String label;
        final String desc;
        final boolean defaultOn;
        final String[] manifest;

        Group(String key, String label, String desc, boolean defaultOn, String... manifest) {
            this.key = key;
            this.label = label;
            this.desc = desc;
            this.defaultOn = defaultOn;
            this.manifest = manifest;
        }
    }

    static final String A = "android.permission.";

    static final Group[] ALL = {
            new Group("camera", "ক্যামেরা", "ওয়েবে ক্যামেরা, ছবি তোলা, QR স্ক্যান", true, A + "CAMERA"),
            new Group("microphone", "মাইক্রোফোন", "ভয়েস/অডিও রেকর্ড, ভিডিও কল", true, A + "RECORD_AUDIO", A + "MODIFY_AUDIO_SETTINGS"),
            new Group("location", "লোকেশন", "GPS / geolocation", true, A + "ACCESS_FINE_LOCATION", A + "ACCESS_COARSE_LOCATION"),
            new Group("call", "ফোন কল", "সরাসরি কল করা (Android.call)", false, A + "CALL_PHONE"),
            new Group("sms", "এসএমএস পাঠানো", "সরাসরি SMS পাঠানো (Android.sendSms)", false, A + "SEND_SMS"),
            new Group("contacts", "কন্টাক্ট", "কন্টাক্ট পড়া", false, A + "READ_CONTACTS"),
            new Group("storage", "ফাইল / স্টোরেজ", "ছবি, ভিডিও, অডিও ও ডাউনলোড", true,
                    A + "READ_EXTERNAL_STORAGE", A + "WRITE_EXTERNAL_STORAGE",
                    A + "READ_MEDIA_IMAGES", A + "READ_MEDIA_VIDEO", A + "READ_MEDIA_AUDIO"),
            new Group("notifications", "নোটিফিকেশন", "Android.notify দিয়ে নোটিফিকেশন", true, A + "POST_NOTIFICATIONS"),
            new Group("vibrate", "ভাইব্রেশন", "ফোন ভাইব্রেট করা", true, A + "VIBRATE"),
            new Group("wakelock", "স্ক্রিন জাগিয়ে রাখা", "WAKE_LOCK", false, A + "WAKE_LOCK"),
            new Group("apps", "অন্য অ্যাপ খোলা", "ইনস্টল করা যেকোনো অ্যাপ প্যাকেজ নামে খোলা", true, A + "QUERY_ALL_PACKAGES"),
            new Group("bluetooth", "ব্লুটুথ", "ব্লুটুথ সংযোগ", false,
                    A + "BLUETOOTH_CONNECT", A + "BLUETOOTH_SCAN", A + "BLUETOOTH", A + "BLUETOOTH_ADMIN"),
    };

    static String[] manifestNames(String key) {
        for (Group g : ALL) if (g.key.equals(key)) return g.manifest;
        return new String[0];
    }
}
