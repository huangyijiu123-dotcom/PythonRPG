# PythonRPG 声明式 UI 框架与核心数据绑定开发说明书
## (RPG UI Framework & Core Binding Development Manual)

本开发说明书（以下简称“说明书”）是为 **PythonRPG** 从纯后台 Kotlin JVM 引擎演进为高质感、高性能跨平台 2D 战术游戏界面而定制的**最高规格约束文档**。本说明书旨在“框死”一切开发细节，明确划清前台 UI 渲染层与后台 17 个并发引擎的物理边界，杜绝任何 AI 幻觉、无脑生成或篡改底层稳定代码的可能。

> **后台引擎模块与前端 UI 完整对照索引**
>
> | 后台引擎模块 (Python) | 对应前端 UI 界面 / 组件 (Compose) | 核心数据协议 / PlayerCommand |
> |---|---|---|
> | `engine.py` / `TickService.kt` (Tick 驱动 + 前台服务) | `ResourceHeader.kt`（顶部资源状态栏 + 晨会/暂停/播放按钮） | `MapStateSnapshot.tickId`, `timeOfDay`, `dayCount` |
> | `territory.py` (领地单例容器，全局状态唯一入口) | 全部 Tab 共享 `GameViewModel` | `MapStateSnapshot` 全部只读字段 |
> | `map_generator.py` (地图生成) + `daynight.py` (昼夜) | `MapScreen.kt` + `MapCanvas.kt`（地图 Tab，第1~8级渲染管线） | `TileSnapshot`, `ExploreStatus`, 迷雾/滤镜渲染 |
> | `villager.py` (村民实体) | `EntitySheet.kt`（村民 BottomSheet）+ `MapCanvas.kt` 翡翠绿粒子渲染 | `EntityGlowPoint(type="VILLAGER")`, `PlayerCommand.AssignJob` |
> | `adventurer.py` (冒险者实体) | `EntitySheet.kt`（冒险者 BottomSheet）+ `BattlePanel.kt`（异步战斗） | `EntityGlowPoint(type="ADVENTURER")`, `PlayerCommand.DispatchAdventurer` |
> | `caravan.py` (商队实体) | `EntitySheet.kt`（商队 BottomSheet） | `EntityGlowPoint(type="CARAVAN")`, `PlayerCommand.DispatchCaravan` |
> | `monster.py` (怪物实体) | `MapCanvas.kt`（深渊紫粒子渲染） | `EntityGlowPoint(type="MONSTER")`, 自动/手动战斗 |
> | `building.py` (建筑) | `TerritoryScreen.kt`（建筑卡片列表）+ `MapCanvas.kt` 像素图标渲染 | `TileSnapshot.buildingSymbol`, `PlayerCommand.BuildBuilding`, `PlayerCommand.UpgradeBuilding` |
> | `economy.py` (资源结算) | `ResourceHeader.kt`（金木石铁药实时数字）+ `ReportScreen.kt`（折线趋势图） | `MapStateSnapshot` 资源字段 (gold/wood/stone/iron/herbs/food) |
> | `warehouse.py` (仓库物流) | `TerritoryScreen.kt`（仓库容量进度条）+ `EntitySheet.kt`（仓库详情面板） | `MapStateSnapshot.warehouseSnapshots` |
> | `trade.py` / `market_dynamics.py` (贸易与动态物价) | `ReportScreen.kt`（城邦市场总览表格）+ `EntitySheet.kt`（城邦物价表 / 价格历史） | `MapStateSnapshot.marketData`, `PlayerCommand.DispatchCaravan` |
> | `combat.py` (异步战斗系统) | `BattlePanel.kt`（半屏战斗答题面板，占屏幕 45%） | `ActiveBattleSnapshot`, `PlayerCommand.AnswerQuestion` |
> | `forge.py` (铁匠铺 / 装备强化 / 拆解) | `TerritoryScreen.kt`（铁匠铺卡片）+ `EntitySheet.kt`（装备面板 / 武器防具槽位） | `PlayerCommand.ForgeItem`, `PlayerCommand.UpgradeEquipment`, `PlayerCommand.RepairEquipment`, `PlayerCommand.DismantleEquipment` |
> | `workshop.py` (工房 / 工具制造队列) | `TechScreen.kt`（工房子标签页）+ 生产进度条 | `PlayerCommand.QueueProduction`, `MapStateSnapshot.workshopQueue` |
> | `policy.py` (政策法令) | `TerritoryScreen.kt`（法令面板 / 激活/停用开关） | `MapStateSnapshot.activePolicyNames`, `PlayerCommand.EnactPolicy` |
> | `technology.py` (科技树 + 科学院考核) | `TechScreen.kt`（科技星图卡片视图）+ `AcademyExamDialog.kt`（考核沙盒全屏弹窗） | `MapStateSnapshot.techProgress`, `PlayerCommand.StartResearch`, `PlayerCommand.SubmitExamAnswer` |
> | `weather.py` (天气系统) | `ResourceHeader.kt`（天气图标）+ `MapCanvas.kt` 第8级滤镜叠加 | `MapStateSnapshot.weatherModifiers` (含 drought/storm/blizzard 子字段) |
> | `event_engine.py` (突发事件：火灾/洪水/瘟疫/Boss骚乱/寒流/坍塌) | `LogConsoleScreen.kt`（灾难预警消息，橙色高亮）+ `MapCanvas.kt` 地图特效标记 | `MapStateSnapshot.activeEvents`, `PlayerCommand.DispatchEmergency` |
> | `relic_discovery.py` (遗迹代码修复) | `RelicCodeDialog.kt`（泛黄纸张主题修复面板，半屏 BottomSheet） | `MapStateSnapshot.relicCodeState`, `PlayerCommand.OpenRelicCode`, `PlayerCommand.SubmitRelicFix` |
> | `treasure_hunt.py` (藏宝图寻宝) | `EntitySheet.kt`（藏宝图详情弹窗 / 寻宝进度） | `MapStateSnapshot.treasureMaps`, `PlayerCommand.StartTreasureHunt` |
> | `pathfinding.py` (寻路算法) | `MapCanvas.kt`（青蓝色虚线移动路径预览 + 目标终点闪烁箭头） | 无独立命令，嵌入 `PlayerCommand.DispatchXxx` |
> | `function_lib.py` (函数库持久化) | `CodeEditor.kt`（函数库标签页）+ `StrategyManager.kt`（策略管理面板） | `PlayerCommand.SaveFunction`, `PlayerCommand.LoadFunction` |
> | `debug_translator.py` (村长翻译官) | `LogConsoleScreen.kt`（可折叠错误卡片）+ `Dialogues.kt`（非模态村长气泡，3秒自动消失） | 无独立数据合约，通过日志流注入翻译卡片 |
> | `sandbox.py` (沙箱执行器) | `CodeEditor.kt`（积木/填空/手打三模式编辑器） | `PlayerCommand.ExecuteScript` |
> | `simulation.py` (仿真预测) | `ReportScreen.kt`（仿真预测卡片，钻石学位解锁） | `PlayerCommand.RunSimulation` |
> | `guild.py` (冒险者公会 / 酒馆招募) | `TerritoryScreen.kt`（酒馆招募面板）+ 冒险者列表 | `PlayerCommand.RecruitAdventurer` |
> | `labor.py` (劳动系统 / 搬运工分配) | `MapCanvas.kt`（实体移动动画 + 轨迹指示）+ `EntitySheet.kt`（搬运工状态） | `PlayerCommand.AssignJob("搬运工")` |
>
> **术语标准化对照（游戏说明书 ↔ UI 说明书 → 统一术语）**：
>
> | 游戏说明书术语 | UI 说明书旧术语 | 统一采用 | 备注 |
> |---|---|---|---|
> | “代码编辑器” | “魔导书” | **代码编辑器** | 功能本质是 Python IDE，改直白命名 |
> | “教学学位”（青铜/白银/黄金/钻石） | 未提及 | **教学学位** | 四个阶段对应 API 逐级解锁 |
> | “晨会时间” | 未提及 | **晨会时间** | 手动模式清晨弹性时间（60秒），状态栏变金色 |
> | “村长翻译官” | 未明确 | **村长翻译官** | Python 错误拦截 + 村长口吻翻译 + 修正建议卡片 |
> | “资源状态看板” (HP/MP/LVL/EXP) | Resource Counters | **资源状态看板** | 包含 HP（当前/最大）、MP（玩家答题点数）、LVL（学位阶段）、EXP（经验进度%） |
> | “城邦市场” | 未提及 | **城邦市场总览** | 列在报表 Tab 中，显示所有已知城邦物价与涨跌箭头 |
> | “异步战斗面板” | BattlePanel | **异步战斗面板** | 45% 屏占比的底部面板，含倒计时圆环、题目、选项按钮、MP技能按钮 |
> | “遗迹代码修复面板” | RelicCodeDialog | **遗迹代码修复面板** | 泛黄纸张主题，衬线字体，Bug行高亮可编辑 |
> | “科技考核沙盒” | AcademyExamDialog | **科技考核沙盒** | 全屏弹窗，含题目、代码编辑区、运行/提示按钮 |
> | “策略管理面板” | StrategyManager | **策略管理面板** | 管理玩家保存的 Tick 策略脚本，含触发条件配置 |


