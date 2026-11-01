# 分组级调度：已实现（2026-10）

状态：**已实现**；并修复过一轮"定时切换与预览 / 手动各走一套分组规则"的不一致
（见下表）。本文件保留已确认的语义决定与代码落点，避免下次重新推导或误判。

## 用户已确认的语义（不要再重新征询）

1. 「顺序」模式 = **本组走完 → 下一个分组**。
2. **手动切换**（点「换一张」/ 悬浮按钮 / 双击 / 立即切换）= **跟随分组调度**。
3. 「仅图片 / 仅视频」分组的 media filter **应当**排除另一类媒体（符合预期）。

## 代码落点（改动时以这些为准）

| 语义 | 实现 |
|---|---|
| 「下一组」定义 | `GroupSchedulePlan.sequentialStartGroupId`（当前图片所在分组）+ `nextGroupAfter`（本组走完后按 id 升序闭环） |
| 取图推进（本组走完 → 下一组） | `GroupPick.pickSequential`：实时引擎 `SwitchExecutor`、静态 `WallpaperApplier`、预取 `PrefetchController`、下一张预览 `NextPreview` **共用同一个调用** |
| 定时 tick 的分组与时钟 | `WallpaperSwitchService.nextScreenTick` → `GroupSchedulePlan.sequential`（当前组自己的时钟）；当前组不可解析时回落屏幕级 `groupId = 0`（`MediaPick.sequentialAcrossGroups`），与预览的兜底一致 |
| 手动 / 解锁的分组 | `GroupSchedulePlan.nextGroupId` / `nextHomeGroupId`；**随机 / 洗牌返回 0** = 屏幕级池（按分组轮转会退化） |
| 「本组走完」判据 | `SwitchPicking.sequentialWrapped`（升序 `<=` / NEWEST `>=` 判绕回） |
| 顺序=新的在前 | `GroupRules.newestFirst`（sortOrder = NEWEST）：三条切换路径 + 预取 + 预览都读同一个判定 | 

## 验证限制

- 本机没有 Robolectric / room-testing（只有 junit + kotlin-test-junit，见
  `app/build.gradle.kts:136-137`），调度逻辑只能用 `FakeWallpaperImageDao` 这类手写
  假 DAO 测。
- 改动会话期间 `adb devices` 全程为空，无法上机验证 tick 行为。

## 相关但独立

压缩包导入（第 1 项）已实现：`engine/ArchiveImport.kt` + `ArchiveImportTest`（9 例）+
`MediaLibraryController.importArchives` + 「添加壁纸」新增压缩包入口；解压到
`files/archives/`，多选拉平，`OwnedMediaCleaner.MANAGED_DIRS` 已加 `"archives"`。
