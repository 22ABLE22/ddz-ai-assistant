package com.example.ddzoffline;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.view.accessibility.AccessibilityEvent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 前台应用监听 —— 判断「现在是不是在打斗地主」。
 *
 * <p>为什么用无障碍服务：
 * <ul>
 *   <li>事件驱动（{@code TYPE_WINDOW_STATE_CHANGED}），不轮询、不耗电</li>
 *   <li>能拿到前台包名 —— 这是 Android 上唯一稳定的前台应用识别方式</li>
 *   <li>代价：用户要在系统设置里手动开启一次（一次性）</li>
 * </ul>
 *
 * <p><b>不读取窗口内容</b>（配置里 {@code canRetrieveWindowContent="false"}）：
 * 只要包名，不要别的。这是能满足需求的最小权限。
 *
 * <p><b>包名不硬编码</b>：斗地主有一堆版本（欢乐/腾讯/各种小程序），
 * 硬编码必然失效。做法是让用户在「最近进入的应用」里点一次，
 * 存下来复用。任何版本都能用，游戏改包名也不用改代码。
 */
public class GameWatchService extends AccessibilityService {

    public static final String PREFS = "ddz_watch";
    public static final String KEY_WATCH = "watch_pkg";
    public static final String KEY_RECENT = "recent_pkgs";
    public static final String KEY_ENABLED = "auto_enabled";

    /** 最近记录的前台应用数量上限。 */
    private static final int RECENT_MAX = 12;

    /** 记录一次前台应用切换。 */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

        CharSequence cs = event.getPackageName();
        if (cs == null) return;
        String pkg = cs.toString();
        if (pkg.isEmpty()) return;
        // 别把自己记进去
        if (getPackageName().equals(pkg)) return;

        SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        rememberRecent(sp, pkg);

        if (!sp.getBoolean(KEY_ENABLED, true)) return;

        String watch = sp.getString(KEY_WATCH, null);
        if (watch != null && watch.equals(pkg)) {
            onWatchedAppOpened(pkg);
        }
    }

    /** 记住最近进入过的应用，供用户在界面上挑选。 */
    private void rememberRecent(SharedPreferences sp, String pkg) {
        String raw = sp.getString(KEY_RECENT, "");
        Set<String> set = new LinkedHashSet<>();
        set.add(pkg);
        for (String s : raw.split("\n")) {
            if (!s.isEmpty() && set.size() < RECENT_MAX) set.add(s);
        }
        sp.edit().putString(KEY_RECENT, String.join("\n", set)).apply();
    }

    /**
     * 目标应用被打开时的动作。
     *
     * <p>这里只发一条通知，不直接拉起界面 —— Android 10 以后禁止后台服务
     * 直接启动 Activity，发通知是标准且合规的做法。
     */
    private void onWatchedAppOpened(String pkg) {
        String name = appLabel(pkg);
        WatchNotifier.notifyGameDetected(this, name);
    }

    /** 包名 → 应用名，拿不到就退回包名。 */
    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    @Override
    public void onInterrupt() {
        // 不需要处理
    }

    // ---------------- 供界面调用的静态辅助 ----------------

    /** 读取最近记录的前台应用（最新的在前）。 */
    public static List<String> recentPackages(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_RECENT, "");
        List<String> out = new ArrayList<>();
        for (String s : raw.split("\n")) {
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** 设置要监听的应用。 */
    public static void setWatched(Context ctx, String pkg) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_WATCH, pkg).apply();
    }

    public static String getWatched(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_WATCH, null);
    }

    public static void setEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, on).apply();
    }

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true);
    }

    /** 无障碍服务是否已在系统设置里开启。 */
    public static boolean isServiceOn(Context ctx) {
        String flat = android.provider.Settings.Secure.getString(
                ctx.getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (flat == null) return false;
        String me = ctx.getPackageName() + "/" + GameWatchService.class.getName();
        for (String s : flat.split(":")) {
            if (s.equalsIgnoreCase(me)) return true;
        }
        return false;
    }

    /** 跳到系统无障碍设置页。 */
    public static void openSettings(Context ctx) {
        try {
            ctx.startActivity(new android.content.Intent(
                    android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }
}
