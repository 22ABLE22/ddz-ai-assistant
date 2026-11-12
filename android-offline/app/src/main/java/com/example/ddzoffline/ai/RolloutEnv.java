package com.example.ddzoffline.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自对弈推演环境 —— 蒙特卡洛搜索的内核。
 *
 * <p>与 {@link com.example.ddzoffline.DdzEngine} 的区别：这里三家手牌**全部已知**
 * （rollout 需要），但状态机、合法着生成、InfoSet 构造全部复用与正式推理
 * 相同的代码路径，保证「搜索里的模型判断」与「界面上的推荐」语义一致。
 *
 * <p>状态更新严格对齐 DdzEngine.applyMove / advance：
 * <ul>
 *   <li>出牌：lastMoveDict[pos]=cards、seq+=cards、played[pos]+=cards、lastMove/lastPid 更新</li>
 *   <li>不出：lastMoveDict[pos]=[]、seq+=[]、lastMove/lastPid 保持不变</li>
 *   <li>轮转：landlord → landlord_down → landlord_up → landlord</li>
 * </ul>
 */
public final class RolloutEnv {

    public static final String LL = "landlord";
    public static final String UP = "landlord_up";
    public static final String DOWN = "landlord_down";
    public static final String[] POS = {LL, UP, DOWN};

    private static final int[] RANKS = {3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 17, 20, 30};

    /** 决策者：给定位置与环境，返回要打的牌（空数组 = 不出）。 */
    public interface Agent {
        int[] act(String pos, RolloutEnv env);
    }

    private final GameRules rules;
    private final Map<String, List<Integer>> hands = new LinkedHashMap<>();
    private final Map<String, List<Integer>> played = new LinkedHashMap<>();
    private final Map<String, int[]> lastMoveDict = new LinkedHashMap<>();
    private final List<int[]> seq = new ArrayList<>();
    private final int[] threeCards;

    private int[] lastMove = new int[0];
    private int[] lastMoveConcrete = new int[0];
    private String lastPid;
    private String turn;
    private int bombNum;
    private boolean over;
    private String winner;

    public RolloutEnv(GameRules rules,
                      Map<String, List<Integer>> hands,
                      int[] threeCards,
                      Map<String, List<Integer>> played,
                      Map<String, int[]> lastMoveDict,
                      List<int[]> seq,
                      int[] lastMove,
                      String lastPid,
                      int bombNum,
                      String turn) {
        this.rules = rules;
        for (String p : POS) {
            this.hands.put(p, new ArrayList<>(hands.get(p)));
            this.played.put(p, new ArrayList<>(played.get(p)));
            int[] lm = lastMoveDict.get(p);
            this.lastMoveDict.put(p, lm == null ? new int[0] : lm.clone());
        }
        this.threeCards = threeCards == null ? new int[0] : threeCards.clone();
        if (seq != null) for (int[] a : seq) this.seq.add(a.clone());
        this.lastMove = lastMove == null ? new int[0] : lastMove.clone();
        this.lastMoveConcrete = this.lastMove.clone();
        this.lastPid = lastPid;
        this.bombNum = bombNum;
        this.turn = turn;
        checkOver();
    }

    // ---------------- 查询 ----------------

    public String turn() { return turn; }
    public boolean isOver() { return over; }
    public String winner() { return winner; }
    public int handCount(String pos) { return hands.get(pos).size(); }
    public List<Integer> handOf(String pos) { return hands.get(pos); }
    public int[] lastMove() { return lastMove.clone(); }
    public String lastPid() { return lastPid; }
    public int bombNum() { return bombNum; }

    /** DouZero 口径：地主赢 {landlord:+2, farmer:-1}，农民赢 {landlord:-2, farmer:+1}。 */
    public double utilityFor(String pos) {
        if (!over) return 0.0;
        boolean landlordWin = LL.equals(winner);
        if (LL.equals(pos)) return landlordWin ? 2.0 : -2.0;
        return landlordWin ? -1.0 : 1.0;
    }

    /** 把 utility 压到约 [0,1]，便于与胜率加权（对齐 ddz_search.py）。 */
    public double normalizedUtility(String pos) {
        double u = utilityFor(pos);
        if (LL.equals(pos)) return clamp((u + 2.0) / 4.0);
        return clamp((u + 1.0) / 2.0);
    }

    private static double clamp(double v) { return Math.max(0.0, Math.min(1.0, v)); }

    /** 某位置的「上家牌」：若上一手是我自己，说明另两家都过了，可自由领出。 */
    public int[] effectiveRivalFor(String pos) {
        if (pos.equals(lastPid)) return new int[0];
        return lastMove;
    }

    // ---------------- 合法着 ----------------

    /** 该位置的合法动作对象（physical + concrete 两套表示）。 */
    public List<LaiziMoves.Move> legalMoveObjects(String pos) {
        List<LaiziMoves.Move> out = new ArrayList<>();
        if (over) return out;

        if (rules.isLaiziMode()) {
            int[] rival = effectiveRivalFor(pos);
            boolean passAllowed = rival.length > 0;
            LaiziMoves.Move rivalMove = passAllowed ? laiziMoveFrom(rival) : null;
            LaiziMoves lm = new LaiziMoves(toArr(hands.get(pos)), rules);
            return lm.legalAgainst(rivalMove, passAllowed);
        }

        // 经典模式：concrete 与 physical 相同
        for (int[] a : DdzFeatures.legalActionsFrom(buildInfoSetFor(pos, null))) {
            out.add(LaiziMoves.Move.of(a, a.clone(), 0));
        }
        return out;
    }

    /** 该位置的合法动作（physical）。空数组表示「不出」。 */
    public List<int[]> legalActions(String pos) {
        List<int[]> out = new ArrayList<>();
        for (LaiziMoves.Move m : legalMoveObjects(pos)) out.add(m.physical);
        return out;
    }

