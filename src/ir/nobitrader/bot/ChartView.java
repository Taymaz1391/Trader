package ir.nobitrader.bot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * Interactive candlestick chart: green/red candles with wicks, volume band,
 * EMA21 overlay, dashed entry line, axis labels and a last-price chip.
 * Supports pinch-to-zoom and horizontal drag-to-pan.
 */
public class ChartView extends View {

    public interface HintHost {
        void onZoomHint();
    }

    private Candle[] data = new Candle[0];
    private double[] ema = new double[0];
    private double[] rsi = new double[0];
    private double[] macd = new double[0];
    private double[] macdSig = new double[0];
    private double[][] markers = new double[0][]; // {candleTimeSec, price, side(+1/-1)}
    private double entry;    // 0 = no entry line
    private double last;
    private String lastLabel = "";

    private int visible = 80;   // candles in view
    private int tail = 0;       // candles hidden at the right edge (pan)

    private static final int GREEN = 0xFF16C784;
    private static final int RED = 0xFFEA3943;
    private static final int GOLD = 0xFFF0B90B;

    private final Paint bodyUp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bodyDn = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wickUp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wickDn = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint volUp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint volDn = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emaP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emaGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint entryP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txtP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txtInvP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lblBgP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rsiP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rsiBandP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rsiZoneP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint macdP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint macdSigP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint histUpP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint histDnP = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final ScaleGestureDetector scale;
    private final GestureDetector gest;

