# CodeGraph 使用说明

CodeGraph 为 Kotlin 类、函数、引用和调用建立本地索引，支持按符号查找源码、查询调用方和分析修改影响。[项目关系图](project-map.md) 提供产品与模块层面的阅读入口，两者共同用于理解项目。

## 建立与更新

已使用 CodeGraph `1.0.1` 在本项目建立索引。该版本要求 Node.js `>=20.0.0 <25.0.0`。安装 CLI 的环境可以直接执行项目命令；需要安装时使用：

```powershell
npm install -g @colbymchenry/codegraph@1.0.1
```

在仓库根目录建立索引：

```powershell
codegraph init .
codegraph status .
```

已有索引的环境在修改源码或取得新的提交后更新索引：

```powershell
codegraph sync .
```

索引保存在 `.codegraph/`，由每台电脑根据当前源码重新生成。数据库、运行日志和锁文件不进入 Git。上传到仓库的 Mermaid 图、源码导航和查询说明可以直接阅读。

## 本次索引记录

2026-11-27 执行 `codegraph sync` 与 `codegraph status`，解析完成，未报告文件解析失败：

| 项目 | 数量 |
| --- | --- |
| 索引文件 | 257 |
| 节点 | 4,785 |
| 关系 | 13,193 |
| kotlin | 147 |
| xml | 95 |
| java | 6 |
| properties | 4 |
| python | 3 |
| yaml | 2 |

索引包含构建生成源码，kotlin 数量高于源码 97 份（main 50、单元测试 11、设备测试 36）。节点包含文件、import、namespace、class、interface、function、method、enum 等类型；关系包含调用、引用、实现与其他静态源码关系。索引数量描述当次源码，源码变更后以 `codegraph status` 为准。Android 生命周期、Room 生成代码及平台网页行为需要结合源码与[验收记录](acceptance.md)阅读。

## 查询项目

| 阅读目的 | 命令 |
| --- | --- |
| 账号同步与更新流程 | `codegraph explore SyncCoordinator --max-files 1` |
| 数据导入与包裹合并 | `codegraph explore LogisticsRepository --max-files 1` |
| 平台实现与账号页面 | `codegraph explore PlatformRegistry --max-files 1` |
| 查看接口原文 | `codegraph node app/src/main/java/com/logisticsmanagement/app/platform/PlatformAdapter.kt` |
| 查看 profile 与 WebView 符号 | `codegraph node app/src/main/java/com/logisticsmanagement/app/platform/PlatformWebView.kt --symbols-only` |
| 查询账号同步入口 | `codegraph callers syncAccount` |
| 查询方法调用的其他方法 | `codegraph callees syncAccount` |
| 查询导入方法的修改影响 | `codegraph impact ingest` |

相同名称的函数可以通过文件路径定位。需要完整文件时使用 `codegraph node` 的 `--offset` 和 `--limit` 参数，例如：

```powershell
codegraph node app/src/main/java/com/logisticsmanagement/app/sync/SyncCoordinator.kt --offset 81 --limit 51
```

本次已执行 `codegraph sync` 与 `status`；此前已核查 `explore`、`node` 和 `callers syncAccount` 的查询入口，应用业务验收范围仍以 `acceptance.md` 为准。
