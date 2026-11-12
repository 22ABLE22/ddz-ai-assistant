package com.example.ddzoffline.ai;

import com.example.ddzoffline.DdzEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 蒙特卡洛仿真搜索 —— 移植自 PC 版 {@code ddz_search.py}。
 *
 * <p>与「单步价值打分」的区别：单步打分只回答「这一手在模型眼里值多少」，
 * 而搜索回答「这一手之后，把剩下的牌打完，我赢的概率是多少」。
 * 后者能判断「该不该现在用掉赖子」这类需要跨步推理的决策。
 *
 * <p>算法（完美信息蒙特卡洛 / PIMC）：
 * <ol>
 *   <li>从剩余牌堆采样对手手牌（每次仿真一个确定化的世界）</li>
 *   <li>强制走出待评估的候选着</li>
 *   <li>三家都用模型贪心打到终局</li>
 *   <li>统计胜率与平均分差，按 wpWeight 融合后取最优</li>
 * </ol>
 *
 * <p>同一轮仿真内所有候选共用同一个采样世界（common random numbers），
 * 这样候选之间的差异来自决策本身而不是采样噪声。
 */
public final class MonteCarloSearch {

    /** 单局最大步数上限，防御性兜底。 */
    public static final int MAX_PLIES = 300;

    private MonteCarloSearch() {}

    // ---------------- 数据结构 ----------------

    /** 搜索所需的局面快照（可从 DdzEngine 导出）。 */
    public static final class Snapshot {
        public String myPos;
        public int[] myHand;
        public int[] threeCards;
        public Map<String, List<Integer>> playedCards;
        public Map<String, int[]> lastMoveDict;
        public List<int[]> actionSeq;
        public int[] lastMove;
        public String lastPid;
        public int bombNum;
        public String turn;
        public Map<String, Integer> numLeft;
    }

    /** 单个候选的搜索结果。 */
    public static final class Row {
        public final int[] move;        // physical（真实打出的牌）
        public final int[] concrete;    // 具体化后的牌
        public final String display;
        public final double winRate;
        public final double avgUtil;
        public final double fused;
        public final int n;

        Row(int[] move, int[] concrete, String display,
            double winRate, double avgUtil, double fused, int n) {
            this.move = move;
            this.concrete = concrete;
            this.display = display;
            this.winRate = winRate;
            this.avgUtil = avgUtil;
            this.fused = fused;
            this.n = n;
        }
    }

    public static final class Result {
        public final Row best;
        public final List<Row> rows;   // 按 fused 降序
        public final int sims;
        public final double wpWeight;
        public final long millis;

        Result(Row best, List<Row> rows, int sims, double wpWeight, long millis) {
            this.best = best;
            this.rows = rows;
            this.sims = sims;
            this.wpWeight = wpWeight;
            this.millis = millis;
        }
    }

    // ---------------- 贪心 agent ----------------

    /**
     * 用模型贪心决策。三家共用同一个实例（模型 forward 是纯函数，权重只读）。
     */
    static final class GreedyAgent implements RolloutEnv.Agent {
        private final Map<String, DdzModel> models;

        GreedyAgent(Map<String, DdzModel> models) { this.models = models; }

        @Override
        public int[] act(String pos, RolloutEnv env) {
            List<LaiziMoves.Move> legal = env.legalMoveObjects(pos);
            if (legal.isEmpty()) return new int[0];
            if (legal.size() == 1) return legal.get(0).physical;

            List<int[]> concretes = new ArrayList<>(legal.size());
            for (LaiziMoves.Move m : legal) concretes.add(m.concrete);

            InfoSet is = env.buildInfoSetFor(pos, concretes);
            is.legalActions = concretes;

            DdzFeatures.Encoded enc = DdzFeatures.encode(is);
            Tensor x = new Tensor(concretes.size(), enc.inDim, enc.xBatch);
            float[] v = models.get(pos).forward(enc.zBatch, x);

            int best = 0;
            for (int i = 1; i < v.length; i++) if (v[i] > v[best]) best = i;
            return legal.get(best).physical;
        }
    }

    // ---------------- 采样 ----------------

    /** 54 张满牌。 */
    static List<Integer> fullDeck() {
        List<Integer> d = new ArrayList<>(54);
        for (int r = 3; r <= 14; r++) for (int i = 0; i < 4; i++) d.add(r);
        for (int i = 0; i < 4; i++) d.add(17);
        d.add(20);
        d.add(30);
        return d;
    }

