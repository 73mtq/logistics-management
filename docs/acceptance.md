# 验收记录

更新日期：2026-11-27，Asia/Shanghai。当前版本1.2.0、versionCode3、Room schema v2。2026-11-25 完成 S1 抖音物流自动导入闭环；2026-11-26 完成 S2 已导入订单物流读取的两轮验证，列表结束标志与未导入订单保持未完成；同日完成 S3 淘宝短信会话恢复与两轮订单物流读取；同日完成 S4 拼多多短信会话恢复与两轮订单物流读取（20 笔订单、24 条轨迹、查询队列 0，分页与标签计数通过，平台结束标志与原生页面对照未完成）；同日完成 S5 京东短信会话恢复与订单页迁移适配（列表单次读取 50 笔去重、36 笔排除、1 笔 90 天内订单写入生产库，物流 25 条轨迹，两轮 21.079 秒与 19.268 秒稳定，清除浏览数据后 NEEDS_LOGIN 与重新登录 READY 均通过）；2026-11-27 完成 S6 数据保留、后台调度与账号移除（京东主账号移除、凭据 6→4、profile 3→2、业务表 27/26/27/90 保持、后台任务 0→3→2→0、共享来源清理与移除审计通过；真实抖音账号移除与 WorkManager 实际执行保持未完成）；同日完成 S7 页面覆盖、版本与公开发布（真实库 26 件已签收、搜索与状态筛选、平台与账号筛选、京东详情 25 条轨迹与备注归档恢复、日历当天更新 3 件、设置 3 个账号卡片，页面数值与数据库一致；版本 1.2.0、versionCode 3，release 包 SHA-256 932f4b99…、证书 SHA-256 c53606e0…）；同日完成 S8 账号清理与后台任务执行（按账号删除 native-entry-evidence、临时京东账号已加载 profile 延迟删除与重启清理、两个 AccountSyncWorker 登记并实际执行；业务表 27/26/27/90 保持；真实抖音账号移除保持未完成）；同日完成 S9 采集与同步进行中的账号移除（真实抖音采集进行中移除、证据目录 3→0、进度删除、业务表保持；重新绑定抖音号 8587…0830 并完成一轮真实采集，5 个屏幕、287 个节点、PARTIAL；两个临时淘宝账号同步进行中移除，凭据键 6→4、pending_deletions 记录目标 profile、重启后 Profile 8 与 Profile 9 删除、业务记录保留；采集遍历让步修复四次输入 ANR，修复后未再出现；取消进行中网页同步的直接证据未取得）；此前阶段序列执行至T15，2026-11-23 完成 P0 基线回归；T06至T14的完整验收仍有未完成范围。每项结果对应当次账号、设备、输入、源码和安装包。

## 2026-11-25 S1 抖音物流自动导入闭环

固定流程：打开抖音（首页）→ 商城 → 我的订单 → 全部；已绑定账号跳过“我”页；按商品标题在订单列表定位已导入订单的卡片，点卡片“更多”，弹窗“查看物流”是独立窗口，按全部可读窗口查找并点击其范围；物流页点“查看更多物流信息”并逐条展开正文。列表不在顶部时在列表范围内注入向下滑动手势；未导入订单仍进入详情读取订单号。

两轮结果（设备 95f79212，应用 1.1.0、versionCode 2，调试签名 SHA-256 d8d0df96…）：

- 第 1 轮：33.826 秒，目标订单 6918…5327 存在且已签收，包裹 中通快递 7698…0533，轨迹 5 条，重复 0，账号 PARTIAL，结束原因“目标运单已保存，本轮结束”。
- 第 2 轮：113.575 秒，同一订单与包裹，轨迹仍为 5 条，重复 0，账号 PARTIAL。
- 数据库比较：TrackingEvent 1→5（新增 4 条真实轨迹），Shipment 1→1（仅 updatedAt 变化），SourceRef 1→1，orders 1→1，其他表与账号无变化。
- 动作证据：卡片“更多”、弹窗“查看物流”节点点击、查看更多物流信息、三条展开动作均为读取用途；交易按钮未被接受。
- 证据：work/evidence/platform-logistics-v2/S1/20261125-085401/（db-before、db-after-2c、summary-1s.json、summary-2c.json 等）；日志 work/logs/platform-logistics-v2/S1/round-1s-*.log、round-2c-*.log。

修正记录：当天早期尝试经过“我”账号页与订单详情入口，八分钟期限后仍停在列表；改为首页直达列表与卡片“更多”后，两轮在一分钟量级完成。目标订单当前物流页显示 中通快递 7698…0533；计划输入中的 韵达快递 3213…6018 样本来自另一页面状态，保留为本机解析测试资料。

未完成范围：列表完整滚动与“全部”标签选中状态未确认，两轮在目标保存后由测试结束采集；订单列表卡片没有订单号节点，列表直达只用于已导入订单，未导入订单仍需要进入详情；公开仓库移除了真实识别样本 app/src/test/resources/ocr-logistics.txt，样本保存在 work/evidence/platform-logistics-v2/S1/ocr-sample/，git 历史仍包含该文件。

验证：四项 Gradle 命令通过（assembleDebug、assembleDebugAndroidTest、testDebugUnitTest、lintDebug；Lint 0 errors、72 warnings）；主机测试 15 项通过、8 项跳过；两轮仪器测试通过；DouyinOcrLogisticsTest 在配置本机真实样本时通过。

## 2026-11-26 S2 抖音完整订单范围与物流历史

固定流程：打开抖音（首页）→ 商城 → 商城订单（“我的订单”入口，页面以 OCR 文本提供）→ 订单列表；订单卡片“更多”→“查看物流”，物流页读取运单、承运商、状态与轨迹。已导入订单按商品标题匹配后直接读取物流；未导入订单直接查看物流没有订单编号，本轮跳过并记录阻塞；不再逐单进入详情页，也不在进入列表时反复回顶部。

两轮结果（设备 95f79212，应用 1.1.0、versionCode 2，调试签名，APK SHA-256 d66f0815…）：

- 第 1 轮：42.058 秒，orders 3、Shipment 3、TrackingEvent 15，账号 PARTIAL；结束原因“已读取 7 个屏幕，最后一个屏幕有 300 个节点；连续 12 张卡片未匹配已导入订单，平台结束标志未出现，完整范围保持未确认”。
- 第 2 轮：45.085 秒，同样的订单、包裹与轨迹数量，账号 PARTIAL，原因相同。
- 数据库比较：两轮 orders、Shipment、SourceRef、TrackingEvent 表一致，result.json 记录 stableTables 四项。
- 阻塞项：全部标签没有 selected、checked、选中描述与选中指示节点；一个已导入订单卡片没有“更多”入口；列表未出现平台结束标志。
- 证据：work/evidence/platform-logistics-v2/S2/20261126-1055/（round-1、round-2、result.json、accessibility-before/after）；日志 work/logs/platform-logistics-v2/S2-20261126-1055.log。
- Pinky 现场复核：work/evidence/pinky/S2/20261126-0952-pinky-observations.md，记录标签无选中属性、列表可滚动、商城页“我的订单”为 OCR 文本。

修正记录：页面更新后“我的订单”入口没有无障碍节点，导航改为节点失败时按 OCR 文本点击；订单卡片按用户流程直接“更多”→“查看物流”；进入列表时移除反复回顶部循环，改用手势向下浏览。

未完成范围：列表结束标志、全部标签选中状态、未导入订单直接物流（列表卡片没有订单编号）、一个已导入订单卡片缺少“更多”入口；完整范围等待后续样本与页面结构确认。

