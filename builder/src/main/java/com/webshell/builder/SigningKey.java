package com.webshell.builder;

import android.content.Context;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;

/**
 * Per-package signing key. Created once and stored in the builder's private storage so that
 * rebuilding the same package produces an APK that can update the previous install.
 */
final class SigningKey {
    final PrivateKey key;
    final X509Certificate cert;

    private SigningKey(PrivateKey key, X509Certificate cert) {
        this.key = key;
        this.cert = cert;
    }

    static SigningKey loadOrCreate(Context c, String pkg, String appName) throws Exception {
        File dir = new File(c.getFilesDir(), "keys");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File fk = new File(dir, pkg + ".pk8");
        File fc = new File(dir, pkg + ".der");

        if (fk.exists() && fc.exists()) {
            PrivateKey k = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(fk.toPath())));
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(Files.readAllBytes(fc.toPath())));
            return new SigningKey(k, cert);
        }

        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        X500Name name = new X500Name("CN=" + sanitize(appName) + ", O=WebShell Builder");
        BigInteger serial = new BigInteger(64, new SecureRandom());
        Date from = new Date(System.currentTimeMillis() - 86_400_000L);
        Date to = new Date(System.currentTimeMillis() + 36_500L * 86_400_000L);
        JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(name, serial, from, to, name, kp.getPublic());
        ContentSigner cs = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(b.build(cs));

        Files.write(fk.toPath(), kp.getPrivate().getEncoded());
        Files.write(fc.toPath(), cert.getEncoded());
        return new SigningKey(kp.getPrivate(), cert);
    }

    private static String sanitize(String s) {
        String r = s.replaceAll("[,=+<>#;\"\\\\]", " ").trim();
        return r.isEmpty() ? "App" : r;
    }
}
