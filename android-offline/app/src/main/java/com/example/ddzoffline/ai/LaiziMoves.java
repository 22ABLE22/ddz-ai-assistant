package com.example.ddzoffline.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 赖子模式的合法出牌生成。
 *
 * 与 {@link MoveGenerator} 的关系：
 *   - 经典模式仍走 MoveGenerator（零改动，保证回归）
 *   - 赖子模式走本类：以「牌型需求 → 用具体牌 + 赖子填充」的方式枚举，
 *     因此**不会漏招**（不依赖对赖子做有限展开的近似）
 *
 * 每个动作同时携带两套表示：
 *   - {@code physical}：实际打出的手牌（含赖子牌本身），用于扣手牌与显示
 *   - {@code concrete}：把赖子替换成目标点数后的牌，用于喂给 DouZero 模型
 *
 * 顺子只允许 3..14（2 与大小王天然不参与，因为 14→17 不连续）。
 */
public final class LaiziMoves {

    /** 可参与顺子的点数（升序）。 */
    static final int[] STRAIGHT = {3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14};
    /** 全部点数（升序），用于非顺子牌型。 */
    static final int[] ALL = {3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 17, 20, 30};

    private static final int MAX_MOVES = 40000;

    /** 一个可出的动作。 */
    public static final class Move {
        public final int[] physical;    // 实际手牌（含赖子）
        public final int[] concrete;    // 替换后的具体牌（给模型）
        public final int laiziUsed;

        Move(int[] physical, int[] concrete, int laiziUsed) {
            this.physical = physical;
            this.concrete = concrete;
            this.laiziUsed = laiziUsed;
        }

        /** 外部构造入口（引擎在做合法性判定时需要自行组装 Move）。 */
        public static Move of(int[] physical, int[] concrete, int laiziUsed) {
            return new Move(physical, concrete, laiziUsed);
        }

        public boolean usesLaizi() { return laiziUsed > 0; }

        /** 显示用：具体牌面，赖子位置加标记。 */
        public String display(GameRules rules) {
            StringBuilder sb = new StringBuilder();
            // 逐张输出具体牌；若该张是赖子则加后缀
            List<Integer> laiziSlots = new ArrayList<>();
            int[] phys = physical.clone();
            int[] conc = concrete.clone();
            Arrays.sort(phys);
            Arrays.sort(conc);
            for (int i = 0; i < phys.length; i++) {
                if (rules.isLaizi(phys[i])) laiziSlots.add(i);
            }
            for (int i = 0; i < conc.length; i++) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(Types.label(conc[i]));
                if (laiziSlots.contains(i)) sb.append('*');
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return Types.display(concrete) + (laiziUsed > 0 ? " (赖x" + laiziUsed + ")" : "");
        }
    }

    private final GameRules rules;
    private final int[] conc = new int[ALL.length];   // 各点数具体牌张数（不含赖子）
    private final int wild;                            // 赖子张数
    private final int laiziRank;

    public LaiziMoves(int[] hand, GameRules rules) {
        this.rules = rules;
        this.laiziRank = rules.getLaiziRank();
        int w = 0;
        for (int c : hand) {
            if (rules.isLaizi(c)) { w++; continue; }
            int i = idx(c);
            if (i >= 0) conc[i]++;
        }
        this.wild = w;
    }

    static int idx(int rank) {
        for (int i = 0; i < ALL.length; i++) if (ALL[i] == rank) return i;
        return -1;
    }

    private static int straightIdx(int rank) {
        for (int i = 0; i < STRAIGHT.length; i++) if (STRAIGHT[i] == rank) return i;
        return -1;
    }

    // ---------------- 核心：按需求物化 ----------------

