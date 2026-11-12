package com.example.ddzoffline.ai;

/** DouZero 牌型常量（对应 douzero/env/utils.py）。 */
public final class Types {
    private Types() {}

    public static final int TYPE_0_PASS = 0;
    public static final int TYPE_1_SINGLE = 1;
    public static final int TYPE_2_PAIR = 2;
    public static final int TYPE_3_TRIPLE = 3;
    public static final int TYPE_4_BOMB = 4;
    public static final int TYPE_5_KING_BOMB = 5;
    public static final int TYPE_6_3_1 = 6;
    public static final int TYPE_7_3_2 = 7;
    public static final int TYPE_8_SERIAL_SINGLE = 8;
    public static final int TYPE_9_SERIAL_PAIR = 9;
    public static final int TYPE_10_SERIAL_TRIPLE = 10;
    public static final int TYPE_11_SERIAL_3_1 = 11;
    public static final int TYPE_12_SERIAL_3_2 = 12;
    public static final int TYPE_13_4_2 = 13;
    public static final int TYPE_14_4_22 = 14;
    public static final int TYPE_15_WRONG = 15;

    /** 牌值 <-> 显示 */
    public static String label(int card) {
        if (card <= 9) return String.valueOf(card);
        switch (card) {
            case 10: return "10";
            case 11: return "J";
            case 12: return "Q";
            case 13: return "K";
            case 14: return "A";
            case 17: return "2";
            case 20: return "X";
            case 30: return "D";
            default: return "?";
        }
    }

    public static String display(int[] cards) {
        StringBuilder sb = new StringBuilder();
        for (int c : cards) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(label(c));
        }
        return sb.toString();
    }
}
