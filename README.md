# 斗地主 AI 出牌助手

基于 [DouZero](https://github.com/kwai/DouZero) 预训练模型的斗地主辅助出牌工具，提供命令行与图形界面两种用法。

## 功能

- 根据当前手牌、底牌与已出牌，用 DouZero 模型推荐最佳出牌
- 支持 **WP（胜率）** / **ADP（平均分差）** 两种推荐模型，可在界面一键切换（默认 WP）
- 图形界面：暗色牌桌主题、手牌可视化、一键开局、一键采用推荐
- 支持记录地主 / 地主上家 / 地主下家的出牌；记录自己位置时与 `play` 等价（会扣手牌）
- 地主可只输入 17 张手牌并填写底牌，程序自动合并为 20 张

## 环境要求

- Windows（已提供 `start_gui.bat`）
- Python 3.9+（建议使用 conda 环境）
- PyTorch（CPU 即可）

主要依赖：

```text
torch
numpy
```

## 快速开始

### 方式一：双击启动

双击 `start_gui.bat`（脚本默认使用本机 `D:\ProgramData\miniconda3\envs\PEMAE\python.exe`，若路径不同请按需修改）。

### 方式二：命令行

```bash
conda activate PEMAE   # 或你自己的环境
cd path/to/DDZ
python ddz_gui.py
```

### 仅使用命令行助手

```bash
python ddz_assistant.py
```

常用命令：

| 命令 | 说明 |
|------|------|
| `record <位置> <牌>` | 记录出牌；位置为 `landlord` / `landlord_up` / `landlord_down` |
| `recommend` | 获取 AI 推荐 |
| `play <牌>` | 执行我的出牌（`play pass` 表示不出） |
| `model WP` / `model ADP` | 切换推荐模型 |
| `status` | 查看状态 |
| `reset` | 新对局 |
| `quit` | 退出 |

## 目录结构

```text
.
├── ddz_assistant.py      # 命令行助手（核心逻辑）
├── ddz_gui.py            # 图形界面
├── start_gui.bat         # Windows 一键启动
├── DouZero/              # DouZero 引擎源码（环境、模型结构）
└── douzero-baselines/
    └── checkpoints/
        ├── douzero_WP/   # 胜率模型（默认）
        └── douzero_ADP/  # 分差模型
```

## 使用提示（GUI）

1. 选择身份：地主上家 / 地主 / 地主下家  
2. 输入手牌（地主建议含底牌共 20 张，或 17 张 + 底牌）与三张底牌  
3. 点「开始对局」  
4. 按出牌顺序记录他人出牌；轮到自己时点「刷新推荐」  
5. 可用「一键采用」「不出」，或在「我的出牌 / 记录出牌」中自行出牌  
6. 推荐面板可切换 WP / ADP  

## 模型说明

| 模型 | 含义 | 特点 |
|------|------|------|
| **WP** | Win Percentage | 更关注胜率（默认） |
| **ADP** | Average Difference of Points | 更关注平均分差 |

预训练权重来自官方 [douzero-baselines](https://github.com/kwai/DouZero/tree/master/douzero/baselines)。

## 致谢

- [DouZero (KuaiShou)](https://github.com/kwai/DouZero) — 斗地主 AI 引擎与预训练模型  
- 相关论文：*DouZero: Mastering DouDizhu with Self-Play Deep Reinforcement Learning*

## 免责声明

本项目仅供学习与研究，请勿用于任何违反平台规则或法律法规的用途。
