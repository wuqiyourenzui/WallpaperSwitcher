## Agent skills

### 工具输出

紧凑：大 dump、日志、diff 先汇总，只取需要的字段与行数（`Select-Object -First N` 之类）；
完整 XML / 日志 / diff 不进对话。上下文膨胀会触发 413 与重连。

### Issue tracker

Issues 和 specs 存放在本 repo 的 GitHub Issues 中，通过 `gh` CLI 操作。详见 `docs/agents/issue-tracker.md`。

### Triage labels

沿用默认的五个 roles，label 名与 role 名一致（`needs-triage`、`needs-info`、`ready-for-agent`、`ready-for-human`、`wontfix`）。详见 `docs/agents/triage-labels.md`。

### Domain docs

Single-context：root 下一个 `CONTEXT.md` + `docs/adr/`。详见 `docs/agents/domain.md`。
