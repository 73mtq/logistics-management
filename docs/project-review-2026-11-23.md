# 项目审查与后续开发计划

审查日期：2026-11-23，Asia/Shanghai。
审查基线：commit 5c049f1f541373ea04d42e1e79301ac034b0dea4，工作区干净；版本 1.1.0、versionCode 2、Room schema v2。
审查方式：阅读公开文档、阶段记录与源码，执行构建、主机测试与 Lint；没有连接手机，没有访问真实账号，没有修改业务代码与数据。

## 审查范围与依据

本次审查覆盖产品范围、页面与数据流、四个平台读取、抖音原生采集、数据保留、隐私与凭据、构建与测试、文档与交付。公开文档包括 README、docs/project-map.md、docs/architecture.md、docs/acceptance.md、docs/development-plan.md、docs/jev-workflow.md、docs/codegraph.md 与 AGENTS.md [S1-S8]；阶段记录包括主状态、执行方案、交接入口、T10 至 T15 与暂停交付 [S9-S15][S36-S38]；源码包括 app/src/main 下的 42 个 Kotlin 文件与 app/src/test、app/src/androidTest 下的 28 个文件 [S16-S33]。Git 历史包含 2026-11-05 的重构提交 a05f752 与之后的提交 5c049f1 [S34]。

本次执行了三项构建检查：:app:assembleDebug、:app:testDebugUnitTest、:app:lintDebug。构建任务成功；assembleDebug 与 testDebugUnitTest 为 UP-TO-DATE，lintDebug 本次执行。主机测试报告记录 12 项通过、7 项跳过，本次没有重新执行这些测试；Lint 为 0 errors、71 warnings、2 hints [S35]。设备仪器测试没有运行；真实账号同步、平台响应解析、抖音采集和数据库比较都没有在本次执行。文中关于平台真实可用范围的判断以 docs/acceptance.md 的记录为依据；关于当前代码行为的判断以源码路径为依据，两类判断分别标注。

## 项目现状

产品面向个人使用，汇总京东、拼多多、淘宝／菜鸟和抖音的订单与物流，提供包裹列表、详情、日历和设置页面；网页账号使用独立 WebView profile，抖音使用官方 App 的无障碍节点采集；业务数据保存在 Room schema v2，凭据和原生采集进度使用 Android Keystore 加密 [S1][S3]。版本保持 1.1.0、versionCode 2，最低 API 26，编译与目标 API 36 [S1][S33]。

2026-10-17 项目暂停功能开发，T00 至 T15 的阶段记录、账号、profile 与证据保留 [S1][S4]。2026-11-05 提交 a05f752 对启动恢复、Repository、SyncCoordinator、平台适配器、采集页面与包裹筛选执行行为保持重构，并新增 5 项筛选主机测试；该提交修改 21 个文件，新增 542 行、删除 300 行 [S34]。docs/acceptance.md 记录了这次重构的构建、主机测试和 Lint 结果，同时明确 86 项设备仪器测试没有运行 [S4:81-88]。这是当前最大的验证缺口：后续工作建立在这批重构代码上，而设备行为没有对应的新证据。

平台真实可用范围的边界如下。表中的“已取得”来自对应阶段的真实记录，“仍未完成”继续保留，不能由源码中存在相应流程推断为可用 [S1][S4]。

| 平台 | 读取方式 | 已取得的真实依据 | 仍未完成 |
| --- | --- | --- | --- |
| 京东 | 官方订单网页响应与物流页 DOM | T06 已确认业务组合的即时配送、充值及服务订单过滤，真实回放及副本清理 [S4] | 完整账号分页、部分类型、早期字段变化 |
| 拼多多 | 官方订单响应、详情及物流页 | T04 列表分页；T05 六个物流样本两轮；T08 签收传播 [S4] | 个别历史网络失败、共享包裹、异常时间比较 |
| 淘宝／菜鸟 | 同账号 profile 的 MTOP 请求与官方物流资料 | T03 订单与轨迹；T07 类型过滤与接口分页 [S4] | 网页滚动分页、部分服务详情、部分配送子类型 |
| 抖音 | 官方 App 的无障碍节点 | T10 字段读取 [S36]；T11 一个已签收订单 [S37]；T12 一个包裹五条轨迹 [S38]；T13 分页与恢复 [S4][S12] | 完整列表结束、两笔候选、完整物流历史、服务自动重连 |

T14 记录的手机主库为 6 个账号、47 个订单、47 个包裹、47 个来源、110 条轨迹、4 个查询项，八张表字段与应用配置在指定范围内保持 [S13][S4]。本次没有连接设备重新核对这组数字；恢复开发时需要重新确认当前账号、数据库和服务状态，不能把 T14 的副本当成今天的状态。

