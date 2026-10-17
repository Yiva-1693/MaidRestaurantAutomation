# 女仆餐厅：自动化附属 — 交付说明（v1.0.0 · 阶段一）

> 新 modid：`maid_restaurant_automation`　包名：`com.icewolf.maidrestaurant.automation`
> 工程目录：`forge-1.20.1-automation/`（由 `forge-1.20.1/` 0.2.4-fix3 基线复制改造，两工程并存、可同时安装）
> 目标版本：Minecraft 1.20.1 / Forge 47.2.0+ / Java 17

## 构建

```bat
cd forge-1.20.1-automation
C:\Users\17602\.gradle\wrapper\dists\gradle-7.6.1-bin\b4g3gk9pknhvdxjrakfmqdm18\gradle-7.6.1\bin\gradle.bat build --offline -g ..\forge-1.20.1\.gradle_repo
```

产物：`forge-1.20.1-automation/build/libs/maid_restaurant_automation-1.20.1-forge-1.0.0.jar`（已构建成功，1.3MB）。

> 说明：会话沙箱内 `gradlew.bat` 无法写 `~/.gradle` 的 wrapper 锁文件，故直接用已解压的 Gradle 7.6.1 + 复用基线工程 `.gradle_repo` 缓存（全离线）。若在普通终端构建，直接 `gradlew build` 即可。

## 已实现（对应开发文档条目）

### T2 骨架与清理
- 新工程、新 modid/包名、新 mixins 配置；`mods.toml` 三个前置硬依赖：TLM 1.5.3+、Maid Restaurant 0.2.9+、Order To Cook **1.3.6+**。
- 删除：酒狐速递站（方块/BE/GUI/渲染器/菜单/配置/贴图/模型/配方）、`takeout.toml`、`RestockBridge`（外卖补货）、配送阶段的外卖分支（`STAGE_GO_TO_STATION`/速递站查找/外卖袋送站）、外卖优先派单。
- 保留（按你的选择）：玩家点单/菜单体系、玩家订单经济（预算/小费/退款/店铺收益）、排班表方块、OTC 操作台/餐盘/洗碗机链路。
- 移除等级门槛：`ProgressionManager` 全部闸门恒解锁、员工上限不再按打单机等级（与工位制冲突）。

### T3 调度显示器方块 + 显式订单状态机
- 新方块「餐厅调度显示器」：放置后主动扫描并绑定 16 格内一台未被绑定的打单机（打单机被拆 → 解绑 + 级联释放工位 + GUI 红字提示）。
- `DispatcherBlockEntity` 持久化：绑定打单机、订单状态机、工位绑定表、GUI 快照全部写 NBT；区块卸载/重启后中间态订单降级回 `QUEUED`（状态自愈）。
- `OrderStateMachine`（`DispatcherData.OrderState`）：`QUEUED → PLACED → COOKING → READY → DELEGATED_TO_WAITER → DELIVERING → SERVED → CLOSED`，以及 `FAILED`（含原因）。
- 订单读取：调度显示器直接从打单机公开 `getItems()` 读取堂食单（**过滤外卖：`Delivery=true` 一律忽略**；玩家点单 `PlayerOrder=1` 交给既有玩家链路；已过期单忽略），原子写入最近空闲操作台订单槽 + 复刻 OTC 接单补写机器归属 NBT + 生成顾客 + `onOrderAccepted()`。
- 订单完成/失败感知：**使用 OTC 1.3.6 公开 `OrderLifecycleApi`**（GENERATED/ACCEPTED/PACKED/COMPLETED/EXPIRED/CANCELLED），零 mixin；配合操作台/餐盘/状态观察推进状态机。
- 失败重试：阶段级指数退避（40 tick × 2ⁿ），单阶段上限 3 次；过期/重试超限 → `FAILED` + 调度显示器附近玩家红字聊天提示（禁止静默失败）。
- 到店顾客（walk-in）订单由调度显示器吸纳进队列（`Order_Type==1` 标记为 walk-in），不重复生成顾客。

### T4 工位绑定
- `WorkstationBinding`：`maidUuid / profession / stationUid / stationPos / seatPos / bindTick / invalid`，原子写入调度显示器绑定表并持久化。
- **不写死方块白名单**：厨师类工位复用女仆餐厅本体 `ICookTask.isValidWorkBlock / searchWorkBlock`（自动覆盖炒锅/汤锅/蒸笼/烤箱/烤面包机/搅拌机/农夫乐事锅等所有已注册厨具）；侍者工位 = OTC 操作台。
- 板凳检测复用本体 `TagBlock.SIT_BLOCK`（厨凳/椅子），厨具工位必须有板凳才允许认领。
- 认领成功：工作方块处播放 **`ParticleTypes.HAPPY_VILLAGER` 绿色粒子**（仅首次）+ 女仆气泡；失效：暂停接单 + 气泡 + 每 5 秒优先重认领原坐标，原坐标不可用才搜索新方块（**绝不抢他人工位**）。
- 认领失败：60 秒一次气泡（发气泡前先重试一次认领），文案随职业工位类型动态替换；区分"一个都没有"与"都被别的女仆认领啦"两种文案。
- **未认领 = 不可接单**：烹饪派单候选过滤（未认领女仆不进候选）+ 选台只用自己的绑定工位；厨具类型不匹配的厨师不参与该订单、不消耗重试。
- 扩展职业接入：`StationSystem.registerProfession(taskUid, stationType, displayName)` 注册条目即可（茶博士/调酒师等），另按 UID/类名做启发式兜底。

