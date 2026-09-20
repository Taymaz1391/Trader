package ir.nobitrader.bot;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * All persisted configuration + bot state, kept in SharedPreferences as
 * primitives and small JSON strings.
 */
public class Prefs {

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences("nobitrader", Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- config

    public static class Cfg {
        public String token = "";
        public String symbol = "BTCIRT";
        public String resolution = "60";   // candle timeframe
        public int intervalSec = 300;      // check interval
        public int strategyId = 0;
        public double tradeAmount = 500000; // quote units: toman (IRT markets) or usdt
        public double slPct = 4;
        public double tpPct = 8;
        public double trailingPct = 0;      // trailing stop, 0 = off
        public boolean live = false;
        public String tgToken = "";         // telegram bot token (optional)
        public String tgChat = "";          // telegram chat id (optional)
    }

    public Cfg cfg() {
        Cfg c = new Cfg();
        c.token = sp.getString("token", "");
        c.symbol = sp.getString("symbol", "BTCIRT");
        c.resolution = sp.getString("resolution", "60");
        c.intervalSec = sp.getInt("intervalSec", 300);
        c.strategyId = sp.getInt("strategyId", 0);
        c.tradeAmount = (double) sp.getFloat("tradeAmount", 500000f);
        c.slPct = (double) sp.getFloat("slPct", 4f);
        c.tpPct = (double) sp.getFloat("tpPct", 8f);
        c.trailingPct = (double) sp.getFloat("trailingPct", 0f);
        c.live = sp.getBoolean("live", false);
        c.tgToken = sp.getString("tgToken", "");
        c.tgChat = sp.getString("tgChat", "");
        return c;
    }

    public void saveCfg(Cfg c) {
        sp.edit()
                .putString("token", c.token)
                .putString("symbol", c.symbol)
                .putString("resolution", c.resolution)
                .putInt("intervalSec", c.intervalSec)
                .putInt("strategyId", c.strategyId)
                .putFloat("tradeAmount", (float) c.tradeAmount)
                .putFloat("slPct", (float) c.slPct)
                .putFloat("tpPct", (float) c.tpPct)
                .putFloat("trailingPct", (float) c.trailingPct)
                .putBoolean("live", c.live)
                .apply();
    }

    // convenience single-field setters used by the UI
    public void setToken(String v) { sp.edit().putString("token", v).apply(); }
    public void setSymbol(String v) { sp.edit().putString("symbol", v).apply(); }
    public void setResolution(String v) { sp.edit().putString("resolution", v).apply(); }
    public void setIntervalSec(int v) { sp.edit().putInt("intervalSec", v).apply(); }
    public void setStrategyId(int v) { sp.edit().putInt("strategyId", v).apply(); }
    public void setTradeAmount(double v) { sp.edit().putFloat("tradeAmount", (float) v).apply(); }
    public void setSlPct(double v) { sp.edit().putFloat("slPct", (float) v).apply(); }
    public void setTpPct(double v) { sp.edit().putFloat("tpPct", (float) v).apply(); }
    public void setTrailingPct(double v) { sp.edit().putFloat("trailingPct", (float) v).apply(); }
    public void setLive(boolean v) { sp.edit().putBoolean("live", v).apply(); }
    public void setTg(String token, String chat) {
        sp.edit().putString("tgToken", token == null ? "" : token.trim())
                .putString("tgChat", chat == null ? "" : chat.trim()).apply();
    }

    // ---------------------------------------------------------------- state

    public boolean posActive() { return sp.getBoolean("posActive", false); }
    public void setPos(boolean active, double amount, double entry, long time, boolean live) {
        sp.edit()
                .putBoolean("posActive", active)
                .putString("posAmount", Double.toString(amount))
                .putString("posEntry", Double.toString(entry))
                .putLong("posTime", time)
                .putBoolean("posLive", live)
                .apply();
    }

    public double posAmount() { return parse(sp.getString("posAmount", "0")); }
    public double posEntry() { return parse(sp.getString("posEntry", "0")); }
    public long posTime() { return sp.getLong("posTime", 0); }
    public boolean posLive() { return sp.getBoolean("posLive", false); }

    /** highest price seen since entry (for the trailing stop) */
    public double posPeak() { return parse(sp.getString("posPeak", "0")); }
    public void setPosPeak(double v) { sp.edit().putString("posPeak", Double.toString(v)).apply(); }

    public double realizedPnl() { return parse(sp.getString("realizedPnl", "0")); }
    public void setRealizedPnl(double v) { sp.edit().putString("realizedPnl", Double.toString(v)).apply(); }

    public int tradeCount() { return sp.getInt("tradeCount", 0); }
    public int winCount() { return sp.getInt("winCount", 0); }
    public void setTradeStats(int trades, int wins) {
        sp.edit().putInt("tradeCount", trades).putInt("winCount", wins).apply();
    }

    public long lastTradeTime() { return sp.getLong("lastTradeTime", 0); }
    public void setLastTradeTime(long v) { sp.edit().putLong("lastTradeTime", v).apply(); }

    public void resetState() {
        setPos(false, 0, 0, 0, false);
        setPosPeak(0);
        setRealizedPnl(0);
        setTradeStats(0, 0);
        setLastTradeTime(0);
        sp.edit().remove("log").remove("trades").apply();
    }

    // ---------------------------------------------------------------- lists

    public JSONArray getArray(String key) {
        try {
            return new JSONArray(sp.getString(key, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public void putArray(String key, JSONArray a) {
        sp.edit().putString(key, a.toString()).apply();
    }

    public static double parse(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return 0;
        }
    }
}