    private static void removeOne(List<Integer> pool, int card) {
        for (int i = 0; i < pool.size(); i++) {
            if (pool.get(i) == card) { pool.remove(i); return; }
        }
    }

    private static boolean contains(List<Integer> l, int c) {
        for (int x : l) if (x == c) return true;
        return false;
    }

    /**
     * 采样一个「确定化的世界」：补齐两家未知手牌。
     *
     * <p>约束：底牌里尚未出现、也不在我手上的牌，必须在地主手上。
     */
    static Map<String, List<Integer>> sampleWorld(Snapshot s, Random rng) {
        List<Integer> pool = fullDeck();
        for (int c : s.myHand) removeOne(pool, c);
        for (String p : RolloutEnv.POS) {
            List<Integer> pl = s.playedCards.get(p);
            if (pl != null) for (int c : pl) removeOne(pool, c);
        }

        // 底牌中未亮出的部分归地主
        List<Integer> threeLeft = new ArrayList<>();
        if (!RolloutEnv.LL.equals(s.myPos)) {
            for (int c : s.threeCards) {
                boolean seen = false;
                for (String p : RolloutEnv.POS) {
                    List<Integer> pl = s.playedCards.get(p);
                    if (pl != null && contains(pl, c)) { seen = true; break; }
                }
                if (!seen && !contains(toList(s.myHand), c)) threeLeft.add(c);
            }
            for (int c : threeLeft) removeOne(pool, c);
        }

        Collections.shuffle(pool, rng);

        int nLL = numLeft(s, RolloutEnv.LL);
        int nUP = numLeft(s, RolloutEnv.UP);
        int nDOWN = numLeft(s, RolloutEnv.DOWN);

        Map<String, List<Integer>> hands = new LinkedHashMap<>();
        int idx = 0;
        if (RolloutEnv.LL.equals(s.myPos)) {
            hands.put(RolloutEnv.LL, sorted(toList(s.myHand)));
            hands.put(RolloutEnv.DOWN, sorted(slice(pool, idx, nDOWN))); idx += nDOWN;
            hands.put(RolloutEnv.UP, sorted(slice(pool, idx, nUP)));
        } else if (RolloutEnv.UP.equals(s.myPos)) {
            hands.put(RolloutEnv.UP, sorted(toList(s.myHand)));
            int llRest = Math.max(0, nLL - threeLeft.size());
            List<Integer> ll = new ArrayList<>(threeLeft);
            ll.addAll(slice(pool, idx, llRest)); idx += llRest;
            hands.put(RolloutEnv.LL, sorted(ll));
            hands.put(RolloutEnv.DOWN, sorted(slice(pool, idx, nDOWN)));
        } else {
            hands.put(RolloutEnv.DOWN, sorted(toList(s.myHand)));
            int llRest = Math.max(0, nLL - threeLeft.size());
            List<Integer> ll = new ArrayList<>(threeLeft);
            ll.addAll(slice(pool, idx, llRest)); idx += llRest;
            hands.put(RolloutEnv.LL, sorted(ll));
            hands.put(RolloutEnv.UP, sorted(slice(pool, idx, nUP)));
        }
        return hands;
    }

    private static int numLeft(Snapshot s, String pos) {
        Integer v = s.numLeft == null ? null : s.numLeft.get(pos);
        if (v != null) return v;
        if (pos.equals(s.myPos)) return s.myHand.length;
        return 17;
    }

    private static List<Integer> slice(List<Integer> src, int from, int n) {
        List<Integer> out = new ArrayList<>(Math.max(0, n));
        for (int i = from; i < from + n && i < src.size(); i++) out.add(src.get(i));
        return out;
    }

    private static List<Integer> toList(int[] a) {
        List<Integer> l = new ArrayList<>(a.length);
        for (int c : a) l.add(c);
        return l;
    }

    private static List<Integer> sorted(List<Integer> l) {
        List<Integer> c = new ArrayList<>(l);
        Collections.sort(c);
        return c;
    }

    // ---------------- 单次推演 ----------------

