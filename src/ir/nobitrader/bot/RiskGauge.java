package ir.nobitrader.bot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * Horizontal risk gauge for the open position: a bar from stop-loss to
 * take-profit with a red zone (below entry) and green zone (above entry),
 * an entry tick and a live marker showing where the current price sits.
 */
public class RiskGauge extends View {

    private double entry, price, slPct = 4, tpPct = 8;

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint redZone = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint greenZone = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint entryTick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txtP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tri = new Path();

    public RiskGauge(Context c) {
        super(c);
        float d = getResources().getDisplayMetrics().density;
        bg.setStyle(Paint.Style.FILL);
        bg.setColor(0xFF1B2334);
        redZone.setStyle(Paint.Style.FILL);
        redZone.setColor(0x66EA3943);
        greenZone.setStyle(Paint.Style.FILL);
        greenZone.setColor(0x6616C784);
        entryTick.setStyle(Paint.Style.STROKE);
        entryTick.setStrokeWidth(2f * d);
        entryTick.setColor(0xFFE8EDF6);
        marker.setStyle(Paint.Style.FILL);
        txtP.setColor(0xFF93A0B8);
        txtP.setTextSize(10f * d);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    public void setData(double entryPrice, double currentPrice, double slPercent, double tpPercent) {
        entry = entryPrice;
        price = currentPrice;
        slPct = slPercent;
        tpPct = tpPercent;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec),
                Math.round(58 * getResources().getDisplayMetrics().density));
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (entry <= 0 || price <= 0 || slPct <= 0 || tpPct <= 0) return;

        float d = getResources().getDisplayMetrics().density;
        float padL = 4 * d, padR = 4 * d;
        float w = getWidth() - padL - padR;
        float barT = 26 * d, barB = 36 * d;
        float radius = 5 * d;

        double sl = entry * (1.0 - slPct / 100.0);
        double tp = entry * (1.0 + tpPct / 100.0);
        double range = tp - sl;
        if (range <= 0) return;

        float entryFrac = (float) ((entry - sl) / range);
        float frac = (float) ((price - sl) / range);
        if (frac < 0) frac = 0;
        if (frac > 1) frac = 1;

        // base bar
        c.drawRoundRect(padL, barT, padL + w, barB, radius, radius, bg);
        // zones
        float ex = padL + w * entryFrac;
        c.save();
        c.clipRect(padL, barT, padL + w, barB);
        if (ex > padL) c.drawRect(padL, barT, ex, barB, redZone);
        if (ex < padL + w) c.drawRect(ex, barT, padL + w, barB, greenZone);
        c.restore();

        // entry tick
        c.drawLine(ex, barT - 3 * d, ex, barB + 3 * d, entryTick);

        // current price marker (triangle + line)
        float mx = padL + w * frac;
        boolean inProfit = price >= entry;
        marker.setColor(inProfit ? 0xFF16C784 : 0xFFEA3943);
        c.drawLine(mx, barT - 6 * d, mx, barB + 2 * d, marker);
        tri.reset();
        tri.moveTo(mx, barT - 6 * d);
        tri.lineTo(mx - 4 * d, barT - 12 * d);
        tri.lineTo(mx + 4 * d, barT - 12 * d);
        tri.close();
        c.drawPath(tri, marker);

        // labels
        String l = "حد ضرر " + String.format(java.util.Locale.US, "-%.1f%%", slPct);
        String r = "حد سود +" + String.format(java.util.Locale.US, "%.1f%%", tpPct);
        c.drawText(l, padL, 16 * d, txtP);
        float rw = txtP.measureText(r);
        c.drawText(r, padL + w - rw, 16 * d, txtP);

        String mid = String.format(java.util.Locale.US, "%+.1f%%", (price / entry - 1.0) * 100.0);
        float mw = txtP.measureText(mid);
        c.drawText(mid, padL + (w - mw) / 2f, 52 * d, txtP);
    }
}
