package com.example.ddzoffline.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 赖子规则测试（纯 JVM，不依赖 Android）。
 *
 * 覆盖：
 *   1. 经典模式不受影响（GameRules.CLASSIC 下 isLaizi 恒 false）
 *   2. 赖子识别与展开
 *   3. 各牌型在赖子参与下能正确物化
 *   4. 压牌判定（含软炸 < 硬炸 < 王炸）
 *   5. 赖子不足时不应生成非法动作
 */
public final class LaiziTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        System.out.println("=".repeat(74));
        System.out.println("赖子规则测试");
        System.out.println("=".repeat(74));

        classicUnchanged();
        laiziBasics();
        materialization();
        bombOrdering();
        noOverGeneration();

        System.out.println();
        System.out.println("=".repeat(74));
        System.out.printf("结果: %d 通过, %d 失败%n", pass, fail);
        System.out.println("=".repeat(74));
        if (fail > 0) System.exit(1);
    }

    // ---------------- 1. 经典模式不受影响 ----------------
    static void classicUnchanged() {
        section("1) 经典模式不受影响");
        GameRules c = GameRules.CLASSIC;
        check("CLASSIC.isLaiziMode() == false", !c.isLaiziMode());
        check("CLASSIC 对任意牌都不是赖子",
                !c.isLaizi(3) && !c.isLaizi(14) && !c.isLaizi(20) && !c.isLaizi(30));
        check("CLASSIC.getLaiziRank() == -1", c.getLaiziRank() == -1);

        // 经典模式下 expand 原样返回
        int[] hand = {3, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 17, 20, 30, 4};
        List<int[]> ex = GameRules.expand(hand, c, 1000);
        check("CLASSIC.expand 只返回一个组合", ex.size() == 1, String.valueOf(ex.size()));
        check("CLASSIC.expand 与输入一致", Arrays.equals(ex.get(0), hand));
    }

    // ---------------- 2. 赖子基础 ----------------
    static void laiziBasics() {
        section("2) 赖子识别与展开");
        GameRules r = GameRules.laizi(7);
        check("laizi(7).isLaiziMode() == true", r.isLaiziMode());
        check("7 是赖子", r.isLaizi(7));
        check("3 不是赖子", !r.isLaizi(3));
        check("X 不是赖子", !r.isLaizi(20));

        // 手牌含 2 张 7，展开应产生多组具体牌
        int[] hand = {3, 4, 5, 7, 7, 9};
        List<int[]> ex = GameRules.expand(hand, r, 500);
        check("含赖子时展开出多个组合", ex.size() > 1, String.valueOf(ex.size()));
        check("展开结果张数不变",
                ex.stream().allMatch(a -> a.length == hand.length),
                "样例=" + Arrays.toString(ex.get(0)));
        // 展开后不应再含赖子点数
        boolean noneLaizi = true;
        for (int[] a : ex) for (int v : a) if (r.isLaizi(v)) noneLaizi = false;
        check("展开后不再含赖子点数", noneLaizi);

        // 候选数上限保护
        int[] manyWild = {7, 7, 7, 7};
        List<int[]> big = GameRules.expand(manyWild, r, 200);
        check("组合数受 maxCombos 限制", big.size() <= 200, String.valueOf(big.size()));
        check("4 张赖子也能展开出结果", big.size() > 0, String.valueOf(big.size()));

        // 大小王默认不可被替代
        check("默认赖子不能当王",
                Arrays.stream(r.substitutionCandidates()).noneMatch(v -> v == 20 || v == 30));
    }

    // ---------------- 3. 牌型物化 ----------------
    static void materialization() {
        section("3) 赖子参与下的牌型物化");
        GameRules r = GameRules.laizi(7);

        // 手牌：3 3 7 7 → 应有对 3、对 7、以及对 A（两张赖子当 A）等
        int[] hand = {3, 3, 7, 7};
        LaiziMoves lm = new LaiziMoves(hand, r);
        List<LaiziMoves.Move> all = lm.generateAll();

        check("能生成动作", !all.isEmpty(), String.valueOf(all.size()));

        // 找一个「两张赖子凑成对 A」的动作
        boolean pairA = all.stream().anyMatch(m ->
                m.concrete.length == 2 && m.concrete[0] == 14 && m.concrete[1] == 14 && m.laiziUsed == 2);
        check("两张赖子可凑成对 A", pairA, "");

        // 顺子：3 4 5 6 7(赖) → 可当 3 4 5 6 7 的具体顺子
        int[] straightHand = {3, 4, 5, 6, 7};
        LaiziMoves lm2 = new LaiziMoves(straightHand, r);
        List<LaiziMoves.Move> moves2 = lm2.generateAll();
        boolean hasStraight = moves2.stream().anyMatch(m ->
                m.concrete.length == 5 && isConsecutive(m.concrete));
        check("含赖子可凑成 5 张顺子", hasStraight, "");

        // 炸弹：3 3 3 7 → 赖子补齐成 3 炸
        int[] bombHand = {3, 3, 3, 7};
        LaiziMoves lm3 = new LaiziMoves(bombHand, r);
        List<LaiziMoves.Move> moves3 = lm3.generateAll();
        boolean softBomb = moves3.stream().anyMatch(m ->
                m.concrete.length == 4 && allSame(m.concrete) && m.laiziUsed == 1);
        check("三张+赖子可凑成炸弹", softBomb, "");

        // 赖子不足时不应生成
        int[] shortHand = {3, 3, 7};   // 只有 1 张赖子，凑不出 3 炸
        LaiziMoves lm4 = new LaiziMoves(shortHand, r);
        List<LaiziMoves.Move> moves4 = lm4.generateAll();
        boolean illegal = moves4.stream().anyMatch(m ->
                m.concrete.length == 4 && allSame(m.concrete) && m.concrete[0] == 3);
        check("赖子不足时不生成非法炸弹", !illegal, "");

        // physical 张数必须等于 concrete 张数
        boolean sizeOk = all.stream().allMatch(m -> m.physical.length == m.concrete.length);
        check("physical 与 concrete 张数一致", sizeOk, "");
    }

    // ---------------- 4. 炸弹次序 ----------------
    static void bombOrdering() {
        section("4) 压牌与炸弹次序");
        GameRules r = GameRules.laizi(7);

        // 手牌：3 3 3 7（软炸）+ 8 8 8 8（硬炸）+ X D（王炸）
        int[] hand = {3, 3, 3, 7, 8, 8, 8, 8, 20, 30};
        LaiziMoves lm = new LaiziMoves(hand, r);

        // 找软炸 3（含 1 张赖子）与硬炸 8
        LaiziMoves.Move soft3 = null, hard8 = null, kingBomb = null;
        for (LaiziMoves.Move m : lm.generateAll()) {
            if (m.concrete.length == 4 && allSame(m.concrete) && m.concrete[0] == 3 && m.laiziUsed == 1)
                soft3 = m;
            if (m.concrete.length == 4 && allSame(m.concrete) && m.concrete[0] == 8 && m.laiziUsed == 0)
                hard8 = m;
            if (m.concrete.length == 2 && m.concrete[0] == 20 && m.concrete[1] == 30) kingBomb = m;
        }
        check("找到软炸 3", soft3 != null, "");
        check("找到硬炸 8", hard8 != null, "");
        check("找到王炸", kingBomb != null, "");

        if (soft3 != null && hard8 != null) {
            // 软炸不能压硬炸
            check("软炸压不过硬炸", !lm.beats(soft3, hard8));
            // 硬炸能压软炸
            check("硬炸能压软炸", lm.beats(hard8, soft3));
        }
        if (kingBomb != null && hard8 != null) {
            check("王炸压硬炸", lm.beats(kingBomb, hard8));
            check("硬炸压不过王炸", !lm.beats(hard8, kingBomb));
        }

        // 同型比大小：软炸 3 vs 软炸 5
        int[] hand2 = {3, 3, 3, 7, 5, 5, 5, 7};
        LaiziMoves lm2 = new LaiziMoves(hand2, r);
        LaiziMoves.Move s3 = null, s5 = null;
        for (LaiziMoves.Move m : lm2.generateAll()) {
            if (m.concrete.length == 4 && allSame(m.concrete) && m.laiziUsed == 1) {
                if (m.concrete[0] == 3) s3 = m;
                if (m.concrete[0] == 5) s5 = m;
            }
        }
        check("找到软炸 3 与软炸 5", s3 != null && s5 != null,
                "s3=" + (s3 != null) + " s5=" + (s5 != null));
        if (s3 != null && s5 != null) {
            check("软炸 5 能压软炸 3", lm2.beats(s5, s3));
            check("软炸 3 压不过软炸 5", !lm2.beats(s3, s5));
        }

        // 自由领出：任何合法动作都可出
        check("自由领出时单张可出", lm.beats(lm.generateAll().get(0), null));
    }

    // ---------------- 5. 不过度生成 ----------------
    static void noOverGeneration() {
        section("5) 生成结果全部合法");
        GameRules r = GameRules.laizi(7);
        int[] hand = {3, 4, 5, 7, 9, 9, 20};
        LaiziMoves lm = new LaiziMoves(hand, r);
        List<LaiziMoves.Move> all = lm.generateAll();

        // 每个动作的 concrete 必须是合法牌型
        int bad = 0;
        for (LaiziMoves.Move m : all) {
            int t = MoveDetector.typeOf(MoveDetector.getMoveType(m.concrete));
            if (t == Types.TYPE_15_WRONG) bad++;
        }
        check("生成的动作全部是合法牌型", bad == 0, "非法 " + bad + "/" + all.size());

        // 每个动作实际用牌不得超过手牌
        int over = 0;
        for (LaiziMoves.Move m : all) {
            if (!withinHand(hand, m.physical, r)) over++;
        }
        check("所有动作不超出手牌", over == 0, "超出 " + over + "/" + all.size());

        // 赖子使用数不得超过手牌里的赖子数
        long wild = Arrays.stream(hand).filter(v -> r.isLaizi(v)).count();
        boolean ok = all.stream().allMatch(m -> m.laiziUsed <= wild);
        check("赖子使用数不超过持有量", ok, "持有 " + wild + " 张");
    }

    /** 判断 physical 用牌是否都在 hand 之内（考虑赖子点数）。 */
    static boolean withinHand(int[] hand, int[] physical, GameRules r) {
        List<Integer> pool = new ArrayList<>();
        for (int c : hand) pool.add(c);
        for (int c : physical) if (!pool.remove((Integer) c)) return false;
        return true;
    }

    static boolean allSame(int[] a) {
        for (int v : a) if (v != a[0]) return false;
        return true;
    }

    static boolean isConsecutive(int[] a) {
        int[] b = a.clone();
        Arrays.sort(b);
        for (int i = 0; i + 1 < b.length; i++) if (b[i + 1] - b[i] != 1) return false;
        return true;
    }

    static void section(String s) {
        System.out.println();
        System.out.println("-- " + s);
    }

    static void check(String name, boolean ok) {
        check(name, ok, "");
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  PASS  " + name); }
        else { fail++; System.out.println("  FAIL  " + name + (detail.isEmpty() ? "" : "  | " + detail)); }
    }
}
