package com.example.ddzoffline.ai;

import java.util.Arrays;
import java.util.List;

/**
 * 输入解析测试。
 *
 * 核心验收（对应提示词 D5）：**点选录入与键盘录入必须一致**。
 * 点选路径直接产出牌值数组，文本路径走 CardInput.parse；
 * 这里用「紧凑串」作为二者之间的公共表示来验证等价性。
 */
public final class CardInputTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        System.out.println("=".repeat(74));
        System.out.println("输入解析测试");
        System.out.println("=".repeat(74));

        basics();
        sugar();
        consistency();
        errors();
        deckMath();

        System.out.println();
        System.out.println("=".repeat(74));
        System.out.printf("结果: %d 通过, %d 失败%n", pass, fail);
        System.out.println("=".repeat(74));
        if (fail > 0) System.exit(1);
    }

    static void basics() {
        section("1) 基础写法");
        eq("紧凑串", "34567JJQQKKAA2XD", "3 4 5 6 7 J J Q Q K K A A 2 X D");
        eq("空格分隔", "3 4 5 6 7 J J Q Q", "3 4 5 6 7 J J Q Q");
        eq("逗号分隔", "3,4,5,J,J", "3 4 5 J J");
        eq("中文逗号", "3，4，5", "3 4 5");
        eq("10 的两种写法", "10 10 T", "10 10 10");
        eq("小写", "jjqq", "J J Q Q");
        eq("混合分隔", "3 3,44 55", "3 3 4 4 5 5");
    }

    static void sugar() {
        section("2) 便捷写法（免逐张敲）");
        eq("区间 3-7", "3-7", "3 4 5 6 7");
        eq("区间 J-A", "J-A", "J Q K A");
        eq("区间倒序自动纠正", "7-3", "3 4 5 6 7");
        eq("数量写法 3*2", "3*2", "3 3");
        eq("数量写法 J*3", "J*3", "J J J");
        eq("数量写法 10*4", "10*4", "10 10 10 10");
        eq("数量 + 区间混用", "3-5 7*2", "3 4 5 7 7");
        eq("区间展开后可满足 17 张",
                "3-9 J*4 Q*4 K*2",
                "3 4 5 6 7 8 9 J J J J Q Q Q Q K K");
        eq("王的数量写法", "X*1 D*1", "X D");
    }

    static void consistency() {
        section("3) 点选与文本一致（D5）");
        // 模拟「点选」：用户点出这些牌，等价于下面的文本
        int[][] picked = {
                {3, 3, 4, 4, 5, 5, 6, 7, 8, 9, 11, 11, 12, 12, 13, 13, 14},
                {17, 17, 20, 30, 3, 3, 3, 4, 4, 5, 5, 6, 7, 8, 9, 10, 11},
                {3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 17, 20, 30, 3, 4},
        };
        String[] texts = {
                "3344556789JJQQKKA",
                "2 2 X D 3 3 3 4 4 5 5 6 7 8 9 10 J",
                "3-A 2 X D 3 4",
        };
        // 文本与点选必须表达同一组牌（顺序无关）
        for (int i = 0; i < picked.length; i++) {
            CardInput.Parsed p = CardInput.parse(texts[i]);
            int[] fromText = p.toArray();
            int[] fromPick = picked[i].clone();
            Arrays.sort(fromPick);
            check("第 " + (i + 1) + " 组：点选 == 文本", Arrays.equals(fromPick, fromText),
                    "点选=" + Arrays.toString(fromPick) + " 文本=" + Arrays.toString(fromText));
            check("第 " + (i + 1) + " 组张数 = 17", fromText.length == 17,
                    String.valueOf(fromText.length));
        }

        // 往返：牌值 -> 紧凑串 -> 牌值
        for (int[] c : picked) {
            String compact = CardInput.toCompact(c);
            int[] back = CardInput.parse(compact).toArray();
            int[] sorted = c.clone();
            Arrays.sort(sorted);
            check("往返一致（" + compact + "）", Arrays.equals(sorted, back),
                    Arrays.toString(back));
        }

        // 与引擎的严格解析保持一致
        for (String t : texts) {
            List<Integer> engine = com.example.ddzoffline.DdzEngine.parseCards(t);
            int[] viaInput = CardInput.parse(t).toArray();
            int[] viaEngine = new int[engine.size()];
            for (int i = 0; i < viaEngine.length; i++) viaEngine[i] = engine.get(i);
            check("与 DdzEngine.parseCards 一致（" + t + "）",
                    Arrays.equals(viaInput, viaEngine),
                    Arrays.toString(viaInput) + " vs " + Arrays.toString(viaEngine));
        }
    }

    static void errors() {
        section("4) 错误处理");
        CardInput.Parsed bad = CardInput.parse("3 4 Z");
        check("非法牌被记录", !bad.ok(), bad.errors.toString());
        check("非法牌不影响其余解析", bad.size() == 2, String.valueOf(bad.size()));

        CardInput.Parsed tooMany = CardInput.parse("10*9");
        check("超出张数上限被拒", !tooMany.ok(), tooMany.errors.toString());

        CardInput.Parsed wrongCount = CardInput.parseExpecting("3 4 5", 17);
        check("张数不符被拒", !wrongCount.ok(), wrongCount.errors.toString());

        CardInput.Parsed empty = CardInput.parse("");
        check("空输入无错误且为空", empty.ok() && empty.size() == 0, "");

        CardInput.Parsed badRange = CardInput.parse("Q-3");
        check("区间自动纠正方向", badRange.ok() && badRange.size() == 10,
                badRange.size() + " " + badRange.errors);
    }

    static void deckMath() {
        section("5) 牌堆与反推");
        int[] deck = CardInput.standardDeck();
        check("标准牌堆 54 张", deck.length == 54, String.valueOf(deck.length));

        // 我的 17 张 + 3 张底牌 = 20，剩 34 张
        int[] mine = CardInput.parse("3344556789JJQQKKA").toArray();
        int[] three = CardInput.parse("2 X D").toArray();
        int[] used = new int[mine.length + three.length];
        System.arraycopy(mine, 0, used, 0, mine.length);
        System.arraycopy(three, 0, used, mine.length, three.length);
        int[] rest = CardInput.subtract(deck, used);
        check("扣除 20 张后剩 34 张", rest.length == 34, String.valueOf(rest.length));
    }

    // ---------------- 工具 ----------------

    static void eq(String name, String input, String expectDisplay) {
        CardInput.Parsed p = CardInput.parse(input);
        String got = display(p.toArray());
        check(name + "  [" + input + "]", got.equals(expectDisplay) && p.ok(),
                "得到 " + got + (p.ok() ? "" : " 错误=" + p.errors));
    }

    static String display(int[] cards) {
        StringBuilder sb = new StringBuilder();
        for (int c : cards) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Types.label(c));
        }
        return sb.toString();
    }

    static void section(String s) {
        System.out.println();
        System.out.println("-- " + s);
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  PASS  " + name); }
        else { fail++; System.out.println("  FAIL  " + name + (detail.isEmpty() ? "" : "  | " + detail)); }
    }
}