### T5 制作侧 + 库存优先
- 保留既有烹饪管线（TaskManager 中心缓存、厨具按打单机隔离、备菜、多槽批量、气泡、超时保护），调度显示器在其上加工位过滤与状态观测。
- **库存优先取用（新增 `StockPriority`）**：发布烹饪委托前，先把操作台 24 格内已绑定容器里的现成成品/原料直接搬到操作台并扣减需求；部分库存 → 部分取用 + 剩余走烹饪；同周期内完成，避免两单抢同一份现成物品。

### T6 侍者侧 + 超时订单顶替
- 保留堂食送餐链路（严格 OrderId 匹配、顾客消失安全销毁餐盘、好感度小费）。
- **超时订单顶替**：侍者到台取餐发现操作台被过期订单占用时，调用调度显示器 `forceExpireCounterOrder`：无视该单、由调度显示器标记 `FAILED` 并释放台面、红字提示；侍者不负责清理、不反复尝试（顶替后下一轮重新取餐）。

### T7 调度显示器 GUI
- 「餐厅调度中心」界面：绑定状态 / 餐厅名与店长 / 员工总览（店长 → 玩家（女仆主人）→ 女仆，中文按 `Collator(Locale.CHINA)` 排序，女仆附主人 + 工位坐标 + 职业/厨具类型，底部"另有 N 名女仆未认领工位"）/ 操作台订单队列（按操作台逐份列出菜品、相同菜品一份一格、接单女仆名、剩余时间、失败红色删除线）。
- 服务端生成只读快照（降频 20 tick，内容不变不发包），客户端只渲染。

## 与开发文档的偏差（有意为之，需你确认）

1. **堂食判定**：文档写"只接受 `Order_Type == 1`"。实测 OTC 1.3.6 中 `Order_Type` 是**来源**（0=打单机打印单，1=walk-in 到店单，>1=外卖距离），堂食的权威条件是 `!Delivery`。实现按 `Delivery=true 一律忽略`，即打单机单与 walk-in 单都进入自动化；若你确实只要 walk-in（`Order_Type==1`），改 `DispatcherLogic.acceptOneMachineOrder` 一行即可。
2. **订单完成感知**用公开 `OrderLifecycleApi`（文档以为没有公开 API，实测 1.3.6 已有），因此省掉 mixin。
3. **取单入台**由调度显示器服务端直接完成（替代原 `OrderFetchBridge` 的"厨师搬单"），因此 `OrderFetchBridge.tickFetch` 已从 tick 链移除；如需保留"女仆亲手端单"的演出可再改回。
4. 排班表 / 玩家点单 / 玩家经济按你的选择原样保留，与新调度显示器并存（排班表继续负责激活打单机与旧开关）。

## 第二轮补齐（本轮新增）

- **公告板 / 健康证：已彻底删除**（方块、BE、物品、饰品、GUI 入口、贴图/模型/语言/配方全部移除；旧「健康证员工绑定」改为空实现，员工上限改由工位数量决定）。
- **挂单夹：功能下线**（方块/物品/BE 从注册表摘除、创造栏移除、贴图/模型/配方/掉落表/语言清理；`TaskManager` 的挂单夹扫描接口改为返回空集合，不再每 tick 扫描）。仅保留两个不再注册的占位类（`OrderClipBlock`/`OrderClipBlockEntity`）以免牵动烹饪预烹饪子系统的重构，运行时不会出现、不会工作。
- **`OrderFetchBridge` 已删除**（取单入台完全由调度显示器状态机接管），相关调用点（`DispatcherLogic`/`OrderBridge`/`WalkInGreetBridge`/`RuntimeState`）全部清理。
- **配置**：仍是 `config/maid_restaurant_automation/` 子目录多文件（`automation.toml` / `gameplay.toml` / `performance.toml` / `safety.toml` / `batch_cooking.toml`）。
  - 说明：曾按文档尝试合并为单文件 `common.toml`，但 Forge 47 的 `ConfigTracker` 不允许把多个 `ForgeConfigSpec` 注册到同一个文件（启动即抛 `RuntimeException: Config conflict detected!`，已在 crasheport 中确认），因此回退为多文件；如需严格单文件需把 4 个配置类合并为一个 Spec（后续可做）。