## 架构与流程

当前代码按读取、事务和调度三部分组织，职责边界总体清楚。平台适配器负责登录、读取和解析；LogisticsRepository 负责事务保存、类型排除清理和包裹合并；SyncCoordinator 负责账号锁、订单批次、物流查询、期限、进度与恢复；页面观察 Room Flow 并调用现有入口 [S3][S18][S17]。

| 层 | 入口 | 职责 |
| --- | --- | --- |
| 应用与容器 | LogisticsApplication.kt、AppContainer.kt | 创建共享对象，启动恢复，在 ready 完成后允许同步 |
| 页面 | LogisticsApp.kt、ShipmentsScreen.kt、ShipmentDetailScreen.kt、CalendarScreen.kt、SettingsScreen.kt | 包裹、详情、日历、账号与采集设置 |
| 调度 | SyncCoordinator.kt、Workers.kt | 账号锁、八分钟期限、批次保存、物流队列、后台更新 |
| 网页平台 | PlatformRegistry.kt、PlatformWebView.kt、BrowserAdapters.kt、TaobaoAdapter.kt | 独立 profile、来源限制、消息采集、订单与物流解析 |
| 原生平台 | DouyinCaptureService.kt、NativePaginationRunner.kt、NativeOrderImportSession.kt、DouyinNativeOrders.kt | 无障碍节点读取、固定导航、分页、订单与物流导入 |
| 数据 | LogisticsRepository.kt、LogisticsDatabase.kt、Models.kt | Room schema v2、事务、稳定标识、包裹合并与别名 |
| 凭据与设置 | CredentialStore.kt、SettingsStore.kt | Android Keystore 加密、应用设置 |
| 语义判断 | NativeSemanticJudge.kt | Jev-1.14.0 页面与入口判断，0.95 门槛、十秒期限、一百次上限 |

这套结构的长处在于：业务写入集中在一个 Repository；网页来源和原生来源分别有明确入口；订单、来源、轨迹使用稳定标识；物流进度在事务中原子保存；网页消息限定官方 HTTPS 来源和主 frame；原生采集进度按账号和抖音号隔离并加密 [S3][S17][S20][S23]。这些决定让重复读取、进程重启和账号切换有可以核查的基础。

需要继续处理的结构性问题在于：AppContainer 作为服务定位器直接传入 Compose 页面，页面可以调用 Repository 和 Coordinator，测试替换点较少；SyncCoordinator 同时承担网页同步、原生采集、账号移除、后台安排和通知清理，文件约 360 行，理解一段流程需要跨多个分支；Repository 的合并、排除和签收传播没有主机测试或独立数据库回归测试；原生采集把证据写入、进度写入和业务提交放在同一条高频路径上，性能与容量边界没有测量数据 [S17][S18][S25][S31]。

## 主要发现

以下发现按影响排序。每条给出代码位置或文档记录；涉及运行时行为的结论标注为需要设备验证的路径，不把静态推断当作已经发生的故障。

### 发现 1：迁移后的抖音账号进入 WEB 模式，网页路径没有订单解析

MIGRATION_1_2 为所有旧账号添加 captureMode，默认值是 WEB [S16:94]。新建账号时，Repository 只为抖音设置 APP_PAGE [S17:84]；迁移不会走这个入口。DouyinAdapter 的 sync 在收到响应后直接抛出“抖音订单响应已经采集，当前响应结构需要通过真实账号确认”，没有订单解析分支 [S19:426]。设置页仍提供“通过抖音 App 页面采集”开关，关闭后账号成为 WEB [S27:60]。

影响很直接：v1 中已经存在的抖音账号在升级到 schema v2 后是 WEB；自动同步会把该账号当成网页账号，打开抖音网页，然后在响应或登录检查处失败，账号进入 FAILED 或 NEEDS_VERIFICATION。T11 开始时账号已经是 APP_PAGE [S37]，迁移本身没有完成这一步。用户从包裹页点“重新更新”时，走的仍是网页同步 [S28:113]。

处理方向是让抖音只有 APP_PAGE 一种可用模式：启动时把 DOUYIN 且 captureMode 为 WEB 的行规范为 APP_PAGE；设置页去掉这个开关，改为说明文字；SyncCoordinator 在网页自动同步中防御性排除 DOUYIN；DouyinAdapter 保留时给出明确的配置错误。改动需要一条使用真实 v2 数据库行的迁移验证，确认其他平台账号的 captureMode 不受影响。

### 发现 2：手动刷新一个包裹会把整个账号标成失败，而且只刷新第一个来源

refreshShipment 从包裹的可用来源中取第一个可用账号 [S18:301]，查询成功后调用 applyTrackingAndFinish；任何 AppFailure 都会进入 catch，随后调用 failAccount 并把异常继续抛出 [S18:315]。failAccount 对 SERVICE_ERROR 也设置 AccountState.FAILED [S18:284-297]。

