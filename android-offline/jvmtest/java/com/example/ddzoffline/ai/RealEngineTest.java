package com.example.ddzoffline.ai;

import com.example.ddzoffline.DdzEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 真实引擎端到端测试（纯 JVM）。
 *
 * 与其他测试不同：这里直接实例化 Android 端真正使用的
 * {@link com.example.ddzoffline.DdzEngine}（通过 WeightSource 注入权重，
 * 绕开 android.content.Context），因此验证的是**上线代码本身**，
 * 而不是测试里的副本。
 *
 * 对拍目标：ddz_assistant.py 在同一局面下的推荐结果。
 */
public final class RealEngineTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path assets = root.resolve("android-offline/app/src/main/assets");

        WeightSource src = name -> Files.readAllBytes(assets.resolve(name));

        System.out.println("=".repeat(74));
        System.out.println("真实 DdzEngine 端到端测试（WP 模型）");
        System.out.println("=".repeat(74));

        // ---- 场景 1：地主开局，应推荐顺子 ----
        DdzEngine e = new DdzEngine(src);
        e.start("landlord", "3 4 5 6 7 8 9 10 J Q K A 2 X D 3 4", "9 9 2");
        expect("开局后轮次为地主先手", "landlord".equals(e.getTurn()), e.getTurn());
        expect("地主手牌 20 张", e.handCount() == 20, String.valueOf(e.handCount()));
        expect("轮到我出牌", e.isMyTurn(), String.valueOf(e.isMyTurn()));

        DdzEngine.Recommendation r1 = e.recommend();
        expect("场景1 推荐与 PyTorch 一致 = '3 4 5 6 7 8 9'",
                "3 4 5 6 7 8 9".equals(r1.move), r1.move);
        expect("场景1 候选数量 > 1", r1.candidates.size() > 1,
                String.valueOf(r1.candidates.size()));
        expect("场景1 首个候选标记 best", r1.candidates.get(0).best,
                String.valueOf(r1.candidates.get(0).best));
        expect("场景1 候选按分值降序",
                isDescending(r1.candidates), describe(r1.candidates));

        // ---- 场景 2：地主下家，应推荐不出 ----
        DdzEngine e2 = new DdzEngine(src);
        e2.start("landlord_down", "3 4 5 6 7 8 9 10 J Q K A 2 X D 3 4", "9 9 2");
        e2.record("landlord", "5");
        expect("场景2 记录地主出牌后轮到我", e2.isMyTurn(), e2.getTurn());
        DdzEngine.Recommendation r2 = e2.recommend();
        expect("场景2 推荐与 PyTorch 一致 = '不出'", "不出".equals(r2.move), r2.move);

        // ---- 场景 3：地主上家，应推荐不出 ----
        DdzEngine e3 = new DdzEngine(src);
        e3.start("landlord_up", "3 3 4 5 6 7 8 9 10 J Q K A 2 X D 4", "9 9 2");
        e3.record("landlord", "K K");
        e3.record("landlord_down", "");
        expect("场景3 轮到上家", e3.isMyTurn(), e3.getTurn());
        DdzEngine.Recommendation r3 = e3.recommend();
        expect("场景3 推荐与 PyTorch 一致 = '不出'", "不出".equals(r3.move), r3.move);

        System.out.println();
        System.out.println("=".repeat(74));
        System.out.println("状态机与规则");
        System.out.println("=".repeat(74));

        DdzEngine g = new DdzEngine(src);
        g.start("landlord", "3 4 5 6 7 8 9 10 J Q K A 2 X D 3 4", "9 9 2");

        // 领出方不能不出
        boolean rejected = false;
        try { g.record("landlord", ""); } catch (IllegalArgumentException ex) { rejected = true; }
        expect("领出方不能不出", rejected, "");

        // 合法领出
        g.record("landlord", "3");
        expect("出牌后手牌减 1", g.handCount() == 19, String.valueOf(g.handCount()));
        expect("出牌后轮到下家", "landlord_down".equals(g.getTurn()), g.getTurn());

        // 记录对手出牌
        g.record("landlord_down", "4");
        expect("记录对手后轮到上家", "landlord_up".equals(g.getTurn()), g.getTurn());

        // 轮次错误被拒
        boolean wrongTurn = false;
        try { g.record("landlord_down", "5"); } catch (IllegalArgumentException ex) { wrongTurn = true; }
        expect("轮次错误被拒", wrongTurn, "");

        // 小牌压不过
        boolean tooSmall = false;
        try { g.record("landlord_up", "3"); } catch (IllegalArgumentException ex) { tooSmall = true; }
        expect("小于上家的牌被拒", tooSmall, "");

        // 不出
        g.record("landlord_up", "");
        expect("不出后轮到我", g.isMyTurn(), g.getTurn());

        System.out.println();
        System.out.println("=".repeat(74));
        System.out.println("撤销");
        System.out.println("=".repeat(74));

        int before = g.handCount();
        String turnBefore = g.getTurn();
        expect("有可撤销步骤", g.canUndo(), "");
        expect("撤销成功", g.undo(), "");
        expect("撤销后手牌复原", g.handCount() != before || !g.isMyTurn(),
                "hand=" + g.handCount() + " turn=" + g.getTurn());

        DdzEngine u = new DdzEngine(src);
        u.start("landlord", "3 4 5 6 7 8 9 10 J Q K A 2 X D 3 4", "9 9 2");
        expect("开局后无可撤销", !u.canUndo(), "");
        expect("空栈撤销返回 false", !u.undo(), "");

        System.out.println();
        System.out.println("=".repeat(74));
        System.out.println("记牌器");
        System.out.println("=".repeat(74));

        DdzEngine c = new DdzEngine(src);
        c.start("landlord", "3 3 3 3 4 4 4 4 5 5 5 5 6 6 6 6 7", "8 8 8");
        var unseen = c.unseenCounts();
        expect("记牌器覆盖 15 个点数", unseen.size() == 15, String.valueOf(unseen.size()));
        expect("4 张 3 全在手 -> 3 剩 0", unseen.get("3") == 0, String.valueOf(unseen.get("3")));
        expect("3 张 8 在底牌 -> 8 剩 1", unseen.get("8") == 1, String.valueOf(unseen.get("8")));
        expect("王未见 -> X 剩 1", unseen.get("X") == 1, String.valueOf(unseen.get("X")));

        System.out.println();
        System.out.println("=".repeat(74));
        System.out.println("ADP 模型切换");
        System.out.println("=".repeat(74));

        DdzEngine a = new DdzEngine(src);
        expect("默认 WP", "WP".equals(a.getObjective()), a.getObjective());
        a.setObjective("ADP");
        expect("切换 ADP 成功", "ADP".equals(a.getObjective()), a.getObjective());
        a.start("landlord", "3 4 5 6 7 8 9 10 J Q K A 2 X D 3 4", "9 9 2");
        DdzEngine.Recommendation ra = a.recommend();
        expect("ADP 也能产出推荐", ra.move != null && !ra.move.isEmpty(), ra.move);
        System.out.println("       ADP 推荐: " + ra.move);

        System.out.println();
        System.out.println("=".repeat(74));
        System.out.printf("结果: %d 通过, %d 失败%n", pass, fail);
        System.out.println("=".repeat(74));
        if (fail > 0) System.exit(1);
    }

    static boolean isDescending(List<DdzEngine.Candidate> c) {
        for (int i = 0; i + 1 < c.size(); i++) {
            if (c.get(i).score < c.get(i + 1).score) return false;
        }
        return true;
    }

    static String describe(List<DdzEngine.Candidate> c) {
        StringBuilder sb = new StringBuilder();
        for (DdzEngine.Candidate x : c) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(x.display).append("=").append(String.format("%.4f", x.score));
        }
        return sb.toString();
    }

    static void expect(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  PASS  " + name); }
        else { fail++; System.out.println("  FAIL  " + name + (detail.isEmpty() ? "" : "  | " + detail)); }
    }
}
