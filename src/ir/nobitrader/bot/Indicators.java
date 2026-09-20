package ir.nobitrader.bot;

/**
 * Classic technical indicators computed over arrays of closing prices.
 * All arrays have the same length as the input; entries before the warm-up
 * period are Double.NaN.
 */
public final class Indicators {

    private Indicators() {
    }

    public static double[] sma(double[] p, int n) {
        double[] out = new double[p.length];
        double sum = 0;
        for (int i = 0; i < p.length; i++) {
            sum += p[i];
            if (i >= n) sum -= p[i - n];
            if (i >= n - 1) out[i] = sum / n;
            else out[i] = Double.NaN;
        }
        return out;
    }

    public static double[] ema(double[] p, int n) {
        double[] out = new double[p.length];
        double k = 2.0 / (n + 1.0);
        double prev = Double.NaN;
        for (int i = 0; i < p.length; i++) {
            if (i < n - 1) {
                out[i] = Double.NaN;
                continue;
            }
            if (Double.isNaN(prev)) {
                double s = 0;
                for (int j = i - n + 1; j <= i; j++) s += p[j];
                prev = s / n;
            } else {
                prev = p[i] * k + prev * (1.0 - k);
            }
            out[i] = prev;
        }
        return out;
    }

    /** Wilder RSI */
    public static double[] rsi(double[] p, int n) {
        double[] out = new double[p.length];
        if (p.length == 0) return out;
        out[0] = Double.NaN;
        double avgG = 0, avgL = 0;
        for (int i = 1; i < p.length; i++) {
            double ch = p[i] - p[i - 1];
            double g = ch > 0 ? ch : 0;
            double l = ch < 0 ? -ch : 0;
            if (i <= n) {
                avgG += g / n;
                avgL += l / n;
                if (i == n) out[i] = rsiVal(avgG, avgL);
                else out[i] = Double.NaN;
            } else {
                avgG = (avgG * (n - 1) + g) / n;
                avgL = (avgL * (n - 1) + l) / n;
                out[i] = rsiVal(avgG, avgL);
            }
        }
        return out;
    }

    private static double rsiVal(double avgG, double avgL) {
        if (avgL == 0) return 100.0;
        double rs = avgG / avgL;
        return 100.0 - 100.0 / (1.0 + rs);
    }

    /** returns [macdLine, signalLine] */
    public static double[][] macd(double[] p, int fast, int slow, int sig) {
        double[] ef = ema(p, fast);
        double[] es = ema(p, slow);
        double[] line = new double[p.length];
        for (int i = 0; i < p.length; i++) {
            if (Double.isNaN(ef[i]) || Double.isNaN(es[i])) line[i] = Double.NaN;
            else line[i] = ef[i] - es[i];
        }
        double[] sigl = new double[p.length];
        double k = 2.0 / (sig + 1.0);
        boolean seeded = false;
        double prev = 0;
        for (int i = 0; i < p.length; i++) {
            sigl[i] = Double.NaN;
            if (Double.isNaN(line[i])) continue;
            if (!seeded) {
                prev = line[i];
                seeded = true;
            } else {
                prev = line[i] * k + prev * (1.0 - k);
            }
            sigl[i] = prev;
        }
        return new double[][]{line, sigl};
    }

    /** returns [mid, upper, lower] */
    public static double[][] bollinger(double[] p, int n, double mult) {
        double[] mid = sma(p, n);
        double[] up = new double[p.length];
        double[] lo = new double[p.length];
        for (int i = 0; i < p.length; i++) {
            if (i < n - 1) {
                up[i] = Double.NaN;
                lo[i] = Double.NaN;
                continue;
            }
            double s = 0;
            for (int j = i - n + 1; j <= i; j++) {
                double d = p[j] - mid[i];
                s += d * d;
            }
            double sd = Math.sqrt(s / n);
            up[i] = mid[i] + mult * sd;
            lo[i] = mid[i] - mult * sd;
        }
        return new double[][]{mid, up, lo};
    }
}
