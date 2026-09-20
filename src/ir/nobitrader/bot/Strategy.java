package ir.nobitrader.bot;

/**
 * Trading strategies. signal() is evaluated on CLOSED candles at index i
 * (caller guarantees i >= WARMUP so all indicator arrays are defined).
 */
public abstract class Strategy {

    public static final int BUY = 1;
    public static final int SELL = -1;
    public static final int HOLD = 0;

    /** minimum closed-candle index required before signals are meaningful */
    public static final int WARMUP = 55;

    /** short human readable reason of the last evaluation (single-thread engine use) */
    public String reason = "";

    public abstract String name();

    public abstract String desc();

    public abstract int signal(Candle[] cs, int i, Ctx ctx);

    // ------------------------------------------------------------------

    /** Precomputed indicator context for one candle series. */
    public static class Ctx {
        public double[] closes;
        public double[] ema9, ema21, sma50, rsi14;
        public double[] macdLine, macdSig;
        public double[] bbMid, bbUp, bbLo;
        public double[] atr14, adx14, volSma20;

        public static Ctx compute(Candle[] cs) {
            Ctx c = new Ctx();
            int n = cs.length;
            double[] closes = new double[n];
            double[] vols = new double[n];
            for (int i = 0; i < n; i++) {
                closes[i] = cs[i].c;
                vols[i] = cs[i].v;
            }
            c.closes = closes;
            c.ema9 = Indicators.ema(closes, 9);
            c.ema21 = Indicators.ema(closes, 21);
            c.sma50 = Indicators.sma(closes, 50);
            c.rsi14 = Indicators.rsi(closes, 14);
            double[][] m = Indicators.macd(closes, 12, 26, 9);
            c.macdLine = m[0];
            c.macdSig = m[1];
            double[][] b = Indicators.bollinger(closes, 20, 2.0);
            c.bbMid = b[0];
            c.bbUp = b[1];
            c.bbLo = b[2];
            c.atr14 = Indicators.atr(cs, 14);
            c.adx14 = Indicators.adx(cs, 14);
            c.volSma20 = Indicators.sma(vols, 20);
            return c;
        }

        /** trend strong enough to trade (NaN-safe) */
        public static boolean trendOk(double[] adx, int i) {
            return Double.isNaN(adx[i]) || adx[i] >= 15;
        }

        /** volume not drying up (NaN-safe) */
        public static boolean volOk(Candle[] cs, double[] volSma, int i, double ratio) {
            double v = volSma[i];
            if (Double.isNaN(v) || v <= 0) return true;
            return cs[i].v >= ratio * v;
        }
    }

    // ------------------------------------------------------------------

    /** EMA 9/21 crossover with SMA50 trend filter and RSI guard. */
    public static class MaCross extends Strategy {
        public String name() { return "کراس میانگین EMA"; }

        public String desc() {
            return "خرید وقتی EMA9 از پایین بالای EMA21 کراس کند، قیمت بالای میانگین ۵۰ باشد و RSI فوق‌گرم نباشد؛ فروش با کراس نزولی.";
        }

        public int signal(Candle[] cs, int i, Ctx ctx) {
            boolean crossUp = ctx.ema9[i - 1] <= ctx.ema21[i - 1] && ctx.ema9[i] > ctx.ema21[i];
            boolean crossDn = ctx.ema9[i - 1] >= ctx.ema21[i - 1] && ctx.ema9[i] < ctx.ema21[i];
            boolean trendUp = ctx.closes[i] > ctx.sma50[i];
            boolean strong = Ctx.trendOk(ctx.adx14, i) && Ctx.volOk(cs, ctx.volSma20, i, 0.6);
            if (crossUp && trendUp && strong && ctx.rsi14[i] <= 68) {
                reason = "کراس صعودی EMA9/EMA21 با روند صعودی و قدرت کافی";
                return BUY;
            }
            if (crossDn) {
                reason = "کراس نزولی EMA9/EMA21";
                return SELL;
            }
            return HOLD;
        }
    }

    // ------------------------------------------------------------------

    /** Bollinger mean-reversion confirmed by RSI. */
    public static class RsiBollinger extends Strategy {
        public String name() { return "RSI + باند بولینگر"; }

