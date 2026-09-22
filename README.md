# 斗地主 AI 出牌助手

基于 [DouZero](https://github.com/kwai/DouZero) 预训练模型的斗地主辅助出牌工具，提供命令行与图形界面两种用法。

## 功能

- 根据当前手牌、底牌与已出牌，用 DouZero 模型推荐最佳出牌
- 支持 **WP（胜率）** / **ADP（平均分差）** 两种策略模型，可在界面一键切换（默认 WP）
- **深度搜索（蒙特卡洛仿真）**：采样未知手牌并用模型把残局打到终局，按胜率/分差评估候选
- **WP/ADP 融合目标（FUSE）**：`score = w * 胜率 + (1-w) * 归一化分差`，默认权重 `w=0.7`
- **多进程并行仿真**：可开启多个 worker 加速（Windows spawn）
- 图形界面：暗色牌桌主题、手牌可视化（仅牌面，无角标）、一键开局、一键采用推荐
- **记牌器**：统计尚未出现的牌；满张/双王未见用红框提示可能成炸，已出完显示灰色
- 支持记录地主 / 地主上家 / 地主下家的出牌；记录自己位置时与 `play` 等价（会扣手牌）
- 手牌必须正好 **17 张**；地主会自动并入 3 张底牌得到 20 张

## 本仓库包含什么

本仓库**只包含**本项目的自研代码与启动脚本：

```text
.
├── ddz_assistant.py      # 命令行助手（核心逻辑）
├── ddz_gui.py            # 图形界面
├── ddz_search.py         # 蒙特卡洛仿真搜索（FUSE / 多进程）
├── start_gui.bat         # Windows 一键启动（请按本机 Python 路径修改）
└── README.md
```

**不包含** DouZero 源码与模型权重，请按下面步骤自行放到对应目录。

## 准备依赖目录

克隆本仓库后，在**仓库根目录**下准备两个文件夹，最终结构应为：

```text
你的项目目录/                  # 即本仓库根目录
├── ddz_assistant.py
├── ddz_gui.py
├── start_gui.bat
├── README.md
├── DouZero/                  # 从官方仓库下载的引擎源码
│   └── douzero/
│       ├── dmc/
│       ├── env/
│       └── ...
├── ddz_search.py
└── douzero-baselines/
    └── checkpoints/
        ├── douzero_WP/       # 胜率模型（默认推荐）
        │   ├── landlord.ckpt
        │   ├── landlord_up.ckpt
        │   └── landlord_down.ckpt
        └── douzero_ADP/      # 分差模型
            ├── landlord.ckpt
            ├── landlord_up.ckpt
            └── landlord_down.ckpt
```

### 1. 下载 DouZero 源码 → `DouZero/`

```bash
git clone https://github.com/kwai/DouZero.git DouZero
```

或从 GitHub 下载 ZIP 后解压，把官方仓库内容放到 `DouZero/` 目录（保证存在 `DouZero/douzero/env`、`DouZero/douzero/dmc`）。

### 2. 下载预训练模型 → `douzero-baselines/`

官方预训练权重见 HuggingFace：  
https://huggingface.co/palemoky/douzero-baselines/tree/main

克隆权重仓库（体积较大）：

```bash
git clone https://huggingface.co/palemoky/douzero-baselines douzero-baselines
```

本程序实际只用到：

- `douzero-baselines/checkpoints/douzero_WP/`
- `douzero-baselines/checkpoints/douzero_ADP/`

若只需要运行助手，可只保留上述两个目录下的 6 个 `.ckpt` 文件。

## 环境要求

- Windows（已提供 `start_gui.bat`）
- Python 3.9+（建议 conda）
- 依赖：

```bash
pip install torch numpy
```

CPU 版 PyTorch 即可。

## 快速开始

确认目录结构如上后：

### 方式一：双击启动

双击 `start_gui.bat`。  
脚本默认使用本机路径 `D:\ProgramData\miniconda3\envs\PEMAE\python.exe`，若环境不同请用记事本打开该文件修改。

### 方式二：命令行

```bash
conda activate 你的环境名
cd 路径/到/本项目
python ddz_gui.py
```

### 仅命令行助手

```bash
python ddz_assistant.py
```

常用命令：

| 命令 | 说明 |
|------|------|
| `record <位置> <牌>` | 记录出牌；位置为 `landlord` / `landlord_up` / `landlord_down` |
| `recommend` | 获取 AI 推荐 |
| `play <牌>` | 执行我的出牌（`play pass` 表示不出） |
| `model WP` / `model ADP` | 切换策略模型 |
| `search on n=200 k=6 w=0.7 workers=8 obj=FUSE` | 开启深度搜索并配置参数 |
| `search off` | 关闭深度搜索 |
| `status` | 查看状态 |
| `reset` | 新对局 |
| `quit` | 退出 |

## 深度搜索（推荐开启）

在轮到自己时，程序会对前若干个候选着做蒙特卡洛仿真：

1. 按已知牌采样两家未知手牌  
2. 强制走出候选着，其后三家用 DouZero 模型打到终局  
3. 统计胜率与分差，并用融合分排序  

融合公式：

```text
fused = w * 胜率 + (1-w) * 归一化分差
```

- `w`（WP 权重）默认 `0.7`：更偏胜率；`1.0` 纯胜率，`0.0` 纯分差  
- 候选初筛会同时参考 WP 与 ADP 两套模型打分（在 `0<w<1` 时）  
- `workers>0` 时多进程并行；失败会自动回退单进程  

**GUI**：推荐面板勾选「深度搜索」，可调仿真次数、WP 权重、并行数。  

**建议参数**：

| 场景 | n | workers | 说明 |
|------|---|---------|------|
| 快速 | 24 | 0 | 约 2 秒级 |
| 均衡 | 48 | 4 | 约 3 秒级（比单进程快约 2 倍） |
| 更稳 | 100+ | 4~8 | 胜率估计更稳，更慢 |

多进程首次调用会加载子进程模型，可能略慢；之后同一进程内更快。

## GUI 使用提示

1. 选择身份：地主上家 / 地主 / 地主下家  
2. 输入手牌（必须正好 **17 张**）与三张底牌；地主自动并底牌为 20 张  
3. 点「开始对局」  
4. 按出牌顺序记录他人出牌；轮到自己时点「刷新推荐」  
5. 可用「一键采用」「不出」，或在「我的出牌 / 记录出牌」中自行出牌  
6. 推荐面板可切换 WP / ADP，并开启深度搜索  
7. 「记牌器」显示未出现张数：红框=该点数仍满/双王均未见（可能炸），灰色=已见完

## 模型说明

| 模型 | 含义 | 特点 |
|------|------|------|
| **WP** | Win Percentage | 更关注胜率（默认） |
| **ADP** | Average Difference of Points | 更关注平均分差 |

## 常见问题

**启动报找不到模型 / DouZero**  
检查是否按上文把 `DouZero/`、`douzero-baselines/checkpoints/douzero_WP|ADP/` 放在与 `ddz_gui.py` 同一级目录。

**`start_gui.bat` 双击闪退**  
用编辑器打开 bat，把 Python 路径改成你的环境里 `python.exe` 的完整路径。

## 致谢

- [DouZero (KuaiShou)](https://github.com/kwai/DouZero) — 斗地主 AI 引擎  
- [douzero-baselines (HuggingFace)](https://huggingface.co/palemoky/douzero-baselines) — 预训练权重  
- 相关论文：*DouZero: Mastering DouDizhu with Self-Play Deep Reinforcement Learning*

## 免责声明

本项目仅供学习与研究，请勿用于任何违反平台规则或法律法规的用途。
