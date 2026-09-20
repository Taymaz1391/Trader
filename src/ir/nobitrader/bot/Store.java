package ir.nobitrader.bot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * In-memory log + trade journal, mirrored into SharedPreferences so the
 * service survives restarts. Single-writer (engine thread) / reader (UI).
 */
public class Store {

    private static final int MAX_LOG = 300;
    private static final int MAX_TRADES = 80;

    private static final List<String> logLines = new ArrayList<String>();
    private static final List<JSONObject> trades = new ArrayList<JSONObject>();
    private static Prefs prefs;
    private static int version = 0;

    public static synchronized void init(Prefs p) {
        if (prefs != null) return;
        prefs = p;
        JSONArray l = p.getArray("log");
        for (int i = 0; i < l.length(); i++) logLines.add(l.optString(i));
        JSONArray t = p.getArray("trades");
        for (int i = 0; i < t.length(); i++) {
            try {
                trades.add(t.getJSONObject(i));
            } catch (Exception ignored) {
            }
        }
    }

    public static synchronized int version() {
        return version;
    }

    /** wipe in-memory log and trades (used by the reset button) */
    public static synchronized void clear() {
        logLines.clear();
        trades.clear();
        version++;
    }

    public static synchronized void log(String msg) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        logLines.add(time + "  " + msg);
        while (logLines.size() > MAX_LOG) logLines.remove(0);
        if (prefs != null) {
            JSONArray a = new JSONArray();
            for (String s : logLines) a.put(s);
            prefs.putArray("log", a);
        }
        version++;
    }

    /** last n log lines joined by newline (for the UI) */
    public static synchronized String logText(int lastN) {
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, logLines.size() - lastN);
        for (int i = from; i < logLines.size(); i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(logLines.get(i));
        }
        return sb.toString();
    }

    public static synchronized void trade(JSONObject t) {
        trades.add(t);
        while (trades.size() > MAX_TRADES) trades.remove(0);
        if (prefs != null) {
            JSONArray a = new JSONArray();
            for (JSONObject o : trades) a.put(o);
            prefs.putArray("trades", a);
        }
        version++;
    }

    public static synchronized int tradeCount() {
        return trades.size();
    }

    /** last n trades as display lines (newest first) */
    public static synchronized String tradesText(Market m, int lastN) {
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, trades.size() - lastN);
        for (int i = trades.size() - 1; i >= from; i--) {
            JSONObject t = trades.get(i);
            boolean buy = "buy".equals(t.optString("side"));
            double price = t.optDouble("price", 0);
            double amount = t.optDouble("amount", 0);
            double pnl = t.optDouble("pnl", 0);
            boolean hasPnl = t.has("pnl") && !t.isNull("pnl");
            String line = (buy ? "🟢 خرید " : "🔴 فروش ")
                    + Fmt.amount(amount) + " × " + Fmt.quote(price, m.isRls)
                    + "  " + Fmt.timeFull(t.optLong("time", 0) / 1000L);
            if (hasPnl) {
                line += "  →  " + Fmt.quote(pnl, m.isRls) + " (" + Fmt.pct(t.optDouble("pnlPct", 0)) + ")";
            }
            if (t.optBoolean("live")) line += "  ⚡";
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.toString();
    }

    /** chart markers from the trade journal: {timeSec, price, side(+1 buy/-1 sell)} */
    public static synchronized java.util.ArrayList<double[]> markers() {
        java.util.ArrayList<double[]> out = new java.util.ArrayList<double[]>();
        for (JSONObject t : trades) {
            double side = "buy".equals(t.optString("side")) ? 1 : -1;
            out.add(new double[]{t.optLong("time", 0) / 1000.0, t.optDouble("price", 0), side});
        }
        return out;
    }

    /** cumulative realized pnl series: [0, pnl1, pnl1+pnl2, ...] (oldest first) */
    public static synchronized double[] pnlSeries() {
        double[] out = new double[trades.size() + 1];
        double cum = 0;
        int k = 1;
        for (JSONObject t : trades) {
            if (t.has("pnl") && !t.isNull("pnl")) cum += t.optDouble("pnl", 0);
            out[k++] = cum;
        }
        return out;
    }

    /** full trade journal as CSV (latin digits, newest last) */
    public static synchronized String csv() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        StringBuilder sb = new StringBuilder("time,side,price,amount,pnl,pnlPct,live\n");
        for (JSONObject t : trades) {
            sb.append(f.format(new Date(t.optLong("time", 0)))).append(',')
                    .append(t.optString("side", "")).append(',')
                    .append(t.optDouble("price", 0)).append(',')
                    .append(t.optDouble("amount", 0)).append(',');
            sb.append(t.has("pnl") && !t.isNull("pnl") ? String.valueOf(t.optDouble("pnl", 0)) : "").append(',');
            sb.append(t.has("pnlPct") && !t.isNull("pnlPct") ? String.valueOf(t.optDouble("pnlPct", 0)) : "").append(',');
            sb.append(t.optBoolean("live")).append('\n');
        }
        return sb.toString();
    }
}
