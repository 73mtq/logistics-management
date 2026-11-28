# 项目重点与难点记录（2026-11-28）

## 背景

S11 阶段的目标是：在 30 分钟周期后台任务 `AccountSyncWorker` 执行期间移除一个临时京东账号，取得系统调度、Worker 取消、同步终止、账号清理与业务数据保留的证据。

实际执行没有完成“同步进行中移除”的核心证据。本记录保留已经验证的修复、失败的具体原因、当前手机状态和后续可用的做法，供继续开发时直接使用。

## 实际结果

已经完成的改动：

- `PlatformBrowserActivity` 在京东、淘宝、轮询登录成功与“完成登录”路径调用 `flushProfile`，把 profile 的 Cookie 写入磁盘。
- `SyncCoordinator` 增加 `SyncStart`、`SyncCancel`、`AccountRemoval` 日志，记录账号 UUID、阶段、计数与 `activeJobs` 命中情况。
- `AccountSyncWorker` 增加开始、成功、失败、终止与结束日志；WorkManager 2.12.0 的 `onStopped` 是 final，终止日志改由 `CancellationException` 分支记录。
- 四项 Gradle 命令在本机通过：`:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:app:testDebugUnitTest`、`:app:lintDebug`；主机测试 42 项、0 失败、12 跳过；Lint 0 errors、75 warnings、2 hints。

已经在设备上验证的修复：

- 第一个临时京东账号在登录后 30 分钟的后台任务中报 `NEEDS_LOGIN`，原因是登录成功时 profile 的 Cookie 只存在内存中，没有写入 `app_webview/Profile 12/Cookies`；WebView 进程重建后 `.jd.com` 的 `pt_key`/`pt_pin` 丢失。
- 增加 `flushProfile` 后重新登录第二个临时账号，Profile 13 的 Cookies 中 `.jd.com` 的 `pt_key`/`pt_pin` 存在且 `is_persistent=1`；账号状态为 READY，一轮同步后 orders 36、Shipment 27、SourceRef 36、TrackingEvent 106、TrackingQueue 0。

未完成的部分：

- 没有取得“后台 Worker 正在同步时移除账号”的时间线。第一次移除发生在同步结束之后，日志为 `AccountRemoval start ... active=false`。
- `S11RemovalEvidenceTest` 没有在设备上执行；两个 S11 测试文件没有提交。
- `docs/development-plan.md` 与 `docs/acceptance.md` 没有写入 S11 记录。

## 关键时间线

| 时间 | 事件 |
| --- | --- |
| 2026-11-28 08:37 | 第一个临时京东账号登录成功，自动同步导入 2 笔订单 |
| 09:08:29 | 第一次后台 Worker 启动，50 毫秒后以 `NEEDS_LOGIN` 失败；profile 12 没有 `pt_key`/`pt_pin` |
| 09:18 | 重新登录第一个临时账号，Cookie 写入修复生效，Profile 12 的 Cookies 出现 `pt_key`/`pt_pin` |
| 09:38:59 | 第二次后台 Worker 启动（旧的 30 分钟周期） |
| 09:49:07 | 执行移除，日志 `active=false`，说明同步已经结束 |
| 09:59:54 | 第二个临时账号（S11 后台移除 2）同步完成，状态 READY |
| 10:01:43 | 开启新的 30 分钟后台周期，JobScheduler 登记 `Minimum latency +29m59s995ms` |
| 10:16 | 用户停止项目，Pinky 租约结束 |

## 难点与原因

### 1. 京东登录会话没有写入 profile

现象：后台 Worker 在 `SyncStart` 后约 50 毫秒抛出 `NEEDS_LOGIN`，没有进入订单读取。

原因：`hasPlatformLoginSession` 检查 profile 的 `https://www.jd.com/` Cookie 中是否存在 `pt_key` 与 `pt_pin`。登录成功路径只更新账号状态并调用 `finish()`，没有调用 `profile.cookieManager.flush()`。Cookie 在登录 WebView 使用期间存在内存中，WebView 进程或 profile 重载后丢失，后台任务在同一应用进程内也读不到。

修复：在登录成功、`finishLogin` 与 `pollLoginSession` 路径调用 `flushProfile`：

```kotlin
private fun flushProfile(account: Account) {
    registry.profiles.profile(account).cookieManager.flush()
}
```

验证：第二个临时账号登录后，Profile 13 的 Cookies 数据库出现 `.jd.com` 的 `pt_key`/`pt_pin`，`is_persistent=1`；随后一轮同步成功。

影响：所有网页平台登录成功后都应把 Cookie 写入磁盘，不能依赖进程存活期间的缓存。

### 2. 后台周期任务的时间窗口很短

WorkManager 的周期任务首次执行在开启后约 30 分钟，系统仍可能因配额、网络和电量推迟。京东一轮同步约 19 至 23 秒，订单读取与物流查询完成后 Worker 立即返回。

第一次尝试中，监视任务在 09:38:59 检测到 `SyncStart`，但 `job_output` 到 09:46:54 才把触发结果返回给执行端；执行端准备点击时同步已经结束。最终移除时间为 09:49:07，日志 `active=false`，不符合“同步进行中移除”的完成条件。

### 3. 监视脚本的子进程延迟了通知

`work/s11_watch.sh` 使用 `nohup adb logcat` 捕获日志。脚本检测到 `SyncStart` 后写出触发文件并退出，但 `adb logcat` 子进程继续运行并占住后台任务的进程组，后台任务在工具侧一直显示为 running，等待方直到很晚才收到结果。

修正：检测到触发后先 `kill` 日志子进程，再退出；超时路径也执行同样的清理。修正后的脚本没有再次用于完整的 30 分钟窗口。