- **本轮修复**：`0.2.4-fix3` 基线里已存在的 `HealthCertificate/PublicNoticeBoard` 等残留引用已全部清理，工程可独立编译运行。
- **未认领不可接单扩展覆盖到侍者侧**：`MaidUtils.pickSmartWaiterAmong / findWaiterMaidSmart / findCookMaidSmart` 在"已放置调度显示器"的餐厅里只返回已认领工位的女仆（打包、上菜、洗碗、接待全部生效）。
- 构建产物已重新打包：`maid_restaurant_automation-1.20.1-forge-1.0.0.jar`（1.26 MB）。

## 尚未完成（如需继续可下发）

- 挂单夹遗留的两个占位类（`OrderClipBlock`/`OrderClipBlockEntity`）与 `CookingBridge` 内已不可达的"挂单夹预烹饪"代码块，可做一次纯清理删除（不影响运行）。
- 员工总览/队列 GUI 为行渲染（未做点击跳转工位详情）。
- 侍者超时顶替采用"调度显示器清理 + 侍者重试"等价实现，未做"侍者把自带订单直接放台顶替"的演出。
- 我做的工作只到编译通过为止，**未做任何游戏内测试**（按你的要求，测试由你来做）。

## 测试建议（按顺序）

1. 把 jar 放进测试实例 `C:\Applications\Minecraft\.minecraft\versions\FarmingTales_ForgeⅡ v1.5.2fix\mods\`（现里面是旧 mod 0.2.7 的 jar，可先移出避免同包冲突）。
2. 进游戏放置打单机 + 操作台 + 厨具 + 板凳，再放「餐厅调度显示器」（16 格内）。
3. GUI 应自动显示已绑定打单机；安排厨师/侍者女仆在附近，观察是否出现绿色粒子与"认领成功"气泡，GUI 员工总览出现工位信息。
4. 打单机刷新出堂食订单后，观察订单是否自动进入操作台、顾客是否生成、GUI 队列是否推进。
5. 异常项：打单机/调度显示器被拆、工位被拆、订单过期、侍者卡住时的表现，把日志（`latest.log`）发我。

## 崩溃报告修复记录（2026-10-17_18.17.25）

- 现象：启动时 `Mod loading error has occurred`，崩溃报告指向 `maid_restaurant_automation`，异常 `java.lang.RuntimeException: Config conflict detected!`（`ConfigTracker.trackConfig`，由 `MaidRestaurantAutomation.<init>` 的 `registerConfig` 触发）。
- 根因：上一轮为贴合文档"common.toml + safety.toml"把 4 个 `ForgeConfigSpec` 注册到同一个 `maid_restaurant_automation/common.toml`。Forge 47 的 `ConfigTracker` 禁止同一配置文件被多个 `ModConfig` 占用，启动期直接抛错。
- 修复：恢复为独立配置文件（`automation.toml` / `gameplay.toml` / `performance.toml` / `safety.toml` / `batch_cooking.toml`），重新 `build` 成功，产物 `maid_restaurant_automation-1.20.1-forge-1.0.0.jar`（1,257,959 字节，2026-10-17 18:19）已覆盖部署到测试实例 mods 目录。

## 第三轮修复记录（2026-10-17 19:18 构建，已部署）

崩溃日志：`crash-reports/crash-2026-10-17_18.37.49-server.txt` 与 `crash-2026-10-17_18.40.18-server.txt`，两次都是同一个 bug：
`java.util.ConcurrentModificationException at DispatcherLogic.maintainBindings`（`LinkedHashMap$LinkedHashIterator.remove`）。
根因：遍历 `workstationBindings` 时调用的 `release()` 内部又直接 `remove` 了同一个 Map（改职业、抱走女仆都会走到该路径）。修复：所有删除只走迭代器 `it.remove()` 并同步 `stationOwners`；`DispatcherBlockEntity.tick` 外层再加 Throwable 兜底，任何调度逻辑异常不再炸服务器。

### 针对你反馈的 5 条
1. **先放打单机再放调度显示器时绑定不上**：新增"右键调度显示器立即就近绑定"+ WorldScanner 索引未命中时的局部方块直扫兜底（`DispatcherLogic.tryBindNearest/tryBindTo`），不再依赖索引缓存时序。
2. **两次崩溃**：见上（CME 已修 + tick 异常兜底）。
3. **员工总览重排**：店长单独一行且名字居中；其他玩家名一行内横向排列（超宽自动换行）；女仆每个一行、只显示名字（已认领绿 / 未认领红）；厨具、主人、工位默认隐藏，鼠标悬停女仆行时以 **2 列 × 3 行表格**（厨具/主人/工位）自绘悬浮显示；底部保留"另有 N 名女仆未认领工位"。
4. **食物归属错误**：原来整单共用一个 `assignedMaidName`，现改为**按菜精确匹配**：读取该操作台在制烹饪请求的 `BusinessCounter + BusinessItemId`（无则回退解析配方产物），逐份食物显示对应厨师。
5. **队列 UI 拆分**：
   - **订单队列**：以订单为单位、按价格（OTC Prestige）降序；包含打单机里尚未接单的堂食单（`[打单机] 待接单`）与已入台订单；被侍者接待入台的订单显示 `→ 侍者名`（到店接待女仆放入操作台时登记）；行悬停显示订单号/菜品清单/顾客与失败原因。
   - **食物队列**：不分订单，只逐份列食物，已分配显示 `→ 厨师名`，未分配显示 `待分配`。
   - 三个面板（员工/订单/食物）各自独立滚动、scissor 裁剪 + 滚动条，自适应 720p/1080p 不溢出。

### 顺带修正
- 订单装盘后（操作台订单物品被 OTC 装盘消耗）不再误判为"已闭环"，改为 `DELIVERING` 并等待 OTC `COMPLETED` 生命周期事件闭环，600 tick 无事件才兜底关闭。
- 操作台所在区块未加载时不再误判"操作台已消失"导致订单失败。

本轮构建：`maid_restaurant_automation-1.20.1-forge-1.0.0.jar`（1,268,214 字节，2026-10-17 19:18:52），已覆盖部署到测试实例 mods 目录；语言文件 JSON 已校验（中英各 109 key）。

## 第四轮修复记录（2026-10-17 19:47 构建，已部署）

对应反馈：
1. **员工总览改为横向流式排列、取消分面板滚动**：女仆名与其他玩家名一样横向 chip 排列（超宽自动换行），已认领绿/未认领红/玩家青；店长仍单独一行居中；厨具/主人/工位默认隐藏，悬停女仆 chip 时弹 2列×3行表格；**整个调度显示器只有一根全局滚动条**（滚轮在窗口任意位置滚动全页，内容超高时右侧显示滚动条，scissor 裁剪）。
2. **操作台有未过期订单但无人做菜**：
   - 移除"必须有厨娘凳才允许认领/否则工位失效"的板凳逻辑（用户确认该待命行为未生效且不需要）。此前缺凳会把厨师工位判为失效 → 被排除在派单之外，是"有单没人做"的重要嫌疑根因。
   - 烹饪选厨师改为**两段式**：先严格按"工位 + 厨具类型"匹配；若全部被过滤，再退回官方选台逻辑兜底，避免一处类型不匹配导致整店停做。
   - `selectWorkPosForMaid`：绑定工位只有**对本配方类型有效**时才直接使用，否则回退官方选台。
   - 新增**烹饪诊断日志**（节流 200 tick，输出到 latest.log），覆盖三种常见原因：①无空闲厨师（TaskCook/被占用/有任务/24 格内）②有空闲厨师但缺厨具/缺食材（含具体缺料名与各厨师绑定工位）③菜品无可用烹饪配方（第三方食谱类型未被女仆餐厅注册，或生食原料）。下次若仍不做菜，日志里搜 `烹饪诊断` 即可定位。
3. **空闲时站板凳待命**：相关代码（板凳检测/要求/失效判定）已按要求删除，只校验工作方块本身；不影响炒菜（炒菜仍由本体女仆餐厅行为树执行）。

构建：`maid_restaurant_automation-1.20.1-forge-1.0.0.jar`（1,271,185 字节，2026-10-17 19:47:51），已覆盖部署到测试实例 mods 目录。

## 第五轮修复记录（2026-10-17 20:03 构建，已部署）

1. **"女仆依旧不做菜"的进一步排查与修复**：
   - 上一轮日志里 **完全没有** `烹饪诊断` 输出 → 说明 `CookingBridge.tickCooking` 在进入逐操作台处理之前就提前返回了。定位到早退闸门 `if (!hasIdleChef(level)) return;`（性能优化：判定无空闲厨师就整轮跳过）。该判定只要因忙碌标记/缓存问题误判，就会**静默跳过所有烹饪**（有订单、不做菜、无日志），与用户现象完全一致。
   - **已删除该提前返回**：现在始终逐操作台处理；没有空闲厨师时会走诊断/气泡分支，不再静默停摆。
   - 新增**总体诊断日志**（每 200 tick）：输出维度、缓存女仆数、厨师数、无 Task 厨师数、激活打单机数、操作台映射数、调度显示器数、厨师明细（task/occupied 状态），可一眼看出卡在哪一步。若仍不做菜，搜 `烹饪诊断(总)` 即可定位。
2. **订单队列只显示已被接待订单 / 未接待顾客不显示**：确认 OTC 的到店订单是在"侍者接待"那一刻才生成的，之前不存在可读取的订单。修复：
   - **自动接待常开**：移除 `AutomationConfig.autoAccept` 与排班表"自动接单"开关对 `WalkInGreetBridge` 的限制（有调度显示器即全功能），到店顾客会自动被接待并立即生成订单；
   - 订单队列新增 **`[待接待] 顾客名`** 条目：列出打单机范围内已到店但尚未接待的顾客（订单未生成，无法显示菜品/价格），并提示接待后生成订单——即"提前读取"能做到的上限。
3. **UI 微调（按用户要求）**：删除员工总览里的"店长""店员"标签文字；店长改为与女仆同款**带底色 chip**（金色底+金字，居中）；其他玩家保持原来位置的青色 chip，均复用同一 `drawChip` 绘制函数；女仆绿/红 chip 与悬停 2×3 表格不变；整页单滚动不变。

构建：`maid_restaurant_automation-1.20.1-forge-1.0.0.jar`（1,272,035 字节，2026-10-17 20:03:58），已覆盖部署到测试实例 mods 目录。

## 第六轮修复记录（2026-10-17 20:15 构建，已部署）——"重启后不做菜"根因

**日志证据**（`latest.log` 的 `烹饪诊断(总)`）：
`缓存女仆=9 厨师=7 无Task厨师=7 激活打单机=1 操作台映射=0 调度显示器=0` → 女仆/厨师/激活都正常，但**操作台→打单机映射为 0**，导致 `CookingBridge` 遍历不到任何操作台，整轮无烹饪；也因为扫描不到调度显示器，所有"是否已认领工位"的过滤退化为旧逻辑。

**根因**：`WorldScanner` 的方块实体索引依赖 `ChunkEvent.Load` 时快照 `chunk.getBlockEntitiesPos()`，而区块加载事件触发时区块内的方块实体可能尚未创建 → 索引为空。第一次测试时方块是当场放置的（`BlockEvent.EntityPlaceEvent` 会把它们加入索引），所以能炒菜；**重启游戏后已有方块永远进不了索引**，于是扫不到打单机/调度显示器/操作台归属，表现为"有订单、不做菜、无日志"。

**修复**：
1. `WorldScanner.scan` 增加**玩家周边已加载区块兜底扫描**（以玩家为中心 16×16 chunk，`getChunkNow` 不触发区块生成；节流 100 tick），并把找到的方块实体位置**回填索引**，之后走快路径；
2. `DispatcherLogic.adoptCounterOrders`：订单被从操作台拿出再放回时，**撤销 `ignoredOrderIds` 标记并重新纳入队列**（修复"拿出再放回后订单列表/食物列表不显示"）；
3. 前几轮已加入的 `烹饪诊断(总)` 每次 200 tick 输出一次，修复后应能看到 `操作台映射>0`、`调度显示器≥1`。

构建：`maid_restaurant_automation-1.20.1-forge-1.0.0.jar`（1,272,968 字节，2026-10-17 20:15:30），已覆盖部署到测试实例 mods 目录。

## 第七轮修复记录（2026-10-17 20:25 构建，已部署）

1. **重启后不做菜的根因修复（结合 `烹饪诊断(总)` 日志）**：
   日志显示 `缓存女仆=9 厨师=7 无Task厨师=7 激活打单机=1 操作台映射=0 调度显示器=0`。
   根因：`WorldScanner` 的方块实体索引在 `ChunkEvent.Load` 时快照 `chunk.getBlockEntitiesPos()`，而区块加载时 BE 可能尚未创建 → 索引为空；第一次测试方块是当场放置（放置事件会记录），所以能炒菜；**重启后已有方块永远扫不到** → 操作台映射=0 → `CookingBridge` 遍历不到操作台。
   修复：`WorldScanner.scan` 增加**玩家周边已加载区块兜底扫描**（16×16 chunk，`getChunkNow` 不生成区块，100 tick 节流），并把找到的方块实体**回填索引**，之后恢复快路径。
2. **订单从操作台拿出再放回后不显示**：`DispatcherLogic.adoptCounterOrders` 重新纳入时撤销 `ignoredOrderIds` 标记，订单列表/食物列表恢复显示。
3. **食物队列"同一种菜全部指向同一个女仆"显示错误**：改为按**真实在制的烹饪请求逐份匹配**（一个请求=该女仆一次能处理的份数；普通锅 1 份，多槽批量厨具按 requested 重复），没有对应请求的份数显示"待分配"；并新增排序：**正在被处理（已分配厨师）的食物排在最前列**，待分配在后（同类保持原顺序）。

构建：`maid_restaurant_automation-1.20.1-forge-1.0.0.jar`（1,273,397 字节，2026-10-17 20:25:14），已覆盖部署到测试实例 mods 目录。

## 第八轮修改记录（2026-10-17 20:40 构建，已部署）

1. **员工总览：女仆按职业分组**（店长/其他玩家部分保持不变）：
   - 女仆 chip 按 `Profession` 分组（取 `" · "` 前的职业名，如 `厨具` / `操作台`），每组先画一个组标题 chip（职业名 + 数量），组内女仆名字继续横向流式 chip（绿=已认领 / 红=未认领），悬停 2×3 表格不变；
   - 组排序：`厨具`（厨师）→ `操作台`（侍者）→ 其他职业按名称排序；组间留间距；
   - 员工区总高度按组动态计算，整页单滚动与 hover 判定同步适配。
2. **女仆工作/活动半径扩大至 15 格**：已认领工位的女仆在调度显示器维护时用原版 `maid.restrictTo(center, 15)`（只增不减）扩大活动半径；女仆餐厅的取菜容器搜索、找工位、距离校验都基于该半径，因此取菜不再因超出原半径（常见默认 10 格）而失败。
3. **防误判解绑**：维护循环里"女仆实体暂时找不到"（走远/区块边界/实体未加载）改为 **20 秒宽限期**，期间保留工位绑定；只有实体确认死亡/移除或职业变化才解绑——修复"女仆去取菜回来发现工位被解绑"的问题。

构建：`maid_restaurant_automation-1.20.1-forge-1.0.0.jar`（1,275,757 字节，2026-10-17 20:40:59），已覆盖部署到测试实例 mods 目录。

## 第九轮修复记录（2026-10-17 20:51 构建，已部署）

**食物队列"同一道菜多份的厨师显示不稳定/随机"修复**：
- 原因：食物队列原来只读取"当前瞬间存在的烹饪请求"。烹饪请求做完即出队，每 20 tick 刷新快照时可能只看到其中一个女仆的请求，导致同一道菜的多份一会儿显示 A、一会儿显示 B，看起来像"在几个女仆里随机选一个"。
- 修复：`TrackedOrder` 增加**逐份厨师分配历史**（itemId → 按份序的厨师名列表，内存态、跟随订单生命周期）。每次快照把新观察到的厨师按份写入历史：第 1 份记录最先接单的女仆，第 2 份记录下一位……之后界面**始终按份稳定显示**（A/B/C 分别对应），不再随机跳变；没有分配到的份数显示"待分配"。
- 已分配（正在做）的食物依旧排在最前列。

构建：`maid_restaurant_automation-1.20.1-forge-1.0.0.jar`（1,276,275 字节，2026-10-17 20:51:05），已覆盖部署到测试实例 mods 目录。

## 第十轮修复记录（2026-10-17 20:51:56 构建，已部署）

**食物队列"正在被处理的食物置顶"精确化**：
- 上一版把所有"有厨师名字"的行统一当已分配排序，但累积历史会让"已做完/已出队"的行也带名字，导致看不出谁"正在做"，排序也没有明显效果。
- 改为三级状态并在服务端排序（稳定排序）：
  - **`2` = 正在制作**（当前确实存在该操作台该菜的在制烹饪请求）→ **排最前**，行首加 `§6▶` 标记、行尾标 `§e(制作中)`，厨师名绿字；
  - **`1` = 已分配（历史）** → 次之；
  - **`0` = 待分配`** → 最后。
