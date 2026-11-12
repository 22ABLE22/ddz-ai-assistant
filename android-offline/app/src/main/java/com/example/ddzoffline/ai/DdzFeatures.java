package com.example.ddzoffline.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 特征编码 —— 逐函数移植自 DouZero douzero/env/env.py 的 get_obs 三套分支。
 *
 * 关键点：
 *  - _cards2array 输出 54 维，其中 4x13 部分按 Fortran 序（列优先）展平
 *  - 历史出牌取最后 15 手，不足补空，再 reshape 成 (5,162)
 *  - 三个位置的 x_batch 拼接顺序各不相同，必须逐一对应
 */
public final class DdzFeatures {

    private static final int[] CARD2COLUMN = new int[18];
    static {
        CARD2COLUMN[3] = 0; CARD2COLUMN[4] = 1; CARD2COLUMN[5] = 2; CARD2COLUMN[6] = 3;
        CARD2COLUMN[7] = 4; CARD2COLUMN[8] = 5; CARD2COLUMN[9] = 6; CARD2COLUMN[10] = 7;
        CARD2COLUMN[11] = 8; CARD2COLUMN[12] = 9; CARD2COLUMN[13] = 10; CARD2COLUMN[14] = 11;
        CARD2COLUMN[17] = 12;
    }

    /** 结果容器。 */
    public static final class Encoded {
        public int[] xNoAction;
        public float[] xBatch;
        public float[] zBatch;
        public int nLegal;
        public int inDim;
    }

    // ---------- 基础编码 ----------
    /** 对应 _cards2array：返回 54 维 int8。 */
    public static int[] cards2Array(int[] cards) {
        int[] out = new int[54];
        if (cards.length == 0) return out;

        int[][] matrix = new int[4][13];
        int[] jokers = new int[2];
        int[] counts = new int[31];
        for (int c : cards) counts[c]++;

        for (int c : cards) {
            if (counts[c] == 0) continue;
        }
        // 每个点数只处理一次
        boolean[] seen = new boolean[31];
        for (int card : cards) {
            if (seen[card]) continue;
            seen[card] = true;
            int n = counts[card];
            if (card < 20) {
                int col = CARD2COLUMN[card];
                int[] ones = numOnes2Array(n);
                for (int row = 0; row < 4; row++) matrix[row][col] = ones[row];
            } else if (card == 20) {
                jokers[0] = 1;
            } else if (card == 30) {
                jokers[1] = 1;
            }
        }

        // numpy matrix.flatten('F')：列优先
        int p = 0;
        for (int col = 0; col < 13; col++) {
            for (int row = 0; row < 4; row++) out[p++] = matrix[row][col];
        }
        out[52] = jokers[0];
        out[53] = jokers[1];
        return out;
    }

    private static int[] numOnes2Array(int n) {
        switch (n) {
            case 0: return new int[]{0, 0, 0, 0};
            case 1: return new int[]{1, 0, 0, 0};
            case 2: return new int[]{1, 1, 0, 0};
            case 3: return new int[]{1, 1, 1, 0};
            default: return new int[]{1, 1, 1, 1};
        }
    }

    /** 对应 _get_one_hot_array。 */
    static float[] oneHotArray(int numLeftCards, int maxNumCards) {
        float[] oneHot = new float[maxNumCards];
        oneHot[numLeftCards - 1] = 1;
        return oneHot;
    }

    /** 对应 _get_one_hot_bomb。 */
    static float[] oneHotBomb(int bombNum) {
        float[] oneHot = new float[15];
        oneHot[bombNum] = 1;
        return oneHot;
    }

    /** 对应 _process_action_seq：取最后 15 手，前面补空。 */
    static List<int[]> processActionSeq(List<int[]> sequence, int length) {
        List<int[]> seq = new ArrayList<>();
        int from = Math.max(0, sequence.size() - length);
        for (int i = 0; i < length - (sequence.size() - from); i++) seq.add(new int[0]);
        for (int i = from; i < sequence.size(); i++) seq.add(sequence.get(i));
        return seq;
    }

