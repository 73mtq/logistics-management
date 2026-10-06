# 项目阅读与开发说明

本项目是个人 Android 快递助手，包名 `com.logisticsmanagement.app`，使用 Kotlin、Jetpack Compose、Room、Android Keystore 和 WorkManager。

## 阅读入口

- [README.md](README.md)：产品页面、构建、安装与外部服务配置。
- [docs/project-map.md](docs/project-map.md)：项目关系图、调用过程与按任务选择源码的入口。
- [docs/architecture.md](docs/architecture.md)：数据保留、合并、会话、后台任务与隐私规则。
- [docs/acceptance.md](docs/acceptance.md)：已经执行的验证及未验证范围。
- [docs/codegraph.md](docs/codegraph.md)：本地代码关系索引与查询命令。
- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)：参考项目与复用许可。

平台适配器存在于源码中，只能说明已经编写相应流程。平台的真实可用范围以验收记录为依据。抖音订单与轨迹响应映射尚未完成。

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