验证：四项 Gradle 命令通过（assembleDebug、assembleDebugAndroidTest、testDebugUnitTest、lintDebug；Lint BUILD SUCCESSFUL）；主机测试 28 项、0 失败、12 跳过（DouyinRangeEvidenceTest 4 项因未配置 S2_EVIDENCE_DIR 跳过）；两轮仪器测试通过（Time 107.711、OK (1 test)）。

## 2026-11-26 S3 淘宝会话恢复与订单物流读取

固定流程：应用内淘宝登录页 →“使用其他账号登录”→“短信验证码登录”→ 选择账号绑定号码 → 获取验证码 → 通知监听捕获并填入 → 提交登录。登录成功后由 SyncCoordinator 完成 MTOP 订单分页与 SSR 物流读取。

两轮结果（设备 95f79212，应用 1.1.0、versionCode 2，APK SHA-256 9c1f4c69…）：

- 第 1 轮：淘宝订单 3、来源 3、包裹 2、轨迹 26；合并后生产库 orders 6、Shipment 5、TrackingEvent 41、TrackingQueue 0，账号 READY。
- 第 2 轮：同样的订单、包裹与轨迹数量，无新增记录。
- 包裹：申通快递 7734…2341 与 7734…1058，状态已签收，轨迹 26 条。
- 失败场景：删除浏览数据后同步得到 NEEDS_LOGIN；T07 的 FAIL_SYS_ILLEGAL_ACCESS 真实响应回放得到 NEEDS_VERIFICATION；安全验证文本识别由 TaobaoLoginProbeTest 5 项通过。
- 证据：work/evidence/platform-logistics-v2/S3/20261126-session/（db-before、db-after-round2、login-steps.json、result.json）；登录步骤 7834 字节，SHA-256 97f82db5…。
- 验证：四项 Gradle 命令通过（使用本机 JDK 17，Java 24 下 testDebugUnitTest 无法创建任务）。

未完成范围：淘宝网页滚动分页与部分服务详情；通知监听受 HyperOS 自启动管理限制，应用进程重启后需要重新绑定；原生页面读取没有启用。

## 2026-11-26 S4 拼多多会话恢复与订单物流读取

固定流程：应用内拼多多登录页 →「手机登录」→ 勾选「同意 服务协议与隐私政策」→ 填入本机号码 132****2972 → 发送短信验证码 → 通知监听捕获并填入 → 提交登录。登录接口返回 uid 与 access_token，应用把访问令牌写入该账号 profile 的 Cookie（PDDAccessToken、pdduid、pdd_user_id、pdd_user_uin），会话检测通过后账号为 READY。同步读取订单页，逐个标签核对 type 与 page，写入订单、来源并按查询队列读取物流。

两轮结果（设备 95f79212，应用 1.1.0、versionCode 2，调试签名，APK SHA-256 42e5fbbb…）：

- 第 1 轮：189.007 秒，orders 20、SourceRef 20、Shipment 20、TrackingEvent 23，newOrders 15、newSources 15，查询队列 1，账号 PARTIAL。
- 第 2 轮：91.226 秒，orders 20、SourceRef 20、Shipment 20、TrackingEvent 24，newOrders 0、newSources 0，查询队列 0，账号 PARTIAL。
- 最终确认同步：85.861 秒，orders 20、sources 20、events 24、查询队列 0；PddPages 没有分页异常。

分页记录（PddPages）：全部 type=all，pages [1]、counts [10]、loads 4、distinct 40、endedBy no-progress；待收货 unreceived 2；待发货 unshipping 0；待付款 unpaidV2 0；评价 unrated 1；待分享 pending_share 不存在。H5 订单列表以重复请求第 1 页的方式增量加载，请求体 page 保持 1；平台没有结束标志，完整范围保持未确认。

物流：19 个来源一次同步成功，1 个订单的物流页承运商为「邮政快递包裹」，旧正则只接受以快递、速递、速运、物流结尾的承运商而失败。按真实页面调整正则后 PddTrackingEvidenceTest#queryRealOrder 通过，1 条轨迹、状态 SIGNED，查询队列清空。

失败场景：清除拼多多 profile 浏览数据后同步得到 NEEDS_LOGIN；登录页安全验证文本由 PddLoginProbeTest 6 项覆盖；没有取得拼多多业务错误码样本，未做错误码回放。

页面对照：H5 登录后拼多多 App 提示「在其他设备上通过短信验证码进行了登录，当前设备已退出登录」，原生订单与物流页无法继续读取。登录前 Pinky 预检记录待收货 2、评价 1，与 H5 标签计数一致；原生页面计数对照与原生物流对照记为未完成。

证据：work/evidence/platform-logistics-v2/S4/20261126-1545/（db-before、db-after-round1、db-after-round2、db-final、db-final3、device-files、tracking-failed-order.json、preflight.txt、apk-after.sha256）；Pinky 记录 work/evidence/pinky/S4/（预检报告、观察记录、页面核对记录）。登录步骤 18 条保存在设备并通过 exportLogin 导出，手机号掩码、验证码不入文件。

验证：四项 Gradle 命令通过（assembleDebug、assembleDebugAndroidTest、testDebugUnitTest、lintDebug）；主机测试 39 项、0 失败、12 跳过；装置测试 PddPaginationEvidenceTest 1 项、PddSessionEvidenceTest 4 项、PddTrackingEvidenceTest#queryRealOrder 1 项通过；Pinky 任务与 instrument 串行执行。

未完成范围：平台结束标志、待分享标签、两条订单的状态提示文本未命中现有映射、原生 App 页面计数与原生物流对照、拼多多单设备登录策略对原生 App 的影响。

## 2026-11-26 S5 京东会话恢复与订单物流读取

固定流程：应用设置 → 添加京东账号（名称含绑定号码）→ 创建并登录 → 应用内京东登录页 → 协议勾选 → 填入本机号码 → 获取验证码 → 完成拼图安全验证 → 通知监听填入短信验证码 → 提交“同意协议并登录”。订单页迁移后，订单读取使用 o.m.jd.com 的 newUserAllOrderList 响应，物流读取使用 orderTrackBusiness 响应与 o.m.jd.com/track 页面。

两轮结果（设备 95f79212，应用 1.1.0、versionCode 2，调试签名，主 APK SHA-256 64de204d…；最终包 4dd7f94c…）：