    /** 对应 _action_seq_list2array：输出 (5,162) 行主序。 */
    static float[] actionSeqList2Array(List<int[]> actionSeqList) {
        int rows = actionSeqList.size();
        int[] flat54 = new int[rows * 54];
        for (int r = 0; r < rows; r++) {
            int[] enc = cards2Array(actionSeqList.get(r));
            System.arraycopy(enc, 0, flat54, r * 54, 54);
        }
        float[] out = new float[5 * 162];
        for (int i = 0; i < out.length; i++) out[i] = flat54[i];
        return out;
    }

    // ---------- 主入口 ----------
    public static Encoded encode(InfoSet is) {
        switch (is.playerPosition) {
            case "landlord": return encodeLandlord(is);
            case "landlord_up": return encodeLandlordUp(is);
            case "landlord_down": return encodeLandlordDown(is);
            default: throw new IllegalArgumentException("未知位置: " + is.playerPosition);
        }
    }

    private static Encoded encodeLandlord(InfoSet is) {
        int n = is.legalActions.size();
        float[] myHand = toF(cards2Array(is.playerHandCards));
        float[] otherHand = toF(cards2Array(is.otherHandCards));
        float[] lastAction = toF(cards2Array(is.lastMove));
        float[] upPlayed = toF(cards2Array(is.playedCards.get("landlord_up")));
        float[] downPlayed = toF(cards2Array(is.playedCards.get("landlord_down")));
        float[] upLeft = oneHotArray(is.numCardsLeftDict.get("landlord_up"), 17);
        float[] downLeft = oneHotArray(is.numCardsLeftDict.get("landlord_down"), 17);
        float[] bomb = oneHotBomb(is.bombNum);

        int inDim = 373;
        float[] x = new float[n * inDim];
        for (int j = 0; j < n; j++) {
            int p = j * inDim;
            p = put(x, p, myHand);
            p = put(x, p, otherHand);
            p = put(x, p, lastAction);
            p = put(x, p, upPlayed);
            p = put(x, p, downPlayed);
            p = put(x, p, upLeft);
            p = put(x, p, downLeft);
            p = put(x, p, bomb);
            p = put(x, p, toF(cards2Array(is.legalActions.get(j))));
        }

        Encoded e = new Encoded();
        e.nLegal = n;
        e.inDim = inDim;
        e.xBatch = x;
        e.xNoAction = flattenInt(myHand, otherHand, lastAction, upPlayed, downPlayed,
                upLeft, downLeft, bomb);
        e.zBatch = zBatch(is);
        return e;
    }

    private static Encoded encodeLandlordUp(InfoSet is) {
        int n = is.legalActions.size();
        float[] myHand = toF(cards2Array(is.playerHandCards));
        float[] otherHand = toF(cards2Array(is.otherHandCards));
        float[] landlordPlayed = toF(cards2Array(is.playedCards.get("landlord")));
        float[] teammatePlayed = toF(cards2Array(is.playedCards.get("landlord_down")));
        float[] lastAction = toF(cards2Array(is.lastMove));
        float[] lastLandlord = toF(cards2Array(is.lastMoveDict.get("landlord")));
        float[] lastTeammate = toF(cards2Array(is.lastMoveDict.get("landlord_down")));
        float[] landlordLeft = oneHotArray(is.numCardsLeftDict.get("landlord"), 20);
        float[] teammateLeft = oneHotArray(is.numCardsLeftDict.get("landlord_down"), 17);
        float[] bomb = oneHotBomb(is.bombNum);

        int inDim = 484;
        float[] x = new float[n * inDim];
        for (int j = 0; j < n; j++) {
            int p = j * inDim;
            p = put(x, p, myHand);
            p = put(x, p, otherHand);
            p = put(x, p, landlordPlayed);
            p = put(x, p, teammatePlayed);
            p = put(x, p, lastAction);
            p = put(x, p, lastLandlord);
            p = put(x, p, lastTeammate);
            p = put(x, p, landlordLeft);
            p = put(x, p, teammateLeft);
            p = put(x, p, bomb);
            p = put(x, p, toF(cards2Array(is.legalActions.get(j))));
        }

        Encoded e = new Encoded();
        e.nLegal = n;
        e.inDim = inDim;
        e.xBatch = x;
        e.xNoAction = flattenInt(myHand, otherHand, landlordPlayed, teammatePlayed, lastAction,
                lastLandlord, lastTeammate, landlordLeft, teammateLeft, bomb);
        e.zBatch = zBatch(is);
        return e;
    }