    /**
     * 把「各点数需要多少张」物化成实际手牌。
     *
     * 先用具体牌，不足部分用赖子补；赖子不够则返回 null。
     * 赖子点数自身的需求全部由赖子牌满足（该点数的牌本身就是赖子）。
     */
    private Move materialize(int[] need) {
        int wildLeft = wild;
        List<Integer> phys = new ArrayList<>();
        List<Integer> concList = new ArrayList<>();
        int used = 0;

        for (int i = 0; i < need.length; i++) {
            int n = need[i];
            if (n == 0) continue;
            int rank = ALL[i];

            // 大小王不能被赖子替代（除非显式开启 laiziCanBeJoker）。
            // 否则会产出「赖子当大王」这类非法牌型 —— 这里用真牌数量兜住。
            boolean joker = (rank == 20 || rank == 30);
            if (joker && !rules.laiziCanBeJoker) {
                if (conc[i] < n) return null;   // 王不够就直接放弃该组合
            }

            int useConcrete = Math.min(conc[i], n);
            for (int k = 0; k < useConcrete; k++) {
                phys.add(rank);
                concList.add(rank);
            }
            int rem = n - useConcrete;
            if (rem > 0) {
                if (wildLeft < rem) return null;
                wildLeft -= rem;
                used += rem;
                for (int k = 0; k < rem; k++) {
                    phys.add(laiziRank);   // 实际打出的是赖子牌
                    concList.add(rank);    // 在牌型中充当该点数
                }
            }
        }
        if (phys.isEmpty()) return null;
        int[] p = toArr(phys);
        int[] c = toArr(concList);
        Arrays.sort(p);
        Arrays.sort(c);
        return new Move(p, c, used);
    }

    private static int[] toArr(List<Integer> l) {
        int[] o = new int[l.size()];
        for (int i = 0; i < o.length; i++) o[i] = l.get(i);
        return o;
    }

    private static int[] need() { return new int[ALL.length]; }

    // ---------------- 枚举全部动作 ----------------

    public List<Move> generateAll() {
        Map<String, Move> out = new LinkedHashMap<>();
        addSingles(out);
        addPairs(out);
        addTriples(out);
        addBombs(out);
        addKingBomb(out);
        addTripleWithSingle(out);
        addTripleWithPair(out);
        addSerialSingle(out);
        addSerialPair(out);
        addSerialTriple(out);
        addSerialTripleWithSingle(out);
        addSerialTripleWithPair(out);
        addFourWithTwo(out);
        addFourWithTwoPairs(out);
        return new ArrayList<>(out.values());
    }

    /** 去重键：physical + concrete 的组合（同一 physical 可有多种 concrete）。 */
    private static String key(Move m) {
        return Arrays.toString(m.physical) + "|" + Arrays.toString(m.concrete);
    }

    private void put(Map<String, Move> out, Move m) {
        if (m == null || out.size() >= MAX_MOVES) return;
        // 同一键保留用赖子更少的那种（更省万能牌）
        String k = key(m);
        Move old = out.get(k);
        if (old == null || m.laiziUsed < old.laiziUsed) out.put(k, m);
    }

    private void addSingles(Map<String, Move> out) {
        for (int i = 0; i < ALL.length; i++) {
            if (conc[i] == 0 && wild == 0) continue;
            if (rules.isLaizi(ALL[i]) && !rules.laiziCanPlayAlone) continue;
            int[] nd = need();
            nd[i] = 1;
            put(out, materialize(nd));
        }
    }

    private void addPairs(Map<String, Move> out) {
        for (int i = 0; i < ALL.length; i++) {
            int[] nd = need();
            nd[i] = 2;
            put(out, materialize(nd));
        }
    }

    private void addTriples(Map<String, Move> out) {
        for (int i = 0; i < ALL.length; i++) {
            int[] nd = need();
            nd[i] = 3;
            put(out, materialize(nd));
        }
    }

    private void addBombs(Map<String, Move> out) {
        for (int i = 0; i < ALL.length; i++) {
            if (ALL[i] == 20 || ALL[i] == 30) continue;   // 王不构成普通炸弹
            int[] nd = need();
            nd[i] = 4;
            put(out, materialize(nd));
        }
    }

