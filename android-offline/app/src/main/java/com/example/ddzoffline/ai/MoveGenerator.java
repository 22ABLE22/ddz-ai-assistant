package com.example.ddzoffline.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 合法出牌生成器 —— 逐函数移植自 DouZero douzero/env/move_generator.py。
 *
 * 牌值编码：3-10 -> 3..10，J=11 Q=12 K=13 A=14 2=17 小王=20 大王=30。
 * 输出顺序与 Python 不一定相同（Python 依赖 set/dict 迭代序），
 * 因此调用方需用 {@link #canonicalize} 统一排序后再送模型。
 */
public final class MoveGenerator {

    public static final int MIN_SINGLE_CARDS = 5;
    public static final int MIN_PAIRS = 3;
    public static final int MIN_TRIPLES = 2;

    private final List<Integer> cardsList;
    private final Map<Integer, Integer> counts = new LinkedHashMap<>();
    private final List<int[]> singleMoves = new ArrayList<>();
    private final List<int[]> pairMoves = new ArrayList<>();
    private final List<int[]> tripleMoves = new ArrayList<>();
    private final List<int[]> bombMoves = new ArrayList<>();
    private final List<int[]> kingBombMoves = new ArrayList<>();

    public MoveGenerator(int[] cards) {
        List<Integer> cl = new ArrayList<>(cards.length);
        for (int c : cards) cl.add(c);
        this.cardsList = cl;
        for (int c : cl) counts.merge(c, 1, Integer::sum);

        for (int r : new TreeSet<>(cl)) singleMoves.add(new int[]{r});
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() >= 2) pairMoves.add(new int[]{e.getKey(), e.getKey()});
            if (e.getValue() >= 3) tripleMoves.add(new int[]{e.getKey(), e.getKey(), e.getKey()});
            if (e.getValue() == 4) bombMoves.add(new int[]{e.getKey(), e.getKey(), e.getKey(), e.getKey()});
        }
        if (cl.contains(20) && cl.contains(30)) kingBombMoves.add(new int[]{20, 30});
    }

    // ---------- 基础牌型 ----------
    public List<int[]> genType1Single() { return copy(singleMoves); }
    public List<int[]> genType2Pair() { return copy(pairMoves); }
    public List<int[]> genType3Triple() { return copy(tripleMoves); }
    public List<int[]> genType4Bomb() { return copy(bombMoves); }
    public List<int[]> genType5KingBomb() { return copy(kingBombMoves); }

    public List<int[]> genType6_3_1() {
        List<int[]> out = new ArrayList<>();
        for (int[] t : singleMoves) {
            for (int[] tr : tripleMoves) {
                if (t[0] != tr[0]) out.add(concat(t, tr));
            }
        }
        return out;
    }

    public List<int[]> genType7_3_2() {
        List<int[]> out = new ArrayList<>();
        for (int[] p : pairMoves) {
            for (int[] tr : tripleMoves) {
                if (p[0] != tr[0]) out.add(concat(p, tr));
            }
        }
        return out;
    }

    // ---------- 顺子 ----------
    private static List<int[]> genSerialMoves(List<Integer> cards, int minSerial,
                                              int repeat, int repeatNum) {
        if (repeatNum < minSerial) repeatNum = 0;

        TreeSet<Integer> uniq = new TreeSet<>(cards);
        List<Integer> singleCards = new ArrayList<>(uniq);
        List<int[]> moves = new ArrayList<>();

        List<int[]> seqRecords = new ArrayList<>();
        int start = 0, i = 0, longest = 1;
        while (i < singleCards.size()) {
            if (i + 1 < singleCards.size()
                    && singleCards.get(i + 1) - singleCards.get(i) == 1) {
                longest++;
                i++;
            } else {
                seqRecords.add(new int[]{start, longest});
                i++;
                start = i;
                longest = 1;
            }
        }

        for (int[] seq : seqRecords) {
            if (seq[1] < minSerial) continue;
            int s = seq[0], len = seq[1];
            List<Integer> longestList = singleCards.subList(s, s + len);

            if (repeatNum == 0) {
                int steps = minSerial;
                while (steps <= len) {
                    int index = 0;
                    while (steps + index <= len) {
                        moves.add(buildRepeat(longestList, index, steps, repeat));
                        index++;
                    }
                    steps++;
                }
            } else {
                if (len < repeatNum) continue;
                int index = 0;
                while (index + repeatNum <= len) {
                    moves.add(buildRepeat(longestList, index, repeatNum, repeat));
                    index++;
                }
            }
        }
        return moves;
    }

    /** Python: sorted(longest_list[index:index+steps] * repeat) */
    private static int[] buildRepeat(List<Integer> list, int index, int steps, int repeat) {
        int[] out = new int[steps * repeat];
        int p = 0;
        for (int r = 0; r < repeat; r++) {
            for (int k = 0; k < steps; k++) out[p++] = list.get(index + k);
        }
        Arrays.sort(out);
        return out;
    }

    public List<int[]> genType8SerialSingle(int repeatNum) {
        return genSerialMoves(cardsList, MIN_SINGLE_CARDS, 1, repeatNum);
    }

    public List<int[]> genType9SerialPair(int repeatNum) {
        List<Integer> pairs = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() >= 2) pairs.add(e.getKey());
        }
        return genSerialMoves(pairs, MIN_PAIRS, 2, repeatNum);
    }

    public List<int[]> genType10SerialTriple(int repeatNum) {
        List<Integer> triples = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() >= 3) triples.add(e.getKey());
        }
        return genSerialMoves(triples, MIN_TRIPLES, 3, repeatNum);
    }

    public List<int[]> genType11Serial3_1(int repeatNum) {
        List<int[]> serial3 = genType10SerialTriple(repeatNum);
        List<int[]> result = new ArrayList<>();
        for (int[] s3 : serial3) {
            TreeSet<Integer> s3set = new TreeSet<>();
            for (int v : s3) s3set.add(v);
            List<Integer> newCards = new ArrayList<>();
            for (int c : cardsList) if (!s3set.contains(c)) newCards.add(c);
            for (List<Integer> sub : combinations(newCards, s3set.size())) {
                result.add(concat(s3, toArray(sub)));
            }
        }
        return dedupeConsecutive(result);
    }

    public List<int[]> genType12Serial3_2(int repeatNum) {
        List<int[]> serial3 = genType10SerialTriple(repeatNum);
        List<int[]> result = new ArrayList<>();
        TreeSet<Integer> pairSet = new TreeSet<>();
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() >= 2) pairSet.add(e.getKey());
        }
        for (int[] s3 : serial3) {
            TreeSet<Integer> s3set = new TreeSet<>();
            for (int v : s3) s3set.add(v);
            List<Integer> candidates = new ArrayList<>();
            for (int p : pairSet) if (!s3set.contains(p)) candidates.add(p);
            for (List<Integer> sub : combinations(candidates, s3set.size())) {
                int[] doubled = new int[sub.size() * 2];
                for (int k = 0; k < sub.size(); k++) {
                    doubled[k * 2] = sub.get(k);
                    doubled[k * 2 + 1] = sub.get(k);
                }
                int[] m = concat(s3, doubled);
                Arrays.sort(m);
                result.add(m);
            }
        }
        return result;
    }

    public List<int[]> genType13_4_2() {
        List<int[]> result = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() != 4) continue;
            int fc = e.getKey();
            List<Integer> rest = new ArrayList<>();
            for (int c : cardsList) if (c != fc) rest.add(c);
            for (List<Integer> sub : combinations(rest, 2)) {
                int[] m = new int[6];
                Arrays.fill(m, 0, 4, fc);
                m[4] = sub.get(0);
                m[5] = sub.get(1);
                result.add(m);
            }
        }
        return dedupeConsecutive(result);
    }

    public List<int[]> genType14_4_22() {
        List<int[]> result = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() != 4) continue;
            int fc = e.getKey();
            List<Integer> cand = new ArrayList<>();
            for (Map.Entry<Integer, Integer> o : counts.entrySet()) {
                if (o.getKey() != fc && o.getValue() >= 2) cand.add(o.getKey());
            }
            for (List<Integer> sub : combinations(cand, 2)) {
                int[] m = new int[8];
                Arrays.fill(m, 0, 4, fc);
                m[4] = sub.get(0); m[5] = sub.get(0);
                m[6] = sub.get(1); m[7] = sub.get(1);
                result.add(m);
            }
        }
        return result;
    }

    /** 全部合法牌型（不含 pass）。 */
    public List<int[]> genMoves() {
        List<int[]> moves = new ArrayList<>();
        moves.addAll(genType1Single());
        moves.addAll(genType2Pair());
        moves.addAll(genType3Triple());
        moves.addAll(genType4Bomb());
        moves.addAll(genType5KingBomb());
        moves.addAll(genType6_3_1());
        moves.addAll(genType7_3_2());
        moves.addAll(genType8SerialSingle(0));
        moves.addAll(genType9SerialPair(0));
        moves.addAll(genType10SerialTriple(0));
        moves.addAll(genType11Serial3_1(0));
        moves.addAll(genType12Serial3_2(0));
        moves.addAll(genType13_4_2());
        moves.addAll(genType14_4_22());
        return moves;
    }

    // ---------- 工具 ----------
    /** itertools.combinations 语义：按下标组合，保留列表中的重复元素。 */
    static List<List<Integer>> combinations(List<Integer> src, int k) {
        List<List<Integer>> out = new ArrayList<>();
        if (k < 0 || k > src.size()) return out;
        if (k == 0) { out.add(new ArrayList<>()); return out; }
        int[] idx = new int[k];
        for (int i = 0; i < k; i++) idx[i] = i;
        while (true) {
            List<Integer> one = new ArrayList<>(k);
            for (int i = 0; i < k; i++) one.add(src.get(idx[i]));
            out.add(one);
            int i = k - 1;
            while (i >= 0 && idx[i] == src.size() - k + i) i--;
            if (i < 0) break;
            idx[i]++;
            for (int j = i + 1; j < k; j++) idx[j] = idx[j - 1] + 1;
        }
        return out;
    }

    /** itertools.groupby 语义：仅去重“相邻”的相同元素。 */
    static List<int[]> dedupeConsecutive(List<int[]> in) {
        List<int[]> out = new ArrayList<>();
        int[] prev = null;
        for (int[] cur : in) {
            if (prev == null || !Arrays.equals(prev, cur)) out.add(cur);
            prev = cur;
        }
        return out;
    }

    /**
     * 规范化 legal_actions 顺序，使 Java 与 Python 侧可逐位比对。
     * 排序键：先比长度，再逐元素比大小（空列表排最前）。
     */
    public static void canonicalize(List<int[]> moves) {
        for (int[] m : moves) Arrays.sort(m);
        moves.sort((a, b) -> {
            if (a.length != b.length) return Integer.compare(a.length, b.length);
            for (int i = 0; i < a.length; i++) {
                if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
            }
            return 0;
        });
    }

    static int[] concat(int[] a, int[] b) {
        int[] o = new int[a.length + b.length];
        System.arraycopy(a, 0, o, 0, a.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }

    static int[] toArray(List<Integer> l) {
        int[] o = new int[l.size()];
        for (int i = 0; i < o.length; i++) o[i] = l.get(i);
        return o;
    }

    static List<int[]> copy(List<int[]> src) {
        List<int[]> out = new ArrayList<>(src.size());
        for (int[] a : src) out.add(a.clone());
        return out;
    }
}