> [!IMPORTANT]
> **绝对开发原则（签字生效约束）**
> 1. **严禁越线修改**：在得到你对本说明书的显式“同意”审批之前，本 AI 绝对不对 `rpgyouxi` 项目中的任何代码进行实际开发、脚手架升级或包目录创建。
> 2. **数据只读单向流动**：前台渲染层仅允许通过只读的 `StateFlow` 机制订阅全局视图快照，绝对禁止任何 UI 界面直接修改后台实体（如村民、冒险者、建筑、地块等）的成员变量。
> 3. **指令事务性提交**：前台所有的按钮交互与策略部署，必须统一封装为 `PlayerCommand`，通过 `ActionProcessor` 的并发队列，由 `GameLoopCoordinator` 周期性地原子触发，规避读写撕裂与并发锁死灾难。
> 4. **100% 对齐核心引擎真实数值**：本说明书已物理校验 `CombatEngine.kt` 与 `SharedModels.kt` 源码，MP 消耗、技能映射、状态定义绝无半点捏造或幻觉！

---

## 一、 项目环境与 KMP 脚手架目录规格

为实现 Windows 桌面端及移动端的多端同构开发，我们将 `rpgyouxi` 重构为 Kotlin Multiplatform (KMP) + Jetbrains Compose 声明式框架。

### 1.1 核心构建配置规范 (build.gradle.kts)
项目的构建系统升级仅包含官方稳定库，版本号被绝对框定为：
*   **Kotlin Compiler / JVM**: `1.9.22` (Java 目标版本 17)
*   **Compose Multiplatform**: `1.6.0` (JetBrains 官方声明式组件)
*   **Kotlinx Coroutines Core**: `1.7.3` (协程异步队列及 UI 轮询，兼容现有底层)
*   **Kotlinx Serialization JSON**: `1.6.2` (驱动本地状态 Save/Load 加密序列化)

### 1.2 共享 UI 层物理目录结构
在 `rpgyouxi/app/src/commonMain/kotlin` 下创建且仅创建以下包结构，以实现最高效的代码隔离与组件复用：

```
rpgyouxi/app/src/
│
└── commonMain/kotlin/com/example/pythonrpg/ui/
    ├── MainActivity.kt        # 游戏应用多端共享引导入口
    │
    ├── state/
    │   ├── GameViewModel.kt   # 唯一暴露 UI 只读状态的 ViewModel 管道控制器
    │   └── UIStateModels.kt   # UI 专属的辅助状态包装类定义
    │
    ├── navigation/
    │   └── BottomNavBar.kt    # 🗺️ 🏡 📈 ⚙️ 💬 底部五个 Tab 的霓虹高发光导航栏
    │
    ├── screens/
    │   ├── MapScreen.kt       # [Tab 1] 2D 亚克力战术沙盘主屏（包裹 MapCanvas）
    │   ├── TerritoryScreen.kt # [Tab 2] 领地房屋工作排班与民居村民管理面板
    │   ├── ReportScreen.kt    # [Tab 3] 市场价格指数与商队交易物价折线报表屏
    │   ├── TechScreen.kt      # [Tab 4] 科学院科技星座星图及资源质押管理屏
    │   └── LogConsoleScreen.kt# [Tab 5] 黑客命令行式系统日志及村长错误预警屏
    │
    └── components/
        ├── MapCanvas.kt       # 2D 亚克力霓虹网格与流星粒子高性能 Canvas 渲染器
        ├── CodeEditor.kt      # 羊皮魔导书高亮 Python 脚本编辑器
        ├── BattlePanel.kt     # 底部抽屉式手动 QTE 答题死斗与三大 MP 技能面板
        └── ResourceHeader.kt  # 顶部日晷环与资源（金币/木/石/铁/药草/全局MP）状态条
```