    private void addKingBomb(Map<String, Move> out) {
        int i20 = idx(20), i30 = idx(30);
        if (conc[i20] >= 1 && conc[i30] >= 1) {
            int[] nd = need();
            nd[i20] = 1;
            nd[i30] = 1;
            put(out, materialize(nd));
        }
    }

    private void addTripleWithSingle(Map<String, Move> out) {
        for (int t = 0; t < ALL.length; t++) {
            for (int s = 0; s < ALL.length; s++) {
                if (s == t) continue;
                int[] nd = need();
                nd[t] = 3;
                nd[s] = 1;
                put(out, materialize(nd));
            }
        }
    }

    private void addTripleWithPair(Map<String, Move> out) {
        for (int t = 0; t < ALL.length; t++) {
            for (int p = 0; p < ALL.length; p++) {
                if (p == t) continue;
                int[] nd = need();
                nd[t] = 3;
                nd[p] = 2;
                put(out, materialize(nd));
            }
        }
    }

    private void addSerialSingle(Map<String, Move> out) {
        for (int len = 5; len <= STRAIGHT.length; len++) {
            for (int s = 0; s + len <= STRAIGHT.length; s++) {
                int[] nd = need();
                for (int k = 0; k < len; k++) nd[idx(STRAIGHT[s + k])] = 1;
                put(out, materialize(nd));
            }
        }
    }

    private void addSerialPair(Map<String, Move> out) {
        for (int len = 3; len <= STRAIGHT.length; len++) {
            for (int s = 0; s + len <= STRAIGHT.length; s++) {
                int[] nd = need();
                for (int k = 0; k < len; k++) nd[idx(STRAIGHT[s + k])] = 2;
                put(out, materialize(nd));
            }
        }
    }

    private void addSerialTriple(Map<String, Move> out) {
        for (int len = 2; len <= STRAIGHT.length; len++) {
            for (int s = 0; s + len <= STRAIGHT.length; s++) {
                int[] nd = need();
                for (int k = 0; k < len; k++) nd[idx(STRAIGHT[s + k])] = 3;
                put(out, materialize(nd));
            }
        }
    }

    /** 飞机带单：三顺 + 等量单张（单张点数不能落在三顺上）。 */
    private void addSerialTripleWithSingle(Map<String, Move> out) {
        for (int len = 2; len <= 5; len++) {
            for (int s = 0; s + len <= STRAIGHT.length; s++) {
                Set<Integer> usedRanks = new LinkedHashSet<>();
                for (int k = 0; k < len; k++) usedRanks.add(STRAIGHT[s + k]);
                List<int[]> extraSets = distinctRanksWithCounts(usedRanks, len, 1);
                for (int[] extra : extraSets) {
                    int[] nd = need();
                    for (int k = 0; k < len; k++) nd[idx(STRAIGHT[s + k])] = 3;
                    for (int e : extra) nd[idx(e)] += 1;
                    put(out, materialize(nd));
                }
            }
        }
    }

    /** 飞机带对：三顺 + 等量对子。 */
    private void addSerialTripleWithPair(Map<String, Move> out) {
        for (int len = 2; len <= 4; len++) {
            for (int s = 0; s + len <= STRAIGHT.length; s++) {
                Set<Integer> usedRanks = new LinkedHashSet<>();
                for (int k = 0; k < len; k++) usedRanks.add(STRAIGHT[s + k]);
                List<int[]> extraSets = distinctRanksWithCounts(usedRanks, len, 2);
                for (int[] extra : extraSets) {
                    int[] nd = need();
                    for (int k = 0; k < len; k++) nd[idx(STRAIGHT[s + k])] = 3;
                    for (int e : extra) nd[idx(e)] += 2;
                    put(out, materialize(nd));
                }
            }
        }
    }