### 4. Pinky 任务与控制权

- Pinky 默认在后台显示中运行；第一次 `phone_connect` 后打开应用，应用位于后台显示。
- `phone_handoff` 把控制交给用户后，应用会回到手机主屏幕；交还控制后，后台显示可能为空，需要 `phone_connect(own_screen: true)` 才能继续操作主屏幕上的登录页或对话框。
- 30 分钟的等待期间任务租约可能失效；用户接管、按 Stop 或离开任务都会结束租约。用户按 Stop 后租约直接消失，不能继续调用手机工具。
- 反复使用“请交回手机控制”这类描述，没有把用户需要做的动作说清楚，造成了额外打扰。正确做法是一次只描述一个动作，例如“把箭头拖进拼图缺口并松手”。

### 5. 数据合并会改变行数与测试断言

账号移除本身保留业务记录：目标账号的订单与来源保留，查询项、凭据、profile、原生进度与可归属证据清理。同步过程中的 `combineShipments` 会按承运商与运单把来源合并到已有包裹，删除旧包裹行并写入 `ShipmentAlias`。

第一次尝试的 `before` 快照中 Shipment 为 29；同步合并后变为 27，但 SourceRef、订单与轨迹计数保持，来源没有成为孤立行。原先 `S11RemovalEvidenceTest` 断言“每一条移除前的 Shipment 行都必须存在”，这个断言与合并语义冲突，需要改为允许别名映射、检查无孤立来源与事件，并把“其他账号不变”作为严格条件。

### 6. 真实服务验证的约束

- 京东登录偶尔要求拖动箭头拼图，必须由本人在手机上完成；短信验证码由通知监听填入。
- 项目规则禁止使用 mock、伪造订单或伪造响应；账号移除验证必须使用真实账号、真实 profile 与真实 Worker。
- 真实账号的会话状态、平台风控、单设备登录策略和页面结构都会影响同一套流程，无法用一次短测试覆盖。
- 用现有活动账号做移除验证会破坏该账号的会话，需要在验证后重新登录；用临时账号则每次都需要一次拼图登录。

## 建议的继续做法

1. 用 `WorkManager` 登记一个一次性的 `AccountSyncWorker`，在 `S11BackgroundRemovalTest` 中等待 `WorkInfo.RUNNING` 与账号状态 `SYNCING`，再调用 `syncCoordinator.removeAccount(id)`。这条路径与周期 Worker 共用 `AccountSyncWorker`、`SyncCoordinator` 与 `removeAccount`，可以稳定取得 `SyncCancel`、`AccountRemoval` 与 `WorkInfo CANCELLED`。周期任务的实际执行已经由 S8 记录，不需要为时序重复等待 30 分钟。
2. 移除测试使用临时账号，测试后不保留活动账号；账号行按既有规则保留为 `REMOVED`，订单与来源保留。
3. 把测试断言改为：账号行 `REMOVED`；查询项、凭据、profile、原生进度清理；其他账号的订单、来源、包裹与轨迹不变；目标账号订单与来源保留；每个来源仍指向存在的包裹；允许 `ShipmentAlias` 记录合并。
4. 为所有网页平台登录成功路径统一增加 Cookie 写入磁盘，并在设备测试中检查 profile 的 Cookies 数据库。
5. 使用手机时保留专用窗口，Pinky 任务保持短时、单一动作；需要用户操作时只给一个明确动作，不要求用户理解工具概念。

## 当前手机状态与恢复步骤

截至 2026-11-28 10:16，应用状态如下：

- “每30分钟后台更新”处于开启状态，JobScheduler 已登记下一次 `AccountSyncWorker`，预计约 10:31 执行。
- 临时账号“S11 后台移除 2”处于 READY、启用状态；第一个临时账号“S11 后台移除”已经移除。
- 京东活动账号（京东 132****2972）、拼多多、淘宝的“启用账号更新”被临时关闭。
- 拼多多处于需要登录，淘宝处于需要完成验证；京东活动账号仍保留 profile 与凭据。
- 移除确认对话框可能仍停留在应用前台。

恢复步骤：

1. 打开快递助手，进入设置；如果看到“移除账号「S11 后台移除 2」”对话框，点“保留账号”。
2. 滚动到页面底部，关闭“每30分钟后台更新”。
3. 分别打开京东、拼多多、淘宝账号卡片的“启用账号更新”。拼多多与淘宝可能需要重新登录或完成验证。
4. 如不再需要第二个临时账号，在其卡片点“移除账号”并确认；已经导入的订单与来源会保留。
5. 如需恢复京东活动账号的更新，使用“登录账号”完成一次短信验证；出现拼图时由本人拖动箭头完成。

## 证据位置

- `work/evidence/platform-logistics-v2/S11/S11-20261127-2130/`：before、before2、before3、after-install、resume、state-0949、after-login2、after-login2b 等快照与 WebView Cookies 副本。
- `work/logs/platform-logistics-v2/S11-*.log`：构建、同步与移除日志。
- `work/evidence/pinky/S11/`：Pinky 任务记录目录，本次未导出完整报告。
- 读屏文本、截图、真实订单与运单资料只保存在本机忽略目录，不进入公开仓库。

## 代码与测试说明

- 本次提交包含三个生产文件：`PlatformBrowserActivity.kt`、`SyncCoordinator.kt`、`Workers.kt`。
- `S11Evidence.kt` 与 `S11RemovalEvidenceTest.kt` 保留在本机工作区，未提交；测试没有在设备上执行，断言需要按合并语义调整。
- 四项 Gradle 检查在加入日志与 Cookie 修复后通过；提交后由 Android CI 复核。