        public String desc() {
            return "خرید وقتی قیمت از زیر باند پایانی بولینگر به داخل بازگردد و RSI اشباع فروش نزدیک باشد؛ فروش با خروج از باند بالایی.";
        }

        public int signal(Candle[] cs, int i, Ctx ctx) {
            double c0 = ctx.closes[i - 1], c1 = ctx.closes[i];
            boolean backIn = c0 < ctx.bbLo[i - 1] && c1 >= ctx.bbLo[i] && ctx.rsi14[i] < 45 && ctx.rsi14[i] > ctx.rsi14[i - 1];
            boolean outTop = c0 > ctx.bbUp[i - 1] && c1 <= ctx.bbUp[i] && ctx.rsi14[i] > 55;
            if (backIn) {
                reason = "بازگشت به داخل باند پایینی با RSI پایین";
                return BUY;
            }
            if (outTop) {
                reason = "خروج از باند بالایی با RSI بالا";
                return SELL;
            }
            return HOLD;
        }
    }

    // ------------------------------------------------------------------

    /** MACD signal-line crossover. */
    public static class MacdTrend extends Strategy {
        public String name() { return "MACD"; }

        public String desc() {
            return "خرید با کراس صعودی خط MACD از خط سیگنال (به شرط عدم اشباع خرید) و فروش با کراس نزولی.";
        }

        public int signal(Candle[] cs, int i, Ctx ctx) {
            boolean crossUp = ctx.macdLine[i - 1] <= ctx.macdSig[i - 1] && ctx.macdLine[i] > ctx.macdSig[i];
            boolean crossDn = ctx.macdLine[i - 1] >= ctx.macdSig[i - 1] && ctx.macdLine[i] < ctx.macdSig[i];
            if (crossUp && ctx.rsi14[i] <= 70) {
                reason = "کراس صعودی MACD/Signal";
                return BUY;
            }
            if (crossDn) {
                reason = "کراس نزولی MACD/Signal";
                return SELL;
            }
            return HOLD;
        }
    }

    // ------------------------------------------------------------------

    /** Weighted score of several indicators; buys when the composite flips strong. */
    public static class ComboScore extends Strategy {
        public String name() { return "ترکیبی هوشمند (امتیازدهی)"; }

        public String desc() {
            return "امتیازدهی هم‌زمان به روند (EMA و SMA50)، قدرت روند (ADX)، مومنتوم (MACD و RSI)، حجم معاملات و موقعیت قیمت نسبت به باند بولینگر؛ خرید در عبور امتیاز از ۶ و فروش زیر ۲.";
        }

        public int score(Candle[] cs, int i, Ctx ctx) {
            int s = 0;
            if (ctx.ema9[i] > ctx.ema21[i]) s += 2;
            if (ctx.closes[i] > ctx.sma50[i]) s += 1;
            if (ctx.macdLine[i] > ctx.macdSig[i]) s += 1;
            if (ctx.closes[i] > ctx.bbMid[i]
                    && Ctx.volOk(cs, ctx.volSma20, i, 0.8)) s += 1;
            if (i >= 2 && ctx.rsi14[i] > ctx.rsi14[i - 2]) s += 1;
            if (ctx.rsi14[i] < 68) s += 1;
            return s;
        }

        public int signal(Candle[] cs, int i, Ctx ctx) {
            int s = score(cs, i, ctx);
            int sp = score(cs, i - 1, ctx);
            if (s >= 6 && sp < 6) {
                reason = "امتیاز ترکیبی " + s + " از ۷ (عبور از آستانه خرید)";
                return BUY;
            }
            if (s <= 2 && sp > 2) {
                reason = "امتیاز ترکیبی " + s + " از ۷ (فروپاشی شرایط)";
                return SELL;
            }
            return HOLD;
        }
    }

    // ------------------------------------------------------------------

    public static final Strategy[] ALL = new Strategy[]{
            new MaCross(), new RsiBollinger(), new MacdTrend(), new ComboScore()
    };

    public static Strategy byId(int id) {
        if (id < 0 || id >= ALL.length) return ALL[0];
        return ALL[id];
    }
}