    /** 四带二：炸弹 + 任意两张。 */
    private void addFourWithTwo(Map<String, Move> out) {
        for (int b = 0; b < ALL.length; b++) {
            if (ALL[b] == 20 || ALL[b] == 30) continue;
            Set<Integer> exclude = new LinkedHashSet<>();
            exclude.add(ALL[b]);
            // 两张可以同点数（如 4+一对），也可以不同点数
            for (int i = 0; i < ALL.length; i++) {
                if (ALL[i] == 20 || ALL[i] == 30) continue;
                if (exclude.contains(ALL[i])) continue;
                int[] nd = need();
                nd[b] = 4;
                nd[i] += 2;
                put(out, materialize(nd));
                for (int j = i + 1; j < ALL.length; j++) {
                    if (ALL[j] == 20 || ALL[j] == 30) continue;
                    if (exclude.contains(ALL[j])) continue;
                    int[] nd2 = need();
                    nd2[b] = 4;
                    nd2[i] += 1;
                    nd2[j] += 1;
                    put(out, materialize(nd2));
                }
            }
        }
    }

    /** 四带两对：炸弹 + 两个对子。 */
    private void addFourWithTwoPairs(Map<String, Move> out) {
        for (int b = 0; b < ALL.length; b++) {
            if (ALL[b] == 20 || ALL[b] == 30) continue;
            List<Integer> pairCands = new ArrayList<>();
            for (int i = 0; i < ALL.length; i++) {
                if (ALL[i] == 20 || ALL[i] == 30) continue;
                if (i == b) continue;
                pairCands.add(ALL[i]);
            }
            for (int x = 0; x < pairCands.size(); x++) {
                for (int y = x + 1; y < pairCands.size(); y++) {
                    int[] nd = need();
                    nd[b] = 4;
                    nd[idx(pairCands.get(x))] += 2;
                    nd[idx(pairCands.get(y))] += 2;
                    put(out, materialize(nd));
                }
            }
        }
    }

    /**
     * 从可用点数里挑 count 个互不相同的点数，每个重复 repeat 次。
     * 用于飞机带单（repeat=1）/ 带对（repeat=2）的附加牌。
     */
    private List<int[]> distinctRanksWithCounts(Set<Integer> exclude, int count, int repeat) {
        List<Integer> avail = new ArrayList<>();
        for (int r : ALL) {
            if (exclude.contains(r)) continue;
            if (r == 20 || r == 30) continue;
            // 需要至少能凑出 repeat 张（具体牌或赖子）
            int i = idx(r);
            if (conc[i] + wild >= repeat) avail.add(r);
        }
        List<int[]> out = new ArrayList<>();
        combos(avail, count, 0, new ArrayList<>(), out);
        return out;
    }

    private void combos(List<Integer> src, int k, int start, List<Integer> cur, List<int[]> out) {
        if (out.size() > 400) return;
        if (cur.size() == k) {
            int[] a = new int[k];
            for (int i = 0; i < k; i++) a[i] = cur.get(i);
            out.add(a);
            return;
        }
        for (int i = start; i < src.size(); i++) {
            cur.add(src.get(i));
            combos(src, k, i + 1, cur, out);
            cur.remove(cur.size() - 1);
        }
    }

    // ---------------- 合法性 ----------------

    /**
     * 从当前手牌生成能压过 rival 的全部动作。
     *
     * @param rival        上家的动作（null 或 physical 为空表示自由领出）
     * @param passAllowed  是否允许不出（上家出过牌时才可以）
     */
    public List<Move> legalAgainst(Move rival, boolean passAllowed) {
        List<Move> all = generateAll();
        List<Move> out = new ArrayList<>();
        boolean freeLead = rival == null || rival.physical.length == 0;
        int[] rivalConcrete = freeLead ? new int[0] : rival.concrete;
        Map<String, Object> rivalType = MoveDetector.getMoveType(rivalConcrete);
        int rType = MoveDetector.typeOf(rivalType);

        for (Move m : all) {
            if (freeLead || beats(m, rival)) out.add(m);
        }
        if (passAllowed) {
            out.add(new Move(new int[0], new int[0], 0));
        }
        return out;
    }

