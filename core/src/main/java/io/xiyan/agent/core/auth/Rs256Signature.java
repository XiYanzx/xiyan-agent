// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.core.auth;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/** Signature verification only; trusted JWKS selection and ID claims remain mandatory. */
public final class Rs256Signature {
    private Rs256Signature() { }
    public static boolean verify(String signingInput, String signature, String modulus, String exponent) {
        try {
            if (signingInput == null || signingInput.length() > 131072
                    || !signingInput.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) return false;
            byte[] n = decode(modulus, 2048), e = decode(exponent, 16), signed = decode(signature, 2048);
            BigInteger number = new BigInteger(1, n), power = new BigInteger(1, e);
            if (number.bitLength() < 2048 || number.bitLength() > 8192 || !number.testBit(0)
                    || power.bitLength() > 32 || power.compareTo(BigInteger.valueOf(3)) < 0 || !power.testBit(0)
                    || signed.length != (number.bitLength() + 7) / 8) return false;
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(number, power)));
            verifier.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return verifier.verify(signed);
        } catch (Exception invalid) { return false; }
    }
    private static byte[] decode(String text, int cap) {
        if (text == null || text.isEmpty() || text.length() > cap || !text.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid public signature input");
        }
        byte[] bytes = Base64.getUrlDecoder().decode(text);
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(text)) {
            throw new IllegalArgumentException("Noncanonical public signature input");
        }
        return bytes;
    }
}
