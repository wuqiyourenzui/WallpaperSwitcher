# 手机 App 开发 Agent 模式

本文件是**在本 repo（Android / Kotlin / Compose）上干活的作业手册**，内容全部来自真机与历史会话
（2026-08 ~ 2026-10 共 60+ 个会话）的返工记录，不是通用套话。每条都附「为什么」或「症状」，便于判断何时适用。

配套：DSH 预设 `mobile-app`（persona + 随包技能 `mobile-app-development`）。本文件是本仓库的落地补充，
冲突时以这里为准；技能里的通用配方可以跨项目复用。

---

## 0. 一图流总则

**先取证，再动手；改一处，验一处；没验过的分支不许说"修好了"。**

本 repo 最贵的返工都不是技术难点，而是**取证不闭环**：
旧日志当新结论、盲点坐标当正确点击、`compileDebugKotlin` 当已装机、`edit` 回执当已落盘、
把"代码路径写完"当"功能可用"、把自己改坏的状态说成站点问题。

---

## 1. 验证闭环（每次改动必做）

```powershell
$env:JAVA_HOME="D:\Android Studio\jbr"; $env:ANDROID_HOME="D:\Android Studio\SDK"
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain --offline
"EXIT=$LASTEXITCODE"
```

规则：

- **三合一，一次调用**。拆成三次会多花两倍时间。冷构建 10 分钟级、增量约 2 分钟 → 丢后台作业收结果。
- 判成功看 **`$LASTEXITCODE -eq 0`**，不要只看 `BUILD SUCCESSFUL` 字样。
- **测试总数控制台不打印**，从 XML 汇总：
  ```powershell
  $files = Get-ChildItem "app\build\test-results\testDebugUnitTest\*.xml"
  $t=0;$f=0;$e=0;$s=0
  foreach($x in $files){ $x2=[xml](Get-Content $x.FullName -Encoding UTF8); $t+=[int]$x2.testsuite.tests; $f+=[int]$x2.testsuite.failures; $e+=[int]$x2.testsuite.errors; $s+=[int]$x2.testsuite.skipped }
  "files=$($files.Count) tests=$t failures=$f errors=$e skipped=$s"
  ```
  当前基线：**82 文件 / 580 tests / 0 failures / 2 skipped**（2026-10-17 实测）。**基线每次重取**，不要拿旧数字判"新增失败"。
- **lint 基线**：`app/lint-baseline.xml` 已冻结历史问题（当前过滤掉 2 errors / 101 warnings），
  控制台只显示新增项 —— **`0 errors, 1 warnings`**（`values-es` 的 `MissingQuantity`）。多一个 warning 就是新引入。
- 失败要说清是哪个 task：`:app:compileDebugKotlin`（主源码集，全局卡点）/ `:app:compileDebugUnitTestKotlin`
  （测试源码，常见 fake 缺新接口）/ `:app:testDebugUnitTest`（真跑了但挂了）。三者含义完全不同。
- 编译错误筛选：`.\gradlew.bat :app:compileDebugKotlin ... 2>&1 | Select-String "\.kt:\d+:\d+"` —— 不触发上下文膨胀。
- 依赖只有 `junit:junit:4.13.2` + `kotlin("test-junit")`：**没有 Robolectric / room-testing**。
  DAO 逻辑靠手写 fake（`FakeWallpaperImageDao`）或纯函数抽出。`app/src/test/java/android/util/Log.kt` 是故意留的桩，**别删**。
- **并发构建会互踩**：两个 Gradle 抢同一 `app/build` 会等锁或产出 0 字节 class；`clean` 会让别人的测试证据作废。
  多人同仓时串行验证；谁 `clean` 谁通知；终验在"全员停写"的树上跑。

---

## 2. adb 取证配方

