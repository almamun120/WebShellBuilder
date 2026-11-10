package com.webshell.builder;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** All settings that go into one generated APK. */
final class BuildSpec {
    String appName;
    String pkg;
    String url;
    String versionName = "1.0";
    int versionCode = 1;
    File iconFile;
    File splashFile;
    int splashBg = 0xFFFFFFFF;
    int accent = 0xFF3B82F6;
    int statusColor = 0;
    int splashMs = 1500;
    boolean splashName = true;
    String orientation = "auto";
    boolean fullscreen;
    boolean doubleBackExit = true;
    boolean keepScreenOn;
    boolean ignoreSsl;
    boolean desktop;
    boolean externalLinks;
    boolean jsBridge = true;
    boolean askOnStart = true;
    boolean zoom = true;
    String userAgentSuffix = "";
    final Set<String> perms = new LinkedHashSet<>();

    // ---- texts (empty = built-in default)
    String exitMessage = "";
    String offlineTitle = "";
    String offlineMessage = "";
    String serverTitle = "";
    String serverMessage = "";
    String retryLabel = "";
    String lockTitle = "";
    String unlockLabel = "";
    String scanHint = "";
    String bgNotifText = "";

    // ---- splash layout: logo | fullFit | fullCrop | fullStretch
    String splashMode = "logo";
    int splashSize = 180;
    boolean splashSpinner = true;

    // ---- extra features
    boolean pullRefresh = true;
    String theme = "system";          // system | light | dark
    boolean forceDarkWeb;
    String lock = "none";             // none | device
    int lockTimeoutSec = 30;
    boolean offlineCache;
    boolean deepLinks;
    boolean shareTarget;
    boolean printing = true;
    boolean pdfViewer = true;
    boolean mediaPlayer;
    String mediaExt = "";             // comma separated, e.g. "mp3,mp4,m3u8"
    String navMode = "none";          // none | bottom
    final List<String[]> navItems = new ArrayList<>();    // {title, url}
    final List<String[]> shortcuts = new ArrayList<>();   // {title, url}
    boolean bgService;
    boolean bgOnBoot;
    String pollUrl = "";
    int pollSec = 60;
}