    /**
     * 判断 mine 能否压过 rival。
     *
     * 必须同时接收两边的 {@link Move}（而不是具体牌数组）——
     * 因为炸弹档位的判定依赖 physical 里的赖子信息，
     * 而具体牌（concrete）里已经看不到赖子了。
     *
     * 炸弹按四分档位比较（依据 qmarliu/ddzlib 的 card_type.h）：
     *   软炸(13) < 硬炸(14) < 纯癞子炸(15) < 王炸(16)
     */
    public boolean beats(Move mine, Move rival) {
        if (mine == null) return false;
        if (rival == null || rival.physical.length == 0) {
            return MoveDetector.typeOf(MoveDetector.getMoveType(mine.concrete))
                    != Types.TYPE_15_WRONG;
        }

        int myTier = BombTier.of(mine.concrete, mine.physical, rules);
        int upTier = BombTier.of(rival.concrete, rival.physical, rules);

        // 王炸最大：压一切
        if (myTier == BombTier.ROCKET) return true;
        if (upTier == BombTier.ROCKET) return false;

        // 我方是炸弹（软/硬/纯癞子）
        if (BombTier.isBomb(myTier)) {
            if (!BombTier.isBomb(upTier)) return true;   // 炸弹压普通牌型
            int myRank = MoveDetector.rankOf(MoveDetector.getMoveType(mine.concrete));
            int upRank = MoveDetector.rankOf(MoveDetector.getMoveType(rival.concrete));
            return BombTier.compare(myTier, myRank, upTier, upRank) > 0;
        }
        // 对方是炸弹，我不是 → 压不过
        if (BombTier.isBomb(upTier)) return false;

        // 普通牌型：同型同长比点数
        Map<String, Object> myType = MoveDetector.getMoveType(mine.concrete);
        Map<String, Object> rivalType = MoveDetector.getMoveType(rival.concrete);
        int mType = MoveDetector.typeOf(myType);
        int rType = MoveDetector.typeOf(rivalType);
        if (mType == Types.TYPE_15_WRONG) return false;
        if (mType != rType) return false;
        if (mine.concrete.length != rival.concrete.length) return false;
        return MoveDetector.rankOf(myType) > MoveDetector.rankOf(rivalType);
    }

    // ---------------- 工具 ----------------

    /** 在动作集合里按 physical 精确匹配（用于校验用户输入）。 */
    public static Move findByPhysical(List<Move> moves, int[] physical) {
        int[] target = physical.clone();
        Arrays.sort(target);
        for (Move m : moves) {
            if (Arrays.equals(m.physical, target)) return m;
        }
        return null;
    }

    /**
     * 把 physical 具体化成「能对上 rival 形态」的牌，用于合法性判定。
     *
     * 判定要求同型同长，因此这里按 rival 的牌型需求来分配赖子：
     *   - 单/对/三/炸：赖子全部补到主点数上
     *   - 顺子/连对/飞机：赖子补到缺口点数
     * 返回 null 表示无法构成与 rival 同型的牌。
     */
    public static int[] concretizeAgainst(int[] physical, GameRules rules, int[] rival) {
        if (!rules.isLaiziMode()) return physical.clone();
        int wild = 0;
        List<Integer> plain = new ArrayList<>();
        for (int c : physical) {
            if (rules.isLaizi(c)) wild++;
            else plain.add(c);
        }
        if (wild == 0) return physical.clone();

        Map<String, Object> rType = MoveDetector.getMoveType(rival);
        int type = MoveDetector.typeOf(rType);
        int rank = MoveDetector.rankOf(rType);
        int len = MoveDetector.lenOf(rType);

        // 目标点数需求
        List<Integer> needRanks = new ArrayList<>();
        switch (type) {
            case Types.TYPE_1_SINGLE:
            case Types.TYPE_2_PAIR:
            case Types.TYPE_3_TRIPLE:
            case Types.TYPE_4_BOMB: {
                int repeat = rival.length;
                for (int i = 0; i < repeat; i++) needRanks.add(rank);
                break;
            }
            case Types.TYPE_8_SERIAL_SINGLE:
            case Types.TYPE_9_SERIAL_PAIR:
            case Types.TYPE_10_SERIAL_TRIPLE: {
                int repeat = rival.length / Math.max(1, len);
                for (int i = 0; i < len; i++) {
                    for (int k = 0; k < repeat; k++) needRanks.add(rank + i);
                }
                break;
            }
            default:
                return null;   // 复杂带牌牌型：交给 expand 的通用路径
        }

        if (needRanks.size() != physical.length) return null;

        // 把已有牌与目标需求做匹配，剩余用赖子填
        int[] out = new int[physical.length];
        List<Integer> pool = new ArrayList<>(plain);
        int p = 0;
        List<Integer> missing = new ArrayList<>();
        for (int want : needRanks) {
            if (pool.remove((Integer) want)) out[p++] = want;
            else missing.add(want);
        }
        if (missing.size() != wild) return null;
        for (int want : missing) out[p++] = want;
        Arrays.sort(out);
        return out;
    }

