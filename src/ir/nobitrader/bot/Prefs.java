package ir.nobitrader.bot;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

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
        public boolean atrStops = false;    // ATR-based adaptive SL/TP
        public boolean tp1Enabled = false;  // partial take-profit at half the TP
        public boolean riskSizing = false;  // dynamic position size by risk
        public double riskPct = 1.0;        // % of equity risked per trade
        public boolean dca = false;         // dollar-cost averaging mode
        public int dcaIntervalCandles = 24; // one ladder every N closed candles
        public int dcaMaxLadders = 5;       // max ladders per position, 0 = unlimited
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
        c.atrStops = sp.getBoolean("atrStops", false);
        c.tp1Enabled = sp.getBoolean("tp1Enabled", false);
        c.riskSizing = sp.getBoolean("riskSizing", false);
        c.riskPct = (double) sp.getFloat("riskPct", 1f);
        c.dca = sp.getBoolean("dca", false);
        c.dcaIntervalCandles = sp.getInt("dcaIntervalCandles", 24);
        c.dcaMaxLadders = sp.getInt("dcaMaxLadders", 5);
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
                .putBoolean("atrStops", c.atrStops)
                .putBoolean("tp1Enabled", c.tp1Enabled)
                .putBoolean("riskSizing", c.riskSizing)
                .putFloat("riskPct", (float) c.riskPct)
                .putBoolean("dca", c.dca)
                .putInt("dcaIntervalCandles", c.dcaIntervalCandles)
                .putInt("dcaMaxLadders", c.dcaMaxLadders)
                .putBoolean("live", c.live)
                .putString("tgToken", c.tgToken)
                .putString("tgChat", c.tgChat)
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
    public void setAtrStops(boolean v) { sp.edit().putBoolean("atrStops", v).apply(); }
    public void setTp1Enabled(boolean v) { sp.edit().putBoolean("tp1Enabled", v).apply(); }
    public void setRiskSizing(boolean v) { sp.edit().putBoolean("riskSizing", v).apply(); }
    public void setRiskPct(double v) { sp.edit().putFloat("riskPct", (float) v).apply(); }
    public void setDca(boolean v) { sp.edit().putBoolean("dca", v).apply(); }
    public void setDcaInterval(int v) { sp.edit().putInt("dcaIntervalCandles", v).apply(); }
    public void setDcaMax(int v) { sp.edit().putInt("dcaMaxLadders", v).apply(); }
    public void setLive(boolean v) { sp.edit().putBoolean("live", v).apply(); }
    public void setTg(String token, String chat) {
        sp.edit()
                .putString("tgToken", token == null ? "" : token.trim())
                .putString("tgChat", chat == null ? "" : chat.trim())
                .apply();
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

    /** partial take-profit bookkeeping */
    public boolean posTp1Taken() { return sp.getBoolean("posTp1Taken", false); }
    public void setPosTp1Taken(boolean v) { sp.edit().putBoolean("posTp1Taken", v).apply(); }

    /** consecutive losing trades (entry cooldown grows after a losing streak) */
    public int consecLosses() { return sp.getInt("consecLosses", 0); }
    public void setConsecLosses(int v) { sp.edit().putInt("consecLosses", v).apply(); }

    /** DCA ladder bookkeeping for the open position */
    public int posLadders() { return sp.getInt("posLadders", 0); }
    public void setPosLadders(int v) { sp.edit().putInt("posLadders", v).apply(); }
    public long posLastLadder() { return sp.getLong("posLastLadder", 0); }
    public void setPosLastLadder(long v) { sp.edit().putLong("posLastLadder", v).apply(); }

    /** whether the bot was running before the device restarted (BootReceiver) */
    public boolean botWasRunning() { return sp.getBoolean("botWasRunning", false); }
    public void setBotWasRunning(boolean v) { sp.edit().putBoolean("botWasRunning", v).apply(); }

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
        setPosLadders(0);
        setPosLastLadder(0);
        setPosTp1Taken(false);
        setConsecLosses(0);
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
