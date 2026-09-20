package ir.nobitrader.bot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * Lightweight price chart drawn on a Canvas: close-price line with gradient
 * fill, EMA21 overlay, dashed entry-price line and a last-price marker.
 */
public class ChartView extends View {

    private double[] closes = new double[0];
    private double[] ema = new double[0];
    private double entry;   // 0 = no entry line
    private double last;
    private String lastLabel = "";

    private final Paint lineP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emaP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint entryP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txtP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lblBgP = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ChartView(Context c) {
        super(c);
        float d = d();
        lineP.setStyle(Paint.Style.STROKE);
        lineP.setStrokeWidth(2.2f * d);
        lineP.setColor(0xFF16C784);
        emaP.setStyle(Paint.Style.STROKE);
        emaP.setStrokeWidth(1.3f * d);
        emaP.setColor(0xFFF0B90B);
        emaP.setAlpha(200);
        fillP.setStyle(Paint.Style.FILL);
        fillP.setColor(0x2616C784);
        gridP.setStyle(Paint.Style.STROKE);
        gridP.setStrokeWidth(1f);
        gridP.setColor(0x14FFFFFF);
        entryP.setStyle(Paint.Style.STROKE);
        entryP.setStrokeWidth(1.6f * d);
        entryP.setColor(0xFF16C784);
        entryP.setPathEffect(new DashPathEffect(new float[]{9f, 7f}, 0));
        txtP.setColor(0xFF93A0B8);
        txtP.setTextSize(10f * d);
        dotP.setStyle(Paint.Style.FILL);
        dotP.setColor(0xFF16C784);
        lblBgP.setStyle(Paint.Style.FILL);
        lblBgP.setColor(0xE6101624);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    private float d() {
        return getResources().getDisplayMetrics().density;
    }

    /** closes/ema arrays must share length; lastLabel is a pre-formatted price string. */
    public void setData(double[] closesArr, double[] emaArr, double lastPrice,
                        double entryPrice, String label) {
        closes = closesArr;
        ema = emaArr;
        last = lastPrice;
        entry = entryPrice;
        lastLabel = label == null ? "" : label;
        if (closes.length > 1) {
            boolean up = closes[closes.length - 1] >= closes[0];
            int c = up ? 0xFF16C784 : 0xFFEA3943;
            lineP.setColor(c);
            dotP.setColor(c);
            fillP.setColor(up ? 0x2616C784 : 0x26EA3943);
        }
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
                Math.round(190 * d()));
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (closes.length < 2) {
            txtP.setTextAlign(Paint.Align.CENTER);
            c.drawText("در حال دریافت نمودار…", getWidth() / 2f, getHeight() / 2f, txtP);
            txtP.setTextAlign(Paint.Align.LEFT);
            return;
        }
        float padL = 6 * d(), padR = 62 * d(), padT = 10 * d(), padB = 8 * d();
        float w = getWidth() - padL - padR;
        float h = getHeight() - padT - padB;

        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double v : closes) {
            min = Math.min(min, v);
            max = Math.max(max, v);
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
        min -= span0 * 0.06;
        max += span0 * 0.06;
        double span = max - min;

        int n = closes.length;
        float dx = w / (n - 1);

        for (int i = 1; i <= 3; i++) {
            float y = padT + h * i / 4f;
            c.drawLine(padL, y, padL + w, y, gridP);
        }

        Path area = new Path();
        Path ln = new Path();
        for (int i = 0; i < n; i++) {
            float x = padL + i * dx;
            float y = padT + (float) ((max - closes[i]) / span) * h;
            if (i == 0) {
                ln.moveTo(x, y);
                area.moveTo(x, getHeight() - padB);
                area.lineTo(x, y);
            } else {
                ln.lineTo(x, y);
                area.lineTo(x, y);
            }
        }
        area.lineTo(padL + w, getHeight() - padB);
        area.lineTo(padL, getHeight() - padB);
        area.close();
        c.drawPath(area, fillP);
        c.drawPath(ln, lineP);

        Path ep = new Path();
        boolean started = false;
        for (int i = 0; i < ema.length; i++) {
            if (Double.isNaN(ema[i])) continue;
            float x = padL + i * dx;
            float y = padT + (float) ((max - ema[i]) / span) * h;
            if (!started) {
                ep.moveTo(x, y);
                started = true;
            } else {
                ep.lineTo(x, y);
            }
        }
        if (started) c.drawPath(ep, emaP);

        if (entry > 0) {
            float y = padT + (float) ((max - entry) / span) * h;
            entryP.setColor(last >= entry ? 0xFF16C784 : 0xFFEA3943);
            c.drawLine(padL, y, padL + w, y, entryP);
            txtP.setTextAlign(Paint.Align.LEFT);
            c.drawText("ورود", padL + 4 * d(), y - 5 * d(), txtP);
        }

        float ly = padT + (float) ((max - last) / span) * h;
        c.drawCircle(padL + w, ly, 3.2f * d(), dotP);
        if (lastLabel.length() > 0) {
            float tw = txtP.measureText(lastLabel);
            c.drawRoundRect(padL + w + 4 * d(), ly - 8 * d(),
                    padL + w + 10 * d() + tw, ly + 8 * d(), 5 * d(), 5 * d(), lblBgP);
            txtP.setTextAlign(Paint.Align.LEFT);
            c.drawText(lastLabel, padL + w + 7 * d(), ly + 3.5f * d(), txtP);
        }
    }
}