- 会话：账号「京东 132****2972」，清除浏览数据前 READY，profile 含 pt_key 与 pt_pin；清除浏览数据后同步得到 NEEDS_LOGIN 且账号状态为 NEEDS_LOGIN，重新登录后 READY，lastSyncedAt 为 18:14。
- 第 1 轮：21.079 秒，READY，orders 1、sources 1、shipments 1、events 25、去重 events 25、pending 0，newOrders 0（唯一订单在订单页迁移后的首次尝试中已写入）。
- 第 2 轮：19.268 秒，同样的订单、来源、包裹与轨迹数量，newOrders 0、newSources 0。
- 最终包复核：安装密码页判定顺序修正后的构建（主 APK SHA-256 4dd7f94c…）再执行一轮，23.157 秒，READY，orders 1、sources 1、events 25、pending 0，计数不变。
- 数据库比较：两轮 orders、SourceRef、Shipment、TrackingEvent 稳定；查询队列 0；integrity_check ok。
- 订单列表：单次读取 6 条 newUserAllOrderList 响应，依次 10、10、10、10、10、0 笔，hasNext 前五次为真、末次为假；去重 50 笔；结束原因为 hasNext 为假与末页空响应。
- 类型过滤：解析保留 12 笔（其中 90 天内 1 笔，写入生产库），排除 36 笔（orderType 22 且 isStoreOrder 或 orderTypeFlag=2 共 33 笔；34/37 且 isVirtualOrder 共 2 笔；75 共 1 笔），另关闭订单 2 笔；待发货与待收货现场样本为 0。
- 物流：京东快递，运单尾号 2187，状态已签收，25 条轨迹；轨迹正文来自 orderTrackBusiness 的 orderMessageList 与 carrierInfos。
- 失败场景：清除浏览数据后同步得到 NEEDS_LOGIN；T09 的 601 响应回放得到 SERVICE_ERROR 与 FAILED；302 与登录页安全验证没有现场样本，未回放。
- 页面核对：Pinky 打开京东 App「我的」页记录订单入口且无数字角标；打开「全部」页触发京东自身「京东验证」，原生计数对照未完成。应用包裹页显示该京东订单的京东快递尾号与已签收文本。
- 证据：work/evidence/platform-logistics-v2/S5/20261126-1705/（before、after-login、after-round1-attempt、after-round1、after-round2、before-session-removal、after-session-removed、after-relogin、after-final-round、round1.json、round2.json、round2-final.json、s5-pages-pages.json、s5-pages-types.json、login-steps.json、relogin-steps.json、relogin-summary.json）；Pinky 记录 work/evidence/pinky/S5/。
- 验证：四项 Gradle 命令通过（assembleDebug、assembleDebugAndroidTest、testDebugUnitTest、lintDebug；两次执行 2 分 5 秒与 2 分 39 秒），Lint 0 errors、75 warnings、2 hints；主机测试 42 项、0 失败、12 跳过；设备测试 JdSessionEvidenceTest#exportLogin（两次）、#roundOne、#roundTwo、#syncWithoutSessionReportsLogin，JdOrderEvidenceTest#recordRealOrders、#replayRealOrders、#replayRealFailure，以及诊断方法 recordRealListPage、recordUrl。

修正记录：京东订单页从 trade.m.jd.com/order/orderlist_jdm.shtml 跳转 o.m.jd.com/list，旧 order_list_m 接口不再由页面调用；新增 newUserAllOrderList 解析、o.m.jd.com/track 与 orderTrackBusiness 物流解析、hasNext 结束条件与页面内部滚动脚本。首次同步尝试未完成分页，已取得订单已经保存，随后修正并重跑。

未完成范围：待发货与待收货现场样本、302 与安全验证现场样本、原生京东 App 订单计数对照、页面没有文本结束标志（以 hasNext 为结束依据）、多包裹与跨年物流样本。

提交与 CI：commit c9e33a2 推送 main，Android CI 通过（run 45801666241）。

## 2026-11-27 S6 数据保留、后台调度与账号移除

固定流程：应用启动并完成前台自动更新 → 设置页开启“每30分钟后台更新” → 移除京东主账号（确认对话框再次点击“移除账号”）→ 包裹页按已移除账号筛选 → 详情页核对来源按钮 → 关闭后台更新。手机界面操作由 Pinky 完成；构建、安装、instrument、数据库副本与 dumpsys 使用 adb。

结果（设备 95f79212，应用 1.1.0、versionCode 2，主 APK SHA-256 1be796a8…，测试 APK SHA-256 551c59cf…）：

- 账号：京东主账号由 READY 变为 REMOVED，enabled=0、deleted=1；其他账号与其余字段保持。
- 清理：encrypted_credentials 的凭据键由 6 个变为 4 个，目标账号的 `jd.login` 与 `captures` 消失；app_webview 的 Profile 目录由 3 个变为 2 个；移除时 profile 未在当前进程加载，删除立即完成，`pending_deletions` 为空；`files/native-sync-progress` 只有抖音进度文件，目标账号没有进度文件。
- 业务数据：orders 27、Shipment 26、SourceRef 27、TrackingEvent 90、ShipmentAlias 1、TrackingQueue 0、PendingProfileCleanup 0 全部保持；详情页来源按钮显示“来源账号已经移除”且不可用。
- 调度：关闭时 0 个本应用 `AccountSyncWorker` 任务；开启后 3 个，每项 `EXTRA_IS_PERIODIC=true`、`Minimum latency +29m59s991ms`、`Required constraints TIMING_DELAY CONNECTIVITY FLEXIBILITY`；移除京东后 2 个；关闭后 0 个。
- 副本审计：before 与 after-remove、after-start 的八张业务表逐字段比较，除目标账号的 enabled/deleted/state、目标账号查询项与凭据键、profile 目录数量外没有差异；`integrity_check` ok、`user_version` 2。
- 共享来源清理：独立副本上两笔真实淘宝订单形成的共享包裹（2 个来源、1 条别名）设置备注与归档后排除第二笔订单，保留包裹、另一来源、备注、归档、别名与 13 条轨迹；单来源京东包裹连同 25 条轨迹删除；Shipment 26→25、orders 27→25、SourceRef 27→25，孤立查询项与别名为 0。
- 移除审计：S6RemovalAuditTest 将生产库与移除前副本逐表比较，queueRowsForAccount 0、credentialKeysForAccount 0、pendingProfiles 0、profileDeleted true；原生进度删除测试用真实进度文件副本验证主文件与备份删除、生产文件保持。
- 证据：work/evidence/platform-logistics-v2/S6/20261127-0930/（before、after-remove、after-start、scheduling、instrument、dryrun）；Pinky 记录 work/evidence/pinky/S6/20261127-0930/（observations.md、report/）；日志 work/logs/platform-logistics-v2/S6-*.log。
- 验证：四项 Gradle 命令通过（最终 33 秒，83 项任务，3 项执行、80 项保持）；主机测试 42 项、12 跳过、0 失败；Lint 0 errors、75 warnings、2 hints；设备测试 S6SharedSourceCleanupTest 1 项、S6RemovalAuditTest 2 项通过；Pinky 任务与 instrument 串行执行。

提交与 CI：commit b03234e 推送 main，Android CI 通过（run 45923754912，2 分 55 秒）。

未完成范围：真实抖音账号移除（原生进度删除由真实文件副本覆盖）；按账号删除 `native-entry-evidence` 目录；WorkManager 实际执行结果；京东账号重新登录；更新进行中移除账号；新的平台第二次导入；历史非空备注与归档样本；已加载 profile 的延迟删除分支（本次为未加载分支，删除立即完成）。

## 2026-11-27 S7 页面覆盖、版本与公开发布

固定流程：adb 采集预检副本与安装包指纹 → 版本升到 1.2.0、versionCode 3 → 四项 Gradle 命令 → 覆盖安装 debug 包 → Pinky 启动应用并逐页核对 → 恢复备注与归档 → 采集后副本比较 → 构建 release 包并核对签名与指纹 → 更新文档并发布。手机界面操作由 Pinky 完成；构建、安装、数据库副本与系统设置使用 adb，两类操作串行执行。

构建与测试（设备 95f79212，Android 17 / API 37）：

- 四项 Gradle 命令退出 0：首次 1 分 43 秒，83 项任务，15 项执行、68 项保持；主机测试重跑 6 秒，Lint 重跑 1 秒。
- 主机测试：11 个测试类、42 项、0 失败、0 错误、12 跳过。
- Lint：0 errors、75 warnings、2 hints。
- 覆盖安装：versionCode 2 → 3、versionName 1.1.0 → 1.2.0，签名证书仍为调试证书 7957a084…；安装前后八张业务表、settings、凭据键哈希、webview 待删除项、work_schedules、profile 目录、原生进度与证据目录、无障碍开关逐项一致。
- 安装包：debug APK SHA-256 6cd9c843…、测试 APK SHA-256 551c59cf…（与 S6 相同，测试源码未改）；release APK SHA-256 932f4b99…、58788698 字节、清单 versionCode 3、versionName 1.2.0、无 debuggable 标记、证书 SHA-256 c53606e0…。

