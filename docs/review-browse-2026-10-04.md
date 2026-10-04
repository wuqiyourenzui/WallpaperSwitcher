# 评审：大图浏览 / 收藏聚合 / 长按预览 / 最近显示回滚

评审人：reviewer-browse（只读评审，未改动任何源码）
评审时间：2026-10-04 20:0x（+08:00）

## 被评审的版本（并发编辑中，本报告以这些 hash 为准）

| 文件 | SHA256 前 12 位 | 大小 | mtime |
| --- | --- | --- | --- |
| `app/src/main/java/com/wallpaperswitcher/ui/screens/MediaBrowseScreen.kt` | `06F8B2FDF0EB` | 33493 | 19:53:44 |
| `app/src/main/java/com/wallpaperswitcher/ui/screens/FavoritesScreen.kt` | `A91DDD783DEF` | 10123 | 19:35:27 |
| `app/src/main/java/com/wallpaperswitcher/ui/screens/RecentScreen.kt` | `766012E74CBC` | 23059 | 20:00:20 |
| `app/src/main/java/com/wallpaperswitcher/wallpaper/HoldPreviewController.kt` | `807B3A931A7D` | 13870 | 19:37:06 |
| `app/src/main/java/com/wallpaperswitcher/wallpaper/FloatingSwitchButton.kt` | `BC54113237A0` | 38578 | 19:38:55 |

评审期间 `RecentScreen.kt`（20:00）与 `WallpaperViewModel.kt`（19:58）被其他人改过：我读的是**改后**的版本，
`setImageAsWallpaper(..., onResult)` + 只在 `ok` 时 `promoteRecentEntry` 已经落地，所以"推回失败也置顶/弹已推回"
这一条**不再成立**，下面不列。

验证：`$env:JAVA_HOME="D:\Android Studio\jbr"; .\gradlew.bat :app:compileDebugKotlin --console=plain` → BUILD SUCCESSFUL（无编译错误）。

---

## 高

### 1. 预览的"拖开取消"永远不可能生效，松手一定确认并换壁纸
`FloatingSwitchButton.kt:671-678` ＋ `HoldPreviewController.kt:131-143`、`:43-47`

- 问题：取消阈值是 `max(96px, 12% 短边)`，但手指一移动超过 `DRAG_SLOP_PX = 8f`（**原始像素，约 2.7dp**）
  就先命中 `dragging = true`，于是 `else if (holdPreviewActive) previewController.onMove(...)` 这个分支再也进不去；
  `onMove` 是 `cancelArmed` 的唯一写入者，所以 `cancelArmed` 恒为 false。
- 为什么是真的：`finish()`(:146-154) 读到 `armed == false` → 走 `requestSwitchFromOutside("hold-preview")`，
  **一边把按钮窗口拖走一边把壁纸换掉**，而预览窗上写的是「拖开取消」（`strings.xml:615`）。
  单测 `HoldPreviewLogicTest` 只钉了阈值算术（喂进去的是 400px 这种距离），没有任何一个用例经过"移动 8px 之后
  onMove 还会不会被调用"，所以这条一直是绿的。
- 修法：`holdPreviewActive` 期间不要进拖动分支（把 :671 的拖动判定挪到 `if (!holdPreviewActive)` 之后，
  或在拖动分支里继续喂 `onMove`），并把 8px 这个 slop 换成 dp 化（≈8dp）的常量。

### 2. "预览 A、切过去 B"是真的：确认路径丢了分组作用域
`HoldPreviewController.kt:159` ＋ `LiveWallpaperService.kt:3654-3657`、`:3030-3043`、`:3199-3241`

- 问题：预览用 `NextPreview.nextForSlot(db, HOME_SLOT)`（`LiveWallpaperService.kt:2306-2309`）——它会
  `GroupSchedulePlan.nextGroupId()` 解析出"下一次该轮到的分组"并在**该分组内**挑图；而确认走
  `requestSwitchFromOutside("hold-preview")`，`"hold-preview"` 不在 `isUserTapSource` 里，于是
  `resolveUserTapGroup()` 返回 0 → `executeSwitch(groupId = 0)` 走**屏幕级**挑选（`LAST_IMAGE_ID` + 全部启用分组）。
- 为什么是真的：只要**任何一个分组带自己的间隔**（`GroupRhythmSection` 那套），两条挑选路径就不是同一个池子：
  预览显示分组 G 的下一张，切过去却是屏幕级随机/顺序的另一张。点击路径没有这个问题（`SOURCE_FLOATING` 是 user-tap source），
  所以这正是"悬浮按钮和引擎各算一次"的分叉——代码注释(:807-809)承诺的"不会出现预览 A、切过去 B"没有实现。
  `previewedId`(:77、:117) 注释说"确认时用它去重"，但全文件只有写、没有读，没有任何地方校验切的是不是预览那张。
