package com.example.ddzoffline.ai;

import com.example.ddzoffline.DdzEngine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 蒙特卡洛搜索移植验证。
 *
 * 验证点：
 *   A) 残局搜索能跑通，胜率落在 [0,1]，候选全部被评估
 *   B) 同 seed 结果可复现（搜索是确定性的）
 *   C) 不同候选的胜率有区分度（搜索在挑，不是瞎给）
 *   D) 赖子场景：搜索能评估「该不该现在用掉赖子」
 *   E) 性能：报告每次搜索耗时，确认手机端可用
 */
public final class McSearchTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path assets = root.resolve("android-offline/app/src/main/assets");

        System.out.println("=".repeat(76));
        System.out.println("蒙特卡洛搜索（移植自 ddz_search.py）—— 验证");
        System.out.println("=".repeat(76));

        Map<String, DdzModel> models = loadModels(assets, "WP");

        // ---------- A：残局搜索跑通 ----------
        section("A) 残局搜索：4 张 vs 3 张 vs 3 张");
        MonteCarloSearch.Snapshot snap = endgameSnapshot();
        List<DdzEngine.Candidate> cands = new ArrayList<>();
        cands.add(new DdzEngine.Candidate("A", 0f, new int[]{14}, true));
        cands.add(new DdzEngine.Candidate("2", 0f, new int[]{17}, false));
        cands.add(new DdzEngine.Candidate("小王", 0f, new int[]{20}, false));
        cands.add(new DdzEngine.Candidate("大王", 0f, new int[]{30}, false));
        cands.add(new DdzEngine.Candidate("王炸", 0f, new int[]{20, 30}, false));

        MonteCarloSearch.Result r = MonteCarloSearch.search(
                GameRules.CLASSIC, snap, cands, models, 8, 0.7, 20261013L, 4);

        System.out.println("      手牌 A 2 小王 大王，我领出；对手各 3 张");
        System.out.println("      仿真 " + r.sims + " 次 / 候选 " + r.rows.size()
                + " 个 / 耗时 " + r.millis + " ms");
        for (MonteCarloSearch.Row row : r.rows) {
            System.out.printf("          %-6s 胜率=%.3f  分差=%+.3f  融合=%.4f  n=%d%n",
                    row.display, row.winRate, row.avgUtil, row.fused, row.n);
        }
        check("搜索返回了首选", r.best != null, "");
        check("全部候选都被评估", r.rows.size() == cands.size(),
                r.rows.size() + "/" + cands.size());
        boolean wrOk = true;
        for (MonteCarloSearch.Row row : r.rows) {
            if (row.winRate < 0 || row.winRate > 1) wrOk = false;
            if (row.n != r.sims) wrOk = false;
        }
        check("胜率落在 [0,1] 且每个候选仿真次数一致", wrOk, "");
        check("结果按融合分降序",
                isSortedDesc(r.rows), "");

        // ---------- B：确定性 ----------
        section("B) 可复现性：同 seed 两次结果一致");
        MonteCarloSearch.Result r2 = MonteCarloSearch.search(
                GameRules.CLASSIC, snap, cands, models, 8, 0.7, 20261013L, 4);
        boolean same = r.rows.size() == r2.rows.size();
        if (same) {
            for (int i = 0; i < r.rows.size(); i++) {
                if (!r.rows.get(i).display.equals(r2.rows.get(i).display)
                        || Math.abs(r.rows.get(i).winRate - r2.rows.get(i).winRate) > 1e-12) {
                    same = false;
                    break;
                }
            }
        }
        check("同 seed 同线程数 → 逐行一致", same, "");
        check("首选一致", r.best.display.equals(r2.best.display),
                r.best.display + " vs " + r2.best.display);

        // ---------- C：区分度 ----------
        section("C) 区分度：不同候选胜率不同");
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (MonteCarloSearch.Row row : r.rows) {
            lo = Math.min(lo, row.winRate);
            hi = Math.max(hi, row.winRate);
        }
        System.out.printf("      胜率区间 %.3f ~ %.3f（跨度 %.3f）%n", lo, hi, hi - lo);
        check("候选之间存在胜率差异", (hi - lo) > 1e-9,
                String.format("%.4f", hi - lo));

        // ---------- D：赖子用法 ----------
        section("D) 赖子场景：该不该现在用掉赖子");
        MonteCarloSearch.Snapshot snapL = laiziEndgameSnapshot();
        List<DdzEngine.Candidate> candsL = new ArrayList<>();
        candsL.add(new DdzEngine.Candidate("三张3", 0f, new int[]{3, 3, 3}, new int[]{3, 3, 3}, false, 0));
        candsL.add(new DdzEngine.Candidate("炸弹3（用赖子）", 0f, new int[]{3, 3, 3, 7}, new int[]{3, 3, 3, 3}, false, 1));

        MonteCarloSearch.Result rL = MonteCarloSearch.search(
                GameRules.laizi(7), snapL, candsL, models, 8, 0.7, 777L, 4);
        System.out.println("      手牌 3 3 3 赖子(7) 9，我领出；对手各 6 张");
        for (MonteCarloSearch.Row row : rL.rows) {
            System.out.printf("          %-18s 胜率=%.3f  分差=%+.3f  融合=%.4f%n",
                    row.display, row.winRate, row.avgUtil, row.fused);
        }
        check("赖子场景搜索跑通", rL.best != null, "");
        check("两个用法都被评估", rL.rows.size() == 2, String.valueOf(rL.rows.size()));
        check("搜索给出了明确取舍（融合分不同）",
                Math.abs(rL.rows.get(0).fused - rL.rows.get(1).fused) > 1e-9,
                String.format("%.4f vs %.4f", rL.rows.get(0).fused, rL.rows.get(1).fused));

        // ---------- E：性能 ----------
        section("E) 性能");
        System.out.printf("      残局（4 张手牌，8 次仿真 × 5 候选）耗时 %d ms%n", r.millis);
        System.out.printf("      赖子残局（8 次仿真 × 2 候选）耗时 %d ms%n", rL.millis);
        check("单次搜索在 10 秒内完成（桌面 CPU）",
                r.millis < 10_000, r.millis + " ms");

        System.out.println();
        System.out.println("=".repeat(76));
        System.out.printf("结果: %d 通过, %d 失败%n", pass, fail);
        System.out.println("=".repeat(76));
        if (fail > 0) System.exit(1);
    }

    // ---------------- 快照构造 ----------------

    /** 我（地主）4 张，对手各 3 张，我领出。 */
    static MonteCarloSearch.Snapshot endgameSnapshot() {
        return snapshot(RolloutEnv.LL, new int[]{14, 17, 20, 30},
                new int[]{3, 3, 3}, 4, 3, 3);
    }

    /** 我（地主）5 张含 1 张赖子，对手各 6 张，我领出。局面有悬念。 */
    static MonteCarloSearch.Snapshot laiziEndgameSnapshot() {
        return snapshot(RolloutEnv.LL, new int[]{3, 3, 3, 7, 9},
                new int[]{3, 3, 3}, 5, 6, 6);
    }

    static MonteCarloSearch.Snapshot snapshot(String myPos, int[] myHand, int[] three,
                                              int nLL, int nUP, int nDOWN) {
        MonteCarloSearch.Snapshot s = new MonteCarloSearch.Snapshot();
        s.myPos = myPos;
        s.myHand = myHand.clone();
        s.threeCards = three.clone();
        s.playedCards = new LinkedHashMap<>();
        s.lastMoveDict = new LinkedHashMap<>();
        for (String p : RolloutEnv.POS) {
            s.playedCards.put(p, new ArrayList<>());
            s.lastMoveDict.put(p, new int[0]);
        }
        s.actionSeq = new ArrayList<>();
        s.lastMove = new int[0];
        s.lastPid = myPos;      // 上一手是我 → 我自由领出
        s.bombNum = 0;
        s.turn = myPos;
        s.numLeft = new LinkedHashMap<>();
        s.numLeft.put(RolloutEnv.LL, nLL);
        s.numLeft.put(RolloutEnv.UP, nUP);
        s.numLeft.put(RolloutEnv.DOWN, nDOWN);
        return s;
    }

    // ---------------- 辅助 ----------------

    static Map<String, DdzModel> loadModels(Path assets, String objective) throws Exception {
        Map<String, DdzModel> m = new LinkedHashMap<>();
        for (String pos : RolloutEnv.POS) {
            byte[] raw = Files.readAllBytes(
                    assets.resolve("ddz_" + objective + "_" + pos + ".bin"));
            ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            float[] blob = new float[raw.length / 4];
            for (int i = 0; i < blob.length; i++) blob[i] = bb.getFloat();
            m.put(pos, DdzModel.fromBlob(blob, DdzModel.inDimFor(pos)));
        }
        return m;
    }

    static boolean isSortedDesc(List<MonteCarloSearch.Row> rows) {
        for (int i = 0; i + 1 < rows.size(); i++) {
            if (rows.get(i).fused < rows.get(i + 1).fused - 1e-12) return false;
        }
        return true;
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