    /** 显示辅助：把赖子张数标注出来。 */
    public static String describe(Move m, GameRules rules) {
        if (m.physical.length == 0) return "不出";
        return m.display(rules) + (m.laiziUsed > 0 ? "（含" + m.laiziUsed + "张赖子）" : "");
    }

    // ---------------- 具体化（physical -> concrete） ----------------

    /**
     * 把一组含赖子的牌具体化。
     *
     * @param physical 实际牌（含赖子）
     * @param rules    规则
     * @param hint     优先采用的替代点数序列（例如推荐阶段选定的具体牌）。
     *                 长度匹配时**原样采用**，保证「推荐评估的牌面」与「实际出牌」一致。
     *                 传 null 时自动搜索一个能构成合法牌型的替代方案。
     *
     * 关键点：
     *   - 提示值允许等于赖子点数本身（赖子牌当然可以当自己用）
     *   - 无提示时不能随便填一个点数，必须**搜索出合法牌型**，
     *     否则会把「9 + 赖子」变成「3 和 9」这种不存在的牌型
     */
    public static int[] concretize(int[] physical, GameRules rules, int[] hint) {
        if (!rules.isLaiziMode()) return physical.clone();

        int[] out = physical.clone();
        List<Integer> laiziPos = new ArrayList<>();
        for (int i = 0; i < out.length; i++) {
            if (rules.isLaizi(out[i])) laiziPos.add(i);
        }
        if (laiziPos.isEmpty()) return out;

        // 1) 有提示且长度一致 → 原样采用（不再过滤赖子点数）
        if (hint != null && hint.length == out.length) {
            for (int i = 0; i < out.length; i++) out[i] = hint[i];
            Arrays.sort(out);
            return out;
        }

        // 2) 无提示 → 搜索能构成合法牌型的替代方案
        int[] best = searchValidConcretization(physical, laiziPos, rules);
        return best != null ? best : out;
    }

    /**
     * 枚举赖子的替代点数，返回第一个能构成合法牌型的结果。
     *
     * 候选顺序：先试「与手牌里已有对子/三张同点数」（最容易凑成牌型），
     * 再试其余点数；点数大的优先（更容易压过对手）。
     */
    private static int[] searchValidConcretization(int[] physical, List<Integer> laiziPos,
                                                   GameRules rules) {
        int[] base = physical.clone();
        List<Integer> cands = candidateRanks(base, rules);
        if (cands.isEmpty()) return null;

        int wild = laiziPos.size();
        // 组合数保护：候选多时限制搜索深度
        long combos = 1;
        for (int i = 0; i < wild; i++) {
            combos *= cands.size();
            if (combos > 4000) break;
        }
        List<Integer> use = cands;
        if (combos > 4000) {
            int keep = Math.max(1, (int) Math.floor(Math.pow(4000, 1.0 / wild)));
            use = cands.subList(0, Math.min(keep, cands.size()));
        }

        int[] idx = new int[wild];
        while (true) {
            int[] trial = base.clone();
            for (int i = 0; i < wild; i++) trial[laiziPos.get(i)] = use.get(idx[i]);
            Arrays.sort(trial);
            // 张数上限守卫 + 合法牌型判定
            if (withinRankLimits(trial)
                    && MoveDetector.typeOf(MoveDetector.getMoveType(trial)) != Types.TYPE_15_WRONG) {
                return trial;
            }
            int p = wild - 1;
            while (p >= 0) {
                idx[p]++;
                if (idx[p] < use.size()) break;
                idx[p] = 0;
                p--;
            }
            if (p < 0) break;
        }
        return null;
    }