| 需求 | 正确写法 | 坑 |
|---|---|---|
| 截图 | `cmd /c "adb exec-out screencap -p > f.png"` | **PowerShell 的 `>` 会破坏二进制** |
| 应用日志 | `adb exec-out run-as <pkg> cat cache/logs/runtime.log` | logcat 常被别的 reader 抽干 |
| UI 文本+坐标 | `adb shell uiautomator dump` + `adb pull` | Compose 偶发只 dump 一部分 |
| 点击 | **先 dump 取 `bounds`，点中心** | 见下方"盲点坐标" |
| 装包 | `adb install -r -d` | 降级安装要 `-d` |
| 装机核对 | `dumpsys package com.wallpaperswitcher \| Select-String lastUpdateTime` | 与 APK mtime 对不上就是没装上 |

`adb` 全路径：`D:\Android Studio\SDK\platform-tools\adb.exe`（本 shell 的 PATH 里没有）。

**日志会轮转**：`runtime.log` 约 10KB 就回卷。**读任何结论前先看时间戳**，确认这些行是**本次安装之后**产生的。
本 repo 至少三次把装包前的旧日志当成新版效果，得出"修复无效"的错误结论。

**盲点坐标是最大杀手**。手写 `input tap 600 1182` 会点空、点到相邻卡片、点到上一屏，整条验证链建立在一个假状态上。
正确做法：

1. `uiautomator dump` → 解析目标节点 `bounds`
2. 点 `(x1+x2)/2, (y1+y2)/2`
3. **点完再 dump 一次，确认状态真的变了**，才进入下一步

本 repo 出过：一次点击被吞后按同坐标再点，落到详情页的"锁屏" chip，把用户分组 7 的应用位置从 BOTH 改成 LOCK。

**只跑 `compileDebugKotlin` 就上机看效果 = 装的还是旧 APK**。真机验证前必须 `assembleDebug` + `install -r`。

**MIUI / HyperOS 真机四硬限**（实测，别再试）：

1. `install` / `pm install` / 会话式安装全路径可能被 `INSTALL_FAILED_USER_RESTRICTED` 拦（纯净模式或 USB 安装未开，shell 无 `WRITE_SECURE_SETTINGS`）；
2. `input keyevent` 熄屏被拒（无 `INJECT_EVENTS`）；
3. 第三方 `logcat` 被屏蔽；
4. Android 12+ 禁后台拉起 FGS。

替代取证：APK 推到 `Download/` 手装，再用 `run-as <pkg> cat cache/logs/runtime.log` 拉应用自写日志。

**做不到的事**：`adb shell input text` 发不了中文；`service call clipboard` 在 Android 12+ 被封。

**换装/回灌**：换签名前先确认目标设备能装，再卸载；卸载前用 `run-as` + base64 导出 db/wal/shm 并留第二份备份；
回灌后哈希比对 + sqlite 校验行数。

`Get-Content` 在本 shell 里把 UTF-8 显示成乱码 —— **不代表文件坏了**，判定编码用 `read`/`grep` 工具，别据此"修复"文件。

---

## 3. 改文件的硬规则

- **Kotlin / XML 一律用 `edit`/`write` 工具改。**
  PowerShell `Set-Content -Encoding UTF8` 会把中文经 GBK 往返写坏，还会压掉换行（曾整份 `RssSourceEditScreen.kt` 被毁）。
  万不得已要用 PowerShell：`[System.IO.File]::ReadAllText/WriteAllText` + `UTF8Encoding($false)`。
- **写盘要复核**：本 repo 出现过 `edit`/`write` 报成功但磁盘未变（重复目录树吞写入、整文件覆盖）。
  每次改完立刻 `git diff --stat` 或重新读取抽查。
- **批量脚本改源码 = 高危**：先建 commit 检查点、单文件小步、每步编译兜底。曾一次批量替换毁 4 个文件、
  一次"按总数补 `}`"毁 1200 行 Compose 文件。