### 1.3 静态水晶地标贴图资源规范
为达成零 3D 多边形算力消耗的 **3.5D 全息发光水晶视觉**，我们全部使用离线预制的高保真 Isometric 水晶贴图，绝对固定路径为：
*   **大本营城堡 (🏰)**: `commonMain/resources/images/crystal_castle.png` (分辨率限定 `128x128px`, 琥珀黄折射发光)
*   **村民民居 (🏠)**: `commonMain/resources/images/crystal_house.png` (分辨率限定 `96x96px`, 冰蓝色折射发光，附带夜晚屋顶发光通道)
*   **熔炉工坊 (🪓)**: `commonMain/resources/images/crystal_workshop.png` (分辨率限定 `96x96px`, 翡翠绿折射发光，附带风箱工作粒子通道)
*   **城邦摩天 (🏙️)**: `commonMain/resources/images/crystal_citystate.png` (分辨率限定 `112x112px`, 全息幻彩渐变发光)

---

## 二、 界面整体布局与模块交互示意 (UI Wireframe)

整个主界面采用**暗色高透玻璃磨砂（Glassmorphism）与霓虹高发光（Neon Glow）**极客极简视觉风。

```
┌────────────────────────────────────────────────────────────────────────┐
│ [ResourceHeader] 🪙:1200  🪵:350  🪨:210  ⛏️:80  🌿:45  ⚡(MP):85/100  [☀️ 晷] │
├─────────────────────────────────────────┬──────────────────────────────┤
│                                         │                              │
│                                         │  [CodeEditor 魔导书策略编辑器] │
│                                         │  ┌────────────────────────┐  │
│  [MapCanvas 2D 亚克力霓虹沙盘]          │  │ 1. import sys           │  │
│  ┌───────────────────────────────────┐  │  │ 2. def auto_assign():   │  │
│  │ 🏰城堡               🌲深绿树林   │  │  │ 3.   if wood < 200:     │  │
│  │                     (可见但迷雾)  │  │  └────────────────────────┘  │
│  │  🧑‍🌾 ➔ (流星粒子)                  │  │  [AST校验指示灯] 🟢 正常      │
│  │                      🔒 Boss锁定 │  │  [Deploy Strategy] 按键      │
│  │  🏠民房(夜晚亮灯)     🚶冒险者⚔️   │  │                              │
│  └───────────────────────────────────┘  └──────────────────────────────┘
├────────────────────────────────────────────────────────────────────────┤
│ [BottomNavBar]   🗺️ 战术沙盘   🏡 领地工坊   📈 物价报表   ⚙️ 科技星座   💬 日志系统│
├────────────────────────────────────────────────────────────────────────┤
│ [BattlePanel (死斗弹出)]   adventurer HP: 85%  [||||||||]  Monster HP: 60%       │
│ ❓ 题目: 请问下面哪条是Python合法变量名？ A/B/C/D选项                         │
│ [⚡5 延时] (-5MP)           [⚡8 排除] (-8MP)          [⚡15 秒答] (Boss战禁用)│
└────────────────────────────────────────────────────────────────────────┘
```

