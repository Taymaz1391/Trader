package ir.nobitrader.bot;

import org.json.JSONObject;

import java.util.Random;

/**
 * Plain-JVM sanity checks for the pure trading logic (no Android classes).
 * Run: java -cp classes ir.nobitrader.bot.SelfTest
 */
public class SelfTest {

    static int failures = 0;

    static void check(boolean cond, String what) {
        if (cond) {
            System.out.println("  OK   " + what);
        } else {
            failures++;
            System.out.println("  FAIL " + what);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== indicators ==");
        double[] p = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        double[] sma = Indicators.sma(p, 3);
        check(Double.isNaN(sma[1]), "sma warmup NaN");
        check(Math.abs(sma[2] - 2.0) < 1e-9 && Math.abs(sma[9] - 9.0) < 1e-9, "sma values");

        double[] ema = Indicators.ema(p, 3);
        check(Double.isNaN(ema[1]), "ema warmup NaN");
        check(Math.abs(ema[2] - 2.0) < 1e-9, "ema seed");
        check(ema[3] > ema[2] && ema[9] > ema[8], "ema rising");

        double[] up = {10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25};
        double[] rsi = Indicators.rsi(up, 14);
        check(Double.isNaN(rsi[13]), "rsi warmup NaN");
        check(Math.abs(rsi[14] - 100.0) < 1e-9, "rsi all-gains = 100");

        double[] mix = new double[60];
        Random rnd = new Random(42);
        double v = 100;
        for (int i = 0; i < mix.length; i++) {
            v *= 1 + (rnd.nextDouble() - 0.5) * 0.05;
            mix[i] = v;
        }
        double[][] macd = Indicators.macd(mix, 12, 26, 9);
        boolean macdOk = true;
        for (int i = 30; i < mix.length; i++) {
            if (Double.isNaN(macd[0][i]) || Double.isNaN(macd[1][i])) macdOk = false;
        }
        check(macdOk, "macd defined after warmup");
        double[][] bb = Indicators.bollinger(mix, 20, 2);
        check(bb[1][59] > bb[0][59] && bb[0][59] > bb[2][59], "bollinger order upper>mid>lower");
        double[] rsi2 = Indicators.rsi(mix, 14);
        check(rsi2[59] >= 0 && rsi2[59] <= 100, "rsi in range");

        System.out.println("== strategy/backtest on synthetic series ==");
        Candle[] cs = new Candle[400];
        Random r2 = new Random(7);
        double price = 1_000_000_000.0; // rials-ish
        for (int i = 0; i < cs.length; i++) {
            // trending sine + noise so strategies occasionally trigger
            double drift = Math.sin(i / 18.0) * 0.02 + 0.0006;
            price *= 1 + drift + (r2.nextDouble() - 0.5) * 0.02;
            double o = price;
            double c = price * (1 + (r2.nextDouble() - 0.5) * 0.008);
            double h = Math.max(o, c) * (1 + r2.nextDouble() * 0.006);
            double l = Math.min(o, c) * (1 - r2.nextDouble() * 0.006);
            cs[i] = new Candle(1_700_000_000L + i * 3600L, o, h, l, c, 10);
        }
        Strategy.Ctx ctx = Strategy.Ctx.compute(cs);
        check(ctx.closes.length == cs.length, "ctx closes length");
        for (int s = 0; s < Strategy.ALL.length; s++) {
            Strategy st = Strategy.ALL[s];
            int signals = 0;
            boolean nanFree = true;
            for (int i = Strategy.WARMUP; i < cs.length; i++) {
                int sig = st.signal(cs, i, ctx);
                if (sig != 0) signals++;
                double[] arr = {ctx.ema9[i], ctx.ema21[i], ctx.sma50[i], ctx.rsi14[i],
                        ctx.macdLine[i], ctx.macdSig[i], ctx.bbUp[i], ctx.bbLo[i]};
                for (double x : arr) {
                    if (Double.isNaN(x) || Double.isInfinite(x)) nanFree = false;
                }
            }
            check(nanFree, st.name() + ": indicators finite at all evaluated bars");
            check(signals > 0, st.name() + ": produces at least one signal (" + signals + ")");
            Backtester.Result res = Backtester.run(st, cs, 4, 8);
            check(res.ok, st.name() + ": backtest completes");
            System.out.println("       -> net=" + String.format("%.2f", res.netPct)
                    + "% bh=" + String.format("%.2f", res.bhPct)
                    + "% trades=" + res.trades + " wins=" + res.wins
                    + " maxDD=" + String.format("%.1f", res.maxDDPct) + "%");
            check(res.trades == 0 || (res.wins >= 0 && res.wins <= res.trades), st.name() + ": wins<=trades");
            check(!Double.isNaN(res.netPct) && !Double.isNaN(res.maxDDPct), st.name() + ": finite results");
        }

        System.out.println("== trailing stop ==");
        check(Math.abs(Backtester.trailPriceFor(100, 3) - 97.0) < 1e-9, "trail price = peak*(1-3%)");
        check(Double.isNaN(Backtester.trailPriceFor(100, 0)), "trail disabled => NaN");

        // deterministic scenario: enter at a fixed bar, price rises then falls;
        // fixed SL/TP are set so they can never fire -> only the trailing stop can exit.
        Strategy fixedBuy = new Strategy() {
            public String name() { return "test-fixed-buy"; }
            public String desc() { return "test"; }
            public int signal(Candle[] cs, int i, Ctx ctx) { return i == 115 ? BUY : HOLD; }
        };
        Candle[] tr = new Candle[200];
        for (int i = 0; i < tr.length; i++) {
            double c;
            if (i < 100) c = 100;
            else if (i < 106) c = 100 - (i - 99) * 0.5;   // small dip -> EMA9 below EMA21
            else if (i < 156) c = 97 + (i - 105) * 1.5;    // steady rise
            else c = 172 - (i - 155) * 1.5;                // steady fall
            tr[i] = new Candle(1_700_000_000L + i * 3600L, c, c + 0.4, c - 0.4, c, 10);
        }
        Backtester.Result hold = Backtester.run(fixedBuy, tr, 50, 1000, 0, 0.0);
        Backtester.Result trail = Backtester.run(fixedBuy, tr, 50, 1000, 3.0, 0.0);
        check(hold.trades == 0, "without trailing the position is never closed (trades=" + hold.trades + ")");
        check(trail.trades == 1, "trailing stop closes the position exactly once (trades=" + trail.trades + ")");
        check(trail.netPct > 0 && trail.netPct < 60,
                "trailing exit locks in profit near the peak (net=" + String.format("%.2f", trail.netPct) + "%)");

        boolean trailOk = true;
        for (int s = 0; s < Strategy.ALL.length; s++) {
            Backtester.Result a = Backtester.run(Strategy.ALL[s], cs, 4, 8, 0, Backtester.DEFAULT_FEE);
            Backtester.Result b = Backtester.run(Strategy.ALL[s], cs, 4, 8, 2.0, Backtester.DEFAULT_FEE);
            if (!a.ok || !b.ok || b.trades < a.trades) trailOk = false;
        }
        check(trailOk, "trailing never reduces trade count across all strategies");

        System.out.println("== csv export ==");
        Store.clear();
        JSONObject tb = new JSONObject();
        tb.put("time", 1_700_000_000_000L);
        tb.put("side", "buy");
        tb.put("price", 100.0);
        tb.put("amount", 0.5);
        tb.put("live", false);
        Store.trade(tb);
        JSONObject ts = new JSONObject();
        ts.put("time", 1_700_003_600_000L);
        ts.put("side", "sell");
        ts.put("price", 104.0);
        ts.put("amount", 0.5);
        ts.put("pnl", 1.9);
        ts.put("pnlPct", 4.0);
        ts.put("live", true);
        Store.trade(ts);
        String csv = Store.csv();
        check(csv.startsWith("time,side,price,amount,pnl,pnlPct,live"), "csv header");
        check(csv.split("\n").length == 3, "csv has 1 header + 2 rows");
        check(csv.contains("buy") && csv.contains("sell"), "csv contains both sides");
        check(csv.contains("2023-11-14") && csv.contains("1.9"), "csv values formatted");

        System.out.println("== atr / adx ==");
        double[] atr = Indicators.atr(cs, 14);
        double[] adx = Indicators.adx(cs, 14);
        check(Double.isNaN(atr[10]), "atr warmup NaN");
        check(atr[cs.length - 1] > 0, "atr positive (" + String.format("%.2f", atr[cs.length - 1]) + ")");
        check(Double.isNaN(adx[20]), "adx warmup NaN");
        boolean adxOk = true;
        for (int i = 55; i < cs.length; i++) {
            if (Double.isNaN(adx[i]) || adx[i] < 0 || adx[i] > 100) adxOk = false;
        }
        check(adxOk, "adx defined and within 0..100 after warmup");
        check(adx[cs.length - 1] > 0, "adx positive on synthetic trends ("
                + String.format("%.1f", adx[cs.length - 1]) + ")");

        System.out.println("== atr adaptive stops ==");
        double[] huge = Backtester.atrStopsPct(100, 100, 4, 8);
        check(Math.abs(huge[0] - 12.0) < 1e-9, "atr stop clamped at 12% (" + huge[0] + ")");
        check(Math.abs(huge[1] - 24.0) < 1e-9, "atr tp keeps 2:1 ratio (" + huge[1] + ")");
        double[] tiny = Backtester.atrStopsPct(0.01, 100, 4, 8);
        check(Math.abs(tiny[0] - 1.5) < 1e-9, "atr stop floored at 1.5% (" + tiny[0] + ")");
        double[] mid = Backtester.atrStopsPct(1.6, 100, 4, 8);
        check(Math.abs(mid[0] - 4.0) < 1e-9, "atr 2.5x => 4% (" + mid[0] + ")");
        double[] bad = Backtester.atrStopsPct(Double.NaN, 100, 4, 8);
        check(bad[0] == 4 && bad[1] == 8, "atr NaN falls back to fixed stops");
        for (int s = 0; s < Strategy.ALL.length; s++) {
            Backtester.Result ra = Backtester.run(Strategy.ALL[s], cs, 4, 8, 0, 0, 0, true, false, Backtester.DEFAULT_FEE);
            check(ra.ok && !Double.isNaN(ra.netPct), Strategy.ALL[s].name() + ": atr-stops backtest completes");
        }

        System.out.println("== dca backtest ==");
        check(Market.tfSeconds("15") == 900 && Market.tfSeconds("60") == 3600
                && Market.tfSeconds("240") == 14400 && Market.tfSeconds("D") == 86400, "tfSeconds mapping");

        Candle[] rise = new Candle[300];
        for (int i = 0; i < rise.length; i++) {
            double c = 100 * Math.pow(1.004, i);
            rise[i] = new Candle(1_700_000_000L + i * 3600L, c, c * 1.002, c * 0.998, c, 10);
        }
        Backtester.Result rd = Backtester.run(Strategy.ALL[0], rise, 90, 25, 0, 12, 0, false, false, 0.0);
        check(rd.ok, "dca completes on rising market");
        check(rd.netPct > 0, "dca profits on steady rise (" + String.format("%.2f", rd.netPct) + "%)");
        check(rd.netPct < rd.bhPct, "dca below buy&hold on steady rise");
        check(rd.trades >= 1, "dca exits at least once on rise (" + rd.trades + ")");

        Candle[] fall = new Candle[300];
        for (int i = 0; i < fall.length; i++) {
            double c = 100 * Math.pow(0.996, i);
            fall[i] = new Candle(1_700_000_000L + i * 3600L, c, c * 1.002, c * 0.998, c, 10);
        }
        Backtester.Result fd = Backtester.run(Strategy.ALL[0], fall, 90, 1000, 0, 12, 0, false, false, 0.0);
        check(fd.ok, "dca completes on falling market");
        check(fd.netPct > fd.bhPct, "dca loses less than buy&hold on steady fall ("
                + String.format("%.2f", fd.netPct) + "% vs " + String.format("%.2f", fd.bhPct) + "%)");

        System.out.println("== donchian breakout ==");
        check(Strategy.ALL.length == 6, "six strategies registered (incl. ensemble)");
        Strategy dk = null;
        for (Strategy s0 : Strategy.ALL) {
            if (s0 instanceof Strategy.DonchianBreakout) dk = s0;
        }
        check(dk != null, "donchian present in ALL");
        Candle[] dkc = new Candle[200];
        for (int i = 0; i < 117; i++) {
            double c = 100 + Math.sin(i / 5.0) * 1.2;
            dkc[i] = new Candle(1_700_000_000L + i * 3600L, c, c + 0.6, c - 0.6, c, 20);
        }
        // three rising bars breaking above the 20-bar high, on strong volume
        dkc[117] = new Candle(1_700_000_117L * 3600L, 100.8, 102.2, 100.6, 101.9, 60);
        dkc[118] = new Candle(1_700_000_118L * 3600L, 101.9, 103.4, 101.7, 103.1, 80);
        dkc[119] = new Candle(1_700_000_119L * 3600L, 103.1, 104.8, 102.9, 104.5, 120);
        dkc[120] = new Candle(1_700_000_120L * 3600L, 104.5, 105.6, 104.0, 105.2, 150);
        double down = 105.2;
        for (int i = 121; i < 200; i++) {
            down -= 0.9;
            dkc[i] = new Candle(1_700_000_000L + i * 3600L, down + 0.9, down + 1.2, down - 0.5, down, 30);
        }
        Strategy.Ctx dctx = Strategy.Ctx.compute(dkc);
        check(dk.signal(dkc, 120, dctx) == Strategy.BUY, "donchian BUY on upside breakout");
        boolean sellFound = false;
        for (int i = 125; i < 200; i++) {
            if (dk.signal(dkc, i, dctx) == Strategy.SELL) {
                sellFound = true;
                break;
            }
        }
        check(sellFound, "donchian SELL on downside breakdown");
        Backtester.Result dkr = Backtester.run(dk, dkc, 5, 10, 0, 0, 0, false, false, Backtester.DEFAULT_FEE);
        check(dkr.ok, "donchian backtest completes");

        System.out.println("== ensemble vote ==");
        Strategy en = null;
        for (Strategy s0 : Strategy.ALL) {
            if (s0 instanceof Strategy.EnsembleVote) en = s0;
        }
        check(en != null, "ensemble present in ALL");
        // property check: ensemble must equal the majority-vote rule on EVERY bar
        boolean consistent = true;
        int anyBuy = 0;
        for (int i = Strategy.WARMUP; i < 200; i++) {
            int b = 0, sl = 0;
            for (Strategy s0 : Strategy.BASE) {
                int vv = s0.signal(dkc, i, dctx);
                if (vv == Strategy.BUY) b++;
                else if (vv == Strategy.SELL) sl++;
            }
            if (b >= 3) anyBuy++;
            int want = b >= 3 ? Strategy.BUY : (sl >= 3 ? Strategy.SELL : Strategy.HOLD);
            if (en.signal(dkc, i, dctx) != want) {
                consistent = false;
                break;
            }
        }
        System.out.println("   bars with a 3+ buy majority: " + anyBuy);
        check(consistent, "ensemble equals the majority-vote rule on every bar");
        Backtester.Result enr = Backtester.run(en, dkc, 5, 10, 0, 0, 0, false, false, Backtester.DEFAULT_FEE);
        check(enr.ok, "ensemble backtest completes");


        System.out.println("== risk sizing ==");
        double q = Backtester.sizeFor(1_000_000, 1, 4, 500_000, 30_000);
        check(Math.abs(q - 250_000) < 1e-6, "1% risk / 4% SL of 1M => 250K (" + q + ")");
        double capped = Backtester.sizeFor(1_000_000, 50, 4, 500_000, 30_000);
        check(Math.abs(capped - 950_000) < 1e-6, "spend capped at 95% of equity (" + capped + ")");
        double fb = Backtester.sizeFor(100_000, 1, 4, 500_000, 30_000 * 10);
        check(fb == 500_000, "below exchange minimum => fallback to configured");
        check(Backtester.sizeFor(-5, 1, 4, 500_000, 1) == 500_000, "invalid equity => fallback");
        check(Backtester.sizeFor(1e9, 0, 4, 500_000, 1) == 500_000, "zero risk => fallback");

        System.out.println("== pnl series ==");
        Store.clear();
        double[] s0 = Store.pnlSeries();
        check(s0.length == 1 && s0[0] == 0, "empty series = [0]");
        JSONObject tb2 = new JSONObject();
        tb2.put("time", 1L).put("side", "buy").put("price", 1.0).put("amount", 1.0).put("live", false);
        Store.trade(tb2);
        JSONObject ts2 = new JSONObject();
        ts2.put("time", 2L).put("side", "sell").put("price", 2.0).put("amount", 1.0)
                .put("pnl", 10.0).put("pnlPct", 100.0).put("live", false);
        Store.trade(ts2);
        JSONObject ts3 = new JSONObject();
        ts3.put("time", 3L).put("side", "sell").put("price", 3.0).put("amount", 1.0)
                .put("pnl", -4.0).put("pnlPct", -40.0).put("live", false);
        Store.trade(ts3);
        double[] s1 = Store.pnlSeries();
        check(s1.length == 4, "series length = trades+1");
        check(s1[0] == 0 && s1[1] == 0 && Math.abs(s1[2] - 10.0) < 1e-9 && Math.abs(s1[3] - 6.0) < 1e-9,
                "cumulative values [0,0,10,6]");

        System.out.println("== partial take-profit (TP1) ==");
        Strategy fixedBuy2 = new Strategy() {
            public String name() { return "test-fixed-buy2"; }
            public String desc() { return "test"; }
            public int signal(Candle[] cs, int i, Ctx ctx) { return i == 60 ? BUY : HOLD; }
        };
        // flat through the entry bar (close=100), then a steady rise crossing 104 and 108
        Candle[] upC = new Candle[120];
        for (int i = 0; i <= 60; i++) upC[i] = new Candle(1_700_000_000L + i * 3600L, 100, 100.2, 99.8, 100, 10);
        for (int i = 61; i < 120; i++) {
            double c = 100 * (1.0 + 0.005 * (i - 60));
            upC[i] = new Candle(1_700_000_000L + i * 3600L, c, c * 1.002, c * 0.998, c, 10);
        }
        Backtester.Result full8 = Backtester.run(fixedBuy2, upC, 50, 8, 0, 0, 0, false, false, 0.0);
        Backtester.Result withTp1 = Backtester.run(fixedBuy2, upC, 50, 8, 0, 0, 0, false, true, 0.0);
        check(full8.ok && Math.abs(full8.netPct - 8.0) < 1e-9,
                "no-TP1: full exit at TP=8% (net=" + full8.netPct + ")");
        check(withTp1.ok && Math.abs(withTp1.netPct - 6.0) < 0.05,
                "TP1: half at 4% + half at 8% => +6% (net=" + withTp1.netPct + ")");

        // rise to +4.5% (TP1 hit) then fall back below entry: breakeven exit for the rest
        // flat entry at 100, rise to +4.5% (TP1 only), then fall back below entry
        Candle[] mixC = new Candle[120];
        for (int i = 0; i <= 60; i++) mixC[i] = new Candle(1_700_000_000L + i * 3600L, 100, 100.2, 99.8, 100, 10);
        for (int i = 61; i <= 80; i++) {
            double c = 100 * (1.0 + 0.045 * (i - 60) / 20.0);
            mixC[i] = new Candle(1_700_000_000L + i * 3600L, c, c * 1.002, c * 0.998, c, 10);
        }
        for (int i = 81; i < 120; i++) {
            double c = 104.5 - (i - 80) * 0.7;
            mixC[i] = new Candle(1_700_000_000L + i * 3600L, c, c * 1.002, c * 0.998, c, 10);
        }
        Backtester.Result be = Backtester.run(fixedBuy2, mixC, 50, 8, 0, 0, 0, false, true, 0.0);
        check(be.ok && Math.abs(be.netPct - 2.0) < 0.1,
                "TP1 then fallback: half at ~4% + half at breakeven = +2% (net=" + be.netPct + ")");

        System.out.println("== replay events ==");
        check(full8.events.size() == 2, "no-TP1 run records entry+exit events");
        double[] firstEv = full8.events.get(0);
        check(firstEv[2] == 1, "first event is a buy");
        double[] lastEv = full8.events.get(full8.events.size() - 1);
        check(lastEv[2] == -1 && Math.abs(lastEv[3] - full8.netPct) < 1e-9,
                "last event: sell whose equity equals netPct");
        check(withTp1.events.size() == 3, "TP1 run records 3 events (buy, tp1, sell)");

        System.out.println("== markers ==");
        Store.clear();
        JSONObject mb = new JSONObject();
        mb.put("time", 1_700_000_123_000L).put("side", "buy").put("price", 5.0).put("amount", 1.0).put("live", false);
        Store.trade(mb);
        JSONObject ms = new JSONObject();
        ms.put("time", 1_700_000_456_000L).put("side", "sell").put("price", 6.0).put("amount", 1.0)
                .put("pnl", 1.0).put("pnlPct", 20.0).put("live", false);
        Store.trade(ms);
        java.util.ArrayList<double[]> mk = Store.markers();
        check(mk.size() == 2, "markers size = trades");
        check(Math.abs(mk.get(0)[0] - 1_700_000_123.0) < 1e-6 && mk.get(0)[2] == 1, "buy marker time+side");
        check(Math.abs(mk.get(1)[1] - 6.0) < 1e-9 && mk.get(1)[2] == -1, "sell marker price+side");

        System.out.println("== macd indicator ==");
        double[] macdUp = new double[80];
        for (int i = 0; i < 80; i++) macdUp[i] = 100 + i * i * 0.02;
        double[][] mu = Indicators.macd(macdUp, 12, 26, 9);
        check(mu[0][79] > 0 && mu[0][79] > mu[1][79],
                "accelerating uptrend: macd>0 and above signal (macd=" + mu[0][79] + ")");
        double[] macdDn = new double[80];
        for (int i = 0; i < 80; i++) macdDn[i] = 100 - i * i * 0.02;
        double[][] md = Indicators.macd(macdDn, 12, 26, 9);
        check(md[0][79] < 0 && md[0][79] < md[1][79],
                "accelerating downtrend: macd<0 and below signal (macd=" + md[0][79] + ")");

        System.out.println("== fmt ==");
        check("100,000".equals(Fmt.toman(1_000_001)), "toman rounding + grouping: " + Fmt.toman(1_000_001));
        check("0.5".equals(Fmt.amount(0.5)), "amount 0.5");
        check("1.23456789".equals(Fmt.amount(1.234567891)), "amount truncation");
        check("1000".equals(Fmt.amount(1000.0)), "amount integer");
        check("+4.2%".equals(Fmt.pct(4.23)), "pct format");

        System.out.println("== market ==");
        Market m1 = Market.of("BTCIRT");
        check(m1.src.equals("btc") && m1.dst.equals("rls") && m1.isRls, "BTCIRT parsed");
        Market m2 = Market.of("ETHUSDT");
        check(m2.src.equals("eth") && m2.dst.equals("usdt") && !m2.isRls, "ETHUSDT parsed");
        check(Market.of("BTCIRT").title.contains("بیت‌کوین"), "title contains coin name");

        if (failures == 0) {
            System.out.println("ALL TESTS PASSED");
        } else {
            System.out.println(failures + " TEST(S) FAILED");
            System.exit(1);
        }
    }
}
