package com.webshell.builder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

public class InstallReceiver extends BroadcastReceiver {
    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context c, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(confirm);
                }
                break;
            case PackageInstaller.STATUS_SUCCESS:
                Toast.makeText(c, "ইনস্টল সম্পন্ন হয়েছে", Toast.LENGTH_LONG).show();
                break;
            default:
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(c, "ইনস্টল ব্যর্থ: " + msg, Toast.LENGTH_LONG).show();
        }
    }
}