> [!NOTE]
> **🖼️ 完整主界面高拟真布局参考图 (Complete Interface Reference)**
> 
> 本框架在 [C:\PythonRPG\ui逻辑\](file:///C:/PythonRPG/ui%E9%80%BB%E8%BE%91/) 目录下存放了最终实现的完整高保真 UI 界面图：
> - **完整主界面图**：[complete_ui_layout.png](file:///C:/PythonRPG/ui%E9%80%BB%E8%BE%91/complete_ui_layout.png)
> - **说明**：该图展示了**完整的游戏主界面**，完美集成了左侧的 `SCRIPT EDITOR` 策略编辑器面板、中间的 2D 霓虹沙盘地图网格、右侧的 `ENTITY LOG` 实体运行日志终端控制台与资源数值看板，以及顶部的世界时钟和底部的全局控制组件。前端在进行 KMP/Compose 编码实现时，必须 100% 对齐此图的布局比例、亚克力磨砂玻璃滤镜、自适应毛玻璃底色以及暗黑高拟真霓虹发光的视觉风格。

---

## 三、 Canvas 8 级渲染管线与夜晚自然剪裁 (MapCanvas.kt Spec)

`MapCanvas` 承担了高频地图刷新的性能重任。通过**视口剪裁（Viewport Culling）**与**二八原则渲染优化**实现超流畅帧率。

### 3.1 坐标物理转换公式
为了确保缩放平移正常，地图单元格坐标 `(x, y)` 转换到 Canvas 画布绝对像素坐标 `(px, py)` 严格符合以下算法：
$$\begin{cases} px = (x \times \text{tileSize} + \text{offsetX}) \times \text{scale} \\ py = (y \times \text{tileSize} + \text{offsetY}) \times \text{scale} \end{cases}$$
*   `tileSize` 预设为常数 `64.dp`。
*   `scale` 动态区间限制在 `[0.5f, 3.0f]` 之间，由手势缩放修改。
*   `offsetX`/`offsetY` 随玩家拖拽平滑平移。

### 3.2 Canvas 8 级串行渲染管线
在单个 `onDraw` 重绘节拍内，绘制代码顺序必须严格如下，且只读取已从全局 ViewModel 汇总好的 `TileSnapshot` 与 `EntityGlowPoint` 列表：

1.  **[第 1 级] 地形底色填充 (drawTerrainColors)**
    *   草原 (`PLAINS`/`GRASSLAND`) 填充抹茶绿 `Color(0xFF8FBC8F)`。
    *   树林 (`FOREST`) 填充墨黛绿 `Color(0xFF2E8B57)`。
    *   山脉 (`MOUNTAIN`) 填充大理石暖灰 `Color(0xFF708090)`。
    *   冻土 (`TUNDRA`) 填充冰川粉蓝 `Color(0xFFADD8E6)`。
    *   火山 (`VOLCANO`) 填充暗红熔岩 `Color(0xFF8B0000)`。
2.  **[第 2 级] 细霓虹网格边界 (drawGridLines)**
    *   沿每个 Grid 的四周边界绘制粗度为 `1dp` 且具有 10% 透明度的淡白色霓虹线 `Color(0x1AFFFFFF)`。
3.  **[第 3 级] 静态 3.5D 水晶地标 (drawCrystalBuildings)**
    *   将 `buildingId != null` 的地块，按照地标类型通过 `drawImage` 绘制对应预制水晶贴图。
    *   大本营城堡居中绘制，占据多格视觉。
4.  **[第 4 级] 首领与怪物警示 (drawMonsterBosses)**
    *   被 `isBossLocked == true` 锁定的格子上，在其上层居中绘制一个具有微微搏动发光的红色水晶骷髅全息徽记。
5.  **[第 5 级] 磨砂玻璃战争迷雾 (drawFrostedGlassFog)**
    *   `ExploreStatus.UNEXPLORED` 地块：绘制 100% 纯黑色高斯模糊遮盖。
    *   `ExploreStatus.VISIBLE_UNEXPLORED` 地块：覆盖 40% 不透明度的亚光半透磨砂黑色滤镜 `Color(0x66000000)`。
6.  **[第 6 级] 通勤实体粒子与流星尾巴 (drawEntityGlowPoints) — 【重点性能与状态匹配】**
    *   *夜晚与休眠裁剪规则*：严格对齐游戏说明书规则。在深夜，**仅隐藏状态为 `VillagerStatus.SLEEPING` 且已安全回到民房休息的村民图标**（其人在民房内，故粒子点不画，改由民房水晶贴图下方亮窗灯光表示）。
    *   *未归家与夜行实体正常绘制*：在夜晚，**未赶回家的在途村民（状态非SLEEPING）、执行夜间移动任务的搬运工（CARRIER）、夜行商队（CARAVAN）以及自由战斗移动的冒险者（ADVENTURER）**，其发光粒子点与流星尾巴**必须 100% 正常绘制**，绝不进行无脑零化。
    *   *视口裁剪（Viewport Culling）优化*：真正的性能优化通过**视口剪裁**实现，即只绘制当前屏幕可视区域（Viewport）内的实体，完全过滤屏幕外的地块与粒子绘制，而非靠强行隐藏夜行实体来偷懒。
    *   *绘制细节*：根据 `EntityGlowPoint` 属性在实体当前的 `(px, py)` 处绘制一个半径为 `4.dp` 的 `RadialGradient` 径向发光像素粒子圆点。
    *   *拖尾效果*：若 `isCarryingTrail == true`，顺着其反向移动向量绘制一段 `Alpha` 渐变消散的流星线段尾迹（最大长度不超过 `8dp`，头部高亮，尾部虚化）。
7.  **[第 7 级] 战斗交火指示 (drawCombatIndicators)**
    *   若冒险者当前处于 `AdventurerStatus.COMBAT` 或 `FIGHTING`，在该格子正上方绘制一个具有 600ms 周期呼吸发光的霓虹双剑交叉 `⚔️` 动画。
8.  **[第 8 级] 金色选中霓虹框 (drawSelectionHighlight)**
    *   对玩家当前触摸选中的 Tile，用 `Color(0xFFFFD700)` 绘制粗度为 `2dp`、外圈带 2.5 维高发光效果的圆角矩形描边。

### 3.3 昼夜交替自然色彩滤镜 (Day-Night Filter)
在 Canvas 顶层覆盖一层随着昼夜时钟 (`TimePeriod`) 推进而平滑过渡的滤镜，保证视觉沉浸感：
*   `MORNING`（清晨）：15% 不透明度的琥珀暖金色滤镜 `Color(0x26FFD700)`。
*   `DAYTIME`（白昼）：完全无色透明，展现最高清晰度。
*   `TWILIGHT`（黄昏）：20% 不透明度的熔金橘红色滤镜 `Color(0x33FF4500)`。
*   `NIGHT`（深夜）：35% 不透明度的高透冷青靛蓝色滤镜 `Color(0x590A1128)`。
*   *亮灯机制*：当进入 `NIGHT` 时，`isNightActive == true` 的民居水晶贴图下方，绘制一个半径为 `16dp` 的淡黄色漫反射光斑，模拟村民“回房掌灯”的温馨温馨生活质感。

---

## 四、 交互按键操作与 MP 奥义扣减规则 (Interaction Specs)

本模块规格已与底层 `CombatEngine.kt` 和 `SharedModels.kt` 严密核实，杜绝任何 API 数值偏差。

### 4.1 底部导航五个 Tab 路由交互
*   **点按**：切换 `viewModel.currentScreen`。
*   **视觉反馈**：被选中的 Tab 按钮卡面整体亮起 15% 透明度的霓虹发光色，文字与图标颜色变更为高对比度发光白，未选中项保持 40% 暗灰半透。

### 4.2 答题决战三大 MP 辅助技能按钮
这三个按钮位于底部抽屉 `BattlePanel` 中，**只能在冒险者处于非挂机战斗状态时使用**：

1.  **`⚡ 延时` 按键**
    *   *消耗*：**5 点 MP** (严格核实 `CombatEngine.MpSkill.EXTEND_TIME`)
    *   *动作*：玩家点按 ➔ 异步封装 `viewModel.useMpSkill(MpSkill.EXTEND_TIME)` ➔ 后台扣减 5 MP ➔ 前台 QTE 60fps 倒计时进度条瞬间向右回弹 +5 秒。
    *   *状态置灰*：若当前冒险者 MP < 5，该按钮强制变灰，点按无效。
2.  **`🎯 排除` 按键**
    *   *消耗*：**8 点 MP** (严格核实 `CombatEngine.MpSkill.ELIMINATE`)
    *   *动作*：玩家点按 ➔ 异步封装 `viewModel.useMpSkill(MpSkill.ELIMINATE)` ➔ 后台扣减 8 MP ➔ 前台 UI 选项中随机两个错误项按钮底色变灰，文字添加删除线，点击拦截置灰失效。
    *   *状态置灰*：若当前冒险者 MP < 8，或当前题目已经排除过，该按钮变灰禁用。
3.  **`🤖 秒答` 按键**
    *   *消耗*：**15 点 MP** (严格核实 `CombatEngine.MpSkill.AUTO_CORRECT`)
    *   *动作*：玩家点按 ➔ 异步封装 `viewModel.useMpSkill(MpSkill.AUTO_CORRECT)` ➔ 后台进行 Boss 战拦截（如果是 Boss 强行失败并拦截，普通怪成功）➔ 后台扣减 15 MP ➔ 前台执行正确答题动效。
    *   *状态置灰*：若当前冒险者 MP < 15，或者当前交战怪物是 **Boss (isBoss == true)**，该按钮强制置灰并带锁孔图表。

### 4.3 答题 Combo 连击及屏幕特效
*   **Combo 机制**：玩家连续答对问题时，会促使 `comboCount` 递增。
*   **连击特效**：当 `comboCount >= 3` 时，UI 上显示的连击数字文本外围激活一个由轻量 Canvas 画出的 **像素火焰喷薄动效**。同时，每次 Combo 递增会触发屏幕轻微的 2dp 物理抖动偏移（通过临时修改 Canvas 转换 Offset 实现），带给玩家畅快打击感！

### 4.4 魔导书“策略部署”按键
*   **操作**：在 `CodeEditor.kt` 中编写 Python 脚本后，点击下方 `[Deploy Strategy]` 按钮。
*   **AST 校验流程**：
    1.  UI 捕捉编辑器字符串文本。
    2.  调用 `viewModel.submitAction(PlayerCommand.DeployScriptCommand(scriptText))` 送往后台。
    3.  后台 `PythonScriptBridge` 进行 AST 安全性过滤。
    4.  若包含高危操作（如 `import os`，`__import__` 等），或者检测到语法死循环，编辑器整体发生 3 次高频闪红报警，并在屏幕右上角浮现老村长的“警告木牌气泡”；若校验 100% 绿色安全，编辑器顶部扩散一圈淡蓝色六芒星魔法阵发光粒子，策略部署成功生效。

---

## 五、 前后台状态订阅与命令分发数据契约

前后台通信有且仅有以下两条严格数据管道：

### 5.1 状态数据契约 (StateFlow: 后台 ➔ 前台)
全局 UI 状态汇总包装在 `MapStateSnapshot` 数据类中，前台 UI 绝对禁止对其进行任何直接 setter 操作：

```kotlin
package com.example.pythonrpg.ui.state

import com.example.pythonrpg.shared.*

// 全局前台 UI 视图只读快照数据类
data class MapStateSnapshot(
    val tickId: Long,
    val timeOfDay: TimePeriod,
    val dayCount: Int,
    val weatherModifiers: WeatherModifiers,
    val policyModifiers: PolicyModifiers,
    // 核心资源（王城可用库存 + 全局金币）
    val gold: Int,
    val wood: Int,
    val stone: Int,
    val iron: Int,
    val herbs: Int,
    val food: Int,
    // 玩家答题点数（当前可用 / 最大上限，用于 BattlePanel MP 扣减逻辑）
    val activeAdventurerMp: Int,
    val maxAdventurerMp: Int,
    // 教学学位阶段（BRONZE / SILVER / GOLD / DIAMOND）
    val currentDegree: String,
    
    // === 只读扁平列表 ===
    val tiles: List<TileSnapshot>,
    val entities: List<EntityGlowPoint>,
    val systemLogs: List<String>,
    
    // === 铁匠铺状态 ===
    val forgeQueue: List<ForgeTaskSnapshot>,
    
    // === 工房生产状态 ===
    val workshopQueue: List<WorkshopTaskSnapshot>,
    
    // === 仓库状态快照 ===
    val warehouseSnapshots: List<WarehouseSnapshot>,
    
    // === 城邦市场数据 ===
    val marketData: List<CityStateMarketSnapshot>,
    
    // === 科技进度 ===
    val techProgress: List<TechNodeSnapshot>,
    
    // === 活跃法令 ===
    val activePolicyNames: List<String>,
    
    // === 活跃突发事件 ===
    val activeEvents: List<ActiveEventSnapshot>,
    
    // === 遗迹代码状态 ===
    val relicCodeState: RelicCodeSnapshot?,
    
    // === 藏宝图列表 ===
    val treasureMaps: List<TreasureMapSnapshot>,
    
    // === 活跃战斗会话（多个冒险者可能同时在战斗） ===
    val activeBattleSessions: List<ActiveBattleSnapshot>,
    
    // === 冒险者队伍 ===
    val adventurerParty: List<AdventurerSnapshot>,
    
    // === 商队编队 ===
    val caravanFleet: List<CaravanSnapshot>,
    
    // === 可用策略列表 ===
    val savedStrategies: List<StrategyMetaSnapshot>
)

data class TileSnapshot(
    val x: Int,
    val y: Int,
    val terrainTypeId: String,
    val exploreStatus: ExploreStatus,
    val isBossLocked: Boolean,
    val hasMonster: Boolean,
    val buildingSymbol: String?, // 🏰, 🏠, 🪓, 🏙️ 
    val buildingId: Long?,
    val isNightActive: Boolean   // 夜晚房屋是否点亮灯火
)

data class EntityGlowPoint(
    val id: Long,
    val type: String,            // "VILLAGER", "ADVENTURER", "CARAVAN"
    val coordinate: Coordinate,
    val targetCoordinate: Coordinate?,
    val status: String,          // IDLE, WORKING, COMBAT, SLEEPING 等
    val carriesCargo: Boolean,   // 前台绘制微型 🪵/🪨 交付小图标的标识
    val isSleepingInHouse: Boolean // 是否处于睡眠且已在房内休眠状态（若是则前台隐藏粒子，通过房里亮灯表示）
)

data class ActiveBattleSnapshot(
    val sessionId: Long,
    val adventurerId: Long,
    val monsterName: String,
    val monsterMaxHp: Int,
    val monsterCurrentHp: Int,
    val adventurerMaxHp: Int,
    val adventurerCurrentHp: Int,
    val comboCount: Int,
    val currentQuestion: String,
    val options: List<String>,
    val countdownRemainingSeconds: Float
)
```

### 5.2 异步命令分发契约 (PlayerCommand: 前台 ➔ 后台)
前台用户的一切带有“状态修改意图”的点击手势均包装为 `PlayerCommand` 实例并调用 `viewModel.submitCommand(cmd)`：

```kotlin
// 前台按钮完全映射的 PlayerCommand 类目清单 (严密对齐 SharedModels.kt)
// 覆盖全部后端引擎模块：建筑/村民/冒险者/商队/战斗/铁匠铺/工房/科技/政策/遗迹/寻宝/策略
val cmd = when(actionType) {
    // === 建筑操作 ===
    "BUILD" -> PlayerCommand.BuildBuilding(x = selectX, y = selectY, buildingType = "HOUSE")
    "UPGRADE" -> PlayerCommand.UpgradeBuilding(x = selectX, y = selectY)
    "DEMOLISH" -> PlayerCommand.DemolishBuilding(x = selectX, y = selectY)
    
    // === 村民操作 ===
    "ASSIGN_JOB" -> PlayerCommand.AssignJob(villagerId = 101L, job = "LUMBERJACK", targetX = 5, targetY = 12)
    "RECALL_VILLAGER" -> PlayerCommand.RecallVillager(villagerId = 101L)
    "EQUIP_TOOL" -> PlayerCommand.EquipTool(villagerId = 101L, toolName = "铁斧")
    
    // === 冒险者操作 ===
    "DISPATCH_ADVENTURER" -> PlayerCommand.DispatchAdventurer(adventurerId = 201L, targetX = 15, targetY = 15)
    "RECALL_ADVENTURER" -> PlayerCommand.RecallAdventurer(adventurerId = 201L)
    "SET_ESCORT" -> PlayerCommand.SetEscort(adventurerId = 201L, caravanId = 301L)
    "SET_COMBAT_STRATEGY" -> PlayerCommand.SetCombatStrategy(adventurerId = 201L, strategyName = "bossFleeStrategy")
    "RECRUIT_ADVENTURER" -> PlayerCommand.RecruitAdventurer()
    
    // === 商队操作 ===
    "DISPATCH_CARAVAN" -> PlayerCommand.DispatchCaravan(caravanId = 301L, destinationCity = "黑铁堡")
    "RECALL_CARAVAN" -> PlayerCommand.RecallCaravan(caravanId = 301L)
    "SET_CARAVAN_MODE" -> PlayerCommand.SetCaravanMode(caravanId = 301L, mode = "内部运输")
    "CARAVAN_LOAD_CARGO" -> PlayerCommand.CaravanLoadCargo(caravanId = 301L, cargo = mapOf("木材" to 50))
    "CARAVAN_SET_PICKUP" -> PlayerCommand.CaravanSetPickup(caravanId = 301L, warehouseX = 3, warehouseY = 5)
    "CARAVAN_SET_DROPOFF" -> PlayerCommand.CaravanSetDropoff(caravanId = 301L, warehouseX = 0, warehouseY = 0)
    
    // === 战斗操作 ===
    "ANSWER_QUESTION" -> PlayerCommand.AnswerQuestion(sessionId = 401L, answerIndex = 1)
    "USE_BATTLE_SKILL" -> PlayerCommand.UseBattleSkill(sessionId = 401L, skillName = "延时", mpCost = 5)
    "AUTO_BATTLE" -> PlayerCommand.AutoBattle(sessionId = 401L)
    "FLEE_BATTLE" -> PlayerCommand.FleeBattle(sessionId = 401L)
    
    // === 铁匠铺操作 ===
    "FORGE_ITEM" -> PlayerCommand.ForgeItem(itemName = "铁剑")
    "UPGRADE_EQUIPMENT" -> PlayerCommand.UpgradeEquipment(equipmentId = 501L)
    "REPAIR_EQUIPMENT" -> PlayerCommand.RepairEquipment(equipmentId = 501L)
    "REPAIR_ALL" -> PlayerCommand.RepairAllEquipment(adventurerId = 201L)
    "DISMANTLE_EQUIPMENT" -> PlayerCommand.DismantleEquipment(equipmentId = 501L)
    
    // === 工房操作 ===
    "QUEUE_PRODUCTION" -> PlayerCommand.QueueProduction(toolName = "铁斧", quantity = 5)
    "CANCEL_PRODUCTION" -> PlayerCommand.CancelProduction(taskId = "task_001")
    
    // === 科技与科学院操作 ===
    "START_RESEARCH" -> PlayerCommand.StartResearch(techId = "METALLURGY")
    "SUBMIT_EXAM_ANSWER" -> PlayerCommand.SubmitExamAnswer(techId = "METALLURGY", answer = "print('hello')")
    "CANCEL_RESEARCH" -> PlayerCommand.CancelResearch(techId = "METALLURGY")
    
    // === 政策法令操作 ===
    "ENACT_POLICY" -> PlayerCommand.EnactPolicy(policyType = "MERCANTILISM", isActive = true)
    
    // === 突发事件操作 ===
    "DISPATCH_EMERGENCY" -> PlayerCommand.DispatchEmergency(eventId = 601L, responderId = 101L)
    
    // === 遗迹代码操作 ===
    "OPEN_RELIC_CODE" -> PlayerCommand.OpenRelicCode(relicId = "relic_logistics_001")
    "SUBMIT_RELIC_FIX" -> PlayerCommand.SubmitRelicFix(relicId = "relic_logistics_001", fixedCode = "def fix(): ...")
    
    // === 藏宝图操作 ===
    "START_TREASURE_HUNT" -> PlayerCommand.StartTreasureHunt(mapId = "treasure_silver_001")
    
    // === 策略与函数库操作 ===
    "EXECUTE_SCRIPT" -> PlayerCommand.ExecuteScript(codeString = "for v in territory.get_idle_villagers(): ...")
    "SAVE_FUNCTION" -> PlayerCommand.SaveFunction(name = "全员工具维护", code = "def ...")
    "LOAD_FUNCTION" -> PlayerCommand.LoadFunction(name = "全员工具维护")
    "DELETE_FUNCTION" -> PlayerCommand.DeleteFunction(name = "全员工具维护")
    "SAVE_STRATEGY" -> PlayerCommand.SaveStrategy(name = "综合调度 v1", triggerCondition = "每 Tick", code = "def ...")
    
    // === 仓库操作 ===
    "EXPAND_WAREHOUSE" -> PlayerCommand.ExpandWarehouse(warehouseX = 0, warehouseY = 0)
    "UPGRADE_TO_DISTRIBUTION" -> PlayerCommand.UpgradeToDistributionCenter(warehouseX = 3, warehouseY = 5)
    
    // === 仿真预测 ===
    "RUN_SIMULATION" -> PlayerCommand.RunSimulation(ticksToSimulate = 30)
    
    else -> null
}
```

---


---

## 五-A、 补充 UI 组件规格：引擎模块对应交互面板

以下补充第 2~4 节未覆盖的后台引擎模块对应的完整前端 UI 面板规格。

### 5A.1 铁匠铺面板 (ForgePanel)

铁匠铺是装备锻造、强化、修复与拆解的唯一入口。位于领地 Tab 的卡片列表中，点击铁匠铺卡片弹出全屏管理面板：

```
┌─────────────────────────────────────┐
│  ⚒️ 铁匠铺 Lv.3        [ ✕ 关闭 ]   │
│  ───────────────────────────────── │
│  [锻造新装备]                        │
│  ┌──────┬──────┬──────┬──────┐     │
│  │ 铁剑 │ 铁斧 │ 铁镐 │ 铁甲 │     │
│  │ 🪵20 │ 🪵15 │ 🪵10 │ 🪨30 │     │
│  │ 🪨10 │ 🪨20 │ 🪨15 │ 🪵10 │     │
│  └──────┴──────┴──────┴──────┘     │
│  ───────────────────────────────── │
│  冒险者装备管理                      │
│  ┌ 赵六 (精英 Lv.5) ──────────────┐ │
│  │ 武器: 铁剑 Lv.3 ██████░░ 78/100│ │
│  │ 防具: 皮甲 Lv.2 ████████░ 92/100││
│  │ [🔧修复] [⬆强化] [💥拆解] [🔧一键修复]││
│  └────────────────────────────────┘ │
└─────────────────────────────────────┘
```

- **锻造网格**：图标 + 名称 + 所需材料，材料不足项红色高亮且灰显按钮。
- **强化**：消耗金币 + 铁矿，成功则装备等级 +1，失败则等级 -1（不掉落至 0 以下）。
- **修复**：消耗铁矿恢复单个装备耐久度。
- **一键修复**：消耗铁矿恢复该冒险者全身装备耐久度——对应 `forge.repair_all(adventurer)`。
- **拆解**：销毁装备，返还部分材料（约 50% 建造消耗）——对应 `forge.dismantle(equipment)`。

### 5A.2 工房面板 (WorkshopPanel)

位于科技 Tab 的第二个子标签页，管理工具制造队列：

```
┌─────────────────────────────────────┐
│  🏭 工房 Lv.2                      │
│  ───────────────────────────────── │
│  当前队列 (2/3):                    │
│  ┌ 铁斧 ×5  ████████░░ 剩余 8 Tick │
│  │ [取消]                          │
│  └─────────────────────────────────┘ │
│  ┌ 石镐 ×3  ██░░░░░░░░ 剩余 15 Tick │
│  │ [取消]                          │
│  └─────────────────────────────────┘ │
│  ───────────────────────────────── │
│  [+ 制造新工具]                     │
│  库存预警: 铁斧(2) ⚠️ 石镐(1) ⚠️    │
└─────────────────────────────────────┘
```

- 每个队列项显示图标、数量、进度条、剩余 Tick。
- 取消返回 50% 材料。
- "制造新工具"弹出配方选择对话框——对应 `workshop.queue_production(tool_name, quantity)`。

### 5A.3 酒馆 / 冒险者招募面板 (GuildPanel)

位于领地 Tab 中，点击酒馆卡片打开：

```
┌─────────────────────────────────────┐
│  🍺 酒馆 Lv.2                      │
│  ───────────────────────────────── │
│  可招募冒险者:                      │
│  ┌ 王五  精英 Lv.3  💰150         │
│  │ HP:120 ATK:45 DEF:30           │
│  │ 技能: 重击                      │
│  │ [招募]                         │
│  ├─────────────────────────────────┤
│  │ 李四  普通 Lv.1  💰50          │
│  │ HP:80 ATK:25 DEF:15            │
│  │ [招募]                         │
│  └─────────────────────────────────┘ │
│  刷新倒计时: 12 Tick               │
└─────────────────────────────────────┘
```

- 冒险者品质色边框：白(普通) / 蓝(精英) / 紫(史诗)。
- 金币不足时按钮变灰。
- 酒馆每 24 Tick 自动刷新可选冒险者池。

### 5A.4 政策法令面板 (PolicyPanel)

位于领地 Tab 中，以开关列表形式展示：

```
┌─────────────────────────────────────┐
│  📜 政策法令                       │
│  ───────────────────────────────── │
│  ┌ 口粮配给制 ──────── [激活🔵] ─┐ │
│  │ 食物消耗 -30%，村民体力恢复 -20% │ │
│  └────────────────────────────────┘ │
│  ┌ 全民皆兵 ──────── [停用⚪] ──┐ │
│  │ 所有村民攻击力 +50%，产量 -30%    │ │
│  └────────────────────────────────┘ │
│  ┌ 狂热采集 ──────── [停用⚪] ──┐ │
│  │ 采集速度 +40%，工具耐久消耗 ×2    │ │
│  └────────────────────────────────┘ │
└─────────────────────────────────────┘
```

- 激活中：蓝色开关 + 绿色背景卡片。
- 停用中：灰色开关 + 白色背景卡片。
- 切换即触发 `PlayerCommand.EnactPolicy`，后台即时生效。

### 5A.5 天气与事件监控

天气图标集成于 `ResourceHeader.kt` 左侧：
- ☀️ 晴天 / 🌧️ 暴雨 / ❄️ 寒潮 / 🔥 干旱 / ⛈️ 雷暴
- 点击图标弹出迷你浮窗：天气名 + 对各项效率的加成/减成系数

突发事件在 `LogConsoleScreen.kt` 中以橙色高亮消息出现，同时 `MapCanvas.kt` 在地图对应坐标渲染事件图标：
- 🔥 火灾：红色闪烁标记
- 🌊 洪水：蓝色水纹动画
- ☠️ 瘟疫：绿色骷髅图标
- ⚫ Boss骚乱：紫色警报圈

### 5A.6 背包面板 (InventoryPanel)

全屏 Dialog，顶部三个标签页：📦资源 | ⚔️装备 | 🧪药水

```
┌─────────────────────────────────────┐
│  📦背包          [📦资源|⚔️装备|🧪药水] │
│  ───────────────────────────────── │
│  ┌───┬───┬───┬───┐                │
│  │🪵  │🪨  │⛏️  │🍇  │                │
│  │木材│石材│铁矿│食物│                │
│  │1200│840 │300 │450 │                │
│  └───┴───┴───┴───┘                │
│  ┌───┬───┬───┬───┐                │
│  │🌿  │💊  │⚗️  │🔮  │                │
│  │草药│药水│药剂│卷轴│                │
│  │ 80 │ 15 │  5 │  2 │                │
│  └───┴───┴───┴───┘                │
└─────────────────────────────────────┘
```

装备标签页以槽位形式展示（武器/防具/饰品），药水标签页显示可使用/可装备的消耗品。

### 5A.7 设置面板 (SettingsScreen)

从主界面右上角齿轮图标或底部导航的 ⚙️ 按钮进入。分四个子分类：

| 分类 | 设置项 |
|------|--------|
| 游戏 | Tick速度(3s/5s/10s/15s)、晨会时间开关、自动暂停开关、地图网格线开关、实体图标大小 |
| 通知 | 总开关、黄昏提醒、灾难警报、商队归来、资源预警、勿扰时段 |
| 显示 | 字体大小(小/中/大)、深色模式(跟随系统/始终浅色/始终深色/游戏夜晚自动)、色盲模式、高对比度文本 |
| 存档 | 导出存档(JSON)、导入存档、云存档(预留)、重置游戏(二次确认) |

### 5A.8 晨会时间特殊 UI

当进入晨会时间（手动模式清晨，`timeOfDay == TimePeriod.MORNING && isMorningMeeting`）：
- 顶部 `ResourceHeader` 整条变为**金黄色**背景
- 左侧文字变为"☀️ 晨会时间"
- 右侧出现脉冲动画按钮"开工"
- 点击"开工"向后台提交 `PlayerCommand.EndMorningMeeting`，恢复正常 Tick
- 地图上未分配村民以高亮边框闪烁提示

### 5A.9 编程技能板 (SkillBoard)

位于报表 Tab 中，以"已点亮/待解锁"图标网格展示玩家已掌握的 Python 概念：
- ✅ for循环 / ✅ while循环 / ✅ if判断 / ✅ 函数定义 / 🔒 列表操作 / 🔒 字典操作 / 🔒 排序算法 / 🔒 文件读写
- 判断标准：手打模式下成功运行过包含该概念的脚本即点亮。


## 六、 阶段性开发步骤与严苛验证计划

一旦你下达指令“同意”，我们将按下图所示的里程碑计划推进工程：

```mermaid
graph TD
    A[第 1 阶段: Gradle 升级与 KMP 脚手架建立] --> B[第 2 阶段: 建立只读 GameViewModel 与数据流绑定]
    B --> C[第 3 阶段: 编写 MapCanvas 高性能 8 级渲染管线]
    C --> D[第 4 阶段: 各个 Screen 界面搭建与导航Tab装配]
    D --> E[第 5 阶段: QTE 手动答题 BattlePanel 及 MP 扣减打通]
    E --> F[第 6 阶段: 200 村民高频平滑运动与夜晚自然剪裁压力测试]
```

### 6.1 高频渲染性能压测指标 (Culling & Frame-rate Test)
为了杜绝高频渲染导致的卡顿灾难，在 UI 重构完成后，我们必须执行以下自动化与人工压测检验：
1.  **200 粒子村民压测**：在地图上生成并激活 **200 个通勤村民**高频往返砍树采矿，使用 `FPS monitor` 工具监测，前台界面刷新率必须稳定保持在 **58~60 帧/秒** 之间，且无任何单帧丢帧与主线程卡死。
2.  **夜晚与视口剪裁精确测试 (Culling & Sleep Render Test)**：将时钟切入深夜。
    *   验证所有处于 `SLEEPING`（休眠中且已回到民房内）状态的村民粒子点是否 **100% 隐藏**，并且对应居住的民居水晶贴图下方渲染出漫反射黄色温情烛光圈。
    *   验证所有未归家村民（非 SLEEPING 状态）、夜行商队、搬运工和冒险者粒子点在深夜中是否 **依然能 100% 正常运动和绘制**，没有出现被无脑抹去的严重 Bug。
    *   进行拖动视口测试，验证当粒子点和地块移动到屏幕视野之外时，后台的 Draw 绘制指令是否执行了视口边界过滤（裁剪跳过），极大节省渲染耗能。
3.  **AST 安全警告器震动校验**：在魔导书编辑器中故意输入带有 `import os; os.system("rm -rf /")` 的脚本，点击部署，验证编辑器是否触发 3 次红光闪烁动画，并完全阻截写入操作。

---

> [!CAUTION]
> **请你进行最终的开发前审查**
> 本《RPG UI 框架开发说明书》至此已全部定制完毕。它完全锁死并规范了所有前台结构、8 级渲染层级、MP 扣减数值、以及只读 API 契约，确保零幻觉。
> 
> 如果你仔细阅读后，完全认可此技术架构与实现路线，**请在下方对话框回复“同意”**。
> 一旦接收到你的批准指令，我将立即进入执行状态，首先进行 `rpgyouxi/app/build.gradle.kts` 的 Gradle 多端重构！
