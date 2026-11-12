package com.example.ddzoffline.ai;

import com.example.ddzoffline.DdzEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「DouZero 能不能判断赖子怎么出」—— 实证测试。
 *
 * 方法：对**全部**合法动作逐个打分（不是只看 top4），
 * 然后比较「同一张赖子扮演不同角色」时模型的打分差异。
 *
 * 若打分随赖子用途显著变化 → 模型确实在评估赖子该怎么用。
 * 若打分完全相同 → 说明具体化方式没被模型区分，那结论就得如实说明。
 */
public final class LaiziReasoningTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path assets = root.resolve("android-offline/app/src/main/assets");
        WeightSource src = name -> Files.readAllBytes(assets.resolve(name));

        System.out.println("=".repeat(76));
        System.out.println("DouZero 对赖子的判断能力 —— 实证（全量打分）");
        System.out.println("=".repeat(76));

        // ---------- A：同一张赖子，不同用法，打分是否不同 ----------
        section("A) 同一张赖子当不同牌，模型打分对比");
        {
            DdzEngine e = newEngine(src, 7,
                    "9 7 3 5 6 8 10 J Q K A 2 X D 4 5 6");
            List<DdzEngine.Candidate> all = e.scoreAllLaiziMoves();
            System.out.println("      该局面共 " + all.size() + " 个合法动作");

            // 找出「用了赖子」的动作里，physical 相同但 concrete 不同的组
            Map<String, List<DdzEngine.Candidate>> byPhysical = new LinkedHashMap<>();
            for (DdzEngine.Candidate c : all) {
                if (c.laiziUsed == 0) continue;
                byPhysical.computeIfAbsent(Arrays.toString(c.cards), k -> new ArrayList<>()).add(c);
            }
            int multi = 0, differing = 0;
            double maxGap = 0;
            for (Map.Entry<String, List<DdzEngine.Candidate>> en : byPhysical.entrySet()) {
                List<DdzEngine.Candidate> group = en.getValue();
                if (group.size() < 2) continue;
                multi++;
                double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
                for (DdzEngine.Candidate c : group) {
                    lo = Math.min(lo, c.score);
                    hi = Math.max(hi, c.score);
                }
                double gap = hi - lo;
                maxGap = Math.max(maxGap, gap);
                if (gap > 1e-7) differing++;
                if (multi <= 3) {
                    System.out.println("      physical " + en.getKey() + " 有 " + group.size()
                            + " 种具体化，打分跨度 " + String.format("%.6f", gap));
                    for (DdzEngine.Candidate c : group) {
                        System.out.printf("          %-26s %.6f%n",
                                Arrays.toString(c.concrete), c.score);
                    }
                }
            }
            System.out.printf("      同一手牌多种具体化的组: %d，其中打分不同的: %d，最大跨度 %.6f%n",
                    multi, differing, maxGap);
            check("存在「同一 physical 多种具体化」的组", multi > 0,
                    "找到 " + multi + " 组");
            check("模型对同一手牌的不同具体化给出不同打分",
                    differing > 0, "打分全同（跨度 " + maxGap + "）");
        }

        // ---------- B：赖子当大牌 vs 当小牌 ----------
        section("B) 赖子当大牌 vs 当小牌（同为单张）");
        {
            // 关键：手牌必须**缺一些点数**，赖子才有替代空间。
            // 若某点数已有真牌，引擎会优先用真牌（不浪费赖子）——这是正确行为。
            DdzEngine e = newEngine(src, 7,
                    "7 9 9 10 10 J J Q Q K K A A 2 2 X D");
            List<DdzEngine.Candidate> all = e.scoreAllLaiziMoves();
            List<DdzEngine.Candidate> singles = new ArrayList<>();
            for (DdzEngine.Candidate c : all) {
                if (c.cards.length == 1 && c.laiziUsed == 1) singles.add(c);
            }
            System.out.println("      单张赖子的具体化共 " + singles.size() + " 种：");
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            String loName = "", hiName = "";
            for (DdzEngine.Candidate c : singles) {
                System.out.printf("          当 %-4s → %.6f%n",
                        Types.label(c.concrete[0]), c.score);
                if (c.score < lo) { lo = c.score; loName = Types.label(c.concrete[0]); }
                if (c.score > hi) { hi = c.score; hiName = Types.label(c.concrete[0]); }
            }
            check("单张赖子有多种具体化", singles.size() > 1,
                    String.valueOf(singles.size()));
            check("模型给不同点数不同打分（能挑出最优）",
                    singles.size() > 1 && (hi - lo) > 1e-7,
                    String.format("最高 %s=%.6f  最低 %s=%.6f  跨度 %.6f",
                            hiName, hi, loName, lo, hi - lo));
        }

        // ---------- C：赖子凑炸弹 vs 拆开当对子 ----------
        section("C) 赖子凑炸弹 vs 当对子");
        {
            DdzEngine e = newEngine(src, 7,
                    "3 3 3 7 4 5 6 8 9 10 J Q K A 2 X D");
            List<DdzEngine.Candidate> all = e.scoreAllLaiziMoves();
            // 炸弹：physical 3 3 3 7，concrete 可当 3 炸或别的点数炸
            List<DdzEngine.Candidate> bombs = new ArrayList<>();
            for (DdzEngine.Candidate c : all) {
                if (c.concrete.length == 4 && allSame(c.concrete)) bombs.add(c);
            }
            Double pairOnly = findScore(all, new int[]{3, 3}, new int[]{3, 3});
            System.out.println("      炸弹类动作 " + bombs.size() + " 个：");
            for (int i = 0; i < Math.min(6, bombs.size()); i++) {
                DdzEngine.Candidate c = bombs.get(i);
                System.out.printf("          %-22s 打分=%.6f 用赖子=%d%n",
                        Arrays.toString(c.concrete), c.score, c.laiziUsed);
            }
            System.out.printf("      只用对 3（不用赖子） 打分 = %s%n", fmt(pairOnly));
            check("存在炸弹类动作", !bombs.isEmpty(), String.valueOf(bombs.size()));
            boolean anyLaiziBomb = bombs.stream().anyMatch(c -> c.laiziUsed > 0);
            check("炸弹里有用赖子凑成的", anyLaiziBomb, "");
            if (pairOnly != null && !bombs.isEmpty()) {
                check("炸弹与对子打分不同",
                        Math.abs(bombs.get(0).score - pairOnly) > 1e-7,
                        String.format("炸弹=%.6f 对子=%.6f", bombs.get(0).score, pairOnly));
            }
        }

        // ---------- D：赖子当不同对子（含真牌配赖子） ----------
        section("D) 赖子当不同对子");
        {
            DdzEngine e = newEngine(src, 7,
                    "7 7 9 9 10 J Q K A 2 X D 3 4 5 6 8");
            List<DdzEngine.Candidate> all = e.scoreAllLaiziMoves();
            // 所有「用赖子凑成的对子」（physical 含赖子、concrete 成对）
            List<DdzEngine.Candidate> pairs = new ArrayList<>();
            for (DdzEngine.Candidate c : all) {
                if (c.cards.length == 2 && c.laiziUsed > 0
                        && c.concrete[0] == c.concrete[1]) {
                    pairs.add(c);
                }
            }
            System.out.println("      用赖子凑成的对子共 " + pairs.size() + " 种：");
            for (DdzEngine.Candidate c : pairs) {
                System.out.printf("          赖子当 %-4s → %.6f%n",
                        Types.label(c.concrete[0]), c.score);
            }
            check("赖子可凑成多种对子", pairs.size() > 1, String.valueOf(pairs.size()));
            if (pairs.size() > 1) {
                double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
                for (DdzEngine.Candidate c : pairs) {
                    lo = Math.min(lo, c.score);
                    hi = Math.max(hi, c.score);
                }
                check("不同对子打分不同（模型在挑）", (hi - lo) > 1e-7,
                        String.format("跨度 %.6f", hi - lo));
            }

            // 赖子默认不当王：concrete 不应出现大小王
            boolean jokerAsLaizi = pairs.stream().anyMatch(c ->
                    c.concrete[0] == 20 || c.concrete[0] == 30);
            check("赖子默认不当大小王（laiziCanBeJoker=false）", !jokerAsLaizi,
                    jokerAsLaizi ? "出现了王" : "");
        }

        // ---------- E：完整推荐的候选分布 ----------
        section("E) 完整推荐：候选是否体现取舍");
        {
            DdzEngine e = newEngine(src, 7,
                    "9 7 3 5 6 8 10 J Q K A 2 X D 4 5 6");
            DdzEngine.Recommendation r = e.recommend();
            System.out.println("      推荐: " + r.move);
            for (DdzEngine.Candidate c : r.candidates) {
                System.out.printf("        %-36s 打分=%.4f 用赖子=%d%n",
                        c.display, c.score, c.laiziUsed);
            }
            List<Double> sc = new ArrayList<>();
            for (DdzEngine.Candidate c : r.candidates) sc.add((double) c.score);
            check("候选打分互不相同（排序有意义）", distinct(sc), sc.toString());
        }

        // ---------- F：经典模式不受影响 ----------
        section("F) 经典模式回归");
        {
            DdzEngine e = new DdzEngine(src);
            e.start("landlord", "3 4 5 6 7 8 9 10 J Q K A 2 X D 3 4", "9 9 2");
            DdzEngine.Recommendation r = e.recommend();
            check("经典模式推荐不变", "3 4 5 6 7 8 9".equals(r.move), r.move);
        }

        System.out.println();
        System.out.println("=".repeat(76));
        System.out.printf("结果: %d 通过, %d 失败%n", pass, fail);
        System.out.println("=".repeat(76));
        if (fail > 0) System.exit(1);
    }

    // ---------------- 辅助 ----------------

    static DdzEngine newEngine(WeightSource src, int laiziRank, String hand) {
        DdzEngine e = new DdzEngine(src);
        e.enableLaizi(laiziRank);
        // 手牌必须 17 张；调用方给的字符串需满足
        List<Integer> cards = CardInput.parse(hand).cards;
        if (cards.size() != 17) {
            throw new IllegalArgumentException("手牌需 17 张，当前 " + cards.size()
                    + ": " + hand);
        }
        e.start("landlord", hand, "2 2 2");
        return e;
    }

    static Double findScore(List<DdzEngine.Candidate> all, int[] physical, int[] concrete) {
        int[] ph = physical.clone();
        Arrays.sort(ph);
        for (DdzEngine.Candidate c : all) {
            int[] cc = c.cards.clone();
            Arrays.sort(cc);
            if (Arrays.equals(cc, ph) && Arrays.equals(c.concrete, sorted(concrete))) {
                return (double) c.score;
            }
        }
        return null;
    }

    static int[] sorted(int[] a) {
        int[] b = a.clone();
        Arrays.sort(b);
        return b;
    }

    static boolean allSame(int[] a) {
        for (int v : a) if (v != a[0]) return false;
        return true;
    }

    static String fmt(Double d) {
        return d == null ? "(未在合法动作中)" : String.format("%.6f", d);
    }

    static boolean distinct(List<Double> vals) {
        for (int i = 0; i < vals.size(); i++) {
            for (int j = i + 1; j < vals.size(); j++) {
                if (Math.abs(vals.get(i) - vals.get(j)) < 1e-9) return false;
            }
        }
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
