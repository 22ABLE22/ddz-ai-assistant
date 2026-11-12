package com.example.ddzoffline;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.ddzoffline.ai.CardInput;
import com.example.ddzoffline.ai.Types;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 点选式牌面录入器 —— 消灭「每次开局逐张敲」。
 *
 * 交互：
 *   - 上方是 15 个牌面按钮（3..2 X D），每点一次加一张
 *   - 下方实时显示已选牌；点已选的牌可以减一张
 *   - 支持上限（普通牌 4 张、大小王 1 张），达到上限后按钮变暗
 *   - 「清空」一键归零
 *
 * 与文本录入的关系：本控件内部同样用牌值数组，
 * 最终与 {@link CardInput#parse} 产出完全一致的表示（有测试保证）。
 */
public final class CardPickerView extends LinearLayout {

    /** 牌面顺序（与记牌器一致）。 */
    private static final int[] RANKS = {3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 17, 20, 30};

    private static final int C_DEEP = Color.rgb(11, 47, 34);
    private static final int C_BORDER = Color.rgb(42, 122, 94);
    private static final int C_GOLD = Color.rgb(255, 213, 79);
    private static final int C_GOLD_INK = Color.rgb(26, 26, 26);
    private static final int C_FG = Color.WHITE;
    private static final int C_MUTED = Color.rgb(165, 214, 196);
    private static final int C_HINT = Color.rgb(154, 217, 191);

    private final Map<Integer, Integer> picked = new LinkedHashMap<>();
    private LinearLayout rankRow1, rankRow2, selectedRow;
    private TextView summary;
    private OnChangeListener listener;
    private int maxCards = 20;
    private int laiziRank = -1;

    public interface OnChangeListener {
        void onChange(int[] cards);
    }

    public CardPickerView(Context context) {
        super(context);
        init();
    }

    public CardPickerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public void setOnChangeListener(OnChangeListener l) { this.listener = l; }

    /** 设置赖子点数（-1 表示经典模式）；赖子按钮会高亮。 */
    public void setLaiziRank(int rank) {
        this.laiziRank = rank;
        refresh();
    }

    public void setMaxCards(int max) {
        this.maxCards = max;
        refresh();
    }

    private void init() {
        setOrientation(VERTICAL);

        rankRow1 = row();
        rankRow2 = row();
        addView(rankRow1, matchWrap());
        addView(rankRow2, matchWrap());

        LinearLayout header = row();
        summary = new TextView(getContext());
        summary.setTextSize(12);
        summary.setTextColor(C_HINT);
        header.addView(summary, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        TextView clear = new TextView(getContext());
        clear.setText("清空");
        clear.setTextSize(13);
        clear.setTextColor(C_MUTED);
        clear.setPadding(dp(12), dp(6), dp(12), dp(6));
        clear.setBackground(rounded(0x00000000, C_BORDER, 6));
        clear.setOnClickListener(v -> { picked.clear(); refresh(); });
        header.addView(clear, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        LayoutParams hp = matchWrap();
        hp.topMargin = dp(6);
        addView(header, hp);

        selectedRow = row();
        LayoutParams sp = matchWrap();
        sp.topMargin = dp(4);
        addView(selectedRow, sp);

        refresh();
    }

    private void buildRankButtons() {
        rankRow1.removeAllViews();
        rankRow2.removeAllViews();
        for (int i = 0; i < RANKS.length; i++) {
            final int rank = RANKS[i];
            TextView b = new TextView(getContext());
            b.setText(Types.label(rank));
            b.setTextSize(16);
            b.setGravity(Gravity.CENTER);
            b.setTypeface(null, android.graphics.Typeface.BOLD);
            b.setOnClickListener(v -> add(rank));
            LayoutParams lp = new LayoutParams(0, dp(46), 1);
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            if (i < 8) rankRow1.addView(b, lp);
            else rankRow2.addView(b, lp);
        }
    }

    /** 点一次加一张；已达上限则忽略。 */
    public void add(int rank) {
        int cur = picked.getOrDefault(rank, 0);
        if (cur >= maxPerRank(rank)) return;
        if (total() >= maxCards) return;
        picked.put(rank, cur + 1);
        refresh();
    }

    /** 点已选牌减一张。 */
    public void remove(int rank) {
        int cur = picked.getOrDefault(rank, 0);
        if (cur <= 0) return;
        if (cur == 1) picked.remove(rank);
        else picked.put(rank, cur - 1);
        refresh();
    }

    private static int maxPerRank(int rank) {
        return (rank == 20 || rank == 30) ? 1 : 4;
    }

    public int total() {
        int n = 0;
        for (int v : picked.values()) n += v;
        return n;
    }

    /** 当前已选的牌值数组（已排序）。 */
    public int[] cards() {
        List<Integer> out = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : picked.entrySet()) {
            for (int i = 0; i < e.getValue(); i++) out.add(e.getKey());
        }
        out.sort(Integer::compareTo);
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /** 以文本形式回填（用于预设/历史）。 */
    public void setCards(String compact) {
        picked.clear();
        int[] arr = CardInput.parse(compact).toArray();
        for (int c : arr) {
            int cur = picked.getOrDefault(c, 0);
            if (cur < maxPerRank(c)) picked.put(c, cur + 1);
        }
        refresh();
    }

    public void clear() {
        picked.clear();
        refresh();
    }

    /** 重新渲染全部子控件。 */
    private void refresh() {
        buildRankButtons();

        // 按钮状态：达到上限变暗
        styleRow(rankRow1, 0);
        styleRow(rankRow2, 8);

        // 已选牌展示
        selectedRow.removeAllViews();
        int[] cur = cards();
        if (cur.length == 0) {
            TextView t = new TextView(getContext());
            t.setText("点上方牌面添加");
            t.setTextSize(13);
            t.setTextColor(C_HINT);
            selectedRow.addView(t);
        } else {
            for (int c : cur) {
                final int rank = c;
                TextView chip = new TextView(getContext());
                chip.setText(Types.label(c));
                chip.setTextSize(15);
                chip.setTypeface(null, android.graphics.Typeface.BOLD);
                chip.setGravity(Gravity.CENTER);
                boolean joker = c == 20 || c == 30;
                boolean major = c == 17 || joker;
                chip.setTextColor(joker ? Color.rgb(255, 138, 101) : (major ? C_GOLD : C_FG));
                chip.setBackground(rounded(C_DEEP,
                        joker ? Color.rgb(255, 138, 101) : (major ? 0x80FFD54F : C_BORDER), 6));
                chip.setOnClickListener(v -> remove(rank));
                LayoutParams lp = new LayoutParams(dp(32), dp(42));
                lp.setMargins(dp(2), 0, dp(2), 0);
                selectedRow.addView(chip, lp);
            }
        }

        summary.setText("已选 " + total() + " 张" + (total() == 17 ? " ✓" : ""));
        summary.setTextColor(total() == 17 ? C_GOLD : C_HINT);

        if (listener != null) listener.onChange(cur);
    }

    private void styleRow(LinearLayout row, int offset) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            if (!(v instanceof TextView)) continue;
            int rank = RANKS[offset + i];
            int cur = picked.getOrDefault(rank, 0);
            boolean full = cur >= maxPerRank(rank);
            boolean laizi = rank == laiziRank;

            ((TextView) v).setTextColor(full ? C_HINT : C_FG);
            v.setAlpha(full ? 0.4f : 1f);
            int border = laizi ? Color.rgb(255, 82, 82) : C_BORDER;
            v.setBackground(rounded(cur > 0 ? Color.rgb(22, 84, 63) : C_DEEP, border, 6));
        }
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(getContext());
        l.setOrientation(HORIZONTAL);
        return l;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        if (stroke != 0) d.setStroke(dp(1), stroke);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private LayoutParams matchWrap() {
        return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
