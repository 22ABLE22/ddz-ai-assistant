package com.example.ddzoffline;

import com.example.ddzoffline.ai.CardInput;
import com.example.ddzoffline.ai.DdzFeatures;
import com.example.ddzoffline.ai.DdzModel;
import com.example.ddzoffline.ai.GameRules;
import com.example.ddzoffline.ai.InfoSet;
import com.example.ddzoffline.ai.LaiziMoves;
import com.example.ddzoffline.ai.MoveDetector;
import com.example.ddzoffline.ai.MonteCarloSearch;
import com.example.ddzoffline.ai.MoveGenerator;
import com.example.ddzoffline.ai.Tensor;
import com.example.ddzoffline.ai.Types;
import com.example.ddzoffline.ai.WeightSource;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手机端 DouZero 引擎 —— 完全离线，不依赖电脑、不联网。
 *
 * 与 PC 版 ddz_assistant.py 的行为保持一致：
 *   - 同样的合法出牌生成（move_generator / detector / selector）
 *   - 同样的特征编码（get_obs 三套分支）
 *   - 同样的模型权重（从 assets 读取，导出脚本见 tools/export_android.py）
 *
 * 模型按需加载：默认只载入 WP，切到 ADP 时才载入，避免一次性占用过多内存。
 */
public final class DdzEngine {

    public static final String LANDLORD = "landlord";
    public static final String UP = "landlord_up";
    public static final String DOWN = "landlord_down";

    private static final String[] POSITIONS = {LANDLORD, UP, DOWN};

    /** 推荐结果（含候选）。 */
    public static final class Candidate {
        public final String display;
        public final float score;
        public final int[] cards;        // 实际打出的手牌（含赖子）
        public final int[] concrete;     // 具体化后的牌（喂模型用）
        public final boolean best;
        public final int laiziUsed;

        public Candidate(String display, float score, int[] cards, boolean best) {
            this(display, score, cards, cards, best, 0);
        }

        public Candidate(String display, float score, int[] cards, int[] concrete,
                  boolean best, int laiziUsed) {
            this.display = display;
            this.score = score;
            this.cards = cards;
            this.concrete = concrete;
            this.best = best;
            this.laiziUsed = laiziUsed;
        }
    }

    public static final class Recommendation {
        public final String move;
        public final int[] cards;
        public final List<Candidate> candidates;

        Recommendation(String move, int[] cards, List<Candidate> candidates) {
            this.move = move;
            this.cards = cards;
            this.candidates = candidates;
        }
    }

    private final WeightSource weights;

    // 当前使用的目标（WP / ADP）
    private String objective = "WP";
    private final Map<String, DdzModel> loaded = new LinkedHashMap<>();

    /** 玩法规则。默认 CLASSIC —— 此时所有赖子分支都不会被触发。 */
    private GameRules rules = GameRules.CLASSIC;

    /** 上一次有效出牌的具体化版本（赖子模式用；经典模式等于 lastMove）。 */
    private int[] lastMoveConcrete = new int[0];

    // 对局状态（镜像 Python 的字段语义）
    private String myPosition;
    private final List<Integer> hand = new ArrayList<>();
    private final List<Integer> threeCards = new ArrayList<>();
    private final Map<String, List<Integer>> playedCards = new LinkedHashMap<>();
    private final Map<String, int[]> lastMoveDict = new LinkedHashMap<>();
    private final Map<String, Integer> numCardsLeft = new LinkedHashMap<>();
    private final List<int[]> cardPlayActionSeq = new ArrayList<>();
    private int[] lastMove = new int[0];
    private String lastPid = LANDLORD;
    private int bombNum;
    private String turn = LANDLORD;
    private boolean started;
    private boolean gameOver;

    // 撤销栈
    private final List<Snapshot> undoStack = new ArrayList<>();

    public DdzEngine(WeightSource weights) {
        this.weights = weights;
        reset();
    }

    // ---------------- 模型加载 ----------------

    /** 载入指定目标的三个位置模型（地主 + 上下家）。 */
    private void ensureModels() {
        if (loaded.size() == 3) return;
        for (String pos : POSITIONS) {
            String asset = "ddz_" + objective + "_" + pos + ".bin";
            float[] blob = readAssetFloats(asset);
            int inDim = DdzModel.inDimFor(pos);
            if (blob.length != DdzModel.expectedParamCount(inDim)) {
                throw new IllegalStateException(asset + " 参数个数异常：" + blob.length);
            }
            loaded.put(pos, DdzModel.fromBlob(blob, inDim));
        }
    }

