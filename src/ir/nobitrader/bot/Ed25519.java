package ir.nobitrader.bot;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Minimal pure-Java Ed25519 (RFC 8032) signer — used for Nobitex's new
 * API-key authentication (Nobitex-Signature header).
 *
 * BigInteger arithmetic keeps the implementation small and auditable; one
 * signature takes a few milliseconds, which is fine for our request rate.
 * Correctness is verified in SelfTest against the official RFC 8032
 * test vectors.
 */
public final class Ed25519 {

    private Ed25519() {
    }

    // field prime p = 2^255 - 19
    private static final BigInteger P =
            new BigInteger("7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffed", 16);
    // group order L = 2^252 + 27742317777372353535851937790883648493
    private static final BigInteger L =
            new BigInteger("1000000000000000000000000000000014def9dea2f79cd65812631a5cf5d3ed", 16);
    // curve constant d = -121665/121666 mod p
    private static final BigInteger D =
            new BigInteger("52036cee2b6ffe738cc740797779e89800700a4d4141d8ab75eb4dca135978a3", 16);
    // base point B
    private static final BigInteger BX =
            new BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202");
    private static final BigInteger BY =
            new BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960");

    /** point in extended homogeneous coordinates (X:Y:Z:T), x=X/Z, y=Y/Z, T=XY/Z */
    private static BigInteger[] point(BigInteger x, BigInteger y) {
        return new BigInteger[]{x, y, BigInteger.ONE, x.multiply(y).mod(P)};
    }

    /** unified point addition (also works for doubling) */
    private static BigInteger[] add(BigInteger[] p1, BigInteger[] p2) {
        BigInteger a = p1[1].subtract(p1[0]).multiply(p2[1].subtract(p2[0])).mod(P);
        BigInteger b = p1[1].add(p1[0]).multiply(p2[1].add(p2[0])).mod(P);
        BigInteger c = p1[3].multiply(D).multiply(p2[3]).shiftLeft(1).mod(P);
        BigInteger d = p1[2].multiply(p2[2]).shiftLeft(1).mod(P);
        BigInteger e = b.subtract(a);
        BigInteger f = d.subtract(c);
        BigInteger g = d.add(c);
        BigInteger h = b.add(a);
        return new BigInteger[]{
                e.multiply(f).mod(P), g.multiply(h).mod(P),
                f.multiply(g).mod(P), e.multiply(h).mod(P)};
    }

    /** scalar multiplication k*P (double-and-add from the most significant bit) */
    private static BigInteger[] mul(BigInteger k, BigInteger[] p) {
        BigInteger[] r = new BigInteger[]{BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO};
        byte[] kb = k.toByteArray();
        for (byte x : kb) {
            for (int b = 7; b >= 0; b--) {
                r = add(r, r);
                if ((x & (1 << b)) != 0) r = add(r, p);
            }
        }
        return r;
    }

    /** 32-byte little-endian encoding of a point (y with the sign bit of x) */
    private static byte[] encode(BigInteger[] p) {
        BigInteger zInv = p[2].modInverse(P);
        BigInteger x = p[0].multiply(zInv).mod(P);
        BigInteger y = p[1].multiply(zInv).mod(P);
        byte[] out = new byte[32];
        byte[] yb = y.toByteArray(); // big-endian, possibly shorter than 32
        for (int i = 0; i < 32 && i < yb.length; i++) {
            out[i] = yb[yb.length - 1 - i];
        }
        if (x.testBit(0)) out[31] |= (byte) 0x80;
        return out;
    }

    /** little-endian bytes -> positive BigInteger */
    private static BigInteger le(byte[] b) {
        byte[] rev = new byte[b.length];
        for (int i = 0; i < b.length; i++) rev[i] = b[b.length - 1 - i];
        return new BigInteger(1, rev);
    }

    /** positive BigInteger -> exactly n little-endian bytes */
    private static byte[] le(BigInteger v, int n) {
        byte[] big = v.toByteArray(); // big-endian
        byte[] out = new byte[n];
        for (int i = 0; i < n && i < big.length; i++) {
            out[i] = big[big.length - 1 - i];
        }
        return out;
    }

