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
 * Cumulative realized-PnL line chart (bot performance curve) with a dashed
 * zero baseline, gradient fill and a colored last value.
 */
public class EquityView extends View {

    private double[] s = new double[]{0};

    private final Paint lineP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zeroP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txtP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lblBgP = new Paint(Paint.ANTI_ALIAS_FLAG);

    public EquityView(Context c) {
        super(c);
        float d = getResources().getDisplayMetrics().density;
        lineP.setStyle(Paint.Style.STROKE);
        lineP.setStrokeWidth(2f * d);
        lineP.setColor(0xFF16C784);
        fillP.setStyle(Paint.Style.FILL);
        zeroP.setStyle(Paint.Style.STROKE);
        zeroP.setStrokeWidth(1f);
        zeroP.setColor(0x55FFFFFF);
        zeroP.setPathEffect(new DashPathEffect(new float[]{6f, 6f}, 0));
        txtP.setColor(0xFF93A0B8);
        txtP.setTextSize(10f * d);
        lblBgP.setStyle(Paint.Style.FILL);
        lblBgP.setColor(0xE6101624);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    /** series = cumulative pnl values, oldest first; length >= 1 */
    public void setData(double[] series) {
        if (series != null && series.length > 0) s = series;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec),
                Math.round(120 * getResources().getDisplayMetrics().density));
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float d = getResources().getDisplayMetrics().density;
        if (s.length < 2) {
            txtP.setTextAlign(Paint.Align.CENTER);
            c.drawText("پس از اولین معامله بسته‌شده، نمودار عملکرد اینجا نمایش داده می‌شود",
                    getWidth() / 2f, getHeight() / 2f, txtP);
            txtP.setTextAlign(Paint.Align.LEFT);
            return;
        }

        float padL = 6 * d, padR = 46 * d, padT = 8 * d, padB = 8 * d;
        float w = getWidth() - padL - padR;
        float h = getHeight() - padT - padB;

        double min = 0, max = 0;
        for (double v : s) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        if (max == min) max = min + 1;
        double span0 = max - min;
        min -= span0 * 0.08;
        max += span0 * 0.08;
        double span = max - min;

        boolean profit = s[s.length - 1] >= 0;
        int col = profit ? 0xFF16C784 : 0xFFEA3943;
        lineP.setColor(col);
        int fillCol = profit ? 0x2616C784 : 0x26EA3943;

        float zeroY = padT + (float) ((max - 0) / span) * h;
        c.drawLine(padL, zeroY, padL + w, zeroY, zeroP);

        float step = w / (s.length - 1);
        Path ln = new Path();
        Path area = new Path();
        for (int i = 0; i < s.length; i++) {
            float x = padL + i * step;
            float y = padT + (float) ((max - s[i]) / span) * h;
            if (i == 0) {
                ln.moveTo(x, y);
                area.moveTo(x, zeroY);
                area.lineTo(x, y);
            } else {
                ln.lineTo(x, y);
                area.lineTo(x, y);
            }
        }
        area.lineTo(padL + w, zeroY);
        area.close();
        fillP.setShader(new LinearGradient(0, padT, 0, padT + h,
                fillCol, 0x00000000, Shader.TileMode.CLAMP));
        c.drawPath(area, fillP);
        fillP.setShader(null);
        c.drawPath(ln, lineP);

        // last value chip
        double lastV = s[s.length - 1];
        float ly = padT + (float) ((max - lastV) / span) * h;
        float lx = padL + w;
        c.drawCircle(lx, ly, 2.6f * d, lineP);
        String label = String.format(java.util.Locale.US, "%+.0f", lastV);
        float tw = txtP.measureText(label);
        c.drawRoundRect(lx + 4 * d, ly - 8 * d, lx + 10 * d + tw, ly + 8 * d, 5 * d, 5 * d, lblBgP);
        txtP.setColor(col);
        c.drawText(label, lx + 7 * d, ly + 3.5f * d, txtP);
        txtP.setColor(0xFF93A0B8);
    }
}
