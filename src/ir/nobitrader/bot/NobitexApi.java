package ir.nobitrader.bot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * Minimal client for the Nobitex API (https://apiv2.nobitex.ir).
 * Public endpoints need no auth. Two authentication modes are supported:
 *  - classic panel token:  "Authorization: Token X" header
 *  - new API key + secret: Ed25519-signed headers
 *      Nobitex-Key / Nobitex-Timestamp / Nobitex-Signature
 *    where signature = base64(Ed25519(timestamp + method + url + body))
 */
public class NobitexApi {

    public static final String BASE = "https://apiv2.nobitex.ir";

    /** minimum order values enforced by Nobitex (rials / usdt) */
    public static final double MIN_RLS = 3_000_000;
    public static final double MIN_USDT = 11;

    /** classic token, or the PUBLIC key of the new API-key system */
    private final String token;
    /** privateKey of the new API-key system (empty = classic token mode) */
    private final String apiSecret;

    public NobitexApi(String token) {
        this(token, "");
    }

    public NobitexApi(String token, String apiSecret) {
        this.token = token == null ? "" : token.trim();
        this.apiSecret = apiSecret == null ? "" : apiSecret.trim();
    }

    /** true when using the new Ed25519-signed API key */
    public boolean isKeyAuth() {
        return !apiSecret.isEmpty();
    }

    public static class ApiError extends Exception {
        public final int http;
        public final String code;

        public ApiError(String msg, int http, String code) {
            super(msg);
            this.http = http;
            this.code = code;
        }
    }

    // ------------------------------------------------------------------

    private String http(String path, boolean post, String jsonBody, boolean auth) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(25000);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "TraderBot/NobiTrader");
            if (auth) {
                if (token.isEmpty()) {
                    throw new ApiError("کلید API تنظیم نشده است", 0, "NoToken");
                }
                if (isKeyAuth()) {
                    // new API-key mode: Ed25519-signed request
                    String ts = String.valueOf(System.currentTimeMillis() / 1000L);
                    String method = post ? "POST" : "GET";
                    String bodyStr = (post && jsonBody != null) ? jsonBody : "";
                    String msg = ts + method + path + bodyStr;
                    byte[] seed = Ed25519.b64Decode(apiSecret);
                    if (seed.length != 32) {
                        throw new ApiError("سکرت کی نامعتبر است — کلید خصوصی باید ۳۲ بایت باشد", 0, "BadSecret");
                    }
                    byte[] sig = Ed25519.sign(seed, msg.getBytes(StandardCharsets.UTF_8));
                    c.setRequestProperty("Nobitex-Key", token);
                    c.setRequestProperty("Nobitex-Timestamp", ts);
                    c.setRequestProperty("Nobitex-Signature", Ed25519.b64Encode(sig));
                } else {
                    c.setRequestProperty("Authorization", "Token " + token);
                }
            }
            if (post) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                if (jsonBody != null) {
                    byte[] b = jsonBody.getBytes(StandardCharsets.UTF_8);
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    c.setFixedLengthStreamingMode(b.length);
                    OutputStream os = c.getOutputStream();
                    os.write(b);
                    os.flush();
                    os.close();
                }
            }
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (is == null) throw new ApiError("پاسخی از سرور دریافت نشد", code, "NoBody");
            if ("gzip".equalsIgnoreCase(c.getContentEncoding())) {
                is = new GZIPInputStream(is);
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            String body = sb.toString();
            if (code == 429) {
                throw new ApiError("تعداد درخواست‌ها زیاد است؛ کمی صبر کنید (429)", 429, "TooManyRequests");
            }
            if (code >= 400) {
                String em = "خطای سرور " + code;
                try {
                    JSONObject eo = new JSONObject(body);
                    String m = errText(eo);
                    if (!m.isEmpty()) em = m + " (HTTP " + code + ")";
                } catch (Throwable ignored) {
                }
                throw new ApiError(em, code, "Http" + code);
            }
            return body;
        } finally {
            c.disconnect();
        }
    }

    private static String errText(JSONObject o) {
        String m = o.optString("message", "");
        String cd = o.optString("code", "");
        if (m.isEmpty()) return cd.isEmpty() ? "خطای نامشخص" : cd;
        return cd.isEmpty() ? m : m + " (" + cd + ")";
    }

    // ------------------------------------------------------------------

    /** OHLCV history (last candle may be the still-forming one). */
    public Candle[] udfHistory(String symbol, String resolution, int countback) throws Exception {
        long to = System.currentTimeMillis() / 1000L;
        String path = "/market/udf/history?symbol=" + symbol + "&resolution=" + resolution
                + "&to=" + to + "&countback=" + countback;
        JSONObject o = new JSONObject(http(path, false, null, false));
        if (!"ok".equals(o.optString("s"))) {
            throw new ApiError(o.optString("errmsg", "خطای دریافت کندل"), 0, "UdfError");
        }
        JSONArray t = o.getJSONArray("t");
        JSONArray op = o.getJSONArray("o");
        JSONArray h = o.getJSONArray("h");
        JSONArray l = o.getJSONArray("l");
        JSONArray cl = o.getJSONArray("c");
        JSONArray v = o.getJSONArray("v");
        int n = Math.min(t.length(), Math.min(cl.length(), op.length()));
        Candle[] out = new Candle[n];
        for (int i = 0; i < n; i++) {
            out[i] = new Candle(t.getLong(i), op.getDouble(i), h.getDouble(i),
                    l.getDouble(i), cl.getDouble(i), v.getDouble(i));
        }
        return out;
    }

    /** last traded price from the v3 orderbook */
    public double lastPrice(String symbol) throws Exception {
        return book(symbol).last;
    }

    /** top of the order book: best bid / best ask / last trade */
    public static class Book {
        public double bid, ask, last;

        /** relative spread between best ask and bid (fraction, e.g. 0.004 = 0.4%) */
        public double spreadPct() {
            if (bid <= 0 || ask <= 0) return 0;
            return (ask / bid - 1.0) * 100.0;
        }
    }

    public Book book(String symbol) throws Exception {
        JSONObject o = new JSONObject(http("/v3/orderbook/" + symbol, false, null, false));
        if (!"ok".equals(o.optString("status"))) {
            throw new ApiError("خطای دریافت دفتر سفارش", 0, "OrderbookError");
        }
        Book b = new Book();
        b.last = o.optDouble("lastTradePrice", 0);
        JSONArray bids = o.optJSONArray("bids");
        JSONArray asks = o.optJSONArray("asks");
        if (bids != null && bids.length() > 0) b.bid = bids.getJSONArray(0).getDouble(0);
        if (asks != null && asks.length() > 0) b.ask = asks.getJSONArray(0).getDouble(0);
        if (b.bid <= 0) b.bid = b.last;
        if (b.ask <= 0) b.ask = b.last;
        return b;
    }

    public static class DayStats {
        public double latest, dayOpen, dayClose, dayHigh, dayLow, volumeDst;

        public double changePct() {
            if (dayOpen <= 0) return 0;
            return (dayClose / dayOpen - 1.0) * 100.0;
        }
    }

    public DayStats stats(String src, String dst) throws Exception {
        JSONObject o = new JSONObject(http("/market/stats?srcCurrency=" + src + "&dstCurrency=" + dst,
                false, null, false));
        if (!"ok".equals(o.optString("status"))) {
            throw new ApiError("خطای دریافت آمار بازار", 0, "StatsError");
        }
        JSONObject s = o.getJSONObject("stats").getJSONObject(src.toLowerCase() + "-" + dst.toLowerCase());
        DayStats d = new DayStats();
        d.latest = s.getDouble("latest");
        d.dayOpen = s.optDouble("dayOpen", d.latest);
        d.dayClose = s.optDouble("dayClose", d.latest);
        d.dayHigh = s.optDouble("dayHigh", d.latest);
        d.dayLow = s.optDouble("dayLow", d.latest);
        d.volumeDst = s.optDouble("volumeDst", 0);
        return d;
    }

    // ------------------------------------------------------------------

    /** wallet list: {"status":"ok","wallets":[{currency,balance,activeBalance,...}]} */
    public JSONObject wallets() throws Exception {
        JSONObject o = new JSONObject(http("/users/wallets/list", false, null, true));
        if (!"ok".equals(o.optString("status"))) {
            throw new ApiError(errText(o), 0, "WalletError");
        }
        return o;
    }

    /** available (active) balance of a currency, -1 if wallet not found */
    public double walletBalance(String currency) throws Exception {
        JSONArray ws = wallets().getJSONArray("wallets");
        String cur = currency.toLowerCase();
        for (int i = 0; i < ws.length(); i++) {
            JSONObject w = ws.getJSONObject(i);
            if (cur.equals(w.optString("currency", "").toLowerCase())) {
                double active = w.optDouble("activeBalance", -1);
                if (active < 0) {
                    double bal = w.optDouble("balance", 0);
                    double blocked = w.optDouble("blockedBalance", 0);
                    active = bal - blocked;
                }
                return active;
            }
        }
        return -1;
    }

    /**
     * Place a spot MARKET order.
     *
     * @param type   "buy" or "sell"
     * @param amount quantity in base currency (e.g. btc)
     */
    public JSONObject addMarketOrder(String type, String src, String dst, double amount) throws Exception {
        JSONObject b = new JSONObject();
        b.put("type", type);
        b.put("execution", "market");
        b.put("srcCurrency", src);
        b.put("dstCurrency", dst);
        b.put("amount", Fmt.amount(amount));
        JSONObject o = new JSONObject(http("/market/orders/add", true, b.toString(), true));
        if (!"ok".equals(o.optString("status"))) {
            throw new ApiError(errText(o), 0, o.optString("code", "OrderError"));
        }
        return o.optJSONObject("order");
    }

    /** order status by id (returns the "order" object) */
    public JSONObject orderStatus(long id) throws Exception {
        JSONObject b = new JSONObject();
        b.put("id", id);
        JSONObject o = new JSONObject(http("/market/orders/status", true, b.toString(), true));
        if (!"ok".equals(o.optString("status"))) {
            throw new ApiError(errText(o), 0, "OrderStatusError");
        }
        return o.optJSONObject("order");
    }

    // ------------------------------------------------------------------ connection test

    /** result of a connection check */
    public static class ConnResult {
        public boolean ok;
        public String detail;

        public ConnResult(boolean ok, String detail) {
            this.ok = ok;
            this.detail = detail;
        }
    }

    /**
     * Verify the credentials against a lightweight authenticated endpoint
     * (GET /users/profile) and return a human-readable Persian result.
     */
    public ConnResult testConnection() {
        if (token.isEmpty()) {
            return new ConnResult(false, "کلید API وارد نشده است");
        }
        try {
            JSONObject o = new JSONObject(http("/users/profile", false, null, true));
            if ("ok".equals(o.optString("status"))) {
                String who = "";
                JSONObject pr = o.optJSONObject("profile");
                if (pr != null) {
                    String em = pr.optString("email", "");
                    if (!em.isEmpty()) who = "\nحساب: " + em;
                }
                return new ConnResult(true, (isKeyAuth()
                        ? "✅ متصل شد (کلید API جدید با امضای Ed25519)" + who
                        : "✅ متصل شد (توکن کلاسیک)" + who));
            }
            return new ConnResult(false, "پاسخ غیرمنتظره: " + errText(o));
        } catch (ApiError e) {
            String hint = "";
            if (e.http == 401 || e.http == 403) {
                hint = isKeyAuth()
                        ? "\n\nراهنما: کلید عمومی و سکرت کی را دقیقاً و کامل کپی کنید؛ کلید باید مجوز READ و TRADE داشته باشد؛ اگر هنگام ساخت کلید «لیست سفید IP» فعال کرده‌اید، گوشی از آن IPها وصل نمی‌شود."
                        : "\n\nراهنما: توکن کلاسیک را از پنل نوبیتکس (بخش API) کامل کپی کنید. اگر کلید جدید (با سکرت کی) دارید، سکرت کی را در فیلد پایین وارد کنید.";
            }
            return new ConnResult(false, "❌ " + e.getMessage() + hint);
        } catch (Exception e) {
            return new ConnResult(false, "❌ خطا: " + e.getMessage()
                    + "\n\nاینترنت/VPN گوشی را بررسی کنید.");
        }
    }
}