    private LaiziMoves.Move laiziMoveFrom(int[] cards) {
        int used = 0;
        for (int c : cards) if (rules.isLaizi(c)) used++;
        int[] concrete = lastMoveConcrete.length == cards.length
                ? lastMoveConcrete.clone()
                : LaiziMoves.concretize(cards, rules, null);
        return LaiziMoves.Move.of(cards.clone(), concrete, used);
    }

    // ---------------- 状态推进 ----------------

    /** 应用一手牌（空数组 = 不出），对齐 DdzEngine.applyMove + advance。 */
    public void apply(String pos, int[] cards) {
        if (over) return;
        if (cards == null) cards = new int[0];

        if (cards.length > 0) {
            List<Integer> h = hands.get(pos);
            for (int c : cards) {
                // 按值移除一张
                for (int i = 0; i < h.size(); i++) {
                    if (h.get(i) == c) { h.remove(i); break; }
                }
            }
            int[] arr = cards.clone();
            lastMoveDict.put(pos, arr);
            seq.add(arr);
            for (int c : cards) played.get(pos).add(c);

            // 炸弹计数：与 DdzEngine 一致（赖子模式按具体化判断）
            int[] forBomb = rules.isLaiziMode()
                    ? LaiziMoves.concretize(arr, rules, null) : arr;
            if (forBomb.length == 4 && forBomb[0] == forBomb[3]) bombNum++;
            else if (forBomb.length == 2 && forBomb[0] == 20 && forBomb[1] == 30) bombNum++;

            lastMove = arr;
            lastPid = pos;
            lastMoveConcrete = forBomb;
        } else {
            lastMoveDict.put(pos, new int[0]);
            seq.add(new int[0]);
        }

        if (hands.get(pos).isEmpty()) {
            over = true;
            winner = pos;
            return;
        }
        turn = next(pos);
    }

    public static String next(String pos) {
        if (LL.equals(pos)) return DOWN;
        if (DOWN.equals(pos)) return UP;
        return LL;
    }

    /** 推进一步：当前行动者按 agent 决策。返回其打出的牌。 */
    public int[] step(Map<String, Agent> agents) {
        if (over) return new int[0];
        Agent a = agents.get(turn);
        List<int[]> legal = legalActions(turn);
        int[] chosen = null;
        if (a != null) {
            int[] want = a.act(turn, this);
            if (want != null && containsAction(legal, want)) chosen = want;
        }
        if (chosen == null) chosen = legal.isEmpty() ? new int[0] : legal.get(0);
        String who = turn;
        apply(who, chosen);
        return chosen;
    }

    /** 一直推到终局。返回实际步数。 */
    public int runOut(Map<String, Agent> agents, int maxPlies) {
        int n = 0;
        while (!over && n < maxPlies) {
            step(agents);
            n++;
        }
        return n;
    }

    private static boolean containsAction(List<int[]> legal, int[] want) {
        for (int[] l : legal) {
            if (l.length != want.length) continue;
            int[] a = l.clone(), b = want.clone();
            java.util.Arrays.sort(a);
            java.util.Arrays.sort(b);
            if (java.util.Arrays.equals(a, b)) return true;
        }
        return false;
    }

    private void checkOver() {
        for (String p : POS) {
            if (hands.get(p).isEmpty()) { over = true; winner = p; return; }
        }
    }

    // ---------------- InfoSet 构造 ----------------

    /**
     * 构造某位置的 InfoSet —— 与 DdzEngine.buildInfoSet / buildInfoSetFor 同构，
     * 保证特征编码与正式推理逐位一致。
     */
    public InfoSet buildInfoSet(String pos) {
        List<int[]> concretes = null;
        if (rules.isLaiziMode()) {
            concretes = new ArrayList<>();
            for (LaiziMoves.Move m : legalMoveObjects(pos)) concretes.add(m.concrete);
        }
        return buildInfoSetFor(pos, concretes);
    }

    /**
     * @param concretes 赖子模式下用于具体化手牌的候选（通常是该位置的合法 concrete）；
     *                  经典模式传 null。
     */
    public InfoSet buildInfoSetFor(String pos, List<int[]> concretes) {
        InfoSet is = new InfoSet(pos);
        is.lastPid = lastPid;
        is.bombNum = bombNum;
        is.cardPlayActionSeq = new ArrayList<>(seq);
        is.lastMove = effectiveRivalFor(pos);

        int[] handArr = toArr(hands.get(pos));
        if (rules.isLaiziMode() && concretes != null) {
            handArr = LaiziMoves.concretizeHand(handArr, rules, concretes);
        }
        is.playerHandCards = handArr;

        // 对手手牌并集 = 54 张减去所有已知牌（与 DdzEngine 同算法）
        List<Integer> known = new ArrayList<>();
        for (int c : handArr) known.add(c);
        for (String p : POS) known.addAll(played.get(p));
        for (int c : threeCards) known.add(c);
        List<Integer> others = new ArrayList<>();
        for (int r : RANKS) {
            int max = (r == 20 || r == 30) ? 1 : 4;
            int have = 0;
            for (int c : known) if (c == r) have++;
            for (int i = 0; i < max - have; i++) others.add(r);
        }
        is.otherHandCards = toArr(others);

        for (String p : POS) {
            is.playedCards.put(p, toArr(played.get(p)));
            is.lastMoveDict.put(p, lastMoveDict.get(p).clone());
            is.numCardsLeftDict.put(p, hands.get(p).size());
        }
        return is;
    }

    static int[] toArr(List<Integer> l) {
        int[] o = new int[l.size()];
        for (int i = 0; i < o.length; i++) o[i] = l.get(i);
        return o;
    }
}
