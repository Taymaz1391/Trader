package ir.nobitrader.bot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

/**
 * Professional candlestick chart drawn on a Canvas: green/red candles with
 * wicks, volume bars in a lower band, EMA21 overlay, dashed entry line,
 * horizontal grid with price labels and a last-price marker chip.
 */
public class ChartView extends View {

    private Candle[] data = new Candle[0];
    private double[] ema = new double[0];
    private double entry;    // 0 = no entry line
    private double last;
    private String lastLabel = "";

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
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    private float d() {
        return getResources().getDisplayMetrics().density;
    }

    /** full data refresh; ema must align with data, label is a pre-formatted price string */
    public void setData(Candle[] candles, double[] emaArr, double entryPrice, String label) {
        data = candles == null ? new Candle[0] : candles;
        ema = emaArr == null ? new double[0] : emaArr;
        entry = entryPrice;
        last = data.length > 0 ? data[data.length - 1].c : 0;
        lastLabel = label == null ? "" : label;
        invalidate();
    }

    /** update just the entry line (called when position changes) */
    public void updateEntry(double entryPrice) {
        entry = entryPrice;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec),
                Math.round(240 * d()));
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (data.length < 2) {
            txtP.setTextAlign(Paint.Align.CENTER);
            c.drawText("در حال دریافت نمودار…", getWidth() / 2f, getHeight() / 2f, txtP);
            txtP.setTextAlign(Paint.Align.LEFT);
            return;
        }

        float padL = 58 * d();   // price axis (left)
        float padR = 8 * d();
        float padT = 10 * d();
        float padB = 6 * d();
        float axisW = padL - 6 * d();

        float totalH = getHeight() - padT - padB;
        float volH = totalH * 0.22f;      // volume band height
        float priceH = totalH - volH - 6 * d();
        float w = getWidth() - padL - padR;

        int n = data.length;
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        double volMax = 0;
        for (Candle k : data) {
            min = Math.min(min, k.l);
            max = Math.max(max, k.h);
            volMax = Math.max(volMax, k.v);
        }
        for (double v : ema) {
            if (!Double.isNaN(v)) {
                min = Math.min(min, v);
                max = Math.max(max, v);
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

        // grid + price labels (4 lines)
        for (int g = 0; g <= 4; g++) {
            double v = min + span * (1.0 - g / 4.0);
            float y = padT + priceH * g / 4f;
            c.drawLine(padL, y, padL + w, y, gridP);
            txtP.setTextAlign(Paint.Align.RIGHT);
            txtP.setTextAlign(android.graphics.Paint.Align.RIGHT);
            c.drawText(fmtAxis(v), padL - 5 * d(), y + 3.5f * d(), txtP);
            txtP.setTextAlign(Paint.Align.LEFT);
        }

        // volume band baseline
        float volBase = padT + priceH + 6 * d() + volH;
        c.drawLine(padL, volBase, padL + w, volBase, gridP);

        // candles + volume
        for (int i = 0; i < n; i++) {
            Candle k = data[i];
            float cx = x0 + step * (i + 0.5f);
            boolean up = k.c >= k.o;
            float yH = padT + (float) ((max - k.h) / span) * priceH;
            float yL = padT + (float) ((max - k.l) / span) * priceH;
            float yO = padT + (float) ((max - k.o) / span) * priceH;
            float yC = padT + (float) ((max - k.c) / span) * priceH;

            // wick
            c.drawLine(cx, yH, cx, yL, up ? wickUp : wickDn);
            // body
            float top = Math.min(yO, yC);
            float bot = Math.max(yO, yC);
            if (bot - top < 1.2f * d()) bot = top + 1.2f * d();
            c.drawRoundRect(cx - bodyW / 2f, top, cx + bodyW / 2f, bot, 1.5f * d(), 1.5f * d(), up ? bodyUp : bodyDn);

            // volume bar
            float vh = (float) (k.v / volMax) * volH;
            c.drawRect(cx - bodyW / 2f, volBase - vh, cx + bodyW / 2f, volBase, up ? volUp : volDn);
        }

        // EMA line (with soft glow)
        Path ep = new Path();
        boolean started = false;
        for (int i = 0; i < ema.length && i < n; i++) {
            if (Double.isNaN(ema[i])) continue;
            float x = x0 + step * (i + 0.5f);
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

        // entry line
        if (entry > 0) {
            float y = padT + (float) ((max - entry) / span) * priceH;
            entryP.setColor(last >= entry ? GREEN : RED);
            c.drawLine(padL, y, padL + w, y, entryP);
            String tag = "ورود";
            float tw = txtP.measureText(tag);
            c.drawRoundRect(padL + 3 * d(), y - 9 * d(), padL + 9 * d() + tw, y + 3 * d(), 4 * d(), 4 * d(), lblBgP);
            c.drawText(tag, padL + 6 * d(), y - 1.5f * d(), txtP);
        }

        // last price chip on the axis
        if (lastLabel.length() > 0) {
            boolean up = data[n - 1].c >= data[n - 1].o;
            chipP.setColor(up ? GREEN : RED);
            float ly = padT + (float) ((max - last) / span) * priceH;
            float tw = txtInvP.measureText(lastLabel);
            float chipL = 2 * d();
            float chipR = chipL + tw + 10 * d();
            c.drawRoundRect(chipL, ly - 8 * d(), chipR, ly + 8 * d(), 5 * d(), 5 * d(), chipP);
            c.drawText(lastLabel, chipL + 5 * d(), ly + 3.5f * d(), txtInvP);
        }
    }

    /** short axis label: e.g. 1.24B, 890M, 12.4 (quote units) */
    private String fmtAxis(double v) {
        double a = Math.abs(v);
        if (a >= 1e12) return String.format(java.util.Locale.US, "%.1fT", v / 1e12);
        if (a >= 1e9) return String.format(java.util.Locale.US, "%.1fB", v / 1e9);
        if (a >= 1e6) return String.format(java.util.Locale.US, "%.1fM", v / 1e6);
        if (a >= 1e3) return String.format(java.util.Locale.US, "%.1fK", v / 1e3);
        return String.format(java.util.Locale.US, "%.1f", v);
    }
}
