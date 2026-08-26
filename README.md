# Dimensional Ripper

Dimensional Ripper 是一个基于 **NeoForge 1.21.1** 的多线程维度并行优化模组。它通过独立维度 tick 循环与细粒度并行调度，充分利用多核 CPU 提升服务端性能，同时保持稳定的 20 TPS。

## 功能特性

- **维度并行 Tick**：每个维度拥有独立的 tick 循环与 worker 线程，维度之间互不拖累——单维度低 TPS 不会影响其他维度。
- **细粒度并行**：在维度内部对实体、方块实体、区块环境 tick 进行并行调度，充分压榨多核性能。
- **碰撞优化**：可禁用实体间碰撞，消除 O(n²) 碰撞开销，显著释放 TPS。
- **异步传送门传送**：支持原版下界/末地传送门与模组维度（如暮色森林、以太）传送门的异步预加载与渐进生成，避免传送到未加载区块导致的卡顿、冻结与虚空传送。
- **区块预取**：根据玩家位置与朝向预加载区块，加快跑图时的区块加载速度。
- **故障降级**：内置看门狗与故障熔断机制，异常时安全降级回原版串行 tick，保障服务器长期稳定运行。

## 环境要求

- Minecraft **1.21.1**
- NeoForge **21.1.x**
- JDK **21**

## 指令

| 指令 | 说明 |
|------|------|
| `/dimensionalripper status` | 查看当前状态、TPS 与各开关 |
| `/dimensionalripper fine on\|off` | 开关细粒度并行 |
| `/dimensionalripper nocollide on\|off` | 开关实体碰撞优化 |

模组**默认启用**多线程，开箱即用，无需额外配置。

## 构建

```bash
./gradlew build
```

构建产物位于 `build/libs/` 目录。

## 设计说明

本模组的设计思想借鉴了 Folia、MCMT、Chlorophyll 等项目的多线程调度方案，但全部代码基于 NeoForge API 独立实现，不包含任何 GPL 代码。

## 许可证

All Rights Reserved © 2026 xxdd001

---

> **AI 生成声明**：本 README 说明文档由 AI 辅助生成，使用的 AI 为 DeepSeek-V4-Flash。