    private static Encoded encodeLandlordDown(InfoSet is) {
        int n = is.legalActions.size();
        float[] myHand = toF(cards2Array(is.playerHandCards));
        float[] otherHand = toF(cards2Array(is.otherHandCards));
        float[] landlordPlayed = toF(cards2Array(is.playedCards.get("landlord")));
        float[] teammatePlayed = toF(cards2Array(is.playedCards.get("landlord_up")));
        float[] lastAction = toF(cards2Array(is.lastMove));
        float[] lastLandlord = toF(cards2Array(is.lastMoveDict.get("landlord")));
        float[] lastTeammate = toF(cards2Array(is.lastMoveDict.get("landlord_up")));
        float[] landlordLeft = oneHotArray(is.numCardsLeftDict.get("landlord"), 20);
        float[] teammateLeft = oneHotArray(is.numCardsLeftDict.get("landlord_up"), 17);
        float[] bomb = oneHotBomb(is.bombNum);

        int inDim = 484;
        float[] x = new float[n * inDim];
        for (int j = 0; j < n; j++) {
            int p = j * inDim;
            p = put(x, p, myHand);
            p = put(x, p, otherHand);
            p = put(x, p, landlordPlayed);
            p = put(x, p, teammatePlayed);
            p = put(x, p, lastAction);
            p = put(x, p, lastLandlord);
            p = put(x, p, lastTeammate);
            p = put(x, p, landlordLeft);
            p = put(x, p, teammateLeft);
            p = put(x, p, bomb);
            p = put(x, p, toF(cards2Array(is.legalActions.get(j))));
        }

        Encoded e = new Encoded();
        e.nLegal = n;
        e.inDim = inDim;
        e.xBatch = x;
        e.xNoAction = flattenInt(myHand, otherHand, landlordPlayed, teammatePlayed, lastAction,
                lastLandlord, lastTeammate, landlordLeft, teammateLeft, bomb);
        e.zBatch = zBatch(is);
        return e;
    }

    private static float[] zBatch(InfoSet is) {
        List<int[]> seq = processActionSeq(is.cardPlayActionSeq, 15);
        return actionSeqList2Array(seq);
    }

    private static int put(float[] dst, int off, float[] src) {
        System.arraycopy(src, 0, dst, off, src.length);
        return off + src.length;
    }

    private static float[] toF(int[] src) {
        float[] o = new float[src.length];
        for (int i = 0; i < src.length; i++) o[i] = src[i];
        return o;
    }

    /** x_no_action 在 Python 里是 hstack 的 int8，这里按 int 保留以便逐位比对。 */
    private static int[] flattenInt(float[]... parts) {
        int total = 0;
        for (float[] p : parts) total += p.length;
        int[] out = new int[total];
        int k = 0;
        for (float[] p : parts) {
            for (float v : p) out[k++] = (int) v;
        }
        return out;
    }