- 同时保留逐份累积分配历史（同一道菜多份分别对应各自女仆，刷新不跳变）。

构建：`maid_restaurant_automation-1.20.1-forge-1.0.0.jar`（1,276,411 字节，2026-10-17 20:51:56），已覆盖部署到测试实例 mods 目录。

## 第十一轮修改记录（2026-10-17 21:04 构建，v1.0.2 已部署）

**员工总览改为按"职业"分组（不再按工作方块）**：
- 服务端 `StationSystem.professionLabel(profession)`：厨师 → `厨师`、侍者 → `侍者`；扩展职业优先用注册显示名，未注册时按任务 UID 启发式映射（tea→`茶博士`、bar/bartend→`调酒师`、brew→`酿酒师`），其余归 `其他职业(xxx)`；
- `maidEntries` 新增 `"Job"` 字段（职业名）；`"Profession"` 保留为工作方块类型，继续供悬停 2×3 表格的"厨具"列使用；
- GUI 分组键改为 `Job`：组标题按 **厨师 → 侍者 → 其他职业** 排序；组标题 chip 按职业配色（厨师暖橙、侍者冷青、其他淡灰+金边）；女仆绿/红 chip 与悬停表格、店长/玩家排布、整页单滚动、订单与食物队列全部不变。
- 注意上一轮"看起来没生效"的原因是游戏会话从 20:17 一直未重启（旧 jar 在内存中），本轮 **版本号升到 1.0.2**，启动后可在模组列表直接确认版本。