    private static byte[] sha512(byte[] in) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            return md.digest(in);
        } catch (Exception e) {
            throw new RuntimeException("SHA-512 unavailable", e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b, byte[] c) {
        byte[] out = new byte[a.length + b.length + c.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        System.arraycopy(c, 0, out, a.length + b.length, c.length);
        return out;
    }

    /** derive the 32-byte Ed25519 public key from a 32-byte seed */
    public static byte[] publicKey(byte[] seed) {
        byte[] h = sha512(seed);
        byte[] a = Arrays.copyOfRange(h, 0, 32);
        a[0] &= (byte) 248;
        a[31] &= (byte) 127;
        a[31] |= (byte) 64;
        return encode(mul(le(a), point(BX, BY)));
    }

    /**
     * RFC 8032 Ed25519 signature over msg with a 32-byte seed.
     * Returns the 64-byte signature (R || S).
     */
    public static byte[] sign(byte[] seed, byte[] msg) {
        byte[] h = sha512(seed);
        byte[] aBytes = Arrays.copyOfRange(h, 0, 32);
        aBytes[0] &= (byte) 248;
        aBytes[31] &= (byte) 127;
        aBytes[31] |= (byte) 64;
        BigInteger a = le(aBytes);
        byte[] prefix = Arrays.copyOfRange(h, 32, 64);
        byte[] pub = publicKey(seed);

        BigInteger r = le(sha512(concat(prefix, msg, new byte[0]))).mod(L);
        byte[] rEnc = encode(mul(r, point(BX, BY)));

        BigInteger k = le(sha512(concat(rEnc, pub, msg))).mod(L);
        BigInteger s = r.add(k.multiply(a).mod(L)).mod(L);
        byte[] sEnc = le(s, 32);

        byte[] out = new byte[64];
        System.arraycopy(rEnc, 0, out, 0, 32);
        System.arraycopy(sEnc, 0, out, 32, 32);
        return out;
    }

    // ------------------------------------------------------------------ base64

    private static final String B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    /** standard base64 encode (with padding) — works on JVM and Android */
    public static String b64Encode(byte[] in) {
        StringBuilder sb = new StringBuilder((in.length * 4 + 2) / 3 * 4);
        int i = 0;
        while (i + 2 < in.length) {
            int v = ((in[i] & 0xff) << 16) | ((in[i + 1] & 0xff) << 8) | (in[i + 2] & 0xff);
            sb.append(B64.charAt(v >> 18)).append(B64.charAt((v >> 12) & 63))
                    .append(B64.charAt((v >> 6) & 63)).append(B64.charAt(v & 63));
            i += 3;
        }
        int rem = in.length - i;
        if (rem == 1) {
            int v = (in[i] & 0xff) << 16;
            sb.append(B64.charAt(v >> 18)).append(B64.charAt((v >> 12) & 63)).append("==");
        } else if (rem == 2) {
            int v = ((in[i] & 0xff) << 16) | ((in[i + 1] & 0xff) << 8);
            sb.append(B64.charAt(v >> 18)).append(B64.charAt((v >> 12) & 63))
                    .append(B64.charAt((v >> 6) & 63)).append("=");
        }
        return sb.toString();
    }

    /**
     * lenient base64 decode: accepts both the standard and the URL-safe
     * alphabet, with or without padding (Nobitex keys use the URL-safe form).
     */
    public static byte[] b64Decode(String s) {
        if (s == null) return new byte[0];
        String t = s.trim().replace("\n", "").replace("\r", "").replace(" ", "");
        // url-safe -> standard
        t = t.replace('-', '+').replace('_', '/');
        while (t.length() % 4 != 0) t += "=";

        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        int buf = 0, bits = 0;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch == '=') break;
            int v = B64.indexOf(ch);
            if (v < 0) throw new IllegalArgumentException("کاراکتر نامعتبر در کلید: '" + ch + "'");
            buf = (buf << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                bos.write((buf >> bits) & 0xff);
            }
        }
        return bos.toByteArray();
    }
}
