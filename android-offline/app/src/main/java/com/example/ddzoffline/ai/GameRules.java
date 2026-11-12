package com.example.ddzoffline.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 玩法规则配置。
 *
 * 设计要点：**经典模式必须与移植前逐位一致**，因此所有赖子相关的分支
 * 都收敛在这个类里，由 {@link #isLaizi} 单点判断；经典模式下
 * {@link #isLaizi(int)} 恒为 false，调用方行为不变。
 *
 * 赖子规则口径（默认按湖北赖子斗地主，见 docs/prompt-laizi-and-input.md）：
 *   - 翻一张牌定赖子点数，该点数的所有牌都是万能牌
 *   - 赖子可代替除大小王外的任意牌（{@link #laiziCanBeJoker} 可放开）
 *   - 赖子可单独出，视为任意单张（{@link #laiziCanPlayAlone}）
 *   - 软炸（含赖子凑成）小于硬炸（天然四张），硬炸小于王炸
 */
public final class GameRules {

    public enum Mode { CLASSIC, LAIZI }

    /** 经典模式（默认，行为与移植前完全一致）。 */
    public static final GameRules CLASSIC = new GameRules(Mode.CLASSIC, -1);

    private final Mode mode;
    private final int laiziRank;

    /** 赖子能否当大小王用。默认 false（多数地方规则不允许）。 */
    public boolean laiziCanBeJoker = false;

    /** 赖子能否单独出。默认 true。 */
    public boolean laiziCanPlayAlone = true;

    /** 软炸（含赖子）是否小于硬炸（天然四张）。默认 true。 */
    public boolean softBombWeakerThanHard = true;

    private GameRules(Mode mode, int laiziRank) {
        this.mode = mode;
        this.laiziRank = laiziRank;
    }

    public static GameRules laizi(int laiziRank) {
        return new GameRules(Mode.LAIZI, laiziRank);
    }

    public Mode getMode() { return mode; }
    public int getLaiziRank() { return laiziRank; }
    public boolean isLaiziMode() { return mode == Mode.LAIZI; }

    /** 该牌值是否为赖子。经典模式恒为 false。 */
    public boolean isLaizi(int card) {
        return mode == Mode.LAIZI && card == laiziRank;
    }

    /** 可被赖子替代的候选牌值。 */
    public int[] substitutionCandidates() {
        // 3..14 与 17（2）；大小王按开关决定
        // 注意：必须排除赖子点数本身 —— 把赖子「替代成自己」没有意义，
        // 而且会让展开结果里仍残留赖子点数，破坏「展开后即可直接喂模型」的前提。
        List<Integer> out = new ArrayList<>();
        for (int v = 3; v <= 14; v++) if (v != laiziRank) out.add(v);
        if (17 != laiziRank) out.add(17);
        if (laiziCanBeJoker) {
            if (20 != laiziRank) out.add(20);
            if (30 != laiziRank) out.add(30);
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /** 从一组牌里挑出赖子与普通牌。 */
    public static int[] splitLaizi(int[] cards, GameRules rules, boolean wantLaizi) {
        List<Integer> out = new ArrayList<>();
        for (int c : cards) {
            if (rules.isLaizi(c) == wantLaizi) out.add(c);
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /**
     * 把一手含赖子的牌展开为若干「具体牌」组合。
     *
     * 策略：对每个赖子枚举替代牌值；候选值先按“更可能有用”排序，
     * 并在组合数超限时截断（保证 UI 不卡死）。
     *
     * @param cards 含赖子的牌
     * @param rules 规则
     * @param maxCombos 组合数上限
     * @return 展开后的具体牌组合列表（每个都是排序好的牌值数组）
     */
    public static List<int[]> expand(int[] cards, GameRules rules, int maxCombos) {
        List<int[]> out = new ArrayList<>();
        if (!rules.isLaiziMode()) {
            out.add(cards.clone());
            return out;
        }
        int wild = 0;
        for (int c : cards) if (rules.isLaizi(c)) wild++;

        if (wild == 0) {
            out.add(cards.clone());
            return out;
        }
        if (!rules.laiziCanPlayAlone && wild == cards.length) {
            return out;   // 全是赖子且不允许单独出
        }

        int[] plain = splitLaizi(cards, rules, false);
        int[] cands = rules.orderedCandidates(cards);

        // 组合数上限保护：13^k 增长很快，超限时只取最有希望的候选
        int limit = cands.length;
        long total = 1;
        for (int i = 0; i < wild; i++) {
            total *= limit;
            if (total > maxCombos) break;
        }
        if (total > maxCombos) {
            // 收缩候选到“手上已有的点数 + 上家点数”这类高价值集合
            int keep = Math.max(1, (int) Math.floor(Math.pow(maxCombos, 1.0 / wild)));
            if (keep < cands.length) cands = Arrays.copyOf(cands, keep);
        }

        int[] idx = new int[wild];
        while (true) {
            int[] concrete = new int[plain.length + wild];
            System.arraycopy(plain, 0, concrete, 0, plain.length);
            for (int i = 0; i < wild; i++) concrete[plain.length + i] = cands[idx[i]];
            Arrays.sort(concrete);

            // 合法性守卫：替代后某点数不得超过该点数的实际张数
            // （普通牌 4 张、大小王各 1 张）。否则会产出物理上不存在的组合，
            // 且 _cards2array 的 NumOnes2Array 只覆盖 0..4，会直接越界。
            if (withinRankLimits(concrete)) out.add(concrete);

            int p = wild - 1;
            while (p >= 0) {
                idx[p]++;
                if (idx[p] < cands.length) break;
                idx[p] = 0;
                p--;
            }
            if (p < 0) break;
            if (out.size() >= maxCombos) break;
        }
        return out;
    }

    /** 校验某点数不超过实际张数上限。 */
    private static boolean withinRankLimits(int[] concrete) {
        int[] count = new int[31];
        for (int c : concrete) count[c]++;
        for (int c : concrete) {
            int max = (c == 20 || c == 30) ? 1 : 4;
            if (count[c] > max) return false;
        }
        return true;
    }

    /** 候选牌值排序：优先与现有手牌同点数，其次按点数从大到小。 */
    private int[] orderedCandidates(int[] cards) {
        Set<Integer> present = new LinkedHashSet<>();
        for (int c : cards) if (!isLaizi(c)) present.add(c);

        List<Integer> ordered = new ArrayList<>();
        for (int v : substitutionCandidates()) {
            if (present.contains(v)) ordered.add(v);
        }
        List<Integer> rest = new ArrayList<>();
        for (int v : substitutionCandidates()) {
            if (!present.contains(v)) rest.add(v);
        }
        // 点数大的优先（更容易压过对手）
        rest.sort((a, b) -> Integer.compare(b, a));
        ordered.addAll(rest);

        int[] arr = new int[ordered.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = ordered.get(i);
        return arr;
    }
}