- 修法：把 `"hold-preview"` 加进 `isUserTapSource`（让 `resolveUserTapGroup` 解析同一个分组），
  或把预览解析出的 groupId 一起传给 `requestSwitchFromOutside`，或直接把预览到的 id 用
  `requestTargetFromOutside(...)` 推给引擎。

---

## 中

### 3. 长按 420ms 之后、预览真正出现之前松手 → 这次按压什么都不做
`FloatingSwitchButton.kt:811-817`、`:719-725` ＋ `HoldPreviewController.kt:93-125`、`:146-150`

- 问题：`beginHoldPreview()` 在计时到点时就 `holdPreviewActive = true`，但预览要等 `pick()`（多次 SQL）
  ＋ `decodeSampled()` 跑完才 `visible = true`；在 `HOLD_PREVIEW_DELAY_MS = 420ms` 到"预览出现"之间的窗口里松手，
  `finish()` 看到 `hadPreview == false` 直接 `return false`，而 ACTION_UP 又在 :724 提前 `return true`，
  不会回落 `performSwitch(v)`。
- 为什么是真的：约 450–800ms 的按压（很常见的"按久一点"）既没有预览闪一下、也没有切壁纸、也没有任何提示。
  取图返回 null（例如所有分组被停用）、`decodeSampled` 失败/OOM（见第 4 条）、`addView` 被 ROM 拒绝
  （`HoldPreviewController.kt:211-217`）时同样如此——预览没出现就没有任何兜底动作。
- 修法：ACTION_UP 里 `finish()` 返回 false 且这次按压从头到尾没显示过预览时，回落 `performSwitch(v)`。

### 4. 预览的采样循环对"宽高比和屏幕差得远"的图无效 → 一次长按分配 ~48MB
`HoldPreviewController.kt:246-251`

- 问题：`while (outWidth/(sample*2) >= w && outHeight/(sample*2) >= h) sample *= 2` 用的是 `&&`：
  4000×3000 的照片在 1080×2400 屏幕上，高度方向第一步就不满足 → `sample` 停在 1 → `decodeStream` 出
  4000×3000 ARGB_8888 ≈ 48MB，与类注释(:234-236)"keeps a 4K wallpaper from allocating ~50MB here"正好相反。
- 为什么是真的：每次长按都可能来一次；真 OOM 时 `catch (t: Throwable)`(:256) 把它吞成 `decode failed`，
  表现为"预览有时候不出现"（并连锁触发第 3 条）。
- 修法：按面积或最长边设内存上限来算 `inSampleSize`（预览后面还有一层 scrim，稍微糊一点无所谓），
  不要让整张原图进内存。

### 5. 收藏状态按 uri 读、却按单行 id 写 → 跨分组的"取消收藏"点了没反应
`MediaBrowseScreen.kt:297`、`:303-309` ＋ `FavoritesScreen.kt:132` ＋ `Daos.kt:164-179` ＋ `WallpaperViewModel.kt:905-909`

- 问题：星标/列表状态是**按 uri**算的（`observeFavorites()` 用 `GROUP BY uri`；`favoriteUris` :279；
  `isFavorite = current.uri in favoriteUris`），写入却是 `setFavorite(image.id, …)` → `UPDATE … WHERE id = :id`，
  只改一行。
- 为什么是真的：同一个文件被导入两个分组是**预期**情况（导入只按分组去重，`observeFavorites` 的注释也这么写）。
  在 B 组的大图页，星标是亮的（因为 A 组那一行 `isFavorite=1`），双击取消收藏改的是 B 组那行
  （本来就是 0）→ Room 回来后 uri 仍在收藏集合里 → 星标不动、Toast 却说"已取消收藏"，连点多少次都没用；
  收藏页长按同一张（两行都被标星时）也会删不掉。
- 修法：写入改成按 uri 批量（`UPDATE wallpaper_images SET isFavorite = :favorite WHERE uri = :uri`），
  或让 VM 把 uri 解析成全部 id 一起更新。

---

## 低

### 6. 按下期间按钮被藏/被移除时，420ms 的计时器仍会弹出一个没人按着的预览窗
`FloatingSwitchButton.kt:364-368`、`:403-405`、`:839-841`

- 问题：`dismissPreviewIfAny()` 只关"已经显示出来"的预览，不清 `holdPreviewActive`、也不
  `mainHandler.removeCallbacks(holdRunnable)`。
- 为什么是真的：悬浮权限被撤销 → `removeOverlay()`、屏幕熄灭/壁纸被覆盖 → `hideOverlay()` 都发生在按下期间时，
  计时器照样触发 `beginHoldPreview()`，用一个 application context 的窗口把全屏预览（半透明、`FLAG_NOT_TOUCHABLE`）
  挂出来且没有手指抬起去收它，下一次 hide 之前一直留在屏幕上；若此时恰好来了 UP，还会真的切一次壁纸。