构建：`maid_restaurant_automation-1.20.1-forge-1.0.2.jar`（1,277,203 字节，2026-10-17 21:04:47），已覆盖部署（旧 1.0.1 已删除），build/deploy SHA256 一致。

## 第十二轮修改记录（2026-10-17 21:1x 构建，v1.0.3 已部署）

1. **女仆绑定/取菜活动范围 15 → 24 格**：`ensureWorkRadius` 改为 `restrictTo(center, 24)`（只增不减），避免女仆去取菜走远时被判出范围而"解绑工位"；同时保留 20 秒实体找不到宽限期。
2. **食物队列"已完成（在操作台）"显示**：
   - 统计操作台（跳过订单槽 0）上已有的成品份数；
   - 对应份数标记为 **`§a已完成 §8(在操作台)`**，并**排到食物队列队尾**；
   - 队列排序改为数字优先级（稳定排序）：**制作中(0) → 已分配(1) → 待分配(2) → 已完成(3，队尾)**；
   - 已完成的份数不再占用"制作中/待分配"行，显示更准确。

构建：`maid_restaurant_automation-1.20.1-forge-1.0.3.jar`（1,277,xxx 字节），已覆盖部署（旧版本已删除）。

## 第十三轮修改记录（2026-10-17 21:2x 构建，v1.0.4 已部署）