Pinky 现场结果（任务报告与录像保存在本机）：

- 启动后前台自动更新执行，提示约 1 秒后消失；淘宝号132****2972 同步成功（新增 0、更新 0、排除 12；物流成功 0、失败 0、待查询 0；最近成功更新 10:16），拼多多 132****2972 保持需要登录并显示“重新登录”。
- 包裹页：统计“待取件 0 件 · 在途 0 件”；分组“已签收 · 26 件”；搜索“垃圾袋”得到 1 张卡片、搜索运单尾号 2187 得到 1 张卡片；“在途”显示“当前条件下没有包裹”与“清除筛选”，“已签收”26 件，“全部”恢复；平台与账号筛选选择“京东 · 京东 132****2972 · 已移除”后 1 张卡片，清除筛选恢复 26 件；搜索“S7-无匹配”显示“没有找到包含「S7-无匹配」的包裹”，清除后恢复。
- 京东包裹详情：京东快递、尾号 2187、预计送达“平台暂未提供预计送达日期”、最近更新 2026年11月27日 09:27、来源“京东 · 京东 132****2972”、按钮“来源账号已经移除”不可用；点“复制运单号”出现“运单号已复制”；逐屏读取 25 条轨迹，首条 2026年09月07日 16:23、末条 2026年09月05日 13:18，与数据库一致；备注“S7 page coverage 2026-11-27”保存后重进保持，随后清空并保存；归档后分组 25 件、已归档筛选 1 件，恢复后回到 26 件。
- 日历：2026年11月、11月27日预计送达 0 件、当天物流更新 3 件（两张淘宝 10:16、一张京东 09:27）；“下一周”显示 2026年12月 与 12月4日 0 件；“返回今天”恢复；日期选择器当前选择 2026年11月27日，点“选择日期”后回到日历。
- 设置：3 个账号卡片（抖音物流采集 部分包裹等待更新、拼多多 132****2972 需要登录、淘宝号132****2972 可以更新）；抖音卡片显示“无障碍服务未启用”与按钮“采集商城订单”；添加账号包含京东、淘宝／菜鸟、拼多多、抖音；Jev 开关关闭且因存在在用账号不可用，“记录完整采集诊断资料”开启；“每30分钟后台更新”关闭。
- 未出现项：取件码行与“复制”按钮、未关联抖音订单卡片、“待发货订单”分组；与执行前数据库计数（取件码 0、待取件 0、未关联抖音订单 0、待发货订单 0）一致，保持 PARTIAL。

副本比较（覆盖安装后 → 页面覆盖后）：

| 检查项 | 结果 |
| --- | --- |
| orders、SourceRef、TrackingEvent、ShipmentAlias、TrackingQueue、PendingProfileCleanup | 一致（27 / 27 / 90 / 1 / 0 / 0） |
| Shipment | 26 行；目标包裹 notes 为空、archived 为 0；两个淘宝包裹 updatedAt 变为 2026-11-27 10:16 |
| Account | 集合一致；拼多多错误文本与 lastAttemptAt 更新；淘宝 NEEDS_VERIFICATION → READY、error 清空、lastSyncedAt 更新 |
| settings、webview 待删除项、work_schedules | 一致 |
| 凭据 | 键集合一致（4 个）；淘宝 captures 重新加密写入 |
| profile、原生进度、证据目录、无障碍 | 一致（Profile 2、Profile 4；抖音进度 1 个；证据目录 3 项） |

证据：work/evidence/platform-logistics-v2/S7/20261127-1012/（preflight、install-after、post、page-expectations.json、install-compare.json、compare.json、apk/）；Pinky 记录 work/evidence/pinky/S7/20261127-1012/（observations.md、report/）；日志 work/logs/platform-logistics-v2/S7-build-20261127-1012.log、S7-release-20261127-1012.log。

未完成范围：取件码复制（没有待取件包裹与取件码）、未关联抖音订单卡片与待发货订单卡片（没有对应真实订单）、release 包未在设备安装（签名切换需要卸载并清除账号、profile 与本机证据）；S6 遗留项继续保留。

验证：四项 Gradle 命令；真实设备页面测试（Pinky）；apksigner 与 aapt2 校验；覆盖安装；SHA-256；git 状态。

提交与 CI：commit 83417b5 推送 main，Android CI 通过（run 45929726302，2 分 59 秒）。发布页 v1.2.0-dev.20261127 提供 app-release.apk（58788698 字节，发布页 digest 与本地 SHA-256 一致）、SHA256SUMS.txt 与 SIGNATURE.txt。

公开文件核查：文档与发布说明中的真实订单号、运单号与完整手机号改为掩码形式；测试与脚本中的目标订单编号改为运行参数、环境变量或占位号码；重新执行构建、主机测试与 Lint 通过。`git grep` 扫描 11 位手机号与 12 位以上数字，只剩淘宝插件的公开应用编号 2021001185671035 与测试占位号码 13800000000；姓名、地址与抖音号扫描无匹配。清理提交 b389881 推送 main，Android CI 通过（run 45931101703，2 分 49 秒）。

## 2026-11-27 S8 账号清理与后台任务执行

固定流程：adb 采集安装前基线 → 覆盖安装调试包与测试包 → Pinky 创建临时京东账号并加载 profile → 不停止应用保存移除前副本 → Pinky 移除账号 → 保存移除后副本 → 停止并重启应用 → 保存重启后副本 → 运行证据副本清理测试 → 开启后台更新并等待系统周期执行 → 关闭后台更新 → 副本比较、四项构建检查与文档更新。

结果（设备 95f79212，应用 1.2.0、versionCode 3，主 APK SHA-256 edd5b092…，测试 APK SHA-256 4b1987b0…）：

