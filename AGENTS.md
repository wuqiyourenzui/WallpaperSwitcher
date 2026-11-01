## Agent skills

### 工具输出

紧凑：大 dump、日志、diff 先汇总，只取需要的字段与行数（`Select-Object -First N` 之类）；
完整 XML / 日志 / diff 不进对话。上下文膨胀会触发 413 与重连。

### 回复与上下文

结论先行，只给必要信息：改动摘要、`文件:行`、验证结果；不复述代码、日志与已读原文。
定向读取：先用 `rg` 定位再按行范围读，不整文件通读，不重复读同一文件。
一个会话做一件连贯的事；长会话用 `/compact` 压缩，任务完成后不追加未要求的总结。

### 手机 App 开发

本 repo 是 Android / Kotlin / Compose 项目。**动手前先读 `docs/agents/mobile-app.md`** ——
验证命令、adb 取证配方、Compose 叠层与返回键的定案、订阅源/WebView 域知识、结论纪律都在里面，
内容全部来自真机踩坑，避免重复犯错。

### Issue tracker

Issues 和 specs 存放在本 repo 的 GitHub Issues 中，通过 `gh` CLI 操作。详见 `docs/agents/issue-tracker.md`。

### Triage labels

沿用默认的五个 roles，label 名与 role 名一致（`needs-triage`、`needs-info`、`ready-for-agent`、`ready-for-human`、`wontfix`）。详见 `docs/agents/triage-labels.md`。

### Domain docs

Single-context：root 下一个 `CONTEXT.md` + `docs/adr/`。详见 `docs/agents/domain.md`。
