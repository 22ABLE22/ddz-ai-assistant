package com.example.ddzoffline;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.ddzoffline.ai.HandRecognizer;
import com.example.ddzoffline.ai.TemplateMatcher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 从截图读手牌。
 *
 * <p>两种模式：
 * <ul>
 *   <li><b>标定</b>（首次）：显示参考截图，用户按 3→大王的顺序各点一下，
 *       每次点按裁一个固定框存为模板。15 次点按，一次搞定，之后永久复用。</li>
 *   <li><b>识别</b>：显示手牌截图，切槽匹配模板，把结果回填到手牌输入框。</li>
 * </ul>
 *
 * <p>取图用 {@code ACTION_OPEN_DOCUMENT}，不需要任何权限（比 MediaProjection 干净）。
 * 手牌张数由调用方传入，地主 20 / 农民 17。
 */
public class HandCaptureActivity extends Activity {

    public static final String EXTRA_COUNT = "hand_count";
    public static final String EXTRA_RESULT = "hand_text";

    /** 标定顺序：与显示牌面对应。 */
    private static final int[] CALIB_RANKS = {
            3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 17, 20, 30
    };
    private static final String[] CALIB_NAMES = {
            "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A", "2", "小王", "大王"
    };

    private static final int REQ_PICK = 1001;
    /** 模板裁框尺寸（像素，原图尺度）。 */
    private static final int TEMPL_W = 48, TEMPL_H = 96;

    private static final int C_BG = 0xFF0E3D2E, C_FG = 0xFFFFFFFF;
    private static final int C_GOLD = 0xFFE8B84B, C_HINT = 0xFF9DB5AC;
    private static final int C_BORDER = 0xFF2C5B4A, C_TEAL = 0xFF136F63;

    private int handCount = 17;
    private boolean calibrating;

    private ImageView preview;
    private TextView status, tapHint;
    private Button pick, action;