- 安装：安装前主 APK SHA-256 6cd9c843…；覆盖安装后 SHA-256 edd5b092…，versionCode 3、versionName 1.2.0 保持；安装后活动账号仍为抖音、拼多多与淘宝。
- 构建：四项 Gradle 命令退出 0；最终复核 13 秒，83 项任务，3 项执行、80 项保持；主机测试报告 11 个测试类、42 项、0 失败、0 错误、12 跳过；Lint 0 errors、75 warnings、2 hints。
- 临时账号：设置 → 添加京东账号 → 名称“S8 清理验证” → 创建并登录；京东登录页加载后返回设置，未登录、未写入凭据。账号 ID 2765…，profile 为 account_2765…。
- 移除前：pending_deletions 为空，profile 目录为 Profile 2、Profile 4、Profile 7；抖音三轮 S2 证据目录保持；业务表 orders 27、Shipment 26、SourceRef 27、TrackingEvent 90。
- 移除后：目标账号 deleted=1、enabled=0、state=REMOVED；pending_deletions 只含目标 profile；profile 目录仍为三项；目标凭据键 0、目标原生进度不存在；八张业务表与抖音证据目录保持；其他账号与凭据保持。
- 重启后：pending_deletions 为空；profile 目录剩 Profile 2、Profile 4；目标账号仍为 REMOVED；八张业务表保持。
- 证据副本：测试复制一轮真实 S2 证据目录，构造旧格式、目标标记、其他账号标记与无标记四种目录。清理返回删除 2 个目录；旧格式与目标标记删除，其他账号与无标记保留；生产三轮证据目录不变。
- 后台更新：开启后 dumpsys jobscheduler 记录两个 AccountSyncWorker（系统编号 26、27，联网约束，最早执行 +29m59s）。周期到点前用系统命令强制执行时，WorkManager 以“before schedule”推迟并重新登记，未执行账号同步；15:29:37 周期到点后两个任务实际启动，拼多多 Worker 返回 FAILURE，淘宝 Worker 在 15:29:39 返回 SUCCESS。两个活动账号的 lastAttemptAt 更新为 15:29:37，淘宝 lastSyncedAt 为 15:29:39、状态 READY、错误为空。关闭“每30分钟后台更新”后任务数为 0。S8BackgroundExecutionTest 记录 runAttemptCount 0→0，lastAttemptAt 由 14:53 更新到 15:29，测试通过；WorkManager 周期任务成功后重置 runAttemptCount，实际执行以系统历史与 lastAttemptAt 为准。
- 未完成：真实抖音账号移除（当前只有一个绑定，重新绑定会形成第二个账号并可能重复导入）；更新进行中移除账号；缺少现场样本的取件码、未关联抖音订单卡片与待发货订单卡片。
- 最终状态：Account 8 行（活动 3：拼多多、抖音、淘宝；已移除 5，其中包含临时京东账号）、orders 27、Shipment 26、SourceRef 27、TrackingEvent 90、TrackingQueue 0、PendingProfileCleanup 0；pending_deletions 为空，profile 目录 Profile 2 与 Profile 4，原生进度 1 个，抖音证据目录 3 个。
- 证据：work/evidence/platform-logistics-v2/S8/20261127-1500/（before-install、before-removal、after-removal、after-start、after-force、after-periodic、final、final2、native-evidence、background、workmanager）；Pinky 记录 work/evidence/pinky/S8/20261127-1500/。
- 验证：四项 Gradle 命令；S8NativeEvidenceCleanupTest 1 项通过；S8ProfileCleanupTest 2 项通过；S8BackgroundExecutionTest 1 项通过；s8_audit.py compare 返回 ok=true；Pinky 与仪器测试串行执行。
- 提交与 CI：commit 9c00c32 推送 main，Android CI 通过（run 45959783473，3 分 30 秒）。

## 2026-11-27 S9 采集与同步进行中的账号移除

固定流程：adb 采集 before 快照并归档三份 S2 设备证据目录 → S9 代码修复与四项构建 → 覆盖安装 → 抖音采集进行中移除 → 重启核对 → 重新绑定并完成一轮采集 → 两个临时淘宝账号同步进行中移除 → 重启核对 → 页面复核与最终快照。手机界面操作由 Pinky 完成；构建、安装、instrument、数据库副本与系统设置使用 adb，两类操作串行执行。

结果（设备 95f79212，应用 1.2.0、versionCode 3，主 APK SHA-256 3e741432…，测试 APK SHA-256 fcf447bd…）：

- 构建：四项 Gradle 命令退出 0，最终 33 秒、83 项任务（11 项执行、72 项保持）；主机测试 42 项、0 失败、12 跳过；Lint 0 errors、77 warnings、2 hints（新增 2 条 RequiresFeature 警告来自 profile 存在性判断）。构建使用 JDK 17；默认 shell 的 Java 24 运行 Gradle 8.13 时配置 testDebugUnitTest 报 “Type T not present”。
- 安装：覆盖安装前 APK SHA-256 edd5b092…，安装后 232940d2…（首个修复包）与 3e741432…（遍历让步修复包）；versionCode 3、versionName 1.2.0；安装前后八张业务表、settings、凭据键、profile 目录、证据目录与进度一致。
- 采集遍历 ANR：修复前 16:39:21、16:41:53、16:46:39、16:48:50 四次 DouyinCaptureActivity 输入 ANR，主线程在 AccessibilityInteractionClient.getChild 上等待约 5.5 秒，节点递归 20 层以上；改为每 25 个节点与每个窗口 delay(1) 让步后，16:52 至 17:13 未再出现 ANR，重新绑定采集读取 15 次扫描、265 个证据文件、12.7 MB。
- 抖音移除：采集进行中（进度文件与新证据目录已建立）在设置页移除账号。移除后目标账号 deleted=1、enabled=0、state=REMOVED；native-entry-evidence 3→0；native-sync-progress 删除；凭据键 4→4（抖音没有凭据键）；pending_deletions 为空；八张业务表 27/26/27/90/0/1/0 保持；重启后状态仍为 REMOVED、业务表保持、其他账号证据与进度不变。移除窗口 logcat 无 FATAL EXCEPTION、FileNotFoundException、IllegalStateException。
- 抖音重新绑定：添加“抖音 主账号”，绑定抖音号 8587…0830，运行到商城订单列表，读取 5 个屏幕、最后屏幕 287 个节点，平台未提供结束标志，状态 PARTIAL，orders 仍为 27、Shipment 26；旧证据目录与旧进度不存在，新进度 1 个；采集结束后采集页显示“采集进度与当前绑定账号不一致”，未重建。
- 淘宝临时账号一：登录后状态 NEEDS_VERIFICATION，同步进行中（卡片显示“正在更新”“正在查询物流，已更新 1 个，待查询 2 个”）移除；凭据键 6→4，pending_deletions 记录目标 profile，查询项 0，业务表 orders 27→30、SourceRef 27→30、ShipmentAlias 1→4、Shipment 26 保持；重启后 Profile 8 删除、pending 清空、业务表保持。
- 淘宝临时账号二：登录使用本机号码自动完成，移除前卡片显示“正在更新”，移除后凭据键删除、pending 记录、Profile 9 在重启后删除；同步在该次移除前完成（约 0.7 秒），未取得取消进行中网页同步的直接证据。
- 页面：包裹页“已签收 · 26 件”，淘宝包裹卡片与详情同时显示“淘宝号132****2972”与“S9 淘宝临时”，后者为“来源账号已经移除”禁用按钮；日历当天物流更新 3 件（17:08）；设置页 3 个活动账号卡片（抖音 部分包裹等待更新、拼多多 需要登录、淘宝 可以更新）。
- 设备测试：S9EndWriteGuardTest 1 项、S9CleanupConvergenceTest 1 项、S9RemovalAuditTest 2 项通过；审计 compare 与最终快照一致（业务表 30/26/30/90/0/4/0）。
- 环境：覆盖安装会移除应用的无障碍启用项，安装后需要重新写入 enabled_accessibility_services；阶段结束时已恢复为 hello.litiaotiao.app/.LttService:app.pinky.companion/.PinkyAccessibilityService。
- 证据：work/evidence/platform-logistics-v2/S9/20261127-1615/（before、after-install、after-tests、douyin-after-removal、douyin-after-start、douyin-after-rebind、taobao-before、taobao-after-removal、taobao-after-start、taobao2-before、taobao2-after-removal、taobao2-after-start、final、audit、device-evidence）；Pinky 记录 work/evidence/pinky/S9/20261127-1615/（淘宝阶段报告与 observations.md）。
- 提交与 CI：commit e5ad9f2 推送 main，Android CI 通过（run 45973267452，3 分 41 秒）。

未完成范围：取消进行中网页同步的直接证据（两次淘宝移除以界面“正在更新”为起点，实际删除时同步已完成）；临时淘宝账号使用本机号码登录，淘宝侧可能新建账号，需要用户自行确认；取件码复制、未关联抖音订单卡片与待发货订单卡片缺少真实样本；京东与拼多多重新登录；京东原生订单计数与安全验证样本；后台更新进行中移除；抖音完整列表结束与全部标签选中状态。

## 已验证的阶段范围

