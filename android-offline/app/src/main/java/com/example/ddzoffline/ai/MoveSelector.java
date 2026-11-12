package com.example.ddzoffline.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 出牌过滤 —— 移植自 DouZero douzero/env/move_selector.py。
 * 各函数假定 moves 与 rivalMove 已是同类型，仅按“大小”筛出能压过的。
 */
public final class MoveSelector {

    private static List<int[]> commonHandle(List<int[]> moves, int[] rivalMove) {
        List<int[]> out = new ArrayList<>();
        for (int[] m : moves) {
            if (m[0] > rivalMove[0]) out.add(m);
        }
        return out;
    }

    public static List<int[]> filterType1Single(List<int[]> moves, int[] rival) {
        return commonHandle(moves, rival);
    }

    public static List<int[]> filterType2Pair(List<int[]> moves, int[] rival) {
        return commonHandle(moves, rival);
    }

    public static List<int[]> filterType3Triple(List<int[]> moves, int[] rival) {
        return commonHandle(moves, rival);
    }

    public static List<int[]> filterType4Bomb(List<int[]> moves, int[] rival) {
        return commonHandle(moves, rival);
    }

    public static List<int[]> filterType6_3_1(List<int[]> moves, int[] rivalIn) {
        int[] rival = rivalIn.clone();
        Arrays.sort(rival);
        int rivalRank = rival[1];
        List<int[]> out = new ArrayList<>();
        for (int[] mIn : moves) {
            int[] m = mIn.clone();
            Arrays.sort(m);
            if (m[1] > rivalRank) out.add(m);
        }
        return out;
    }

    public static List<int[]> filterType7_3_2(List<int[]> moves, int[] rivalIn) {
        int[] rival = rivalIn.clone();
        Arrays.sort(rival);
        int rivalRank = rival[2];
        List<int[]> out = new ArrayList<>();
        for (int[] mIn : moves) {
            int[] m = mIn.clone();
            Arrays.sort(m);
            if (m[2] > rivalRank) out.add(m);
        }
        return out;
    }

    public static List<int[]> filterType8SerialSingle(List<int[]> moves, int[] rival) {
        return commonHandle(moves, rival);
    }

    public static List<int[]> filterType9SerialPair(List<int[]> moves, int[] rival) {
        return commonHandle(moves, rival);
    }

    public static List<int[]> filterType10SerialTriple(List<int[]> moves, int[] rival) {
        return commonHandle(moves, rival);
    }

    public static List<int[]> filterType11Serial3_1(List<int[]> moves, int[] rival) {
        int rivalRank = maxRankWithCount(rival, 3);
        List<int[]> out = new ArrayList<>();
        for (int[] m : moves) {
            if (maxRankWithCount(m, 3) > rivalRank) out.add(m);
        }
        return out;
    }

    public static List<int[]> filterType12Serial3_2(List<int[]> moves, int[] rival) {
        int rivalRank = maxRankWithCount(rival, 3);
        List<int[]> out = new ArrayList<>();
        for (int[] m : moves) {
            if (maxRankWithCount(m, 3) > rivalRank) out.add(m);
        }
        return out;
    }

    public static List<int[]> filterType13_4_2(List<int[]> moves, int[] rivalIn) {
        int[] rival = rivalIn.clone();
        Arrays.sort(rival);
        int rivalRank = rival[2];
        List<int[]> out = new ArrayList<>();
        for (int[] mIn : moves) {
            int[] m = mIn.clone();
            Arrays.sort(m);
            if (m[2] > rivalRank) out.add(m);
        }
        return out;
    }

    public static List<int[]> filterType14_4_22(List<int[]> moves, int[] rival) {
        int rivalRank = rankWithCount(rival, 4);
        List<int[]> out = new ArrayList<>();
        for (int[] m : moves) {
            if (rankWithCount(m, 4) > rivalRank) out.add(m);
        }
        return out;
    }

    private static Map<Integer, Integer> counts(int[] cards) {
        Map<Integer, Integer> m = new LinkedHashMap<>();
        for (int c : cards) m.merge(c, 1, Integer::sum);
        return m;
    }

    /** Python: max([k for k,v in items if v == count]) —— 无匹配时 Python 会抛错，这里返回 0。 */
    private static int maxRankWithCount(int[] cards, int count) {
        int best = 0;
        for (Map.Entry<Integer, Integer> e : counts(cards).entrySet()) {
            if (e.getValue() == count) best = Math.max(best, e.getKey());
        }
        return best;
    }

    private static int rankWithCount(int[] cards, int count) {
        int best = 0;
        for (Map.Entry<Integer, Integer> e : counts(cards).entrySet()) {
            if (e.getValue() == count) best = e.getKey();
        }
        return best;
    }
}
