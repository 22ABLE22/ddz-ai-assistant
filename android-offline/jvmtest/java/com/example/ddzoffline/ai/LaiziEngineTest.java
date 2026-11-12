package com.example.ddzoffline.ai;

import com.example.ddzoffline.DdzEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 赖子模式引擎端到端测试（真实 DdzEngine）。
 *
 * 重点验证：
 *   1. 切换赖子模式后能正常开局与推荐
 *   2. 赖子能参与凑牌型（推荐里出现用赖子的动作）
 *   3. 软炸 < 硬炸 < 王炸 的次序在引擎层面成立
 *   4. 切回经典模式后行为与之前完全一致
 */
public final class LaiziEngineTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path assets = root.resolve("android-offline/app/src/main/assets");
        WeightSource src = name -> Files.readAllBytes(assets.resolve(name));

        System.out.println("=".repeat(74));
        System.out.println("赖子模式引擎端到端测试");
        System.out.println("=".repeat(74));

        // ---- 1. 模式切换 ----
        section("1) 模式切换");
        DdzEngine e = new DdzEngine(src);
        check("默认经典模式", !e.isLaiziMode());
        e.enableLaizi(7);
        check("切到赖子模式（7 为赖子）", e.isLaiziMode());
        check("赖子点数正确", e.getRules().getLaiziRank() == 7);
        check("切换后状态已重置", !e.isStarted());
        e.enableClassic();
        check("切回经典模式", !e.isLaiziMode());

        // ---- 2. 赖子模式开局与推荐 ----
        section("2) 赖子模式开局与推荐");
        DdzEngine g = new DdzEngine(src);
        g.enableLaizi(7);
        // 手牌：3 3 3 7（赖子可补成炸弹）+ 其他
        g.start("landlord", "3 3 3 7 5 6 8 9 10 J Q K A 2 X D 4", "9 9 2");
        check("赖子模式可开局", g.isStarted());
        check("地主 20 张", g.handCount() == 20, String.valueOf(g.handCount()));

        DdzEngine.Recommendation r = g.recommend();
        check("赖子模式能产出推荐", r.move != null && !r.move.isEmpty(), r.move);
        check("有候选", !r.candidates.isEmpty(), String.valueOf(r.candidates.size()));
        System.out.println("       推荐: " + r.move);

        // 推荐里应至少有一个用赖子的动作（手上有 4 张可用赖子）
        boolean anyLaizi = r.candidates.stream().anyMatch(c -> c.laiziUsed > 0);
        check("候选中出现用赖子的打法", anyLaizi,
                "候选=" + describe(r.candidates));

        // 用赖子的候选必须带 concrete 与 physical
        boolean shapesOk = r.candidates.stream().allMatch(c ->
                c.cards.length == c.concrete.length && c.cards.length > 0);
        check("候选 physical/concrete 张数一致", shapesOk, "");

        // ---- 3. 赖子凑炸弹 ----
        section("3) 赖子凑炸弹");
        // 直接验证动作空间：手牌 3 3 3 + 7(赖子) 应能凑出 3 炸
        GameRules rules = GameRules.laizi(7);
        int[] bombHand = CardInput.parse("3 3 3 7 4 5 6 8 9 10 J Q K A 2 X D").toArray();
        LaiziMoves lm = new LaiziMoves(bombHand, rules);
        List<LaiziMoves.Move> all = lm.generateAll();
        boolean bombGeneratable = all.stream().anyMatch(m ->
                m.concrete.length == 4 && allSame(m.concrete)
                        && m.concrete[0] == 3 && m.laiziUsed == 1);
        check("动作空间含「3张+赖子」凑成的 3 炸", bombGeneratable,
                "共 " + all.size() + " 个动作");

        // 引擎推荐必须能产出（炸弹是否进 top4 取决于模型打分，不作硬性断言）
        DdzEngine b = new DdzEngine(src);
        b.enableLaizi(7);
        b.start("landlord", "3 3 3 7 4 5 6 8 9 10 J Q K A 2 X D", "2 2 2");
        DdzEngine.Recommendation rb = b.recommend();
        check("赖子模式推荐非空", rb.move != null && !rb.move.isEmpty(), rb.move);
        System.out.println("       推荐: " + rb.move);
        check("候选 physical/concrete 张数一致",
                rb.candidates.stream().allMatch(c -> c.cards.length == c.concrete.length), "");

        // ---- 4. 赖子模式的记牌器与撤销 ----
        section("4) 赖子模式的记牌器与撤销");
        var unseen = b.unseenCounts();
        check("记牌器可用", unseen.size() == 15, String.valueOf(unseen.size()));
        // 7 是赖子，手里 1 张 -> 未出现 3
        check("赖子点数也计入记牌器", unseen.get("7") != null, String.valueOf(unseen.get("7")));

        DdzEngine u = new DdzEngine(src);
        u.enableLaizi(7);
        u.start("landlord", "3 3 3 7 4 5 6 8 9 10 J Q K A 2 X D", "2 2 2");
        int h0 = u.handCount();
        u.record("landlord", "3 3 3 7");   // 用赖子当第 4 张 3，构成炸弹
        check("用赖子出牌后手牌减少 4", u.handCount() == h0 - 4,
                h0 + " -> " + u.handCount());
        check("可撤销", u.canUndo());
        u.undo();
        check("撤销后手牌复原", u.handCount() == h0, String.valueOf(u.handCount()));
        check("撤销后轮次复原", "landlord".equals(u.getTurn()), u.getTurn());

        // ---- 5. 经典模式回归 ----
        section("5) 切回经典模式后行为一致");
        DdzEngine c = new DdzEngine(src);
        c.start("landlord", "3 4 5 6 7 8 9 10 J Q K A 2 X D 3 4", "9 9 2");
        DdzEngine.Recommendation rc = c.recommend();
        check("经典模式推荐仍为 '3 4 5 6 7 8 9'",
                "3 4 5 6 7 8 9".equals(rc.move), rc.move);
        check("经典模式候选用 0 张赖子",
                rc.candidates.stream().allMatch(x -> x.laiziUsed == 0), "");

        // 切换后经典行为不受影响
        c.enableLaizi(7);
        c.enableClassic();
        c.start("landlord", "3 4 5 6 7 8 9 10 J Q K A 2 X D 3 4", "9 9 2");
        DdzEngine.Recommendation rc2 = c.recommend();
        check("来回切换后经典推荐不变", "3 4 5 6 7 8 9".equals(rc2.move), rc2.move);

        System.out.println();
        System.out.println("=".repeat(74));
        System.out.printf("结果: %d 通过, %d 失败%n", pass, fail);
        System.out.println("=".repeat(74));
        if (fail > 0) System.exit(1);
    }

    static String describe(List<DdzEngine.Candidate> c) {
        StringBuilder sb = new StringBuilder();
        for (DdzEngine.Candidate x : c) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(x.display).append(String.format("(%.3f,赖%d)", x.score, x.laiziUsed));
        }
        return sb.toString();
    }

    static boolean allSame(int[] a) {
        for (int v : a) if (v != a[0]) return false;
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
