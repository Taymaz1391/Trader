package ir.nobitrader.bot;

/**
 * LIVE connection test against the REAL Nobitex production API.
 * Runs on a GitHub Actions runner (open internet) using the exact same
 * NobitexApi / Ed25519 classes the Android app ships with.
 *
 * Checks:
 *   1. public data pipeline the bot depends on (orderbook / candles / stats)
 *   2. the new Ed25519 API-key auth path — with a randomly generated
 *      (invalid) key pair, to verify the server actually parses our
 *      Nobitex-Key / Nobitex-Signature / Nobitex-Timestamp headers and
 *      to capture the exact error text shown for bad credentials
 *   3. the classic token auth path for comparison
 *
 * Exit code 0 = public pipeline OK (auth errors with fake keys are
 * expected and are the diagnostic information we want to print).
 */
public class LiveTest {

    public static void main(String[] args) {
        int failures = 0;

        System.out.println("=== 0) Nobitex key-pair math (docs example key pair) ===");
        {
            // from POST /apikeys/create in the official docs:
            String docKey = "5XOCQZSPLQM4MiLzuUnZoBuqgYgTKl40W2X5j1pxfIA=";
            String docPriv = "S5y19KewZzheCWCO4xqMcwwvtR8vQ-hHjE_cdjz-XxE=";
            boolean valid = NobitexApi.secretLooksValid(docPriv);
            System.out.println("secret looks valid : " + valid);
            boolean derived = false;
            try {
                derived = java.util.Arrays.equals(
                        Ed25519.b64Decode(docKey), NobitexApi.derivePublicBytes(docPriv));
            } catch (Throwable t) {
                System.out.println("  derive error: " + t);
            }
            System.out.println("public == derive(private): " + derived);
            if (!valid || !derived) {
                failures++;
                System.out.println("  FAIL: Nobitex key-pair derivation mismatch");
            } else {
                System.out.println("  OK — the app can verify/repair key pairs locally");
            }
        }

        System.out.println();
        System.out.println("=== 1) public market data (the bot's data pipeline) ===");
        NobitexApi pub = new NobitexApi(null);
        try {
            NobitexApi.Book b = pub.book("BTCIRT");
            System.out.println("orderbook BTCIRT: bid=" + b.bid + " ask=" + b.ask
                    + " last=" + b.last + " spread=" + String.format(java.util.Locale.US, "%.3f%%", b.spreadPct()));
            if (b.last <= 0 || b.bid <= 0 || b.ask <= 0) {
                failures++;
                System.out.println("  FAIL: empty orderbook");
            } else {
                System.out.println("  OK");
            }
        } catch (Exception e) {
            failures++;
            System.out.println("  FAIL orderbook: " + e);
        }
        try {
            NobitexApi.Book bk = pub.book("BTCIRT");
            Candle[] cs = pub.udfHistory("BTCIRT", "60", 24, bk.last);
            double lastClose = cs[cs.length - 1].c;
            double ratio = lastClose / bk.last;
            System.out.println("udf history 1h x24: " + cs.length + " candles, last close=" + lastClose
                    + " (book last=" + bk.last + ", ratio=" + String.format(java.util.Locale.US, "%.4f", ratio) + ")");
            if (cs.length < 10) {
                failures++;
                System.out.println("  FAIL: too few candles");
            } else if (Math.abs(ratio - 1.0) > 0.02) {
                failures++;
                System.out.println("  FAIL: candle unit != orderbook unit (ratio " + ratio + ")");
            } else {
                System.out.println("  OK — candles normalized to the orderbook quote unit");
            }
        } catch (Exception e) {
            failures++;
            System.out.println("  FAIL udf history: " + e);
        }
        try {
            NobitexApi.DayStats st = pub.stats("btc", "rls");
            System.out.println("stats BTC/RLS: latest=" + st.latest + " 24h ch=" + String.format(java.util.Locale.US, "%.2f%%", st.changePct()));
            if (st.latest <= 0) {
                failures++;
                System.out.println("  FAIL: no stats");
            } else {
                System.out.println("  OK");
            }
        } catch (Exception e) {
            failures++;
            System.out.println("  FAIL stats: " + e);
        }

        System.out.println();
        System.out.println("=== 2) API-key auth path (Ed25519-signed, random FAKE key) ===");
        // random 32-byte seed -> the app signs exactly like a real key would
        byte[] seed = new byte[32];
        new java.util.Random(123456789L).nextBytes(seed);
        String fakeSecret = Ed25519.b64Encode(seed);          // url-safe style like the panel shows
        String fakeKey = Ed25519.b64Encode(Ed25519.sign(seed, "probe".getBytes())); // random-looking base64 key
        System.out.println("fake public key : " + fakeKey);
        System.out.println("fake secret     : " + fakeSecret.substring(0, 8) + "…(32 bytes)");
        try {
            NobitexApi fakeApi = new NobitexApi(fakeKey, fakeSecret);
            System.out.println("mode detected   : " + (fakeApi.isKeyAuth() ? "API-key (Ed25519)" : "classic"));
            NobitexApi.ConnResult r = fakeApi.testConnection();
            System.out.println("server response : ok=" + r.ok);
            System.out.println(r.detail);
            System.out.println("  -> the server REACHED the auth layer and answered (see message above).");
        } catch (Exception e) {
            failures++;
            System.out.println("  FAIL (network/format): " + e);
        }

        System.out.println();
        System.out.println("=== 3) classic token auth path (fake token) ===");
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 40; i++) sb.append('a');
            NobitexApi.ConnResult r = new NobitexApi(sb.toString(), "").testConnection();
            System.out.println("server response : ok=" + r.ok);
            System.out.println(r.detail);
        } catch (Exception e) {
            failures++;
            System.out.println("  FAIL: " + e);
        }

        System.out.println();
        System.out.println("=== 4) REAL credentials (from env NOBI_KEY/NOBI_SECRET) ===");
        String rk = System.getenv("NOBI_KEY");
        String rs = System.getenv("NOBI_SECRET");
        if (rk == null || rk.isEmpty() || rs == null || rs.isEmpty()) {
            System.out.println("no real credentials provided — skipped (this is normal)");
        } else {
            try {
                NobitexApi real = new NobitexApi(rk, rs);
                System.out.println("mode            : " + (real.isKeyAuth() ? "API-key (Ed25519)" : "classic token"));
                NobitexApi.ConnResult rr = real.testConnection();
                System.out.println("testConnection  : ok=" + rr.ok);
                System.out.println(rr.detail);
                if (rr.ok) {
                    double rls = real.walletBalance("rls");
                    System.out.println("wallet RLS      : " + rls);
                }
            } catch (Exception e) {
                failures++;
                System.out.println("  FAIL real-key test: " + e);
            }
        }

        System.out.println();
        if (failures == 0) {
            System.out.println("LIVE TEST: public pipeline OK, auth paths answered by the real server.");
        } else {
            System.out.println("LIVE TEST: " + failures + " hard failure(s).");
            System.exit(1);
        }
    }
}