这意味着京东某个订单暂时没有物流轨迹、拼多多某个页面没有物流入口，都会把整个账号标成更新失败，包裹页出现“重新更新”横幅，其他包裹的更新状态被这条失败覆盖。批量同步路径的处理不同：单件 SERVICE_ERROR 只记录 TrackingFailure，不改变账号状态 [S18:246-251]。两处行为需要统一：手动刷新只对 NEEDS_LOGIN、NEEDS_VERIFICATION、UNSUPPORTED_WEBVIEW 这类需要用户处理的失败改变账号状态；单件服务错误在详情页或 Snackbar 中显示，账号保持原状态。

同一处还有第二个问题：一个包裹有多个来源时，刷新只查第一个来源 [S18:301]，其他来源的轨迹不会更新。数据模型支持多来源，页面也显示多个来源；手动刷新应逐个可用来源执行，或在页面上让用户选择来源。这个改动与上面的状态分类可以一起完成。

### 发现 3：APP_PAGE 账号失败后的“重新更新”走网页同步

包裹页对 FAILED 账号的横幅按钮直接调用 syncAccount [S28:113]。syncAccount 最终通过 PlatformRegistry 选择适配器 [S18:216-222]；对于 APP_PAGE 的抖音账号，DouyinAdapter 没有订单解析，结果是再次失败。账号处于 NEEDS_LOGIN 或 NEEDS_VERIFICATION 时按钮走 openAccountLogin，能打开采集页面；FAILED 这个分支没有按 captureMode 分流。

影响是抖音原生账号一旦进入 FAILED，用户在包裹页无法通过“重新更新”恢复，只能在设置页打开采集页面。处理方向是按 captureMode 分流：APP_PAGE 打开 DouyinCaptureActivity，并显示“请开始新一轮采集”的状态；WEB 才走 syncAccount。这个改动很小，但需要在设备上验证按钮文案、返回行为和账号状态刷新。

### 发现 4：原生采集证据与进度没有容量边界，高频路径反复写入加密存储

prepareEvidence 为每个 captureId 建立 native-entry-evidence/<captureId> 目录 [S25:296]；capture 循环每 750 毫秒读取一次窗口；页面变化时写入一份 scan 密文和一份 timing 密文 [S25:209-215][S25:409-424]；同时把页面 JSON 交给 PlatformRegistry.rememberCapture，而该方法把最近 8 份内容放进一个加密 SharedPreferences 条目并 commit [S21:116-121][S25:414]。按八分钟期限计算，单轮可以产生数百份扫描文件和多轮 SharedPreferences 重写。

代码里没有清理这些目录的分支：账号移除只删除凭据和 profile，不删除 native-entry-evidence 或 native-sync-progress [S18:320-327]；应用启动也不清理旧 captureId。长时间使用后，filesDir 会持续增长，单轮采集也可能因为加密、序列化和 XML 重写产生额外延迟。这个问题在 T13 的采集资料中已经以大量扫描文件的形式出现，只是当时按证据保留处理，没有按生产容量处理。

处理方向是把生产进度与诊断证据分开：NativeSyncProgress 继续保留，用于继续采集；完整 scan、action、timing 只在诊断开关打开时写入，或者只保留最近 N 轮或 M 天；去掉采集热路径上的 rememberCapture，页面证据由原生目录自己管理；为账号移除和启动增加清理入口；记录单轮实际文件数与字节数，作为验收数据。

### 发现 5：取消、超时、账号移除与证据写入的异常类型不一致

captureRefreshed 用 checkNotNull 包装 capture 的结果，capture 在账号被停用、被移除或采集期限到达时返回 null [S25:333][S25:339-345]；checkNotNull 抛出的是 IllegalStateException。NativePaginationRunner 的 run 只捕获 NavigationRestart [S22:167]，startOrders 中的采集协程只捕获 AppFailure [S25:241]；onFinished 在 finally 中调用 writeText 写入 order-import.enc，IO 异常没有被转换成 AppFailure [S25:245]；DouyinCaptureActivity 的 lifecycleScope.launch 对缺少 accountId 或账号已移除直接 throw [S26:70]。

这些路径是否真的会崩溃取决于协程取消与主线程调度的时序，不能仅凭静态阅读断言已经发生。可以确定的是异常类型与捕获边界不一致：一部分状态变化被表达成 IllegalStateException 或 IOException，而外层只处理 AppFailure 和 CancellationException。处理方向是统一边界：用户可见的状态变化使用 AppFailure(CONFIGURATION)，停止或到期使用 AppFailure 并把账号置为 PARTIAL；采集启动协程捕获 Throwable，CancellationException 继续抛出；证据写入放到 IO 调度器并把 IOException 包成 AppFailure；Activity 入口捕获 AppFailure 并显示在状态文本中。需要按停止、八分钟到期、账号移除三种条件补测试。

