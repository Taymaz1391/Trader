package ir.nobitrader.bot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the bot after a device reboot when it was running before. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        try {
            if (new Prefs(ctx).botWasRunning()) {
                BotService.start(ctx);
            }
        } catch (Throwable ignored) {
        }
    }
}
