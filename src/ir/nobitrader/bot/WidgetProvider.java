package ir.nobitrader.bot;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

/**
 * Home-screen widget: market, live price, position P/L or daily change,
 * and the bot running state. Tapping opens the app.
 */
public class WidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] appWidgetIds) {
        push(ctx);
    }

    /** refresh every instance from the shared engine/prefs state (safe from any thread) */
    public static void push(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            if (mgr == null) return;
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, WidgetProvider.class));
            if (ids == null || ids.length == 0) return;

            Prefs prefs = new Prefs(ctx);
            BotEngine e = BotEngine.get(ctx);
            Prefs.Cfg cfg = prefs.cfg();
            Market m = Market.of(cfg.symbol);

            RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget);
            v.setTextViewText(R.id.w_title,
                    "ربات تریدر نوبیتکس" + (e.isRunning() ? "  ●" : "  ○"));

            double price = e.lastPrice;
            v.setTextViewText(R.id.w_market, m.title);
            if (price > 0) {
                v.setTextViewText(R.id.w_price,
                        Fmt.quote(price, m.isRls) + " " + m.quoteUnit());
            } else {
                v.setTextViewText(R.id.w_price, "—");
            }

            int green = 0xFF16C784;
            int red = 0xFFEA3943;
            int gray = 0xFF93A0B8;
            if (prefs.posActive() && price > 0 && prefs.posEntry() > 0) {
                double pnl = (price / prefs.posEntry() - 1.0) * 100.0;
                v.setTextViewText(R.id.w_sub, "پوزیشن: " + Fmt.pct(pnl));
                v.setTextColor(R.id.w_sub, pnl >= 0 ? green : red);
            } else if (e.dayChangePct != 0) {
                v.setTextViewText(R.id.w_sub, "۲۴ ساعته: " + Fmt.pct(e.dayChangePct));
                v.setTextColor(R.id.w_sub, e.dayChangePct >= 0 ? green : red);
            } else {
                v.setTextViewText(R.id.w_sub, e.isRunning() ? "در انتظار سیگنال" : "متوقف");
                v.setTextColor(R.id.w_sub, gray);
            }

            Intent open = new Intent(ctx, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(ctx, 2, open, flags);
            v.setOnClickPendingIntent(R.id.widget_root, pi);

            mgr.updateAppWidget(ids, v);
        } catch (Throwable ignored) {
        }
    }
}