### 发现 6：抖音卡片定位依赖固定路径深度

DouyinNativeOrders.card 要求 anchor.path.size 至少为 15，取前 14 个索引作为前缀，把第 15 个索引当作卡片行号 [S24:163]；cardsOnPage 又要求卡片上一行包含状态标签 [S22:37-52]。这组规则来自真实节点证据，当前抖音 40.7.0 可以工作；它同时把页面树的前 15 层结构写成了隐式条件。

影响是应用或抖音更新后，卡片行可能从第 14 层移到第 13 或第 15 层，cardsOnPage 返回空列表，runner 只会看到“列表没有变化”或“无法定位”，不会指出是卡片根路径失效。处理方向是用结构关系替代固定深度：在纵向列表内找到状态行、商品图片和标题，取它们在列表内的最近公共父节点作为卡片根；如果找不到，记录列表路径、行数、状态行数、图片数和候选标题数，并把当前范围标成 blocked，停止继续滚动。这个改动风险较高，需要先用 T13 的真实扫描做回放，再上设备。

### 发现 7：恢复采集保留旧的 blockedCards 与旧订单，complete 无法达成

NativeSyncProgressStore.begin 在 resume 为 true 时复制上一轮进度，只重置 captureId、running、listEnded、scopeConfirmed、terminalEvidence 和 reason，没有清空 blockedCards [S23:66-70]。NativeSyncProgress.complete 要求 listEnded、scopeConfirmed、terminalEvidence 非空、blockedCards 为空，并且所有订单 finished [S23:30]。NativeProgressSession.observe 又会跳过 evidenceCaptureId 与当前 captureId 不一致的订单 [S23:105-109]。

因此，上一轮因为某个卡片结构异常而 blocked，或者某个已发现订单在新一轮列表里已经不存在，恢复后这些记录仍然留在进度里，complete 永远为 false。用户会看到“继续采集”按钮，采集也能跑完，但账号一直停在 PARTIAL。处理方向是按轮次管理 blockedCards 和订单：begin 时清空本轮 blockedCards，把上一轮的 blocker 移到诊断字段；新一轮结束后，把没有再次出现的订单标成 stale 并给出原因，complete 只要求本轮订单 finished 或 stale；blockedCards 的 key 带上 cycleId，避免跨轮混淆。需要一条恢复测试覆盖“上一轮 blocked、下一轮未再出现”的场景。

### 发现 8：WebView 能力门槛没有预检，最低 API 的表述不足以说明可用条件

PlatformWebView 在创建时要求 MULTI_PROFILE、WEB_MESSAGE_LISTENER、DOCUMENT_START_SCRIPT、GET_COOKIE_INFO、DELETE_BROWSING_DATA 等能力 [S20:52-53][S20:187-190]；cookieValues 还要求 GET_COOKIE_INFO [S20:138]。这些能力由系统 WebView 提供，版本不足时 requireFeature 抛出 UNSUPPORTED_WEBVIEW，错误只在打开登录或同步时出现。README 写的是最低 API 26 [S1:5]，没有说明网页平台还依赖较新的系统 WebView。

影响是老设备或未更新 WebView 的设备无法使用京东、拼多多、淘宝的网页登录与同步，用户看到的是一条“请更新系统 WebView”的错误，应用没有在设置页提前说明。多账号 profile、cookie 读取和 document-start 脚本都依赖这些能力，去掉它们会改变会话隔离方案，代价很大。处理方向是在启动或设置页做一次能力检查，显示明确的系统 WebView 要求与当前版本；README 增加“功能要求”部分；测试至少覆盖能力缺失时的提示路径。最低 API 26 可以保留，但需要把“可安装”和“网页平台可用”分开说明。

### 发现 9：凭据解密失败没有转换成 AppFailure，设置页和同步存在崩溃路径

CredentialStore.decrypt 对格式使用 require，对 Keystore 解密直接调用 doFinal [S29:33-43]；GeneralSecurityException、IllegalArgumentException 没有在这里转换成 AppFailure。SettingsScreen 在首次组合时直接调用 credentials.getSecret 读取 Jev 凭据 [S27:35]；PlatformRegistry.accountSecret 也在同步路径上读取账号凭据 [S21:61]。如果 Keystore 密钥因为设备锁定方式变化、系统恢复或数据损坏而失效，这些调用会抛出不在这两层捕获范围内的异常。

