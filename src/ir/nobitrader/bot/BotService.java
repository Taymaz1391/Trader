package ir.nobitrader.bot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** Foreground service that keeps the BotEngine alive with an ongoing notification. */
public class BotService extends Service {

    public static final String CHANNEL_ID = "nobitrader_bot";
    public static final int NOTIF_ID = 1;

    private PowerManager.WakeLock wl;

    // ------------------------------------------------------------------

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, BotService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, BotService.class));
    }

    /** re-post the status notification (called by the engine) */
    public static void postStatus(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        try {
            nm.notify(NOTIF_ID, build(ctx));
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    "ربات تریدر نوبیتکس", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("وضعیت اجرای ربات معامله‌گر");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nobitrader:engine");
            wl.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, build(this));
        if (wl != null) wl.acquire();
        BotEngine.get(this).start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        BotEngine.get(this).stop();
        if (wl != null && wl.isHeld()) wl.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------

    private static Notification build(Context ctx) {
        BotEngine e = BotEngine.get(ctx);
        Prefs p = new Prefs(ctx);
        Market m = Market.of(p.cfg().symbol);

        String text;
        if (p.posActive()) {
            double price = e.lastPrice > 0 ? e.lastPrice : p.posEntry();
            double pnl = p.posEntry() > 0 ? (price / p.posEntry() - 1.0) * 100.0 : 0;
            text = "در پوزیشن " + Market.coinName(m.src) + " — " + Fmt.pct(pnl);
        } else {
            text = "در انتظار سیگنال — " + m.title;
        }

        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, flags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(ctx, CHANNEL_ID);
        } else {
            b = new Notification.Builder(ctx);
        }
        b.setContentTitle("ربات تریدر نوبیتکس")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        return b.build();
    }
}