    private float[] readAssetFloats(String name) {
        try {
            byte[] raw = weights.read(name);
            ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            float[] out = new float[raw.length / 4];
            for (int i = 0; i < out.length; i++) out[i] = bb.getFloat();
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("无法读取模型 " + name + "：" + e.getMessage(), e);
        }
    }

    /** 切换推荐模型（WP=胜率 / ADP=平均分差）。 */
    public void setObjective(String obj) {
        String key = obj == null ? "WP" : obj.trim().toUpperCase();
        if (!key.equals("WP") && !key.equals("ADP")) {
            throw new IllegalArgumentException("未知模型：" + obj);
        }
        if (!key.equals(objective)) {
            objective = key;
            loaded.clear();   // 释放旧权重，换载另一套
        }
    }

    public String getObjective() { return objective; }

    // ---------------- 玩法规则 ----------------

    /** 当前规则。 */
    public GameRules getRules() { return rules; }

    public boolean isLaiziMode() { return rules.isLaiziMode(); }

    /** 诊断：上一次有效出牌的具体化版本（赖子模式下用于校验与特征编码）。 */
    public int[] getLastMoveConcrete() { return lastMoveConcrete.clone(); }

    /**
     * 切换玩法。切换会清空当前对局状态 —— 因为手牌、赖子点数、
     * 已出牌记录在两种规则下不可混用。
     */
    public void setRules(GameRules newRules) {
        this.rules = newRules == null ? GameRules.CLASSIC : newRules;
        reset();
    }

    /** 便捷：按赖子点数开启赖子模式。 */
    public void enableLaizi(int laiziRank) {
        setRules(GameRules.laizi(laiziRank));
    }

    /** 切回经典模式。 */
    public void enableClassic() {
        setRules(GameRules.CLASSIC);
    }

    // ---------------- 对局 ----------------

    public void reset() {
        myPosition = null;
        hand.clear();
        threeCards.clear();
        playedCards.clear();
        lastMoveDict.clear();
        for (String p : POSITIONS) {
            playedCards.put(p, new ArrayList<>());
            lastMoveDict.put(p, new int[0]);
        }
        numCardsLeft.clear();
        numCardsLeft.put(LANDLORD, 20);
        numCardsLeft.put(UP, 17);
        numCardsLeft.put(DOWN, 17);
        cardPlayActionSeq.clear();
        lastMove = new int[0];
        lastMoveConcrete = new int[0];
        lastPid = LANDLORD;
        bombNum = 0;
        turn = LANDLORD;
        started = false;
        gameOver = false;
        undoStack.clear();
    }

    public boolean isStarted() { return started; }
    public boolean isGameOver() { return gameOver; }
    public String getMine() { return myPosition; }
    public String getTurn() { return turn; }
    public int handCount() { return hand.size(); }
    public String getHandText() { return Types.display(toIntArray(hand)); }
    public String getLastMoveText() {
        return lastMove.length == 0 ? "不出" : Types.display(lastMove);
    }
    public boolean isMyTurn() { return started && myPosition != null && turn.equals(myPosition); }
    public boolean canUndo() { return !undoStack.isEmpty(); }

    public String status() {
        String who = name(turn);
        if (myPosition != null && turn.equals(myPosition)) return "轮到你了 — 可点「推荐出牌」";
        return "当前出牌：" + who;
    }

    public void start(String position, String handText, String threeText) {
        List<Integer> initial = parseCards(handText);
        if (initial.size() != 17) {
            throw new IllegalArgumentException("起始手牌必须正好17张，当前 " + initial.size() + " 张");
        }
        List<Integer> three = parseCards(threeText);
        if (LANDLORD.equals(position)) {
            if (three.size() != 3) throw new IllegalArgumentException("地主必须输入3张底牌");
        } else if (!three.isEmpty() && three.size() != 3) {
            throw new IllegalArgumentException("底牌需为3张或留空");
        }

        // 校验通过后再清状态，避免开局失败破坏当前对局
        reset();
        myPosition = position;
        hand.clear();
        hand.addAll(initial);
        if (LANDLORD.equals(position)) hand.addAll(three);
        java.util.Collections.sort(hand);
        threeCards.clear();
        threeCards.addAll(three);
        numCardsLeft.put(position, hand.size());
        started = true;
        turn = LANDLORD;   // 斗地主固定由地主先出
    }

