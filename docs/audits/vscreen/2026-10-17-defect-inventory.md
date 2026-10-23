# 虚拟屏缺陷盘点（2026-10-17，基线 build173）

只读审计结论，未改代码。完整分析见会话记录；这里是交接用的缺陷索引与修复优先级。
路径前缀：`app/src/main/java/com/deepseekharness/app/`

## 关键不变量
- I1 坐标/文本输入必须基于调用方自己看过、且仍是最新的一帧（30s 内）
- I2 generation 唯一代次，旧代次输入必须 STALE_GENERATION
- I3 撤销返回后旧代次不再获授权，已发出请求不追究
- I4 结果未知 ≠ 未执行，绝不自动重放
- I5 UI 不得在状态锁内做网络/无障碍

结论：I2/I3/I4/I5 基本成立，**I1 被 B1、B2 两处实现破坏**。

## 缺陷索引

| 编号 | 级别 | 位置 | 问题 | 修法方向 |
|---|---|---|---|---|
| B1 | P1 安全 | `vscreen/VirtualScreenCore.java:520-536` + `vscreen/VirtualScreenPreviews.java:131-153` | `/vscreen/preview` 无条件写 `session.observed`，本地预览轮询（FG/Act/Ovl 三个常驻订阅者，250ms）替所有调用方"代签"观察；只调 status 拿 frameSeq 就能盲点。与 `/app/help`、`Act:395` 文案相反 | preview 加 `observe=0|1`，轮询走 `previewPassive()`；或改 observeId 一次性票据 |
| B2 | P1 可用 | `util/VirtualScreenPolicy.java:34-36,98-101` | `fresh()` 严格等值，任何 ≥1fps 重绘都让坐标输入必然 FRAME_EXPIRED；`OBSERVATION_TIMEOUT` 事实上不可达 | 放宽为"最近 K 帧之内 + ageMs<1500" |
| B3 | P1 ANR | `vscreen/VirtualScreenManager.java:728-758` | 主线程 stop 里做 HTTP close（read 12s）+ 抢 ACTIONS 锁（手势可占 3-5.5s） | 参照 `revoke()`：只 detach，重活投 WORKER |
| B4 | P1 可用 | `VirtualScreenCore.java:284,350-374,38-49` + `VirtualScreenManager.java:530-540` | `route()` 类锁串行化全部路由；池 4/队列 8，满了裸关 socket；心跳一次失败即销毁会话 | 按 Session 加锁 + 输入独立串行锁；心跳连续失败阈值；池满回 503 |
| B5 | P2 安全 | `VirtualScreenCore.java:306-329,371-373,349` | `/vscreen/touch` 只有 DOWN 过帧门，MOVE 可无限拖到未观察坐标；tap 的 finally 无条件 cancel 掉别人的 stroke | MOVE 要求观察窗内；finally 只在自建 strokeId 上 cancel |
| B6 | P2 | `VirtualScreenCore.java:394-400,428-456` | create 先 release 旧会话再建新的，新建失败即丢屏且心跳仍成功、永不收敛 | 先建成功再 release 旧的 |
| B7 | P2 性能 | `VirtualScreenCore.java:523-543` | preview 在命中编码缓存前整张 copy Bitmap → 4Hz × 7.2MB ≈ 29MB/s | 缓存命中判断搬到 copy 之前 |
| B8 | P2 可观测 | `VirtualScreenCore.java:779-791` | `capturedAt` 在帧未变化时也刷新，`frameAgeMs` 恒 0 | 只在 `sequence++` 分支更新 |
| B9 | P2 契约 | `vscreen/VirtualScreenAccessibility.java:128-151` + `util/VscreenBridgeRequest.java:316-319` | editor 的 get 返回当前文本 selection，edit 却按新文本长度校验，get→edit 对非空 selection 必失败 | 二选一定死并写进 schema |
| B10 | P3 | `vscreen/VirtualScreenInput.java:206-218` | 文本里的字面量 `%s` 被 `/system/bin/input text` 还原成空格 | 检测 `%` 返回稳定拒绝码 |
| B11 | P3 | `VirtualScreenCore.java:604-624` | `ByteArrayOutputStream` 跨线程读，`join(500)` 后不查 alive | 检查 isAlive，视为 unknown |
| B12 | P3 | `VirtualScreenManager.java:784-797` | `failStart()` 不取消已排定心跳 | 补 cancel |
| B13 | P3 | `VirtualScreenManager.java:728-742` | close 结果被丢弃，最长 60s 僵尸 Core | 待关闭队列 + WORKER 补两次 close |
| B14 | P3 | `util/VscreenBridgeRequest.java:105-108` + `VirtualScreenManager.java:659-670` | 查询串重复拼 generation/frameSeq，两侧重复参数策略相反 | 只前置缺失键 |
| B15 | 观察 | 截图/Bitmap 所有权 | 当前无泄漏/无 double-recycle，但依赖"都在主线程"的隐含前提，`Act` 回调缺 assertMain | 加 `assertMain()` |
| B16 | 观察 | 线程/Handler/socket | 基本干净；`Previews` 的 ExecutorService 进程级常驻（可接受） | — |
| B17 | 观察 | `VirtualScreenCore.java:508-518` | `getRunningTasks`+反射 `TaskInfo.displayId` 是单点，字段不可见则恒返回 ""，变更类操作全失败 | 反射提到循环外，给稳定码 |
| B18 | 观察 | 权限撤销 | 屏幕授权正确；关无障碍不会停坐标输入（设计取舍），但帮助文案没写清 | 文案 |

## 修复优先级
1. **B4**：虚拟屏"自己消失"的唯一来源，拆成三个独立小改动。
2. **B1**：唯一一条安全不变量被悄悄绕过，改动小（一个开关注入点）。
3. **B3**：唯一 ANR 面，调用点就是"停止"按钮；顺带 B12、B8。

B2 属产品决策（已有 tree+node 替代路径），与 B1 同批做。

## 测试缺口
- `VirtualScreenCore` 无任何单测；B1/B5/B6/B7/B8 全落在这一层
- 无"轮询不应写 observed"的断言（B1 至今未被发现的原因）
- `VirtualScreenPreviews` / `VirtualScreenAccessibility` 无测试
- 插件与 Java 的字段级契约无对照测试（两侧各测各的）
