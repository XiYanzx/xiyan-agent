package io.xiyan.agent.shizuku;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import io.xiyan.agent.BuildConfig;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Identity is obtained from the installed package, never from an IPC caller's claimed UID. */
final class AppIdentity {
    final int uid;
    final String signerDigest;
    private final PackageManager packages;

    AppIdentity(Context context) throws PackageManager.NameNotFoundException {
        if (!BuildConfig.APPLICATION_ID.equals(context.getPackageName())) throw denied();
        packages = context.getPackageManager();
        ApplicationInfo info = packages.getApplicationInfo(BuildConfig.APPLICATION_ID, 0);
        if (info.uid < 10_000 || context.getApplicationInfo().uid != info.uid) throw denied();
        uid = info.uid;
        PackageInfo pkg = packages.getPackageInfo(BuildConfig.APPLICATION_ID,
                PackageManager.GET_SIGNING_CERTIFICATES);
        if (pkg.signingInfo == null) throw denied();
        Signature[] signers = pkg.signingInfo.getApkContentsSigners();
        if (signers == null || signers.length == 0 || signers.length > 4) throw denied();
        String[] digests = new String[signers.length];
        for (int i = 0; i < signers.length; i++) digests[i] = digest(signers[i].toByteArray());
        Arrays.sort(digests);
        signerDigest = String.join(":", digests);
        enforceUid(uid);
    }

    void enforceUid(int callerUid) {
        if (callerUid != uid) throw denied();
        String[] names = packages.getPackagesForUid(callerUid);
        if (names == null || names.length != 1 || !BuildConfig.APPLICATION_ID.equals(names[0])) {
            throw denied();
        }
    }

    private static String digest(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) {
                result.append(Character.forDigit((value >>> 4) & 15, 16));
                result.append(Character.forDigit(value & 15, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("sha256_unavailable", unavailable);
        }
    }

    static SecurityException denied() { return new SecurityException("input_caller_denied"); }
}