| 阶段 | 已取得的真实依据 | 保留的条件 |
| --- | --- | --- |
| T00 | 保存执行方案、源码、APK及真实数据库一致副本 | 基线保存不代表平台业务通过 |
| T01 | 淘宝快速进入的实际跳转、profile、Cookie键和业务响应记录 | 仅取证范围 |
| T02 | 淘宝真实登录跳转、账号状态及已有数据保留 | 全部平台会话场景分别验收 |
| T03 | 淘宝真实订单、普通快递轨迹及重复导入 | 多包裹及全部历史范围仍需专门样本 |
| T04 | 拼多多真实列表、分页type/page、重复导入与官方页面比较 | 全部账号范围不由单账号样本推定 |
| T05 | 六个真实物流样本两轮共12项，登录失效、明确服务失败及订单关联 | 早期一次NETWORK缺少完整独立资料，原因未确认 |
| T06 | 京东已确认业务字段组合过滤、真实响应回放及存量副本清理 | 完整分页和部分类型未完成；六个拼多多订单createdAt变化缺少对应原始列表依据 |
| T07 | 淘宝确认即时配送类型、详情编号关联、接口分页及重复清理 | 网页滚动分页未通过，其他服务详情和部分配送子类型缺少样本；早期京东清理的后台执行依据仍未确认 |
| T08 | 真实拼多多已收货传播、两轮同步、较早资料保留及页面显示 | 实际在途转签收、异常时间比较及共享包裹等缺少样本，跨暂停全字段保持未通过 |
| T09 | 取消、执行期限、进程恢复、三平台真实回放及拼多多两轮恢复 | WorkManager实际调度、新增订单中断及部分当前失败场景未验证 |
| T10 | 官方App账号、订单与物流节点读取、重复采集、结束及期限释放 | 运输中样本、当时轨迹年份和整个阶段跨暂停业务保持未完成 |
| T11 | 真实抖音号绑定、展开完整下单时间、近期已签收订单导入及重复采集 | 待发货、在途、服务类型、账号不匹配和部分生命周期样本未验证 |
| T12 | 同一订单真实运单、承运商及五条轨迹关联，两轮保存及唯一区间年份核查 | 完整物流历史到底、跨年、在途、多包裹、登录及安全验证等缺少样本 |
| T13 | Jev辅助导航、三笔真实编号、停止继续、两种进程终止恢复及实际八分钟期限 | 全部标签选中状态、平台明确结束、完整遍历及两笔候选字段未确认；自动重连和整体效果对照未完成 |
| T14 | 真实schema1迁移、schema2重新打开、两轮生产页面保存及主库保持 | 历史共享来源、别名及非空用户字段等缺少样本；跨暂停无障碍配置变化未归因 |

本机 `work/evidence/platform-logistics-v2/T15/evidence-index.json` 关联T00至T14状态记录、可用核查报告及SHA-256。完整订单、运单和采集正文保存在本机忽略目录。早期原始文档也保存在T15开始副本中。

## 抖音真实设备证据

T11 已通过App服务取得真实抖音号、订单编号、展开后的含年份下单时间及普通配送依据，保存一个符合九十天条件的已签收订单。最终两轮实际1项309.914秒通过，生产组件卡片和筛选另有真实断言。

T12 将该订单关联一个真实包裹和五条物流轨迹。月日时分依据下单时间至扫描时间的唯一区间确定年份，保存原文、精度及依据。最终同进程两轮、主动结束、正常触摸恢复和五秒期限实际1项144.508秒通过，另有独立数据库回放及生产组件验证。

T13 最终两轮实际1项239.333秒通过，核查三笔真实编号，其中两笔缺少日期或配送依据，没有导入。二十三次接受动作均为读取用途；四次Jev成功请求、三次缓存复用，中位请求417毫秒，8837输入tokens，成功响应费用估算0.000371154美元。该范围没有同条件完整流程比较。

BUSINESS_COMMITTED与PROGRESS_SAVED两种外部终止和重启核查通过，verifyRestart分别0.123秒、0.101秒；没有自动启动原生采集。八分钟生产默认期限实际480.211秒通过，到期PARTIAL并释放任务及触摸请求。跨安装、跨测试进程无障碍自动重连保持未解决。

## T14 数据与页面验收

T14-final-device-20261016-02通过直接am instrument执行，3项通过，49.587秒，没有跳过。真实schema1迁移及重新打开后，Account由7变为6，PendingProfileCleanup由0变为1，34个订单、包裹、来源及2条轨迹保持。该副本没有被删除平台的实际业务来源，不能证明对应业务删除分支。

真实schema2连续打开两次，八张表保持，关系和重复检查通过。两轮生产页面验证包裹、日历、设置、详情、返回、四个平台与账号筛选、状态、搜索、空结果及页面重建。承运商、运单、逐条时间和正文、来源入口参数、账号状态、成功时间及错误与数据库一致。

备注、归档及恢复仅修改独立副本。两轮结束仅同一目标包裹notes与原副本不同，归档恢复原值；关闭并重新打开数据库后保存结果保持。已关联抖音订单只显示一个包裹卡片。运单复制通过实际剪贴板检查；明暗主题及1.0、1.5字体共4种组合、10次键盘和系统栏范围及可访问性检查通过。

手机主库before、resume-before及after一致副本完整性ok：Account6、orders47、Shipment47、SourceRef47、TrackingEvent110、TrackingQueue4、ShipmentAlias0、PendingProfileCleanup0。八张表全部字段及四份应用配置保持。整个阶段无障碍启用列表保持未通过，恢复前已缺少抖音采集服务，原因未归因；恢复后的列表保持通过。没有修改系统设置或请求重新开启，结束时触摸模式false。

T14四项Gradle检查通过；主机IdentityTest2项、PlatformTimeTest5项通过，NativeNavigationEvidenceTest7项因未配置真实T13目录跳过。95份源码、两个APK、签名、SHA-256和安装指纹核查通过。Models.kt、PlatformAdapter.kt及schema2与T13归档相同。

## T15 Jev辅助文档核查

使用已有真实版本说明、导航、平台枚举、成功测试、失败测试、主动中断、跳过测试、缺少样本及跨阶段记录进行九项四分类核查。模型输入只含公共说明和经过处理的证据；完整来源位置及映射保存在本机。App源码保持，业务资料只用于已有副本核查。

完成上下文核查后的九项判断中，七项与资料复核结果一致；达到0.95概率的五项均一致。页面说明及主动中断两项需要重新核查，最终文档按源码和原始记录确定。该小规模资料回放不构成独立通用准确率证明；无法确定类别缺少专门样本，整体耗时改善没有同条件对照。

实际三次成功请求、一次缓存复用。三次输入共9687tokens，按当日官方每百万输入tokens0.042美元估算费用0.000406854美元，输出免费；缓存不新增请求。最终资料请求耗时2000毫秒，输入3613tokens、输出524tokens。原始请求、概率、逐项复核、输入版本和全部调用记录保存在本机T15目录。

T15四项Gradle检查通过，2分20秒，1项执行82项保持；主机测试任务使用缓存，报告仍为七项通过、七项跳过。Android源码和两个APK与T14验证产物一致，引用T14实际三项目标测试；本阶段不重复启动抖音采集。

## 继续未完成的范围