**女仆互相卡住问题**：
- 说明：本工程的 Gradle 离线环境没有 Mixin 注解处理器（无 refmap），无法安全地通过 mixin 关闭 `EntityMaid` 的实体碰撞（SRG 名称无法在构建期验证，写错会在启动时崩溃），因此改用**服务端自动脱困**方案，效果上等价于"从卡住的同伴身上穿过去"：
- 调度显示器维护循环（每 20 tick）对**身上有餐厅任务**的上岗女仆做卡住检测：连续 3 次（约 3 秒）位置几乎没变化 → 沿她当前寻路目标（无则沿绑定工位）方向**瞬移最多 2 格穿过阻挡物**，随后清空导航重新寻路；带 3 秒冷却，避免抖动；若目标方向被墙挡住则尝试只沿 X / 只沿 Z 小步穿越，仍不行就放弃（不穿墙）。
- 该逻辑只作用于"正在去取菜/去工位/送餐"等有任务的女仆，不影响玩家与其它实体碰撞。

构建：`maid_restaurant_automation-1.20.1-forge-1.0.4.jar`（约 1.28 MB），已覆盖部署（旧版本已删除）。

## 第十四轮修改记录（2026-10-17 21:4x 构建，v1.0.5 已部署）

1. **成品自动交付（修复"炒完菜不交台、堆背包"）**：新增 `core/DishDeliveryHelper`，调度显示器每轮（10 tick）对每个在制订单按"订单需求 − 操作台已有"的差额，从本店已认领女仆背包里把对应**成品**搬进操作台（只插槽位 1 起，槽 0 为订单槽；放不下自动回背包），不再依赖"备菜"触发；搬走后订单剩余需求同步减少。
2. **食物队列 UI 改造**：
   - 去掉坐标显示（服务端数据已改为结构化 `Dish/Assignee/Flag`）；
   - 改为**两列竖排对齐**：左列菜名（制作中带 ▶），右列厨师/状态（未分配灰字、已完成绿字），列宽按内容自动计算并裁剪超长文本；
   - 排序保持：制作中 → 已分配 → 待分配 → 已完成。
