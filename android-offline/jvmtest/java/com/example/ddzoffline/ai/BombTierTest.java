package com.example.ddzoffline.ai;

import java.util.Arrays;

/**
 * 炸弹分级测试 —— MVP 缺口 A1/A2。
 *
 * 依据 qmarliu/ddzlib 的 card_type.h：
 *   软炸(13) < 硬炸(14) < 纯癞子炸(15) < 王炸(16)
 *
 * 重点验证之前漏掉的「纯癞子炸」档位：4 张赖子应大于任何硬炸。
 */
public final class BombTierTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        System.out.println("=".repeat(74));
        System.out.println("炸弹分级测试（四分档位）");
        System.out.println("=".repeat(74));

        GameRules r = GameRules.laizi(7);

        section("1) 档位判定");
        // 硬炸：天然四张 8
        check("硬炸判定", BombTier.of(new int[]{8, 8, 8, 8}, new int[]{8, 8, 8, 8}, r) == BombTier.HARD,
                BombTier.name(BombTier.of(new int[]{8, 8, 8, 8}, new int[]{8, 8, 8, 8}, r)));

        // 软炸：3 3 3 + 1 张赖子（physical 含 7）
        check("软炸判定", BombTier.of(new int[]{3, 3, 3, 3}, new int[]{3, 3, 3, 7}, r) == BombTier.SOFT,
                BombTier.name(BombTier.of(new int[]{3, 3, 3, 3}, new int[]{3, 3, 3, 7}, r)));

        // 纯癞子炸：4 张赖子
        check("纯癞子炸判定", BombTier.of(new int[]{9, 9, 9, 9}, new int[]{7, 7, 7, 7}, r) == BombTier.GHOST,
                BombTier.name(BombTier.of(new int[]{9, 9, 9, 9}, new int[]{7, 7, 7, 7}, r)));

        // 王炸
        check("王炸判定", BombTier.of(new int[]{20, 30}, new int[]{20, 30}, r) == BombTier.ROCKET,
                BombTier.name(BombTier.of(new int[]{20, 30}, new int[]{20, 30}, r)));

        // 非炸弹
        check("单张非炸弹", BombTier.of(new int[]{5}, new int[]{5}, r) == BombTier.NONE);
        check("对子非炸弹", BombTier.of(new int[]{5, 5}, new int[]{5, 5}, r) == BombTier.NONE);
        check("三张非炸弹", BombTier.of(new int[]{5, 5, 5}, new int[]{5, 5, 5}, r) == BombTier.NONE);

        section("2) 四档全序比较");
        int soft = BombTier.SOFT, hard = BombTier.HARD, ghost = BombTier.GHOST, rocket = BombTier.ROCKET;

        check("硬炸 > 软炸", BombTier.compare(hard, 8, soft, 3) > 0);
        check("软炸 < 硬炸", BombTier.compare(soft, 3, hard, 8) < 0);
        check("纯癞子炸 > 硬炸", BombTier.compare(ghost, 0, hard, 14) > 0);
        check("硬炸 < 纯癞子炸", BombTier.compare(hard, 14, ghost, 0) < 0);
        check("王炸 > 纯癞子炸", BombTier.compare(rocket, 0, ghost, 0) > 0);
        check("纯癞子炸 < 王炸", BombTier.compare(ghost, 0, rocket, 0) < 0);

        // 同级比点数
        check("硬炸 9 > 硬炸 8", BombTier.compare(hard, 9, hard, 8) > 0);
        check("硬炸 8 < 硬炸 9", BombTier.compare(hard, 8, hard, 9) < 0);
        check("硬炸 8 == 硬炸 8", BombTier.compare(hard, 8, hard, 8) == 0);
        check("软炸 9 > 软炸 3", BombTier.compare(soft, 9, soft, 3) > 0);

        // 纯癞子炸同档不比点数（视为同大）
        check("纯癞子炸同档不可比", BombTier.compare(ghost, 3, ghost, 9) == 0);
        check("王炸同档不可比", BombTier.compare(rocket, 0, rocket, 0) == 0);

        section("3) 引擎层实际压牌（LaiziMoves.beats）");
        GameRules rules = GameRules.laizi(7);

        // 手牌：3 3 3 7（软炸3）+ 8 8 8 8（硬炸8）+ 9 9 9 7（软炸9）+ 7 7 7 7（纯癞子炸）+ X D
        int[] hand = {3, 3, 3, 7, 8, 8, 8, 8, 9, 9, 9, 20, 30, 7, 7, 7};
        LaiziMoves lm = new LaiziMoves(hand, rules);

        LaiziMoves.Move softBomb3 = LaiziMoves.Move.of(
                new int[]{3, 3, 3, 7}, new int[]{3, 3, 3, 3}, 1);
        LaiziMoves.Move hardBomb8 = LaiziMoves.Move.of(
                new int[]{8, 8, 8, 8}, new int[]{8, 8, 8, 8}, 0);
        LaiziMoves.Move softBomb9 = LaiziMoves.Move.of(
                new int[]{9, 9, 9, 7}, new int[]{9, 9, 9, 9}, 1);
        LaiziMoves.Move ghostBomb = LaiziMoves.Move.of(
                new int[]{7, 7, 7, 7}, new int[]{9, 9, 9, 9}, 4);
        LaiziMoves.Move rocketMove = LaiziMoves.Move.of(
                new int[]{20, 30}, new int[]{20, 30}, 0);

        check("硬炸压软炸", lm.beats(hardBomb8, softBomb3));
        check("软炸压不过硬炸", !lm.beats(softBomb3, hardBomb8));
        check("纯癞子炸压硬炸", lm.beats(ghostBomb, hardBomb8));
        check("硬炸压不过纯癞子炸", !lm.beats(hardBomb8, ghostBomb));
        check("王炸压纯癞子炸", lm.beats(rocketMove, ghostBomb));
        check("纯癞子炸压不过王炸", !lm.beats(ghostBomb, rocketMove));
        check("软炸9 压软炸3", lm.beats(softBomb9, softBomb3));
        check("炸弹压普通牌型",
                lm.beats(hardBomb8, LaiziMoves.Move.of(new int[]{14}, new int[]{14}, 0)));
        check("普通牌型压不过炸弹",
                !lm.beats(LaiziMoves.Move.of(new int[]{14}, new int[]{14}, 0), hardBomb8));

        section("4) 生成的动作里包含纯癞子炸");
        int[] ghostHand = {7, 7, 7, 7, 3, 4, 5, 6, 8, 9, 10, 11, 12, 13, 14, 17, 20};
        LaiziMoves glm = new LaiziMoves(ghostHand, rules);
        boolean hasGhost = false, hasSoft = false;
        for (LaiziMoves.Move m : glm.generateAll()) {
            int t = BombTier.of(m.concrete, m.physical, rules);
            if (t == BombTier.GHOST) hasGhost = true;
            if (t == BombTier.SOFT) hasSoft = true;
        }
        check("动作空间含纯癞子炸", hasGhost);
        check("动作空间含软炸", hasSoft);

        section("5) 经典模式不受影响");
        check("经典模式无赖子概念",
                !GameRules.CLASSIC.isLaiziMode() && !GameRules.CLASSIC.isLaizi(7));
        // 经典模式下 BombTier 仍能识别硬炸（不依赖赖子）
        check("经典硬炸判定", BombTier.of(new int[]{8, 8, 8, 8}, new int[]{8, 8, 8, 8},
                GameRules.CLASSIC) == BombTier.HARD);
        check("经典王炸判定", BombTier.of(new int[]{20, 30}, new int[]{20, 30},
                GameRules.CLASSIC) == BombTier.ROCKET);

        System.out.println();
        System.out.println("=".repeat(74));
        System.out.printf("结果: %d 通过, %d 失败%n", pass, fail);
        System.out.println("=".repeat(74));
        if (fail > 0) System.exit(1);
    }

    static void section(String s) {
        System.out.println();
        System.out.println("-- " + s);
    }

    static void check(String name, boolean ok) {
        check(name, ok, "");
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  PASS  " + name); }
        else { fail++; System.out.println("  FAIL  " + name + (detail.isEmpty() ? "" : "  | " + detail)); }
    }
}
