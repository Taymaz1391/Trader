package ir.nobitrader.bot;

/**
 * Simple event-driven backtest over closed candles: entries/exits at candle
 * close, stop-loss / take-profit checked against candle low/high, fee applied
 * on both sides. Mirrors the rules the live engine follows.
 */
public class Backtester {

    public static final double DEFAULT_FEE = 0.0025; // 0.25% per side

    public static class Result {
        public String name;
        public double netPct;    // strategy return, percent
        public double bhPct;     // buy & hold return, percent
        public int trades;
        public int wins;
        public double maxDDPct;  // max drawdown, percent (positive number)
        public boolean ok;
    }

    /** trailing-stop price for a given peak; NaN when trailing is disabled */
    public static double trailPriceFor(double peak, double trailPct) {
        return trailPct > 0 ? peak * (1.0 - trailPct / 100.0) : Double.NaN;
    }

    public static Result run(Strategy st, Candle[] candles, double slPct, double tpPct) {
        return run(st, candles, slPct, tpPct, 0, DEFAULT_FEE);
    }

    public static Result run(Strategy st, Candle[] candles, double slPct, double tpPct, double fee) {
        return run(st, candles, slPct, tpPct, 0, fee);
    }

    /**
     * @param candles   closed candles, oldest first
     * @param slPct     stop loss percent (e.g. 4 => -4%)
     * @param tpPct     take profit percent (e.g. 8 => +8%)
     * @param trailPct  trailing stop percent from the peak (0 = off)
     * @param fee       one-sided fee fraction
     */
    public static Result run(Strategy st, Candle[] candles, double slPct, double tpPct,
                             double trailPct, double fee) {
        Result r = new Result();
        r.name = st.name();
        try {
            Strategy.Ctx ctx = Strategy.Ctx.compute(candles);
            int start = Math.max(Strategy.WARMUP, 1);
            if (candles.length <= start + 2) {
                r.ok = false;
                return r;
            }
            boolean in = false;
            double entry = 0;
            double peakPrice = 0;   // highest price while in position (for trailing)
            double equity = 1.0;
            double peak = 1.0;
            double maxDD = 0;
            int trades = 0, wins = 0;

            for (int i = start; i < candles.length; i++) {
                double close = candles[i].c;
                if (in) {
                    double slPrice = entry * (1.0 - slPct / 100.0);
                    double tpPrice = entry * (1.0 + tpPct / 100.0);
                    double trPrice = trailPriceFor(peakPrice, trailPct);
                    boolean slHit = candles[i].l <= slPrice;
                    boolean tpHit = candles[i].h >= tpPrice;
                    boolean trHit = !Double.isNaN(trPrice) && candles[i].l <= trPrice;
                    double exit = close;
                    if (slHit || trHit) {
                        // pessimistic: if several stops could have fired, take the worst
                        exit = Double.MAX_VALUE;
                        if (slHit) exit = Math.min(exit, slPrice);
                        if (trHit) exit = Math.min(exit, trPrice);
                    } else if (tpHit) {
                        exit = tpPrice;
                    }
                    int sig = st.signal(candles, i, ctx);
                    if (slHit || trHit || tpHit || sig == Strategy.SELL) {
                        equity *= (exit / entry) * (1.0 - fee);
                        if (exit > entry) wins++;
                        trades++;
                        in = false;
                    } else {
                        // still holding: raise the trailing peak with this bar's high
                        if (candles[i].h > peakPrice) peakPrice = candles[i].h;
                    }
                } else {
                    if (st.signal(candles, i, ctx) == Strategy.BUY) {
                        in = true;
                        entry = close;
                        peakPrice = close;
                        equity *= (1.0 - fee);
                    }
                }
                double mark = in ? equity * (close / entry) : equity;
                if (mark > peak) peak = mark;
                double dd = (peak - mark) / peak * 100.0;
                if (dd > maxDD) maxDD = dd;
            }

            r.trades = trades;
            r.wins = trades == 0 ? 0 : wins;
            r.netPct = (equity - 1.0) * 100.0;
            r.bhPct = (candles[candles.length - 1].c / candles[start].c - 1.0) * 100.0;
            r.maxDDPct = maxDD;
            r.ok = true;
        } catch (Throwable t) {
            r.ok = false;
        }
        return r;
    }
}
