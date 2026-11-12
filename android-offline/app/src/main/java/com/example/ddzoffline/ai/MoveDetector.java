package com.example.ddzoffline.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 牌型识别 —— 移植自 DouZero douzero/env/move_detector.py。
 * 返回 {type, rank?, len?}，类型常量见 {@link Types}。
 */
public final class MoveDetector {

    public static boolean isContinuousSeq(int[] move) {
        for (int i = 0; i + 1 < move.length; i++) {
            if (move[i + 1] - move[i] != 1) return false;
        }
        return true;
    }

    public static Map<String, Object> getMoveType(int[] moveIn) {
        int[] move = moveIn.clone();
        Arrays.sort(move);
        Map<String, Object> r = new LinkedHashMap<>();
        int size = move.length;
        if (size == 0) { r.put("type", Types.TYPE_0_PASS); return r; }

        if (size == 1) { r.put("type", Types.TYPE_1_SINGLE); r.put("rank", move[0]); return r; }

        if (size == 2) {
            if (move[0] == move[1]) { r.put("type", Types.TYPE_2_PAIR); r.put("rank", move[0]); return r; }
            if (move[0] == 20 && move[1] == 30) { r.put("type", Types.TYPE_5_KING_BOMB); return r; }
            r.put("type", Types.TYPE_15_WRONG); return r;
        }

        Map<Integer, Integer> moveDict = new LinkedHashMap<>();
        for (int c : move) moveDict.merge(c, 1, Integer::sum);

        if (size == 3) {
            if (moveDict.size() == 1) { r.put("type", Types.TYPE_3_TRIPLE); r.put("rank", move[0]); }
            else r.put("type", Types.TYPE_15_WRONG);
            return r;
        }

        if (size == 4) {
            if (moveDict.size() == 1) { r.put("type", Types.TYPE_4_BOMB); r.put("rank", move[0]); return r; }
            if (moveDict.size() == 2) {
                if ((move[0] == move[1] && move[1] == move[2])
                        || (move[1] == move[2] && move[2] == move[3])) {
                    r.put("type", Types.TYPE_6_3_1); r.put("rank", move[1]); return r;
                }
                r.put("type", Types.TYPE_15_WRONG); return r;
            }
            r.put("type", Types.TYPE_15_WRONG); return r;
        }

        if (isContinuousSeq(move)) {
            r.put("type", Types.TYPE_8_SERIAL_SINGLE);
            r.put("rank", move[0]);
            r.put("len", move.length);
            return r;
        }

        if (size == 5) {
            if (moveDict.size() == 2) { r.put("type", Types.TYPE_7_3_2); r.put("rank", move[2]); }
            else r.put("type", Types.TYPE_15_WRONG);
            return r;
        }

        Map<Integer, Integer> countDict = new LinkedHashMap<>();
        for (int n : moveDict.values()) countDict.merge(n, 1, Integer::sum);

        if (size == 6) {
            if ((moveDict.size() == 2 || moveDict.size() == 3)
                    && intOr(countDict, 4) == 1
                    && (intOr(countDict, 2) == 1 || intOr(countDict, 1) == 2)) {
                r.put("type", Types.TYPE_13_4_2); r.put("rank", move[2]); return r;
            }
        }

        if (size == 8) {
            boolean a = (moveDict.size() == 3 || moveDict.size() == 2)
                    && intOr(countDict, 4) == 1 && intOr(countDict, 2) == 2;
            boolean b = intOr(countDict, 4) == 2;
            if (a || b) {
                int rank = 0;
                for (Map.Entry<Integer, Integer> e : moveDict.entrySet()) {
                    if (e.getValue() == 4) rank = Math.max(rank, e.getKey());
                }
                r.put("type", Types.TYPE_14_4_22); r.put("rank", rank); return r;
            }
        }

        List<Integer> mdkeys = new ArrayList<>(moveDict.keySet());
        Collections.sort(mdkeys);

        if (moveDict.size() == intOr(countDict, 2) && isContinuousSeq(toArr(mdkeys))) {
            r.put("type", Types.TYPE_9_SERIAL_PAIR);
            r.put("rank", mdkeys.get(0));
            r.put("len", mdkeys.size());
            return r;
        }
        if (moveDict.size() == intOr(countDict, 3) && isContinuousSeq(toArr(mdkeys))) {
            r.put("type", Types.TYPE_10_SERIAL_TRIPLE);
            r.put("rank", mdkeys.get(0));
            r.put("len", mdkeys.size());
            return r;
        }

        if (intOr(countDict, 3) >= MoveGenerator.MIN_TRIPLES) {
            List<Integer> serial3 = new ArrayList<>();
            List<Integer> single = new ArrayList<>();
            List<Integer> pair = new ArrayList<>();
            for (Map.Entry<Integer, Integer> e : moveDict.entrySet()) {
                int v = e.getValue();
                if (v == 3) serial3.add(e.getKey());
                else if (v == 1) single.add(e.getKey());
                else if (v == 2) pair.add(e.getKey());
                else { r.put("type", Types.TYPE_15_WRONG); return r; }
            }
            Collections.sort(serial3);
            if (isContinuousSeq(toArr(serial3))) {
                if (serial3.size() == single.size() + pair.size() * 2) {
                    r.put("type", Types.TYPE_11_SERIAL_3_1);
                    r.put("rank", serial3.get(0));
                    r.put("len", serial3.size());
                    return r;
                }
                if (serial3.size() == pair.size() && moveDict.size() == serial3.size() * 2) {
                    r.put("type", Types.TYPE_12_SERIAL_3_2);
                    r.put("rank", serial3.get(0));
                    r.put("len", serial3.size());
                    return r;
                }
            }
            if (serial3.size() == 4) {
                int[] tail = sub(serial3, 1, 4);
                int[] head = sub(serial3, 0, 3);
                if (isContinuousSeq(tail)) {
                    r.put("type", Types.TYPE_11_SERIAL_3_1);
                    r.put("rank", serial3.get(1));
                    r.put("len", 3);
                    return r;
                }
                if (isContinuousSeq(head)) {
                    r.put("type", Types.TYPE_11_SERIAL_3_1);
                    r.put("rank", serial3.get(0));
                    r.put("len", 3);
                    return r;
                }
            }
        }

        r.put("type", Types.TYPE_15_WRONG);
        return r;
    }

    public static int typeOf(Map<String, Object> t) { return (Integer) t.get("type"); }
    public static int rankOf(Map<String, Object> t) {
        Object v = t.get("rank");
        return v == null ? 0 : (Integer) v;
    }
    public static int lenOf(Map<String, Object> t) {
        Object v = t.get("len");
        return v == null ? 1 : (Integer) v;
    }

    private static int intOr(Map<Integer, Integer> m, int k) {
        Integer v = m.get(k);
        return v == null ? 0 : v;
    }

    private static int[] toArr(List<Integer> l) {
        int[] o = new int[l.size()];
        for (int i = 0; i < o.length; i++) o[i] = l.get(i);
        return o;
    }

    private static int[] sub(List<Integer> l, int from, int to) {
        int[] o = new int[to - from];
        for (int i = 0; i < o.length; i++) o[i] = l.get(from + i);
        return o;
    }
}
