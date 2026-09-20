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

    /**
     * Risk-based position sizing: the quote amount to spend so that hitting the
     * stop-loss costs exactly riskPct% of equity. Falls back to the configured
     * amount when the result is below the exchange minimum or inputs are invalid.
     */
    public static double sizeFor(double equity, double riskPct, double slPct,
                                 double configured, double minQuote) {
        if (equity <= 0 || riskPct <= 0 || slPct <= 0) return configured;
        double spend = equity * (riskPct / 100.0) / (slPct / 100.0);
        spend = Math.min(spend, equity * 0.95);
        if (spend < minQuote) return configured;
        return spend;
    }

    /** trailing-stop price for a given peak; NaN when trailing is disabled */
    public static double trailPriceFor(double peak, double trailPct) {
        return trailPct > 0 ? peak * (1.0 - trailPct / 100.0) : Double.NaN;
    }

    /**
     * ATR-based adaptive stop percentages: SL = clamp(2.5 x ATR/price, 1.5%, 12%),
     * TP keeps the user's reward/risk ratio (tpPct/slPct). Returns {slPct, tpPct}.
     */
    public static double[] atrStopsPct(double atr, double price, double slPct, double tpPct) {
        if (atr <= 0 || price <= 0 || Double.isNaN(atr) || slPct <= 0 || tpPct <= 0) {
            return new double[]{slPct, tpPct};
        }
        double sl = 2.5 * atr / price * 100.0;
        sl = Math.max(1.5, Math.min(12.0, sl));
        double ratio = tpPct / slPct;
        return new double[]{sl, sl * ratio};
    }

    public static Result run(Strategy st, Candle[] candles, double slPct, double tpPct) {
        return run(st, candles, slPct, tpPct, 0, 0, 0, false, DEFAULT_FEE);
    }

    public static Result run(Strategy st, Candle[] candles, double slPct, double tpPct,
                             double trailPct, double fee) {
        return run(st, candles, slPct, tpPct, trailPct, 0, 0, false, fee);
    }

    /**
     * @param trailPct    trailing stop percent from the peak (0 = off)
     * @param dcaInterval DCA ladder spacing in candles (0 = signal entries)
     * @param dcaMax      max DCA ladders per position (0 = unlimited)
     * @param atrStops    ATR-based adaptive SL/TP instead of fixed percents
     */
    public static Result run(Strategy st, Candle[] candles, double slPct, double tpPct,
                             double trailPct, double dcaInterval, double dcaMax,
                             boolean atrStops, double fee) {
        if (dcaInterval > 0) {
            return runDca(st, candles, slPct, tpPct, trailPct,
                    (int) Math.max(1, dcaInterval), (int) Math.max(0, dcaMax), atrStops, fee);
        }
        return runSignal(st, candles, slPct, tpPct, trailPct, atrStops, fee);
    }

    /** signal-entry backtest (original engine) */
    private static Result runSignal(Strategy st, Candle[] candles, double slPct, double tpPct,
                                    double trailPct, boolean atrStops, double fee) {
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
            double posSlPct = slPct, posTpPct = tpPct; // effective stops for the open position
            double peakPrice = 0;   // highest price while in position (for trailing)
            double equity = 1.0;
            double peak = 1.0;
            double maxDD = 0;
            int trades = 0, wins = 0;

            for (int i = start; i < candles.length; i++) {
                double close = candles[i].c;
                if (in) {
                    double slPrice = entry * (1.0 - posSlPct / 100.0);
                    double tpPrice = entry * (1.0 + posTpPct / 100.0);
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
                        if (atrStops && !Double.isNaN(ctx.atr14[i])) {
                            double[] eff = atrStopsPct(ctx.atr14[i], close, slPct, tpPct);
                            posSlPct = eff[0];
                            posTpPct = eff[1];
                        } else {
                            posSlPct = slPct;
                            posTpPct = tpPct;
                        }
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
    /**
     * DCA backtest: buys a ladder every `interval` closed candles (spending half
     * of the remaining cash each time, capped by maxLadders), tracks the
     * weighted-average entry, and exits the whole position on SL/TP/trailing
     * (vs the average entry) or a strategy SELL signal.
     */
    private static Result runDca(Strategy st, Candle[] candles, double slPct, double tpPct,
                                 double trailPct, int interval, int maxLadders, boolean atrStops, double fee) {
        Result r = new Result();
        r.name = st.name() + " (پله‌ای)";
        try {
            Strategy.Ctx ctx = Strategy.Ctx.compute(candles);
            int start = Math.max(Strategy.WARMUP, 1);
            if (candles.length <= start + 2) {
                r.ok = false;
                return r;
            }
            final double FRAC = 0.5; // each ladder spends half of the remaining cash
            double cash = 1.0;
            double coins = 0;
            double invested = 0;
            double avg = 0;
            double posSlPct = slPct, posTpPct = tpPct;
            double peakPrice = 0;
            int ladders = 0;
            int lastLadderIdx = -1000000;
            double peakEq = 1.0, maxDD = 0;
            int trades = 0, wins = 0;

            for (int i = start; i < candles.length; i++) {
                double close = candles[i].c;
                if (coins > 0) {
                    double slPrice = avg * (1.0 - posSlPct / 100.0);
                    double tpPrice = avg * (1.0 + posTpPct / 100.0);
                    double trPrice = trailPriceFor(peakPrice, trailPct);
                    boolean slHit = candles[i].l <= slPrice;
                    boolean tpHit = candles[i].h >= tpPrice;
                    boolean trHit = !Double.isNaN(trPrice) && candles[i].l <= trPrice;
                    boolean sellSig = st.signal(candles, i, ctx) == Strategy.SELL;
                    if (slHit || trHit || tpHit || sellSig) {
                        double exit = close;
                        if (slHit || trHit) {
                            exit = Double.MAX_VALUE;
                            if (slHit) exit = Math.min(exit, slPrice);
                            if (trHit) exit = Math.min(exit, trPrice);
                        } else if (tpHit) {
                            exit = tpPrice;
                        }
                        double proceeds = coins * exit * (1.0 - fee);
                        cash += proceeds;
                        trades++;
                        if (proceeds > invested) wins++;
                        coins = 0;
                        invested = 0;
                        avg = 0;
                        peakPrice = 0;
                        ladders = 0;
                        lastLadderIdx = i;
                    } else {
                        if (candles[i].h > peakPrice) peakPrice = candles[i].h;
                        boolean maxOk = maxLadders <= 0 || ladders < maxLadders;
                        if (maxOk && i - lastLadderIdx >= interval) {
                            double spend = cash * FRAC;
                            coins += spend * (1.0 - fee) / close;
                            invested += spend;
                            cash -= spend;
                            ladders++;
                            lastLadderIdx = i;
                            avg = coins > 0 ? invested / coins : 0;
                            if (close > peakPrice) peakPrice = close;
                            if (atrStops && !Double.isNaN(ctx.atr14[i])) {
                                double[] eff = atrStopsPct(ctx.atr14[i], avg, slPct, tpPct);
                                posSlPct = eff[0];
                                posTpPct = eff[1];
                            }
                        }
                    }
                } else if (i - lastLadderIdx >= interval) {
                    double spend = cash * FRAC;
                    coins = spend * (1.0 - fee) / close;
                    invested = spend;
                    cash -= spend;
                    ladders = 1;
                    lastLadderIdx = i;
                    avg = coins > 0 ? invested / coins : 0;
                    peakPrice = close;
                    if (atrStops && !Double.isNaN(ctx.atr14[i])) {
                        double[] eff = atrStopsPct(ctx.atr14[i], avg, slPct, tpPct);
                        posSlPct = eff[0];
                        posTpPct = eff[1];
                    } else {
                        posSlPct = slPct;
                        posTpPct = tpPct;
                    }
                }
                double equity = cash + coins * close;
                if (equity > peakEq) peakEq = equity;
                double dd = (peakEq - equity) / peakEq * 100.0;
                if (dd > maxDD) maxDD = dd;
            }

            double finalEq = cash + coins * candles[candles.length - 1].c;
            r.trades = trades;
            r.wins = wins;
            r.netPct = (finalEq - 1.0) * 100.0;
            r.bhPct = (candles[candles.length - 1].c / candles[start].c - 1.0) * 100.0;
            r.maxDDPct = maxDD;
            r.ok = true;
        } catch (Throwable t) {
            r.ok = false;
        }
        return r;
    }
}
