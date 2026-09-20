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
