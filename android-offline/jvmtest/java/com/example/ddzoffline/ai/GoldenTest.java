package com.example.ddzoffline.ai;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯 JVM 验收测试（无需 Android 设备）：
 *   1. 读取导出权重，校验参数个数与 meta 一致
 *   2. Layer A：用 PyTorch 生成的黄金输入跑前向，逐值比对
 *   3. Layer B：用黄金 infoset 跑特征编码，逐位比对
 *
 * 运行：java -cp <classes> com.example.ddzoffline.ai.GoldenTest <项目根>
 */
public final class GoldenTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        Path assets = root.resolve("android-offline/app/src/main/assets");
        Path golden = root.resolve("tools/golden");

        System.out.println("项目根: " + root.toAbsolutePath());

        System.out.println();
        System.out.println("=".repeat(72));
        System.out.println("1) 权重加载与参数个数校验");
        System.out.println("=".repeat(72));
        Map<String, float[]> blobs = new LinkedHashMap<>();
        for (String obj : new String[]{"WP", "ADP"}) {
            for (String pos : new String[]{"landlord", "landlord_up", "landlord_down"}) {
                Path f = assets.resolve("ddz_" + obj + "_" + pos + ".bin");
                float[] blob = readFloats(f);
                int inDim = DdzModel.inDimFor(pos);
                int expected = DdzModel.expectedParamCount(inDim);
                check(obj + "/" + pos + " 参数个数", blob.length == expected,
                        blob.length + " vs " + expected);
                // 构建一次确保布局自洽
                DdzModel m = DdzModel.fromBlob(blob, inDim);
                check(obj + "/" + pos + " inDim", m.inDim == inDim, String.valueOf(m.inDim));
                blobs.put(obj + "/" + pos, blob);
            }
        }

        System.out.println();
        System.out.println("=".repeat(72));
        System.out.println("2) Layer A：forward 数值比对（vs PyTorch）");
        System.out.println("=".repeat(72));
        String layerA = Files.readString(golden.resolve("layer_a_forward.json"), StandardCharsets.UTF_8);
        List<Object> casesA = (List<Object>) ((Map<String, Object>) MiniJson.parse(layerA)).get("cases");
        double worstAbs = 0, worstRel = 0;
        for (Object o : casesA) {
            Map<String, Object> c = (Map<String, Object>) o;
            String obj = (String) c.get("objective");
            String pos = (String) c.get("position");
            int inDim = (int) num(c.get("in_dim"));
            int nLegal = (int) num(c.get("n_legal"));
            float[] x = toFloats((List<Object>) c.get("x"));
            float[] z = toFloats((List<Object>) c.get("z"));
            float[] exp = toFloats((List<Object>) c.get("expected_values"));

            DdzModel model = DdzModel.fromBlob(blobs.get(obj + "/" + pos), inDim);
            Tensor xT = new Tensor(nLegal, inDim, x);
            float[] got = model.forward(z, xT);

            double maxAbs = 0, maxRef = 0;
            for (int i = 0; i < exp.length; i++) {
                maxAbs = Math.max(maxAbs, Math.abs(got[i] - exp[i]));
                maxRef = Math.max(maxRef, Math.abs(exp[i]));
            }
            // 相对误差判据：ADP 目标函数无界（点差可达数千），
            // 绝对误差必须按量级归一，否则只是 float32 累加噪声被误判为不一致。
            double rel = maxAbs / Math.max(1.0, maxRef);
            worstAbs = Math.max(worstAbs, maxAbs);
            worstRel = Math.max(worstRel, rel);
            check(String.format("%s/%s #%d  n=%-3d maxAbs=%.2e rel=%.2e", obj, pos,
                            (int) num(c.get("index")), nLegal, maxAbs, rel),
                    rel < 1e-5, "");
        }
        System.out.printf("  → 最大绝对误差 %.3e / 最大相对误差 %.3e%n", worstAbs, worstRel);

        System.out.println();
        System.out.println("=".repeat(72));
        System.out.println("3) Layer B：特征编码逐位比对（vs PyTorch）");
        System.out.println("=".repeat(72));
        String layerB = Files.readString(golden.resolve("layer_b_encode.json"), StandardCharsets.UTF_8);
        List<Object> casesB = (List<Object>) ((Map<String, Object>) MiniJson.parse(layerB)).get("cases");
        for (Object o : casesB) {
            Map<String, Object> c = (Map<String, Object>) o;
            int idx = (int) num(c.get("index"));
            String pos = (String) c.get("position");
            int[] xNoAction = toInts((List<Object>) c.get("x_no_action"));
            float[] expX = toFloats((List<Object>) c.get("x_batch"));
            float[] expZ = toFloats((List<Object>) c.get("z_batch"));
            int[] expLegalFlat = toIntListOfLists((List<Object>) c.get("legal_actions"));
            int expLegalCount = ((List<Object>) c.get("legal_actions")).size();

            // 用黄金快照重建 infoset
            com.example.ddzoffline.ai.InfoSet is = new com.example.ddzoffline.ai.InfoSet(pos);
            is.playerHandCards = toInts((List<Object>) c.get("player_hand_cards"));
            is.otherHandCards = toInts((List<Object>) c.get("other_hand_cards"));
            is.numCardsLeftDict = toIntMap((Map<String, Object>) c.get("num_cards_left_dict"));
            is.bombNum = (int) num(c.get("bomb_num"));
            is.lastPid = (String) c.get("last_pid");
            is.lastMove = toInts((List<Object>) c.get("last_move"));
            is.lastMoveDict = toIntListMap((Map<String, Object>) c.get("last_move_dict"));
            is.playedCards = toIntListMap((Map<String, Object>) c.get("played_cards"));
            is.cardPlayActionSeq = toIntListOfLists2((List<Object>) c.get("card_play_action_seq"));
            is.legalActions = DdzFeatures.legalActionsFrom(is);   // 由牌型生成器重算

            check("B#" + idx + " " + pos + " legal_actions 数量",
                    is.legalActions.size() == expLegalCount,
                    "got=" + is.legalActions.size() + " exp=" + expLegalCount);
            int[] gotLegal = flatten(is.legalActions);
            check("B#" + idx + " " + pos + " legal_actions 逐位一致",
                    java.util.Arrays.equals(gotLegal, expLegalFlat),
                    "got=" + gotLegal.length + " exp=" + expLegalFlat.length);

            DdzFeatures.Encoded enc = DdzFeatures.encode(is);
            check("B#" + idx + " " + pos + " x_no_action 一致",
                    java.util.Arrays.equals(enc.xNoAction, xNoAction), "");
            check("B#" + idx + " " + pos + " x_batch 一致",
                    floatsEqual(enc.xBatch, expX), "");
            // Python 的 z_batch 是 z 按合法动作数复制后的 (n,5,162)；
            // Java 只保留基序列 (5,162) 以避免重复分配。这里按“每 810 个一组”比对。
            boolean zOk = expZ.length % 810 == 0;
            if (zOk) {
                for (int off = 0; off + 810 <= expZ.length; off += 810) {
                    for (int k = 0; k < 810; k++) {
                        if (enc.zBatch[k] != expZ[off + k]) { zOk = false; break; }
                    }
                    if (!zOk) break;
                }
            }
            check("B#" + idx + " " + pos + " z_batch 一致（按行比对基序列）", zOk,
                    "java=" + enc.zBatch.length + " py=" + expZ.length);
        }

        System.out.println();
        System.out.println("=".repeat(72));
        System.out.printf("结果: %d 通过, %d 失败%n", pass, fail);
        System.out.println("=".repeat(72));
        if (fail > 0) System.exit(1);
    }

    static int avgLen(int[] flat) { return 1; }

    /** MiniJson 把所有数字解析为 Double，统一取整。 */
    static double num(Object o) {
        return ((Number) o).doubleValue();
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  PASS  " + name + (detail.isEmpty() ? "" : "  | " + detail)); }
        else { fail++; System.out.println("  FAIL  " + name + (detail.isEmpty() ? "" : "  | " + detail)); }
    }

    static boolean floatsEqual(float[] a, float[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    static float[] readFloats(Path p) throws IOException {
        byte[] raw = Files.readAllBytes(p);
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[raw.length / 4];
        for (int i = 0; i < out.length; i++) out[i] = bb.getFloat();
        return out;
    }

    static float[] toFloats(List<Object> l) {
        float[] o = new float[l.size()];
        for (int i = 0; i < o.length; i++) o[i] = (float) ((Number) l.get(i)).doubleValue();
        return o;
    }

    static int[] toInts(List<Object> l) {
        int[] o = new int[l.size()];
        for (int i = 0; i < o.length; i++) o[i] = ((Number) l.get(i)).intValue();
        return o;
    }

    static Map<String, Integer> toIntMap(Map<String, Object> m) {
        Map<String, Integer> o = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            o.put(e.getKey(), ((Number) e.getValue()).intValue());
        }
        return o;
    }

    static Map<String, int[]> toIntListMap(Map<String, Object> m) {
        Map<String, int[]> o = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            o.put(e.getKey(), toInts((List<Object>) e.getValue()));
        }
        return o;
    }

    static List<int[]> toIntListOfLists2(List<Object> l) {
        List<int[]> o = new ArrayList<>();
        for (Object x : l) o.add(toInts((List<Object>) x));
        return o;
    }

    static int[] toIntListOfLists(List<Object> l) {
        List<Integer> flat = new ArrayList<>();
        for (Object x : l) for (Object v : (List<Object>) x) flat.add(((Number) v).intValue());
        int[] o = new int[flat.size()];
        for (int i = 0; i < o.length; i++) o[i] = flat.get(i);
        return o;
    }

    static int[] flatten(List<int[]> l) {
        int n = 0;
        for (int[] a : l) n += a.length;
        int[] o = new int[n];
        int p = 0;
        for (int[] a : l) { System.arraycopy(a, 0, o, p, a.length); p += a.length; }
        return o;
    }
}
