# Maid Restaurant: Automation（女仆餐厅：自动化附属）

Minecraft **1.20.1 / Forge 47.2.0+ / Java 17** 附属模组：以「餐厅调度显示器」方块为控制与观测中心，让车万女仆把一家女仆餐厅**全自动经营**起来（堂食自动化）。

> 本模组基于 [Maid Restaurant: Business（营业中）](https://github.com/IceWolfBG/maid-restaurant-business) 的 `forge-1.20.1` 分支（MIT 协议）改造而来，保留原始版权声明，详见 `LICENSE`。

## 功能概览

- **餐厅调度显示器**（新方块）：放置后自动绑定 16 格内一台打单机，持有订单队列、员工总览、工位绑定表与全部自动化状态。
- **显式订单状态机**：`QUEUED → PLACED → COOKING → READY → DELEGATED_TO_WAITER → DELIVERING → SERVED → CLOSED`（含 `FAILED`），订单生命周期事件走 Order To Cook 的公开 `OrderLifecycleApi`。
- **工位绑定**：厨师认领炒锅 / 汤锅 / 蒸笼等厨具、侍者认领操作台；认领成功播放绿色粒子，未认领不可接单；女仆只在自己的工位干活。
- **职业分组总览**：店长 / 玩家 / 女仆（按厨师、侍者、其他兼容职业分组）名字 chip，悬停显示厨具 / 主人 / 工位。
- **订单队列**：已接待在上、未接待在下，显示价格、剩余时间、处理侍者。
- **食物队列**：两列排版，逐份显示对应厨师；制作中置顶、已完成置底。
- **库存优先**：取食材前先找附近容器现成成品 / 原料，不足再烹饪。
- **成品自动交付**：厨师炒完立即把成品放回操作台，不再堆在背包里。
- **稳定性**：卡住自动脱困、扫描兜底、超时重试、防解绑宽限期；不做外卖，只做堂食。

## 依赖（硬依赖）

- 车万女仆 Touhou Little Maid `1.5.3+`
- 女仆餐厅 Maid Restaurant `0.2.9+`
- 下单了 Order To Cook **`1.3.6+`**
- 可选：森罗物语系列、农夫乐事（提供更多厨具与料理）、Jade

## 构建

```bash
cd forge-1.20.1-automation
./gradlew build
# 产物：build/libs/Maid Restaurant Automation-1.20.1-forge-1.0.0.jar
```

依赖 jar 放置于 `forge-1.20.1-automation/libs/`（`compileOnly`，不随模组打包）。
构建后可运行 `tools/check_mixins.ps1` 校验 mixin 配置与 class 一致性。

## 目录

本仓库只包含本附属模组的代码与文档（不包含任何上游模组的克隆代码）：

| 目录 | 说明 |
|---|---|
| `forge-1.20.1-automation/` | **本模组工程**（Maid Restaurant: Automation） |
| `docs/` | 开发/交付记录 |
| `tools/` | 构建校验脚本（如 `check_mixins.ps1`） |

## 许可

MIT。前置模组各自遵循其原有协议；本模组不包含前置模组的代码或 jar。