    // ---------- 合法动作 ----------
    /**
     * 移植自 ddz_assistant._build_infoset 的合法动作生成部分。
     *
     * 严格镜像 Python 的 if/elif 链：首条件是 `rival_type == TYPE_0_PASS or len(rival_move) == 0`，
     * 因此“上家未出牌”时直接生成全部牌型，与 rival_type 无关。
     *
     * 返回顺序已规范化（见 MoveGenerator.canonicalize），保证与黄金向量可逐位比对。
     */
    public static List<int[]> legalActionsFrom(InfoSet is) {
        MoveGenerator mg = new MoveGenerator(is.playerHandCards);

        // 与 Python _build_infoset 一致：
        //   若上一次有效出牌是我自己，说明其他两家都 pass，我可以自由领出
        int[] rivalMove;
        if (is.lastPid != null && is.lastPid.equals(is.playerPosition)) {
            rivalMove = new int[0];
        } else {
            rivalMove = is.lastMove;
        }

        Map<String, Object> rivalTypeInfo = MoveDetector.getMoveType(rivalMove);
        int rivalType = MoveDetector.typeOf(rivalTypeInfo);
        int rivalLen = MoveDetector.lenOf(rivalTypeInfo);

        List<int[]> moves = new ArrayList<>();

        if (rivalType == Types.TYPE_0_PASS || rivalMove.length == 0) {
            moves = mg.genMoves();
        } else if (rivalType == Types.TYPE_1_SINGLE) {
            moves = MoveSelector.filterType1Single(mg.genType1Single(), rivalMove);
        } else if (rivalType == Types.TYPE_2_PAIR) {
            moves = MoveSelector.filterType2Pair(mg.genType2Pair(), rivalMove);
        } else if (rivalType == Types.TYPE_3_TRIPLE) {
            moves = MoveSelector.filterType3Triple(mg.genType3Triple(), rivalMove);
        } else if (rivalType == Types.TYPE_4_BOMB) {
            List<int[]> all = new ArrayList<>(mg.genType4Bomb());
            all.addAll(mg.genType5KingBomb());
            moves = MoveSelector.filterType4Bomb(all, rivalMove);
        } else if (rivalType == Types.TYPE_5_KING_BOMB) {
            moves = new ArrayList<>();
        } else if (rivalType == Types.TYPE_6_3_1) {
            moves = MoveSelector.filterType6_3_1(mg.genType6_3_1(), rivalMove);
        } else if (rivalType == Types.TYPE_7_3_2) {
            moves = MoveSelector.filterType7_3_2(mg.genType7_3_2(), rivalMove);
        } else if (rivalType == Types.TYPE_8_SERIAL_SINGLE) {
            moves = MoveSelector.filterType8SerialSingle(mg.genType8SerialSingle(rivalLen), rivalMove);
        } else if (rivalType == Types.TYPE_9_SERIAL_PAIR) {
            moves = MoveSelector.filterType9SerialPair(mg.genType9SerialPair(rivalLen), rivalMove);
        } else if (rivalType == Types.TYPE_10_SERIAL_TRIPLE) {
            moves = MoveSelector.filterType10SerialTriple(mg.genType10SerialTriple(rivalLen), rivalMove);
        } else if (rivalType == Types.TYPE_11_SERIAL_3_1) {
            moves = MoveSelector.filterType11Serial3_1(mg.genType11Serial3_1(rivalLen), rivalMove);
        } else if (rivalType == Types.TYPE_12_SERIAL_3_2) {
            moves = MoveSelector.filterType12Serial3_2(mg.genType12Serial3_2(rivalLen), rivalMove);
        } else if (rivalType == Types.TYPE_13_4_2) {
            moves = MoveSelector.filterType13_4_2(mg.genType13_4_2(), rivalMove);
        } else if (rivalType == Types.TYPE_14_4_22) {
            moves = MoveSelector.filterType14_4_22(mg.genType14_4_22(), rivalMove);
        }

        // 非 pass/炸弹/王炸时，可以加炸弹和王炸
        if (rivalType != Types.TYPE_0_PASS && rivalType != Types.TYPE_4_BOMB
                && rivalType != Types.TYPE_5_KING_BOMB) {
            moves.addAll(mg.genType4Bomb());
            moves.addAll(mg.genType5KingBomb());
        }
        // 对手出了牌时，可以 pass
        if (rivalMove.length != 0) moves.add(new int[0]);

        MoveGenerator.canonicalize(moves);
        return moves;
    }
}
