package com.example.ddzoffline.ai;

/**
 * 炸弹分级 —— 赖子玩法的核心规则。
 *
 * 依据参考实现 qmarliu/ddzlib 的 card_type.h：
 *   CARD_TYPE_SOFTBOMB  = 13   软炸：四张同点，其中含赖子充当
 *   CARD_TYPE_BOMB      = 14   硬炸：天然四张同点
 *   CARD_TYPE_GHOSTBOMB = 15   纯癞子炸：四张全是赖子
 *   CARD_TYPE_ROCKET    = 16   王炸：大小王
 *
 * 数值直接沿用参考实现的枚举值，便于对照与扩展。
 *
 * 注意：本类**只在赖子模式下**参与比较；经典模式走原有二分逻辑，
 * 保证既有行为零回归。
 */
public final class BombTier {

    public static final int NONE = 0;
    public static final int SOFT = 13;    // 含赖子凑成的四张
    public static final int HARD = 14;    // 天然四张
    public static final int GHOST = 15;   // 四张全是赖子
    public static final int ROCKET = 16;  // 王炸

    private BombTier() {}

    /**
     * 判定一个动作的炸弹档位。
     *
     * @param concrete 具体化后的牌（赖子已被替换成目标点数）
     * @param physical 实际打出的牌（含赖子）
     * @param rules    规则（用于 isLaizi 判定）
     * @return 档位常量；非炸弹返回 {@link #NONE}
     */
    public static int of(int[] concrete, int[] physical, GameRules rules) {
        if (concrete == null || concrete.length == 0) return NONE;

        // 王炸：大小王各一张
        if (concrete.length == 2 && concrete[0] == 20 && concrete[1] == 30) {
            return ROCKET;
        }

        // 其余炸弹必须是四张同点
        if (concrete.length != 4) return NONE;
        for (int v : concrete) {
            if (v != concrete[0]) return NONE;
        }

        // 数一下实际用了几张赖子
        int laiziUsed = 0;
        if (physical != null) {
            for (int c : physical) {
                if (rules != null && rules.isLaizi(c)) laiziUsed++;
            }
        }

        if (laiziUsed == 0) return HARD;
        // 四张全是赖子 → 纯癞子炸
        if (physical != null && laiziUsed == physical.length && physical.length == 4) {
            return GHOST;
        }
        return SOFT;
    }

    /** 是否为炸弹（含王炸）。 */
    public static boolean isBomb(int tier) {
        return tier >= SOFT;
    }

    /**
     * 比较两个炸弹。
     *
     * @return >0 表示 a 更大；<0 表示 b 更大；0 表示不可比（非炸弹或同级同点）
     */
    public static int compare(int tierA, int rankA, int tierB, int rankB) {
        if (tierA != tierB) return Integer.compare(tierA, tierB);
        // 同级：王炸与纯癞子炸不比点数（同档视为同大，先出者压制）
        if (tierA == ROCKET || tierA == GHOST) return 0;
        return Integer.compare(rankA, rankB);
    }

    /** 显示名，用于界面与日志。 */
    public static String name(int tier) {
        switch (tier) {
            case SOFT: return "软炸";
            case HARD: return "硬炸";
            case GHOST: return "纯癞子炸";
            case ROCKET: return "王炸";
            default: return "非炸弹";
        }
    }
}
