# MVP 方案：赖子玩法补全 + 检索脚本加固

> 依据：GitHub 检索结论（`qmarliu/ddzlib` 等 6 个赖子实现）
> 产出：可安装 APK + 脚本改进 + 回归证据

---

## 一、缺口分析（检索得来，非拍脑袋）

### 缺口 1：炸弹分级不完整 ⚠️ 规则错误

**参考实现**（[qmarliu/ddzlib](https://github.com/qmarliu/ddzlib) `card_type.h`）定义了**三分炸弹**：

```c
CARD_TYPE_SOFTBOMB  = 13,  // 软炸：4 张里含癞子
CARD_TYPE_BOMB      = 14,  // 硬炸：天然四张
CARD_TYPE_GHOSTBOMB = 15,  // 纯癞子炸：4 张全是癞子
CARD_TYPE_ROCKET    = 16,  // 王炸
```

**我当前实现**只有二分（软炸 < 硬炸），**漏了「纯癞子炸」这一档**。

后果：4 张癞子凑的炸弹，在我这被判为普通软炸，**压不过别人的硬炸** —— 与地方规则不符。

`card_analysis.cc` 的比较逻辑印证了顺序：
```cpp
else if (type >= CARD_TYPE_SOFTBOMB || card_ana.type >= CARD_TYPE_SOFTBOMB)
    return type < card_ana.type;   // 直接按枚举值比大小
```

### 缺口 2：检索脚本被关键词污染

搜「斗地主 外挂」时 GitHub 返回的 top 结果是政治垃圾仓库（★3261 排第一），
它们把无关关键词塞进描述蹭流量。我的评分只看 star/活跃度，**识别不出污染**。

---

## 二、MVP 范围（明确做什么、不做什么）

### 做

| 项 | 内容 | 验收 |
|---|---|---|
| A1 | 补「纯癞子炸」档位 | 新增单测：4 张癞子 > 硬炸 > 软炸 |
| A2 | 炸弹分级抽成独立判定 | 单测覆盖 4 档全序 |
| A3 | 检索脚本加关键词相关度过滤 | 政治垃圾仓库不再进 top |
| A4 | 全量回归 | 原 209 用例 + 新增全绿 |
| A5 | 重建 APK | 模型内置、无测试类泄漏 |

### 不做（明确排除）

- ❌ 不做「天癞子 / 翻牌定赖子」的完整发牌流程（属于游戏服务端逻辑，助手不需要）
- ❌ 不做赖子模式的重训模型（DouZero 无赖子训练数据）
- ❌ 不做「软炸能否压硬炸」的可配置开关（地方规则差异大，MVP 固定按湖北规则）
- ❌ 不改经典模式任何行为

---

## 三、实现要点

### A1/A2 炸弹分级

新增 `BombTier` 概念，判定依据 `(concrete 是否四张同点, physical 是否全为赖子)`：

```java
enum BombTier {
    NONE(0), SOFT(13), HARD(14), GHOST(15), ROCKET(16);
}
```

判定规则：

| 条件 | 档位 |
|---|---|
| concrete = `[20,30]` | ROCKET |
| concrete 四张同点 且 physical 全是赖子 | GHOST |
| concrete 四张同点 且 用了赖子 | SOFT |
| concrete 四张同点 且 没用赖子 | HARD |

比较：档位不同比档位；档位相同比点数（GHOST 比点数无意义，视为同大）。

### A3 关键词过滤

评分时加入**描述与查询词的重合度**，并对「描述与查询毫无关系」的结果直接剔除：

```python
def relevance(repo, query_terms):
    text = (name + " " + description).lower()
    hit = sum(1 for t in query_terms if t in text)
    return hit / max(len(query_terms), 1)
```

相关度为 0 的直接过滤掉，不再让高 star 的无关仓库霸榜。

---

## 四、验收标准

- [ ] 新增单测覆盖炸弹四档全序（≥8 条）
- [ ] 原 209 用例全部仍通过
- [ ] 一键回归 11/11 步骤
- [ ] 脚本过滤生效：政治垃圾仓库不再出现
- [ ] APK 重建成功、7 个模型内置、无测试类泄漏
- [ ] 明确标注真机未验证项

## 五、风险

| 风险 | 对策 |
|---|---|
| 改炸弹分级影响经典模式 | 分级逻辑只在 `isLaiziMode()` 分支生效，经典路径零改动 |
| 纯癞子炸判定歧义（癞子点数本身） | 明确：physical 全部 `isLaizi()` 才算 GHOST |
| 过滤太严误杀真项目 | 只在相关度为 0 时剔除，且保留原始结果供复查 |
