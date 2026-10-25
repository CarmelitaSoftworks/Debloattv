package com.carmelita.tvtools;

import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * Par de claves RSA con el que el teléfono se identifica ante la TV. La TV lo recuerda
 * tras aceptar "Permitir siempre", así que solo pregunta la primera vez.
 */
public final class AdbKey {
    private static final int KEY_BITS = 2048;
    private static final int KEY_WORDS = KEY_BITS / 32;

    // Relleno PKCS#1 v1.5 + DigestInfo de SHA-1: adbd trata el token de 20 bytes como un hash SHA-1.
    private static final byte[] SIGNATURE_PADDING = signaturePadding();

    private final PrivateKey privateKey;
    private final RSAPublicKey publicKey;

    private AdbKey(PrivateKey privateKey, RSAPublicKey publicKey) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
    }

    public static AdbKey loadOrCreate(File dir) throws IOException {
        File privFile = new File(dir, "adbkey");
        File pubFile = new File(dir, "adbkey.pub");
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            if (privFile.exists() && pubFile.exists()) {
                return new AdbKey(
                        kf.generatePrivate(new PKCS8EncodedKeySpec(read(privFile))),
                        (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(read(pubFile))));
            }
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(KEY_BITS);
            KeyPair pair = gen.generateKeyPair();
            write(privFile, pair.getPrivate().getEncoded());
            write(pubFile, pair.getPublic().getEncoded());
            return new AdbKey(pair.getPrivate(), (RSAPublicKey) pair.getPublic());
        } catch (GeneralSecurityException e) {
            throw new IOException("No se pudo crear la clave ADB", e);
        }
    }

    byte[] sign(byte[] token) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, privateKey);
            cipher.update(SIGNATURE_PADDING);
            return cipher.doFinal(token);
        } catch (GeneralSecurityException e) {
            throw new IOException("No se pudo firmar el token ADB", e);
        }
    }

    /** Clave pública en el formato RSAPublicKey de mincrypt que espera adbd, en base64. */
    byte[] publicKeyPayload() {
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n = publicKey.getModulus();
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(KEY_BITS).modPow(BigInteger.valueOf(2), n);

        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + KEY_WORDS * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(KEY_WORDS);
        buf.putInt(n0inv.intValue());
        for (int i = 0; i < KEY_WORDS; i++) {
            buf.putInt(n.mod(r32).intValue());
            n = n.shiftRight(32);
        }
        for (int i = 0; i < KEY_WORDS; i++) {
            buf.putInt(rr.mod(r32).intValue());
            rr = rr.shiftRight(32);
        }
        buf.putInt(publicKey.getPublicExponent().intValue());

        String encoded = Base64.encodeToString(buf.array(), Base64.NO_WRAP) + " debloattv@telefono";
        byte[] text = encoded.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[text.length + 1]; // adbd espera la cadena terminada en \0
        System.arraycopy(text, 0, out, 0, text.length);
        return out;
    }

    private static byte[] signaturePadding() {
        byte[] digestInfo = {
                0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
        byte[] pad = new byte[KEY_BITS / 8 - 20];
        pad[0] = 0x00;
        pad[1] = 0x01;
        int ffEnd = pad.length - digestInfo.length - 1;
        for (int i = 2; i < ffEnd; i++) pad[i] = (byte) 0xff;
        pad[ffEnd] = 0x00;
        System.arraycopy(digestInfo, 0, pad, ffEnd + 1, digestInfo.length);
        return pad;
    }

    private static byte[] read(File f) throws IOException {
        byte[] data = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) throw new IOException("Archivo truncado: " + f);
                off += n;
            }
        }
        return data;
    }

    private static void write(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }
}