    /**
     * 记录某位置的出牌；若该位置是我，则等同于我出牌（扣手牌）。
     *
     * @param explicitConcrete 赖子模式下的具体化结果（来自推荐方案）。
     *                         传 null 时由引擎自行推导；非赖子模式忽略此参数。
     */
    public void record(String position, String cardsText) {
        record(position, cardsText, null);
    }

    public void record(String position, String cardsText, int[] explicitConcrete) {
        ensureStarted();
        if (!turn.equals(position)) {
            throw new IllegalArgumentException("当前应由" + name(turn) + "出牌");
        }
        List<Integer> cards = parseCards(cardsText);
        if (position.equals(myPosition)) {
            playMine(cards, explicitConcrete);
        } else {
            // 无论是否为空都要走规则校验：领出方不能「不出」
            if (!isLegalAgainst(cards, effectiveRival())) {
                throw new IllegalArgumentException(cards.isEmpty()
                        ? "现在是你领出，不能不出" : "这组牌不符合当前牌型");
            }
            int remaining = numCardsLeft.get(position) - cards.size();
            if (remaining < 0) throw new IllegalArgumentException("该玩家剩余牌数不足");
            pushUndo();
            numCardsLeft.put(position, remaining);
            int[] concrete = resolveConcrete(cards, explicitConcrete);
            applyMove(position, cards, concrete);
            advance(position);
        }
    }

    /**
     * 推导一组牌的具体化结果（赖子模式）。
     *
     * 优先级：
     *   1. 调用方显式给出（推荐方案里已算好的）→ 原样采用
     *   2. 否则按「与上家同型」尝试（能对上就用，保证压牌语义正确）
     *   3. 否则贪心补成合法牌型
     *
     * 注意：这里**不能**拿 lastMoveConcrete 当提示 —— 那是上家的牌，
     * 与本手要出的牌无关（曾经的缺陷）。
     */
    private int[] resolveConcrete(List<Integer> cards, int[] explicitConcrete) {
        if (!rules.isLaiziMode()) return null;
        int[] phys = toIntArray(cards);
        if (explicitConcrete != null && explicitConcrete.length == phys.length) {
            return explicitConcrete.clone();
        }
        int[] rival = effectiveRival();
        if (rival.length > 0) {
            int[] rivalConcrete = lastMoveConcrete.length == rival.length
                    ? lastMoveConcrete : LaiziMoves.concretize(rival, rules, null);
            int[] shaped = LaiziMoves.concretizeAgainst(phys, rules, rivalConcrete);
            if (shaped != null) return shaped;
        }
        return LaiziMoves.concretize(phys, rules, null);
    }

    private void playMine(List<Integer> cards) {
        playMine(cards, null);
    }

    private void playMine(List<Integer> cards, int[] explicitConcrete) {
        if (!cards.isEmpty() && !containsAll(hand, cards)) {
            throw new IllegalArgumentException("这组牌不在我的剩余手牌中");
        }
        // 无论是否为空都要走规则校验：领出方不能「不出」
        if (!isLegalAgainst(cards, effectiveRival())) {
            throw new IllegalArgumentException(cards.isEmpty()
                    ? "现在是你领出，不能不出" : "这组牌不符合当前牌型");
        }
        pushUndo();
        for (Integer c : cards) hand.remove(c);
        numCardsLeft.put(myPosition, hand.size());
        int[] concrete = resolveConcrete(cards, explicitConcrete);
        applyMove(myPosition, cards, concrete);
        if (hand.isEmpty()) {
            gameOver = true;
            started = false;
        } else {
            advance(myPosition);
        }
    }

    /** 更新 last_move / last_pid / bomb_num / 历史，与 Python 一致。 */
    private void applyMove(String position, List<Integer> cards) {
        applyMove(position, cards, null);
    }