影响是设置页可能打不开，网页同步可能在读取凭据时崩溃；用户没有清除或重建凭据的入口，只能清除应用数据。处理方向是把加密、解密、Base64 解析的失败统一包成 AppFailure(CONFIGURATION)，在设置页显示“凭据无法读取，请重新登录或清除凭据”；增加清除账号凭据和 Jev 凭据的按钮；用一条独立数据库或独立配置的测试覆盖损坏密文和错误版本。原生进度读取已经做了这类转换 [S23:43-51]，凭据路径可以沿用同一做法。

### 发现 10：Repository、Sync 与解析器的自动化回归测试不足

app/src/test 只有 4 个测试类；本次构建的主机测试任务为 UP-TO-DATE，报告记录 IdentityTest 2 项、PlatformTimeTest 5 项、ShipmentFilterTest 5 项通过，NativeNavigationEvidenceTest 7 项因为缺少 T13_EVIDENCE_DIR 跳过 [S31][S35]。app/src/androidTest 有 23 个测试类、86 项 @Test，但多数需要真实账号、真实响应或真实设备资料，缺少条件时跳过 [S32][S4]。Repository 的 ingest、removeExcludedOrders、combineShipments、resolveStatus，SyncCoordinator 的批次、期限、取消，以及四个平台的解析器，都没有主机级回归测试。

影响是 2026-11-05 的重构只能靠构建、12 项主机测试和 Lint 证明“编译与静态检查通过”，不能证明合并、排除、签收传播和恢复行为没有变化；后续修改这些高风险逻辑时，最早的反馈仍然要等到设备会话。处理方向分两层：把不依赖 Android 的判定提取成纯函数，用主机测试覆盖 90 天规则、排除清理、签收传播、别名合并和进度完成条件；用隔离数据库的仪器测试覆盖 Repository 事务，输入使用本机真实归档响应的副本，缺少资料时跳过并在报告中记录。测试数据必须标注用途，不能把构造记录当成平台验收证据。

### 发现 11：验收记录、项目地图与 CodeGraph 记录仍陈旧于代码

docs/acceptance.md 在 2026-11-05 增加了重构验证段 [S4:81]，但验收记录、项目地图和 CodeGraph 记录仍停在旧基线；docs/project-map.md 仍写“当前暂停功能开发”，没有说明重构后的验证状态 [S2]；docs/codegraph.md 的索引记录是 2026-10-06、44 个 Kotlin 文件 [S7:30]，当前 app/src 下有 70 个 Kotlin 文件。开发计划已在本次审查中更新为 P0 至 P7。

影响是下一次恢复时，读者可能按旧验收记录直接进入平台阶段，跳过对重构代码的设备回归；文件数和索引记录也会让 CodeGraph 的使用说明与当前状态不一致。处理方向是在 P0 更新验收记录、项目地图和 CodeGraph 记录，明确“代码基线 5c049f1、重构已提交、设备回归待执行”，并把本文件的审查结果链接到验收记录。

### 发现 12：发布与持续集成缺位

仓库没有 .github 工作流；release 构建的 isMinifyEnabled 为 false，交付使用既有调试签名 [S33]。构建、主机测试和 Lint 目前只在开发机上执行；没有推送后的自动检查。

影响是回归只能在本地被发现，发布包的来源与签名依赖手工步骤；调试签名适合当前开发验证，不适合长期安装包。处理方向是增加一个只运行 assembleDebug、testDebugUnitTest、lintDebug 的 GitHub Actions 工作流，不接触真实账号；在 P7 决定 release 签名与是否启用 R8，生成并备份 keystore，更新版本号、变更说明和校验值。CI 工作流会增加一个需要维护的文件和少量构建时间，收益是每次推送都有统一的编译、主机测试和 Lint 结果。

### 其他需要处理的问题

TaobaoAdapter.applyCookies 在 CookieManager 回调里使用 check(success) [S30:304]；回调线程上的失败会抛出，没有把失败交回协程。应改成 resumeWithException(AppFailure)。

removeAccount 先把账号标记为 deleted，再删除 profile 和凭据 [S18:320-327]；如果 profile 删除失败，凭据可能保留。应把凭据删除放进 finally，或先完成 profile 与凭据清理再标记删除。

包裹页的“在途”数字把待发货、异常和状态未提供都算进去 [S28:92]；这个数字更接近“未签收”，应改名或按 IN_TRANSIT + DELIVERING 计算。

设置页没有清除 Jev 凭据的入口 [S27]；用户只能替换 key，不能删除。应增加“清除凭据”，并在清除后关闭 nativeJevEnabled。

CredentialStore.deleteServiceSecrets 仍在清理 ai. 和 kuaidi100. 前缀 [S29:54-56]；这是旧功能留下的未使用代码，清理不会改变业务行为。

## 未完成验收与证据边界