3. **订单队列 UI 改造**：
   - 分两组：**已接待（上）**、**未接待（下）**，组标题沿用员工总览的 chip 样式（`已接待（N）`/`未接待（N）`）；
   - 每单一行、两列对齐：左列 `★价格 剩余时间 顾客名`（价格未知显示 `★?`，失败订单红字/红底），右列 `状态 → 处理侍者`；
   - 悬停订单行仍显示订单号/菜品清单/失败原因等详情；组内按价格降序，整页单滚动与布局高度同步适配。

构建：`maid_restaurant_automation-1.20.1-forge-1.0.5.jar`，已覆盖部署（旧版本已删除）。

## 第十五轮修改（2026-10-17 21:41 构建，v1.0.6 已部署）

**调度显示器合成配方改为无序合成 1:1**：
- 数据文件 `data/maid_restaurant_automation/recipes/dispatcher.json`：
  - `minecraft:crafting_shapeless`，材料 **1 个打单机（`ordertocook:order_machine`）** → 产物 **1 个调度显示器**；
  - 替换掉原来的 3×3 有序配方（铁锭/红石/纸）。
- 已确认配方正确打进 `maid_restaurant_automation-1.20.1-forge-1.0.6.jar`（jar 内文本核对）。

## 版本回退记录（2026-10-17 21:45）

- 按用户要求**撤回到 v1.0.5**：
  - 源码 `mod_version` 改回 `1.0.5`；
  - `dispatcher.json` 配方恢复为 1.0.5 时的 3×3 有序配方（铁锭/红石/纸）；
  - 测试实例 mods 目录删除 `1.0.6`，重新部署**原始 1.0.5 构建产物**（build/libs 中保留的 jar，内容与当时一致）。
- v1.0.6 的"1 打单机 = 1 调度显示器无序合成"改动暂不生效。

## 崩溃修复记录（2026-10-17 21:46 报告）

**现象**：启动即崩溃，`错误报告-2026-10-17_21.46.40.zip` 内 `游戏崩溃前的输出.txt`：
`InvalidMixinException: The specified mixin 'com.icewolf.maidrestaurant.automation.mixin.MaidEntityCollisionMixin' was not found`
→ `Mixin [maid_restaurant_automation.mixins.json:MaidEntityCollisionMixin] FAILED during PREPARE`。

