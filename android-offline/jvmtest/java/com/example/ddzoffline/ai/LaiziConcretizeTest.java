package com.example.ddzoffline.ai;

import com.example.ddzoffline.DdzEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 赖子「具体化」正确性测试 —— 回答「DouZero 能不能判断赖子怎么出」。
 *
 * 原理背景：DouZero 未训练过赖子。当前做法是把赖子**具体化**成某张真实牌
 * （concrete），再喂给经典模型打分。因此具体化必须满足：
 *
 *   1. 无提示时，必须产出**合法牌型**
 *      —— 不能把「一张 9 + 一张赖子」具体化成「3 和 9」（那不是对子）
 *   2. 有提示时（来自推荐的选定方案），必须**精确复现**该方案
 *      —— 否则推荐评估的牌面和实际打出的牌面不一致
 *   3. 动作空间里每个含赖子的动作，都能被无损往返
 */
public final class LaiziConcretizeTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path assets = root.resolve("android-offline/app/src/main/assets");
        WeightSource src = name -> Files.readAllBytes(assets.resolve(name));

        GameRules rules = GameRules.laizi(7);

        System.out.println("=".repeat(74));
        System.out.println("赖子具体化正确性测试");
        System.out.println("=".repeat(74));

        section("1) 无提示时必须产出合法牌型");
        checkValid("单张赖子", new int[]{7}, rules);
        checkValid("对子（9 + 赖子）", new int[]{7, 9}, rules);
        checkValid("对子（赖子 + 9）", new int[]{9, 7}, rules);
        checkValid("三张（5 5 + 赖子）", new int[]{5, 5, 7}, rules);
        checkValid("炸弹（3 3 3 + 赖子）", new int[]{3, 3, 3, 7}, rules);
        checkValid("顺子（3 4 5 6 + 赖子）", new int[]{3, 4, 5, 6, 7}, rules);
        checkValid("两张赖子（7 7）", new int[]{7, 7}, rules);
        checkValid("三张赖子（7 7 7）", new int[]{7, 7, 7}, rules);
        checkValid("四张赖子（7 7 7 7）", new int[]{7, 7, 7, 7}, rules);
        checkValid("连对（3 3 4 4 + 赖子）", new int[]{3, 3, 4, 4, 7}, rules);
        // 赖子当自己用：单张赖子就是一张合法单牌
        checkValid("单张赖子当自己", new int[]{7}, rules);

        // 无合法牌型的情形：赖子 + 大王凑不出对子（赖子默认不当王）
        int[] badPair = LaiziMoves.concretize(new int[]{7, 30}, rules, null);
        check("赖子+大王无合法对子（保持原样不伪造）",
                MoveDetector.typeOf(MoveDetector.getMoveType(badPair)) == Types.TYPE_15_WRONG,
                Arrays.toString(badPair));

        section("2) 具体化结果必须自洽（点数守恒）");
        // 具体化只是「换掉赖子」，真实牌必须原样保留
        int[] phys = {7, 9, 3};
        int[] c = LaiziMoves.concretize(phys, rules, null);
        check("真实牌 9 与 3 被保留",
                contains(c, 9) && contains(c, 3), Arrays.toString(c));
        check("张数不变", c.length == phys.length, c.length + " vs " + phys.length);
        // 注意：结果**允许**含赖子点数 —— 赖子牌可以当自己用，
        // 这恰好是修掉的一个 bug（曾错误排除赖子点数导致合法牌型被拒）
        check("赖子张数被如实替换（1 张赖子 → 1 张替代牌）",
                countOf(c, rules.getLaiziRank()) <= countOf(phys, rules.getLaiziRank()),
                Arrays.toString(c));

        section("3) 有提示时必须精确复现");
        checkHint("对 9", new int[]{7, 9}, new int[]{9, 9}, rules);
        checkHint("对 A", new int[]{7, 14}, new int[]{14, 14}, rules);
        checkHint("对 2", new int[]{7, 17}, new int[]{17, 17}, rules);
        checkHint("炸弹 3", new int[]{3, 3, 3, 7}, new int[]{3, 3, 3, 3}, rules);
        checkHint("单张 A", new int[]{7}, new int[]{14}, rules);

        section("4) 往返：动作空间内每个含赖子动作都可无损复现");
        int[] hand = {7, 9, 3, 3, 4, 5, 6, 8, 10, 11, 12, 13, 14, 17, 20, 30, 4};
        LaiziMoves lm = new LaiziMoves(hand, rules);
        int total = 0, bad = 0, laiziMoves = 0;
        for (LaiziMoves.Move m : lm.generateAll()) {
            total++;
            if (m.laiziUsed == 0) continue;
            laiziMoves++;
            int[] back = LaiziMoves.concretize(m.physical, rules, m.concrete);
            if (!Arrays.equals(back, m.concrete)) {
                bad++;
                if (bad <= 3) {
                    System.out.println("      往返失败: phys=" + Arrays.toString(m.physical)
                            + " conc=" + Arrays.toString(m.concrete)
                            + " 回=" + Arrays.toString(back));
                }
            }
        }
        check("动作总数 " + total + "，含赖子 " + laiziMoves + " 个，全部可复现",
                bad == 0, "失败 " + bad + " 个");

        section("5) 引擎层：采用推荐后存储的 concrete 应与推荐一致");
        DdzEngine e = new DdzEngine(src);
        e.enableLaizi(7);
        e.start("landlord", "9 7 3 4 5 6 8 10 J Q K A 2 X D 4 5", "2 2 2");
        DdzEngine.Recommendation r = e.recommend();
        DdzEngine.Candidate pick = null;
        for (DdzEngine.Candidate x : r.candidates) {
            if (x.laiziUsed > 0) { pick = x; break; }
        }
        check("候选中存在用赖子的方案", pick != null,
                pick == null ? "候选=" + describe(r.candidates) : "");
        if (pick != null) {
            System.out.println("      选中候选: " + pick.display
                    + "  physical=" + Arrays.toString(pick.cards)
                    + "  concrete=" + Arrays.toString(pick.concrete));
            e.record("landlord", Types.display(pick.cards), pick.concrete);
            int[] stored = e.getLastMoveConcrete();
            check("存储的 concrete 与推荐一致",
                    Arrays.equals(stored, pick.concrete),
                    "推荐=" + Arrays.toString(pick.concrete)
                            + " 存储=" + Arrays.toString(stored));
        }

        section("6) 显式提示被 record 采用");
        DdzEngine e2 = new DdzEngine(src);
        e2.enableLaizi(7);
        e2.start("landlord", "9 7 3 4 5 6 8 10 J Q K A 2 X D 4 5", "2 2 2");
        e2.record("landlord", "7 9", new int[]{9, 9});
        check("显式提示原样存储",
                Arrays.equals(e2.getLastMoveConcrete(), new int[]{9, 9}),
                Arrays.toString(e2.getLastMoveConcrete()));

        System.out.println();
        System.out.println("=".repeat(74));
        System.out.printf("结果: %d 通过, %d 失败%n", pass, fail);
        System.out.println("=".repeat(74));
        if (fail > 0) System.exit(1);
    }

    // ---------------- 辅助 ----------------

    static void checkValid(String label, int[] physical, GameRules rules) {
        int[] c = LaiziMoves.concretize(physical, rules, null);
        int t = MoveDetector.typeOf(MoveDetector.getMoveType(c));
        check(label + " " + Arrays.toString(physical) + " → " + Arrays.toString(c),
                t != Types.TYPE_15_WRONG, "产出非法牌型");
    }

    static void checkHint(String label, int[] physical, int[] hint, GameRules rules) {
        int[] c = LaiziMoves.concretize(physical, rules, hint);
        check(label + " 复现 " + Arrays.toString(hint),
                Arrays.equals(c, hint),
                "得到 " + Arrays.toString(c));
    }

    static boolean contains(int[] arr, int v) {
        for (int x : arr) if (x == v) return true;
        return false;
    }

    static int countOf(int[] arr, int v) {
        int n = 0;
        for (int x : arr) if (x == v) n++;
        return n;
    }

    static String describe(List<DdzEngine.Candidate> cs) {
        StringBuilder sb = new StringBuilder();
        for (DdzEngine.Candidate c : cs) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(c.display).append("(赖").append(c.laiziUsed).append(")");
        }
        return sb.toString();
    }

    static void section(String s) {
        System.out.println();
        System.out.println("-- " + s);
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  PASS  " + name); }
        else { fail++; System.out.println("  FAIL  " + name + (detail.isEmpty() ? "" : "  | " + detail)); }
    }
}