docs/acceptance.md 已经逐阶段记录了未完成范围 [S4]。下面按恢复顺序整理，作为计划的输入，不重复全部阶段文本。

| 阶段 | 已取得的真实依据 | 仍缺少的条件 |
| --- | --- | --- |
| T06 京东过滤 | 已确认业务组合的即时配送、充值及服务订单过滤，真实回放与副本清理 | 完整账号分页、部分类型、六个拼多多订单 createdAt 变化的原始列表依据 |
| T07 淘宝过滤 | 即时配送类型确认、详情编号关联、接口分页与重复清理 | 网页滚动分页、其他服务详情、部分配送子类型、早期京东清理的后台执行依据 |
| T08 拼多多签收 | 已收货传播、两轮同步、较早资料保留与页面显示 | 实际在途转签收、异常时间比较、共享包裹、跨暂停全字段保持 |
| T09 中断恢复 | 取消、执行期限、进程恢复、三平台真实回放、拼多多两轮恢复 | WorkManager 实际调度、新增订单中断、部分当前失败场景 |
| T10 抖音字段 | 账号、订单与物流节点读取、重复采集、结束及期限释放 | 运输中样本、轨迹年份、整个阶段跨暂停业务保持 |
| T11 抖音订单 | 真实抖音号绑定、完整下单时间、近期已签收订单导入、重复采集 | 待发货、在途、服务类型、账号不匹配、部分生命周期样本 |
| T12 抖音物流 | 同一订单真实运单、承运商、五条轨迹、两轮保存、年份唯一区间 | 完整物流历史到底、跨年、在途、多包裹、登录与安全验证 |
| T13 抖音分页 | Jev 辅助导航、三笔真实编号、停止继续、两种进程终止恢复、八分钟期限 | 全部标签选中状态、平台明确结束、完整遍历、两笔候选字段、自动重连、整体效果对照 |
| T14 迁移与页面 | schema1 迁移、schema2 重开、两轮页面保存、备注归档、主库保持 | 历史共享来源、别名、非空用户字段、被删除平台清理分支、取件码、MainActivity 启动联合流程、跨暂停无障碍列表归因 |
| T15 文档与 Jev | 九项四分类核查、七项一致、达到 0.95 的五项一致、费用记录 | 通用准确率、UNCERTAIN 专门样本、整体提速与人工介入改善 |

设备测试没有在本次审查执行，因此“构建成功”只能说明源码可以编译、缓存的主机测试报告与 Lint 通过，不能说明 86 项设备测试通过。恢复开发时，先运行 P0 的设备回归，再进入平台阶段；任何缺少真实输入的测试继续跳过，并在阶段记录中写明跳过数量与原因。

## 后续开发计划

后续按 P0 至 P7 的顺序执行，每次由用户指定一个阶段；前一个阶段的完成条件没有满足，不进入下一个阶段。每个阶段保留现有账号、profile、Room schema v2 和证据，除非阶段目标明确要求改变。平台真实可用范围继续以设备证据和 docs/acceptance.md 为准。

| 阶段 | 目标 | 主要对应发现 | 设备会话 |
| --- | --- | --- | --- |
| P0 | 基线回归、文档校正、持续集成门槛 | 10、11、12 | 1 次 |
| P1 | 抖音账号模式、服务连接状态与导航安全 | 1、3、5、6 | 1 至 2 次 |
| P2 | 原生采集资源、恢复语义与进度重建 | 4、5、7、9 | 1 次 |
| P3 | 抖音完整订单、候选字段与物流历史 | 现有恢复顺序第 2 项 | 2 至 3 次 |
| P4 | 京东与淘宝读取范围、类型过滤与会话失败 | 现有恢复顺序第 3 项 | 2 次 |
| P5 | 数据保留、系统调度与账号移除 | 现有恢复顺序第 4 项 | 1 至 2 次 |
| P6 | 页面覆盖与 Jev 效果对照 | 现有恢复顺序第 5 项 | 1 至 2 次 |
| P7 | 发布签名、版本、文档与清理 | 12 及其他问题 | 1 次 |

P0 的工作包括：建立当前 commit、APK、数据库副本和账号状态的基线记录；更新 docs/development-plan.md、docs/acceptance.md、docs/project-map.md 与 docs/codegraph.md；增加 GitHub Actions 工作流，只运行 assembleDebug、testDebugUnitTest、lintDebug；在设备上运行 MigrationEvidenceTest、DataUiEvidenceTest、ShipmentStatusEvidenceTest、SyncRecoveryEvidenceTest 等不依赖新平台样本的回归测试，比较主库八张表和四份应用配置的前后副本。完成条件是：CI 在推送后通过；文档日期、基线和测试计数与当前代码一致；设备回归的实际通过、跳过和未运行范围记录完整；主库比较没有业务字段变化。这个阶段会增加一个工作流文件和一次设备会话，收益是后续阶段不再建立在没有设备证据的重构代码上。

