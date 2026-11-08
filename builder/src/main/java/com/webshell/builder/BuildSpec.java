package com.webshell.builder;

import java.io.File;
import java.util.LinkedHashSet;
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
}
