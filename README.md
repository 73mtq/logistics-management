# 快递助手

Android 应用，使用 Kotlin、Jetpack Compose、Material 3、Room、Android Keystore 和 WorkManager。包名为 `com.logisticsmanagement.app`，最低 Android API 26，编译与目标 API 36。

应用已在小米 15 安装并启动。已确认京东真实登录与 19 条订单导入，拼多多真实登录与两次返回 0 条订单的同步。测试已按用户要求停止，完整平台和外部服务验收尚未完成。具体结果见 [验收记录](docs/acceptance.md)，数据及同步实现见 [架构说明](docs/architecture.md)。

源码保存在 [GitHub 仓库](https://github.com/73mtq/logistics-management)，调试 APK 和 SHA256 校验文件见 [开发预发布版本 v1.0.0-dev.20261006](https://github.com/73mtq/logistics-management/releases/tag/v1.0.0-dev.20261006)。

## 理解项目

[项目关系图与源码导航](docs/project-map.md) 包含启动过程、模块关系、同步过程、数据关系和账号移除流程，GitHub 可以直接显示其中的 Mermaid 图。[CodeGraph 使用说明](docs/codegraph.md) 提供 Kotlin 代码关系索引的建立、更新和查询命令；[AGENTS.md](AGENTS.md) 提供 AI 阅读入口与开发要求。

## 构建与安装

需要 JDK 17 和 Android SDK，SDK 中安装 API 36。Gradle wrapper 固定使用 Gradle 8.13；首次执行会下载 Gradle 和项目依赖。

在项目目录通过 PowerShell 执行，SDK 路径填写本机实际位置：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:ANDROID_HOME = 'D:\Android\Sdk'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。启用手机的 USB 调试并允许电脑连接后安装：

```powershell
& "$env:ANDROID_HOME\platform-tools\adb.exe" devices -l
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r '.\app\build\outputs\apk\debug\app-debug.apk'
& "$env:ANDROID_HOME\platform-tools\adb.exe" shell am start -n com.logisticsmanagement.app/.MainActivity
```

页面与设备能力 instrumentation 使用：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.logisticsmanagement.app.DeviceAcceptanceTest' --console=plain
```

真实平台测试需要显式提供 `platform` 参数，并使用已经登录的真实账号：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.logisticsmanagement.app.RealPlatformAcceptanceTest' '-Pandroid.testInstrumentationRunnerArguments.platform=JD' --console=plain
```

`platform` 可填写 `JD`、`PDD`、`TAOBAO`、`DOUYIN` 或 `XIAOMI`。测试执行两次同步并检查导入标识保留；没有实际订单的数据只能验证空列表响应。当前抖音订单与轨迹结构尚未确认。没有提供 `platform` 时该项测试会跳过。

测试报告分别位于 `app/build/reports/tests/testDebugUnitTest/`、`app/build/reports/lint-results-debug.html` 和 `app/build/reports/androidTests/connected/`。`release` 构建需要自行配置签名；当前交付安装文件采用调试签名。

此前 APK 构建、2 项单元测试与 Lint 已通过。`DeviceAcceptanceTest` 在字体比例 1.0 和 2.0 下各执行 8 项，每次 6 项通过、2 项跳过、没有失败；跳过项目缺少真实运单或小米账号。字体比例已经恢复为 1.0。近期京东状态、日期和官方网页来源处理尚未完成完整同步复验。

本次交付通过项目 `gradlew.bat` 仅执行 `:app:assembleDebug`，13 秒构建成功，38 项任务中 5 项执行。最终修改之后没有重新运行单元测试、Lint 或真机验证。

| APK 资料 | 当前文件 |
| --- | --- |
| 路径 | `app/build/outputs/apk/debug/app-debug.apk` |
| 版本 | versionName `1.0.0`，versionCode `1` |
| 大小 | 20,643,792 bytes |
| 文件修改时间 | 2026-10-06 16:07:32，Asia/Shanghai |
| SHA-256 | `A3327AF979ADF68261985E71240FE00B3C5D84B67004909B9B40B232961A76B5` |

固定版本：

| 组件 | 版本 |
| --- | --- |
| JDK | 17 |
| Android Gradle Plugin | 8.13.2 |
| Gradle | 8.13 |
| Kotlin／Compose Compiler | 2.3.21 |
| KSP | 2.3.12 |
| Compose BOM | 2026.06.01 |
| Room | 2.8.5 |
| AndroidX WebKit | 1.17.1 |
| WorkManager | 2.12.0 |

## 使用页面

- **包裹**：优先显示待取件和物流异常；搜索名称、运单号、备注或来源账号，筛选平台、账号、状态和归档记录。尚无运单的待发货订单独立显示。
- **包裹详情**：查看来源账号、物流记录、取件信息和平台提供的预计送达时间，复制运单与取件码，保存备注和归档状态，打开来源订单网页。
- **日历**：按设备时区显示预计送达与当天物流更新，支持日期选择、切换周和返回今天。未提供预计送达日期的包裹单独列出。
- **助手**：启用自配 API 后询问包裹、生成日报及查看本地记录。
- **设置**：添加、登录、启停及移除平台账号，配置外部 API、通知权限、后台更新和日报时间。

界面明暗主题跟随系统，使用系统字体，正文为 `16.sp`，辅助文字至少 `14.sp`，主要触摸区域至少 `48.dp`。

## 平台账号

每个账号拥有独立 WebView profile。登录、同步和查看该账号来源订单使用同一 profile；官方网页可能要求短信、滑块、二维码或其他人工验证，这些操作在手机内完成。

| 平台 | 当前接入范围 | 验证状态 |
| --- | --- | --- |
| 小米智能助理 | 智能助理服务会话、物流手机号绑定、包裹列表及轨迹请求 | 真实登录、短信绑定及响应尚未验收 |
| 京东 | 官方订单页面的订单响应及物流页面读取 | 真实登录和 19 条订单导入已确认；近期映射与官方网页来源调整的完整同步复验未完成 |
| 淘宝／菜鸟 | 同账号 profile 中的 MTOP 订单请求及物流页面数据读取 | 真实分页、轨迹与登录保存尚未验收 |
| 拼多多 | 官方订单响应及物流页面读取 | 真实登录及两次同步成功，实际返回 0 条订单；有订单的分页和物流尚未验证 |
| 抖音 | 官方登录网页和商城订单响应采集 | 订单与轨迹响应结构尚未完成真实确认，当前明确报告需要验证 |

小米需要设备安装智能助理。在账号配置中通过“登录小米账号”取得对应服务会话，使用“读取设备标识”读取设备提供的 OAID／VAID，使用真实手机号发送验证码、验证并绑定物流手机号。登录会话、设备标识读取与短信绑定流程尚未完成真机验收。

京东已根据真实响应映射“完成”和“充值成功”，通过 `submitDate` 的 `yyyy-MM-dd HH:mm:ss` 格式读取创建时间，充值订单保存为无包裹订单。首次导入的已完成订单受最近 90 天限制；已经导入的订单持续更新并保留。其他部分平台尚未读取创建时间，90 天完整范围尚未验收。小米列表请求设置数量上限 29，达到上限时报告范围尚未确认。一个订单对应多个真实包裹的映射尚未完成逐个平台验证。

移除账号后取消该账号同步和后台更新，清除账号凭据及浏览数据，保留已导入订单、包裹、轨迹和用户备注。已经加载的 profile 会在下次应用进程启动时完成删除。移除来源账号后，其订单网页入口停止使用。

## 手动包裹与快递100

在设置中填写有效的 `customer` 和 `key`，保存后通过“添加包裹”选择公司、填写运单及查询所需电话号码。顺丰和中通查询需要电话号码，号码加密保存。

快递100使用实时查询接口。应用在发送请求前记录查询尝试，同一公司与运单至少间隔 30 分钟；未取得成功响应的请求也计入间隔。后台更新处理未归档且未签收的手动包裹。服务业务错误和网络错误通过应用显示。

## AI 配置与隐私

AI 默认关闭。在设置中填写 OpenAI-compatible 服务的 `baseURL`、`API key` 和 `model`，保存并测试连接后启用问答。应用使用 Chat Completions 文本响应格式；地址可填写服务基础路径，也可填写完整的 `chat/completions` 路径。

支持 HTTPS 和 HTTP。设置页面会说明 HTTP 使用未加密连接发送 API key 与问题内容。API key 仅用于已配置服务的认证。

问答与日报发送经过隐私处理的商品名称、快递公司、来源平台、包裹状态、时间、设备时区、是否有取件信息、轨迹数量与最近轨迹时间，以及尚无包裹的待发货订单摘要。自动摘要排除登录凭据、账号名称、运单号、取件码、取件地址和轨迹原文。

问题与商品名称中匹配到的已有运单、已有取件地址、完整电话号码和已配置服务凭据会被隐藏。请勿在问题中填写登录凭据或详细地址；未保存在应用中的地址无法通过已有地址匹配识别。问题和回答记录保存在本机。

每日自动报告需要同时启用 AI 和日报，默认时间为设备当地时间 20:00；也可以在助手页面手动生成。

## 后台运行与本地数据

后台更新默认每 30 分钟安排一次，要求网络连接。WorkManager 的实际执行时间由 Android 根据电量、网络、后台限制和系统调度决定。账号同步执行期限为 8 分钟，网页读取另有期限；取消或超时会释放 WebView。设置中显示账号最近成功更新时间。

物流通知按包裹、状态、轨迹事件和取件码保存已发送标识。通知需要用户授权，物流通知进入对应包裹，账号通知进入账号操作页面，日报通知进入助手。

业务记录保存在 Room。Android Keystore 加密保存账号配置、服务 key 和手动查询电话；WebView 会话保存在各账号 profile 中。应用禁用系统备份，并通过备份及设备迁移规则排除应用数据。卸载应用会清除本地记录。

## 开发与许可

修改平台映射时使用真实账号、真实响应及官方页面验证。程序错误直接暴露，明确的外部服务失败使用 `AppFailure` 显示；响应结构不匹配时保留已取得的数据并报告失败。验收资料不得包含凭据、完整手机号、详细地址或实际运单。

参考项目及复用许可见 [第三方许可](THIRD_PARTY_NOTICES.md)。新增或复用代码时保留对应许可声明；验证结果更新到 [验收记录](docs/acceptance.md)。
