# 快递助手

个人 Android 快递助手，汇总京东、拼多多、淘宝／菜鸟和抖音的订单与物流。使用 Kotlin、Jetpack Compose、Material 3、Room、Android Keystore 和 WorkManager。包名 `com.logisticsmanagement.app`，版本 `1.1.0`、versionCode `2`，最低 API 26，编译及目标 API 36。

## 开发状态与安装包

2026-10-17 起暂停功能开发。T00 至 T15 已执行并保存记录，完整业务验收仍有未完成项目。当前源码、测试、账号与 profile、原始证据及本机工具继续保留，暂停期间不自动启动开发测试或抖音采集。

[下载 1.1.0 开发预发布](https://github.com/73mtq/logistics-management/releases/tag/v1.1.0-dev.20261017)：提供主 APK、SHA-256 和签名说明。使用既有调试签名，适合当前开发验证；测试 APK 及私人证据保存在本机。下载后核查 SHA-256，通过 `adb install -r app-debug.apk` 覆盖安装；沿用现有账号资料需要保留应用数据。

已完成工作及继续开发顺序见[开发计划与恢复入口](docs/development-plan.md)，实际通过、失败、跳过和缺少样本范围见[验收记录](docs/acceptance.md)。恢复开发由用户指定目标后开始。

## 页面与使用

- **包裹**：搜索商品名称、运单、备注或账号，按平台、账号、状态及归档筛选。待发货订单和尚未关联物流的抖音订单分别显示；关联成功后显示实际包裹。
- **包裹详情**：查看承运商、运单、取件信息、预计送达、来源及逐条轨迹，复制运单和取件码，保存备注、归档及恢复包裹。抖音来源入口携带目标订单编号进入采集页面。
- **日历**：按设备时区显示预计送达和当天物流更新，支持日期选择、切换周和返回今天。
- **设置**：添加、登录、启停及移除账号，设置后台更新、抖音页面采集及 Jev 辅助判断。

界面跟随系统明暗主题和字体。T14 使用真实副本验证正常字体、1.5 倍字体、明暗主题、键盘范围、备注及归档保存；取件码复制缺少真实样本。完整范围见[验收记录](docs/acceptance.md)。

## 平台范围与验证依据

| 平台 | 读取方式 | 已取得的真实证据与边界 |
| --- | --- | --- |
| 京东 | 官方订单网页响应与物流页 | 已确认业务组合的即时配送、充值及服务订单过滤和副本清理；完整账号分页及部分类型样本仍未完成验收 |
| 拼多多 | 官方订单响应、详情及物流页 | T04 列表分页、T05 六个物流样本连续两轮及登录失效处理通过；个别历史网络失败诊断仍缺少完整依据 |
| 淘宝／菜鸟 | 同账号 profile 的 MTOP 请求及官方物流资料 | 登录、订单与轨迹读取取得真实证据；T07 已确认类型过滤通过，部分服务详情及网页分页范围未完成验收 |
| 抖音 | 官方 App 的无障碍节点 | 已绑定真实抖音号，导入一个符合条件的已签收订单及关联物流；停止继续和进程恢复取得证据，完整列表结束及部分候选字段仍未确认 |

这些结果来自对应阶段的真实记录，不能代表全部账号或当前平台服务持续可用。T06 至 T14 的未完成范围继续保留；详情见[分阶段验收](docs/acceptance.md)。

## 账号与同步

网页账号使用独立 WebView profile，登录、同步和来源页面共用对应会话。登录、验证码和系统授权由用户在手机完成。账号移除后停止更新并清理其凭据与浏览资料，已经导入的业务记录保持。

前台自动更新采用十五分钟节流；三十分钟后台更新默认关闭，由 Android 根据网络、电量和系统限制调度。网页账号共用 `SyncCoordinator`；抖音页面采集由用户在前台启动。执行期限默认八分钟。

同步按批次保存订单及待查询进度，失败或中断后保留已提交资料。首次导入已完成订单使用最近九十天规则；已经导入的记录持续保留。具体日期要求和平台差异见[架构说明](docs/architecture.md)。

## 抖音采集与 Jev

在设置中选择抖音账号和 App 页面采集，进入采集页面后使用“开始新一轮”或“继续采集”。固定导航为账号核查后进入首页、商城、我的订单、全部，再读取目标详情和物流。需要手动导航时使用“正常触摸导航”，返回后恢复采集；结束、取消或期限到达时解除本服务请求的触摸模式。

系统无障碍服务的“已启用”与实际连接状态分别检查。跨安装、跨测试进程自动重连仍未解决。采集服务未连接时需要用户完成系统设置；阶段文档核查不需要启动采集。

Jev 在设置中配置 API key，经 `CredentialStore` 加密保存；默认关闭。启用后使用 `jev-1.14.0` 判断商品区域和物流正文。请求仅包含公共标签及必要结构关系，完整账号、订单、运单、地址和商品原文保存在本机。单次期限十秒，每轮最多一百次；至少 `0.95` 概率且通过程序检查才采用，交易操作禁止自动执行。联网调用产生费用。

开发阶段使用 Jev 辅助证据核查时，固定返回证据支持、证据矛盾、资料不足或无法确定，结果逐项核实。T15 的小规模文档判断结果、调用统计和未验证范围见验收记录；整体提速尚未完成同条件比较。

## 构建与安装

本机验证使用 Android Studio 的 JBR 21、Android SDK API 36 和 Gradle wrapper 8.13，JVM target 为17。PowerShell 中按本机路径设置：

```powershell
$env:JAVA_HOME = 'D:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = 'D:\Android\Sdk'
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
```

产物为 `app/build/outputs/apk/debug/app-debug.apk` 和 `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。当前交付使用既有调试签名。

```powershell
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.logisticsmanagement.app/.MainActivity
```

启动应用会执行符合条件的前台自动更新。设备验收前保存配置及一致副本；真实资料测试需要按目标测试类显式传入账号、输入路径、独立数据库名称和唯一 runName。保留数据的验收使用直接 `am instrument`，检查实际测试数量及断言。

主机测试报告位于 `app/build/reports/tests/testDebugUnitTest/`，Lint 报告位于 `app/build/reports/lint-results-debug.html`。`NativeNavigationEvidenceTest` 需要真实 T13 资料目录及相应配置，缺少条件会跳过；构建成功与平台业务验收分别记录。

## 本地数据与开发入口

业务资料保存在 Room schema v2，凭据和原生采集进度使用 Android Keystore 加密。应用排除系统备份和设备迁移数据；卸载会清除本地资料。完整真实采集和账号资料仅保存在本机忽略目录。

- [项目地图](docs/project-map.md)：模块、流程和源码入口。
- [架构说明](docs/architecture.md)：事务、来源合并、恢复与隐私。
- [验收记录](docs/acceptance.md)：真实通过、失败、跳过及未运行范围。
- [开发计划与恢复入口](docs/development-plan.md)：暂停状态、优先顺序、验收条件和本机资料位置。
- [CodeGraph](docs/codegraph.md)：本地索引及查询命令。
- [开发说明](AGENTS.md)与[第三方许可](THIRD_PARTY_NOTICES.md)。

修改平台读取逻辑使用真实账号、响应和页面验证。未知结构报告 `UNSUPPORTED_RESPONSE`，登录、安全验证及明确服务失败分别展示。程序错误在发生位置暴露。
