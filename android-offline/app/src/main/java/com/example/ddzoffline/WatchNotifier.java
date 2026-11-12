package com.example.ddzoffline;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 「检测到对局」通知。
 *
 * <p>为什么不直接拉起界面：Android 10 以后禁止后台服务直接启动 Activity，
 * 发通知让用户点一下是标准且合规的做法。
 */
public final class WatchNotifier {

    private static final String CHANNEL = "ddz_watch";
    private static final int ID = 1001;

    private WatchNotifier() {}

    public static void notifyGameDetected(Context ctx, String appName) {
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "对局检测", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("检测到斗地主类应用被打开时提醒");
            nm.createNotificationChannel(ch);
        }

        Intent open = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(
                ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(ctx, CHANNEL)
                : new Notification.Builder(ctx);
        b.setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("检测到「" + appName + "」已打开")
                .setContentText("点此打开助手开始记录")
                .setContentIntent(pi)
                .setAutoCancel(true);

        try {
            nm.notify(ID, b.build());
        } catch (Exception ignored) {
            // Android 13+ 未授予通知权限时静默失败，不影响其他功能
        }
    }
}
