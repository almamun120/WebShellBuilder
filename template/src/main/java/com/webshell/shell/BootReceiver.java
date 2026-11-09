package com.webshell.shell;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the background service after the phone boots (only if enabled in the build). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (i == null || !Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
        AppConfig cfg = AppConfig.load(c);
        if (cfg.bgService && cfg.bgOnBoot && PollService.enabled(c)) PollService.start(c);
    }
}
