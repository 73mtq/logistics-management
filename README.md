# 快递助手

个人 Android 快递助手，汇总京东、拼多多、淘宝／菜鸟和抖音的订单与物流。使用 Kotlin、Jetpack Compose、Material 3、Room、Android Keystore 和 WorkManager。包名 `com.logisticsmanagement.app`，版本 `1.2.0`、versionCode `3`，最低 API 26，编译及目标 API 36。

## 开发状态与安装包

2026-11-27 完成 S8 账号清理与后台任务执行闭环：账号移除时按账号清理 native-entry-evidence，使用真实证据副本验证新格式与旧格式目录；Pinky 创建并移除临时京东账号，验证已加载 profile 的延迟删除与重启清理；开启每30分钟后台更新并核对 AccountSyncWorker 的实际执行。同日完成 S7 页面覆盖、版本与公开发布：在真实生产库上核对包裹列表（26 件已签收、搜索、状态筛选、平台与账号筛选、空结果）、京东包裹详情（复制运单号、25 条轨迹、备注保存与恢复、归档与恢复）、日历（当天物流更新 3 件、日期选择）与设置页（3 个账号卡片、自动更新开关），页面数值与数据库一致；版本升到 1.2.0、versionCode 3，发布 release 签名包并记录 APK SHA-256 与证书指纹。S1 至 S6 的读取与清理能力沿用：抖音物流列表直达与详情读取；淘宝、拼多多、京东短信会话恢复与两轮读取（京东订单页迁移到 o.m.jd.com 与 newUserAllOrderList）；账号移除、共享来源清理与后台调度核对。取件码复制、未关联抖音订单卡片与待发货订单卡片缺少真实样本，保持未覆盖。T00 至 T15 的阶段记录、账号与 profile、原始证据及本机工具继续保留；本轮页面覆盖使用 Pinky，构建、安装、instrument 与副本使用 adb，两类操作串行执行，不自动启动平台查询或抖音采集。

