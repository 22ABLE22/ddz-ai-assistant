package com.example.ddzoffline.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 推理所需的信息集 —— 对应 DouZero douzero/env/game.py 的 InfoSet。
 * 只保留 get_obs 与合法动作生成用到的字段。
 */
public final class InfoSet {

    public String playerPosition;
    public int[] playerHandCards = new int[0];
    public int[] otherHandCards = new int[0];
    public Map<String, Integer> numCardsLeftDict = new LinkedHashMap<>();
    public int bombNum;
    public int[] lastMove = new int[0];
    public Map<String, int[]> lastMoveDict = new LinkedHashMap<>();
    public Map<String, int[]> playedCards = new LinkedHashMap<>();
    public List<int[]> cardPlayActionSeq = new ArrayList<>();
    public List<int[]> legalActions = new ArrayList<>();

    /**
     * 最后一次有效出牌的位置（对应 Python 的 self.last_pid）。
     * 当它等于我方位置时，说明另外两家都 pass 了，我可以自由领出 —— 此时
     * 合法动作必须按“无上家牌”生成，否则会错误地拿自己的上一手牌做过滤。
     */
    public String lastPid = "landlord";

    public InfoSet(String playerPosition) {
        this.playerPosition = playerPosition;
    }

    public static InfoSet blank(String position) {
        InfoSet is = new InfoSet(position);
        for (String p : new String[]{"landlord", "landlord_up", "landlord_down"}) {
            is.lastMoveDict.put(p, new int[0]);
            is.playedCards.put(p, new int[0]);
        }
        is.numCardsLeftDict.put("landlord", 20);
        is.numCardsLeftDict.put("landlord_up", 17);
        is.numCardsLeftDict.put("landlord_down", 17);
        return is;
    }
}