P1 的工作包括：规范抖音账号为 APP_PAGE，移除设置页的 WEB 选项，在网页自动同步中排除抖音；服务重新连接时清理旧的采集状态并把账号置为 PARTIAL，增加“已启用、已连接、采集中、触摸模式”四类状态显示；把固定路径深度的卡片定位改为结构定位，并在卡片识别失败时记录具体原因；用真实 T13 资料和一次设备会话回归交易按钮拒绝规则。完成条件是：迁移后的抖音账号全部为 APP_PAGE；服务重连后不需要强制停止应用就能重新开始；当前真实列表可以识别卡片，识别失败时有明确 blocked 原因；本次设备会话接受的动作全部为读取用途，交易节点全部被拒绝。这个阶段会改动设置页、采集服务和卡片规则，风险集中在卡片定位；需要先用真实扫描回放，再上设备。

P2 的工作包括：把完整诊断证据与生产进度分离，增加证据保留上限和账号移除清理；恢复采集时按轮次清空 blockedCards，把没有再次出现的订单标成 stale，让 complete 能够给出“已完成”或“明确未完成”的结果；为损坏或不兼容的进度增加“重建采集进度”入口，保留原文件用于诊断；统一停止、超时、账号移除和证据写入的异常类型，补停止、到期、移除三种条件的测试。完成条件是：单轮采集的文件数量和字节数有记录，旧证据按保留规则清理；恢复后不会永久停在 PARTIAL；损坏进度可以在确认后重建；停止、到期、移除路径都得到 PARTIAL 或明确的配置错误，没有未捕获异常。这个阶段会改动原生采集的状态管理，收益是长期使用的容量和恢复可靠性。

P3 的工作包括：补齐全部标签选中状态、平台明确列表结束、完整遍历和两笔候选字段的真实样本；把物流正文展开到底，记录跨年、在途、多包裹和截断情况；明确抖音待发货订单是否在导入范围，如果在，调整“普通配送依据”的适用状态；完成两轮读取与重复检查。完成条件是：每条导入订单都有真实订单编号、下单时间和配送依据；没有官方结束标志时保持 PARTIAL；两轮读取不增加重复订单、来源和轨迹；完整物流历史到底有页面证据。缺少样本的项目继续保持未完成，不用构造数据补齐。

P4 的工作包括：完成京东完整分页、待发货和部分服务类型的真实验证；确认淘宝同步使用 MTOP 的 nextPageIndex，网页滚动分页作为独立能力记录；补齐已确认即时配送、充值、服务订单的字段依据；验证登录失效、安全验证和 HTTP 401/601 的账号状态。完成条件是：两轮结果与官方页面一致；类型规则有业务字段或详情支持；普通快递和待发货保留；存量清理只删除已确认编号，共享包裹、备注和归档保留。这个阶段依赖真实服务和相应订单样本，样本不足时保持对应项未完成。

P5 的工作包括：用真实共享来源、别名、非空历史备注和归档副本验证迁移与清理分支；验证被删除平台的来源清理、账号停用和移除期间的任务取消；用 dumpsys jobscheduler 核查 30 分钟后台更新的实际调度与约束；验证进度密文损坏、版本不兼容和重建入口。完成条件是：独立副本逐字段比较通过，业务提交与恢复标记一致，其他来源和用户字段保持；主库只读核查另有记录；调度与移除行为有系统侧证据。

P6 的工作包括：补齐取件码复制、未关联抖音订单卡片、外部平台导航、MainActivity 启动与自动同步的联合流程；按采集会话划分独立资料，比较修正后的程序规则与 Jev 辅助判断的正确性、总耗时、人工介入和费用。完成条件是：生产页面与数据库一致；交易操作全部拒绝；Jev 只在概率至少 0.95 且通过程序检查时采用；相同条件下的比较结果可以复核。没有同条件对照时，不声明整体提速或人工介入减少。

P7 的工作包括：生成并备份 release keystore，配置 release 签名，决定是否启用 R8；更新 versionCode、versionName、变更说明和 README 的已知限制；清理未使用代码和过期文档；完成最终 APK、签名、SHA-256 与安装指纹核查。完成条件是：release APK 可以覆盖安装并保留数据；四项构建检查和主机测试通过；README 与实际能力一致；仓库不含私人资料。这个阶段需要用户保管 keystore，丢失后无法用同一签名升级。

完整的阶段任务、输入、完成条件、验证命令与影响代价见 docs/development-plan.md [S5]。本文件保留审查依据与发现，开发计划文件作为执行入口。

## 验证与交付规则