    private Bitmap image;
    private final List<int[]> taps = new ArrayList<>();   // 标定时的点击点
    private final List<HandRecognizer.CardTemplate> templates = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handCount = getIntent().getIntExtra(EXTRA_COUNT, 17);
        calibrating = !templateFile().exists();
        buildUi();
        if (!calibrating) loadTemplates();
    }

    private File templateFile() {
        return new File(getFilesDir(), "hand_templates.ddzt");
    }

    // ---------------- UI ----------------

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(C_BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, p);
        scroll.addView(root);

        root.addView(text("从截图读手牌", 20, C_GOLD));

        status = text("", 13, C_HINT);
        LinearLayout.LayoutParams slp = wrap();
        slp.topMargin = dp(8);
        root.addView(status, slp);

        tapHint = text("", 12, C_GOLD);
        LinearLayout.LayoutParams thp = wrap();
        thp.topMargin = dp(6);
        root.addView(tapHint, thp);

        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams plp = wrap();
        plp.topMargin = dp(10);
        root.addView(preview, plp);

        pick = button(calibrating ? "① 选一张包含全部点数的截图" : "① 选一张手牌截图");
        pick.setOnClickListener(v -> pickImage());
        LinearLayout.LayoutParams pkp = wrap();
        pkp.topMargin = dp(10);
        root.addView(pick, pkp);

        action = button(calibrating ? "② 完成标定" : "② 识别并填入");
        action.setEnabled(false);
        action.setOnClickListener(v -> {
            if (calibrating) finishCalibration();
            else doRecognize();
        });
        LinearLayout.LayoutParams alp = wrap();
        alp.topMargin = dp(8);
        root.addView(action, alp);

        Button cancel = button("返回");
        cancel.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams clp = wrap();
        clp.topMargin = dp(8);
        root.addView(cancel, clp);

        if (calibrating) {
            root.addView(text("提示：截图需包含 3~2 的全部点数（一张完整手牌图通常够用）。\n"
                    + "标定只需做一次，之后每次开局直接识别。", 11, C_HINT), wrap());
        }

        setContentView(scroll);
        renderStatus();
    }

    private void renderStatus() {
        if (calibrating) {
            int done = taps.size();
            if (done == 0) {
                status.setText("首次使用需标定一次：选图后按顺序点每个点数。");
            } else {
                status.setText("已标定 " + done + " / " + CALIB_RANKS.length + " 个点数");
            }
            tapHint.setText(done < CALIB_RANKS.length
                    ? "请点： " + CALIB_NAMES[done]
                    : "全部点完，点下方「完成标定」保存");
        } else {
            status.setText("模板已就绪（" + templates.size() + " 个点数）。选一张裁好的手牌截图即可识别。");
            tapHint.setText("");
        }
    }

    // ---------------- 取图 ----------------

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            toast("打不开图片选择器：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            Bitmap bmp = BitmapFactory.decodeStream(in);
            if (in != null) in.close();
            if (bmp == null) { toast("读不出这张图"); return; }

            image = bmp;
            preview.setImageBitmap(bmp);
            taps.clear();
            action.setEnabled(!calibrating);
            if (calibrating) attachTapListener();
            renderStatus();
        } catch (Exception e) {
            toast("读图失败：" + e.getMessage());
        }
    }

    // ---------------- 标定 ----------------

    /** 点按 → 换算到原图像素 → 裁框存模板。 */
    private void attachTapListener() {
        preview.setOnTouchListener((v, ev) -> {
            if (ev.getAction() != MotionEvent.ACTION_UP) return true;
            if (image == null) return true;
            if (taps.size() >= CALIB_RANKS.length) return true;

            // 显示坐标 → 原图坐标（FIT_CENTER 留白要扣掉）
            float vw = v.getWidth(), vh = v.getHeight();
            float iw = image.getWidth(), ih = image.getHeight();
            float scale = Math.min(vw / iw, vh / ih);
            float dw = iw * scale, dh = ih * scale;
            float ox = (vw - dw) / 2f, oy = (vh - dh) / 2f;
            float px = (ev.getX() - ox) / scale;
            float py = (ev.getY() - oy) / scale;
            if (px < 0 || py < 0 || px >= iw || py >= ih) return true;

            taps.add(new int[]{(int) px, (int) py});
            renderStatus();
            if (taps.size() == CALIB_RANKS.length) {
                action.setEnabled(true);
                toast("点完了，记得点「完成标定」保存");
            }
            return true;
        });
    }

    private void finishCalibration() {
        if (taps.size() != CALIB_RANKS.length) {
            toast("还差 " + (CALIB_RANKS.length - taps.size()) + " 个点数没点");
            return;
        }
        try {
            List<HandRecognizer.CardTemplate> out = new ArrayList<>();
            for (int i = 0; i < CALIB_RANKS.length; i++) {
                int[] pt = taps.get(i);
                int x = clamp(pt[0] - TEMPL_W / 2, 0, Math.max(0, image.getWidth() - TEMPL_W));
                int y = clamp(pt[1] - TEMPL_H / 2, 0, Math.max(0, image.getHeight() - TEMPL_H));
                int w = Math.min(TEMPL_W, image.getWidth() - x);
                int h = Math.min(TEMPL_H, image.getHeight() - y);
                if (w < 8 || h < 8) throw new IllegalStateException("截图太小，裁不出模板");

                int[] px = new int[w * h];
                image.getPixels(px, 0, w, x, y, w, h);
                out.add(new HandRecognizer.CardTemplate(
                        CALIB_RANKS[i], TemplateMatcher.toGray(px, w, h)));
            }
            byte[] blob = HandRecognizer.encode(out);
            try (FileOutputStream fo = new FileOutputStream(templateFile())) {
                fo.write(blob);
            }
            toast("标定完成，共 " + out.size() + " 个点数");
            setResult(RESULT_OK);
            finish();
        } catch (Exception e) {
            toast("标定失败：" + e.getMessage());
        }
    }

    private void loadTemplates() {
        try {
            byte[] raw = new byte[(int) templateFile().length()];
            try (InputStream in = new java.io.FileInputStream(templateFile())) {
                int off = 0;
                while (off < raw.length) {
                    int n = in.read(raw, off, raw.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            templates.clear();
            templates.addAll(HandRecognizer.decode(raw));
        } catch (Exception e) {
            // 模板坏了就退回标定模式
            calibrating = true;
            templates.clear();
        }
    }

    // ---------------- 识别 ----------------

    private void doRecognize() {
        if (image == null) { toast("先选一张图"); return; }
        try {
            int w = image.getWidth(), h = image.getHeight();
            int[] px = new int[w * h];
            image.getPixels(px, 0, w, 0, 0, w, h);

            // 与模板同尺度：模板存的是原图尺度，这里不降采样
            TemplateMatcher.Gray gray = TemplateMatcher.toGray(px, w, h);
            HandRecognizer.Result r =
                    HandRecognizer.recognize(gray, handCount, templates, 0.6);

            if (!r.allRecognized()) {
                status.setText("识别结果：" + r.describe()
                        + "\n有认不出的（显示为 ?）。可以换张更清晰的截图，"
                        + "或重新标定一次。");
                tapHint.setText("");
                toast("有 " + countUnknown(r) + " 张没认出来，建议重新截图");
                return;
            }

            Intent out = new Intent();
            out.putExtra(EXTRA_RESULT, r.describe());
            setResult(RESULT_OK, out);
            finish();
        } catch (Exception e) {
            toast("识别失败：" + e.getMessage());
        }
    }

    private static int countUnknown(HandRecognizer.Result r) {
        int n = 0;
        for (HandRecognizer.Slot s : r.slots) if (s.rank < 0) n++;
        return n;
    }

    // ---------------- 小工具 ----------------

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView text(String s, int size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setLineSpacing(dp(3), 1f);
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(C_FG);
        b.setBackgroundColor(C_BORDER);
        b.setGravity(Gravity.CENTER);
        return b;
    }
}