    /** 强制走出 firstAction，然后三家贪心打到终局。返回 {win, util}。 */
    static double[] playout(GameRules rules, Snapshot s,
                            Map<String, List<Integer>> hands,
                            Map<String, DdzModel> models,
                            int[] firstAction) {
        RolloutEnv env = new RolloutEnv(rules, hands, s.threeCards, s.playedCards,
                s.lastMoveDict, s.actionSeq, s.lastMove, s.lastPid, s.bombNum, s.turn);
        env.apply(s.myPos, firstAction);

        GreedyAgent greedy = new GreedyAgent(models);
        Map<String, RolloutEnv.Agent> agents = new HashMap<>();
        for (String p : RolloutEnv.POS) agents.put(p, greedy);
        env.runOut(agents, MAX_PLIES);

        double u = env.utilityFor(s.myPos);
        return new double[]{u > 0 ? 1.0 : 0.0, u};
    }

    // ---------------- 搜索主入口 ----------------

    /**
     * 对候选着做仿真搜索。
     *
     * @param candidates 待评估候选（通常来自单步打分 top-K）
     * @param sims       仿真次数
     * @param wpWeight   融合权重：wpWeight*胜率 + (1-wpWeight)*归一化分差
     * @param threads    并行线程数（1 = 单线程）
     */
    public static Result search(GameRules rules,
                                Snapshot snap,
                                List<DdzEngine.Candidate> candidates,
                                Map<String, DdzModel> models,
                                int sims,
                                double wpWeight,
                                long seed,
                                int threads) {
        long t0 = System.currentTimeMillis();
        final int k = candidates.size();
        if (k == 0) throw new IllegalArgumentException("没有待评估的候选");

        final int nSims = Math.max(1, sims);
        final int nThreads = Math.max(1, threads);

        final double[] wins = new double[k];
        final double[] utils = new double[k];
        final int[] counts = new int[k];

        List<Callable<double[]>> tasks = new ArrayList<>(nSims);
        for (int i = 0; i < nSims; i++) {
            final long s0 = seed + (long) i * 9973L;
            tasks.add(() -> {
                Random rng = new Random(s0);
                Map<String, List<Integer>> hands = sampleWorld(snap, rng);
                double[] out = new double[2 * k];
                for (int c = 0; c < k; c++) {
                    double[] r = playout(rules, snap, hands, models, candidates.get(c).cards);
                    out[c] = r[0];
                    out[k + c] = r[1];
                }
                return out;
            });
        }

        ExecutorService ex = nThreads > 1 ? Executors.newFixedThreadPool(nThreads) : null;
        try {
            List<Future<double[]>> futures;
            if (ex != null) {
                futures = ex.invokeAll(tasks);
            } else {
                futures = new ArrayList<>(nSims);
                for (Callable<double[]> t : tasks) {
                    double[] r;
                    try {
                        r = t.call();
                    } catch (Exception e) {
                        r = null;
                    }
                    final double[] fr = r;
                    futures.add(new Future<double[]>() {
                        public boolean cancel(boolean b) { return false; }
                        public boolean isCancelled() { return false; }
                        public boolean isDone() { return true; }
                        public double[] get() { return fr; }
                        public double[] get(long a, TimeUnit u) { return fr; }
                    });
                }
            }
            for (Future<double[]> f : futures) {
                double[] r;
                try {
                    r = f.get();
                } catch (Exception e) {
                    continue;
                }
                if (r == null) continue;
                for (int c = 0; c < k; c++) {
                    wins[c] += r[c];
                    utils[c] += r[k + c];
                    counts[c]++;
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            if (ex != null) ex.shutdownNow();
        }

        List<Row> rows = new ArrayList<>(k);
        for (int c = 0; c < k; c++) {
            int n = Math.max(1, counts[c]);
            double wr = wins[c] / n;
            double au = utils[c] / n;
            double nu = normalizeUtil(au, snap.myPos);
            double fused = wpWeight * wr + (1.0 - wpWeight) * nu;
            DdzEngine.Candidate cand = candidates.get(c);
            rows.add(new Row(cand.cards, cand.concrete, cand.display, wr, au, fused, counts[c]));
        }
        Collections.sort(rows, (a, b) -> Double.compare(b.fused, a.fused));

        long ms = System.currentTimeMillis() - t0;
        return new Result(rows.get(0), rows, nSims, wpWeight, ms);
    }

    /** utility → [0,1]，对齐 ddz_search.py 的 normalize_util。 */
    static double normalizeUtil(double util, String myPos) {
        double v = RolloutEnv.LL.equals(myPos) ? (util + 2.0) / 4.0 : (util + 1.0) / 2.0;
        return Math.max(0.0, Math.min(1.0, v));
    }
}