- 取件码复制、未关联抖音订单卡片与待发货订单卡片缺少真实样本；release 包未在设备安装（签名切换需要卸载并清除本地资料）。
- 京东待发货与待收货现场样本、302 与安全验证现场样本、原生 App 订单计数对照、多包裹与跨年物流；淘宝部分类型及完整平台范围；早期跨阶段字段变化的独立响应依据。
- 历史非空备注与归档样本、缺少被删除平台来源的清理分支；重新绑定的历史订单归属规则（S9 重新绑定后新账号未导入历史订单，旧账号保留订单与来源）。
- 在途、跨年、多包裹、新订单、账号不匹配、登录及安全验证的部分原生场景。
- 抖音完整列表结束、两笔候选必要字段、完整物流历史到底及无障碍自动重连。
- 拼多多平台结束标志、待分享标签、两条订单的状态提示文本、原生 App 页面对照及单设备登录策略。
- 真实取件码复制、未关联抖音订单卡片、外部平台导航及MainActivity启动与自动同步的完整联合流程。
- 进度密文损坏与版本不兼容；进行中网页同步的取消证据（S9 两次淘宝移除以界面“正在更新”为起点，删除动作发生时同步已完成）；后台更新进行中移除。
- 临时淘宝账号使用本机号码登录时淘宝侧账号的后续处置，需要用户确认是否新建了账号。
- 跨暂停无障碍启用列表变化归因、Jev整体速度和人工介入改善。

完整验收保持未完成。阶段结束表示本次已完成指定执行、记录和交付，缺少证据的项目继续保留。

## 暂停开发交付

2026-10-17 整理暂停说明、后续计划及开发预发布。版本、Room schema、App 源码、签名、账号和 profile 保持；没有安装应用、修改手机设置、查询平台或启动抖音采集。

四项 Gradle 检查退出0，4秒，1项执行、82项保持。单元测试任务使用已有结果，报告七项通过、七项跳过，没有重新执行这些缓存项目。78份App文件和两个APK与T15相同，继续引用T14实际三项49.587秒及对应两轮页面证据。构建与完整平台业务验收分别记录。

文档链接、两个APK的签名和SHA-256核查通过。待公开的源码与文档对照当前真实副本100项私人标识及凭据特征检查；主APK的151个归档条目同样检查，未发现匹配。该检查覆盖已知标识和凭据特征，完整原始资料继续只保存在本机。本次复用T15已复核的Jev证据，没有新增模型请求或调用费用。

五个经核实的生成文件目标已清理，共2729个文件、157534226字节，约150MiB。最终两个APK、测试及Lint报告另行保存；历史阶段证据、数据库副本、工具和本机构建依赖保留。本次未增加平台业务验收范围，后续顺序见[开发计划](development-plan.md)。完整日志及指纹保存至本机 `work/evidence/project-pause-delivery/` 和 `work/logs/project-pause-delivery/`。

## 2026-11-05 代码重构验证

对启动恢复、Repository 事务、SyncCoordinator、平台适配器、采集页面与包裹筛选执行行为保持重构；未修改 Room schema、平台接口、业务 Models、凭据格式与数据内容。

- `:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:app:testDebugUnitTest`、`:app:lintDebug` 退出 0；完整执行 1分39秒，最终复核 44秒。
- 主机测试：IdentityTest 2项、PlatformTimeTest 5项、ShipmentFilterTest 5项通过；NativeNavigationEvidenceTest 7项因未配置真实 T13 目录跳过。新增 5 项筛选纯函数测试。
- Lint：0 errors、71 warnings、2 hints；warning 为既有的 UseKtx、RequiresFeature 等提示。
- 未运行：86 项设备仪器测试、真实账号同步、平台响应解析与抖音采集。平台业务范围仍以本文件既有记录为准。

## 2026-11-23 P0 基线回归

2026-11-05 重构提交 a05f752 之后首次在真实设备执行回归。设备 24129PN74C 为 Android 17（API 37，系统 OS4.0.4.0.XOCCNXM，构建日期 2026-11-12），应用为 1.1.0、versionCode 2、Room schema v2。

- 构建：:app:assembleDebug、:app:assembleDebugAndroidTest、:app:testDebugUnitTest --rerun、:app:lintDebug --rerun 退出0；1分55秒，83项任务，9项执行、74项保持。
- 主机测试：IdentityTest 2项、PlatformTimeTest 5项、ShipmentFilterTest 5项通过；NativeNavigationEvidenceTest 7项因未配置T13证据目录跳过。合计12项通过、7项跳过。
- Lint：0 errors、71 warnings、2 hints。
- 设备测试：6项通过、0跳过、0失败。MigrationEvidenceTest 2项0.109秒；DataUiEvidenceTest 1项42.356秒；SyncRecoveryEvidenceTest#replayInterruptedDatabase 1项0.087秒、#replayRealOrders 1项1.132秒、#replayRecordedFailure 1项0.082秒。输入为T14真实schema1与schema2副本、T09真实订单与失败响应、T09中断副本；运行前核对SHA-256。
- 数据库：resume-before与after的一致副本中，八张业务表全部字段及settings、encrypted_credentials、webview_profiles、work_schedules字节一致；user_version 2、integrity_check ok。启动恢复把京东账号由SYNCING改为PARTIAL并记录“上次更新已中断”，before与resume-before仅Account表因此不同，其余七张表与四份配置一致。
- 安装：两个APK覆盖安装并保留数据。主APK SHA-256 ebd308f6…、测试包SHA-256 8d91e82f…、调试证书SHA-256 7957a084…；安装前设备主包为T15交付的ce8d7e8a…。
- 测试依赖：设备系统在2026-11-12更新后移除android.hardware.input.InputManager.getInstance；原Espresso 3.5.0通过反射调用该方法，Compose页面测试在EspressoLink初始化时失败。androidTest增加espresso-core 3.8.0-alpha04后通过；该版本改用Context.getSystemService(InputManager.class)反射injectInputEvent，test:core与test:runner解析为1.8.0-alpha04。正式包没有依赖变化。
- 未运行：ShipmentStatusEvidenceTest回放（本机缺少earlierDatabase与database副本）、SessionIsolationTest（拼多多账号需要登录）、86项设备测试全量、真实账号同步、平台响应解析与抖音采集。job scheduler当前没有该应用的登记作业。
- 资料：本机work/evidence/p0-baseline-2026-11-23/与work/logs/p0-baseline-2026-11-23/。

## 2026-11-24 P1 抖音采集模式与服务状态

- 账号模式：启动阶段把未删除的 DOUYIN WEB 账号规范为 APP_PAGE；设置页移除网页模式开关；SyncCoordinator 的网页同步排除 DOUYIN，DouyinAdapter.sync 返回 CONFIGURATION 错误。
- 真实迁移验证：T14 schema1→2 迁移副本中 1 个未删除抖音账号由 WEB 变为 APP_PAGE，其他平台账号与已删除抖音账号保持原值；MigrationEvidenceTest 3 项通过，0.128 秒。
- 服务状态：控制器新增 connected 状态流；onServiceConnected 检测上一轮活动采集时停止会话、结束进度并把账号置为 PARTIAL；recordEnd 在没有证据目录时安全返回。采集页面与设置页显示无障碍启用、连接、采集与触摸模式状态。
- 卡片定位：DouyinNativeOrders.card 改为按包含锚点的纵向列表定位，去掉固定深度 14；新增卡片范围 section 与失败诊断，查看物流入口按同一卡片范围判断。T13 真实扫描回放新增结构断言通过。
- 交易安全：NativeNavigationEvidenceTest 的真实动作回放确认四类交易节点被拒绝；本轮没有现场采集动作。
- 主机测试：16 项通过、3 项跳过、0 失败。设备测试：MigrationEvidenceTest 3 项 0.128 秒、DataUiEvidenceTest 1 项 47.411 秒通过。Lint 0 errors、71 warnings、2 hints。
- 未运行：真实抖音采集轮次、服务重连真机流程、卡片识别失败诊断的现场记录。无障碍启用列表当前只有 hello.litiaotiao.app；自动开启无障碍属于用户授权范围，本阶段没有修改系统设置。

## 2026-11-24 P2 原生采集资源与恢复语义