- **KDoc 里不能出现 `*/`**。注释里写 glob（如 `mp43/*.mp4`）会把注释提前闭合，报 "Unclosed comment"。
- **Compose lint 要求 `modifier: Modifier = Modifier` 是所有可选参数里的第一个**（`ModifierParameter`）。
- 用户可见文案进 `res/values/strings.xml`，不硬编码。
- 删代码前全局搜调用者：`playerPageHtml` 看似孤儿，实际还被「浏览器收集」对话框的内联播放用着。

---

## 4. Compose 叠层与导航（本 repo 反复踩的区域）

**Scaffold 的槽位是独立的绘制层**：`topBar` / `bottomBar` 画在 **content 之上**。所以写在 content 里的全屏叠层：

- **盖不住顶栏/底栏的像素**，用户在叠层开着时**照样能点到**顶栏按钮
- 因此**任何在顶栏/底栏里切屏的动作，都必须先清掉叠层状态**，否则新页面被叠层盖住

**Box 内兄弟的绘制顺序 = 代码先后**（后写的在上面）。把"编辑器叠层"写在"文章叠层"之前 → 文章盖住编辑页。

**`BackHandler` 是 LIFO**：后注册的赢。要拦截某一层的返回，处理器必须注册在**那一层内部**；
通用返回处理器要加叠层守卫：`if (backHandled && article == null && editor == null)`。
顶层底栏标签不注册 `BackHandler`（直接退应用），只有真有父页的子页才拦。

**`remember { }` 的 key 必须覆盖所有会变的输入**：对话框初值（保存后仍显示旧值）、`itemCount`（fast-scroller 拇指错位）都是典型漏项。

**跨页保活的滚动位置由父级持有**；跳子页回来回顶 = `rememberScrollState()` 写在页面内部被重建。
**状态推进读数据库/VM 最新值**，不能读 UI 快照（连点两次"切换布局"跳档就栽在这）。

---

## 5. 实时壁纸 / 渲染 / 媒体（本 repo 主战场）

- 像素在纹理里：`lastImageBitmap == null` 不等于"不可重绘"，早退条件用 `canRepaintFromTexture()` + 缓存的源尺寸，
  否则释放 CPU 位图后转屏黑屏。
- EGL 上下文重建要把 `imageTextured`、`imageSrcW/H` 一起归零，否则"可重绘"是假状态（画出空纹理）。
- **"已显示"只在 `swapBuffers` 成功后提交**；surface 未就绪时静默丢帧 + `drawCurrentImage` 早退 = 永久旧帧。
- 看门狗/异步回调要带代次令牌（`videoGeneration`）；`cancel()` 拦不住已经跑起来的那一拍。
- 音频线程要 join 或代次令牌，`track.release()` 归创建者，否则两段音频叠放/杂音。
- 内存释放只在安全态（非视频播放、非切换中、非省电、GIF 非活动）；释放后下一拍要能重建（GIF 缓冲不能当致命错误）。
- 跟随进程的缓存一律有界（仓库约定 LRU 256）；`ConcurrentHashMap` 不能存 null。
- `WallpaperManager.setBitmap` 在调用方进程里做 PNG 压缩（单次静态写入实测 2.2–3.7s）→ 已改 JPEG q95 + `setStream` + JPEG 复用
  （实测每次写入省约 230ms CPU）。**写入型 API 的返回值必须判 0**（失败返回 0，被丢弃就等于假成功）。
- **10s 间隔下限是产品约束**，不是缺陷。

---

## 6. 订阅源 / WebView 域知识

- **`loadDataWithBaseURL` 的 baseUrl 不能用媒体地址**：WebView 会把它当页面目标直接去加载那条 mp4，整屏黑。用 `null` 或 `about:blank`。
- **hls.js 走 XHR，会撞 CORS**。只有 `.m3u8` 才交给 hls.js；普通 mp4 直接 `video.src`。
  搞错的症状：`origin 'null' has been blocked by CORS policy` + `manifestLoadError` + `readyState=0`。