    /**
     * @param concrete 赖子模式下该手牌的具体化版本（用于后续软炸判定与特征编码）；
     *                 经典模式传 null。
     */
    private void applyMove(String position, List<Integer> cards, int[] concrete) {
        int[] arr = toIntArray(cards);
        lastMoveDict.put(position, arr);
        cardPlayActionSeq.add(arr);
        playedCards.get(position).addAll(cards);
        // 炸弹计数：经典按牌值判断；赖子模式下由 concrete 判断（软炸也算炸）
        int[] forBomb = concrete != null ? concrete : arr;
        if (forBomb.length == 4 && forBomb[0] == forBomb[3]) bombNum++;
        else if (forBomb.length == 2 && forBomb[0] == 20 && forBomb[1] == 30) bombNum++;
        if (arr.length > 0) {
            lastMove = arr;
            lastPid = position;
            lastMoveConcrete = concrete != null ? concrete.clone() : arr.clone();
        }
    }

    /**
     * 用于过滤的“上家牌”：
     * 若上一手有效出牌是我自己，说明另外两家都 pass，我可以自由领出。
     */
    private int[] effectiveRival() {
        if (myPosition != null && myPosition.equals(lastPid)) return new int[0];
        return lastMove;
    }

    // ---------------- 推荐 ----------------

    /**
     * 生成推荐。
     *
     * 经典模式：完全走移植前的路径（{@link DdzFeatures#legalActionsFrom}），
     * 行为与黄金向量逐位一致。
     *
     * 赖子模式：走 {@link LaiziMoves} 生成动作，每个动作带 physical/concrete 两套表示；
     * 用 concrete 喂 DouZero 打分，返回时用 physical 扣手牌与显示。
     *
     * 注意：DouZero 从未训练过赖子玩法，因此赖子模式的打分是
     * 「把赖子具体化后套用经典模型」的**近似**，不是重新训练的结果。
     */
    public Recommendation recommend() {
        return recommend(4);
    }