- 资料保留：默认只保存采集进度与每轮汇总（文件数、字节数、耗时）；扫描、事件、动作、耗时与分页资料由“记录完整采集诊断资料”开关控制，默认关闭。采集热路径不再写入 PlatformRegistry 的最近响应缓存。
- 清理规则：保留最近 3 轮并删除超过 7 天的目录，启动、开始采集与结束采集时执行；账号移除删除加密进度文件。清理前设备有 46 个证据目录、667293696 字节，已导出到本机 work/evidence/p2-native-evidence/；启动清理后目录数为 0。
- 轮次语义：恢复采集清空本轮 blocker、保留上一轮 blocker 到 previousBlocked、生成新 cycleId，blockedCards 的 key 带 cycleId；本轮未再出现的订单标为 stale；completeFor 要求本轮订单 finished 或 stale，并保持列表结束、范围确认、结束证据与无 blocker 条件。
- 进度重建：加密进度与备份改名为带时间戳的 .corrupt-* 副本；采集页面在进度读取失败时显示“重建采集进度”按钮，确认后提示开始新一轮。
- 异常边界：captureNow 与 captureRefreshed 用 AppFailure 表示采集已经停止；采集协程捕获 Throwable 并继续抛出 CancellationException；结束资料在 Dispatchers.IO 写入，IOException 转为 AppFailure；采集页面入口捕获 AppFailure。
- 主机测试：18 项通过、3 项跳过、0 失败，其中 NativeProgressTest 2 项覆盖恢复轮次与 stale 完成条件。设备测试：MigrationEvidenceTest 3 项 0.448 秒、DataUiEvidenceTest 1 项 47.505 秒通过。Lint 0 errors、71 warnings、2 hints。
- 未运行：真实采集的停止、八分钟到期、账号移除三种条件，诊断开关打开后的资料写入量，损坏进度重建与现场 blocked 诊断。设备无障碍服务未启用，需要用户授权与抖音前台操作。推送 commit 6ca945c 后 Android CI 通过。

## 2026-11-24 P3 抖音范围、候选字段与物流历史

- 结束标志扩展为九种文本，没有平台结束标志时保持 PARTIAL；“全部”标签未提供选中状态时把 scope-selection 标为 blocked，完整范围保持未确认。
- 已签收订单仍要求包含年份的下单时间与普通配送依据；缺少时保留 missing 或 excluded 原因。物流正文按“展开”节点循环展开，最多 12 次。
- 待发货范围决定：抖音待发货订单纳入导入范围，要求真实订单编号、订单状态与普通配送依据，不要求下单时间，不读取物流。
- 主机测试 18 项通过、3 项跳过；本次只扩展结束标志，没有新增真实页面样本。
- 未完成：平台明确列表结束与“全部”选中状态的现场证据、两笔候选的年份与配送依据、完整物流历史到底、跨年、在途与多包裹样本、两轮真实采集。推送 commit de843ba 后 Android CI 通过。

## 2026-11-24 P4 京东与淘宝真实响应回放

- 京东：JdOrderEvidenceTest#replayRealOrders 使用 T09 真实响应与 T06 期望，1 项通过，0.088 秒；排除编号集合与期望一致。
- 淘宝：TaobaoOrderEvidenceTest#replayRealOrders 使用 T09 真实 MTOP 响应与 T07 classic 期望，1 项通过，0.089 秒；最后一页 complete 为真，排除与保留集合一致。
- 数据库清理回放：使用 T06 before 与 T07 before 的 schema v2 副本，京东 1 项通过 0.183 秒、淘宝 1 项通过 0.189 秒；两轮 ingest 后只删除已确认编号对应的订单、来源与独立包裹，其余字段保持，integrity_check ok。
- 未运行：京东多页实时读取与列表结束、淘宝 nextPageIndex 与 hasMore 实时分页、登录失效与安全验证、当前待发货、充值、即时配送与服务订单样本。

## 2026-11-24 P5 至 P7 状态

- P5：无新增现场证据时沿用 P0 副本。2026-11-24 设备测试 `deadlineReleasesCaptureMode` 通过（14.974 秒），`deadlineReleased=true`，验证五秒期限到达后触摸请求释放与采集状态结束。共享来源、账号移除全流程与后台调度仍未验证。
- P6：页面覆盖沿用 DataUiEvidenceTest 的多次通过；Jev 对照未运行，需要 TYPESAFE_API_KEY 与费用授权。
- P7：2026-11-24 用户接受清空数据后完成最小发布安装：卸载调试包，安装本机签名 release 包，SHA-256 1278c616…、证书 SHA-256 c53606e0…，versionCode 2、versionName 1.1.0，安装后签名与构建一致。旧账号、包裹与采集进度已清除，需要重新登录；GitHub 发布与版本更新未执行。

## 2026-11-24 抖音真机采集与固定流程修复

- 用户启用抖音采集无障碍服务并登录账号后执行固定流程。期间修复三处导航问题：动作执行前页面变化改为最多 5 次重读，并使用同文字、同描述、位置相差 40px 内的节点；点击商城后如果直接进入订单列表，跳过“我的订单”入口；订单列表识别改为“全部 + 待支付、待发货、待收货、售后、评价”组合。
- 最终一轮运行 498.345 秒：进入订单列表并核查 5 笔订单，新增 0、更新 0、物流 0，5 笔待补充。列表节点为 RecyclerView，但没有提供 ACTION_SCROLL_FORWARD，流程停在正常触摸导航，八分钟期限到达后账号为 PARTIAL。
- 目标订单 6918…5327 已在数据库，但本轮候选未再次保存；连续恢复测试的“目标业务事务后停止”断言未通过。
- 期限截止证据：会话停止、没有活动账号、触摸请求标志为 0、账号 PARTIAL。
- Jev 已按用户要求关闭，本轮没有模型请求。
- 未完成：候选缺失字段的具体名称与详情布局、列表手势滚动、列表结束、物流历史、两轮恢复。
- 复查解密证据：详情页的订单编号、下单时间（2026-10-09 20:55:35）、状态与商品标题都是可见节点；可见性字段默认 true，此前按字段缺失统计的判断有误。
- 候选未保存的直接原因是详情页没有“配送方式/订单类型”字段，普通配送依据为空；当前版本的“查看物流”入口不在详情页，流程没有读取承运商或物流正文。
- 订单列表的 RecyclerView 在列表中段提供 ACTION_SCROLL_FORWARD，运行到列表底部后只剩 ACTION_SCROLL_BACKWARD；底部没有平台结束标志，流程按既有规则停在 PARTIAL。
- 用户确认订单详情中的“更多”进入物流信息；该页面由 ECBlenderActivity 渲染，无障碍节点只有“联系快递员”，物流正文、承运商与运单号不在节点树中。展开后的订单详情也只有时间字段，没有配送方式。当前采集方式无法读取物流正文。2026-11-24 试验：采集服务通过无障碍截图与 ML Kit 中文识别读取该画布页，识别出“韵达快递 3213…6018”、已签收 10-10 10:27、待取件、派件中、运输中轨迹以及“展开全部物流信息”；OCR 方案可行。已新增 DouyinOcrLogistics 解析器，用真实识别文本在主机测试中解析出承运商“韵达快递”、运单号“3213…6018”、已签收状态与待取件、派件中、运输中轨迹。2026-11-24 只读导入验证：手动停在真实物流页后，截图识别并写入订单 6918…5327；主库 Shipment 59→60、TrackingEvent 148→149，import.txt 记录 events=1。手势滑动方案经过真实运行未取得预期结果，已恢复原有滚动处理；仓库保持已验证状态。

