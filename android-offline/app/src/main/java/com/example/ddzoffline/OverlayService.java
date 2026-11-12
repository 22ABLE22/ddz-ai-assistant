package com.example.ddzoffline;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Native Android overlay for manual card entry while another app is visible. */
public final class OverlayService extends Service {
    private DdzEngine engine;
    private WindowManager windowManager;
    private View overlay;
    private WindowManager.LayoutParams params;
    private TextView status;
    private TextView move;
    private TextView hand;
    private EditText cards;
    private boolean folded;

    @Override
    public void onCreate() {
        super.onCreate();
        engine = GameState.engine(this);
        startForegroundServiceNotification();
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先允许悬浮窗权限", Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        try {
            buildOverlay();
        } catch (Exception e) {
            Toast.makeText(this, "悬浮窗创建失败：" + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override
    public void onDestroy() {
        if (overlay != null) windowManager.removeView(overlay);
        overlay = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void startForegroundServiceNotification() {
        String channelId = "ddz_overlay";
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(channelId, "斗地主助手", NotificationManager.IMPORTANCE_LOW));
        }
        Notification notification = new Notification.Builder(this, channelId)
                .setContentTitle("斗地主离线助手")
                .setContentText("悬浮窗正在运行，点击返回助手设置")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build();
        startForeground(1001, notification);
    }

    private void buildOverlay() {
        LinearLayout root = column();
        root.setBackgroundColor(Color.rgb(14, 61, 46));
        root.setPadding(10, 8, 10, 10);
        TextView title = text("🃏 离线军师", 16, Color.rgb(255, 224, 130));
        title.setPadding(8, 0, 8, 0);
        root.addView(title, full(46));
        installDrag(title);

        status = text(engine.isStarted() ? engine.status() : "请先在主界面开始对局", 12, Color.rgb(255, 224, 130));
        root.addView(status, full(40));
        cards = input("本轮出的牌");
        root.addView(cards, full(48));
        LinearLayout recordRow = row();
        Button record = button("记录");
        Button pass = button("过");
        recordRow.addView(record, weight());
        recordRow.addView(pass, weight());
        root.addView(recordRow, full(46));
        record.setOnClickListener(v -> record(cards.getText().toString()));
        pass.setOnClickListener(v -> record(""));

        move = text("推荐：—", 17, Color.rgb(255, 213, 79));
        move.setGravity(Gravity.CENTER);
        root.addView(move, full(48));
        LinearLayout actionRow = row();
        Button recommend = button("推荐");
        Button adopt = button("采用");
        Button fold = button("收起");
        actionRow.addView(recommend, weight());
        actionRow.addView(adopt, weight());
        actionRow.addView(fold, weight());
        root.addView(actionRow, full(46));
        recommend.setOnClickListener(v -> recommend());
        adopt.setOnClickListener(v -> adopt());
        fold.setOnClickListener(v -> toggleFold(root, actionRow, fold));

        hand = text("手牌：" + (engine.isStarted() ? engine.getHandText() : "—"), 11, Color.rgb(165, 214, 196));
        root.addView(hand, full(36));
        TextView close = text("关闭悬浮窗", 12, Color.rgb(255, 138, 128));
        close.setGravity(Gravity.CENTER);
        root.addView(close, full(34));
        close.setOnClickListener(v -> stopSelf());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        overlay = scroll;
        params = new WindowManager.LayoutParams(dp(300), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = dp(8);
        params.y = dp(90);
        windowManager.addView(overlay, params);
    }

    private void record(String value) {
        try {
            engine.record(engine.getTurn(), value);
            cards.setText("");
            refresh(engine.status());
        } catch (Exception e) { fail(e); }
    }

    private void recommend() {
        try {
            DdzEngine.Recommendation r = engine.recommend();
            move.setText("推荐：" + r.move);
            status.setText(r.candidates.size() > 1
                    ? "已生成推荐（" + r.candidates.size() + " 个候选，详见主界面）"
                    : "已生成推荐");
        } catch (Exception e) { fail(e); }
    }

    private void adopt() {
        String value = move.getText().toString().replace("推荐：", "").trim();
        if (value.isEmpty() || value.equals("—") || value.equals("不出")) {
            record("");
        } else {
            record(value);
        }
        move.setText("推荐：—");
    }

    private void refresh(String value) {
        status.setText(value);
        hand.setText("手牌：" + engine.getHandText());
        move.setText("推荐：—");
    }

    private void fail(Exception e) {
        status.setText(e.getMessage() == null ? "操作失败" : e.getMessage());
    }

    private void toggleFold(LinearLayout root, LinearLayout actions, Button fold) {
        folded = !folded;
        for (int i = 1; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child != actions) child.setVisibility(folded ? View.GONE : View.VISIBLE);
        }
        actions.setVisibility(View.VISIBLE);
        fold.setText(folded ? "展开" : "收起");
    }

    private void installDrag(View handle) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY; int startX, startY;
            @Override public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    downX = event.getRawX(); downY = event.getRawY(); startX = params.x; startY = params.y; return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    params.x = startX - (int) (event.getRawX() - downX);
                    params.y = startY + (int) (event.getRawY() - downY);
                    windowManager.updateViewLayout(overlay, params); return true;
                }
                return true;
            }
        });
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private Button button(String value) { Button b = new Button(this); b.setText(value); b.setTextSize(12); b.setAllCaps(false); return b; }
    private EditText input(String hint) { EditText e = new EditText(this); e.setHint(hint); e.setTextColor(Color.WHITE); e.setHintTextColor(Color.rgb(127, 196, 168)); e.setTextSize(14); e.setSingleLine(true); e.setPadding(10, 0, 10, 0); e.setBackgroundColor(Color.rgb(11, 47, 34)); return e; }
    private TextView text(String value, int size, int color) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setGravity(Gravity.CENTER_VERTICAL); return t; }
    private LinearLayout.LayoutParams full(int height) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(height)); p.setMargins(0, dp(2), 0, dp(2)); return p; }
    private LinearLayout.LayoutParams weight() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1); p.setMargins(dp(2), 0, dp(2), 0); return p; }
}
