# 项目阅读与开发说明

本项目是个人 Android 快递助手，包名 `com.logisticsmanagement.app`，使用 Kotlin、Jetpack Compose、Room、Android Keystore 和 WorkManager。

## 阅读入口

- [README.md](README.md)：产品页面、构建、安装与外部服务配置。
- [docs/project-map.md](docs/project-map.md)：项目关系图、调用过程与按任务选择源码的入口。
- [docs/architecture.md](docs/architecture.md)：数据保留、合并、会话、后台任务与隐私规则。
- [docs/acceptance.md](docs/acceptance.md)：已经执行的验证及未验证范围。
- [docs/development-plan.md](docs/development-plan.md)：阶段顺序、完成条件及恢复入口。
- [docs/codegraph.md](docs/codegraph.md)：本地代码关系索引与查询命令。
- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)：参考项目与复用许可。

平台适配器存在于源码中，只能说明已经编写相应流程。平台的真实可用范围以验收记录为依据。抖音已取得一个真实已签收订单的导入与物流关联证据；完整列表结束、部分字段及服务自动重连仍有未完成范围。

## CodeGraph

当项目根目录存在 `.codegraph/` 时，需要了解或定位代码应当首先使用 `codegraph explore`、`codegraph node` 或对应 MCP 工具，再根据返回结果阅读相关源码。索引在本机生成；修改源码后执行 `codegraph sync`。

```powershell
codegraph explore SyncCoordinator --max-files 1
codegraph node app/src/main/java/com/logisticsmanagement/app/platform/PlatformAdapter.kt
codegraph callers syncAccount
```

尚未建立索引的环境可以按照 `docs/codegraph.md` 建立索引，也可以通过 `docs/project-map.md` 的源码链接阅读项目。

## 代码与数据要求

- identifier 保留英文，注释使用中文，专用名词保留原名。
- 业务写入与包裹合并由 `LogisticsRepository` 维护；页面与平台适配器使用现有入口。
- 前台和后台的账号更新共用 `SyncCoordinator`。账号登录、同步和订单页面使用该账号的 WebView profile。
- 响应使用 kotlinx.serialization，网络使用 OkHttp，日期使用标准日期库。结构不匹配时报告 `UNSUPPORTED_RESPONSE`。
- 程序错误在出错位置暴露；明确的外部服务失败通过 `AppFailure` 和账号状态展示。
- 使用真实账号、真实响应和真实服务验证平台行为。禁止伪造订单、凭据、响应或通过绕过流程制造通过结果。
- 凭据、完整手机号、详细地址、实际运单和未处理的响应资料保存在本机。`.work/`、`.codegraph/`、构建目录与 `local.properties` 由 `.gitignore` 排除。
- 需要登录、验证码或授权时由用户在手机中完成。涉及实际服务的验证需要有效配置与相应设备条件。

构建版本以根目录和 `app/build.gradle.kts` 为准。文档修改无需运行 Android 业务测试；需要验证代码时使用 README 中对应任务，并将实际通过、跳过和未运行范围分别记录。

2026-11-23 完成 P0 基线回归，后续按开发计划 P1 至 P7 执行。保留现有账号、profile 及本机证据；不自动启动平台查询或抖音采集，需要登录、验证码或授权时由用户在手机完成。

## 后续阶段的 Jev 辅助

T15及之后新建阶段采用[Jev辅助规则](docs/jev-workflow.md)，明确每阶段的输入、固定选项、来源及核查。沿用jev-1.14.0、单次十秒、每阶段最多一百次及相同输入缓存；私人资料保存在本机。模型结果逐项复核，页面自动操作保持0.95概率及程序检查，交易操作禁止。模型效果、工具验收和缺少样本的范围分别记录。

## 开发期 Pinky 辅助

开发期使用[Pinky](docs/pinky-workflow.md)的 MCP 工具操作测试设备；手机界面操作、页面导航与页面证据采集默认使用 Pinky，包括页面探索、失败复现与日志读取。Pinky 不进入 App 依赖与生产采集路径；构建、安装、instrument 与数据库副本仍使用 adb，两类操作串行执行；正式验收仍使用 instrument 测试、数据库副本与真实页面证据。

- Pinky 任务与 instrument 测试、生产采集串行执行；任务前后记录安装包指纹、无障碍启用列表与数据库状态。
- 读屏文本、截图与任务报告保存在本机忽略目录 `work/evidence/pinky/`，不进入公开仓库；真实订单号、运单号与地址不写入文档与提交信息。
- 需要登录、验证码、授权或系统设置变更时由用户在手机完成。
- Pinky 网关不可用、首次批准未完成或工具超时时停止设备执行并报告；不使用 adb shell input、uiautomator dump 一类手工驱动替代手机操作。