[下载 1.2.0 开发预发布](https://github.com/73mtq/logistics-management/releases/tag/v1.2.0-dev.20261127)：提供 release 签名主 APK、SHA-256 和签名说明。测试 APK 及私人证据保存在本机。下载后核查 SHA-256，新安装使用 `adb install app-release.apk`；1.1.0 调试包与本发布签名不同，覆盖安装会被拒绝，需要卸载旧包后安装，卸载会清除本地账号、profile 与业务资料。

已完成工作及继续开发顺序见[开发计划与恢复入口](docs/development-plan.md)，实际通过、失败、跳过和缺少样本范围见[验收记录](docs/acceptance.md)。

## 页面与使用

- **包裹**：搜索商品名称、运单、备注或账号，按平台、账号、状态及归档筛选。待发货订单和尚未关联物流的抖音订单分别显示；关联成功后显示实际包裹。
- **包裹详情**：查看承运商、运单、取件信息、预计送达、来源及逐条轨迹，复制运单和取件码，保存备注、归档及恢复包裹。抖音来源入口携带目标订单编号进入采集页面。
- **日历**：按设备时区显示预计送达和当天物流更新，支持日期选择、切换周和返回今天。
- **设置**：添加、登录、启停及移除账号，设置后台更新及 Jev 辅助判断；抖音账号卡片显示无障碍、连接与采集状态。

界面跟随系统明暗主题和字体。T14 使用真实副本验证正常字体、1.5 倍字体、明暗主题、键盘范围、备注及归档保存；取件码复制缺少真实样本。完整范围见[验收记录](docs/acceptance.md)。

## 平台范围与验证依据

| 平台 | 读取方式 | 已取得的真实证据与边界 |
| --- | --- | --- |
| 京东 | 官方订单网页响应与物流页 | S5 完成短信会话恢复、订单页迁移到 o.m.jd.com 与 newUserAllOrderList 后的 hasNext 分页（单次 50 笔去重）、orderTrackBusiness 物流读取与两轮稳定（1 笔订单、25 条轨迹、查询队列 0）；清除会话后 NEEDS_LOGIN 与重新登录 READY 均通过；待发货、待收货、302 与安全验证现场样本未完成 |
| 拼多多 | 官方订单响应、详情及物流页 | S4 完成短信会话恢复、两轮订单物流读取（20 笔订单、24 条轨迹、查询队列 0）与分页标签计数；平台结束标志与原生页面对照未完成；T04 列表分页、T05 六个物流样本连续两轮及登录失效处理继续保留 |
| 淘宝／菜鸟 | 同账号 profile 的 MTOP 请求及官方物流资料 | S3 完成短信会话恢复与两轮订单物流读取；T07 已确认类型过滤通过，部分服务详情及网页滚动分页未完成验收 |
| 抖音 | 官方 App 的无障碍节点 | 已绑定真实抖音号，导入一个符合条件的已签收订单及关联物流；停止继续和进程恢复取得证据，完整列表结束及部分候选字段仍未确认 |

这些结果来自对应阶段的真实记录，不能代表全部账号或当前平台服务持续可用。T06 至 T14 的未完成范围继续保留；详情见[分阶段验收](docs/acceptance.md)。

## 功能要求与已知限制

使用需要 Android 8.0（API 26）及以上设备。网页平台依赖系统 WebView 的多 profile、Cookie 读取、文档脚本、Web 消息和浏览数据删除能力；缺少任一能力时对应平台无法登录或同步。抖音采集需要开启无障碍服务并在应用内手动启动。Jev 辅助判断需要自行配置 API key，可以关闭。

已知限制：京东待发货与待收货样本、302 与安全验证现场样本、原生订单计数对照、淘宝部分服务详情与网页滚动分页、抖音完整列表结束与候选字段、完整物流历史、真实抖音账号移除仍在后续范围；按账号证据清理、已加载 profile 的延迟删除与 WorkManager 实际执行已在本机验证。抖音订单列表卡片不含订单号，未导入订单仍需进入详情读取订单号。网页会话可能过期，失败与验证状态显示在账号卡片。淘宝、拼多多与京东短信登录依赖通知使用权，HyperOS 的自启动管理可能在应用重启后拒绝重新绑定通知监听，需要在系统设置中允许该应用自启动。拼多多 H5 登录会被平台视为其他设备登录，原生 App 会退出；该平台当前没有免密入口，登录需要绑定号码接收短信。发布包使用本机 release keystore 签名（证书 SHA-256 c53606e0…）；本机设备保留调试签名安装包以保留账号、profile 与本机证据，从调试包切换到发布包需要卸载并清除本地资料。取件码复制、未关联抖音订单卡片与待发货订单卡片缺少真实样本，页面验证保持未完成。设备 Compose 页面测试使用 espresso-core 3.8.0-alpha04，因为 Android 17 移除了旧版反射调用的 InputManager.getInstance；稳定版发布后应替换。

## 账号与同步

网页账号使用独立 WebView profile，登录、同步和来源页面共用对应会话。登录、验证码和系统授权由用户在手机完成。账号移除后停止更新，清理待查询项、凭据、浏览资料、原生采集进度及可归属到该账号的原生证据目录；无法归属的旧目录继续按全局保留规则处理。已经导入的业务记录保持。未加载 profile 在移除时直接删除，已加载 profile 的实际删除在后续进程完成。

前台自动更新采用十五分钟节流；三十分钟后台更新默认关闭，由 Android 根据网络、电量和系统限制调度；S6 已核对系统登记、联网约束与取消。网页账号共用 `SyncCoordinator`；抖音页面采集由用户在前台启动。执行期限默认八分钟。

同步按批次保存订单及待查询进度，失败或中断后保留已提交资料。首次导入已完成订单使用最近九十天规则；已经导入的记录持续保留。具体日期要求和平台差异见[架构说明](docs/architecture.md)。

## 抖音采集与 Jev

从设置的抖音账号卡片进入采集页面，使用“开始新一轮”或“继续采集”。固定导航为打开抖音后从首页进入商城、我的订单、全部；已绑定账号跳过“我”页。已导入订单按商品标题定位卡片，点“更多”后从弹窗点“查看物流”；未导入订单进入详情读取订单号；列表不在顶部时自动滑动回到顶部。需要手动导航时使用“正常触摸导航”，返回后恢复采集；结束、取消或期限到达时解除本服务请求的触摸模式。默认只保留采集进度与每轮汇总；设置中的“记录完整采集诊断资料”可以保存扫描、动作与耗时，保留最近 3 轮并清理超过 7 天的目录。

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

产物为 `app/build/outputs/apk/debug/app-debug.apk` 和 `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。当前开发包使用调试签名；1.2.0 发布包使用本机 keystore 签名，产物为 `app/build/outputs/apk/release/app-release.apk`，可用 `apksigner verify --print-certs` 核对证书 SHA-256 c53606e0…。发布包构建：设置 `RELEASE_STORE_FILE`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD` 后执行 `:app:assembleRelease`。切换签名时可以先加 `-PdebuggableRelease=true` 构建临时可调试发布包，安装后通过 `run-as` 恢复整包备份，再安装正式包；该流程会先卸载旧包，必须在确认备份可用后执行。keystore 与密码保存在本机 `work/release/`，不进入仓库；发布签名与调试签名不同，覆盖安装到已有调试包会因签名不一致被拒绝，切换签名前需要备份资料并卸载旧包。推送与拉取请求由 [GitHub Actions](.github/workflows/android.yml) 执行构建、主机测试和 Lint 并上传报告；工作流不运行设备测试，不接触真实账号或私人资料。

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
- [开发计划与恢复入口](docs/development-plan.md)：阶段顺序、完成条件、验收要求和本机资料位置。
- [CodeGraph](docs/codegraph.md)：本地索引及查询命令。
- [开发说明](AGENTS.md)与[第三方许可](THIRD_PARTY_NOTICES.md)。

修改平台读取逻辑使用真实账号、响应和页面验证。未知结构报告 `UNSUPPORTED_RESPONSE`，登录、安全验证及明确服务失败分别展示。程序错误在发生位置暴露。
