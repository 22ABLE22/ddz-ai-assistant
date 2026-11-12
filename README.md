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

本仓库包含自研代码、启动脚本、Android 离线 APK 源码与验收工具：

```text
.
├── ddz_assistant.py          # 命令行助手（核心逻辑）
├── ddz_gui.py                # 桌面图形界面（Tk）
├── ddz_search.py             # 蒙特卡洛仿真搜索（FUSE / 多进程）
├── web_server.py             # Flask 服务：电脑跑推理，手机浏览器访问
├── web_index.html            # 移动端网页（记牌器 / 撤销 / 候选）
├── ddz_float.js              # Auto.js 手机悬浮窗
├── start_gui.bat             # 桌面 GUI 一键启动
├── start_web.bat             # Web 服务一键启动
├── android-offline/          # 纯离线 Android APK（端侧 DouZero 推理）
├── tools/                    # 权重导出、逐位校验、16 步一键回归
├── docs/                     # 赖子推理、识牌接入、MVP 方案记录
├── design-system/            # 界面设计令牌与组件规范
├── test_ui_acceptance.py     # 65 项 UI 契约检查
├── test_overlay_compat.py    # 悬浮窗 API 兼容检查
└── README.md
```

**不包含** DouZero 源码与 PyTorch `.ckpt` 权重（桌面端按下面步骤自行下载）。Android APK 用的 float32 权重已导出并入库，见 `android-offline/app/src/main/assets/`。

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

双击 `start_gui.bat`。脚本会自动探测 `python`，找不到时回退 `py -3`；两者都不在 PATH 时请先安装 Python 3.9+ 并勾选「Add to PATH」。

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

## 纯手机离线 MVP

项目新增 `android-offline/`，可构建成不依赖电脑、局域网或网络权限的 Android APK。

**APK 内部真的跑 DouZero**：PyTorch `.ckpt` 已由 `tools/export_android.py` 转成 little-endian float32 权重，随 `assets/` 打进 APK；Java 端用 `Lstm` + `DdzModel` 重写了 `LSTM(162,128) → 5×Dense(512) → Dense(1)` 的前向，`DdzEngine.recommend()` 直接对合法着打分排序，无需 Python、无需网络、无需 PC 陪跑。

已移植的能力：

| 模块 | 说明 |
|---|---|
| `ai/DdzModel` `ai/Lstm` `ai/Tensor` | DouZero 前向数值实现（地主 inDim=373 / 农民 484） |
| `ai/MoveGenerator` `ai/GameRules` | 牌型枚举与合法性判定 |
| `ai/LaiziMoves` | 赖子具体化（癞子补牌、组合展开） |
| `ai/BombTier` | 炸弹四分档位 |
| `ai/MonteCarloSearch` `ai/RolloutEnv` | 蒙特卡洛残局搜索 |
| `ai/HandRecognizer` `ai/TemplateMatcher` | 截图识牌（模板匹配） |
| `OverlayService` `GameWatchService` | 原生悬浮窗 + 读屏监听 |

构建命令（本机已有 Android SDK 与 Gradle 时）：

```powershell
cd android-offline
C:\Android\gradle-9.6.0\bin\gradle.bat assembleDebug --offline
```

APK 输出：`android-offline/app/build/outputs/apk/debug/app-debug.apk`（约 33 MB，其中权重约 34 MB 未压缩）。

### 一键回归（16 步）

```powershell
python tools/run_all_checks.py
```

依次执行：导出权重与黄金向量 → 与 PyTorch 逐位比对 → JVM 编译 → 11 个 Java 测试（黄金向量 / 引擎一致性 / 端到端 / 赖子 / 搜索 / 识牌）→ Gradle 构建 → APK 内容核验（模型数、dex、测试类是否泄漏）。当前状态 **16/16 通过**。

单跑 Python 侧契约检查：

```powershell
python test_ui_acceptance.py     # 65 项 UI 契约
python test_overlay_compat.py    # 悬浮窗 API 字段向后兼容
```

### 纯手机悬浮窗

APK 已内置 Android 原生悬浮窗，不依赖电脑和 Auto.js：

1. 安装 APK，打开应用。
2. 输入身份、17 张手牌和底牌，点击“开始对局”。
3. 点击“开启手机悬浮窗”，第一次按系统提示允许“显示在其他应用上层”。
4. 返回离线牌局，悬浮窗中输入当前出的牌，点“记录”；不出点“过”。
5. 轮到自己时点“推荐”，确认后点“采用”；标题栏可拖动，点“收起”可压缩操作面板。
6. 输入框可直接唤起手机键盘；通知栏会显示悬浮窗正在运行。

## 手机悬浮窗使用

项目提供两种手机入口：

1. **手机浏览器**：电脑双击 `start_web.bat`，确保电脑和手机连接同一 Wi-Fi；在手机浏览器打开启动窗口打印的 `http://电脑局域网IP:8899`。
2. **Android 悬浮窗（Auto.js）**：在 Auto.js 中运行 `ddz_float.js`。首次启动点右上角齿轮，填写电脑局域网地址（例如 `192.168.1.8:8899`）并保存；连接正常后，悬浮窗即可在斗地主客户端上方使用。

悬浮窗适配手机触控：标题可拖动，按钮和输入框为大触控区域；点 `▾` 可折叠为边缘小球，点 `⚙` 可修改并测试服务地址。身份和服务地址会自动保存；网络中断时会在状态栏提示，不会直接退出脚本。

开局后按桌上实际顺序，在“本轮出的牌”输入框录入当前出牌者的牌并点“记录”；不出则点“过”。悬浮窗根据服务端状态自动提示下一位是谁。自己手动出的牌也用同一输入框记录，系统会验证合法性并同步扣减手牌；点“采用”则自动记下 AI 推荐。点“重置本局”可清空对局状态。

### 手机端快速验证

先在电脑浏览器或手机浏览器打开 `http://电脑局域网IP:8899/api/health`，看到 `{"ok":true,"service":"ddz-ai-assistant"}` 后再运行悬浮窗。若无法打开，检查 Windows 防火墙是否允许 Python/8899 端口，以及手机和电脑是否处于同一 Wi-Fi。

## 常见问题

**启动报找不到模型 / DouZero**  
检查是否按上文把 `DouZero/`、`douzero-baselines/checkpoints/douzero_WP|ADP/` 放在与 `ddz_gui.py` 同一级目录。

**`start_gui.bat` 双击闪退**  
bat 已改为自动探测 `python` / `py -3`。若仍闪退，说明 Python 不在 PATH，请先安装 Python 3.9+ 并勾选「Add to PATH」。

**手机端能开页面但推荐报错**  
电脑侧必须能加载 DouZero 与 `.ckpt`（Web 服务复用桌面端的 `DDZAssistant`）。Android APK 不依赖电脑，权重已内置。

**APK 能装但悬浮窗不显示**  
首次使用需在系统设置里授予「显示在其他应用上层」；读屏相关功能还需在无障碍里启用本应用。

**改完 Java 代码后测试不过**  
先跑 `python tools/export_android.py` 重新导出权重与黄金向量，再跑 `python tools/run_all_checks.py`。

## 致谢

- [DouZero (KuaiShou)](https://github.com/kwai/DouZero) — 斗地主 AI 引擎  
- [douzero-baselines (HuggingFace)](https://huggingface.co/palemoky/douzero-baselines) — 预训练权重  
- 相关论文：*DouZero: Mastering DouDizhu with Self-Play Deep Reinforcement Learning*

## 免责声明

本项目仅供学习与研究，请勿用于任何违反平台规则或法律法规的用途。