- **自己拼的 `data:` 播放页在本机型上永不出画**：元数据到手、`paused=false`、`duration` 有值、无 `error`，
  但 `readyState` 停在 0/1。同一地址交给**站点自己的播放页**就正常。已定案，别再试 `LAYER_TYPE_NONE`（实测更糟）。
- **加载站点页必须带源的 header**（Referer/UA），否则只拿到约 639B 的 stub，真页面约 38KB。
- **`ruleContent` 的输出类型决定渲染方式**：HTML → 按规则渲染；一条媒体地址 → 就播这条地址；什么都没有 → 才退站点页面。
  任何一支都不能"丢掉规则结果"。
- **`java.ajax` 是 Rhino 线程上的阻塞调用**，而 JS 有 watchdog 用 `Thread.interrupt()` 防死循环。
  网络等待被算进同一份超时预算时，超时中断会取消正在飞的 OkHttp → `StreamResetException: stream was reset: CANCEL`。
- **HTTP 状态码失败不要重试、不要计入熔断**。图集规则会故意往后多探几页，站点对越界页回 502 —— 那是规则的一部分。
  只有传输层失败（`FetchException.httpStatus == 0` / IOException）才值得换连接重试。
  曾把 502 计入熔断，图集从 47 张掉到 5 张。
- **JS 返回值保留原始类型**：`toString()` 会把 Rhino 数组变成一条字符串（`items=1`）；只对集合用 raw，
  否则数字会变 `2.0` 并弄坏既有测试。
- **媒体地址要支持协议相对地址**（`//ev-h.phncdn.com/...`），否则判成"没有媒体"→ 退回网页 → 无法播放。
- **正文为空/被挡（0B 或几百字节挑战页）时按视频文章走播放页**；判图还是视频看文章内容，不看源的类型。
- **`loginCheckJs` 返回 false 不算失败**；成功页永远不许判成 auth。
- **图集/多页规则超时按最慢的合法规则给**（60s 量级），不是按"正常规则"。

---

## 7. 数据 / 文件

- **一处清单只留一份**：`MANAGED_DIRS`（哪些目录归应用管）与删除路径白名单曾各写一份，漂移后"删分组不删压缩包解出的图片"。
  删除路径复用清理器清单，用单测钉住不漂移。
- **压缩包解压到 app 私有目录**，多选压缩包拉平成一个目录。
- **索引跟着热查询走**：`WHERE groupId=? ORDER BY addedAt DESC, id DESC` 需要 `(groupId, addedAt, id)` 复合索引 + migration。
  Room 版本看 `AppDatabase`（当前 16），改 schema 必须补 migration。
- **设置读取合并成单 Flow**（28 路 combine → 单查询）是已验证方向；`collect` 外包静默 `catch` 会让订阅永久退出，
  必须 `while(true)` 重订阅并 rethrow `CancellationException`。

---

## 8. 性能 / 功耗测量纪律（新增，来自 35MB 审计会话）

> 每一次错误结论都来自测量协议，而不是代码理解。

1. 先三绿基线（build + test + lint），再谈性能。
2. **禁止用 `force-stop` 造"冷启动"**：连磁盘解码一起冷掉。`install -r` 后 ART/JIT 冷，必须预热（20 次量级）再测。
3. A/B 同机、同包、同脚本、同前台窗口；用 `AppLog started` 行切出安装边界，新旧包时间窗不能混。
4. 每个结论都要一次**反方向对照**；一次正向结果不算数。
5. 先定指标口径：不要把"引擎实例初始 ON/OFF"算成抖动（曾报 246 次，实为 8 次）。
6. 先相位分解（`framestats`：UI / GPU / 整帧），GPU 低而 UI 高就别动绘制。
7. 热点用 `simpleperf --app <pkg>`（非 root 下 `cpu-cycles:u` 可能被拒）；网格滑动的真成本是 `libjpeg decode_mcu`（66.99%）。
8. **`gfxinfo` 对动态壁纸/部分机型恒 0 帧**（`Janky 0.00%` 是假象）→ 用 Perfetto / Macrobenchmark / 自建帧时间埋点；
   `tools/perf-snapshot.ps1` 会输出 `UNAVAILABLE` 而不是 0%。
