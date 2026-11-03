// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent.core.auth;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
import org.junit.BeforeClass;
import org.junit.Test;

public class Rs256SignatureTest {
    private static final String INPUT = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ0ZXN0LW9ubHkifQ";
    private static String modulus, exponent, signed;
    @BeforeClass public static void prepareLocalTestKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair(); RSAPublicKey key = (RSAPublicKey) pair.getPublic();
        modulus = unsigned(key.getModulus()); exponent = unsigned(key.getPublicExponent());
        Signature signer = Signature.getInstance("SHA256withRSA"); signer.initSign(pair.getPrivate());
        signer.update(INPUT.getBytes(StandardCharsets.US_ASCII));
        signed = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
    }
    @Test public void verifiesAuthenticLocallySignedInput() { assertTrue(Rs256Signature.verify(INPUT, signed, modulus, exponent)); }
    @Test public void changedPayloadCannotReuseSignature() { assertFalse(Rs256Signature.verify(INPUT + "A", signed, modulus, exponent)); }
    @Test public void changedSignatureIsRejected() {
        byte[] bytes = Base64.getUrlDecoder().decode(signed); bytes[10] ^= 1;
        assertFalse(Rs256Signature.verify(INPUT, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), modulus, exponent));
    }
    @Test public void truncatedSignatureIsRejected() { assertFalse(Rs256Signature.verify(INPUT, signed.substring(0, 20), modulus, exponent)); }
    @Test public void invalidOrWeakPublicKeyIsRejected() {
        assertFalse(Rs256Signature.verify(INPUT, signed, "Aw", exponent));
        assertFalse(Rs256Signature.verify(INPUT, signed, modulus, "Ag"));
        assertFalse(Rs256Signature.verify(INPUT, signed, "invalid!", exponent));
    }
    @Test public void rejectsPaddingAndDifferentBase64Alphabet() {
        assertFalse(Rs256Signature.verify(INPUT, signed + "=", modulus, exponent));
        assertFalse(Rs256Signature.verify(INPUT, signed, modulus + "/", exponent));
    }
    private static String unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes);
    }
}