    public ChartView(Context c) {
        super(c);
        float d = d();
        bodyUp.setStyle(Paint.Style.FILL);
        bodyUp.setColor(GREEN);
        bodyDn.setStyle(Paint.Style.FILL);
        bodyDn.setColor(RED);
        wickUp.setStyle(Paint.Style.STROKE);
        wickUp.setStrokeWidth(1f * d);
        wickUp.setColor(GREEN);
        wickDn.setStyle(Paint.Style.STROKE);
        wickDn.setStrokeWidth(1f * d);
        wickDn.setColor(RED);
        volUp.setColor(0x4616C784);
        volDn.setColor(0x46EA3943);
        emaP.setStyle(Paint.Style.STROKE);
        emaP.setStrokeWidth(1.6f * d);
        emaP.setColor(GOLD);
        emaGlow.setStyle(Paint.Style.STROKE);
        emaGlow.setStrokeWidth(4.5f * d);
        emaGlow.setColor(0x2EF0B90B);
        gridP.setStyle(Paint.Style.STROKE);
        gridP.setStrokeWidth(1f);
        gridP.setColor(0x16FFFFFF);
        entryP.setStyle(Paint.Style.STROKE);
        entryP.setStrokeWidth(1.6f * d);
        entryP.setColor(GREEN);
        entryP.setPathEffect(new DashPathEffect(new float[]{9f, 7f}, 0));
        txtP.setColor(0xFF93A0B8);
        txtP.setTextSize(9.5f * d);
        txtInvP.setColor(0xFFFFFFFF);
        txtInvP.setTextSize(9.5f * d);
        txtInvP.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
        chipP.setStyle(Paint.Style.FILL);
        lblBgP.setStyle(Paint.Style.FILL);
        lblBgP.setColor(0xE6101624);
        rsiP.setStyle(Paint.Style.STROKE);
        rsiP.setStrokeWidth(1.5f * d);
        rsiP.setColor(0xFF9B6BFF);
        rsiBandP.setStyle(Paint.Style.STROKE);
        rsiBandP.setStrokeWidth(1f);
        rsiBandP.setColor(0x44FFFFFF);
        rsiBandP.setPathEffect(new DashPathEffect(new float[]{5f, 5f}, 0));
        rsiZoneP.setStyle(Paint.Style.FILL);
        rsiZoneP.setColor(0x149B6BFF);
        markP.setStyle(Paint.Style.FILL);
        markP.setAntiAlias(true);
        macdP.setStyle(Paint.Style.STROKE);
        macdP.setStrokeWidth(1.5f * d);
        macdP.setColor(0xFFF5B84D);
        macdSigP.setStyle(Paint.Style.STROKE);
        macdSigP.setStrokeWidth(1.1f * d);
        macdSigP.setColor(0xFF9B6BFF);
        histUpP.setStyle(Paint.Style.FILL);
        histUpP.setColor(0x8816C784);
        histDnP.setStyle(Paint.Style.FILL);
        histDnP.setColor(0x88E5484D);
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        scale = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector det) {
                float f = det.getScaleFactor();
                if (f > 0.99f && f < 1.01f) return true;
                int v = Math.round(visible / f);
                visible = Math.max(25, Math.min(data.length > 0 ? data.length : 25, v));
                clampWindow();
                invalidate();
                return true;
            }
        });
        gest = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (data.length == 0 || getWidth() == 0) return true;
                if (scale.isInProgress()) return true;
                float stepW = (getWidth() - 66 * d()) / (float) visible;
                if (stepW <= 0) return true;
                int move = Math.round(dx / stepW);
                if (move != 0) {
                    tail = Math.max(0, Math.min(data.length - visible, tail + move));
                    invalidate();
                }
                return true;
            }
        });
    }

    private void clampWindow() {
        if (data.length == 0) {
            visible = Math.max(25, visible);
            tail = 0;
            return;
        }
        visible = Math.max(25, Math.min(data.length, visible));
        tail = Math.max(0, Math.min(data.length - visible, tail));
    }

    private float d() {
        return getResources().getDisplayMetrics().density;
    }

    /** full data refresh; ema/rsi/macd/sig must align with data; markers = {timeSec, price, side} */
    public void setData(Candle[] candles, double[] emaArr, double[] rsiArr,
                        double[] macdArr, double[] sigArr,
                        double entryPrice, String label, double[][] tradeMarkers) {
        data = candles == null ? new Candle[0] : candles;
        ema = emaArr == null ? new double[0] : emaArr;
        rsi = rsiArr == null ? new double[0] : rsiArr;
        macd = macdArr == null ? new double[0] : macdArr;
        macdSig = sigArr == null ? new double[0] : sigArr;
        markers = tradeMarkers == null ? new double[0][] : tradeMarkers;
        entry = entryPrice;
        last = data.length > 0 ? data[data.length - 1].c : 0;
        lastLabel = label == null ? "" : label;
        clampWindow();
        invalidate();
    }

    /** update just the entry line (called when position changes) */
    public void updateEntry(double entryPrice) {
        entry = entryPrice;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scale.onTouchEvent(e);
        gest.onTouchEvent(e);
        return true;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec),
                Math.round(360 * d()));
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float d = d();
        if (data.length < 2) {
            txtP.setTextAlign(Paint.Align.CENTER);
            c.drawText("در حال دریافت نمودار…", getWidth() / 2f, getHeight() / 2f, txtP);
            txtP.setTextAlign(Paint.Align.LEFT);
            return;
        }

        int end = data.length - tail;
        int start = Math.max(0, end - visible);
        int n = end - start;
        if (n < 2) return;

        float padL = 58 * d();
        float padR = 8 * d();
        float padT = 10 * d();
        float padB = 6 * d();

        float totalH = getHeight() - padT - padB;
        float volH = totalH * 0.13f;
        float rsiH = totalH * 0.17f;
        float macdH = totalH * 0.20f;
        float priceH = totalH - volH - rsiH - macdH - 18 * d();
        float w = getWidth() - padL - padR;

        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        double volMax = 0;
        for (int i = start; i < end; i++) {
            Candle k = data[i];
            min = Math.min(min, k.l);
            max = Math.max(max, k.h);
            volMax = Math.max(volMax, k.v);
        }
        for (int i = start; i < end && i < ema.length; i++) {
            if (!Double.isNaN(ema[i])) {
                min = Math.min(min, ema[i]);
                max = Math.max(max, ema[i]);
            }
        }
        if (entry > 0) {
            min = Math.min(min, entry);
            max = Math.max(max, entry);
        }
        if (max <= min) max = min + 1;
        double span0 = max - min;
        min -= span0 * 0.05;
        max += span0 * 0.05;
        double span = max - min;
        if (volMax <= 0) volMax = 1;

        float step = w / n;
        float bodyW = Math.max(2 * d(), step * 0.62f);
        float x0 = padL;

        for (int g = 0; g <= 4; g++) {
            double v = min + span * (1.0 - g / 4.0);
            float y = padT + priceH * g / 4f;
            c.drawLine(padL, y, padL + w, y, gridP);
            txtP.setTextAlign(android.graphics.Paint.Align.RIGHT);
            c.drawText(fmtAxis(v), padL - 5 * d(), y + 3.5f * d(), txtP);
            txtP.setTextAlign(Paint.Align.LEFT);
        }

        // ---- RSI panel ----
        float rsiT = padT + priceH + 8 * d();
        float rsiB = rsiT + rsiH;
        float y70 = rsiT + rsiH * 0.30f;
        float y50 = rsiT + rsiH * 0.50f;
        float y30 = rsiT + rsiH * 0.70f;
        c.drawLine(padL, y70, padL + w, y70, rsiBandP);
        c.drawLine(padL, y30, padL + w, y30, rsiBandP);
        c.drawRect(padL, rsiT, padL + w, y70, rsiZoneP);
        c.drawRect(padL, y30, padL + w, rsiB, rsiZoneP);
        txtP.setTextAlign(android.graphics.Paint.Align.RIGHT);
        c.drawText("70", padL - 5 * d(), y70 + 3 * d(), txtP);
        c.drawText("30", padL - 5 * d(), y30 + 3 * d(), txtP);
        String rsiLbl = "RSI";
        c.drawText(rsiLbl, padL - 5 * d(), y50 + 3 * d(), txtP);
        txtP.setTextAlign(Paint.Align.LEFT);
        if (rsi.length == data.length) {
            Path rp = new Path();
            boolean rs = false;
            for (int i = start; i < end; i++) {
                if (Double.isNaN(rsi[i])) continue;
                float x = padL + step * (i - start + 0.5f);
                float y = rsiT + (float) ((100.0 - rsi[i]) / 100.0) * rsiH;
                if (!rs) {
                    rp.moveTo(x, y);
                    rs = true;
                } else {
                    rp.lineTo(x, y);
                }
            }
            if (rs) c.drawPath(rp, rsiP);
        }

        // ---- MACD panel ----
        float mmT = rsiB + 6 * d();
        float mmB = mmT + macdH;
        float mAbs = 0.0000001f;
        if (macd.length == data.length) {
            for (int i = start; i < end; i++) {
                double a = macd[i], b2 = macdSig[i];
                if (!Double.isNaN(a)) mAbs = Math.max(mAbs, (float) Math.abs(a));
                if (!Double.isNaN(b2)) mAbs = Math.max(mAbs, (float) Math.abs(b2));
            }
        }
        float zero = (mmT + mmB) / 2f;
        c.drawLine(padL, zero, padL + w, zero, gridP);
        txtP.setTextAlign(android.graphics.Paint.Align.RIGHT);
        c.drawText("MACD", padL - 5 * d(), mmT + 8 * d(), txtP);
        txtP.setTextAlign(Paint.Align.LEFT);
        if (macd.length == data.length) {
            // histogram bars
            for (int i = start; i < end; i++) {
                double h = macd[i] - macdSig[i];
                if (Double.isNaN(h)) continue;
                float bh = (float) (h / mAbs) * (macdH / 2f);
                float lft = padL + step * (i - start) + step * 0.35f;
                float rgt = lft + step * 0.3f;
                c.drawRect(lft, Math.min(zero, zero - bh), rgt, Math.max(zero, zero - bh),
                        h >= 0 ? histUpP : histDnP);
            }
            // macd + signal lines
            Path mp = new Path();
            Path sp2 = new Path();
            boolean mOn = false, sOn = false;
            for (int i = start; i < end; i++) {
                float x = padL + step * (i - start + 0.5f);
                if (!Double.isNaN(macd[i])) {
                    float y = zero - (float) (macd[i] / mAbs) * (macdH / 2f);
                    if (!mOn) { mp.moveTo(x, y); mOn = true; } else mp.lineTo(x, y);
                }
                if (!Double.isNaN(macdSig[i])) {
                    float y = zero - (float) (macdSig[i] / mAbs) * (macdH / 2f);
                    if (!sOn) { sp2.moveTo(x, y); sOn = true; } else sp2.lineTo(x, y);
                }
            }
            if (mOn) c.drawPath(mp, macdP);
            if (sOn) c.drawPath(sp2, macdSigP);
        }

        // ---- volume baseline ----
        float volBase = mmB + 6 * d() + volH;
        c.drawLine(padL, volBase, padL + w, volBase, gridP);

        for (int i = start; i < end; i++) {
            Candle k = data[i];
            float cx = x0 + step * (i - start + 0.5f);
            boolean up = k.c >= k.o;
            float yH = padT + (float) ((max - k.h) / span) * priceH;
            float yL = padT + (float) ((max - k.l) / span) * priceH;
            float yO = padT + (float) ((max - k.o) / span) * priceH;
            float yC = padT + (float) ((max - k.c) / span) * priceH;

            c.drawLine(cx, yH, cx, yL, up ? wickUp : wickDn);
            float top = Math.min(yO, yC);
            float bot = Math.max(yO, yC);
            if (bot - top < 1.2f * d()) bot = top + 1.2f * d();
            c.drawRoundRect(cx - bodyW / 2f, top, cx + bodyW / 2f, bot, 1.5f * d(), 1.5f * d(), up ? bodyUp : bodyDn);

            float vh = (float) (k.v / volMax) * volH;
            c.drawRect(cx - bodyW / 2f, volBase - vh, cx + bodyW / 2f, volBase, up ? volUp : volDn);
        }

        Path ep = new Path();
        boolean started = false;
        for (int i = start; i < end && i < ema.length; i++) {
            if (Double.isNaN(ema[i])) continue;
            float x = x0 + step * (i - start + 0.5f);
            float y = padT + (float) ((max - ema[i]) / span) * priceH;
            if (!started) {
                ep.moveTo(x, y);
                started = true;
            } else {
                ep.lineTo(x, y);
            }
        }
        if (started) {
            c.drawPath(ep, emaGlow);
            c.drawPath(ep, emaP);
        }

        // trade markers: buy triangle under the candle, sell triangle above it
        for (double[] mk : markers) {
            if (mk.length < 3) continue;
            long mt = (long) mk[0];
            double mp = mk[1];
            boolean buy = mk[2] > 0;
            int idx = -1;
            for (int i = start; i < end; i++) {
                if (data[i].t == mt) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) continue;
            float cx = x0 + step * (idx - start + 0.5f);
            float ty = padT + (float) ((max - mp) / span) * priceH;
            markP.setColor(buy ? GREEN : RED);
            Path tp = new Path();
            if (buy) {
                float y = ty + 14 * d();
                tp.moveTo(cx, y - 6 * d());
                tp.lineTo(cx - 4.5f * d, y);
                tp.lineTo(cx + 4.5f * d, y);
            } else {
                float y = ty - 14 * d();
                tp.moveTo(cx, y + 6 * d());
                tp.lineTo(cx - 4.5f * d, y);
                tp.lineTo(cx + 4.5f * d, y);
            }
            tp.close();
            c.drawPath(tp, markP);
        }

        if (entry > 0) {
            float y = padT + (float) ((max - entry) / span) * priceH;
            entryP.setColor(last >= entry ? GREEN : RED);
            c.drawLine(padL, y, padL + w, y, entryP);
            String tag = "ورود";
            float tw = txtP.measureText(tag);
            c.drawRoundRect(padL + 3 * d(), y - 9 * d(), padL + 9 * d() + tw, y + 3 * d(), 4 * d(), 4 * d(), lblBgP);
            c.drawText(tag, padL + 6 * d(), y - 1.5f * d(), txtP);
        }

        if (lastLabel.length() > 0 && tail == 0) {
            boolean up = data[data.length - 1].c >= data[data.length - 1].o;
            chipP.setColor(up ? GREEN : RED);
            float ly = padT + (float) ((max - last) / span) * priceH;
            float tw = txtInvP.measureText(lastLabel);
            float chipL = 2 * d();
            float chipR = chipL + tw + 10 * d();
            c.drawRoundRect(chipL, ly - 8 * d(), chipR, ly + 8 * d(), 5 * d(), 5 * d(), chipP);
            c.drawText(lastLabel, chipL + 5 * d(), ly + 3.5f * d(), txtInvP);
        }
    }

    private String fmtAxis(double v) {
        double a = Math.abs(v);
        if (a >= 1e12) return String.format(java.util.Locale.US, "%.1fT", v / 1e12);
        if (a >= 1e9) return String.format(java.util.Locale.US, "%.1fB", v / 1e9);
        if (a >= 1e6) return String.format(java.util.Locale.US, "%.1fM", v / 1e6);
        if (a >= 1e3) return String.format(java.util.Locale.US, "%.1fK", v / 1e3);
        return String.format(java.util.Locale.US, "%.1f", v);
    }
}
