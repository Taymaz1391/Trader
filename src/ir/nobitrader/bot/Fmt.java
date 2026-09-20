package ir.nobitrader.bot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Number and date formatting helpers (Latin digits, US grouping). */
public final class Fmt {

    private Fmt() {
    }

    /** rials -> formatted toman integer string */
    public static String toman(double rls) {
        long t = Math.round(rls / 10.0);
        return String.format(Locale.US, "%,d", t);
    }

    /** format a quote value: rls->toman integer, otherwise decimal with up to 2 fraction digits */
    public static String quote(double v, boolean isRls) {
        if (isRls) return toman(v);
        if (Math.abs(v) >= 1000) return String.format(Locale.US, "%,.0f", v);
        return String.format(Locale.US, "%,.2f", v);
    }

    /** crypto amount: up to 8 decimals, trailing zeros stripped */
    public static String amount(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "0";
        BigDecimal b = BigDecimal.valueOf(Math.abs(v)).setScale(8, RoundingMode.DOWN).stripTrailingZeros();
        if (b.scale() < 0) b = b.setScale(0);
        return b.toPlainString();
    }

    /** floor to 8 decimals (for order amounts) */
    public static double amountFloor(double v) {
        return BigDecimal.valueOf(v).setScale(8, RoundingMode.DOWN).doubleValue();
    }

    /** signed percent like +4.2% */
    public static String pct(double p) {
        return String.format(Locale.US, "%+.1f%%", p);
    }

    public static String time(long epochSec) {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(epochSec * 1000L));
    }

    public static String timeFull(long epochSec) {
        return new SimpleDateFormat("MM/dd HH:mm", Locale.US).format(new Date(epochSec * 1000L));
    }
}
