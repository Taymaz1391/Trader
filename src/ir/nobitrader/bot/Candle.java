package ir.nobitrader.bot;

/** One OHLCV candle. Prices are in the market quote unit (rials for IRT markets, USDT for USDT markets). */
public class Candle {
    public final long t;   // unix seconds, candle open time
    public final double o, h, l, c, v;

    public Candle(long t, double o, double h, double l, double c, double v) {
        this.t = t;
        this.o = o;
        this.h = h;
        this.l = l;
        this.c = c;
        this.v = v;
    }
}