每个阶段收尾执行四项检查：:app:assembleDebug、:app:assembleDebugAndroidTest、:app:testDebugUnitTest、:app:lintDebug；记录实际退出状态、执行数量、缓存数量和跳过数量。主机测试报告位于 app/build/reports/tests/testDebugUnitTest/，Lint 报告位于 app/build/reports/lint-results-debug.html。

设备测试使用直接 am instrument，避免 connectedDebugAndroidTest 结束后卸载目标应用；需要保留数据时覆盖安装，不改动账号、profile 和主库。破坏性验证使用独立数据库名称和独立副本，开始前保存一致副本，结束后逐字段比较八张业务表和 settings、encrypted_credentials、webview_profiles、work_schedules 四份配置。主库只读核查单独记录，不能由独立副本的结果代替。

APK 交付记录主 APK 与测试 APK 的 SHA-256、签名证书和安装指纹；源码、工具、测试报告、数据库副本和日志按阶段保存到 work/evidence/platform-logistics-v2/ 与 work/logs/platform-logistics-v2/。公开仓库只包含源码、测试、公开文档和主 APK；账号、订单、运单、地址、原始响应和采集正文保存在本机忽略目录。

Jev 继续使用 jev-1.14.0、单次十秒、每阶段最多一百次和相同输入缓存；页面自动操作保持至少 0.95 概率和当前窗口、节点、范围检查，交易操作禁止。每阶段记录请求数、缓存数、耗时、输入输出 tokens、费用估算和逐项复核结果；模型版本变化需要重新验证。整体提速或人工介入改善需要同条件比较，不能由小规模判断结果推断。

## 来源清单

编号对应正文引用。路径相对于仓库根目录；行号是审查时的当前行号。

[S1] README.md
[S2] docs/project-map.md
[S3] docs/architecture.md
[S4] docs/acceptance.md
[S5] docs/development-plan.md
[S6] docs/jev-workflow.md
[S7] docs/codegraph.md
[S8] AGENTS.md
[S9] work/tasks/platform-logistics-v2.md
[S10] work/tasks/platform-logistics-v2-plan.md
[S11] work/tasks/platform-logistics-v2-handoff.md
[S12] work/tasks/platform-logistics-v2-T13.md
[S13] work/tasks/platform-logistics-v2-T14.md
[S14] work/tasks/platform-logistics-v2-T15.md
[S15] work/tasks/project-pause-delivery.md
[S16] app/src/main/java/com/logisticsmanagement/app/data/LogisticsDatabase.kt
[S17] app/src/main/java/com/logisticsmanagement/app/data/LogisticsRepository.kt
[S18] app/src/main/java/com/logisticsmanagement/app/sync/SyncCoordinator.kt
[S19] app/src/main/java/com/logisticsmanagement/app/platform/BrowserAdapters.kt
[S20] app/src/main/java/com/logisticsmanagement/app/platform/PlatformWebView.kt
[S21] app/src/main/java/com/logisticsmanagement/app/platform/PlatformRegistry.kt
[S22] app/src/main/java/com/logisticsmanagement/app/platform/NativePaginationRunner.kt
[S23] app/src/main/java/com/logisticsmanagement/app/platform/NativeSyncProgress.kt
[S24] app/src/main/java/com/logisticsmanagement/app/platform/DouyinNativeOrders.kt
[S25] app/src/main/java/com/logisticsmanagement/app/platform/DouyinCaptureService.kt
[S26] app/src/main/java/com/logisticsmanagement/app/platform/DouyinCaptureActivity.kt
[S27] app/src/main/java/com/logisticsmanagement/app/ui/SettingsScreen.kt
[S28] app/src/main/java/com/logisticsmanagement/app/ui/ShipmentsScreen.kt
[S29] app/src/main/java/com/logisticsmanagement/app/data/CredentialStore.kt
[S30] app/src/main/java/com/logisticsmanagement/app/platform/TaobaoAdapter.kt
[S31] app/src/test/java/com/logisticsmanagement/app/ 下的 IdentityTest、PlatformTimeTest、ShipmentFilterTest、NativeNavigationEvidenceTest
[S32] app/src/androidTest/java/com/logisticsmanagement/app/ 下的 23 个测试类
[S33] build.gradle.kts、app/build.gradle.kts、gradle/wrapper/gradle-wrapper.properties
[S34] Git 提交 a05f752 与 5c049f1
[S35] 2026-11-23 执行的 Gradle 构建、主机测试与 Lint 输出；报告位于 app/build/reports/tests/testDebugUnitTest/ 与 app/build/reports/lint-results-debug.html
[S36] work/tasks/platform-logistics-v2-T10.md
[S37] work/tasks/platform-logistics-v2-T11.md
[S38] work/tasks/platform-logistics-v2-T12.md