- 修法：`hideOverlay()/removeOverlay()` 里补 `mainHandler.removeCallbacks(holdRunnable); holdPreviewActive = false`。

### 7. `dispose()` 没有任何调用者，`scope` 永不取消
`HoldPreviewController.kt:185-188`、`:72-73`（另见只写不读的 `params` :73、`previewedId` :77）

- 问题：全仓库搜不到 `previewController.dispose()`，所以 `scope.cancel()` 从来没跑过。
- 为什么是真的：实际影响很小（`hide()` 会取消在飞的解码、窗口由 `hideOverlay()` 收掉），
  但这条"引擎销毁时收尾"的路径等于没接上，和 `params`/`previewedId` 两个只写字段一起看，
  说明收尾没人负责。
- 修法：在引擎销毁路径（或 `removeOverlay()`）里调一次 `previewController.dispose()`。

### 8. `recentForceSlot` 的说明与 ViewModel 现状不符
`RecentScreen.kt:107-109` ＋ `WallpaperViewModel.kt:2432-2440`

- 问题：注释说"ViewModel 只特判 LOCK，传 SLOT_HOME 和传 null 走的是同一条路径"，但 VM 现在对
  `SLOT_HOME` 也特判（`:2438` → `HOME`），两者**不等价**。
- 为什么是真的：对 BOTH 分组里的"桌面项"，传 null 会按分组目标把**锁屏也一起写**；下一个人照注释推理会得出错误结论。
  代码选择（桌面项传 null = 照分组目标）本身可以接受，是注释过期。
- 修法：把这段注释改成"桌面项刻意传 null（按分组目标），传 SLOT_HOME 会强制只写桌面"。

### 9. 收藏页首次打开会闪一帧"还没有收藏"
`FavoritesScreen.kt:112-117` ＋ `WallpaperViewModel.kt:901-902`

- 问题：`favorites` 是 `stateIn(..., emptyList())`，Room 第一次发值之前 Compose 已经按空列表渲染过。
- 为什么是真的：进程启动后第一次进收藏页，有收藏的用户会先看到「还没有收藏，去大图浏览里双击一下试试」再出网格（1–2 帧）。
- 修法：给这份 Flow 一个"是否已加载"标志（或初值用 null），未加载时显示 loading 而不是空状态。

---

## 明确未发现

- **主线程 I/O（第 1 项）**：未发现。三个页面里没有组合/点击回调直接读库、解码或走网络：
  取数全在 `collectAsStateWithLifecycle` / `LaunchedEffect`＋suspend（`recentShownRows` 内部 `withContext(IO)`），
  缩略图/大图解码在 Coil 自己的线程（`browseImageRequest`、`favoriteThumbnailRequest`、`buildRecentThumbnailRequest`
  只构造 `ImageRequest`，不触发解码），`setFavorite/deleteImage/setImageAsWallpaper` 都经 `viewModelScope` 且
  内部 `withContext(Dispatchers.IO)`（含 binder 往返 `isHomeLiveWallpaper` 与文件删除）。
- **崩溃面（第 3 项）**：未发现。索引只有 `clampBrowseIndex`/`wrapBrowseIndex`/`getOrNull`/`firstOrNull`，
  除零只有 `if (count > 0)` 包着的进度条与 `(position+1)/count`；四个文件里没有 `!!`；
  `MediaBrowseScreen:294` 用了 `images.getOrNull(index)`，`clampBrowseIndex(count<=0)` 返回 0 再由 `getOrNull` 兜住；
  手势层 `while(true)` 全部有 `break` 出口，`awaitEachGesture` 会在块返回后等手指抬起。
- **forceSlot 跨文件一致性（第 5 项）**：调用点用得都对——`MediaBrowseScreen:408/411` 明确传 `SLOT_HOME`/`SLOT_LOCK`
  （与 VM:2437-2438 一致，LOCK-only 分组点"设为桌面"确实写桌面）；`FavoritesScreen:130`、`GroupDetailScreen:578`
  传 null（按分组目标）；`RecentScreen:251` 锁屏行传 `SLOT_LOCK`、桌面行传 null。唯一的问题是第 8 条那句注释。
- **窗口/位图收尾**：`hide()` 会 `cancel` 解码任务、`removeViewImmediate` 后用 `overlay = null`
  防重复 recycle，`PreviewView.onDraw` 有 `bitmap.isRecycled` 保护；引擎 `onDestroy`
  （`LiveWallpaperService.kt:2109`）会 `hideOverlay()` → `dismissPreviewIfAny()`，残留只有第 6 条那个窄场景。

---

## 结论

**需修 5 处后再合并**（高 1–2、中 3–5）；低 4 条可随后处理。