**根因（我上一轮引入的错误）**：第 13 轮做碰撞优化时先加了 `MaidEntityCollisionMixin` 并登记进 `mixins.json`，随后因无 refmap 决定撤掉该 mixin，但**删 class 时 `mixins.json` 里的条目没有真正删掉**（脚本替换未命中且未校验），导致之后构建出的 1.0.4 / 1.0.5 / 1.0.6 jar 都带着"配置里有、class 没有"的失效条目 → 启动时 mixin 准备阶段直接崩溃。

**修复**：
1. 从 `maid_restaurant_automation.mixins.json` 删除 `MaidEntityCollisionMixin` 条目；
2. 全量校验 jar 内 mixin 配置条目与 `mixin/*.class` 一一对应（10 条全部存在）；
3. 重新构建 **v1.0.5**（保留用户要求的版本号）并部署，产物 1,288,512 字节，SHA256 `57A1780F…B25CAF18`；
4. 新增工具脚本 `tools/check_mixins.ps1`：以后每次构建后可用它检查 "mixin 配置 ↔ class" 一致性，避免同类问题。

## 版本与发布记录（2026-10-17 22:12）

- **mod 版本号改为 `1.0.0`**（`forge-1.20.1-automation/gradle.properties`）。
- **构建产物名称改为 `Maid Restaurant Automation`**：`build.gradle` 的 `archivesName` 与 `jar.archiveFileName` 改为该名称，产物为
  `Maid Restaurant Automation-1.20.1-forge-1.0.0.jar`（1,288,510 字节，SHA256 `09F8BE3B…F314007`）；modid/包名/游戏内显示名等**代码内容未改动**。
- 已部署到测试实例 mods 目录（并移除旧命名的 jar）。
- **Git 提交并推送**：
  - 新增提交 `72c512d`（v1.0.0 首次提交，135 文件 / +23,155 行）与合并提交 `a8b85e8`（合并远程 Initial commit，LICENSE 采用双版权：IceWolf 原作 + Yiva-1693 改造；根 README 与工程 README 更新为本 mod 说明）；
  - remote 已改为 `https://github.com/Yiva-1693/MaidRestaurantAutomation.git`，`main` 已推送，本地与远程 HEAD 均为 `a8b85e8e32341baf46d1aa3efdd0b55328e9500e`。

## 名称与仓库整理记录（2026-10-17 22:25）

1. **全部显示名称改为「餐厅调度显示器」**：
   - 源码/资源中共替换 "调度器" → "调度显示器" 70 处（21 个文件）：方块/物品名、菜单标题、聊天/气泡提示、GUI 文案、注释等；
   - 英文 lang 同步：`Restaurant Dispatcher` → `Restaurant Dispatcher Display`；
   - README 与本文档同步替换；代码标识符（modid / 包名 / 类名 / 方块注册名）保持不变。
   - 版本仍为 **1.0.0**，产物 `Maid Restaurant Automation-1.20.1-forge-1.0.0.jar`（1,288,564 字节，SHA256 `CE7E3642…DD23D7`），已部署测试实例。
2. **仓库清理：只保留本项目代码**：
   - 删除从上游克隆的全部工程与旧产物：`forge-1.20.1/`（原基线工程，含 585MB 离线缓存）、`neoforge-1.21.1/`、`releases/`、`.idea/`、`tools/` 下上游脚本、`docs/recon/`、根目录旧分析文档与构建日志；
   - 保留：`forge-1.20.1-automation/`（本模组）、`docs/DELIVERY_自动化附属_v1.md`、`tools/check_mixins.ps1`、`LICENSE`、`README.md`、`.gitignore`；
   - **Git 历史重建**：以孤儿提交（orphan branch）重建 `main`，只包含本项目文件，并 force push 覆盖远程 `https://github.com/Yiva-1693/MaidRestaurantAutomation`（上游克隆历史不再保留）。
   - 构建改用本机默认 Gradle 用户目录缓存（不再依赖已删除的 `forge-1.20.1/.gradle_repo`），离线构建验证通过。

## 食物队列按订单分组 + 组内双列排版（v1.0.1）

- **服务端**（`DispatcherLogic.buildSnapshot`）/ 食物条目新增 `"Order"`（订单组序号，组序=订单队列顺序）、`"OrderLabel"`（组标题，如 `★387 · wuyexianyu`）；组内生成顺序保持 制作中 → 已分配 → 待分配 → 已完成；取消全局 Flag 排序。
- **GUI**（`DispatcherScreen`）：
  - 食物队列改为**按订单分组**：每组先画一行组标题（沿用组标题 chip 风格：淡灰底+淡金边）；
  - 组内条目**左右双列依次排列**（两条一行，从左到右再换行），每格紧凑显示 `▶ 菜名 → 厨师 / 待分配 / 已完成`；
  - 组内按 Flag 稳定排序（制作中靠前、已完成最后）；布局高度、整页单滚动与滚动条同步适配。
- 版本升 **1.0.1**，产物 `Maid Restaurant Automation-1.20.1-forge-1.0.1.jar`。