    /**
     * @param topK 返回的候选数量（搜索时用得上：候选越多，搜索面越广）
     */
    public Recommendation recommend(int topK) {
        ensureStarted();
        if (!isMyTurn()) throw new IllegalArgumentException("还没轮到你出牌");
        ensureModels();

        if (rules.isLaiziMode()) return recommendLaizi(topK);

        InfoSet is = buildInfoSet();
        List<int[]> legal = DdzFeatures.legalActionsFrom(is);
        if (legal.isEmpty()) throw new IllegalStateException("当前没有合法出牌");
        is.legalActions = legal;

        DdzFeatures.Encoded enc = DdzFeatures.encode(is);
        Tensor x = new Tensor(legal.size(), enc.inDim, enc.xBatch);
        float[] values = loaded.get(myPosition).forward(enc.zBatch, x);

        // 排序取前若干个候选
        Integer[] order = new Integer[values.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        final float[] v = values;
        Arrays.sort(order, (a, b) -> Float.compare(v[b], v[a]));

        List<Candidate> cands = new ArrayList<>();
        int k = Math.min(Math.max(1, topK), order.length);
        for (int j = 0; j < k; j++) {
            int idx = order[j];
            int[] cards = legal.get(idx);
            cands.add(new Candidate(
                    cards.length == 0 ? "不出" : Types.display(cards),
                    values[idx], cards, j == 0));
        }

        int[] best = legal.get(order[0]);
        return new Recommendation(best.length == 0 ? "不出" : Types.display(best), best, cands);
    }

    // ---------------- 赖子模式推荐 ----------------

    /**
     * 赖子模式的推荐。
     *
     * 做法：用 LaiziMoves 生成全部可出动作（不依赖对赖子做有限展开，故不漏招），
     * 把每个动作的 concrete（赖子已具体化）编码成特征，交给同一个 DouZero 模型打分。
     * 返回给界面的仍是 physical（真实手牌），这样扣牌与显示都正确。
     *
     * 这是**近似**：模型没见过赖子玩法，只是在「具体化后的牌面」上评估。
     */
    private Recommendation recommendLaizi() {
        return recommendLaizi(4);
    }

    /**
     * 赖子模式推荐。
     *
     * @param topK 返回的候选数量；传 0 表示返回全部合法动作（用于诊断/对照）
     */
    public Recommendation recommendLaizi(int topK) {
        int[] rival = effectiveRival();
        LaiziMoves.Move rivalMove = rivalMoveFromHistory(rival);
        boolean passAllowed = rival.length > 0;

        LaiziMoves lm = new LaiziMoves(toIntArray(hand), rules);
        List<LaiziMoves.Move> legal = lm.legalAgainst(rivalMove, passAllowed);
        if (legal.isEmpty()) throw new IllegalStateException("当前没有合法出牌");

        // 用 concrete 构造 InfoSet：手牌也用具体化后的版本，
        // 否则模型看到的仍是赖子牌值，等于喂了没见过的输入。
        List<int[]> concretes = new ArrayList<>(legal.size());
        for (LaiziMoves.Move m : legal) concretes.add(m.concrete);

        InfoSet is = buildInfoSetFor(concretes);
        List<int[]> legalList = new ArrayList<>(concretes);
        is.legalActions = legalList;

        DdzFeatures.Encoded enc = DdzFeatures.encode(is);
        Tensor x = new Tensor(legalList.size(), enc.inDim, enc.xBatch);
        float[] values = loaded.get(myPosition).forward(enc.zBatch, x);

        Integer[] order = new Integer[values.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        final float[] v = values;
        Arrays.sort(order, (a, b) -> Float.compare(v[b], v[a]));

        List<Candidate> cands = new ArrayList<>();
        int k = (topK <= 0) ? order.length : Math.min(topK, order.length);
        for (int j = 0; j < k; j++) {
            int i = order[j];
            LaiziMoves.Move m = legal.get(i);
            cands.add(new Candidate(
                    LaiziMoves.describe(m, rules),
                    values[i], m.physical, m.concrete, j == 0, m.laiziUsed));
        }
        LaiziMoves.Move best = legal.get(order[0]);
        return new Recommendation(LaiziMoves.describe(best, rules), best.physical, cands);
    }

    /** 当前局面的全部合法动作及其模型打分（诊断用）。 */
    public List<Candidate> scoreAllLaiziMoves() {
        ensureStarted();
        ensureModels();
        if (!rules.isLaiziMode()) throw new IllegalStateException("仅赖子模式可用");
        return recommendLaizi(0).candidates;
    }

    // ---------------- 蒙特卡洛深度搜索 ----------------

    /** 深度搜索的结果。 */
    public static final class SearchResult {
        public final String move;
        public final int[] cards;
        public final int[] concrete;
        public final List<MonteCarloSearch.Row> rows;
        public final MonteCarloSearch.Row best;
        public final int sims;
        public final long millis;
        /** 单步打分的首选，用于对比「搜索是否改变了结论」。 */
        public final String baselineMove;
        public final double baselineScore;

        SearchResult(String move, int[] cards, int[] concrete,
                     List<MonteCarloSearch.Row> rows, MonteCarloSearch.Row best,
                     int sims, long millis,
                     String baselineMove, double baselineScore) {
            this.move = move;
            this.cards = cards;
            this.concrete = concrete;
            this.rows = rows;
            this.best = best;
            this.sims = sims;
            this.millis = millis;
            this.baselineMove = baselineMove;
            this.baselineScore = baselineScore;
        }

        /** 搜索是否推翻了单步打分的结论。 */
        public boolean changedDecision() {
            return !move.equals(baselineMove);
        }
    }

    /** 导出搜索所需的局面快照。 */
    public MonteCarloSearch.Snapshot snapshot() {
        ensureStarted();
        MonteCarloSearch.Snapshot s = new MonteCarloSearch.Snapshot();
        s.myPos = myPosition;
        s.myHand = toIntArray(hand);
        s.threeCards = toIntArray(threeCards);
        s.playedCards = new LinkedHashMap<>();
        for (String p : POSITIONS) s.playedCards.put(p, new ArrayList<>(playedCards.get(p)));
        s.lastMoveDict = new LinkedHashMap<>();
        for (String p : POSITIONS) s.lastMoveDict.put(p, lastMoveDict.get(p).clone());
        s.actionSeq = new ArrayList<>(cardPlayActionSeq);
        s.lastMove = lastMove.clone();
        s.lastPid = lastPid;
        s.bombNum = bombNum;
        s.turn = turn;
        s.numLeft = new LinkedHashMap<>(numCardsLeft);
        return s;
    }

    /**
     * 蒙特卡洛深度搜索：在单步打分的候选上做仿真，用「胜率」而非「单步价值」选牌。
     *
     * <p>单步打分回答「这一手模型觉得值多少」；搜索回答「这一手之后把牌打完，
     * 我赢的概率是多少」。后者能处理「赖子该不该现在用掉」这类跨步决策。
     *
     * @param topK     参与搜索的候选数量
     * @param sims     每个候选的仿真次数
     * @param wpWeight 融合权重（1.0 = 纯胜率，0.0 = 纯分差）
     * @param threads  并行线程数（1 = 单线程）
     */
    public SearchResult deepSearch(int topK, int sims, double wpWeight, int threads) {
        ensureStarted();
        if (!isMyTurn()) throw new IllegalArgumentException("还没轮到你出牌");
        ensureModels();

        Recommendation base = recommend(topK);
        MonteCarloSearch.Result r = MonteCarloSearch.search(
                rules, snapshot(), base.candidates, loaded, sims, wpWeight,
                System.nanoTime(), threads);

        MonteCarloSearch.Row best = r.best;
        return new SearchResult(
                best.display, best.move, best.concrete, r.rows, best, r.sims, r.millis,
                base.move, base.candidates.isEmpty() ? 0f : base.candidates.get(0).score);
    }

    /** 把上家的 concrete 牌还原成带 laiziUsed 信息的 Move，供软炸判定使用。 */
    private LaiziMoves.Move rivalMoveFromHistory(int[] rival) {
        if (rival == null || rival.length == 0) return null;
        // 历史里记录的是 physical（含赖子），laiziUsed 可由其中赖子张数直接数出
        int used = 0;
        for (int c : rival) if (rules.isLaizi(c)) used++;
        int[] concrete = LaiziMoves.concretize(rival, rules, lastMoveConcrete);
        return LaiziMoves.Move.of(rival.clone(), concrete, used);
    }

    /**
     * 构造用于打分的 InfoSet（赖子模式）。
     * 与 {@link #buildInfoSet} 的区别：手牌与合法动作都替换为具体化版本。
     */
    private InfoSet buildInfoSetFor(List<int[]> concretes) {
        InfoSet base = buildInfoSet();
        // 手牌具体化：赖子按「不影响牌型」的方式替换（用同点数占位）
        int[] concreteHand = LaiziMoves.concretizeHand(toIntArray(hand), rules, concretes);
        base.playerHandCards = concreteHand;

        // 对手手牌并集需要重算：已知牌里的赖子已被具体化，数量保持一致
        List<Integer> known = new ArrayList<>();
        for (int c : concreteHand) known.add(c);
        for (String p : POSITIONS) known.addAll(playedCards.get(p));
        known.addAll(threeCards);
        List<Integer> others = new ArrayList<>();
        int[] ranks = {3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 17, 20, 30};
        for (int r : ranks) {
            int max = (r == 20 || r == 30) ? 1 : 4;
            int have = 0;
            for (int c : known) if (c == r) have++;
            for (int i = 0; i < max - have; i++) others.add(r);
        }
        base.otherHandCards = toIntArray(others);
        return base;
    }

    // ---------------- 记牌器 ----------------
    /** 各点数尚未出现的张数，键为显示牌面。 */
    public Map<String, Integer> unseenCounts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        Map<Integer, Integer> seen = new LinkedHashMap<>();
        for (int c : hand) seen.merge(c, 1, Integer::sum);
        for (String p : POSITIONS) {
            for (int c : playedCards.get(p)) seen.merge(c, 1, Integer::sum);
        }
        int[] ranks = {3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 17, 20, 30};
        for (int r : ranks) {
            int total = (r == 20 || r == 30) ? 1 : 4;
            int left = total - seen.getOrDefault(r, 0);
            out.put(Types.label(r), Math.max(0, left));
        }
        return out;
    }

    // ---------------- 撤销 ----------------

    private static final class Snapshot {
        List<Integer> hand;
        Map<String, List<Integer>> played;
        Map<String, int[]> lastMoveDict;
        Map<String, Integer> numCardsLeft;
        List<int[]> seq;
        int[] lastMove;
        int[] lastMoveConcrete;
        String lastPid;
        int bombNum;
        String turn;
        boolean gameOver;
        boolean started;
    }

    private void pushUndo() {
        Snapshot s = new Snapshot();
        s.hand = new ArrayList<>(hand);
        s.played = new LinkedHashMap<>();
        for (Map.Entry<String, List<Integer>> e : playedCards.entrySet()) {
            s.played.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        s.lastMoveDict = new LinkedHashMap<>(lastMoveDict);
        s.numCardsLeft = new LinkedHashMap<>(numCardsLeft);
        s.seq = new ArrayList<>(cardPlayActionSeq);
        s.lastMove = lastMove.clone();
        s.lastMoveConcrete = lastMoveConcrete.clone();
        s.lastPid = lastPid;
        s.bombNum = bombNum;
        s.turn = turn;
        s.gameOver = gameOver;
        s.started = started;
        undoStack.add(s);
        if (undoStack.size() > 200) undoStack.remove(0);
    }

    public boolean undo() {
        if (undoStack.isEmpty()) return false;
        Snapshot s = undoStack.remove(undoStack.size() - 1);
        hand.clear();
        hand.addAll(s.hand);
        playedCards.clear();
        playedCards.putAll(s.played);
        lastMoveDict.clear();
        lastMoveDict.putAll(s.lastMoveDict);
        numCardsLeft.clear();
        numCardsLeft.putAll(s.numCardsLeft);
        cardPlayActionSeq.clear();
        cardPlayActionSeq.addAll(s.seq);
        lastMove = s.lastMove;
        lastMoveConcrete = s.lastMoveConcrete;
        lastPid = s.lastPid;
        bombNum = s.bombNum;
        turn = s.turn;
        gameOver = s.gameOver;
        started = s.started;
        return true;
    }

    // ---------------- 规则 ----------------

    /**
     * 结构化合法性判断：只依赖牌型本身，不依赖“谁的手牌”。
     *
     * 这是必需的 —— 记录对手出牌时我们并不知道对手手牌，用我方手牌去生成
     * 合法动作会得出错误结论。因此这里按牌型规则判断：
     *   - 空牌（不出）：仅当上家确实出过牌时合法
     *   - 非空：必须是合法牌型，且能压过上家（同型同长更大，或炸弹/王炸）
     *
     * 赖子模式：走 LaiziMoves，把含赖子的牌先具体化再判定，
     * 并保留「软炸 < 硬炸」的次序。
     */
    private boolean isLegalAgainst(List<Integer> cards, int[] previous) {
        int[] arr = toIntArray(cards);
        int[] prev = previous == null ? new int[0] : previous;

        if (arr.length == 0) {
            // 领出方不能不出
            return prev.length > 0;
        }

        if (rules.isLaiziMode()) {
            return isLegalLaizi(arr, prev);
        }

        Map<String, Object> my = MoveDetector.getMoveType(arr);
        int myType = MoveDetector.typeOf(my);
        if (myType == Types.TYPE_15_WRONG) return false;

        if (prev.length == 0) return true;   // 自由领出，任意合法牌型

        Map<String, Object> up = MoveDetector.getMoveType(prev);
        int upType = MoveDetector.typeOf(up);

        boolean upBomb = upType == Types.TYPE_4_BOMB || upType == Types.TYPE_5_KING_BOMB;

        // 王炸最大，炸弹次之，其余必须同型同长比大小
        if (myType == Types.TYPE_5_KING_BOMB) return true;
        if (upType == Types.TYPE_5_KING_BOMB) return false;
        if (myType == Types.TYPE_4_BOMB) return upType != Types.TYPE_4_BOMB
                || MoveDetector.rankOf(my) > MoveDetector.rankOf(up);
        if (upBomb) return false;

        if (myType != upType) return false;
        if (arr.length != prev.length) return false;
        return MoveDetector.rankOf(my) > MoveDetector.rankOf(up);
    }

    /**
     * 赖子模式的合法性判定。
     *
     * 做法：把待判定的 physical 与上家牌都具体化，再用 LaiziMoves.beats 比较。
     * 具体化时优先对齐上家的点数形态（同型同长），这样「用赖子补成能压的牌」才判得出来。
     */
    private boolean isLegalLaizi(int[] physical, int[] prev) {
        if (prev.length == 0) {
            // 自由领出：只要存在一种具体化能构成合法牌型即可。
            // 复用 LaiziMoves.concretize 的搜索逻辑（它允许赖子当自己用），
            // 而不是 GameRules.expand（后者排除了赖子点数，会把
            // 「12 张顺子含 1 张赖子」这类合法牌误判为非法）。
            int[] c = LaiziMoves.concretize(physical, rules, null);
            if (MoveDetector.typeOf(MoveDetector.getMoveType(c)) == Types.TYPE_15_WRONG) {
                return false;
            }
            // 还要确认具体化没有偷偷改变真实牌（张数守恒已由 concretize 保证）
            return true;
        }

        int[] prevConcrete = lastMoveConcrete.length == prev.length
                ? lastMoveConcrete : LaiziMoves.concretize(prev, rules, null);

        // 按上家牌型生成「恰好同型同长」的候选具体化，逐一试压
        int[] target = LaiziMoves.concretizeAgainst(physical, rules, prevConcrete);
        if (target == null) return false;
        int used = 0;
        for (int c : physical) if (rules.isLaizi(c)) used++;
        LaiziMoves.Move mine = LaiziMoves.Move.of(physical.clone(), target, used);
        int prevUsed = 0;
        for (int c : prev) if (rules.isLaizi(c)) prevUsed++;
        LaiziMoves.Move rival = LaiziMoves.Move.of(prev.clone(), prevConcrete, prevUsed);
        return new LaiziMoves(toIntArray(hand), rules).beats(mine, rival);
    }

    private InfoSet buildInfoSet() {
        InfoSet is = new InfoSet(myPosition);
        is.playerHandCards = toIntArray(hand);
        is.lastMove = effectiveRival();
        is.lastPid = lastPid;
        is.bombNum = bombNum;
        is.cardPlayActionSeq = new ArrayList<>(cardPlayActionSeq);

        // 对手手牌并集：54 张减去所有已知牌
        List<Integer> known = new ArrayList<>(hand);
        for (String p : POSITIONS) known.addAll(playedCards.get(p));
        known.addAll(threeCards);
        List<Integer> others = new ArrayList<>();
        int[] ranks = {3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 17, 20, 30};
        for (int r : ranks) {
            int max = (r == 20 || r == 30) ? 1 : 4;
            int have = 0;
            for (int c : known) if (c == r) have++;
            for (int i = 0; i < max - have; i++) others.add(r);
        }
        is.otherHandCards = toIntArray(others);

        for (String p : POSITIONS) {
            is.playedCards.put(p, toIntArray(playedCards.get(p)));
            is.lastMoveDict.put(p, lastMoveDict.get(p));
        }
        is.numCardsLeftDict = new LinkedHashMap<>(numCardsLeft);
        return is;
    }

    private void advance(String position) {
        turn = LANDLORD.equals(position) ? DOWN : (DOWN.equals(position) ? UP : LANDLORD);
    }

    private void ensureStarted() {
        if (!started && !gameOver) throw new IllegalStateException("请先开始对局");
        if (myPosition == null) throw new IllegalStateException("请先开始对局");
    }

    public static String name(String position) {
        return LANDLORD.equals(position) ? "地主" : (DOWN.equals(position) ? "下家" : "上家");
    }

    // ---------------- 牌面解析 ----------------

    /**
     * 牌面解析。
     *
     * 统一委托给 {@link CardInput}，使「点选录入」与「文本录入」共用同一套规则，
     * 避免两条路径产生分歧。支持紧凑串、空格/逗号分隔、区间（3-7）、
     * 数量后缀（J*3）等写法。
     */
    public static List<Integer> parseCards(String text) {
        CardInput.Parsed p = CardInput.parse(text);
        if (!p.ok()) {
            throw new IllegalArgumentException(String.join("；", p.errors));
        }
        return p.cards;
    }

    private static int[] toIntArray(List<Integer> l) {
        int[] o = new int[l.size()];
        for (int i = 0; i < o.length; i++) o[i] = l.get(i);
        return o;
    }

    private static boolean containsAll(List<Integer> source, List<Integer> wanted) {
        List<Integer> copy = new ArrayList<>(source);
        for (Integer c : wanted) if (!copy.remove(c)) return false;
        return true;
    }
}