9. 换机 / 换屏（60→120Hz）/ 换 Android 版本都要重测。
10. 优化要留可观测日志行（如 `Visibility blip coalesced`），否则无法计数验证。

**已知实测基线**（平板 1440×3200）：亮屏+桌面+锁屏定时 10s ≈ **33% 单核**；熄屏 Dozing ≈ 0.17%；
冷启动 309ms、PSS 240MB（`docs/performance/`）。

---

## 9. 协作、范围与结论纪律（最贵的一节）

1. **审查/诊断类请求默认只出清单**：`文件:行号` + 原始代码 + 影响面 + 建议，等用户确认再改。
   用户两次纠正过"直接改了才汇报"。
2. **按用户给的批次边界执行**：A1–A6 + B1–B4 做完就停，B5–B10"另开一批"不顺手做。一次性改 11 项引发过回退。
3. **不替用户做产品决策**：省电 vs 功能、默认值、交互语义先提方案；撤回用户既有行为会被要求恢复。
4. **区分三件事，措辞要准**：`代码路径已实现` / `编译与单测通过` / `真机验证通过`。只有第三件才能说"修好了"。
5. **破坏既有功能立刻回退并明说**。曾把"能播的 91porn"改成不能播，还基于坏状态说"两边都播不了"。
6. **不动用户的设置/分组/订阅源**。为验证而点开的页面要说明，改这类配置前必须确认。
7. **诊断脚手架用完就撤或降级**。屏幕上叠状态行会挡真实画面；状态改走 `console.log` → 应用日志。
8. **不为了让构建过而删/跳测试**，不吞异常。测试名必须描述真正被测的东西。
9. **未验证项单列**，附用户可自测的三步；不要用"应该可以"糊过去。

---

## 10. 效率配方

- **让程序自己说话，别读像素**：状态 `console.log` 进应用日志，一行 grep 拿到 `readyState=…/4 errCode=…`。
- **一次命令跑完整条 adb 链路**（force-stop → start → tap → 等 → dump → pull），但**每个关键状态转换都要 dump 验证**。
- **大 dump / 日志先汇总再进对话**：只取 `text`/`bounds`/关键行，`Select-Object -First N`。
- **长构建/装机/批量测量放后台作业**，等待期间做别的。
- **先判栈再写码**：`dependencyInsight` 定版本、`javap` 定签名、`glob` 定路径、`Measure-Object` 定行数。

---

## 11. 来源与更新（2026-10-17）

本文件与 DSH 预设 `mobile-app`（persona + 随包技能 `mobile-app-development`）同源，规则来自本仓库
60+ 个历史会话（DSH 53 个 + Codex 15 个，含 3 个 20MB 级审计会话）的返工复盘。重复度最高的失败模式：

| 失败模式 | 典型代价 |
|---|---|
| 没取证就下结论（旧日志/旧 APK/盲点坐标/读代码当编译） | 单次 1–2 小时返工，结论反转 3 次 |
| 写盘不复核（edit 报成功但磁盘未变） | 交付"假完成" |
| 不判栈就写第三方 API | 15 分钟级编译错误 + 改坏别人的文件 |
| 测量协议污染（force-stop 造冷、无预热、无对照） | 整轮性能结论作废 |
| 范围失控（审查直接改、跨批次顺手做、替用户做产品决策） | 改动被回退 |
| 上下文膨胀（整文件/整份日志进对话） | 触发压缩/断连 |

详细取证报告（每会话的证据与原件行号）留在 `build/session-mine/reports/`，属于可再生的工作产物，不入库。
