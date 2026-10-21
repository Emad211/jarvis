package ir.emad.hermesctl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import org.json.JSONObject;

/**
 * Background watchdog: pings the gwctl server every ~15 min and raises a
 * notification the moment the gateway stops being healthy.
 *
 * JobScheduler (not a foreground Service) on purpose: a persistent foreground
 * service on Android 14+ needs a declared FGS type and a visible notification at
 * all times, which drains the phone. A periodic job costs nothing while idle.
 */
public class Watchdog extends JobService {

    private static final int JOB_ID = 8871;
    private static final String CHANNEL = "hermes_status";
    private static final String PREFS = "hcontrol";
    private static final int NOTIF_ID = 4401;

    public static void schedule(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) {
                return;
            }
            JobInfo.Builder b = new JobInfo.Builder(JOB_ID,
                    new ComponentName(ctx, Watchdog.class))
                    .setPeriodic(15 * 60 * 1000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true);
            js.schedule(b.build());
        } catch (Exception ignored) {
            // scheduling is best-effort; the app still works when opened manually
        }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context ctx = this;
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean healthy = false;
                String detail = "";
                try {
                    JSONObject status = Api.execForJob("/status", 9000);
                    String overall = J.str(status, "overall", "");
                    String tg = J.str(status, "telegram", "");
                    healthy = "online".equals(overall) && "connected".equals(tg);
                    detail = overall + " · تلگرام: " + tg;
                } catch (Exception e) {
                    detail = "سرویس کنترل جواب نداد";
                }
                notifyIfChanged(ctx, healthy, detail);
                jobFinished(params, false);
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    private void notifyIfChanged(Context ctx, boolean healthy, String detail) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean lastHealthy = sp.getBoolean("last_healthy", true);
        sp.edit().putBoolean("last_healthy", healthy).apply();

        NotificationManager nm = (NotificationManager) ctx.getSystemService(
                Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    "وضعیت هرمس", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("اعلان قطع شدن ربات");
            nm.createNotificationChannel(ch);
        }

        if (healthy) {
            if (!lastHealthy) {
                post(ctx, nm, "ربات دوباره وصل شد ✓", detail, Ui.GREEN);
            }
            return;
        }
        post(ctx, nm, "ربات هرمس قطع است", detail + " — برای روشن کردن لمس کن", Ui.RED);
    }

    private void post(Context ctx, NotificationManager nm, String title, String body,
                      int color) {
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CHANNEL)
                : new Notification.Builder(ctx);
        b.setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setColor(color)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setStyle(new Notification.BigTextStyle().bigText(body));
        try {
            nm.notify(NOTIF_ID, b.build());
        } catch (Exception ignored) {
            // notifications denied (Android 13+ runtime permission)
        }
    }
}
