# Pinky 开发辅助工作流

更新日期：2026-11-26，Asia/Shanghai。

Pinky 是桌面端的 MCP 手机操作网关，用于开发期的页面探索、失败复现与日志诊断。它不进入 App 依赖，不改变生产采集路径，不替代阶段验收。

## 接入

- Windows 安装包 pinky-setup-2026.1125.9-amd64.exe 安装到 `%LOCALAPPDATA%\Programs\Pinky`。
- 设备 95f79212 通过 USB 连接，Pinky 复用系统 adb。
- DeepSeek Harness 的 desktop profile 在 `cordis.patch.yml` 中包含 `mcp-pinky` 条目，stdio 传输，命令为 `Pinky.exe` 加 `mcp` 参数；重启 DSH 后工具以 `mcp__pinky__phone_*` 名称出现。
- 首次连接需要在 Pinky 窗口批准 agent 并选择权限范围。
- 小米、Redmi、POCO 设备需要在开发者选项打开「USB 调试（安全设置）」，否则 companion 不能点击与输入。

## 工具映射

| 开发任务 | Pinky 工具 |
| --- | --- |
| 读取当前页面结构 | phone_observe |
| 跨屏读取订单列表 | phone_read_list |
| 按元素引用点击 | phone_tap |
| 输入文本 | phone_type |
| 滑动与滚动 | phone_swipe |
| 启动应用或打开链接 | phone_launch |
| 安装、更新、停止 debug 包 | phone_app |
| 读取 App 日志与崩溃 | phone_logs |
| 等待页面文本出现 | phone_wait_for |
| 在电脑与手机之间传文件 | phone_files |
| 需要登录、验证码或授权时交接用户 | phone_handoff |

## 证据与隐私

- 读屏文本、截图与任务报告保存到 `work/evidence/pinky/<阶段>/`，该目录由 `.gitignore` 排除。
- 真实订单号、运单号、地址不写入文档、提交信息或公开仓库。
- Pinky 自身不上传屏幕，但读屏文本会进入 DSH 会话的模型上下文。含真实订单正文的页面需要用户明确许可后再交给 agent。

## 设备隔离

- Pinky 任务与 instrument 测试、生产采集串行执行，避免两个无障碍服务同时读取抖音页面。
- 每次任务前后记录安装包指纹、无障碍启用列表与数据库副本状态，写入对应阶段证据目录。
- Pinky companion 的无障碍服务只在任务期间开启，任务结束几秒后关闭；验收检查期间不要同时运行 Pinky 任务。

## 故障处理

- 小米「USB 调试（安全设置）」无法打开：停止 Pinky 接入，回到现有 adb 与 work 脚本流程。
- MCP 调用超时或 gateway 未运行：打开或重启 Pinky 桌面应用，再重启 DSH。
- 读屏缺少所需属性：使用 `uiautomator dump` 补属性证据，Pinky 只用于页面导航与文本确认。
- 需要验证码、密码或系统设置变更：使用 phone_handoff，由用户在手机完成。
