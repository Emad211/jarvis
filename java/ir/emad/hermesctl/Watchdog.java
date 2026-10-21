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

import org.json.JSONObject;

/**
 * Background watchdog: checks the bot every ~15 min and notifies when it goes down or comes
 * back. While it stays down it reminds at most every REMIND_MS, not on every run.
 *
 * JobScheduler (not a foreground service) on purpose: an Android 14+ foreground service needs
 * a declared type and a permanent notification, which costs battery; an idle job costs nothing.
 */
public class Watchdog extends JobService {

    private static final int JOB_ID = 8871;
    private static final int NOTIF_ID = 4401;
    private static final String CHANNEL = "hermes_status";
    private static final long REMIND_MS = 3 * 60 * 60 * 1000L;

    public static void schedule(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(JOB_ID) != null) {
            return;
        }
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(ctx, Watchdog.class))
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            boolean healthy;
            String detail;
            try {
                JSONObject st = Api.getBlocking("/status", 10000);
                String overall = J.str(st, "overall", "");
                String tg = J.str(st, "telegram", "");
                healthy = "online".equals(overall) && "connected".equals(tg);
                detail = "ربات: " + overall + " · تلگرام: " + HomeScreen.telegramLabel(tg);
            } catch (Exception e) {
                healthy = false;
                detail = "سرویس کنترل جواب نداد (ترموکس خوابیده؟)";
            }
            report(healthy, detail);
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    private void report(boolean healthy, String detail) {
        SharedPreferences sp = getSharedPreferences("watchdog", MODE_PRIVATE);
        boolean wasHealthy = sp.getBoolean("healthy", true);
        long lastAlert = sp.getLong("last_alert", 0);
        long now = System.currentTimeMillis();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "وضعیت هرمس",
                NotificationManager.IMPORTANCE_HIGH));

        if (healthy) {
            if (!wasHealthy) {
                post(nm, "ربات دوباره وصل شد", detail);
            }
            sp.edit().putBoolean("healthy", true).apply();
            return;
        }
        if (wasHealthy || now - lastAlert > REMIND_MS) {
            post(nm, "ربات هرمس قطع است", detail + " — برای باز کردن لمس کن");
            sp.edit().putLong("last_alert", now).apply();
        }
        sp.edit().putBoolean("healthy", false).apply();
    }

    private void post(NotificationManager nm, String title, String body) {
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setColor(Ui.ACCENT)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        try {
            nm.notify(NOTIF_ID, n);
        } catch (SecurityException ignored) {
            // notification permission denied (Android 13+)
        }
    }
}
