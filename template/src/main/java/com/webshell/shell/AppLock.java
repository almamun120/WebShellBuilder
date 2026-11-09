package com.webshell.shell;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;

/**
 * Device-credential lock: fingerprint / face on Android 10+, otherwise the phone's PIN / pattern.
 * If the phone has no screen lock at all, nothing can be verified and {@code unavailable()} is called.
 */
final class AppLock {
    static final int REQ_KEYGUARD = 9101;

    interface Result {
        void ok();

        void denied();

        void unavailable();
    }

    private static Result pending;

    private AppLock() {}

    static boolean isSecure(Context c) {
        KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isDeviceSecure();
    }

    @SuppressWarnings("deprecation")
    static void authenticate(Activity a, String title, Result r) {
        if (!isSecure(a)) {
            r.unavailable();
            return;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                BiometricPrompt.Builder b = new BiometricPrompt.Builder(a).setTitle(title);
                if (Build.VERSION.SDK_INT >= 30) {
                    b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK
                            | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
                } else {
                    b.setDeviceCredentialAllowed(true);
                }
                b.build().authenticate(new CancellationSignal(), a.getMainExecutor(),
                        new BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult res) {
                                r.ok();
                            }

                            @Override
                            public void onAuthenticationError(int code, CharSequence msg) {
                                r.denied();
                            }
                        });
                return;
            } catch (Exception ignored) {
                // fall through to the keyguard screen
            }
        }
        try {
            KeyguardManager km = (KeyguardManager) a.getSystemService(Context.KEYGUARD_SERVICE);
            Intent i = km.createConfirmDeviceCredentialIntent(title, null);
            if (i == null) {
                r.unavailable();
                return;
            }
            pending = r;
            a.startActivityForResult(i, REQ_KEYGUARD);
        } catch (Exception e) {
            pending = null;
            r.unavailable();
        }
    }

    /** Call from Activity.onActivityResult. */
    static void onActivityResult(int req, int res) {
        if (req != REQ_KEYGUARD) return;
        Result r = pending;
        pending = null;
        if (r == null) return;
        if (res == Activity.RESULT_OK) r.ok();
        else r.denied();
    }
}
