package com.example.ddzoffline.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 手牌输入解析的增强层。
 *
 * 目标是消灭「每次开局逐张敲」的重复劳动。支持三类便捷写法，
 * 全部归一化到与 {@link #parseStrict} 完全一致的结果：
 *
 *   1. 紧凑串：`34567JJQQKKAA2XD`
 *   2. 带数量：`3*2 4*2 J*2 Q*2`（星号表示重复张数）
 *   3. 区间：  `3-7` 展开为 3 4 5 6 7
 *
 * 关键约定：便捷写法只是「语法糖」，最终仍走同一套牌值映射，
 * 因此点选录入与文本录入的结果必然一致（有测试保证）。
 */
public final class CardInput {

    /** 解析结果。 */
    public static final class Parsed {
        public final List<Integer> cards;
        public final List<String> errors;

        Parsed(List<Integer> cards, List<String> errors) {
            this.cards = cards;
            this.errors = errors;
        }

        public boolean ok() { return errors.isEmpty(); }
        public int size() { return cards.size(); }
        public int[] toArray() {
            int[] o = new int[cards.size()];
            for (int i = 0; i < o.length; i++) o[i] = cards.get(i);
            return o;
        }
    }

    /**
     * 宽松解析：支持紧凑串 / 数量后缀 / 区间 / 空格逗号分隔。
     * 无法识别的片段记入 errors，但仍返回已解析出的牌。
     */
    public static Parsed parse(String text) {
        List<Integer> out = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return new Parsed(out, errors);

        // 先按空白/逗号切成 token；token 内可能还有数量后缀或区间
        String normalized = text.replace('，', ',').replace('、', ',').toUpperCase();
        String[] tokens = normalized.split("[\\s,]+");

        for (String token : tokens) {
            if (token.isEmpty()) continue;
            expandToken(token, out, errors);
        }
        out.sort(Integer::compareTo);
        return new Parsed(out, errors);
    }

    /** 展开单个 token：`3-7` / `3*4` / `J*2` / `34567`。 */
    private static void expandToken(String token, List<Integer> out, List<String> errors) {
        // 区间写法：3-7
        int dash = token.indexOf('-');
        if (dash > 0 && dash < token.length() - 1) {
            String a = token.substring(0, dash);
            String b = token.substring(dash + 1);
            Integer ra = single(a);
            Integer rb = single(b);
            if (ra == null || rb == null) {
                errors.add("无法识别的区间：" + token);
                return;
            }
            if (ra > rb) { int t = ra; ra = rb; rb = t; }
            for (int r = ra; r <= rb; r++) {
                if (!isValidRank(r)) { errors.add("区间含无效牌：" + Types.label(r)); continue; }
                out.add(r);
            }
            return;
        }

        // 数量写法：3*4 / J*2
        int star = token.indexOf('*');
        if (star > 0 && star < token.length() - 1) {
            String a = token.substring(0, star);
            String n = token.substring(star + 1);
            Integer rank = single(a);
            if (rank == null) { errors.add("无法识别的牌：" + a); return; }
            int count;
            try {
                count = Integer.parseInt(n);
            } catch (NumberFormatException e) {
                errors.add("无法识别的张数：" + n);
                return;
            }
            int max = (rank == 20 || rank == 30) ? 1 : 4;
            if (count < 1 || count > max) {
                errors.add(Types.label(rank) + " 的张数应在 1-" + max + " 之间，收到 " + count);
                return;
            }
            for (int i = 0; i < count; i++) out.add(rank);
            return;
        }

        // 紧凑串：逐字符
        for (int i = 0; i < token.length(); ) {
            char ch = token.charAt(i);
            if (ch == '1') {
                // 与 PC 版一致：单独的 1 也当作 10
                out.add(10);
                i += (i + 1 < token.length() && token.charAt(i + 1) == '0') ? 2 : 1;
                continue;
            }
            Integer v = charToRank(ch);
            if (v == null) {
                errors.add("无法识别的牌：" + ch);
                i++;
                continue;
            }
            out.add(v);
            i++;
        }
    }

    /** 单个牌面（可能多字符，如 `10`）。 */
    private static Integer single(String s) {
        if (s.isEmpty()) return null;
        if (s.equals("10")) return 10;
        if (s.length() == 1) return charToRank(s.charAt(0));
        return null;
    }

    private static Integer charToRank(char ch) {
        if (ch >= '3' && ch <= '9') return ch - '0';
        switch (ch) {
            case 'T': return 10;
            case 'J': return 11;
            case 'Q': return 12;
            case 'K': return 13;
            case 'A': return 14;
            case '2': return 17;
            case 'X': return 20;
            case 'D': return 30;
            default: return null;
        }
    }

    private static boolean isValidRank(int r) {
        return (r >= 3 && r <= 14) || r == 17 || r == 20 || r == 30;
    }

    /**
     * 严格解析（与 {@link #parse} 同源，但校验总张数）。
     * 供开局校验使用。
     */
    public static Parsed parseExpecting(String text, int expected) {
        Parsed p = parse(text);
        if (p.ok() && p.size() != expected) {
            p.errors.add("需要正好 " + expected + " 张，当前 " + p.size() + " 张");
        }
        return p;
    }

    /** 反向：把牌数组渲染成紧凑串（用于预设展示）。 */
    public static String toCompact(int[] cards) {
        int[] sorted = cards.clone();
        Arrays.sort(sorted);
        StringBuilder sb = new StringBuilder();
        for (int c : sorted) sb.append(Types.label(c));
        return sb.toString();
    }

    /**
     * 从「完整牌堆」中减去已知牌，得到剩余牌（用于「我记不清手牌」时反推）。
     * 牌堆按标准 54 张构建。
     */
    public static int[] standardDeck() {
        List<Integer> deck = new ArrayList<>();
        for (int r = 3; r <= 14; r++) for (int i = 0; i < 4; i++) deck.add(r);
        for (int i = 0; i < 4; i++) deck.add(17);
        deck.add(20);
        deck.add(30);
        int[] o = new int[deck.size()];
        for (int i = 0; i < o.length; i++) o[i] = deck.get(i);
        return o;
    }

    /** 从牌堆中扣除 used，返回剩余。 */
    public static int[] subtract(int[] deck, int[] used) {
        List<Integer> pool = new ArrayList<>();
        for (int c : deck) pool.add(c);
        for (int c : used) pool.remove((Integer) c);
        int[] o = new int[pool.size()];
        for (int i = 0; i < o.length; i++) o[i] = pool.get(i);
        return o;
    }
}