    /**
     * 替代候选点数排序。
     *
     * 注意：**包含赖子点数本身** —— 赖子牌可以当自己用（打出一张 7 当 7）。
     * 之前把赖子点数排除，导致「12 张顺子含 1 张赖子」被判非法。
     */
    private static List<Integer> candidateRanks(int[] physical, GameRules rules) {
        Map<Integer, Integer> count = new LinkedHashMap<>();
        for (int c : physical) {
            if (!rules.isLaizi(c)) count.merge(c, 1, Integer::sum);
        }
        List<Integer> withPair = new ArrayList<>();
        List<Integer> rest = new ArrayList<>();
        for (int r : ALL) {
            // 大小王默认不可被赖子替代（受 laiziCanBeJoker 开关控制）
            if ((r == 20 || r == 30) && !rules.laiziCanBeJoker) continue;
            int have = count.getOrDefault(r, 0);
            if (have >= 2) withPair.add(r);
            else rest.add(r);
        }
        // 点数大的优先
        withPair.sort((a, b) -> Integer.compare(b, a));
        rest.sort((a, b) -> Integer.compare(b, a));
        List<Integer> out = new ArrayList<>(withPair);
        out.addAll(rest);
        return out;
    }

    /** 校验某点数不超过实际张数上限（普通牌 4 张、大小王各 1 张）。 */
    private static boolean withinRankLimits(int[] concrete) {
        int[] count = new int[31];
        for (int c : concrete) count[c]++;
        for (int c : concrete) {
            int max = (c == 20 || c == 30) ? 1 : 4;
            if (count[c] > max) return false;
        }
        return true;
    }

    /**
     * 把整手牌具体化，且尽量与给定动作集合兼容。
     *
     * 之所以需要「与动作兼容」：模型看到的 my_handcards 与实际要出的牌应当自洽，
     * 否则会出现「手牌里没有该点数却要出它」的失真输入。
     */
    public static int[] concretizeHand(int[] hand, GameRules rules, List<int[]> actions) {
        if (!rules.isLaiziMode()) return hand.clone();
        int wild = 0;
        for (int c : hand) if (rules.isLaizi(c)) wild++;
        if (wild == 0) return hand.clone();

        // 统计动作里各点数的最大需求，优先把赖子补到这些点数上
        int[] demand = new int[31];
        if (actions != null) {
            for (int[] a : actions) {
                int[] cnt = new int[31];
                for (int c : a) cnt[c]++;
                for (int r : ALL) demand[r] = Math.max(demand[r], cnt[r]);
            }
        }
        int[] count = new int[31];
        for (int c : hand) if (!rules.isLaizi(c)) count[c]++;

        int[] out = new int[hand.length];
        int p = 0;
        for (int c : hand) if (!rules.isLaizi(c)) out[p++] = c;

        for (int k = 0; k < wild; k++) {
            int best = -1, bestScore = -1;
            for (int r : ALL) {
                if (r == 20 || r == 30) continue;
                if (count[r] >= 4) continue;
                int score = demand[r] * 10 + count[r];
                if (score > bestScore) { bestScore = score; best = r; }
            }
            if (best < 0) best = 3;
            out[p++] = best;
            count[best]++;
        }
        Arrays.sort(out);
        return out;
    }
}
