package com.example.ddzoffline;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 手牌预设与历史持久化 —— 解决「每次开局逐张输入很麻烦」。
 *
 * 两类数据：
 *   预设：用户主动保存的常用手牌，可命名，一键回填
 *   历史：最近若干局的起始手牌，自动记录，可一键复用
 *
 * 存储用 SharedPreferences（无第三方依赖）。格式：每项 `名称|紧凑牌串`。
 */
public final class HandStore {

    private static final String PREF = "ddz_hands";
    private static final String KEY_PRESETS = "presets";
    private static final String KEY_HISTORY = "history";

    private static final int MAX_HISTORY = 12;
    private static final String SEP = "\u0001";   // 用不可见字符分隔，避免与牌面冲突

    private final SharedPreferences prefs;

    public HandStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 一条记录。 */
    public static final class Entry {
        public final String name;
        public final String cards;      // 紧凑串
        public final int laiziRank;     // -1 表示经典模式
        public final int position;      // 0=地主 1=下家 2=上家

        Entry(String name, String cards, int laiziRank, int position) {
            this.name = name;
            this.cards = cards;
            this.laiziRank = laiziRank;
            this.position = position;
        }

        public String display() {
            return name.isEmpty() ? cards : name + "　" + cards;
        }
    }

    // ---------------- 预设 ----------------

    public List<Entry> presets() {
        return decode(prefs.getString(KEY_PRESETS, ""));
    }

    /** 保存预设；同名则覆盖。 */
    public void savePreset(String name, String cards, int laiziRank, int position) {
        List<Entry> list = presets();
        String cleanName = name == null ? "" : name.trim();
        // 覆盖同名项
        List<Entry> out = new ArrayList<>();
        for (Entry e : list) {
            if (!e.name.equals(cleanName)) out.add(e);
        }
        out.add(0, new Entry(cleanName, cards, laiziRank, position));
        if (out.size() > 30) out = out.subList(0, 30);
        prefs.edit().putString(KEY_PRESETS, encode(out)).apply();
    }

    public void deletePreset(String name) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : presets()) {
            if (!e.name.equals(name)) out.add(e);
        }
        prefs.edit().putString(KEY_PRESETS, encode(out)).apply();
    }

    // ---------------- 历史 ----------------

    public List<Entry> history() {
        return decode(prefs.getString(KEY_HISTORY, ""));
    }

    /** 记录一次开局；相同牌串会被提到最前，不重复堆积。 */
    public void pushHistory(String cards, int laiziRank, int position) {
        List<Entry> out = new ArrayList<>();
        out.add(new Entry("", cards, laiziRank, position));
        for (Entry e : history()) {
            if (e.cards.equals(cards)) continue;
            out.add(e);
        }
        if (out.size() > MAX_HISTORY) out = out.subList(0, MAX_HISTORY);
        prefs.edit().putString(KEY_HISTORY, encode(out)).apply();
    }

    public void clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply();
    }

    // ---------------- 编解码 ----------------

    private static String encode(List<Entry> list) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : list) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.name).append(SEP).append(e.cards).append(SEP)
              .append(e.laiziRank).append(SEP).append(e.position);
        }
        return sb.toString();
    }

    private static List<Entry> decode(String raw) {
        List<Entry> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (String line : raw.split("\n")) {
            if (line.isEmpty()) continue;
            String[] parts = line.split(SEP, -1);
            if (parts.length < 4) continue;
            try {
                String key = parts[0] + "|" + parts[1];
                if (!seen.add(key)) continue;
                out.add(new Entry(parts[0], parts[1],
                        Integer.parseInt(parts[2]), Integer.parseInt(parts[3])));
            } catch (NumberFormatException ignored) {
                // 跳过损坏条目，不影响其余数据
            }
        }
        return out;
    }

    // ---------------- 便捷：从已选牌生成 ----------------

    /** 从牌值数组生成紧凑串（用于保存）。 */
    public static String toCompact(int[] cards) {
        return com.example.ddzoffline.ai.CardInput.toCompact(cards);
    }

    /** 牌串 -> 牌值数组。 */
    public static int[] parse(String compact) {
        return com.example.ddzoffline.ai.CardInput.parse(compact).toArray();
    }
}
