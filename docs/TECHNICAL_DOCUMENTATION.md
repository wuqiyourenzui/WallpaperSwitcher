# 壁纸切换 (WallpaperSwitcher) — 功能技术文档

> 版本：v1.1（versionCode 2）| 最后更新：2026-09-20（与当前源码实现同步：熄屏完全暂停、计时从亮屏开始、
> 无权限不删记录、播放时钟重锚、切换/循环不再黑帧、媒体库读取优化、自动扫描可靠化）

---

## 一、项目概述

壁纸切换是一款 Android 轻量级壁纸自动切换应用，支持图片、视频、GIF 三种媒体类型的壁纸管理。用户可通过分组管理壁纸素材，配合定时切换、解锁切换、双击切换等多种触发方式，实现桌面壁纸的自动轮换。

### 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin |
| UI 框架 | Jetpack Compose + Material 3 |
| 架构模式 | MVVM (ViewModel + StateFlow) |
| 数据库 | Room (SQLite) |
| 图片加载 | Coil 2.5 (含视频帧解码器) |
| 异步框架 | Kotlin Coroutines + Flow |
| 视频渲染 | MediaCodec + SurfaceTexture + EGL (OpenGL ES 2.0) |
| 最低 SDK | API 26 (Android 8.0) |
| 目标 SDK | API 34 (Android 14) |
| 构建工具 | Gradle KTS + KSP |

### 依赖清单

```
androidx.compose:compose-bom:2024.01.00
androidx.compose.material3:material3
androidx.compose.material:material-icons-extended
androidx.compose.animation:animation-core:1.6.0   # 固定版本，规避 M3 进度条 NoSuchMethodError
androidx.activity:activity-compose:1.8.2
androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0
androidx.lifecycle:lifecycle-runtime-compose:2.7.0
androidx.navigation:navigation-compose:2.7.6
androidx.room:room-runtime:2.6.1
androidx.room:room-ktx:2.6.1
androidx.work:work-runtime-ktx:2.9.0
io.coil-kt:coil-compose:2.5.0
io.coil-kt:coil-video:2.5.0
androidx.documentfile:documentfile:1.0.1
```

---

## 二、项目架构

```
com.wallpaperswitcher/
├── WallpaperSwitcherApp.kt          # Application 入口（通知渠道/默认设置/解锁接收器/Coil）
├── data/                             # 数据层
│   ├── Entities.kt                   # Room 实体定义
│   ├── Daos.kt                       # 数据访问对象 (DAO)
│   ├── AppDatabase.kt               # Room 数据库（版本 3，含 1→2、2→3 迁移）
│   └── SettingsKeys.kt              # 设置键常量
├── engine/                           # 静态壁纸引擎
│   ├── BitmapUtils.kt               # 位图工具类（二次解码 + 屏幕尺寸降采样）
│   ├── WallpaperApplier.kt          # 静态壁纸应用 (WallpaperManager，视频/GIF 取首帧)
│   └── MediaScanner.kt              # MediaStore 文件夹扫描 + SAF 递归扫描
├── receiver/                         # 广播接收器
│   ├── BootReceiver.kt              # 开机自启动
│   └── ScreenUnlockReceiver.kt      # 解锁切换触发 (USER_PRESENT)
├── service/                          # 前台服务
│   └── WallpaperSwitchService.kt    # 定时切换服务
├── wallpaper/                        # 动态壁纸引擎
│   ├── LiveWallpaperService.kt      # 动态壁纸服务 (核心：切换队列/预取/双击)
│   ├── WallpaperRenderer.kt         # EGL 渲染器 (图片/GIF/视频统一 GL 管线)
│   └── FloatingSwitchButton.kt      # 悬浮双击按钮（启动器不转发触摸时的兜底）
├── worker/                           # WorkManager 后台任务
│   └── FolderAutoScanWorker.kt      # 文件夹自动扫描
├── viewmodel/                        # 视图模型
│   └── WallpaperViewModel.kt        # 业务逻辑 + 状态管理
└── ui/                               # 界面层
    ├── MainActivity.kt               # 主 Activity（权限请求 + 引擎自愈）
    ├── WallpaperSwitcherApp.kt       # Compose 根组件 + 导航
    ├── theme/Theme.kt                # Material 3 主题（动态取色 + 自定义主色）
    └── screens/
        ├── HomeScreen.kt             # 首页 (分组列表 + 服务总开关)
        ├── GroupDetailScreen.kt      # 分组详情 (媒体网格 + 批量操作 + 快速滚动)
        └── SettingsScreen.kt         # 设置页
```

---

## 三、数据模型

### 3.1 数据库表结构

#### wallpaper_groups (壁纸分组表)

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long (PK, 自增) | 分组 ID |
| name | String | 分组名称 |
| isEnabled | Boolean | 是否启用 (默认 true) |
| createdAt | Long | 创建时间戳 |
| type | String | 兼容字段（历史版本区分 IMAGE/VIDEO），当前分组已混合存放，不再参与切换逻辑 |

#### wallpaper_images (壁纸图片表)

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long (PK, 自增) | 图片 ID |
| groupId | Long (FK → wallpaper_groups.id) | 所属分组 ID |
| uri | String | 媒体文件 URI (SAF content:// 或 MediaStore) |
| displayName | String | 显示名称 (文件名) |
| mediaType | String | 媒体类型: "IMAGE" / "VIDEO" / "GIF" |
| isFromFolder | Boolean | 是否来自文件夹导入 |
| folderPath | String | 来源文件夹路径 |
| addedAt | Long | 添加时间戳 |
| width / height | Int | 媒体像素尺寸（0 = 未知）。扫描 MediaStore 时顺带取回，否则在首次解码后写回 |
| rotationDegrees | Int | EXIF 旋转角度（0/90/180/270）。有了宽高+角度，解码只需 **1 次**媒体库读取 |

**外键约束**：`groupId` → `wallpaper_groups.id`，级联删除 (ON DELETE CASCADE)。
**索引**：`groupId` 字段建立索引以加速查询。

#### app_settings (设置表)

| 字段 | 类型 | 说明 |
|------|------|------|
| key | String (PK) | 设置键名 |
| value | String | 设置值 (字符串存储) |

#### shuffle_shown (洗牌牌堆表，schema v6 起)

| 字段 | 类型 | 说明 |
|------|------|------|
| slot | String (PK) | `HOME` / `LOCK`（见 `WallpaperTarget.SLOT_*`） |
| mediaId | Long (PK) | 本趟已展示过的媒体 ID |

**为什么单独建表**：洗牌进度原本存在 `app_settings.shuffle_shown_ids`（逗号分隔的 ID 串）。
牌堆随媒体库增长（3.8 万张约 230KB），而每次切换都要把这串**整体**读出、解析、重建再写回：
CPU 与闪存写入都随库大小线性上升，而且写 `app_settings` 会让设置页的 20 路 Room Flow 全部失效重查。
现在「已展示」= 一行记录（`INSERT OR IGNORE`，恒定大小），重开一趟 = 一次带索引的 `DELETE`。

### 3.2 设置键定义

| 键名 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| service_enabled | Boolean | false | 自动切换服务是否开启 |
| double_tap_enabled | Boolean | true | 双击切换是否开启 |
| unlock_switch_enabled | Boolean | false | 解锁切换是否开启 |
| floating_button_enabled | Boolean | false | 悬浮双击按钮是否开启 |
| floating_button_color | String | "#1E88E5" | 悬浮按钮颜色 |
| floating_button_alpha | Long | 10 | 悬浮按钮不透明度百分比 (5..100) |
| last_image_id | Long | 0 | 最后显示的媒体 ID |
| sequential_index | Long | 0 | 顺序模式当前索引 |
| global_interval_ms | Long | 60000 | 切换间隔 (毫秒，下限 10 秒) |
| timer_last_switch_wall_ms | Long | 0 | 当前间隔的起点（上次定时切换 / 亮屏时刻的墙钟时间）。熄屏期间不计时：亮屏时会被前移到"现在"，进程被杀重启也能续算 |
| global_switch_mode | String | "RANDOM" | 切换模式: RANDOM/SEQUENTIAL/SHUFFLE |
| last_image_id_lock | Long | 0 | 锁屏槽位的顺序游标（桌面用 last_image_id） |
| global_scale_mode | String | "FIT" | 缩放模式: FILL/FIT/STRETCH |
| rotate_mismatch_enabled | Boolean | true | 自动旋转适配开关（填充/拉伸时方向不符的媒体旋转 90°） |
| rotate_mismatch_cw | Boolean | true | 旋转方向：true = 顺时针 90°，false = 逆时针 90° |
| clarity_mode | String | "on" | 清晰度增强开关: 只有 "off" 是关（历史值 auto/super/strong/缺失都按开处理，老安装默认行为不变） |
| enhance_algo | String | "fsr1" | 超分算法（清晰度增强打开时生效）: fsr1（EASU/RCAS）/ anime4k（Original x2） |
| switch_fade_enabled | Boolean | true | 切换淡入动画开关 |
| auto_scan_enabled | Boolean | false | 文件夹自动扫描开关 |
| auto_scan_interval_ms | Long | 24h | 自动扫描间隔（下限 15 分钟） |
| shuffle_all_count | Long | 0 | 洗牌模式媒体总数快照（引擎日志用；已展示集合见 `shuffle_shown` 表） |
| theme_color | String | "" | 主题色 (空 = 跟随系统) |

### 3.3 数据库版本迁移

- **版本 1 → 2**：`wallpaper_images` 表新增 `mediaType`、`isFromFolder`、`folderPath` 三个字段。
- **版本 2 → 3**：`wallpaper_groups` 表新增 `type` 字段。
- **版本 3 → 4**：`wallpaper_groups` 表新增 `target` 字段（默认 `'BOTH'`，见 4.9.1 双屏设置）。
- **版本 4 → 5**：`wallpaper_images` 表新增 `width`、`height`、`rotationDegrees`（解码元数据，默认 0；
  见 4.9.7「大幅减少访问照片和视频」——老数据在扫描或首次解码时自动补齐）。
- **版本 5 → 6**：新建 `shuffle_shown` 表（见 3.1），并删除遗留的 `shuffle_shown_ids` /
  `shuffle_shown_ids_lock`（两个切换路径都改用新表，留着只会让设置页继续观察死数据）。
  代价：升级后当前这趟洗牌牌堆从头开始（最多重复几张图），不丢任何媒体数据。
- 使用显式 `Migration` 对象迁移，无 `fallbackToDestructiveMigration`。

---

## 四、功能模块详解

### 4.1 分组管理

**功能**：
- 创建、删除壁纸分组
- 每个分组独立启用/禁用
- 分组内的图片、视频、GIF 混合存放

**实现**：
- `WallpaperGroupDao` 提供 CRUD 操作
- 分组列表通过 `Flow<List<WallpaperGroup>>` 实时响应式更新
- 删除分组时，Room 外键级联删除自动清理关联图片

### 4.2 壁纸添加

**支持的添加方式**：

| 方式 | 实现 | 说明 |
|------|------|------|
| 单张选择 | `ActivityResultContracts.OpenDocument` | SAF 文件选择器 |
| 多张选择 | `ActivityResultContracts.OpenMultipleDocuments` | 批量选择 |
| 系统文件夹 | `ActivityResultContracts.OpenDocumentTree` + DocumentFile 递归扫描 | 持久化 URI 权限 |
| 扫描到的文件夹 | MediaStore 文件夹列表（多选导入） | 缓存扫描结果，对话框秒开 |

**支持的媒体格式**：JPG、JPEG、PNG、WebP、BMP、GIF、MP4、MKV、WebM、AVI、MOV、3GP。

**媒体类型检测**：优先取 `ContentResolver.getType()`（MIME）判断，其次 `OpenableColumns.DISPLAY_NAME`
（回退 `DocumentFile.name`、URI 末段）解析显示名，最后才用扩展名兜底。

> **为什么不能只看扩展名**：非小米机型的 SAF 文件选择器（下载/云盘等 DocumentsProvider）
> 返回的显示名可能完全没有扩展名（例如 `msf:1000000024`），旧版本据此把视频存成 `IMAGE`，
> 预览与动态壁纸都按图片解码 → **视频黑屏**。现在 `resolveMediaType()` 按 MIME 归类
> （`video/*` → VIDEO、`image/gif` → GIF、`image/*` → IMAGE），单张与多张添加共用同一实现。
>
> **旧数据自愈**：引擎遇到 `IMAGE` 却解码失败时，会查询真实 MIME，把该行 `mediaType`
> 就地修正为 VIDEO/GIF 并重试同一媒体（`repairMisTypedMedia()`），静态应用
> `WallpaperApplier` 也有同样的 MIME 兜底判断，用户无需删除重新添加。

**导入优化**：
- 递归扫描子文件夹，`isActive` 支持取消
- 每 100 行一批 `insertAll`（低于旧 SQLite 999 绑定变量上限）
- 导入进度实时更新，`yield()` 让出主线程

### 4.3 壁纸切换

#### 4.3.1 切换模式

| 模式 | 枚举值 | 算法 |
|------|--------|------|
| 随机 | `RANDOM` | 从启用分组中随机选取，排除当前显示的媒体 |
| 顺序 | `SEQUENTIAL` | 按 id 顺序依次切换，使用 `sequential_index` 记录位置 |
| 洗牌 | `SHUFFLE` | 随机不重复，全部显示完后重新洗牌，状态持久化到数据库 |

**随机选取优化**：不使用 `ORDER BY RANDOM()`（大库全表排序慢），而是 `COUNT + 随机 OFFSET` 快速定位；OFFSET 落在被删行间隙时回退到 `ORDER BY RANDOM()` 变体。

**洗牌选取修正**：洗牌不再用"随机采样 + 最多重试 10 次"（旧写法在 6 个媒体的牌堆里约 16% 的概率重试失败，于是提前重置牌堆、在还有未显示媒体时重复播放）。现在由 `getEnabledIds(slot)` 取该屏全部可用 id，再交给纯函数 `SwitchPicking.pickUnseen()` 在内存里过滤掉"已显示 + 当前屏上的那张"并随机取一张（`MediaPick.shuffleUnseen()`）。不再使用 `id NOT IN (:shownIds)`，所以分组媒体数超过 SQLite 绑定变量上限（老设备 999）时也不会让整次切换失败；一副牌仍然完整播完才重洗。

#### 4.3.2 切换触发方式

| 触发方式 | 实现组件 | 说明 |
|----------|----------|------|
| 定时切换 | `WallpaperSwitchService` | 前台服务定时发送 ACTION_SWITCH 广播 |
| 双击切换 | 引擎自定义 DOWN/UP 双击检测 | 300ms 窗口 + 40dp 容差 |
| 悬浮按钮双击 | `FloatingSwitchButton` | Android 16/17 部分启动器不转发触摸时的兜底 |
| 解锁切换 | `ScreenUnlockReceiver` | 监听 `ACTION_USER_PRESENT` 广播 |
| 手动切换 | `switchNow()` | App 内"立即切换壁纸" |
| 设置壁纸 | `setImageAsWallpaper()` | 指定媒体设为壁纸（携带 target_id） |

#### 4.3.3 切换核心流程（动态引擎）

```
触发源 (定时/双击/解锁/手动/恢复)
    ↓
LiveWallpaperService.requestSwitch() → 串行 Channel(8)
    ↓
consumeSwitches() 单消费者逐条执行
    ↓
executeSwitch(source, targetId)
    ├─ 熄屏跳过（省电）
    ├─ targetId：读取目标媒体，锚定顺序游标
    ├─ 无 targetId：优先消费预取缓存，否则 pickNextImage() 按模式选取
    ├─ 更新 LAST_IMAGE_ID
    ├─ 跳过 failedMediaIds 黑名单媒体（恢复切换时）
    └─ 按 mediaType 分发：
        ├── IMAGE → 加载位图 → stopVideoAndRender()（GL 原子过渡）
        ├── VIDEO → stopVideo → startVideo()（MediaCodec 解码）
        └── GIF   → playGif()（ImageDecoder + AnimatedImageDrawable）
    ↓
maybePrefetchNext()：后台解码下一张图片（仅 IMAGE）
```

**切换队列**：所有触发源统一进入容量 8 的串行 Channel，单消费者顺序执行。切换中的新触发排队等待，不会丢失；定时 tick 在队列满时直接丢弃（下一个 interval 自然补上），用户触发（双击/解锁/手动/恢复）永不丢弃。

**失败恢复**：
- 图片/视频/GIF 解码失败 → 加入 `failedMediaIds` 黑名单 → 立即请求恢复切换（换一个媒体）
- 视频 15 秒内无首帧 / 12 秒无新帧 → 健康监控触发恢复
- 连续 5 次恢复失败后暂停自动恢复，避免死循环耗电

**预取 (Prefetch)**：切换完成后在后台解码"下一张"图片缓存起来，快速连切/解锁/下个定时 tick 近零延迟显示。只缓存 IMAGE；SEQUENTIAL 模式预取遇到视频/GIF 时不推进游标（否则会跳过它们），SHUFFLE 模式预取视频/GIF 时不记入"已显示"集合。

### 4.4 缩放模式

| 模式 | 枚举值 | 说明 |
|------|--------|------|
| 填充 | `FILL` | 裁剪多余部分，填满屏幕，保持比例 |
| 适应 | `FIT` | 完整显示媒体，可能有黑边 |
| 拉伸 | `STRETCH` | 强制拉伸填满屏幕，不保持比例 |

**实现**：EGL 渲染线程计算四边形顶点（`computeQuad`/`computeVideoQuad`），通过着色器绘制。FIT 留黑边区域用 1x1 黑色纹理铺底，防止残留上一帧画面。

### 4.4.1 自动旋转适配（填充/拉伸 + 屏幕方向不符）

横图在竖屏（或竖图在横屏）填充/拉伸时只显示中间一条，看不清内容。开关打开后：

1. **图片**：解码阶段 `BitmapUtils.loadBitmap(..., rotateMismatch, rotateClockwise)`
   在 `rotateForFill()` 里按 `Matrix.postRotate(±90°)` 先转正，再交给 FILL/STRETCH 计算四边形。
2. **GIF**：`rotateBitmap90(bmp, clockwise)` 在每帧绘制前旋转（画布 `cv.rotate(±90°)`）。
3. **视频**：不预转帧（解码管线代价高），改为在渲染端用 `EXTRA_ROTATE_90_CW_MATRIX` /
   `EXTRA_ROTATE_90_CCW_MATRIX` 与解码器 texMatrix 相乘，并交换四边形宽高，
   与图片保持同一旋转方向。
4. 切换方向（顺时针/逆时针）时：`applyRotateSettingsLive()` 清空预取缓存、重置
   `lastDisplayedId` 并对当前媒体重解码重绘；GIF 重启、视频重算四边形。

**校验方式**（模拟器上 screencap 抓不到动态壁纸层时的替代手段）：渲染线程
`glReadPixels` 读取画面四角，4 色象限测试图在顺时针下读出
`TL=蓝 TR=绿 BL=黄 BR=红`，逆时针下为镜像的
`TL=红 TR=黄 BL=绿 BR=蓝`。

### 4.5 视频壁纸（MediaCodec + EGL）

**架构**：

```
解码线程 (VideoDecode)                      渲染线程 (WallpaperRenderer, HandlerThread)
MediaExtractor → 读取样本                     EGL 上下文（跨 Surface 重建存活）
MediaCodec      → 硬件解码                     SurfaceTexture 接收帧
                 ↓                            updateTexImage → renderVideoFrame
              releaseOutputBuffer(render=true)  → 纹理四边形 → eglSwapBuffers
```

**详细流程**：
1. `openAssetFileDescriptor` 在守护线程打开（15 秒超时，云盘/SAF 卡住不阻塞切换），文件描述符跨循环复用。
2. MediaExtractor 选择视频轨，`setDataSource(fd, offset, length)` 正确处理非零偏移容器。
3. 解码尺寸按屏幕分辨率封顶：FIT 模式按**实际显示尺寸**（letterbox 后的适配尺寸 ×1.25 余量，横屏视频在竖屏手机上不再按屏幕最大边过度解码），FILL/STRETCH 模式 ≥1920 下限，避免 4K/8K 全尺寸解码浪费功耗。
4. 内层解码循环：批量预填输入缓冲（云盘慢读不卡顿），输出帧经 SurfaceTexture 渲染；渲染端按源帧率呈现，仅保留 16ms（≈60fps）的呈现下限（早期版本的 33ms ≈30fps 节流已移除，见 §8.5）。
5. 播放到 EOF 后干净重启 codec（循环播放），避免原地 flush 导致硬件解码器崩溃。
6. 代际保护（`videoGeneration`）：被新视频取代的旧解码线程退出时绝不清理新视频的资源。
7. 熄屏 / 壁纸不可见时**完全暂停**：解码循环不再取出任何帧（输出队列自然填满，解码器自行停住，CPU/GPU 都不再消耗），
   画面停在当前帧，重新可见时从这一帧按原速继续（见 §4.9.7 的播放时钟重锚；旧行为是继续以约 1fps 放"幻灯"）。

**健康监控**：引擎侧 watchdog 检测视频 15 秒无首帧 / 12 秒无新帧 → 触发恢复切换。

### 4.6 GIF 壁纸

**实现**：
- API 28+：`ImageDecoder` + `AnimatedImageDrawable`（`repeatCount = -1` 无限循环）
- 帧栅格化运行在独立 `HandlerThread`（"GifRender"），不占主线程；双缓冲 (ping-pong) 防止 GL 上传读到半绘制帧
- 帧率上限 20fps（50ms/帧）；熄屏 / 壁纸不可见时**完全暂停**（`AnimatedImageDrawable.stop()`，恢复时 `start()` 从原帧继续）
- 渲染尺寸按屏幕封顶（FILL/STRETCH 可放大到 4096 上限）
- 解码失败（动画 + 静态帧兜底均失败）→ `onGifFailed()` 黑名单 + 恢复切换

### 4.7 图片壁纸

**实现**：
- `BitmapUtils.loadBitmap()` 两次解码：先 `inJustDecodeBounds` 读尺寸，再按屏幕区域解码
- 采样率取"解码后长边 ≥ 显示区域的 75%"的最大 2 的幂：GPU 最多放大 ~1.33×，由锐化着色器覆盖，典型图片解码像素仅为旧"绝不放大"规则的约 1/4；4096px 内存上限兜底
- **EXIF 方向处理**：`BitmapFactory` 不读 EXIF，解码后用 `android.media.ExifInterface` 读取方向并旋转，竖拍照片不再横躺
- `ARGB_8888` 全色深；渲染线程 `texImage2D` 上传；mipmap 三线性过滤仅在**缩小显示时**生成（放大/1:1 显示时跳过，省一次整纹理 GPU 扫描；GIF 帧始终不生成以省电）
- 低清媒体放大时着色器 unsharp mask 增强（清晰度模式：auto=1.25x / off=0 / strong=1.6x），`uSharp=0` 时与原始采样完全一致

### 4.8 切换过渡动画

- 切换完成后黑色遮罩 150ms 淡入（6 步 × 25ms），图片/视频/GIF 通用
- 快速连续切换（间隔 <1.5s，如双击连打）自动跳过淡入，避免黑闪
- 可设置关闭 (`switch_fade_enabled`)

### 4.9 设置壁纸

**点击图片即设置** (`setAsLiveWallpaper`)：分组里单击媒体即进入系统动态壁纸界面
（`ACTION_CHANGE_LIVE_WALLPAPER`），**每次**都弹出系统确认页；引擎已运行时同时广播
`ACTION_SWITCH` 让预览先显示该媒体。

**菜单「设为壁纸」** (`setImageAsWallpaper`，先弹预览确认)：
1. 保存目标媒体 ID 到数据库 (`LAST_IMAGE_ID`)，并锚定顺序模式游标到该媒体之后
2. 写入位置由该媒体所在分组的 `target` 决定（见 4.9.1）：桌面 / 锁屏 / 两者
3. 桌面：引擎已运行 → 广播 `ACTION_SWITCH`（携带 `EXTRA_TARGET_ID`）；否则静态写入 `FLAG_SYSTEM`
4. 锁屏：始终通过 `WallpaperManager.setBitmap(..., FLAG_LOCK)` 静态写入（动态壁纸无法在锁屏渲染另一张图）
5. 需要静态写入的视频/GIF 用首帧并提示；全部静态应用共用 `staticApplyInProgress` 守卫，杜绝并发 `setBitmap`

#### 4.9.1 桌面 / 锁屏分开设置（Paperize 双屏思路）

参考 GitHub `Anthonyy232/Paperize` 的 *Dual Screen Support（桌面和锁屏可选择相同或不同相册）*：

- **数据**：`wallpaper_groups.target`（`HOME` / `LOCK` / `BOTH`，默认 `BOTH`；v3→v4 迁移新增）。
  分组详情页的「应用位置」chips 修改，首页分组卡片显示当前值。
- **取图**：所有跨分组查询都带 `slot` 参数，只从
  `target IN ('BOTH', :slot)` 的启用分组里取；桌面用 `SLOT_HOME`（动态壁纸引擎也是桌面，所以引擎用它），
  锁屏用 `SLOT_LOCK`。
- **独立轮换**：桌面沿用 `last_image_id` / `shuffle_shown_ids`，锁屏新增
  `last_image_id_lock` / `shuffle_shown_ids_lock` / `shuffle_all_count_lock`，两块屏的顺序、洗牌牌堆互不影响。
- **一次 tick**（`WallpaperSwitchService.runStaticTick(context, slot, which)`）只针对一个屏幕取图并写入：
  桌面 tick 不受锁屏影响，锁屏 tick 也不受桌面影响。
- **兼容**：MIUI 等拒绝单独锁屏壁纸的机型，`setBitmap(FLAG_LOCK)` 失败只记日志，不影响桌面切换。

#### 4.9.2 锁屏独立的定时切换

锁屏只保留一个独立触发：定时（设置 →「锁屏切换（独立于桌面）」）。桌面的定时、双击、解锁
切换都不会再动锁屏。

| 设置键 | 默认 | 行为 |
|--------|------|------|
| `lock_timer_enabled` | true | 锁屏定时开关；关闭后 `runLockSwitchLoop()` 立即退出 |
| `lock_interval_ms` | 60000 | 锁屏自己的间隔（与 `global_interval_ms` 无关） |
| `lock_timer_last_switch_wall_ms` | 0 | 锁屏调度锚点（与桌面的 `timer_last_switch_wall_ms` 分开） |
| `manual_pick_hold_until` | 0 | 手动设置壁纸后的保护期（墙钟 ms，15 秒）。**只作用于桌面定时**：系统动态壁纸确认页还开着时，保证预览不会被下一 tick 顶掉；锁屏定时不受它影响 |

`runLockSwitchLoop()` 与桌面的 `runSwitchLoop()` 并行运行，各自读自己的开关/间隔/锚点，
熄屏时都暂停、亮屏时**两个间隔都从亮屏时刻重新开始计时**；桌面循环永不写锁屏槽位，锁屏循环也永不写桌面槽位。

#### 4.9.3 手动设置 vs 定时 / 动态壁纸 vs 分组位置

- **手动优先（按屏生效）**：`setImageAsWallpaper()` 与 `setAsLiveWallpaper()` 会
  **把对应屏的调度锚点前移到当前时间**（`TIMER_LAST_SWITCH_WALL_MS` / `LOCK_TIMER_LAST_SWITCH_WALL_MS`），
  所以手动设的那张至少完整显示一个间隔；分组含桌面时额外设置 15 秒
  `manual_pick_hold_until`，只让**桌面**定时让路（系统动态壁纸确认页用）。
  锁屏定时**从不被保护期阻塞**——早期版本两个定时器共用一个 60 秒保护期，用户连续手动设锁屏图时保护期被不断续期，
  锁屏定时看上去"完全不生效"，现已改为上面的按屏策略。
- **分组位置优先于系统对话框**：系统对话框无法拦截（它是系统 Activity，且总是填满 system 槽位，
  选「主屏幕和锁定屏幕」时还会占掉 lock 槽位）。引擎 `onCreate()` 后 3 秒调用
  `WallpaperSwitchService.enforceSlotsAfterLiveApply()` 按分组位置校正（走同一把
  `staticApplyInProgress` 守卫）：
  | 当前媒体的分组位置 | 校正动作 |
  |---|---|
  | 两者 | 把这张图镜像写入 `FLAG_LOCK`（即使用户在系统界面只选了「主屏幕」，两边也都会生效） |
  | 桌面（且存在锁屏分组） | 用锁屏分组重新写一次锁屏槽位 |
  | 桌面（且没有锁屏分组） | 用 `last_image_id_lock` 恢复上一次的锁屏壁纸；没有记录则只记日志 |
  | 锁屏 | 不会走到这里：`setAsLiveWallpaper()` 对锁屏分组直接静态写锁屏，不打开系统界面 |

  **只在实际需要时写锁屏**（`enforceSlotsAfterLiveApply`）：
  - 预览引擎直接跳过（`Engine.isPreview()`）——早期版本连"打开系统预览页"都会重写一次锁屏；
  - 记录最近一次锁屏写入（`last_lock_write_id` / `last_lock_write_at`），5 分钟内同一张不再重复写；
    桌面分组的当前图若是那张刚写过的锁屏图，记 `Lock screen already restored recently` 后直接返回。
    修复前设备日志里出现过 2 分钟重复写同一张锁屏图 4 次。

#### 4.9.4 两块屏的图片互不串图

- **任何分组都能设为动态壁纸**：`setAsLiveWallpaper()` 对所有分组都打开系统动态壁纸界面
  （桌面/锁屏/两者一致）。设置完成后由 `enforceSlotsAfterLiveApply()` 按分组位置决定两块屏各自显示
  什么：当前媒体的分组含锁屏 → 把这张图写到锁屏槽位（桌面继续轮换"含桌面"分组的图）；
  只含桌面 → 锁屏用上一次的锁屏图/锁屏分组的图恢复。
- **引擎只渲染桌面分组的图**：`drawCurrentImage()` 解析 `last_image_id` 后校验该媒体的分组——
  分组被删除/禁用，或 `WallpaperTarget.suitsSlot(SLOT_HOME)` 为 false（锁屏专用分组）时，记录
  "Last shown media is not for the home screen, picking a home one" 并改取桌面分组的图片，
  同时把 `last_image_id` 修正过来。旧版本遗留的"锁屏图跑上桌面"状态因此在下次引擎启动/旋转重绘时自愈。
- 单测 `WallpaperTargetTest` 覆盖 `suitsSlot()`：锁屏专用分组永远不会被判为可用于桌面槽位。

#### 4.9.5 锁屏只接受静态图片

锁屏由系统用静态壁纸渲染，视频/GIF 放到锁屏只会冻住首帧，所以锁屏槽位一律跳过动态媒体：

- **取图 SQL 层拦截**：所有跨分组查询都带 `AND (:slot != 'LOCK' OR mediaType = 'IMAGE')`，
  `SLOT_LOCK` 时只可能取到 `mediaType = 'IMAGE'` 的媒体。锁屏分组里只有视频/GIF 时
  `countByEnabledGroups(SLOT_LOCK) == 0`，`runLockSwitchLoop()` 停在空闲轮询，锁屏保持原样。
- **逐条设置也拦截**：菜单「设为壁纸」对锁屏槽位遇到视频/GIF 时提示"锁屏不支持视频/GIF，已自动跳过"；
  `enforceSlotsAfterLiveApply()` 里若当前媒体是视频/GIF 且分组含锁屏，则不写锁屏，改为回退到
  锁屏分组中的静态图（取不到就什么都不做，日志记 `... skipped for the lock screen`）。
- **服务自愈**：`ensureRunning()` 与 `BootReceiver` 现在以
  `service_enabled || lock_timer_enabled` 判断是否需要服务，避免"关掉桌面定时后锁屏定时在重启/更新后失效"。

#### 4.9.6 审查修复（互不干涉 & 静态模式补齐）

- **静态模式的解锁切换**：引擎没运行时不再直接放弃，而是调用
  `WallpaperSwitchService.applyStaticTickNow(context, SLOT_HOME, FLAG_SYSTEM)`
  静态切换桌面（同一把 `staticApplyInProgress` 守卫），并同样通知定时器"这次解锁已处理"。
- **手动切换重新计时**：`switchNow()` 先把 `timer_last_switch_wall_ms` 前移到当前时间，
  手动换完之后不会再被紧接着的定时 tick 顶掉。
- **没有桌面分组时**：首页新增提示条（桌面会显示占位图，请把某个分组的应用位置设为桌面/两者）；
  定时 tick 没有可切换媒体时也只记 debug 日志，不再每个间隔刷一条 error。
- **锁屏定时即时生效**：改分组应用位置/启用状态时调用 `WallpaperSwitchService.poke()` 唤醒两个循环，
  不必等锁屏循环的空闲轮询（15 秒）。
- **删除媒体时同步清理锁屏游标**（`last_image_id_lock`）与锁屏写入去重记录（`last_lock_write_id`）。
- **前台服务通知**：Android 13+ 首次启动申请 `POST_NOTIFICATIONS`（之前声明了但从未申请）。
- **首页状态更准确**：卡片标题按两个定时器显示"桌面 + 锁屏运行中 / 桌面运行中 / 锁屏运行中 / 已停止"；
  "立即切换壁纸"在任一计时器开启时都可用。
- **性能**：自动扫描 Worker 现在每组只读一次 URI 集合（原来每个文件夹都全量读一次）；
  引擎销毁时写洗牌状态改为异步 IO，不再在主线程 `runBlocking`。
- **失效代码清理**：删除 14 个从未调用的 DAO 查询、`MediaType` 枚举、`SEQUENTIAL_INDEX` 键、
  `FloatingSwitchButton.isShowing`、`LiveWallpaperService.destRect`、`WallpaperViewModel.createGroup`、
  未使用的 `android.os.Build` import 与 `WAKE_LOCK` 权限；清晰度映射收敛成单一 `clarityStrength()`。
  （`wallpaper_groups.type` 列保留：Room 会校验列集合，物理删列需要重建父表，会通过外键级联删掉
  全部媒体行，因此只标注为 legacy。）

**引擎运行状态检测**：`LiveWallpaperService.engineRunning` 静态标志，`onCreate` 置 true、`onDestroy` 清 false，仅当前活动引擎有权清标志（壁纸重新应用时旧引擎的 onDestroy 不会误清）。

#### 4.9.7 审查修复（逻辑 / 边界 / 死代码）

- **手动设置锁屏后不再被覆盖**：`applyStaticWallpaper()` 成功后按槽位写回
  `last_image_id`（桌面）/ `last_image_id_lock` + `last_lock_write_id`（锁屏）。
  此前手动设的锁屏图立刻被遗忘：锁屏定时从旧游标继续，下一次动态壁纸应用时
  `enforceSlotsLocked()` 还会用旧的 `last_lock_write_id` 把用户刚设的图覆盖回去。
- **用户操作不再被静默丢弃**：`applyStaticWallpaper()` 与 `enforceSlotsAfterLiveApply()`
  改用 `withStaticApply(STATIC_APPLY_WAIT_MAX_MS)` 有界等待共享守卫（原 `applyStaticWallpaper`
  是 0 等待，撞上定时写入就失败）；返回值改为 `StaticApplyOutcome{APPLIED,BUSY,FAILED}`，
  首页"设为壁纸"能区分"系统正在写入壁纸，请稍后重试"与"无法读取该媒体文件"。
- **锁屏 tick 抢锁**：锁屏循环改为按 250ms 步进最多等待 6 秒（原为撞锁即 `delay(2000)`
  重试，连续碰撞会把切换推迟到设定间隔之后）。
- **洗牌不再有 SQL 变量上限**：`getRandomUnseenFromEnabledGroups`（`id NOT IN (:shownIds)`）
  换成 `getEnabledIds(slot)` + 新增纯函数 `SwitchPicking.pickUnseen()` / `MediaPick.shuffleUnseen()`
  的内存过滤。分组媒体数超过 SQLite 绑定变量上限（老设备 999）时，原查询会抛异常导致整次
  切换失败；现在牌堆语义不变（不重复、轮完才重置）。
- **旋转后必定重解码**：`onSurfaceChanged()` 现在**不看可见性**就置 `pendingOrientationRedraw`
  并清预取缓存，`onVisibilityChanged(true)` 也会因该标志强制重绘。此前"在别的应用里旋转再回桌面"
  不会重新解码：FILL/STRETCH 的 90° 方向是解码时按旧屏幕方向决定的，回来后会保持错误方向/较糊。
- **失效媒体重试有效**：`failedMediaIds` 的候选重试循环每轮都排除上一个候选（原来只有成功时
  才更新 `media`，5 次查询的排除条件完全相同，4 次是纯重复读取）。
- **解锁切换带确认**：`ScreenUnlockReceiver` 优先走 `LiveWallpaperService.requestSwitchFromOutside()`，
  返回 true（真的有引擎实例接收）才通知定时器"这次解锁已处理"；`engineRunning` 是过期标志时
  仍回落到广播，行为与之前一致。
- **启动与引擎回调的异常兜底**：引擎 `onCreate` 的数据库/触摸初始化加 try/catch（数据库打不开时
  只记日志、保留 `engineRunning` 以免定时写入把动态壁纸覆盖成静态图）；`Application.onCreate`
  的通知渠道/接收器/Coil 初始化改为 `runCatching`，任一失败只降级该功能而不再让每次启动崩溃。
- **提示一致性**：`HintOverlay` 的兜底长 Toast 与气泡一起在 `dismiss()`（回前台）时取消；
  删除首页/`MainActivity` 重复的"引擎未运行"提示（保留首页常驻卡片）。
- **重复逻辑收敛**：媒体类型判定集中到 `MediaTypes`（`isSupportedName` / `fromName` /
  `fromMimeOrName` / `repairFromMime` / `isMotion`），"文件是否已失效"集中到 `MediaProbe.isGone()`；
  `MediaScanner.isSupportedMedia/detectMediaType` 保留为薄包装，`AppLog` 去掉重复的二次 flush。
- **不再把"没权限"当成"文件已删除"（数据丢失级修复）**：`MediaProbe.isGone()` 现在只在
  **文件确实不存在**（`FileNotFoundException` 且消息不像权限拒绝）时返回 true；
  `SecurityException`（如 `has no access to content://media/... forWrite = false`，即 READ_MEDIA_*
  未授予）以及消息含 "permission/denied/forWrite" 的 `FileNotFoundException` 一律返回 false，
  并打印一次可操作提示（"缺少照片/视频读取权限…媒体条目已保留（不会删除）"）。
  旧实现在平板未授予照片权限时，每 5 秒把一条**仍在相册里**的媒体当"文件已删除"删掉，
  实测已累计删除 92 条（日志 87 行 SecurityException）。日志报告的 header 也新增
  `read_media_permission=...`，便于从导出日志直接判断。
- **视频不再"一开始快放"（播放时钟不与墙钟赛跑）**：解码循环按帧时间戳推进，但**播放时钟原本只在每个播放
  段（pass）开始时锚定一次**。壁纸不可见时（熄屏 / 其他应用在前台 / 系统动态壁纸对话框）循环被节流到约 1fps，
  墙钟却继续走，于是积累出"欠账"（平板日志实证：`Video pass: 11 frames presented in 9864ms (1.1 fps)` /
  `22 frames presented in 21373ms`）。一旦壁纸重新可见，这些帧全部"迟到"，循环就把它们背靠背地放出去追赶
  （受 16ms 呈现下限约束，最多约 60fps），观感就是"刚开始很快、一会儿才恢复正常"。
  现在迟到超过 `maxPlaybackLagNs`（500ms）时**重锚播放时钟**（`passStartNs = now - ptsOffset`）：
  视频从"当前应显示的帧"继续按原速播放，不追赶。节流期间同样每帧重锚，所以欠账根本不会累积。
  新增两行诊断日志（都不是每帧刷屏）：节流开始时 `Playback clock paused for power save (Xms behind)`
  （每段节流一条），恢复可见后若仍有迟到 `Playback was Xms behind; re-anchored instead of fast-forwarding`
  （最多 5 秒一条）。模拟器实测：恢复后下一段立即回到 10.1fps（源为 10fps），不再出现快放段。
- **壁纸不可见时视频完全暂停**：解码循环在 `renderer.powerSaveMode`（熄屏 / 其他应用在前台 / 系统动态壁纸对话框）时
  **不再取出任何帧**，只是每 250ms 检查一次状态——输出队列自然填满、解码器自行停住，CPU/GPU 与纹理上传全部停止；
  画面停在当前帧，恢复可见后从这一帧按原速继续（配合上面的播放时钟重锚，既不快进也不跳帧）。
  新增 `Video paused (wallpaper not visible)` / `Video resumed (wallpaper visible again)` 各一条日志（只在"本段确实
  播放过"时打印，隐藏期间定时器重启同一视频不会刷屏）。健康监控相应地把"故意暂停"排除在卡死判定之外，
  并且把"首帧超时"窗口在暂停期间顺延——否则一个在隐藏状态下启动的视频会在重新可见的瞬间被误判为坏文件而触发恢复切换。
  实测（模拟器，10fps 源）：可见时 10.1fps → 切到应用后 25 秒内**零帧**（日志无 `Video pass`）→ 回桌面立即 10.1fps。
- **GIF 与视频一样完全暂停**：GIF 帧循环在 `renderer.powerSaveMode` 时调用 `AnimatedImageDrawable.stop()` 冻结动画
  （恢复可见时 `start()` 从同一帧继续），不再"隐藏期间继续前进、回来后跳到别的帧"；`startGifHealthMonitor()`
  也改为等"屏幕亮 + 壁纸可见"再开始 8 秒判定，避免隐藏状态下把正常 GIF 判成坏图而替换成静态首帧。
  日志与视频同款：`GIF paused (wallpaper not visible)` / `GIF resumed (wallpaper visible again)`。
- **熄屏不倒计时、亮屏重新计时**：`ACTION_SCREEN_ON` 时把桌面与锁屏两个锚点都前移到"现在"再启动循环 ——
  熄屏期间本该到期的那一拍直接作废，下一次切换是完整一个间隔之后（旧行为是保留锚点、亮屏瞬间立刻补切一刀）。
  熄屏期间本就不做任何切换（循环被取消），因此"熄屏时桌面定时切换不生效"即是设计行为。
- **切换/循环不再"黑一下"**：视频 GL 初始化里原本会 `glClear` 并交换一帧**全黑**（注释写着"防止上一段视频的残影"），
  而每个播放段（循环）都会重新创建 codec → 于是"图片→视频"以及"每次循环"都会闪一帧黑。现在改为
  **保留上一帧**（上一张图片，或同一段视频的最后一帧）直到新视频首帧呈现：循环无缝、切图不闪；
  真正"永远出不来帧"的视频仍由引擎的恢复逻辑处理（首帧兜底 → 换媒体），不靠涂黑掩盖。
  实测（模拟器 4K 视频持续循环，截图亮度采样）：修复前最低亮度 **16.8**（中位 130），修复后最低 **129.1**、无任何偏暗帧。
- **大幅减少"访问照片和视频"**（MIUI/HyperOS 的隐私提示按 provider 读取计数）：
  - **解码元数据缓存**：`BitmapUtils` 记住每个媒体的像素尺寸 + EXIF 旋转，之后再解码同一张只读 **1 次** provider
    （原来：bounds 一遍、解码一遍、`getType()` 一次、EXIF 再一遍 = 3 次）。缓存是访问序 LRU（512 条，覆盖一整副牌堆），
    进程常驻所以一次切换循环之后基本都走快路径。
  - **定时切换不再预取下一张**：预取是给用户手动触发（双击/悬浮按钮/解锁/手动）提速用的；定时 tick 至少还有一个完整
    间隔，按需解码只要约 100ms，预取只是把媒体库读取次数翻倍。手动触发仍然预取。
  - 顺带修掉一个**失效逻辑**：`ACTION_SWITCH` 广播接收器忽略了 `EXTRA_SOURCE`，永远把来源写成 `broadcast`，
    于是"定时切换不预取"和"队列满时丢弃定时 tick"两条规则从来没生效过（现在按真实来源处理）。
  - 实测（模拟器，定时 10 秒、4 个媒体顺序循环）：定时切换一张图片从 **6 次 provider 读取降到 1 次**
    （1 个媒体 × 3 次 → 1 个媒体 × 1 次）；日志 `Timer switch: skipping prefetch (media access)` 每次定时切换一条。
  - **解码元数据持久化（Room v5）**：`wallpaper_images` 新增 `width`/`height`/`rotationDegrees`。MediaStore 文件夹扫描
    顺带把 `WIDTH/HEIGHT/ORIENTATION` 三列存进去（同一条查询，零额外读取）；SAF 导入或历史数据则在**首次解码**时学到
    并写回（惰性自愈，老库不需要重导）。于是"新进程的第一次解码"也只读 1 次。
    日志里 `Learned media metadata (3 reads, now stored): <uri>` 只会出现在该媒体第一次解码时；同一媒体再出现即为异常。
    实测：首次解码写入 `3000x2000` 后重启进程，连续切换 img1/2/3 再无 `Learned...` 行（走 1 次读取的快路径）。
  - **预取只在"快速连点"时发生**：距上一次切换 < 3 秒才预取（双击连击 / 连续点悬浮按钮的场合）。单次点击或定时 tick
    都跳过（日志 `Not a rapid switch (Xms); skipping prefetch`）——下一次切换按需解码只要约 100ms。
    实测：间隔 8738ms 的单次点击跳过预取；间隔 500ms 的连点触发 `Prefetching next image` → `Prefetch ready`。
  - **预取位图 120 秒未使用就释放**（`Dropped unused prefetch after 120s`）：它只是一张屏幕尺寸的 ARGB（数十 MB），
    只在快速连点时有意义，空闲时不该占着内存。实测熄屏 120 秒后如期释放。
- **"重新扫描"有明确反馈**：扫描期间工具行的计数位置变成"正在重新扫描…（请稍候）"（主色显示），完成后弹出结果
  Toast：`扫描完成：N 个文件夹 / M 个媒体（新增 K 个文件夹）`，没有新增时说明"没有新增"，一个都没扫到时提示
  "未找到文件夹（请确认已授予照片/视频权限）"。运行日志同时记录 `rescanFolders: N folders / M media`，
  导出日志即可核对（此前只有按钮文字变化，文件夹数不变时完全看不出有没有扫成功）。
- **文件夹自动扫描确认生效 + 抗 ROM 压制**：
  - 周期任务本身上限为 WorkManager 的周期工作（`folder_auto_scan`，间隔 ≥15 分钟，约束 `BATTERY_NOT_LOW`），
    实测入队后**立即执行一次**，并按 URI 去重只插入新增媒体（日志 `Auto-scan folder <路径>: N items, M new` /
    `Auto-scan finished: M new media from K folders`）。
  - 但 MIUI/HyperOS 会推迟甚至丢弃 JobScheduler 任务：平板日志里开了自动扫描却**一条 `Auto-scan` 都没有**。
    现在**打开应用时补扫**：设置开启且距上次扫描已超过设定间隔时，立即入队一次无约束的一次性扫描
    （`Auto-scan is due (last run at X); running it now`），周期任务继续保留作为正常路径。
  - 设置页直接显示结果：`自动扫描文件夹` 的副标题追加 `（上次扫描：刚刚 / 5 分钟前 / 1 天前 / 从未）`
    （`formatAgo()`，纯函数 + 单测）。
  - 静默分支不再静默：关闭状态 → `Auto-scan skipped: the setting is off`；没有任何"来自文件夹"的媒体 →
    `Auto-scan: no folder-imported media to re-scan`。
  - 并发保护：周期任务与应用内补扫可能同一秒触发（平板实测两路并行扫同一批文件夹），两条路径若都基于插入前的
    状态判断"这是新文件"就会重复插入，因此 Worker 内加了进程级 `Mutex` 串行化。
- **代码审查修复（线程安全 / 泄漏 / 重复计算）**：
  - `AppLog` 的 `SimpleDateFormat` 原来在锁外调用（多线程共享，时间戳可能错乱、甚至抛异常并冒到调用方）；
    现在时间戳在 `synchronized(lock)` 内格式化。
  - 解码超时后的位图竞态泄漏（`BitmapLoad` / `GifDecode` / `StaticMediaLoad` 三处）：worker 存入结果后再复查一次
    `abandoned`，若调用方已超时返回则立即回收（原来会漏掉一张屏幕尺寸 ARGB，约 18MB）。
  - `LiveWallpaperEngine.db` 是 `lateinit`：引擎 `onCreate` 在数据库打不开时会提前返回，`onDestroy → flushShuffleState()`
    仍可能读到它 → 现已用 `::db.isInitialized` 兜底。
  - **元数据过期自愈**：快路径解码后用实际解码尺寸与缓存尺寸对比（±2 容差），不一致说明同一 URI 的文件被替换过
    → 丢弃缓存并按慢路径重读、重新落库（日志 `Stale media metadata for <uri>: ... - re-reading`）。
  - **重复实现收敛**：新增 `engine/FirstFrame.kt` 统一"视频首帧（含 rotation 元数据）/ GIF 首帧（限屏幕尺寸）"，
    动态引擎的恢复兜底与静态应用共用同一份（此前只有静态侧处理 rotation，竖拍视频在静态模式会横着显示）；
    `MediaTypes.mimeOf()` 统一三处 MIME 探测；`util/HexColor.kt` 统一按钮色/主题色解析。
  - **每屏启用媒体数缓存 3 秒**（`enabledCountCached`）：一次切换 + 预取原本要跑 2–3 次 COUNT（大库上是索引全扫）。
  - 预取的"120 秒未用即释放"任务改为**替换而非叠加**（连点不再累积几十个定时任务）。
  - 自动扫描的"上次运行时间"改为**扫描完成后**写入（中途被杀不会伪装成已完成）。
  - 有意保留：每次解码仍使用独立守护线程（不是线程池）——单个卡死的云盘/SAF provider 不能阻塞后续解码；
    线程创建开销约 0.1ms，相对切换间隔可忽略；`SettingsUiState` 的 19 元数组解包维持现状（改动面大、当前类型安全）。
- **死代码/冗余**：删除 `WallpaperRenderer.videoSourceW/H`（只赋值不读取）、`HomeScreen` 未使用的
  `androidx.compose.animation.*` import、`mipmap-hdpi` 下与 `mipmap-anydpi-v26` 完全相同的自适应图标；
  `WallpaperViewModel.isLoadingMore` 更名 `isLoadingImages`（分页已移除）。
- **新增单元测试**：`MediaTypesTest`（扩展名/MIME 分类与修复规则）、`MediaPickTest`（随机与洗牌选取、
  牌堆重置、排除当前媒体，用内存 Fake DAO）、`SwitchPickingTest.pickUnseen`、`FormatIntervalTest`。

#### 4.9.8 功耗 / 流畅度 / 稳定性优化（方案顺序落地 + 实测）

> 前提：**不改变任何现有功能行为**（不可见即暂停、亮屏重新计时、锁屏跳过动态媒体、176px 缩略图与
> "划到哪加载到哪"等既有策略全部保留）。下列改动按实际执行顺序排列。

**① 协程异常兜底（新增 `util/Coroutines.kt`）**

- `logCoroutineFailures(tag)`：给长生命周期 `CoroutineScope` 挂 `CoroutineExceptionHandler`。
- 原因：`scope.launch {}` 内未捕获的异常会交给线程默认 uncaught handler → **直接杀进程**。动态壁纸引擎、
  前台切换服务与 UI 同一进程，任何一处抛异常都会表现为"壁纸引擎随机重启"。
- 接入点：`LiveWallpaperService.scope`、`WallpaperSwitchService.scope`、`WallpaperSwitcherApp`、
  `BootReceiver`、`ScreenUnlockReceiver`；两个服务另加带 handler 的 companion `ioScope`，
  替换原先 5 处裸 `CoroutineScope(Dispatchers.IO).launch`。

**② release 构建开启 R8**

- `release { isMinifyEnabled = true; isShrinkResources = true; signingConfig = debug; isDebuggable = true }`。
- 体积：debug APK **16.92MB → release APK 6.87MB**（R8 混淆 + 资源裁剪），构建产出 `mapping.txt`（22.8MB）可回溯混淆栈。
- 保留 debug 签名 + `debuggable`：`install -r` 可直接覆盖安装（平板不卸载、不丢 38k 媒体与分组数据），
  `run-as`、日志导出仍可用。正式分发前需换真实 release keystore 并关闭 `isDebuggable`（代码内已注释说明）。

**③ 去掉重复写盘 + 缓存屏幕尺寸**

- 桌面槽位新增 `LAST_HOME_WRITE_ID` / `LAST_HOME_WRITE_AT`，与锁屏的 `LAST_LOCK_*` 对称；
  `WallpaperApplier` 用 `WRITE_REPEAT_WINDOW_MS = 30min` 判定"这块屏上就是这张图"，
  命中即跳过解码与 `setBitmap`（日志 `Static HOME already shows X; skipping re-write`）。
  实测：单图分组在 10s 间隔下由"每 10s 重写一次"变为**只写一次**。
- `BitmapUtils.getScreenMetrics()` 增加按 `orientation + screenWidthDp/HeightDp + densityDpi` 为 key 的缓存，
  切换/旋转不再每次重算 `DisplayMetrics`。

**④ 淡入时序对齐首帧（`WallpaperRenderer.onFirstVideoFrame`）**

- 原来淡入在"切换发起"时就起算，解码器还在预热 → 观感是"先黑一下再闪进来"。
- 现在普通切换（`!wasRapidSwitch()`）把淡入挂到**首帧真正上屏之后**（`onFirstVideoFrame` → `maybeFade(force = true)`）；
  横竖屏重绘（`suppressFadeUntilFirstFrame`）不淡入；连点切换（rapid switch）依旧不淡入，避免动画叠加。
- 实测（模拟器）：图片 `Switch to → Fade-in requested`；视频 `Switch to → startVideo → Video frame rendered → Fade-in requested`。

**⑤ 轮询改事件驱动**

- 锁屏"无锁屏分组"时的空转等待 `15s → 10min`（`LOCK_IDLE_WAIT_MS`）；系统动态壁纸界面挂起等待
  `2s → 30s`（`PREVIEW_DIALOG_HOLD_WAIT_MS`）。等待期间改由事件唤醒：
  - 预览引擎 `onDestroy` → `WallpaperSwitchService.poke()`
  - 新增媒体 / 新增文件夹 / 导入扫描结果（`addImage(s)`、`addFolder`、`importScannedFolders`）→ `poke()`
- `start()` 中 `startForegroundService` 加 try/catch：Android 12+ 后台限制抛出的异常不再冒到调用方。
- 效果：稳态下不再有固定周期的空转唤醒，只在真正有变更时被叫醒。

**⑥ 类型安全 / 写队列 / 重试策略 / 可观测性**

- `settingsUiState` 的 19 元 `combine` 去掉 `as` 强转，改为 `SettingsField` 下标常量 +
  `combined(values, index, name, type)`；类型不符时记日志并回落默认值（原来异常抛在 flow 内会让整个设置页静默停更）。
- 新增 `engine/MediaMetaWriter.kt`：解码后的"媒体宽高/旋转"回写由 `runBlocking { dao.updateMediaMeta() }`
  改为 `Channel(256, DROP_OLDEST)` 串行后台写；丢一条最多让下次多探测一次，但不再让解码线程等数据库。
- `FolderAutoScanWorker` 异常分支 `Result.retry()` → `Result.success()`：永久性失败（权限/路径消失）
  不再被 WorkManager 无限退避唤醒。
- `LiveWallpaperService.databaseUnavailable`（引擎建库失败置位），导出报告头新增 `engine_database_ok=`，
  数据库打不开时一眼可见。

**⑦ 修锁屏分组"点了却没生效"（本次回归时发现）**

- 现象：锁屏分组里点一张图 → 系统界面点「设置壁纸」→ 确认后锁屏仍是**上一张**图（`Slot enforcement
  after live wallpaper apply=false`）。日志：`Lock screen already restored recently, nothing to enforce`。
- 根因：补写走读的是**桌面游标** `LAST_IMAGE_ID`。点图 → 确认界面停留几秒 → 桌面定时在这中间推进了一次游标
  （实测 `last_image_id=29`，而用户点的是锁屏组的 id=7），补写于是把"当前媒体"认成了桌面图，直接走到
  "桌面分组"分支并跳过；同时 `setAsLiveWallpaper` 对锁屏分组也会污染桌面游标。
- 修复：
  - 新增 `MANUAL_PICK_MEDIA_ID` / `MANUAL_PICK_AT`：点图时记下"用户到底点了哪张"；
  - `enforceSlotsLocked` 优先使用这条手动记录（3 分钟有效期，用完即清），只有没有记录时才回退到 `LAST_IMAGE_ID`；
  - 锁屏/两者分组才写手动记录；**只有含桌面的分组才移动 `LAST_IMAGE_ID`**，锁屏分组不再污染桌面游标；
  - 媒体被删除时同步清掉这条记录（两处清理点，与 `LAST_HOME/LOCK_WRITE_ID` 一致）。
- 复测（模拟器，锁屏间隔临时调成 10 分钟以排除定时干扰）：点 img4 → 确认 → `Wallpaper applied (lock):
  img4.jpg`、`Lock-targeted group (LOCK): picked media written to the lock screen=true`，
  `last_lock_write_id=6`、`manual_pick_media_id=0`（已消费）。

**本轮验证（release 包，平板 + AOSP 14 模拟器）**

| 项目 | 结果 |
| --- | --- |
| 单元测试 | 78 项全部通过 |
| 构建 | `assembleDebug` / `assembleRelease` 均通过，release APK 6.87MB |
| 安装 | 平板（覆盖安装、不卸载）+ 模拟器 `install -r -d` 成功 |
| 崩溃/ANR | 新包运行期间 `FATAL EXCEPTION`、`ANR` 0 条，`runtime.log` 无 `E` 行 |
| 桌面定时 | 10s 间隔稳定切换，图片与视频交替 |
| 锁屏定时 | 独立 10s 切换，只写锁屏槽位；锁屏候选在 SQL 层限制 `mediaType='IMAGE'`，视频/GIF 自动跳过（实测 `anim.gif` 与 `small60.mp4` 均未被写入锁屏，只在 img4/img5/img6 之间轮换） |
| 桌面/锁屏互不干涉 | 引擎只作用于桌面槽位，锁屏由 `WallpaperApplier(lock)` 单独写入 |
| 熄屏/亮屏 | `Screen off: timers paused` → `Screen on: restarting both switch intervals from now` |
| 横竖屏 | `Surface size changed 1600x2560 -> 2560x1600; forcing re-render`，无残影、无崩溃 |
| 双击 / 悬浮按钮 | `Switch requested: double-tap`；悬浮按钮**单击一次**即 `Switch done: floating-tap` |
| 视频 | 10fps 素材实测 10.1fps；首帧后淡入，循环无黑屏空档 |
| 点击图片设动态壁纸 | 打开系统动态壁纸界面，提示按分组给出（桌面分组=请选择"主屏幕"） |
| 手动点图 → 锁屏 | 锁屏分组点 img4 并确认：`Lock-targeted group (LOCK): picked media written to the lock screen=true`、`last_lock_write_id=6`；两者分组点 img6：锁屏写入 img6；桌面分组点图：锁屏保持自己的图片不被覆盖，桌面游标跟随点击的那张 |
| 不重复写 | `Static LOCK already shows img4.jpg; skipping re-write`（单图分组只写 1 次） |
| 日志导出 | SAF 保存成功，报告头含 `engine_database_ok=true`、`wallpaper_component=...LiveWallpaperService` |
| 功耗 | 平板 10 分钟归因功耗 ≈ 0mAh（详见下方实测） |
| 稳定性压力 | 5×熄屏/亮屏 + 4×横竖屏往返 + 8 组连续双击：`FATAL EXCEPTION` / `ANR` 0 条，画面持续正常。旋转风暴中出现 2 条设计内日志 `Video GL setup failed` / `Decode pass interrupted`（旧世代视频在旋转时被替换），引擎随即回退到静态图并继续出画，无黑屏残留 |

**功耗与流畅度实测**

| 指标 | 方法 | 结果 |
| --- | --- | --- |
| 归因功耗（平板） | `dumpsys batterystats --reset` → 10m10s（屏幕点亮、动态壁纸引擎运行、锁屏定时开启） | 应用 UID `u0a534` **未出现在 `Estimated power use` 列表**，即归因 CPU 功耗低于 0.000045mAh 的显示门槛（≈0mAh / 10min）；设备整体 cpu 6.77mAh 中 system_server 3.77mAh、kernel 2.04mAh 占绝对多数 |
| 进程 CPU（平板） | `top -b -n 1 -o %CPU,RES,ARGS` | `com.wallpaperswitcher` **0.0% CPU**，RES 200MB |
| 对比基线（优化前 debug 包） | 平板 1h49m 混合场景（含视频播放） | 归因 4.20mAh（≈2.3mAh/h），其中 `fgs: 4.11` |
| 切换响应（模拟器） | 日志 `Switch start→Switch done` 692 次 | min 15ms / p50 56ms / p90 95ms / max 951ms（首帧等待编解码器的极值） |
| 大图解码（平板） | 日志 `drawCurrentImage loading→loaded` 92 次 | min 43ms / p50 171ms / p90 234ms |
| 视频帧率 | 日志 `Video pass` 汇总 | 10fps 素材实测 **10.1fps**（与源一致） |

> 说明：两次功耗测量的场景不同（基线含视频播放、时长为 1h49m；本次为 10 分钟静置），
> 因此上表只用于判断"量级"：优化后应用自身的归因功耗已经低到系统不单独统计。
> 同时因为 `--reset` 后设备其它进程的唤醒（system_server / kernel）仍会被计入整机数值，
> 这部分不归因到本应用。

#### 4.9.9 代码审查修复（重复计算 / 线程安全 / 日志卫生）

- **洗牌改为 deck（新增 `engine/ShuffleDeck.kt`）**：原来每次洗牌切换都执行
  `getEnabledIds(slot)`（全量 id 游标）并在内存里 filter 一次——38k 媒体库 + 10 秒间隔等于每 10 秒
  一次全表游标加两个 N 长列表。现在整副牌只取一次 id、洗好顺序后按游标 O(1) 逐个发出；
  启用数变化（新增/删除/启用/禁用）或发完自动重建（`needsBuild`）。
  实测日志：`Shuffle pick: deck=0/9 → 1/9 → … → 8/9 → 0/9`，单副牌内不重复。
- **预取与真正切换选同一张**：洗牌以前"预取随机抽一次、切换再随机抽一次"，预取基本白做。
  现在预取走 `peek`（只窥视不消费），实际切换 `take` 拿到的就是它：日志由"Prefetch ready → 重新解码"
  变成 `Using prefetched bitmap: landscape.jpg id=4`。
  **（该条已在后续按用户要求回退，见 §4.9.11；牌堆本身保留。）**
- **音频会话的线程可见性**：`AudioSession.track` / `playing` / `bufferMillis` 与 `audioPending`
  加 `@Volatile`——音频线程与引擎线程共享（`stopAudio()`、`release()` 从引擎线程调用），否则可能
  读到过期音轨（表现为"关掉开关没立刻静音"或往已释放音轨写）。
- **每帧日志节流 + AppLog 批量 flush**：`renderVideoFrame skipped`、`eglSwapBuffers failed`
  原来每帧都可能打印（30–60 行/秒），现按 5 秒节流；`AppLog` 由"每行 flush"改为
  **W/E/I 立即落盘、D 行按 4KB 或 250ms 批量**，故障时不再把磁盘写入变成新的瓶颈，
  同时崩溃最多丢 250ms 的调试行（警告/错误永远不丢）。
- **每帧零分配**：`renderVideoFrame` 里每帧 `FloatArray(16)` 与 `videoScreenTexelDelta()` 的 `Pair`
  改为复用字段（图像路径的 texel 同样处理），60fps 下每秒少 120 个小对象。
- **视频解码线程改 daemon**：与音频、打开媒体两个辅助线程一致；云盘阻塞 I/O 卡住的线程在 JVM
  语义上不再拖住进程。
- **取消选择时清掉待应用的手动选择**：预览引擎销毁后 1.5 秒内若没有真引擎接管（= 用户按了返回），
  清除 `MANUAL_PICK_MEDIA_ID`，避免 3 分钟窗口内某次无关的引擎重建把它写到锁屏。
- **离开分组界面即释放整组媒体列表**：分组详情有意不分页（快滚/全选/批量都要整表），但之前离开界面
  并不清空，几万张的组会一直常驻内存；现在 `Screen.GroupDetail` 用 `DisposableEffect` 在退出时
  `selectGroup(null)`（UI 行为不变，重进照常加载）。
- **新增测试**：`ShuffleDeckTest`（整副牌不重复、peek 不消费、排除当前屏、计数变化重建、发完重建、
  `drop` 陈旧 id、空牌）与 `MediaPickTest` 增补（预取 = 实际选择、计数变化重建、陈旧行跳过），
  全量 **90 项单测通过**。

#### 4.9.10 反馈修复（悬浮按钮防抖 / 日志保留一轮 / 洗牌回退）

- **悬浮按钮取消 250ms 防抖**：平板日志里 295 次点击有 **105 次**被
  `Tap ignored (post-switch debounce)` 吞掉（"点了没反应"）。现在每次点击都发起切换，
  限流交给引擎自己的合并逻辑（`requestSwitch` 会把切换进行中的触发折叠成**一次**待处理切换），
  所以连点既不会丢点击，也不会堆叠解码。实测：400ms 间隔 6 连点 → 6 次切换 0 次忽略；
  150ms 间隔 5 连点 → 5 次切换 0 次忽略。
- **日志保留一轮**：日志超过 2MB 时原来在下次启动**整文件删除**（13k 行的日志变成 2.7k 行，
  跨重启排查断档）。现在轮转为 `runtime.1.log`，并在导出报告末尾附加它的尾部（≤256KB），
  报告里带 `---------- previous session (runtime.1.log tail) ----------` 分段。
  实测：5.0MB 的 runtime.log 重启后 → `runtime.1.log`(5.0MB) + 全新 `runtime.log`，
  导出文件 271KB 且包含上一轮内容。
- **洗牌"发完"回退改走新一副牌**：原来的回退是"随机取（排除当前屏）否则任意随机"这对选项，
  它**绕过了 deck**：选中的图不属于任何牌堆，紧接着重建的牌堆可能把它再发一次（上一张立刻重复）。
  现在回退同样从重建后的牌堆取第一张（仍排除当前屏），引擎与静态应用器两处一致。
  新增两条单测（回退不重复当前屏、走完整副牌后回退的首张不同），全量 **92 项通过**。

#### 4.9.11 按反馈回退：洗牌预取恢复"独立随机"

应用户要求，把 §4.9.9 中的**洗牌预取复用**（`ShuffleDeck.peek`）回退为改动前的行为：

- 预取路径改为 `ShuffleDeck.peekRandom(excludeId)`：在**尚未展示的那部分**里随机取一张，
  **不消费、不推进牌堆游标**（等价于改动前那次"独立随机抽一张"）。因此预取解码的那张
  不保证就是下一次切换显示的那张——这是回退前用户已知并接受的行为。
  - 仍然是 O(1) 且**零分配**：随机取下标后若正好是当前屏那张，就往后挪一位（必要时回到游标起点），
    与旧实现"把当前屏从候选里过滤掉"的效果一致。
- **保留**的部分：牌堆本身（每副牌只查询一次全量 id、`take` 逐个发出、计数变化或抽完自动重建）、
  以及"发完回退走新一副牌"。也就是只需回退"预取与切换选同一张"这一条，不影响
  "洗牌不再每次切换都全量查 id"和"副内不重复"。
- 测试同步调整：原 `prefetchPeekReturnsTheSameMediaTheNextPickShows` 改为
  `prefetchPicksAnUnseenMediaWithoutConsumingTheDeck`（预取给出未展示的媒体、且不消耗牌堆，
  真实切换仍按牌堆走且不重复），并新增 `peekRandomNeverOffersTheMediaOnScreen`。
  全量 **93 项单测通过**。

#### 4.9.12 洗牌语义修正：一轮没走完，不重复任何已展示的图片

用户要求：**洗牌模式下先把分组里的图片随机走一遍，走完之前不出现重复；走完一轮后才重新随机**。
核对后发现两个会破坏这条规则的地方，都已修：

1. **启用集数量变化会清空"本轮已展示"**：旧规则 `shouldResetShuffleDeck(shownSize, total, savedCount)`
   里 `savedCount != totalCount` 也算"要重置"，于是导入文件夹、开关分组（平板日志里 5 分钟出现 4 次）
   都会把已展示集合清掉 → 同一轮里已经看过的图片会被重新发出来。
   现在 `shouldResetShuffleDeck(shownSize, totalCount)` **只在一轮走完时**为真；数量变化只是让牌堆
   按"当前启用的 id 减去已展示"重建，**本轮继续**（新增的图片会加入本轮，删掉/禁用的自然消失）。
   实测（模拟器）：一轮已展示 3 张时插入第 10 张 → 计数从 `3/9` 变 `3/10` **继续递增**
   （`4/10 → 5/10 → 6/10`），新插入的图片在本轮内被选中，已展示的 3 张没有再次出现。
2. **"本轮已展示"只在引擎销毁时落盘**：MIUI 频繁杀掉/重建壁纸引擎，重建后读到的是过期（甚至空）的集合，
   新牌堆会把本轮已看过的图片再发一遍。现在每次切换都会增量落盘（已展示集合 ≤1000 时逐次写，
   超大集合按 30 秒节流，避免 38k 的 id 串每 tick 写几百 KB），`flushShuffleState` 也会正确地
   清除/在失败时恢复 dirty 标记。
   实测（模拟器）：连切 3 次后数据库里已经是 `shuffle_shown_ids=2,29,30`（此前只可能在引擎销毁时写入）。

新增/更新测试：`SwitchPickingTest` 改为新语义（数量变化不再重置、走完才重置、禁用后总数小于已展示则重置），
`MediaPickTest` 增补"一轮中途新增媒体后本轮继续且不重复"，全量 **95 项单测通过**。

#### 4.9.13 按反馈回退：洗牌恢复修复前的取图方式

应用户要求，把 §4.9.9 的**第 1、2 项**（`ShuffleDeck` 牌堆缓存 + 预取复用）整体回退：

- `MediaPick.shuffleUnseen(imageDao, slot, shownIds, excludeId)` 恢复为修复前的实现：
  **每次挑选都 `getEnabledIds(slot)` 拉取全量启用 id**，在内存里过滤掉"本轮已展示"和"当前屏那张"，
  再由 `SwitchPicking.pickUnseen` 随机取一张；`ShuffleDeck.kt` 与 `ShuffleDeckTest.kt` 已删除。
- 预取路径也回到"自己随机抽一张"：它解码的图片**不保证**就是下一次切换显示的那张（回退前行为）。
- 一轮走完后的回退恢复为原来的"随机（排除当前屏）→ 否则任意随机"两个选项。
- **保留**（与 1、2 无关，且属后续按反馈要求的语义）：
  - `shouldResetShuffleDeck(shownSize, totalCount)`：只有一轮走完才重置 —— 启用集变化不再清空本轮进度（§4.9.12）。
  - 挑选前剔除"已展示集合"里**已不再启用**的 id（否则本轮会被提前判为走完而重播）。
  - "本轮已展示"增量落盘（每次切换写；超大集合 30 秒节流）+ 引擎销毁时落盘（§4.9.12）。
- 测试同步：恢复 `SwitchPickingTest` 的 6 条 `pickUnseen` 用例；`MediaPickTest` 的洗牌用例按原签名重写
  （一轮内不重复、中途新增媒体本轮继续、剔除失效的已展示 id、排除当前屏），全量 **89 项通过**。
- 预期代价（回退前即存在）：每次洗牌切换多一次启用 id 全量查询（38k 库约数毫秒 + 一个 N 长列表），
  预取命中率回到随机水平。

#### 4.9.14 修掉"一轮没走完却重现已展示图片"的两处根因

日志分析里发现：16 轮洗牌中有 2 轮各出现 1 次轮内重复（20:42–20:59 与 21:10，均在旧包上），
相邻重复 0 次。定位到两个根因并修复：

1. **"记为已展示"依赖共享字段**：`if (lastDisplayedId == nextImage.id && SHUFFLE)` 用来判断
   "这笔切换是否真的把这张图放上了屏"，但 `lastDisplayedId` 是引擎级共享字段 —— 连点时后一笔切换
   会把它覆盖，前一笔记不上"已展示"，于是同一轮里会再把这张发一次。
   现在改为**每笔切换自己的布尔标志** `appliedThisSwitch`（三个分支在真正应用成功处各置一次），
   用它来做"记为已展示"和"从失败名单里移除"；淡入仍按"当前屏是不是这张"判断（那是它的语义）。
2. **增量落盘没有串行化**：多笔切换几乎同时 `ioScope.launch` 写库，旧快照可能后落盘、把新进度覆盖掉
   （等于丢掉"已展示"记录）。现在用 `Mutex` 串行化，并带自增"写入代号"：**被更新的快照超越的写入直接丢弃**，
   失败时回置 dirty 以便下次重试。

验证（模拟器，新包，悬浮按钮 450ms 连点 12 次）：按"严格配对"口径（`Shuffle pick ... -> id=X`
紧跟同一 id 的 `Switch to`）逐轮核对，得到两轮完整的一轮：`1,2,3,4,5` 与 `4,5,2,1,…`，
**每轮内无重复、相邻重复 0 次**（修复前的同一操作会在一轮里重复 id=5）。
全量 **89 项单测通过**，两台设备已装新包。

> 分析日志的正确口径（避免把"预取挑到视频/GIF 而中止"的挑选误当成切换）：
> 只看 `Switch to: ... id=X` 这一列（用户实际看到的东西），再用 `Shuffle pick: deck=N/M` 归零来切分轮次；
> 只要一轮内 X 不重复就没问题，一轮走完后重新随机属于设计行为。

#### 4.9.15 「滑动 / 翻页切换」：已移除

用户要求取消该功能，相关代码与开关已全部删除：

- 删除文件：`engine/SwipeDetector.kt`、`engine/PageSwipeTracker.kt`、`engine/HomePageFlipDetector.kt`、
  `engine/HomePageSwipeDetector.kt`、`engine/HomePackages.kt`、`service/HomePageAccessibilityService.kt`、
  `res/xml/accessibility_service_config.xml` 及对应的 5 个测试文件。
- 删除设置项 `swipe_switch_enabled`、设置页「滑动/翻页切换」开关与「翻页切换（无障碍）」行、
  `WallpaperSwitchService.tryGestureSwitch()`（手势去重窗口）、导出报告里的 `swipe_switch=` /
  `accessibility_page_flip=` 字段、`AndroidManifest` 中的无障碍服务与 HOME `<queries>` 声明。
- `LiveWallpaperService` 回到只处理触摸双击：删除 `onOffsetsChanged` 翻页分支、触摸滑动分支
  以及 `SOURCE_SWIPE` / `SOURCE_PAGE_SWIPE` 两个来源常量。

**为什么移除（重要记录）**：翻页切换在小米设备上必须依赖无障碍服务，而无障碍服务一旦请求
「触摸手势事件」（`FLAG_SEND_MOTION_EVENTS` + `motionEventSources = SOURCE_TOUCHSCREEN`），
MIUI/HyperOS 的输入滤波就会把触摸事件收进无障碍通道、应用侧收不到 DOWN，
表现为**整个触屏失灵**（平板实测：日志出现 `Motion events received (swipe detection active)` 之后桌面点不动）。
AOSP 14 模拟器无此现象，属 MIUI 特有行为，无法在保留该功能的前提下规避，故整体移除。
双击切换、悬浮按钮、定时切换、解锁切换均不受影响。

#### 4.9.16 代码审查修复（本轮）

对全量代码做了一次缺陷盘点，并按下述顺序修掉（除标注外都是**行为等价或更接近注释所述语义**的改动）：

1. **引擎看门狗漏判锁屏定时**：`LiveWallpaperService.selfHealTimerService()` 之前只看 `service_enabled`，
   而 `BootReceiver` / `ensureRunning` / `toggleLockTimer` 都用 `service_enabled || lock_timer_enabled`。
   现在四处一致：只开锁屏定时、前台服务被 MIUI 杀掉后，看门狗也会把它拉起来。
2. **"连续 3 次无启用分组"不再连坐锁屏**：以前直接 `stopSelf()`，会把只开锁屏定时的服务一起杀掉，
   而 UI 仍显示"锁屏运行中"。现在只有锁屏定时也关掉时才停服务。
3. **锁屏分组的动态壁纸不再推迟桌面定时**：`setAsLiveWallpaper` 移动 `timer_last_switch_wall_ms` /
   `manual_pick_hold_until` 的语句改为只在 `target.includesHome` 时执行，与 `setImageAsWallpaper` 一致。
4. **游标语义写清楚，引擎改为"显示后推进"**：`LiveWallpaperService.executeSwitch` 不再先写 `last_image_id`
   再解码（引擎有 `failedMediaIds` + 恢复切换，失败不会死循环）。静态路径**保持**原有"先推进游标"的行为
   ——静态路径没有失败黑名单，若失败不推进，坏文件会被每个 tick 重新选中、壁纸一直不变——但把注释改成
   与行为一致（失败即本轮跳过，文件仍在下一轮重试；只有文件真的消失才删行）。
5. **静态 tick 的结果可区分**：新增 `WallpaperApplier.StaticTickOutcome`
   （`APPLIED` / `ALREADY_SHOWING` / `NO_MEDIA` / `FAILED`）。"已经是这张、跳过重写"不再被记成
   `Static wallpaper switched`，日志改成 `Static wallpaper already showing (...), nothing written`。
   解锁切换的"是否算已切换"语义保持不变（`ALREADY_SHOWING` 仍算成功）。
6. **解锁切换提示纠正**：无引擎时接收器会走静态切换，旧提示"需要动态壁纸引擎运行"是错的，
   现在改为说明当前是静态壁纸模式（无动画）。
7. **系统壁纸预览 hold 到期后清标志**：`isPreviewDialogOpen()` 超过 5 分钟上限时会把
   `previewEngineActive` 置回 false，避免"标志一直挂着、后来又被打断"的窗口。
8. **惰性字段与注释清理**：`SettingsKeys` 里 `unlock_switch_enabled` 的注释仍在讲已删除的滑动功能、
   `manual_pick_hold_until` 的注释写"BOTH screens"（锁屏循环实际忽略它）——都已改成与实现一致。
9. **首页引擎状态不再是死值**：新增 5 秒一次的 RESUMED 轮询（仅在首页可见且有定时开启时运行），
   MIUI 在前台期间杀掉壁纸进程后，"引擎未运行"提示卡会自己更新。
10. **只写不读的洗牌计数去掉**：`SHUFFLE_ALL_COUNT_LOCK` 常量与静态路径的计数写入已删除
    （只有引擎的 `SHUFFLE_ALL_COUNT` 还用于日志，保留）。
11. **去掉每 tick 重复查询**：`WallpaperApplier.applyNext` 开头的 "有没有启用分组" 全表查询删除——
    下面每个挑选查询本来就按启用分组 + slot 过滤，没有媒体时同样返回 `NO_MEDIA`。
12. **主线程 binder 调用下移**：`setImageAsWallpaper` 里的 `WallpaperManager.wallpaperInfo`
    查询移入 `Dispatchers.IO`。
13. **废弃设置键清理**：启动时一次性删除 `swipe_switch_enabled`、`shuffle_all_count_lock`
    （`SettingsDao.deleteKey` + `OBSOLETE_KEYS`，删除时会写日志）。
14. **触摸日志节流**：删除功能的滑动需要每指一条日志，现在普通 `Touch DOWN/UP` 30 秒最多一条，
    双击识别仍然每条都记（"启动器是否把触摸交给壁纸"依然能从日志判断）。

**遗留说明（有意不改）**：`wallpaper_groups.type` 是旧设计的列，全仓库已无任何读取；它只为
Room schema 兼容保留（删列要重建整表并级联删除媒体行）。新代码不要再依赖它。
`app/src/main/java/.../engine` 等模块的纯函数仍保持"每个都有调用方"，本轮审查未发现其它死代码。

#### 4.9.17 日志分析后的修复（真机日志驱动）

以平板 `runtime.log`（09-20 23:31–23:33）为依据修的四项：

1. **取消系统动态壁纸选择器后必须清掉"待确认的手动选择"**。
   旧实现用全局 `engineRunning` 判断取消：`if (!engineRunning) clearManualPick(...)`。但当我们的动态
   壁纸**本来就是激活的**（常见情形：已经设过，再点另一张图），预览引擎销毁并不会清 `engineRunning`，
   于是"取消"分支永远不成立。日志证据：23:32:50 `setAsLiveWallpaper(id=40136, target=LOCK)` → 23:32:51
   预览引擎 `Engine created` → 23:32:52 `Service started`（预览销毁 poke），但**没有任何 clearManualPick
   日志**，而数据库里 `manual_pick_media_id=40136` 一直留着。风险：该值在 `MANUAL_PICK_FRESH_MS`（3 分钟）
   内遇到真实引擎重建（MIUI 杀进程恢复、旋转、再次应用壁纸）就会被 `enforceSlotsLocked` 写进锁屏 ——
   一张用户没确认过的图。
   修法：新增"真实引擎代次" `realEngineGeneration`（真实引擎 onCreate 时 +1）。预览引擎记住自己创建时的
   代次，onDestroy 后等 `PICK_CANCEL_GRACE_MS`（1.5s）：代次没变 ⇒ 没有新引擎接管 ⇒ 判定取消并清除；
   代次变了 ⇒ 用户确认过，交给 `enforceSlotsLocked` 处理。日志新增
   `Live wallpaper picker closed without a new engine: pick cancelled`。
2. **MIUI 拒绝聚合查询时不再每次重试**。MIUI 的 MediaProvider 会校验投影并抛
   `IllegalArgumentException: Invalid column count(*) as c`（日志里 2 秒内 6 条 W），于是每次文件夹扫描都
   先白跑一次注定失败的 `GROUP BY` 查询再退化到逐行遍历。新增进程级 `groupedScanUnsupported`：第一次
   失败（抛异常或返回空游标）记一条 W，之后直接走逐行遍历，不再重复尝试、不再刷日志。
3. **悬浮按钮加隐藏防抖**：可见性抖动（日志里 23:32:44.112 hidden → .149 shown，2 分钟 27 个完整周期）
   会让 overlay 反复 `removeView` + `addView`。现在**显示仍然立即**，隐藏延后
   `FLOATING_BUTTON_HIDE_SETTLE_MS`（400ms），触发时再确认一次 `isVisible`；期间恢复可见则整段churn 取消。
   应用打开时的立即隐藏（`dismissFloatingButtonNow`）会取消这个待定隐藏。
4. **首次切换的日志噪音**：`lastSwitchCompletedAt == 0` 时不再用 `elapsedRealtime - 0` 计算间隔（旧日志
   出现 `Not a rapid switch (271624535ms)`），改为 0，并在预取逻辑里直接记
   `First switch of this engine; skipping prefetch`。

#### 4.9.18 视频播放路径优化（循环不重建 codec / 保留上一帧 / 省电确认）

1. **同一文件循环复用已预热的会话**（WallpaperRenderer 的解码线程）。
   旧实现每个播放轮次都新建 `MediaExtractor` + `MediaCodec` + `SurfaceTexture`，所以短片每循环一次
   就要付一次 codec create/configure/start（30–80ms）与 GL 重建，循环点出现停顿。
   现在：`sessionExtractor` / `sessionDecoder` 跨轮次保留，循环时只做 `dec.flush()` +
   重新提交 codec-specific data（`csd-0/1/2`，Qualcomm 等解码器 flush 后需要）+
   `ext.seekTo(0, SEEK_TO_CLOSEST_SYNC)`；GL 侧因为 `SurfaceTexture` 仍绑定同一个 codec 而完全跳过重建，
   并且**不再在循环点清理 GL 资源**（清理会破坏 codec 的 surface，也是循环点黑闪的来源之一）。
   - 安全网：如果复用的一轮**一帧都没呈现**（个别解码器不接受 flush 后的重绕），该会话会被丢弃，
     下一轮自动走完整重建，并记 `Reused video codec produced no frame; rebuilding the session`。
   - 复用失败时（`Codec reuse failed (...)`）同样走"释放会话 + GL 清理 + 重建"，不触发恢复切换。
   - 不可 seek 的云盘源：第一轮会把文件拷进 cache，后续轮次直接用那份副本（不再重复拷贝）。
   - 切换**到另一个文件**仍然完整重建（codec 无法换格式），这部分行为不变。
2. **切到视频时保留上一帧**（LiveWallpaperService）。
   - 渲染器早先已经不再清 framebuffer；这次补齐引擎侧：`clearCurrentBitmap()` 换成
     `retireCurrentBitmap()` —— 位图先"退休"而不是立即回收，等新视频首帧真正上屏
     （`onFirstVideoFrame`）再回收；视频启动失败、`onTrimMemory`、引擎销毁也会回收。
     这样图片→视频之间不会再出现"没有任何东西可画"的窗口。
   - 同时去掉视频分支里多余的 `delay(30ms)` 结算等待：`stopVideo()` 内部已经 join 解码线程（≤120ms）。
3. **省电行为核对（无需改动）**：视频解码循环在 `powerSaveMode` 时是**完全暂停**——
   在喂输入之前就 `Thread.sleep(PAUSE_POLL_MS)` + `continue`（WallpaperRenderer 解码循环开头），
   所以不可见期间既不喂输入也不取输出，codec 自行停住；音频线程同样处理。
   `PAUSE_POLL_MS = 250ms` 是"恢复延迟 vs 唤醒次数"的折中，保持不变。
   日志上可见成对的 `Video paused / Video resumed (wallpaper not visible)` 与
   `Video audio paused / resumed`。

验证要点（真机日志）：短片的循环点上应出现 `Video loop restart (codec + GL reused, no re-init)`，
且不再出现每次循环的 GL 重建；`Video pass: … fps` 的窗口应连续、fps 与源一致；
图片→视频切换时不再有黑屏；不可见期间应只有一条 `Video paused`。

#### 4.9.19 图片路径：方向旋转改为 GPU 四边形旋转（去掉整屏 CPU 拷贝）

背景：FILL/STRETCH 的「自动旋转适配」原来在解码后用 `Matrix.createBitmap` 把整张图转 90°
（`BitmapUtils.rotateForFill`）——1440×3200 屏幕上就是**多一次 ~18MB 的位图分配 + 整屏拷贝**，
而且这笔拷贝发生在最热的切换路径上。

改法：

1. **纯几何旋转**：`WallpaperGeometry.computeQuad(..., rotateCw)` 新增可选参数——像素不动，
   只把"显示宽高比"换成旋转后的比例，并按旋转方向置换四个角的纹理坐标
   （顺时针：图片左上角落在屏幕右上角；逆时针：落在屏幕左下角）。渲染器本来就有一条
   "视频 90°" 的路子，这次让静态图走同一类做法，且**不需要改 shader**（图像 shader 的 texcoords
   直接来自这个顶点数组）。新增 5 条单测（角点映射、宽高比、纹理角完整性、null 行为一致）。
2. **解码只报告旋转、不做拷贝**：`BitmapUtils.loadBitmapForEngine()` 返回
   `EngineImage(bitmap, rotateCw)`；`loadBitmap()`（静态 WallpaperManager 路径）内部仍会把旋转
   **烘焙进像素**（`bakeQuarterTurn`），因为系统静态壁纸无法旋转——两条路径行为各自保持正确。
   EXIF 方向仍然在解码时烘焙（`BitmapFactory` 不认 EXIF，渲染器那个 90° 是"屏幕方向不匹配"，
   与相机元数据是两件事）。
3. **引擎侧打通**：切换/预取/旋转重绘都携带 `rotateCw`（预取缓存 `prefetchedRotateCw` 随位图一起消费），
   `stopVideoAndRender(bitmap, scaleMode, rotateCw)` 与 `renderImageFromTexture()`（淡入重绘、
   不重新上传）都按同一个角度出图；GIF 的首帧/健康回退路径因为由 GIF ticker 自己绘制，
   显式调用 `bakeQuarterTurn` 烘焙。
   日志里 `Bitmap loaded: WxH rotate=cw|ccw|none` 可直接确认走的是 GPU 旋转。

**本次未做（需要真机验证后再决定）**：`ImageDecoder` + `Config.HARDWARE` 让上传走硬件缓冲、
完全不占 Java 堆。原因是硬件位图**不能**用 `GLUtils.texImage2D` 直接上传（它读不到像素），
要另走 `Bitmap.getHardwareBuffer()` → `eglCreateImageKHR` → `glEGLImageTargetTexture2DOES`
这条 external-texture 路径（API 29+，还需要 `GL_OES_EGL_image_external` 与 EGLImage 生命周期管理）。
在没有真机可以对比验证的情况下贸然接入，风险（黑图/错图）高于收益，因此留作下一步：
先在本轮 GPU 旋转的基础上验证显示正确，再单独评估硬件位图上传。

#### 4.9.20 修复"视频无法循环"（4.9.18 的回归）

4.9.18 引入"预热会话"后，循环点不再重建 codec/GL，但**每轮仍然重新 setup extractor**——
也就是对已经读到 EOF 的 `MediaExtractor` 又调了一次 `setDataSource(...)`。平板实测该调用抛
`IOException: Failed to instantiate extractor`（连"拷贝到 cache 再 setDataSource"的重试也失败），
于是每个循环点都变成：首帧兜底 + 恢复切换 —— 用户看到的现象就是"视频放完一遍就不循环了"：

```
22:11:15.009 W Video source not seekable, copying to cache: media/498 | IOException: Failed to instantiate extractor.
22:11:15.076 E Decode error | IOException: Failed to instantiate extractor.
22:11:15.093 D Video pass: 949 frames presented in 19587ms (48.5 fps)   ← 恰好是 19.6s 片长的自然结束
22:11:15.227 D Video fallback first frame shown: 1440x2560
```

修法：**复用会话时完全不碰数据源**——`setDataSource` 只在"新会话"（首次播放 / 换文件 / 复用失败后重建）
执行；循环重启只做 `dec.flush()` → 重新提交 `csd-0/1/2` → `ext.seekTo(0, SEEK_TO_CLOSEST_SYNC)`
（这正是音频线程一直在用的写法，视频侧现在与之统一）。同时把"复用的一轮一帧未出 → 丢弃会话重建"保留为兜底。

另外：预热会话不再走拆解路径，所以每轮的 `Video pass: … fps` 会消失；为此把"记录并重置帧率窗口"
拆成 `logPassFrameRate()`，在**循环重启**与**会话拆解**两处都调用，诊断信息保持和以前一致。

实测（09-24 22:39–22:41，19.6s 60fps 1440×2560 HEVC 素材，FILL）：

```
22:41:04.335 D WallpaperRenderer: Video pass: 994 frames presented in 19504ms (51.0 fps)
22:41:04.344 D VideoDecode: Video loop restart (codec + GL reused, no re-init)
22:41:04.345 D VideoDecode: Video started: 1440x2560 @ 60fps codec=c2.qti.hevc.decoder
22:41:23.861 D WallpaperRenderer: Video pass: 978 frames presented in 19502ms (50.1 fps)
```

连续可见时每个循环恰好 19.5s，帧率 49–51fps（设备解码/合成上限；节流门是 16ms = 62.5fps，不是瓶颈），
日志里不再出现 `Failed to instantiate extractor` / `Video failed to start` / `Video fallback first frame`。

#### 4.9.21 文件夹扫描：删掉不可能生效的聚合"快路径"，改为"素材库没变就不重扫"

`MediaScanner.scanFolders()` 原有一条"一次 GROUP BY 得到每个文件夹的媒体数"的快路径（投影
`COUNT(*) AS c` / `MAX(_ID) AS max_id` / `MAX(DATE_ADDED) AS max_added`），并在失败时回退到逐行扫描。
平板日志里一直有一条
`Grouped folder scan failed, falling back to row iteration: IllegalArgumentException: Invalid column COUNT(*) AS c`。

本轮在 AOSP 14 模拟器上把三种写法都试了一遍（投影内别名、`QUERY_ARG_SQL_GROUP_BY`、
`QUERY_ARG_GROUP_COLUMNS` + `QUERY_ARG_SORT_COLUMNS`），**全部**被 MediaProvider 拒绝：

```
W/MediaScanner: Grouped folder scan unavailable, using the row-by-row scan (logged once per process):
  query args: IllegalArgumentException: Invalid column COUNT(*) AS c;
  sort order: IllegalArgumentException: Invalid column COUNT(*) AS c
```

原因是框架层的 `SQLiteQueryBuilder.setStrict(true)`：MediaProvider 把投影里每一项都当作**列名**去自己的
列集合里查，聚合表达式不是列，因此与"用哪种方式传 GROUP BY"无关；MIUI 的 MediaProvider 是同一套框架代码
加 OEM 分支，所以平板上的表现一致。结论：这条快路径在公开的 MediaStore API 上**不可能生效**，
于是把它整段删除（连带 `groupedScanUnsupported` 标志与那行误导性的 W），只保留逐行扫描这一条可移植路径，
并在代码里写明原因，避免以后再被"优化"回来。

替代的优化落在"不要重复扫"上：`scanFolders()` 现在记录扫描完成时的
`MediaStore.getGeneration(context, VOLUME_EXTERNAL)`（API 30+，provider 在增/删/改媒体时自增），
只有素材库真的变了才重新逐行扫描：

- 列表只缓存**非空**结果，避免权限刚授予/被拒时把空列表缓存住；
- 存的是**扫描前**读到的 generation：扫描过程中发生的变更会让记录值落后，下一次自动重扫而不是信任旧列表；
- "重新扫描"按钮语义不变（素材库有变化就会真的重扫并显示新文件夹），只是没变化时瞬间返回，仍然给出
  `扫描完成：N 个文件夹 / M 个媒体（没有新增）` 的提示。

模拟器实测（AOSP 14，`emulator-5554`）：

```
D/MediaScanner: scanFolders: found 4 folders                                   ← 首次打开对话框
D/MediaScanner: scanFolders: media store unchanged (gen=564), reusing 4 folders ← 点“重新扫描”，不再走全表
D/MediaScanner: scanFolders: found 5 folders                                   ← 新增 Pictures/wsnew/newpic.jpg 后
D/WallpaperViewModel: rescanFolders: 5 folders / 26 media                       （对话框“共 5 个文件夹”含新文件夹）
D/MediaScanner: scanFolders: found 4 folders                                   ← 删掉该文件后
D/WallpaperViewModel: rescanFolders: 4 folders / 25 media
```

#### 4.9.22 真机日志驱动的两处修复：失效视频行不清理 / poke 重启服务

Redmi 平板（Android 17 / HyperOS，应用 1.1）11:06:49–11:10:47 的日志（1984 行）显示：切换 150 次
中位 174ms、洗牌跨轮 0 重复、`home+lock` 一起写 0 次、熄屏 21 秒内零切换、90 次预取命中 84 次，
但有两处需要修：

**① 失效视频不会被清理（图片/GIF 会）**

```
11:07:25.576 Switch to: Fleurdelys.mov (VIDEO) id=58366   uri=.../video/media/89951
11:07:25.581 E Failed to open video stream: FileNotFoundException: No item at ...
11:07:25.582 W Video failed to start; scheduling recovery switch
（没有 "Dropped unreadable media"）
```

直查系统库确认 `content://media/external/video/media/89951` 已不存在（`No result found`）。
同期的 5 张失效图片（`IMG_20260827_*.jpg`）都走了"选中 → 读失败 → `Dropped unreadable media`"，
每张只出现 3 行就从库里删掉了 —— 说明清理逻辑本身是对的，只是**视频路径漏了调用**：
`onVideoStartFailed()` 只把 id 加进内存 `failedMediaIds`（仅本次引擎会话有效），
而图片路径和 GIF 路径都调用了 `dropMediaIfGone()`。修法即在该函数补一行
`dropMediaIfGone(failedId)`（`MediaProbe.isGone` 只在"文件真的没了"时返回 true，
对"被更新的切换打断"这类瞬时失败不会误删）。

模拟器复现验证（造一条指向删掉文件的 VIDEO 行 id=52）：

```
03:22:29.274 D Switch to: wsdead.mp4 (VIDEO) id=52
03:22:29.339 W Dropped unreadable media wsdead.mp4 id=52 (file is gone)   ← 新增行为
03:22:30.829 D Switch requested: recovery target=null                     ← 1.5s 后恢复换成图片
sqlite: select count(*) from wallpaper_images where id=52  →  0           ← 行已删除
```

**② `poke()` 每次改设置都重启一次服务**

```
11:10:29.444 / 29.905 / 30.188 / 31.600 / ... / 35.685   共 10 次
D WallpaperSwitchService: Service started, action=null
D WallpaperSwitchService: Home timer disabled, stopping home loop
```

`poke()`（改动分组启用/应用位置、锁屏定时开关、导入媒体时调用）在服务已运行时仍走
`startForegroundService()`，于是每次改动都会重跑 `startForeground()`/`createNotification()`
并取消+重建两个定时协程，日志里 6 秒内 10 次的"Service started"就是纯噪声。
改为：伴生对象保存活实例（`activeInstance`，`onCreate`/`onStartCommand` 赋值、`onDestroy` 清空），
服务活着时直接 `wakeLoopsInPlace()` 就地重建循环；实例不存在/已销毁才回退到真正的服务启动。
同时把"Home timer disabled, stopping home loop"改成**每段禁用期只记一次**，避免就地唤醒后重复刷屏。

模拟器验证：

```
03:23:40.711 D WallpaperViewModel: setGroupTarget: group=1 target=HOME
03:23:40.713 D WallpaperSwitchService: poke: timer loops re-evaluated in place   ← 不再是 Service started
03:23:48 / 03:23:58 / 03:24:08  Switch requested: timer target=null              ← 定时循环照常按 10s 节奏跑
```

#### 4.9.23 内存优化：按实际显示尺寸解码（曾试验的"仅图片时用静态壁纸"已按反馈撤销）

Redmi 平板（1200×2608）实测 PSS 在 165–215 MB 之间波动、Native Heap 33–102 MB（绝大部分是壁纸位图），
CPU 在"纯壁纸：10 秒换一张图 + 10 秒写一次锁屏"状态下平均 36.8% 单核。据此做了下面这项优化：

**解码直接输出"真正会显示"的尺寸**

`BitmapFactory` 的 `inSampleSize` 只能按 2 的幂降采样，于是 12MP 相机照片（3024×4032）在
1200×2608 屏幕上被整张保留：48 MB/张。但 FILL 模式真正会显示的区域只有 1956×2608（20 MB），
其余像素永远到不了屏幕。新增 `displayTargetSize()` 计算该尺寸，并通过 `inScaled` +
`inDensity/inTargetDensity`（只当比例载体用）让解码器**直接按目标尺寸解码**（JPEG 走 libjpeg 的
缩放解码，不产生全尺寸中间位图）；解码后把 `bitmap.density` 置为 `DENSITY_NONE`，避免 Canvas
再按密度缩放一次。

- FILL/STRETCH：目标是"刚好覆盖屏幕"（裁剪仍由 GPU 四边形完成，比例不变，旋转判定与四边形计算都不受影响）；
- FIT / 静态壁纸：目标是"刚好装进屏幕"，源图小于屏幕时不下采样（不放大）。
- 同时修正 `decodeWithKnownSize()` 的"元数据过期"校验：原本拿 `源尺寸/采样` 比对解码结果，现在按目标
  尺寸比对，否则每次解码都会误判为"存储的尺寸过期"并重读一次。

模拟器实测（屏幕 2560×1600，源图 3000×2000）：

```
之前: Static bitmap: 3000x2000 for img1.jpg / Bitmap loaded: 3000x2000
之后: Static bitmap: 2560x1707 for img1.jpg / Bitmap loaded: 2560x1707   ← 只解码会显示的像素
```

**真机踩到的坑（已修 + 已加回归测试）**：`BitmapFactory` 把 `inDensity/inTargetDensity` 的比例叠乘在
`inSampleSize` **之上**，而不是替代它。第一版用 `inDensity = 源宽`，于是 sample=2 的大图被多缩了一半
（6048×8064 的照片解成 978×1304 而不是 1956×2608），运行期还会把它当成"存储的元数据过期"再重读一次：

```
11:50:00 W BitmapUtils: Stale media metadata for .../1813: decoded 978x1304, expected 1956x2608 - re-reading
```

修法：`inDensity` 必须是**经过 `inSampleSize` 之后的宽度**（见 `decodeScale()`），并用
`BitmapDecodeSizingTest` 锁住这条算术（含 6048/2 × 1956/3024 == 1956）。

真机实测（Redmi 平板 1200×2608，同一张 12MP 照片）：

```
修复前: drawCurrentImage bitmap loaded: 3024x4032   (48 MB/张)
第一版: bitmap loaded: 978x1304 + Stale media metadata 警告（多缩一半 + 多一次重读）
修复后: drawCurrentImage bitmap loaded: 1956x2608   (20 MB/张，−58%)，无 Stale 警告
```

同一台设备的内存变化：Native Heap 峰值 102 MB → 31 MB，Graphics 31–67 MB → 27.7 MB，
`TOTAL SWAP PSS` 31 MB → ≈0（系统不再把本进程换出），PSS 峰值 215 MB → 95–97 MB
（最后一个数字采于息屏暂停状态，量级参考；位图本身的 −58% 与解码尺寸日志是确定值）。

> 曾经实现的"仅图片时用静态壁纸"开关（分组无视频/GIF 时改走静态写入并释放动态壁纸引擎，
> 模拟器实测 PSS 89.5 MB → 61.3 MB）已按用户反馈**撤销**：它会替换掉用户在系统里选定的动态壁纸，
> 之后分组里再出现视频/GIF 时又得手动重新设置，代价与体验不成正比。撤掉的部分包括设置项、
> 服务里的判定与 `runStaticTick(allowReplacingLiveWallpaper)` 旁路；遗留的
> `static_when_image_only` 设置行由 `WallpaperSwitcherApp.OBSOLETE_KEYS` 在启动时清理。
> 需要进一步省内存/省电时，优先调大桌面与锁屏的切换间隔。

#### 4.9.24 内存实测与界面缓存治理（缩略图缓存）

对 Redmi 平板（1200×2608，素材库 990 文件夹 / 65298 媒体）做了一次完整的 `dumpsys meminfo` 采样，
"桌面在前台、我们的 Activity 留在后台任务栈、动态壁纸引擎运行、桌面/锁屏各 10s 定时"这一常见状态下：

```
TOTAL PSS 273–285 MB（均值 282），TOTAL RSS ~320 MB，TOTAL SWAP PSS 135–151 MB（进程总足迹约 420 MB）
  Bitmap (malloced): 2662 个 / 92–115 MB   ← 最大单项：Coil 缩略图内存缓存
  EGL mtrack: 64.6 MB                      ← 应用两个窗口 + 动态壁纸引擎的 GL 表面
  Native Heap 21 MB 常驻（alloc 148 MB，其中 125 MB 被 zram 换出）
  Java Heap 13.8 MB、Gfx dev 5.4 MB、SQLite ~7 MB、Code/.so/.dex ~14 MB
Objects: Activities 1, Views 16, ViewRootImpl 2, AppContexts 17, WebViews 0
```

三分钟内位图数量 2662→2664→2662、PSS 稳定，**不是泄漏**；对比"刚安装完、UI 未加载、只有引擎"的
95–97 MB，多出来的约 190 MB 全在界面上：缩略图缓存 + 两个应用窗口的 EGL 表面。

治理（两处，均为小改动）：

1. **UI 退到后台 60 秒后主动清缩略图缓存**（`WallpaperSwitcherApp.scheduleThumbnailCacheTrim()`，
   `MainActivity.onStop/onStart` 成对调用）。系统的 trim 回调在这台设备上始终没来，缓存于是一直留着；
   现在离开界面一分钟就释放，若用户很快返回（系统动态壁纸选择器、临时切应用）则在 `onStart` 里取消，
   不会白丢缓存。磁盘缓存不动，重进时只是重新解码可见缩略图。
2. **Coil 内存缓存上限 20% → 10%**（`initCoil()` 的 `maxSizePercent`）：上限从约 100 MB 降到约 50 MB，
   代价只是滚动时多几次解码（磁盘缓存仍在）。

模拟器验证：

```
离开 App 20s：无清理                                        ← 延迟生效，避免误清
离开 App 60s：Thumbnail memory cache cleared (UI hidden)     ← 到点清理
20s 内返回并在 App 内待满 60s：无清理                        ← onStart 取消生效
再次离开 60s：Thumbnail memory cache cleared (UI hidden)
```

真机（12:47 更新后、进程刚重启）：PSS 55.5–75.3 MB、Native 12.4–34.7 MB、Swap ≈0、更新后 0 条 E/W；
用户下次打开 App 再离开时，原来的 92–115 MB 缩略图缓存会在 60 秒后释放掉。

#### 4.9.25 「自动旋转适配」对"适应"模式也生效

原来这条规则只作用于 填充/拉伸（`fillRotationFor` / `shouldRotateMediaForScreen` /
`refreshVideoQuad` 三处各自判断，都写着 `scaleMode == FILL || STRETCH`）。按反馈改成**三种模式都生效**，
并把判定收敛到一处：

- 新增 `BitmapUtils.quarterTurnFor(srcW, srcH, screenW, screenH, scaleMode, rotateMismatch, clockwise)`：
  "方向与屏幕不符就转 90°"的唯一规则，`fillRotationFor()`、渲染器的视频四边形、
  `LiveWallpaperService.shouldRotateMediaForScreen()`（GIF 帧）全部改为调用它，避免以后再各改各的。
- 适应（FIT）下旋转的实际意义：方向不符的素材在 FIT 里受**屏幕短边**限制，转过来之后显示面积大得多，
  而且仍然完整可见（不裁剪）。例：1920×1080 在 1200×2608 屏上，转前 1200×675（占屏高 26%），
  转后 1200×2133（占屏高 82%）。
- 顺带修正解码尺寸：`chooseSample()` / `displayTargetSize()` 现在按**旋转后**的覆盖/适配矩形计算。
  之前 FILL 用的是"未旋转的覆盖"（永远偏大，白解码），FIT 用的是"未旋转的适配"（旋转后会被放大变虚）。

模拟器实测（屏幕 1600×2560 竖屏、源图 3000×2000 横图）：

```
适应(FIT) + 自动旋转: Bitmap loaded: 2400x1600 rotate=cw
      （旋转后的适配区域 1600x2400 = 4.4 MP；修正前会按未旋转的 1600x1066 解码 → 显示时放大发虚）
填充(FILL) 静态路径:  Static bitmap: 1707x2560
      （旋转后的覆盖区域 1707x2560 = 4.4 MP；修正前按未旋转覆盖解满 3000x2000 = 6 MP）
```

设置项文案同步更新为"填充/拉伸/适应时…（适应模式下显示面积也会明显变大）"；
`BitmapDecodeSizingTest` 新增两条用例（FIT 也旋转 + 旋转后尺寸按交换后的长短边计算），共 101 条单测通过。

> 补丁：视频的 FIT 解码上限也要按"旋转后"的适配尺寸算。真机日志暴露过这一点——切到「适应」后，
> 4K60 横屏视频在竖屏上按**未旋转**的适配尺寸被解成 `1500x842`，显示时被 GPU 放大 1.7× 发虚；
> 修正为按旋转后的适配尺寸（`fitW/fitH` 交换）计算，同一素材现在解 `2464x1386`，显示 1200x2133 时
> 约 1:1，清晰。位置：`WallpaperRenderer.startVideo()` 的 `decodeCapBase`（FIT 分支）。

#### 4.9.26 打开应用时立刻静音 + 立刻隐藏悬浮按钮

反馈："打开应用时，声音无法立刻关闭，还有悬浮旋钮"。

原因：静音与隐藏都只依赖 `onVisibilityChanged`，而系统这条回调**晚于窗口动画**——应用已经显示出来了，
引擎仍认为壁纸可见。模拟器实测这条回调比 `MainActivity.onStart` 晚 **342ms**，加上悬浮按钮原本
`FLOATING_BUTTON_HIDE_SETTLE_MS = 400ms` 的"被覆盖"防抖，于是：视频音还要响约 0.3–0.7 秒，
按钮也要多留约 0.7 秒。

修法：让 Activity 显式告诉引擎"我的界面在前台"。

- 新增 `LiveWallpaperService.setAppForeground(Boolean)`（伴生对象，进程级标志，引擎冷启动时读取）；
  `MainActivity.onStart/onStop` 成对调用。
- 引擎新增 `applyAppForeground()`：前台 → `setPowerSave(true, "app-foreground")` + `hideFloatingButtonNow()`；
  离开 → 若壁纸真的可见则 `setPowerSave(false, "app-left")`，并重算悬浮按钮。
- 悬浮按钮的显示条件加上 `!appInForeground`，且前台时走**立即隐藏**分支（不再等 400ms 防抖）。
- 音频再加一道保险：`WallpaperRenderer.powerSaveMode` 的 setter 在**进入省电态时直接
  `audioSession.pause()`**（`AudioTrack.pause()` 线程安全、不丢缓冲），所以熄屏/被覆盖/自己打开应用
  都在同一瞬间静音，不必等音频线程的下一个 PCM 缓冲（约 20ms 起）。

模拟器实测（动态壁纸播放视频时打开应用）：

```
05:18:14.512 App UI foreground: mute audio + hide floating button   ← onStart（立刻静音）
05:18:14.514 Power save ON (app-foreground)                          ← 同一帧
05:18:14.640 Floating button hidden                                  ← 126ms 后按钮已消失
05:18:14.854 Wallpaper covered: pausing decode/audio                 ← 系统的可见性回调晚 342ms（现在已不影响静音）
回到桌面：Power save OFF (visibility) → App UI hidden → Floating button shown
```

#### 4.9.27 全量代码审查后的修复（三批）

对全部 17,586 行 Kotlin 做了一次审查（逻辑问题 + 无效代码），按确认的顺序分三批修完：

**第一批（死代码 + 日志 + 光标一致性）**
- 删除 `BitmapUtils.rotateForFill()`（无调用，注释还与现行"FIT 也旋转"矛盾）与
  `LiveWallpaperService.PREFETCH_MAX_TIMER_INTERVAL_MS`（未使用）；去掉
  `quarterTurnFor()/wantsQuarterTurn()/fillRotationFor()/shouldRotateMediaForScreen()` 里
  已无用的 `scaleMode` 形参（此前是编译器唯一的警告源）。
- `AppLog`：批量的 D 日志现在**写完就排一个 250ms 的延迟 flush**。原来 flush 只在下一条日志到达时
  才评估，于是"一串突发日志 + 之后安静"时最后几行会一直留在 8KB 缓冲里（真机上看过"日志冻结"，
  误判过一次卡死），进程被杀还会丢尾。
- 新增 [MediaDrop.kt](../../app/src/main/java/com/wallpaperswitcher/engine/MediaDrop.kt)：
  `dropGoneMedia()` / `clearMediaCursors()` 成为"删失效媒体 + 清光标"的唯一实现。此前引擎只清
  `LAST_IMAGE_ID`、静态路径一个都不清、UI 删除清全部五个键。

**第二批（收敛重复实现）**
- 类型判定统一到 `MediaTypes`：19 处裸字符串 `"VIDEO"/"GIF"/"IMAGE"` 改为常量或
  `MediaTypes.isMotion()`（MediaTypes 的注释自称唯一来源，历史上就因为一处"比较自己"让所有文件
  都通过了体检）。
- GIF 帧旋转 `rotateBitmap90()` 改为调用 `BitmapUtils.bakeQuarterTurn()`（原来是第二份 90° 旋转实现）。
- 新增 `BitmapUtils.displaySpan()`：旋转后"哪条边才是长边"的唯一规则，图片解码目标、视频 FIT 解码
  上限、GIF 帧尺寸三处共用（视频那处正是之前漂移出 1500x842 的地方）。

**第三批（行为与并发）**
- 导入按 URI 去重：`addImage()` / `addImages()` / `addFolder()` 与自动扫描 worker 用同一条规则，
  重复选择同一文件不再产生第二行（模拟器实测：同一张图连选两次，分组计数保持 7 不变）。
- 离开应用时按"进应用前壁纸是否可见 + 屏幕亮 + 无锁屏"三个条件**乐观恢复**播放/声音，避免
  系统"壁纸重新可见"回调慢（真机 1.5–2.5s）造成的静音；不满足条件时仍等回调。
- `WallpaperSwitchService.surfaceRetryCount` 加 `@Volatile`（广播接收器与定时循环都会写它）。

验证：单元测试 **102 条全通过**，`compileReleaseKotlin --rerun-tasks` **零警告**；模拟器实测打开应用
2ms 静音、45ms 隐藏按钮，日志文件突发后 0.6s 内落盘；小米平板覆盖安装后 0 条 E/W、引擎与定时正常。

#### 4.9.28 减少系统照片/视频访问次数

先量了现状（小米平板日志，94 次切换）：稳态下每次切换已经是 **1 次相册读取**
（74 次直接用预取位图 + 20 次按需解码，预取浪费仅 3 次），空闲时 0 次，文件夹扫描由 MediaStore
generation 缓存（`media store unchanged (gen=6877), reusing 28 folders`）。剩下的可省项只有两类：

**① 首次解码：3 次读取 → 1 次**

`decodeAndLearnSize()`（每个媒体在本进程里的第一次解码）原来是"开流读 bounds + 再开流解码 +
再开流读 EXIF"。现在只向 provider 要 **一次**文件。

> **2026-09-25 修订**：最初用 `mark(128KB)` + 多次 `reset()` 在同一个流上完成，AOSP 上有效，但在
> HyperOS 上失效——Redmi 平板日志里 695 次首次解码有 **603 次回退**到 3 次读取
> （`Learned media metadata (3 reads)`），原因是 MIUI 的 `ExifInterface(InputStream)` 读取量超过
> 128KB mark 窗口（同一批文件在模拟器上只读 39KB，实测对比），`reset()` 失败即回退。
> 现在改为**缓冲文件头**：`readHead()` 一次最多读 256KB 到内存，EXIF 与 bounds 都从这个
> `ByteArrayInputStream` 解析，解码用 `SequenceInputStream(头部, 剩余流)` —— 全程
> **只开一次 provider 流、完全不依赖 mark/reset**。头部超出 256KB 的罕见文件仍走
> `decodeWithFreshStreams()` 兜底。

- EXIF 必须在流的**开头**读（ExifInterface 的要求），所以顺序是 EXIF 先、bounds 后
  —— 这一点第一版写反了，在提交前用自造的 EXIF 样例抓出来并修正。
- 只有 JPEG 会读 EXIF（用两字节 SOI 魔数判断，不再额外 `getType()`），JPEG 的 EXIF 段 ≤64KB，
  因此每次 rewind 都在 128KB 窗口内；万一某 provider 超限，`decodeWithFreshStreams()` 作为兜底
  按老流程（3 次读取）走，正确性不受影响。
- 两条路径共用 `finishLearnedDecode()`（烘焙 EXIF 旋转、判定屏幕方向旋转、写回学习结果、日志），
  日志会写明本次花了几个 read：`Learned media metadata (1 read(s), now stored)`。

验证（模拟器）：把一张图的 stored width/height 清 0 后切换 → `(1 read(s), now stored)`；
自造一张带 EXIF Orientation=6 的 JPEG（在本地给 img5.jpg 插入 APP1/EXIF 段）→ 学到的元数据是
`3000x2000 rotationDegrees=90`，说明 EXIF 仍被正确读取并烘焙。

**② 自动扫描：库没变就整轮跳过**

`FolderAutoScanWorker` 原来每次运行都要对每个已导入文件夹各查两次（图片表 + 视频表）。现在先取
`MediaScanner.currentGeneration()`（API 30+，provider 增删改媒体时自增），与上次成功后记录的
`auto_scan_last_generation` 相同就直接返回，并记 `Auto-scan skipped: media store unchanged (gen=…)`。
SAF 树目录（`content://`）不在 MediaStore 里，存在这类目录时该捷径自动关闭。

验证（模拟器）：第一次运行正常扫描（8 items）并记录 `auto_scan_last_generation|656`；第二次运行
`Auto-scan skipped: media store unchanged (gen=656), 1 folders` —— 省掉"文件夹数 × 2"次查询。

仍可再省（本次未做，收益小/风险中等）：
- 视频开启声音时，视频解码与音频解码各开一次文件（2 次 provider open/视频）；可改成共享同一
  `AssetFileDescriptor`。**已单独验证：原方案不可行**（见 §4.9.29 末段）。
- 界面里滑动分组网格时的缩略图读取属于"用户在看图"，只能靠更小的缩略图/缓存上限控制（已做）。
- 访问频率的最大来源仍是**切换间隔**本身：定时 10 秒 = 每分钟 6 次读取，调大间隔最直接。

#### 4.9.29 功耗优化：静态写入改 JPEG 流、锁屏不可见不写、GIF 按真实帧率、音频不再空转

先量现状（小米平板 24117RK2CC，1440×3200@60Hz，Android 17，锁屏定时 10 秒）：

| 场景 | app 进程 CPU |
| --- | --- |
| 熄屏（Dozing） | 0.17% 单核（30s 内 0.05s） |
| 亮屏 + 桌面 + 锁屏定时 10s | **33%** 单核（60s 用 19.8s，同窗口日志 6 次锁屏写入） |
| 模拟器：720p/10fps 视频壁纸 | 2.6% 单核（硬解在 codec 进程） |

最大的一项不是视频渲染，而是**锁屏定时**：用户在桌面/应用里时锁屏壁纸完全不可见，却每 10 秒
把一张 2316×3088 的位图重新编码写一次。

**① 静态写入：`setBitmap` → JPEG + `setStream`**

`WallpaperManager.setBitmap()` 会在**调用方进程内**把整张位图 **PNG 压一遍**再交给系统
（AOSP 14 `WallpaperManager.java:2102` `fullImage.compress(PNG, 90, fos)`）。平板实测每次
`Static bitmap → Wallpaper applied` 要 2.2–3.7s。现在 `WallpaperApplier` 改为
`Bitmap.compress(JPEG, 95)` → `setStream()`（`setStream` 只拷贝流，不做重编码），
JPEG 编码失败时回退 `setBitmap`：

- 平板：`Wallpaper JPEG: 2316x3088 1789KB in 68ms`，整次应用 **104ms**（≈30 倍），
  写入系统壁纸的文件也从 PNG（数十 MB）变成 ~1.8MB。
- 模拟器对照（2560x1707）：PNG 路径 135–145ms → JPEG 路径 27ms + 应用 29ms。
- 画质不变：位图像素、分辨率、裁剪语义都没动，只是换了编码格式（屏幕 1:1 显示下 JPEG 95 不可辨）。

**② 锁屏不可见时不应用**

`runLockSwitchLoop()` 新增 keyguard 门控：屏幕已亮但锁屏未显示（用户在桌面/应用里）时，
**定时到点仍然切一次**（避免长时间亮屏使用后锁屏壁纸完全停在旧图上），随后本次亮屏就保持空闲
——不再每个间隔都为一个看不见的槽位做全分辨率解码+编码，只保留每 60s 一次的重新检查
（兜底漏发屏幕广播的 ROM）。锁屏重新可见、或屏幕熄灭后再次亮起，都会重新允许这一"一次"。
锁屏可见期间仍按设定间隔正常切换。

真机验证（小米平板，Android 17 / HyperOS）：解锁停在桌面时，60 秒内 `Wallpaper applied (lock)`
最多 **+1**（定时到点那一次），随后进程 CPU 0.1% 单核（改前同场景 33%，每个间隔都写）；
按电源键锁屏再点亮并停在锁屏界面时，每 10 秒照常换一张（16:37:39 / :49 / :59 三次，每次 65–125ms），
熄屏后立即停止。即 MIUI 的 `isKeyguardLocked()` 上报与标准一致，门控不会误伤锁屏定时。

模拟器回归（AOSP 14）：亮屏+桌面 75 秒 `Wallpaper applied (lock)` 21→22（+1），日志
`Lock wallpaper switched (lock screen not showing: the one allowed switch of this screen-on)` +
`lock timer idle (one switch already done)`；随后 24 秒不再增长。锁屏界面保持显示时按 10 秒间隔
连写（10:09:13 / :23 / :33）。

**③ GIF 按自身帧延时上传**

原来固定 50ms ticker（20fps）采样 GIF，无论 GIF 本身多慢；本应用的样例 GIF 都是 120ms/帧，
于是每帧被"栅格化 + 上传纹理"2.4 次。`AnimatedImageDrawable` 只在开头几次通过
`Drawable.Callback` 报告下一帧时间（设备实测 3 秒内只回调 6 次），不能当时钟用，因此新增
`GifTiming`：解析 GIF 的图形控制扩展，得出每帧延时，ticker 按 `delay + 8ms` 走一帧
（多出来的 8ms 保证每 tick 都落在帧边界上；解析失败/非 GIF 动画则回落到原来的 50ms）。
解析器是纯 Kotlin，配 7 个单测（含全局色表、局部色表、注释扩展、截断流、非 GIF）。

验证（模拟器，600×400/120ms GIF）：日志新增 `GIF timing: 10 frames, first delays [120, …]ms`；
GifRender 线程 30s 内 330ms（≈1.1% 单核，约 7.8 次/秒），三张间隔 1s 的截图哈希各不相同（动画正常）。

**④ 音频解码不再 5ms 轮询**

音频循环原来用 `dequeueOutputBuffer(info, 0)` + "没进展就 sleep 5ms"（最高 200 次/秒唤醒）；
现在没有输入可喂时直接**阻塞等待编解码器**（10ms 超时）。`AUDIO_IDLE_SLEEP_MS` 随之删除。
写 AudioTrack 的 5ms 重试保留：那是缓冲满时的节流机制，改成阻塞写会在锁屏暂停时挂住线程。

**⑤ 手动切换不再淡入（悬钮"不跟手"）**

淡入的第一帧是**全黑**，再用 8×25ms 渐显（`startFadeSteps`）。用户点击悬钮后要等"解码 +
这段 200ms 黑场"才看到新图，观感就是"点了没反应/不跟手"（日志实测：点击→请求 2ms，但
点击→完成平均 149ms，其中选媒体 131ms + 就绪 67ms，外加黑场淡入）。

现在**用户主动触发的切换**（悬钮 `floating-tap`、桌面双击 `double-tap`、应用内"立即切换壁纸"
`manual`）一律**跳过淡入**，新图解码完成即呈现（日志：`Manual switch, skipping fade (instant
response)`）；「切换过渡动画」设置仍然作用于自动切换（定时 / 解锁 / 恢复）。模拟器实测：
点击悬钮 → 26ms 选中媒体 → 143ms 完成，无黑场。

**顺带修掉的真实缺陷：视频循环后声音消失**

加了"线程退出原因"诊断后，日志显示 `Video audio: thread finished (PCM write refused (pass=1) …
passes=2)`：视频进入下一轮播放（正常循环边界）时，音频正在写本轮最后一块 PCM，
`audioWriteStillWanted()` 判定"本轮已过期"，而旧代码把这当成致命错误直接结束整个音频线程——
于是循环播放的视频第一轮之后就没声音了。现在这种情况只结束**本轮**（`passDone`），
线程继续等下一轮。模拟器验证：音频线程跨 15+ 轮循环持续存活（`/proc` 中 VideoAudio 线程一直在）。

验证与回归：`assembleRelease` + `testDebugUnitTest`（109 用例，含新增 7 个 GIF 时序用例）；
模拟器验证 ①②③④ 与音频路径（自造带 AAC 音轨的 mp4 做端到端验证）；平板 `install -r -d` 后
熄屏 30s CPU 为 0、无 E/W 日志，静态写入 104ms。

**仍不做：视频/音频共享同一 fd（原"方案 1"）**

平台源码（`NuMediaExtractor.cpp:138` `new FileSource(dup(fd), …)`）与设备实验都表明：两个
`MediaExtractor` 共用同一个 `FileDescriptor` 时会**共享文件偏移**，而 `FileSource::readAt_l`
是 `lseek64 + read`（非 `pread`）。同一个视频上并发跑两个 extractor：20 次里 18 次样本数据与
对照不一致（字节数变少、哈希不同、**不抛异常**，属静默损坏）。可行的替代是"一次 provider 打开 +
音频侧用 `MediaDataSource` + `Os.pread` 位置读取"，实验 20/20 与对照完全一致；收益是每个"有声
视频切换"省 1 次访问（平板真机会话里约 19 次），当前未实施。

#### 4.9.30 功耗 / 流畅度第三轮（lint 归零、静态写入可见性门控 + JPEG 复用、洗牌状态表化）

本轮源自一次全项目功耗/流畅性审计（27 条，按严重度排序后逐条落地）。下面只列**已实现**的部分；
未实施项见本节末尾。

**① lint 4 个 error 清零（其中 1 个是真崩溃）**

- `AppLog.appVersion()` 用了 `PackageInfo.longVersionCode`（API 28+），minSdk 是 26：
  Android 8.0/8.1 上导出日志报告会抛 `NoSuchMethodError`——**它是 `Error`，原来的
  `catch (_: Exception)` 抓不住**，直接崩。改为 `SDK_INT >= 28 ? longVersionCode : versionCode`，
  并把兜底 catch 放宽到 `Throwable`。
- `startGifTicker()`（`AnimatedImageDrawable.start/stop`）补 `@TargetApi(28)`；它只能从
  `playGif28()` 进入，注解把这件事告诉 lint。
- `WallpaperRenderer` 里视频 GL 初始化的 `if (reuseGl) … else handler.post { … }` 缩进错乱
  （lint `SuspiciousIndentation`），补花括号并整体重排缩进。

**② 添加媒体不再在 UI 线程查 provider**

`addImages()` / `addImage()` 走 `guardedWrite()` → `viewModelScope.launch`（主线程），
而 `resolveDisplayName()`/`mimeOf()` 是同步 provider 查询（`DISPLAY_NAME` 游标 + `getType()`
+ `DocumentFile` 兜底）。多选几百个文件时这是几百次**主线程** binder 调用，选完文件立刻掉帧。
现在整段解析放进 `withContext(Dispatchers.IO)`（与 `addFolder()` 一致），并且每个 URI 的
`getType()` 只取一次（原来媒体类型和"是否受支持"各取一次）。

**③ 导入进度与选中数不再整屏重组**

`scanProgress` 原来在 `GroupDetailScreen` 根作用域 collect，而导入时每 50/100 条就写一次 →
每个 tick 重新执行整个屏幕（含 `LazyVerticalGrid` 的 item lambda）。现在：

- 进度卡片抽成 `ScanProgressCard(viewModel)`，自己在内部 collect；
- 批量工具栏抽成 `SelectionToolbar(...)`，`selectedMap.size` / 全选状态只在工具栏内读；
- `WallpaperViewModel.publishScanProgress()` 把非空进度节流到 **200ms 一次**
  （清空/结束立刻发布，卡片不会赖着不走）。

**④ GIF 路径两处"熄屏仍在做事"**

- GIF 健康看门狗等屏幕亮的循环超时（60s）后**仍然**解码整屏首帧并 `showImage()`（`renderImage`
  没有省电守卫）——与文件自身"熄屏不解码"的规则矛盾。现在循环退出时若仍不可见/省电就直接返回，
  且轮询步长从 1s 改成 5s（最多 12 次唤醒而不是 60 次）。
- GIF ticker 的 `catch` 里只打日志、随后**无条件**按帧率自续：持续失败时每秒 20 条堆栈日志
  （每条都落盘）+ 20 次唤醒，永不停止。现在连续失败计数达 `GIF_FRAME_FAILURE_LIMIT=5` 就停止
  ticker、关闭 drawable 并走 `onGifFailed()` 恢复（与加载失败路径一致），成功一帧即清零。

**⑤ 静态写入只在目标屏可见时做（桌面侧）**

`runSwitchLoop()` 新增门控：**仅当动态壁纸引擎没在跑**（即走静态写入路径）且锁屏正覆盖桌面时
（`isHomeScreenVisible()` 为 false），不做写入，而是 `delay(60_000)` 后重新检查，**不消耗这个 tick**
——桌面重新可见时按既有 catch-up 逻辑立刻补切一次。

- 引擎在跑时不门控：引擎自己的 `powerSaveMode` 已经停掉不可见时的渲染，而把 tick 压后会让
  解锁后先看到旧壁纸再切一下，反而变差。
- 锁屏槽位保持 4.9.29 的既有策略（亮屏期间不可见时"仍然切一次"，随后空闲），那是明确的产品要求。
- keyguard 读取失败/服务缺失时默认"桌面可见"，与锁屏侧的默认相反——任何一侧的默认值都不能
  让另一侧的功能永久停摆。

**⑥ 静态写入的 JPEG 复用缓存**

静态写入的成本 = 全分辨率解码 +（含 alpha 时）整屏 ARGB 展平副本 + 整图 JPEG 编码。
小分组 / 洗牌牌堆回头时反复写同一张图，像素完全没变。现在 `WallpaperApplier` 缓存"媒体 + 旋转
偏好 + 存储的 EXIF 角度 + 屏幕尺寸"→ JPEG 字节（访问序 LRU，最多 2 条、单条 ≤4MB、TTL 15 分钟），
命中时只做一次 `setStream()`，日志会打印 `Static wallpaper from cache (…KB)`。

安全性：条目的"来源尺寸"会和媒体行里存的 `width/height` 比对，不一致（文件被替换）或超时就丢弃
（0 表示老数据没有尺寸，跳过这项检查）；`setStream` 失败会自动回退到重新解码。
`WallpaperSwitcherApp.onTrimMemory()` 会清掉这个缓存。

**⑦ 重绘重试去重 + GIF 单帧回退移出主线程**

- `drawCurrentImage()` 的 100ms 重试原来是"谁触发谁排队"：surface 事件、可见性变化、旋转可以
  各起一条 10Hz 主线程链，而 `switchInProgress` 被 15s 媒体加载超时拖住时链子永不结束。
  现在 `redrawRetryQueued` 保证同时只有一条重试在排队，`REDRAW_RETRY_LIMIT=200`（20s）是上限。
- GIF 的"非动画 drawable → 首帧"回退原来在**主线程**做整屏 `createBitmap` + `Canvas` 光栅化
  +（可能）整屏旋转复制；现在放到 GifRender 线程（`gifHandler?.post`），引擎销毁时回收位图，
  `post()` 返回 false（线程已退出）时直接关闭 drawable，避免泄漏。

**⑧ 洗牌状态表化 + DB 事务合并 + 轮询退避**

- `shuffle_shown` 表（见 3.1）：引擎每切一张只 `INSERT` 一行新 id；静态路径每次 tick 一行；
  牌堆重开是一次 `DELETE`。原来的"整串读写 + 写 app_settings 导致设置页 20 路 Flow 全失效"消失，
  连带的 `shuffleWriteMutex` / 写序号戳 / 1000 条阈值 / 30s 节流全部删除。
- 静态一次切换的"游标 + 写入簿记 + 洗牌行"合并进**一个** `db.withTransaction`；锁屏定时循环里
  重复的 `recordLockWrite()`（同一对键写两遍）删除。
- 轮询退避：`awaitRenderSurface()`（250ms → 500 → 1000ms 上限，且 `getSystemService` 提到循环外）、
  `withStaticApply()` 与锁屏守卫等待同样退避；视频看门狗在熄屏/被遮挡时把心跳从 10s 放宽到 60s。

**验证记录（本轮）**

- `:app:assembleDebug` ✅、`:app:testDebugUnitTest` ✅（**120 用例**，本轮新增 6 例
  `WallpaperJpegCacheTest`：键敏感度、TTL 边界、文件被替换、尺寸未知）、`:app:lintDebug` ✅
  （**0 error**，本轮前是 4 error / 23 warning）。
- **真机 schema 迁移**（小米平板 24117RK2CC，817 张媒体，原 `user_version=5`）：
  `install -r` + 启动后 `user_version=6`、`shuffle_shown` 建表、`shuffle_shown_ids` /
  `shuffle_shown_ids_lock` 已删除（只剩 `shuffle_all_count`）、817 行媒体完好，无迁移异常日志。
- **真机洗牌持久化**（同一平板，817 张媒体，SHUFFLE / 10s / 桌面可见，服务临时开启 50 秒）：
  `shuffle_shown` 的 HOME 行 1 → 8（10 秒一次，符合节拍），`shuffle_all_count=817`；
  日志 `sendSwitch(timer) engineRunning=true` → `Switch broadcast sent` →
  `Switch start: timer` → `Shuffle pick: deck=7/817 (saved=817) -> id=41311` → 加载并切换（约 300ms），
  全程无 E/W。验证后已把 `service_enabled` 还原为 `false`。
- **⑤ 可见性门控实测**（模拟器 AOSP 14：先 `am force-stop` 让引擎不在运行 = 走静态写入路径，
  再只在锁屏覆盖桌面时让服务跑起来）：日志出现
  `Home screen not visible (lock screen showing): home timer idle`，同一时段**只有**
  `Wallpaper applied (lock)`，桌面槽位一次写入都没有（10s 定时到点被门控挡下，没有做那次
  全分辨率解码+编码）；锁屏定时期间依旧每 10s 一次。同时说明 MIUI/AOSP 的 `isKeyguardLocked()`
  上报与门控预期一致。
- **⑥ 缓存命中实测 + 同机 A/B**（同一模拟器、同一进程、同屏态，锁屏定时 10s = 每分钟 6 次写入）：

  | 场景 | app 单核 CPU |
  | --- | --- |
  | 缓存命中（2 张交替，日志 `Static wallpaper from cache (31KB)`） | **3.75% / 4.18%**（两次） |
  | 缓存未命中（3 张图，每次都 `Static bitmap` + `Wallpaper JPEG 25–45ms`） | **6.52%** |
  | 两个定时都关（进程存活、引擎空闲） | **0.02%** |

  即命中把"整屏解码 +（含 alpha 时）展平副本 + 整图编码"整段省掉，单核占用从约 6.5% 降到约 4.0%
  （每次写入省 ~230ms CPU；编码本身只占 25–45ms，省下的大头是解码与内存分配）。
  剩余 ~4% 是每次 tick 的周边开销（十余条 SQLite 语句 + 壁纸 IPC），属下一轮可优化项。
- 模拟器同样完成 5→6 迁移（`user_version=6` + 新表 + 旧键删除）。

**本轮未做 / 未验证**

- A2（GIF 帧率上限）按要求**不改**：GIF 仍是"每帧 CPU 光栅化 + 整屏纹理上传"，是所有壁纸类型里
  最耗电的一类。
- ④（GIF 熄屏守卫、帧失败上限）、⑦（重绘重试上限、GIF 单帧回退移出主线程）没有设备级复现
  ——需要"GIF 壁纸 + 熄屏/持续失败"或"卡住的切换"这类场景，目前由单测 + 编译 + lint + 代码审查覆盖。
- 审计中其余中低项未纳入本轮：日志导出/清空的主线程 IO、Coil 缓存清理在主线程、
  `FolderPickerDialog` 无条件挂 `VideoFrameDecoder`、`FirstFrame.gif` 整只解码、
  `getScannedFolderPaths` 全表 DISTINCT 无索引、SAF 文件夹使自动扫描 generation 短路失效、
  `GifTiming` 每次切换重新解析、静态 apply 每次新建线程、`ioScope` 进程级不取消、
  `AppLog` 限行不限行长、以及上面量到的"每 tick 十余条 SQLite 语句"的周边开销。

#### 4.9.31 桌面定时策略（用户需求）：应用在前台完全不切，回到桌面立刻补切一张

**需求（用户确认）**：
1. 应用在前台时，桌面定时**一次都不切**（不是"只切一次"）；
2. 退出应用 / 回到桌面后，**立刻补切一张**。

理由：桌面壁纸在应用后面看不见，每个 tick 都是一次全分辨率解码（静态模式下还要叠加 JPEG 编码 +
写入系统壁纸），而没人看得到结果。

**实现**（`WallpaperSwitchService.runSwitchLoop`，纯策略在 `SwitchPicking.shouldIdleWhileAppInForeground`）：

| 状态 | 行为 |
| --- | --- |
| 应用不在前台（桌面 / 其他应用） | 按设定间隔正常轮换（不受影响） |
| 应用在前台 | **完全不切**：每 5s 只做一次兜底状态检查（`HOME_APP_HIDDEN_RECHECK_MS`），tick **不消耗** |
| 退出应用（`MainActivity.onStop`） | `setAppForeground(false)` + **poke 定时服务** → 重新检查发现已到期 → **立刻补切一张**（实测离开后 17ms 就发出切换），随后从这一刻起按正常间隔轮换 |
| 应用在前台时熄屏 | 屏幕熄灭路径照旧暂停整个定时 |
| 锁屏定时 | 独立（自己的 `lockHidden` 策略：亮屏期间不可见时仍切一次），不受影响 |
| 手动切换 / 悬浮按钮 / 双击 / 解锁切换 | 不受影响（只门控"定时"这一个来源） |

"应用在前台"的判据是 `LiveWallpaperService.isAppForeground()`：由 `MainActivity.onStart/onStop` 设置，
是**进程级**静态标志（UI 与定时服务同进程），壁纸引擎被 MIUI 杀掉重建也不丢状态。

**顺带修掉的一处观感问题**：A1 的锁屏门控重查从 60s 改为 5s（`HOME_HIDDEN_RECHECK_MS`）。
ROM 在解锁后的一瞬间仍可能上报 `isKeyguardLocked == true`，60s 的重查会把这一瞬放大成"解锁后桌面定时
一分钟不动"，看起来就像定时服务死了；5s 的检查只是一次 keyguard binder 调用 + 一次 DB 读，
且只在"亮屏 + 锁屏覆盖桌面"这个通常只有几秒的窗口里跑。

**实测（真机：小米平板 24117RK2CC，Android 17 / HyperOS，动态壁纸模式，SHUFFLE，间隔 10s）**：

| 阶段 | 日志证据 | 结果 |
| --- | --- | --- |
| 基线（用户停在别的应用） | `sendSwitch(timer)` 11:41:45 / :55、11:42:05 / :15 / :29 | 每 10s 一次 ✅ |
| 进入本应用 11:42:38 | 11:42:38.532 `App UI foreground…`；11:42:39.240 `App in foreground: home timer idle (no switch while the app is open)`；**随后 46 秒 0 次切换**（期间 4 个 tick 到期，全部被压掉） | 前台完全不切 ✅ |
| 回到桌面 11:43:24 | 11:43:24.776 `App UI hidden…`；11:43:24.778 `poke: timer loops re-evaluated in place`；**11:43:24.795 `sendSwitch(timer)`**（离开后 17ms）；11:43:25.594 `Switch done: timer`；11:43:34.814 下一次（+10s） | 立刻补切一张 + 恢复轮换 ✅ |

模拟器（AOSP 14，静态写入路径）同样验证过"前台 0 次切换 / 26 万次测量口径见 4.9.30"，
以及更早的"只切一次"版本；最终版本以真机数据为准。

单测：`SwitchPickingTest` 覆盖"前台必空闲 / 不在前台必不空闲"两条规则（`SwitchPicking.shouldIdleWhileAppInForeground`）。

#### 4.9.32 引擎可见性去抖 + 熄屏误报防护 + 日志脱敏（日志分析驱动）

来源：对真机 `runtime.log`（5705 行 / 17.5 小时）的统计 —— 0 个 W/E，定时切换中位间隔 10.0s、
中位耗时 220ms（最大 349ms），视频循环复用 codec 41 次、0 次卡死；同时发现三处可改：

**① 熄屏后收到"壁纸可见"回调会让引擎在不可见的屏幕上恢复解码**

日志里有 5 次 `Power save OFF (visibility)` 落在"熄屏"与"亮屏"之间，其中一次出现在熄屏后 **13 秒**
（其余 4 次紧挨着真正的 `Screen on`，属于正常先后顺序）。当时屏幕上恰好是静态图，代价只有一次重绘；
若那一刻在放视频/GIF，就会在没人看得见的表面上继续解码+渲染 —— 正是省电模式要防的事。

修法：把"要不要省电"抽成纯函数 `ScreenPowerPolicy.shouldPause(requestedPause, screenInteractive)`
（`engine/ScreenPowerPolicy.kt`），引擎在**真正应用**这个状态时再读一次
`PowerManager.isInteractive()`，所以"熄屏永远是暂停"不受回调顺序影响；
`isInteractive()` 抛异常时按"可交互"处理（宁可再生一次，也不能把视频壁纸永久冻住）。

**② 可见性回调成串到达，视频每次都要 pause/resume + 重锚播放时钟**

同一份日志里 17 小时出现 **139 次** ON<->OFF 翻转，最短间隔约 100ms（应用/窗口切换瞬间）。
现在 `setPowerSave()` 只对**恢复**方向去抖 `VISIBILITY_DEBOUNCE_MS = 250ms`，
**暂停方向立即生效**（隐藏壁纸要立刻停解码、立刻静音，这是之前"声音没立刻关"的反馈），
并发的成串回调最多只产生一次状态变化。屏幕上那一帧在整个过程中一直可见，所以 250ms 的恢复延迟不可感知。

**③ 日志里的完整 content URI / 目录名**

240 行含完整 SAF URI（最长 421 字符、合计约 40KB），并且带着用户的相册目录名
（percent-encoded 的中文）。日志文件是可以在设置里导出分享的。新增
`util/LogText.kt`（纯函数 + 单测）：`short()` 保留 scheme+authority 与最后一段
（`content://media/…/1230`），`folder()` 保留最后两级目录，两者都截断到 64 字符；
引擎、解码、视频、媒体扫描、自动扫描 worker、ViewModel 的对应日志行全部改用它。

**验证**

- 单测：`ScreenPowerPolicyTest`（暂停总是优先 / 仅屏幕亮时才允许恢复）、`LogTextTest`
  （空值、authority 保留、SAF 超长截断后不含个人目录名、plain path、目录取末两级、截断上限）。
  全套 **130 用例全绿**；`:app:lintDebug` **0 error**。
- 真机（小米平板）：新包启动后引擎 `Power save ON (visibility)`（熄屏/被遮挡），
  随后 **20 分钟熄屏期间零次 `Power save OFF`**（旧版会在熄屏后十几秒出现一次误恢复）；
  日志行已变为 `drawCurrentImage loading bitmap: content://media/…/724` ✅；无 E/W。
- 待长期观察的一项：ON<->OFF 翻转率（旧基线约 8 次/小时）。可用同一份日志统计脚本复查。

#### 4.9.33 视频「播放时间与文件时间不符」：两处缺陷（Redmi 平板日志分析）

日志窗口 08:16–13:25（5 小时；库内视频多为 1–4.5 小时长片 + 一个 11.4s 短片）。

**先排除速度问题**：可见播放期间 `Video render rate` 稳定 **30.0 fps**（源 30fps），11.4s 短片每
2.6s 完成一次 pass（文件 2.47s）——播放速率正确，问题出在**暂停/重启**。

**缺陷 1：锁屏暂停被误判为卡顿（已修）**

```
10:13:41 Power save ON (visibility)      ← 锁屏，视频按设计暂停
10:14:07 Power save OFF (visibility)     ← 亮屏
10:14:07 W Video stalled: no frame for 24s; recovering   ← 误判
10:14:07 W Video failed to start; scheduling recovery switch  ← 视频被换掉
```

健康监控以 `now - lastVideoFrameAt > 12s` 判卡顿，而暂停期间本来就没有帧；亮屏瞬间
`powerSaveMode` 已为 false、新帧还没到，于是把 24s 暂停当成卡顿。
修法：`applyPowerSave()` 由暂停转回可见时调用 `renderer.resetVideoFrameClock()`
（亮屏广播那条路径在"可见性防抖已经恢复过"的场景会被 `setPowerSave` 的等值早退跳过，必须在这里补）。
模拟器验证：熄屏 22s 后回来，日志不再出现 `Video stalled`。

**缺陷 2：锁屏释放后媒体不回来，视频停在首帧（已修）**

```
05:21:45 Video session released after 10s locked (power save)
05:21:59 Power save OFF (visibility)
（此后 20 秒无任何 Video started / drawCurrentImage）
```

`onVisibilityChanged(true)` 立刻派发的重绘撞上 `setPowerSave(false)` 的防抖窗口（恢复延迟
`VISIBILITY_DEBOUNCE_MS` 才生效），`drawCurrentImage()` 在 `powerSaveMode == true` 时直接
`return` 且不重试 → 被释放的视频永远停在首帧，播放位置与文件时间完全脱节。
修法：该分支在"可见但省电标志尚未落下"时改为 `retryDrawCurrentImageSoon()`（100ms 步进、有上限）；
真正被覆盖时仍直接返回，不做无谓重试。

**仍属设计行为（未改，待确认）**：锁屏超过 10s 释放解码器后，回到桌面是从头播放；隐藏期间的暂停
也会让播放位置落后墙钟（日志 30 次 `Playback was Xms behind; re-anchored`，落后量≈隐藏时长）。
「接着上次位置继续播放」已按需求实现，见 §4.9.34。

#### 4.9.34 锁屏释放后「接着上次位置继续播放」

背景：锁屏超过 10s 会释放解码器省电（`MEDIA_RELEASE_AFTER_SCREEN_OFF_MS`），回到桌面时媒体会被
重新应用；原来这次重新应用是从 **0 开始**——1 小时的长片看到一半、锁一次屏就回到开头，用户反馈
「视频播放时间与文件时间不符」。

实现（三处配合）：

1. **记住位置**：`WallpaperRenderer` 每呈现一帧就把 `lastVideoPositionUs` 更新为该帧 PTS；
   释放会话前引擎把它和媒体 id 一起存进 `resumeVideoPositionUs` / `resumeVideoMediaId`。
2. **重建时续播**：`startVideo(uri, scaleMode, startPositionUs)` 把位置传给渲染器；`decodeLoop`
   的**第一轮**在选好视频轨后 `ext.seekTo(position, SEEK_TO_CLOSEST_SYNC)`（后续轮次仍回到 0，
   循环播放不受影响）。音频线程同样在**第一轮** seek 到该位置，保证声画同步。
   记住的是 PTS 而非帧号，定位到最近的关键帧（通常几秒内），不会黑屏。
3. **静止画面也来自该位置**：`FirstFrame.video(context, uri, positionUs)` 用
   `getFrameAtTime(positionUs, OPTION_CLOSEST_SYNC)` 取帧，重建期间屏幕不会闪一下片头。

边界：只有**同一个媒体**的下一次启动会消费这个位置（消费后清空）；切到别的视频、或用户主动切回
同一视频，都从 0 开始。若 `startVideo` 因表面未就绪提前失败，位置不会被消费，重试仍能续播。

日志：`Resuming video at Nms (lock release)`（引擎）+ `Video resumes at Nms (lock-release position)`
（渲染器）。

#### 4.9.35 点击图片只打开系统界面，确认后才设置

用户反馈「点击图片弹出系统动态壁纸界面，就设置了动态壁纸」。原因：`setAsLiveWallpaper()` 在打开
系统对话框**之前**先给引擎发了 `sendTargetBroadcast(image.id)`（本意是让系统对话框的预览显示
刚点的那张），于是壁纸在弹窗出现前就已经换掉——**即使点取消也已经生效**。

现在这段预切换被删除：点击图片只打开系统界面；确认时系统重新应用本动态壁纸，引擎启动后按
`LAST_IMAGE_ID`（= 刚点的那张）渲染并按分组「应用位置」校正，取消则屏幕上什么都不变。

预览并未因此变差：系统选择器会以 **preview 模式**启动我们的服务（日志 `Preview engine:
skipping slot enforcement`），预览引擎同样按 `LAST_IMAGE_ID` 渲染，所以预览里看到的仍是刚点的那张。

模拟器实测（AOSP 14）：
- 点击图片 → 只有 `setAsLiveWallpaper` + 提示条 + `LiveWallpaper.livepicker` 获得焦点，日志中
  **没有** `Switch requested` / `Switch to`；
- 按返回取消 → 之后仍无任何切换，`dumpsys wallpaper` 仍是本应用，home 引擎停在点击前的
  `clip.mp4`（`Switch to: clip.mp4` 仍是点击前那条）。

**真机复测后仍会"未确认就设置"的第二个原因（已修）**：Redmi 平板日志显示点击后
`Engine created` → 预览引擎渲染点的那张（预览正确）→ 用户返回取消 →
**真实引擎恢复可见时又 `drawCurrentImage loading bitmap: <点的那张>`**，桌面壁纸被换成点的那张。

原因：`setAsLiveWallpaper` 为了让选择器的预览显示点的那张，把 **HOME 游标**
`LAST_IMAGE_ID` 改成了它；取消时只清理了锁屏游标（`clearManualPick`），没有把 HOME 游标放回去，
于是真实引擎恢复可见时按"游标 ≠ 当前显示"的规则重新应用了预览过的那张。

修法（`LiveWallpaperService`）：
- 点击时记住旧的 HOME 游标（`notePreviewPick(previous, picked)`）；
- **待确认期间**（`hasPendingPreviewPick()`）真实引擎的可见性重绘先跳过，不跟随预览游标；
- 取消（预览引擎销毁且期间没有真实引擎接管）→ `restoreHomeCursorAfterCancelledPick()` 把游标放回，
  日志 `Pick cancelled: HOME cursor restored to N`；
- 确认（真实引擎 `onCreate` 出现）→ `realApplySincePick = true`，游标保留、待确认状态清空。

模拟器实测：点击 → 取消，日志只有 `Pick cancelled: HOME cursor restored to 59`，home 引擎**没有**
任何新的 `drawCurrentImage`/`Switch to`（壁纸保持原样）；点击 → 确认（Set wallpaper → Home screen），
真实引擎创建后加载点的那张（`GifTiming`/`GIF frame presented`），设置正常生效。

#### 4.9.36 平板旋转：视频不再从头播放（真机日志分析）

用户反馈（小米平板 25053RP5CC / Android 16 / 2136×3200）：**旋转屏幕后视频从头开始播放**。
旧日志里每一次旋转都是同一个三段式：

```
14:35:49.133 Surface size changed 2136x3200 -> 3200x2136; forcing re-render
14:35:49.170 startVideo: content://media/…/30043
14:35:49.355 Video started: 1920x1080 @ 30fps          ← 从 0:00 重来（音频线程同时重建）
```

两个原因：

1. **引擎在旋转重绘时无条件重建视频会话**。`onSurfaceChanged()` 里屏幕尺寸变化会置
   `pendingOrientationRedraw`，`drawCurrentImage()` 的视频分支于是重新 `startVideo(...)`；
   而视频的解码尺寸只由文件决定（四边形在 `WallpaperRenderer.surfaceChanged()` 里已按新屏幕
   重算），根本没有需要重解码的东西——重启只是把**播放位置和音频会话一起丢掉**。
2. **续播分支是死代码**。上一轮为旋转写的 "继续上次位置" 判断写成
   `orientationRedraw && image.id == lastDisplayedId && videoMode`，但 `videoMode` 在它上面
   几行就已经被置为 `false`，条件恒不成立，于是即使被重建也照样从 0 开始。

修法（`LiveWallpaperService` + `WallpaperRenderer`）：

1. **能不停就不停**：旋转重绘时若 `orientationRedraw && r.isCurrentVideo(uri) && 视频仍在播放`，
   直接 `return@launch`（日志 `Rotation: video keeps playing (no restart)`），只调
   `refreshAfterAutoRotateChange()` 让四边形按新屏幕重算。解码线程继续喂帧，无黑帧、无音频重启。
2. **必须重建时续播**：`videoWasPlaying` 在 `videoMode = false` **之前**取出，重建分支改用
   `r.lastVideoPositionUs`（每帧更新的文件内 PTS）+ `isCurrentVideo(uri)` 判定，不再依赖被清零的
   标志或被 `onSurfaceDestroyed()` 归零的 `lastDisplayedId`。日志
   `Rotation: video continues at Nms (not from the start)` + `Video resumes at Nms (kept position, not the start)`。
3. **`isCurrentVideo(uri)`**：渲染器记住会话对应的 URI（`stopVideoInternal()` 故意保留，`stopVideoAndRender()`
   换成图片/GIF 时清空），所以「表面被旋转销毁」这种系统行为也能认出"还是同一个片段"。
4. 可见性路径（在别的应用里旋转后回桌面）同样不再先 `videoMode=false; clearCurrentBitmap()`，
   停着的解码器保持原样继续播（日志 `Back on desktop after rotation: video kept alive`）。

**平板实测（release 包，连续 5 次横竖切换）**：

| 旋转方向 | 日志 | 结果 |
|---|---|---|
| 横 → 竖（表面未销毁） | `Rotation: video keeps playing (no restart)` | 位置不变，无音频重启 |
| 竖 → 横（系统重建表面） | `Rotation: video continues at 19600ms` → `Video resumes at 19600ms (kept position, not the start)` | 从 19.6s 继续，不再回 0 |

连续两次实测位置 16.5s → 19.6s → 28.0s 单调推进（与墙上时间一致），证明续播用的是**文件内 PTS**、
不是墙钟；循环播放（每轮 `seekTo(0)`）不受影响。

**图片**：静态图在旋转时按**新的 Surface 尺寸**重新解码（日志
`drawCurrentImage bitmap loaded: 2136x3204`，旧方向是 `2667x4000`），四边形的 90° 决策由
`fillRotationFor(oriented, 新屏宽高, …)` 重算，重解码完成前由 `surfaceChanged()` 用旧纹理顶着，
不会黑屏。审核本轮真机日志未发现图片相关的异常/告警（E/W 只有"文件已被删除"的失效媒体）。

构建与测试：`:app:assembleRelease` + `:app:testDebugUnitTest`（130 条单测）全绿；
平板用 `install -r -d` 覆盖安装（不卸载、数据保留）。

#### 4.9.37 平板"无法设置动态壁纸"：MIUI 确认不重建引擎（真机日志分析）

用户反馈（小米平板 25053RP5CC / Android 16）：点击图片 → 系统动态壁纸界面 → 设置壁纸，桌面壁纸
**不变化**，连续三次都一样。App 日志每次都把它记成"取消"：

```
14:55:34.761 setAsLiveWallpaper: id=3808 target=BOTH      ← 用户点图片
14:55:34.894 Engine created, touch events enabled          ← 预览引擎
14:55:39.138 Live wallpaper picker closed without a new engine: pick cancelled
14:55:39.145 Pick cancelled: HOME cursor restored to 3813   ← 游标被回退，所点媒体从未生效
```

但系统侧证据表明用户**确实点了"设置壁纸"**：

```
(picker) 14:55:36.970 D WallpaperManagerImpl: notifyWallpaperComponentChanged, which = 3
(miui)   14:55:36.97  MiuiWallpaperManagerService::dispatchWallpaperChanged for reason=liveWallpaperSetting
```

根因：应用把"系统重建了真实引擎（`realEngineGeneration` 变化）"当作唯一确认信号
（§4.9.35）。当我们的动态壁纸**已经是当前壁纸**时，MIUI/HyperOS 重新应用同一组件**不会重新绑定
服务**——预览引擎关闭后没有任何新引擎，于是被判成取消：游标回退、`clearManualPick`、
`enforceSlotsAfterLiveApply` 全都不执行。用户看到的就是"设置壁纸没反应"。

修法（`LiveWallpaperService`，三处配合）：

1. **用系统壁纸 id 作确认信号**：预览引擎 `onCreate`（选择器刚打开）记下
   `WallpaperManager.getWallpaperId(FLAG_SYSTEM/FLAG_LOCK)`；预览引擎 `onDestroy`（选择器关闭、
   且已发生在系统 apply 之后、timer 被 poke 之前）同步比对，任一 id 变化即"已确认"。
   实测：确认后系统 id 3353 → **3354**（选择"锁定的屏幕"时锁屏 id 3357 → **3358**），取消时不变。
2. **确认后主动把所点媒体推给仍在运行的引擎**：只保留游标不够——引擎没被重建就仍在显示旧媒体，
   所以确认分支通过 `pushConfirmedPickToEngine(id)`（`ACTION_SWITCH` + `EXTRA_TARGET_ID`，
   与手动选择同一条队列路径）让它立刻渲染新媒体；引擎已显示该媒体时走"already playing, skip"。
   若当时没有活动引擎（静态模式），游标已保留，下次引擎启动会渲染它。
3. **确认仍然执行槽位纠正**：`enforceSlotsAfterLiveApply()`（锁屏静态化、视频/GIF 不上锁屏）
   在没有新引擎的情况下也照常运行，与真实引擎路径（§4.5）行为一致。

取消路径完全不变（id 不变 → 仍走 `clearManualPick` + 恢复 HOME 游标）。

**真机实测（release 包，走 App 内点击流程）**：

| 操作 | 日志 | 屏幕结果 |
|---|---|---|
| 点 id=3812 视频 → 设置壁纸 → 主屏幕和锁定屏幕 | `Pick confirmed … pickedHomeId=3812 prev=3813` → `Confirmed pick pushed … id=3812` → `Switch to: 1788046174120.mp4 (VIDEO) id=3812` | 桌面换成所点视频 |
| 点 id=3798 图片 → 设置壁纸 → 主屏幕和锁定屏幕 | `Pick confirmed … pickedHomeId=3798` → `Switch to: telegram@realmtldss - 010.jpg id=3798` → `Lock-targeted group (BOTH): picked media written to the lock screen=true` | 桌面换图，锁屏同步为该图 |
| 点图片 → 返回取消 | `Live wallpaper picker closed without a new engine: pick cancelled` → `Pick cancelled: HOME cursor restored to N` | 壁纸保持原样，无 `Switch to` |

日志无 E/W、无崩溃；`:app:testDebugUnitTest`（130 条）全绿。

#### 4.9.38 点击"设置壁纸"后的卡顿（真机计时分析）

用户反馈（小米平板 25053RP5CC / Android 16 / 3200×2136）：**点系统界面里的"设置壁纸"之后会卡顿**，
不像手机那么顺。日志计时把这段时间拆开看，问题是两件事叠在同一瞬间：

1. **确认后要等 1.5s 才动手**。`PICK_CANCEL_GRACE_MS`（取消判定用的等待）也被用在了确认路径上：

```
15:13:11.349 Pick confirmed ...            ← 用户已经点了"设置壁纸"
（此处空等 1.5s，桌面还是旧壁纸）
15:13:11.350 Confirmed pick pushed
```

   用户点完盯着桌面，1.5s 内"什么都没发生"，然后才突然换掉——这就是"卡一下"的主要来源。
2. **换壁纸 + 锁屏槽位纠正挤在一起**。确认路径紧接着调用 `enforceSlotsAfterLiveApply()`，
   它要解一张整屏位图并编码 JPEG（实测 `Wallpaper JPEG: 3840x2559 1999KB in 108ms`），
   还要和桌面切换、系统对话框关闭动画抢 CPU/GPU。

修法（`LiveWallpaperService` + `WallpaperRenderer`）：

- **确认走短等待**：新增 `PICK_CONFIRM_GRACE_MS = 250ms`（只有"已确认"才用），
  取消仍用 1.5s 的旧等待；确认后立刻 `pushConfirmedPickToEngine` 换壁纸。
- **槽位纠正延后 1.2s**（`PICK_CONFIRM_ENFORCE_DELAY_MS`）：桌面先换完、系统动画先结束，
  锁屏静态写入再跑，不再和用户正在看的画面抢资源。
- **省掉一次整屏 GPU 通道**：`renderImage` 只在纹理相对屏幕缩小 ≥1.6x 时才 `glGenerateMipmap`
  （`MIPMAP_MIN_DOWNSCALE`）。平板上照片常常只比屏幕大 1.2~1.5x，这种缩小只会采样 mip 0，
  过去的无条件 mipmap 生成是每次切图多出来的一整趟全屏 GPU 工作。

**实测（release 包，走 App 内点击 → 系统界面 → 设置壁纸）**：

| 项目 | 修改前 | 修改后 |
|---|---|---|
| 点"设置壁纸"→ 桌面换图 | 1.5s 等待 + 切换 | **183ms**（`26.482` 确认 → `26.665` 切换完成） |
| 点"设置壁纸"→ 桌面换视频 | 1.5s 等待 + 重建解码 | **约 120ms 起播**（`00.180` 确认 → `00.207` 切换完成 → `00.258` `Video started`） |
| 锁屏静态写入 | 与切换同瞬间（108ms JPEG + 整屏解码） | 延后 1.2s 执行（`01.402`/`01.941`），不再抢当前画面 |

日志无 E/W、无崩溃；`:app:testDebugUnitTest`（130 条）全绿。

#### 4.9.39 横屏设置壁纸卡顿、竖屏正常（真机日志分析）

用户定位到"**横屏设置动态壁纸卡顿、竖屏不卡**"。真机日志显示横屏时桌面壁纸表面会在一次设置
流程里来回换向：

```
15:49:47.742 Surface size changed 3200x2136 -> 2136x3200; forcing re-render
15:49:52.986 Surface size changed 2136x3200 -> 3200x2136; forcing re-render
```

原因：MIUI 的"动态壁纸切换"(LiveWallpaperChange) 是**只支持竖屏**的系统界面。平板处于横屏时，
系统把这套竖屏界面旋转铺到横屏显示上，整个显示随之转向再转回，**我们的壁纸表面也就跟着转两次**；
而每一次 `Surface size changed` 都会触发 `drawCurrentImage()` 重新解码整张图：

```
15:51:03.822 drawCurrentImage loading bitmap: content://media/…/9118
15:51:03.965 drawCurrentImage bitmap loaded: 3840x2559   ← 144ms + 39MB
```

竖屏时系统界面本来就是竖屏，没有旋转、没有表面变化，所以不卡。

修法（三处，都在"旋转重绘"这条路径上）：

1. **够大就不再解码**：`bitmapFillsScreen()` 用渲染器同一个四边形（`WallpaperGeometry.computeQuad`
   + 方向规则）算出这张图在新屏幕上实际占多少像素；现有位图不小于该尺寸时，只
   `refreshImageQuad()` 重新套一次贴合/90°，不再解码。实测：

```
15:55:36.679 Rotation: re-presenting 3840x2559 (big enough for 2136x3200, no re-decode)   ← 20ms
15:55:41.803 Rotation: re-presenting 3840x2559 (big enough for 3200x2136, no re-decode)   ← 33ms
```

   （位图确实不够大时——例如竖屏只解出 1920x1279 而横屏要 3200 宽——仍会正常重解码。）
2. **视频永不重启**（§4.9.36）：旋转时同一段视频继续播放，不重建解码器/音频。
3. **少一趟全屏 GPU 通道**：整屏纹理只在相对屏幕缩小 ≥1.6x 时才生成 mipmap
   （`MIPMAP_MIN_DOWNSCALE`）；系统选择器的预览引擎也不再套 5 抽头锐化
   （`applyClarityMode()`：`isPreview` 时 `sharpnessScale = 0`）——预览画面由系统合成
   （横屏时还要旋转合成），把 GPU 让给它，真正生效的桌面壁纸仍按设置锐化。

复测：横↔竖连续切换 4 次，除第一次（位图确实偏小）外都是 `no re-decode`，日志无 E/W，
壁纸画面正常；`:app:testDebugUnitTest`（130 条）全绿。

#### 4.9.40 代码审查修复（转向轴向 / 选图确认身份 / 重绘预算 / 音频起始位置 / 表面销毁后的视频身份）

对 4.9.33–4.9.39 这批改动做了一次逐行审查（三路并行只读审查 + 关键结论人工复核），发现并修掉 6 处：

**① 转向时比较轴没交换（4.9.39 的快速路径其实没生效）**
`bitmapFillsScreen()` 用 `bmp.width >= drawnW && bmp.height >= drawnH` 判断"现有位图够不够大"，但
`WallpaperGeometry.computeQuad()` 在 90° 转向时已经把长宽交换（`va = imgH / imgW`），转向后屏幕
横向用的位图 **height**。代入 4.9.39 自己那组数（3840×2559 图 / 2136×3200 屏 / FIT）：要求
`2559 >= 3200×0.98` → false → **仍然整屏重解码**。也就是说 4.9.39 记录的 `no re-decode` 行在当时的
判据下算不出来（那次测量应当是在"自动旋转适配"关闭时做的）。
修法：把判定抽成纯函数 `WallpaperGeometry.bitmapCoversQuad(wd, ht, screenW, screenH, mode, rotateCw)`
（转向时按 `srcW = ht, srcH = wd` 比较），`bitmapFillsScreen()` 改为调用它；同一根因下
`WallpaperRenderer.renderImage()` 的 mipmap 门控也改成"按四边形与源的对应轴比较"，并且只算一次四边形。

**② 选图确认被"引擎重建"绕过（未确认的媒体仍可能生效）**
真实引擎 `onCreate` 原来无条件认定"用户已确认"并 `clearPendingPreviewPick()`；而确认/取消分支又被
`realEngineGeneration == generationAtStart` 卡住。picker 打开期间系统重建一次引擎，两侧就同时失效：
pending 被清、分支被跳过 → 既不回滚游标也不 `clearManualPick` → 下次可见重绘把**未确认**的媒体设成
壁纸（4.9.35 的原始故障，只是换了触发时序）。
修法：
- `onCreate` 只在"本 pick 的预览引擎已经不在（`previewEngineActive == false`）"时才认定确认；
  预览引擎仍活着时保留 pending 与对话框 hold，并打日志；
- 延迟判定不再依赖 engine generation，改为绑定 **pick 身份**（`previewPickAtMs`）：身份变了或
  pending 已被处理就跳过，避免"连续两次点击互相干扰"与"已决断又被覆盖"；
- 确认信号复查两次：延迟结束后再读一次系统壁纸 id（MIUI 的 apply 通知可能晚于 picker 拆除）；
- pending 超时（120s，picker 没起预览引擎/picker 启动失败）不再只是"放行重绘"，而是**按取消处理**
  回滚游标——原来的写法等于把这个 bug 延迟 2 分钟发作。

**③ 重绘重试预算被白烧、耗尽即放弃**
`redrawRetryCount` 原来在 CAS 之外自增：一次旋转会同时触发 `onSurfaceCreated/onSurfaceChanged/
onVisibilityChanged` 三条 `drawCurrentImage()`，其中两条被合并却照样计数，预算最多 3 倍速消耗，
耗尽时直接放弃重绘（可能停在旧帧）。现在预算只在真正入队时计一次，且耗尽后额外安排**一次**延后尝试
（`redrawFinalAttemptDone` 保证只一次，不会成环），真正的重绘发生时两个计数器一起归零。

**④ 打开"视频声音"时音频从 0 开始**
`applyVideoSound(enabled)` 调 `startAudio(uri, gen)` 用了默认 `startPositionUs = 0`，画面却在中段，
两者要到下一轮循环边界才重新对齐（长片可能是几十分钟）。改为传 `lastVideoPositionUs` 并打日志
`Video sound ON (from Nms)`。

**⑤ 表面销毁后视频"身份"丢失，旋转仍重建解码器 + 音频**
`onSurfaceDestroyed()` 里 `videoMode = false` 本意是停看门狗，但它同时是可见性路径判断"视频会话还活着"
的依据（`if (videoMode && renderer?.isVideoPlaying == true)`），于是表面被销毁的那类旋转会走重建分支：
位置靠 `rotationKeepsClip` 保住了，但每次都有黑帧 + 音频重启（正是 4.9.36 想避免的）。现在只
`videoHealthJob?.cancel()`，`videoMode` 保留；其余消费者都同时要求 `renderer?.isVideoPlaying`，
不会因陈旧 `true` 复活死会话。

**⑥ 确认信号不可观测**
`capturePickWallpaperIds()` / `wallpaperIdsChangedSincePick()` 原来静默处理异常与 -1，日志里看不出
"这个 ROM 上 id 信号到底有没有用"。现在两者都会打印基线 id、比较结果与失败原因。

**验证**：`:app:assembleDebug` ✅、`:app:testDebugUnitTest` **134 条全绿**（新增 4 条
`WallpaperGeometryTest`：两轴都要够大 / 转向时交换轴（含 4.9.39 的平板参数） / FILL 裁剪要求 /
退化输入）、`:app:lintDebug` **0 error**。①③ 是纯判定改动，已由单测直接锁住；②④⑤⑥ 的运行时确认需要
真机复现（②需要"picker 打开期间引擎被重建"这一时序，日志中新增的
`Real engine created while the picker is open` / `Pick confirmed (idChange=… newEngine=…)` /
`Pick decision skipped` 三行就是判定依据）。

**本次审查中判为不成立、未改动的两条**（避免误改）：`startVideo` 并未无条件清零
`lastVideoPositionUs`（`WallpaperRenderer.kt:1074-1075` 是条件清零）；音频与视频的首轮定位都用同一个
`MediaExtractor` + `SEEK_TO_CLOSEST_SYNC` 指向同一位置，不存在系统性声画偏移。

**仍未处理（已记录，未修）**：`startVideo()` 失败时续播位置已被消费（F3）、续播位置不跨引擎销毁
（F7）、取消回滚游标的 read-check-write 非原子（F8）。

#### 4.9.41 真机日志复核（4.9.40 修复后的 14 分钟真实使用）

样本：Redmi 手机（Android 17，1200×2608），`runtime.log` 08:16–16:57 共 10900 行；其中
**16:43:50 之后 1729 行**是 4.9.40 修复版（pid=10125）在真实操作下产生的。期间用户完成了
**11 次选图流程**、频繁进出应用、并用悬浮按钮连点切换。

**总体健康**：新包窗口 **0 条 E/W**（全日志 8 条 E + 2 条 W 全部早于修复：08:35 的 4 条
`decodeStream bounds invalid: -1x-1` 与 10:14 的 1 条 `Video stalled` 误判）。定时切换 410 次，
相邻完成间隔**中位 10.01s**（配置 10s），切换耗时中位 282ms / p90 447ms / max 806ms。

**4.9.40 各修复的真机验证**：

| 修复 | 真机证据 |
|---|---|
| ② 选图确认身份 | `Real engine created while the picker is open: pending pick kept (not a confirmation)` **×8** —— 正是"picker 打开期间引擎被重建"这一原先会误判的时序；随后 `Pick wallpaper ids: system=8628->8636 … changed=true` → `Pick confirmed (idChange=true newEngine=false)` **×8** → `Switch requested: manual target=…` → `Slot enforcement after confirmed pick=true`。取消路径同样正确：`changed=false` → `Live wallpaper picker closed without a new engine` → `Pick cancelled: HOME cursor restored to 141976` **×1**。连续点击的干扰被 `Pick decision skipped: a newer pick or another decision already owns…` **×2** 拦住 |
| ⑥ 确认信号可观测 | 11 条 `Pick baseline wallpaper ids: system=… lock=…` + 12 条比较行，可据此判断该 ROM 上 id 信号可用（含 `lock=-1` = 锁屏槽为视频被正确清空） |
| ④ 声音起始位置 | `Video sound ON (from 31300ms)` ×1（播放到 31.3s 时打开声音，不再从 0 起播） |
| ③ 重绘预算 / ⑤ 视频身份 | 窗口内未出现 `Redraw still blocked/abandoned`、未出现异常重建；`Video loop restart (codec + GL reused, no re-init)` ×3、`rebuilding the session` 0 次 |
| ① 转向轴向 | 手机日志 0 次旋转；**平板 25053RP5CC（MIUI 16，FILL）复现成功**：临时打开"自动旋转适配"（`Auto rotate mismatch = true`）后旋转，`17:10:05.137 Surface size changed 3200x2136 -> 2136x3200` → `17:10:05.158 Rotation: re-presenting 3840x2559 (big enough for 2136x3200, no re-decode)`，**21ms 零解码**；同一判据在修复前要求 `ht(2559) >= 3200*0.98`，必然重解码（这正是 4.9.39 的卡顿） |

**顺带复核的两件事**：

- **省电与位置语义是对的**：隐藏期间解码循环完全暂停（`Video paused (wallpaper not visible)` 185 次、
  `Wallpaper covered: pausing decode/audio` 357 次），恢复时**从停下的那一帧继续**、只把配速时钟重锚，
  所以 83 条 `Playback was Xms behind; re-anchored instead of fast-forwarding`（中位 5.9s、p90 130s、
  max 627s）记录的是**被丢弃的墙钟滞后量**，不是画面快进 —— 这正是 4.9.33 想要的语义。138 个
  "隐藏→恢复"周期（中位 1.5s，21% 超过 60s，最长 41 分钟）都没有产生卡顿或误判。
- **日志脱敏生效**：218 行含 `content://…/NNN`，完整 SAF URI **0 行**，最长 URI 44 字符（原上限 421）。

**FILL 模式下"竖屏重解码"是正确的**（排查记录）：平板缩放模式为**填充**且"自动旋转适配"关闭时，
竖屏（2136×3200）要的裁剪面积是 4800×3200，而 4000×2667 的照片确实不够，所以
`Rotation: re-decoding 4000x2667 for 2136x3200 (idMatch=true bmpOk=true fills=false)` 是**正确行为**，
不是漏修。为此在 `LiveWallpaperService` 的旋转分支加了常驻诊断行
`Rotation: re-decoding WxH for SWxSH (idMatch=… bmpOk=… fills=…)`：以后"为什么又重解码了"可以从
日志直接判定，不必再靠推演。

**顺带记录一个 MIUI 行为（排查时踩到）**：`am force-stop <本应用>` 会让 MIUI 把桌面槽的**动态壁纸清掉**
（`dumpsys wallpaper` 变成 `mBindSource=SET_LIVE_TO_CLEAR` + MIUI 自己的 `ImageWallpaper`），
需要重新走一次"点图 → 系统预览 → 设置壁纸"才能恢复。改设置时不要用 force-stop 让进程重启，
用应用内的设置开关（`applyRotateSettingsLive` 会立即生效）。

**两个待办（非本轮改动引入）**：
1. 库里有 4 个媒体解不出来：id `184890/184894/184862/184867`（`0042.jpg/0046.jpg/0014.jpg/0019.jpg`），
   `decodeStream bounds invalid: -1x-1` → `Failed to load bitmap for: …`，定时轮到它们时静默跳到下一张。
   建议在 UI 上标记/自动禁用（多半是云盘占位或文件已失效）。
2. 高频交互时省电状态翻转较多（新包窗口 126 次/14 分钟，安静时段 16–37 次/小时）：每个选图流程都会让
   `app-foreground`（暂停）与 `visibility`（恢复）交替。功能无碍（单次切换极廉价），若要更安静，可在
   `applyAppForeground` 里合并"应用在前台"与"壁纸可见"两个信号。

#### 4.9.42 省电信号合并（不再"最后到达的信号赢"）+ 日志按实例可区分

**改动**：`ScreenPowerPolicy` 由 `shouldPause(requestedPause, screenInteractive)` 改为从**三个输入推导**：
`pauseReasons(wallpaperVisible, appInForeground, screenInteractive)` → `screen-off` / `visibility` /
`app-foreground` 中任意一个成立就暂停。引擎侧 `setPowerSave(enabled, reason)` 改为
`refreshPowerSave(hint)`：调用方只更新**自己那一个输入**（`onVisibilityChanged` 写
`powerSaveVisibleInput`、`applyAppForeground` 写 `appInForeground`、屏幕状态实时读），状态由
`applyPowerSave()` 统一推导。暂停仍然立即、恢复仍然 250ms 去抖并重新筛查（4.9.32 的两条保证不变）。

这样做的直接效果：**"壁纸可见"回调不可能再在我们自己的界面处于前台时把引擎恢复**（旧模型下它会先恢复
解码/音频、再被下一个 app 信号暂停）。日志里也会看到**全部生效的输入**，例如
`Power save ON (visibility, app-foreground | preview=false)`。

**顺带修一个分析陷阱**：选择器的**预览引擎**和真实引擎写同一个 TAG、同一个文件，导致日志里相邻的
ON/OFF 常常属于**两个不同实例**（实测同一时刻出现 `ON(visibility)` 与 `ON(app-foreground)` 相隔 9ms ——
单实例不可能连打两次 ON）。这让人很容易把"两个实例各自的合法转换"读成"一个引擎在激烈抖动"。
现在每条省电日志都带 `| preview=true/false`，可以按实例精确统计。

**实测（平板 25053RP5CC，4 轮"应用→分组→选图→返回→桌面"脚本，同机同脚本）**：

| 实例 | 事件 | 反向转换 | 其中 <2s | 原因分布 |
|---|---|---|---|---|
| 真实引擎 `preview=false` | 20 | 19 | **1** | app-foreground ×9、app-left ×9、visibility ×2 |
| 预览引擎 `preview=true` | 12 | 8 | 4 | visibility ×12 |

结论：真实引擎在这套高频交互里的转换**全是真实状态变化**（进出应用各 9 次 + 可见性 2 次），没有来回抖动；
预览引擎那 4 次短转换是"新建实例时表面还没可见 → 250ms 后可见"的合法初始化（每个 picker 打开一次）。

**订正 §4.9.41 的一处读数**：那里把"手机 14 分钟 126 次翻转"当作抖动，实际混入了两个引擎实例的合法
转换（且手机窗口里每个 picker 都会新建预览引擎）。按实例统计后，真实引擎在该场景下没有抖动。省电合并
改动本身的收益是"**结构上不可能再出现'自己界面前台时被可见性回调恢复'**"（由单测锁定），而不是某个
可测的翻转数下降。

**验证**：`:app:assembleDebug` ✅、`:app:testDebugUnitTest` **137 条全绿**（`ScreenPowerPolicyTest`
重写为 5 例：熄屏永远暂停 / 被遮挡暂停 / **自己界面前台时即使可见也暂停**（旧的抖动场景）/ 只有"可见+亮屏+
不在前台"才全速 / 原因列表覆盖全部分歧输入）、`:app:lintDebug` **0 error**；新包在平板上跑完 4 轮脚本，
无 E/W、壁纸正常。

#### 4.9.43 悬浮按钮引起的省电抖动：断源 + 合并（真机日志驱动）

来源：手机 09-30 真实使用 11.4 小时的日志（2355 行，**零 E/W**）。按实例统计（4.9.42 加的 `preview=`
标记）后，真实引擎 522 次省电事件里有 **53 次"被遮挡→恢复"短于 2 秒**，且这段原始日志给出了完整机制：

```
11:07:04.504 Wallpaper covered: pausing decode/audio
11:07:04.504 Power save ON (visibility | preview=false)
11:07:04.907 Floating button hidden        ← removeView（覆盖窗口被移除）
11:07:05.259 Floating button shown         ← addView（352ms 后加回）
11:07:05.482 Power save OFF (visibility | preview=false)
11:07:06.640 Wallpaper covered: pausing decode/audio   ← 约 2 秒后又一轮
```

悬浮按钮是 `APPLICATION_OVERLAY` 覆盖窗口：它的 add/remove（以及任何改变窗口栈的交互）会让 ROM 把壁纸报成
"被遮挡"几百毫秒再报"可见"，而可见性回调又会 `updateFloatingButton()` 把窗口加回来 —— 形成自激回路。代价：
每次抖动 = 视频壁纸的解码器暂停/恢复 + 音频重启 + 播放时钟重锚，按钮本身还会**闪一下**。
（同时订正 4.9.41/4.9.42 的口径：这类抖动 11 小时里只有 53 次、约 5 次/小时，集中在连点悬浮按钮时；
261 次遮挡的中位时长 15.8s，属真实遮挡，不是抖动。）

**两处修改**：

1. **断源**：`FLOATING_BUTTON_HIDE_SETTLE_MS` 400ms → **1500ms**。抖动实测 250–400ms，400ms 刚好低于它，
   所以按钮真的被移除又加回来（既闪一下、又喂给 ROM 另一次可见性翻转）。1500ms 让这类抖动根本不触碰覆盖
   窗口；真实遮挡（切到别的应用）仍会隐藏按钮（最多晚 1.5s），而我们自己的界面打开仍然**立即**隐藏
   （`hideFloatingButtonNow`）。
2. **合并**：新增 `VISIBILITY_PAUSE_COALESCE_MS = 600ms` + 纯函数
   `ScreenPowerPolicy.isVisibilityBlipOnly(reasons)`：**只有"仅 visibility 原因"的暂停**会被延迟，遮挡期间
   输入变回可见就整段取消（一次转换都不发生）；`screen-off` 与"自家界面前台"仍然**立即**暂停（"声音没立刻
   关"的保证不变）。被吸收的抖动会打一行
   `Visibility blip coalesced: no pause/resume for the blip | preview=false`，以后可直接从日志计数。

**验证**：`:app:assembleDebug` ✅、`:app:testDebugUnitTest` **138 条全绿**（新增
`onlyAPureVisibilityCoverCountsAsABlip`：只有纯 visibility 才算抖动，screen-off/app-foreground 组合不算）、
`:app:lintDebug` **0 error**；真机回归（手机）：应用前后台切换 3 次 —— `App UI foreground` → `Power save ON
(app-foreground)` 延迟 **+1ms**（仍立即），离开后正常恢复；悬浮按钮连点 8 次 → 8 次切换；**0 E/W**。

**诚实口径**：脚本无法稳定复现 ROM 侧的抖动触发（通知栏遮挡、悬浮按钮自身连点都不触发它），所以"抖动次数
下降"本身没能在实验台上量出来；现有证据是上面这条机制（原始日志）、纯函数单测、以及新日志行在真实使用中的
计数。

#### 4.9.44 悬浮按钮自定义文字 / 自定义图片（图片替代文字）

需求（用户）：悬浮按钮支持自定义文字，支持自定义图片，**设置图片后不显示文字**。

**实现**：

- 纯策略 `engine/FloatingButtonContent.kt`：`FloatingButtonContentPolicy.resolve(text, imageUri)` →
  `Image(uri)` 或 `Text(value)`。规则：**非空图片 URI 永远优先，文字随之不画**；文字为空/空白回落
  `DEFAULT_TEXT`（「切」，与 `SettingsKeys.FLOATING_BUTTON_TEXT_DEFAULT` 一致，有单测锁定两者相等）；
  文字按 **code point** 截断到 4（不会把 emoji 切成半个代理对）；超过 2 个字自动缩小字号。
- 设置项：`FLOATING_BUTTON_TEXT`、`FLOATING_BUTTON_IMAGE_URI`（ViewModel 两条 StateFlow +
  `SettingsUiState`，日志报告头也带上便于排查）。
- `FloatingSwitchButton`：圆底 `FrameLayout`（`clipToOutline` 靠 oval 背景裁圆）里放 `TextView` 与
  `ImageView`；`setContent(text, uri)` 实时切换（不重建窗口）。图片在**后台线程**按目标尺寸
  (`inSampleSize`) 解码（12–50MP 的照片不会为了 40dp 圆点解出几十 MB），解不出来时回落文字，按钮永不为空白
  圆；触摸反馈对图片同样生效。
- 设置页「悬浮按钮外观」新增：按钮文字输入框、自定义图片行（圆形预览 + 选择/更换/清除）。选图用
  `ActivityResultContracts.OpenDocument` + `takePersistableUriPermission`（照片选择器的 URI 不能持久化，
  重启后按钮会找不到图）。

**真机验证（手机 25102RKBEC，完整生命周期）**：

```
20:52:29 Floating button content: text '切'          ← 默认
20:54:23 Floating button content: text 'Go'          ← 自定义文字（界面输入后实时生效）
21:01:46 Floating button content: image content://…  ← 选择自定义图片后：只画图，不画文字
21:02:55 Floating button content: text '切'          ← 清除图片后回到文字
```

设置页状态同步正确（「已设置：按钮显示图片，文字隐藏」+「更换图片/清除图片」）。单测 **143 条全绿**
（新增 `FloatingButtonContentTest` 5 例）、lint **0 error**、release 包已重出（6.5MB）。

**验证中发现一个既有缺陷（未修完，已记录）**：桌面会同时存在**多个**悬浮按钮覆盖窗口 ——
`dumpsys window` 实测三个 `pkg = com.wallpaperswitcher ty=APPLICATION_OVERLAY` 窗口且全部
`isVisible=true`；应用日志里 `Floating button shown/hidden` 配对、`removeView failed` 为 0、每次都是不同
实例（新增的实例 id 日志证实）。问题因此在"窗口移除"这条路径而非实例记账。已做加固：①预览引擎不再创建浮钮
（`updateFloatingButton()` 遇 `isPreview` 直接返回）；②`FloatingSwitchButton` 用进程级 `current` 单例，
新实例 `show()` 前先回收旧的；③`show/hide/Replacing` 日志带实例 id。残余影响：多出一个内容相同、不可见的
覆盖窗口，可见按钮工作正常；下次应从"removeView 之后窗口为何仍在（MIUI 窗口回收延迟？）"入手。

#### 4.9.45 悬浮按钮：进程内只保留一个窗口（根因：MIUI 的 removeView 不真正移除）

用户反馈"桌面上有多个悬浮按钮，只留一个"。根因在 4.9.44 的验证尾巴上被抓到，日志原文：

```
21:18:03.975 W FloatingSwitchButton: Floating button window survived removeView; keeping it for reuse
```

即 **MIUI 的 `WindowManager.removeView()` 不抛异常、也不真正把窗口摘掉**。旧实现是"每次引擎需要显示就
新建一个 `FloatingSwitchButton` + `addView`，隐藏时 `removeView`"，于是被"移除"的窗口留在窗口栈里，
`dumpsys window` 实测同时存在 **3 个** `pkg = com.wallpaperswitcher ty=APPLICATION_OVERLAY` 窗口
（全部 `isVisible=true`），而日志里 `shown/hidden` 是配对的、`removeView failed` 为 0 —— 这也解释了为什么
只有引擎最后持有的那个实例能收到实时文字/图片更新（4.9.44 的观察）。

**修法（与 ROM 行为无关）**：`FloatingSwitchButton` 改成**进程级单例** + **同一个 View 复用**：

- `FloatingSwitchButton.obtain(context)` 返回进程内唯一实例（引擎会被系统反复重建，窗口不跟着重建）；
- `showOnDesktop()`：已有 View 时只切可见性；若 ROM 已经把窗口丢掉（`isAttachedToWindow == false`），
  用**同一个 View** 重新 `addView` —— 永远不会变成两个窗口；
- `hideOverlay()`：只 `visibility = GONE` + `FLAG_NOT_TOUCHABLE`，不调用 `removeView`；
- `removeOverlay()`（仅"用户关掉功能/最终销毁"）：`removeView` 后检查 `isAttachedToWindow`，**若窗口仍在
  就保留引用以便复用**（正是上面那条日志），绝不丢弃引用后再新建一个；
- `show/hide/re-attached/survived removeView` 四类日志都带实例 id，可直接从日志核对"是不是同一个实例"。

**真机验证（手机 25102RKBEC）**：

| 检查 | 结果 |
|---|---|
| 连续 5 轮「打开应用 → 回桌面」 | 浮钮窗口**恒为 1**（旧版最多到 3），且全部为**同一个实例 id** |
| 桌面态窗口状态 | `mViewVisibility=0`（可见）、`isVisible=true`、`flags=NOT_FOCUSABLE HARDWARE_ACCELERATED`（未加 `NOT_TOUCHABLE`，可点击） |
| 关闭"悬浮切换按钮"开关 | 窗口归 0（真正移除路径生效） |
| 点击切换 | 屏幕点亮时正常（此前 8 次连点 = 8 次切换；`Switch done: floating-tap`） |
| 测试 | `:app:testDebugUnitTest` **143 条全绿**、`:app:lintDebug` **0 error**、release 包已重出并装机 |

附带收益：窗口不再随每次显隐 add/remove，4.9.43 里"覆盖窗口增删导致 ROM 报壁纸被遮挡→可见"的抖动机制从
根源上少了一条来源（同类抖动仍由 `VISIBILITY_PAUSE_COALESCE_MS` 兜底）。

#### 4.9.46 分组网格滑动的真实成本（含一次测量方法学修正）

用户反馈"壁纸分组滑动卡顿、设置滑动有点卡顿"。用手机（25102RKBEC，1200x2608，**120Hz 面板 →
每帧预算 8.3ms**）做了四轮测量，结论与最初的判断**相反**，过程与数据如下。

**先修正测量方法：装机后 ART/JIT 是冷的** ✗

同一个场景（进 18285 张的分组、连续下滑 20 次新项）在不同时刻测出：

| 状态 | Janky | p50 |
|---|---|---|
| 刚 `adb install -r` 后立刻测 | **17% – 31%** | 23–42ms |
| 同一次安装内、先预热 20 次滑动再测 | **0.35% – 0.43%** | 18–19ms |

也就是说**此前报告里的"17–31% jank"主要是装机后 JIT 冷造成的假象**，不是应用稳态表现。任何 A/B
都必须先预热再测，否则不可比。

**稳态实测（release 包，ART 已暖，四种组合一致）**

| 场景 | Janky | p50 | p90 | Slow UI thread |
|---|---|---|---|---|
| 滑入**新项**（磁盘/内存缓存冷或热都一样） | **0.35 – 0.43%** | 18–19ms | 32–34ms | 4–5 |
| 回滑**已看过的项** | **0.06 – 0.12%** | **6ms** | 7ms | 0–1 |
| 从桌面返回后首滑（缓存修剪保留 17.1MB 之后） | 0.12% | 5ms | 6ms | 0 |

即：**约 55–60fps 的稳态、极低的 jank 比例**；把已组合过的项来回滑则是满帧 120fps。

**唯一的真实成本：JPEG 解码**

`simpleperf --app com.wallpaperswitcher`（6 秒持续滑动、磁盘缓存冷）：

```
66.99%  /system/lib64/libjpeg.so  decode_mcu(...)
 3.75%  /system/lib64/libjpeg.so  decompress_onepass(...)
```

**约 71% 的进程 CPU 花在 libjpeg**。原因是 JPEG 没有随机访问：即使给了 `inSampleSize`，12–50MP 照片的
熵编码扫描也必须先解完才能缩放，所以每格缩略图都是数毫秒 CPU。

**试过并否掉的两条路**（都留了数据）

| 方案 | 结果 | 处置 |
|---|---|---|
| 首屏预取（12 张立即 + 28 张延后 150ms 入队） | 冷启动首滑 **17.5% → 30.7%** ✗（位图成批送达 → 一次性大量重组） | **回退** |
| 系统缩略图 `Interceptor`（`ContentResolver.loadThumbnail`） | 功能正常（日志 `MediaStore thumbnails served: 150 (fell back 0)`），但 A/B（同为暖 ART）**2.97% vs 0.35%** ✗ —— 磁盘缓存热时，每次多一次 Binder 往返比省下的解码更贵 | **不发布**（结论与开关方式记在此；若库以超大 JPEG 为主可再启用） |

顺带记一个坑：Coil 的 interceptor 运行在**调用方线程**上，第一版把 `loadThumbnail` 放在主线程直接
造成 **p90 = 1850ms** 的单帧 ✗；正确写法是 `withContext(Dispatchers.IO)`。

**保留的改动（各自有验证）**

| 改动 | 证据 |
|---|---|
| 缩略图内存缓存：`clear()` → `trimMemory(UI_HIDDEN)` | 设备日志 `trimmed (UI hidden): 17111900 -> 17111900` —— 保留 17.1MB 工作集（改前是直接归零），从桌面返回后首滑 866 帧 / 0.12% ✓ |
| `DropdownMenu` 仅在展开时组合 | 网格项不再为每个可见格建弹出菜单状态机 |
| 网格缩略图 `ARGB_8888` + 硬件位图 | 全局 `RGB_565` 与硬件位图不兼容，会退化成软件位图（每次绘制都要上传） |

**仍待解决**：应用**首次**打开（磁盘与 JIT 都冷）时的首屏遍历，实测 17–31% jank ✗ —— 这一段是真实
用户会遇到的"刚装好用起来卡"。可选后续：扫描阶段预生成 256px 缩略图到应用缓存（一次性成本，之后
滚动只解小图），或按上面的开关方式启用系统缩略图路径。

#### 4.9.47 设置页排版修复（花括号"位置"与"数量"是两件事）

**症状**：用户报"设置界面排版有问题"。真机截图确认：「壁纸设置」的四组选项、分隔线、开关行被**挤进同一个
横向 `Row`** 横向排列；「悬浮按钮外观」的色板/输入框/图片行同理。

**根因**：4.9.44 那次事故的修复只保证了**花括号数量**（余量 0 ✓），但**位置**有几处放错 —— 被删的 `}`
补在了段落末尾而不是它本该结束的地方，于是后续兄弟节点被吞进上一个容器。典型四处：

| 位置 | 错法 | 正确 |
|---|---|---|
| 壁纸设置 | 一个 `Row` 包住整段（四组选项 + 开关 + 子行） | `Row` 只包一组「图标+标签+选项」，在chips 后收尾 |
| 切换间隔 / 锁屏间隔 / 透明度 | `Column(weight(1f))` 的收尾放在「修改 / 数值」文本**之后** | 收尾在其**之前**，「修改」与标签列是兄弟 |
| 运行日志按钮 | 三个按钮都在同一个 `Row` 里 | 「导出/清空」并排，「保存到手机」独立全宽 |
| 自定义图片 | 预览图与按钮行层级错位 | 预览 `if` 与图片 `Row` 都在按钮行之前收尾 |

**怎么定位的**：写了一个**用缩进反查嵌套**的检查器（`.repair/nesting_check.py`）—— 本文件的缩进是完整的
（事故没破坏缩进），所以每条语句行都应满足 `括号深度 == 缩进/4 + 常数`，偏离处即放错的位置；同时用括号
余量校验器（`.repair/region_check.py`）保证每次改动后总量仍为 0（**移动**闭合点而非增删）。偏差从 89 处
降到 42 处，剩余的都是参数续行（`OutlinedTextField(` 等）造成的正常差异。

**缩进重排**：嵌套正确后，缩进可由括号深度机械推导（`.repair/reindent.py`，改了 110 行；备份在
`.repair/SettingsScreen.before_reindent.kt`）。Kotlin 缩进不影响语义，编译与单测验证通过。

**验收**：真机逐段截图确认 —— 壁纸设置四组选项各自成行、分隔线正常；切换方式六个开关行；
悬浮按钮外观的色板 6+3 自动换行、文字输入框（浮动标签+辅助文字）、自定义图片行（说明+圆形预览+更换/清除）；
运行日志「导出并分享 / 清空日志」并排 + 「保存到手机」全宽；使用指南与关于卡片。

**回归**：`:app:assembleDebug` / `:app:assembleRelease` ✓、**143 单测全绿** ✓、`lintDebug` 0 error ✓、
括号余量 0 ✓、dex 字符串 oracle 与修复前一致（无文案丢失）✓。

#### 4.9.48 悬浮按钮进入应用不消失（两条独立的原因）

用户报"悬浮按钮进入应用无法立即消失"。真机复现 + 日志定位后发现是**两层**问题叠加：

**原因一：Activity 的隐藏请求依赖引擎实例**

`MainActivity.onResume()` 走的是 `LiveWallpaperService.dismissFloatingButtonIfAny()` → `activeEngine?.hideFloatingButtonNow()`。
**引擎实例为 null 时（被系统回收、或还没注册）这行是空操作** —— 在 4.9.45 把按钮改成"进程级单例"之前这不致命：
旧实现里每个引擎各持一份按钮，引擎销毁时会把窗口一起 `dismiss()`；改成单例后按钮能跨引擎存活，于是
"上一个引擎创建的按钮"就留在了屏幕上 ✗。

**原因二：MIUI 会继续绘制"视图 GONE"的覆盖窗口**

即使隐藏逻辑执行了（实测 `hideShared → Floating button hidden` 只相隔 **1 ms**），
`dumpsys window` 里窗口依旧存在、按钮依旧可见 ✗ —— 视图 `GONE` 并不足以让这个 ROM 停止绘制覆盖窗口的
最后一帧缓冲。

**修法**

| 改动 | 位置 |
|---|---|
| 新增 `FloatingSwitchButton.hideShared()`（进程级、任意线程）+ 实例 `hideNow()`，Activity **直接**隐藏，不再依赖引擎 | `FloatingSwitchButton.kt` |
| `MainActivity.onStart()`（窗口绘制之前，最早时机）与 `onResume()` 都调用它；引擎路径保留（它还负责自身记账） | `MainActivity.kt` |
| `hideOverlay()` 除视图 `GONE` 外，**把窗口本身也设为不可见**：`alpha = 0f` + 移出屏幕上方 + `updateViewLayout` 强制事务 | `FloatingSwitchButton.kt` |
| `showOnDesktop()` 对应恢复 `alpha = 1f` 与移出前的位置 | `FloatingSwitchButton.kt` |

**真机验证（手机 25102RKBEC）**

```
23:35:57.107 MainActivity: onStart: hiding the floating button
23:35:57.107 FloatingSwitchButton: hideShared: hiding (id=70791749)
23:35:57.108 FloatingSwitchButton: Floating button hidden (id=70791749)      ← 1 ms
23:35:57.108 LiveWallpaperService: App UI foreground: mute audio + hide floating button
23:35:57.118 VideoAudio: Video audio paused (wallpaper not visible)
```

* 应用内截图：**没有浮钮覆盖** ✓
* 回桌面：日志 `Floating button shown (id=70791749)`（同一实例）+ 窗口仍在 ✓，**点击两次都成功切换**
  （`Switch done: floating-tap` ×2，第二次日志 `Not a rapid switch (3855ms)` 说明节流也在工作）✓

**测量陷阱（记下来避免下次误判）**：用"`dumpsys window` 里覆盖窗口的数量"判断按钮是否可见**不可靠** ✗ ——
隐藏后窗口对象仍会留在列表里（alpha=0、不再绘制），真正的判据是**截图**和 `isVisible`/alpha 字段。

**回归**：`:app:assembleDebug` / `:app:assembleRelease` ✓、143 单测 ✓、lint 0 error ✓。

#### 4.9.49 悬浮按钮切到**别的**应用时也要立刻消失（去抖从 1500ms 降到 150ms）

用户报"进入其他应用还是没有立即消失"。这条路径与 4.9.48 不同：不是我们自己的 Activity（那条已即时隐藏 ✓），
而是**任何外部应用盖住壁纸**时的隐藏 —— 它走 `FLOATING_BUTTON_HIDE_SETTLE_MS`（`FLOATING_BUTTON_HIDE_SETTLE_MS`）。

**实测（手机，打开系统设置）**：

```
23:37:37.389 Wallpaper covered: pausing decode/audio
23:37:38.947 Floating button hidden          ← 间隔 1560 ms ✗
```

同刻音频 0.6s 就停了，只有浮钮在等那 1.5 秒 ✗。

**为什么当初是 1500ms**：ROM 在桌面使用过程中会报 250–400ms 的"壁纸被遮挡"抖动，400ms 刚好低于它 →
按钮闪烁 ✗，而且**每次翻转都会 `removeView` + `addView` 覆盖窗口两次**，又反过来喂给 ROM 新的可见性翻转 ✗。

**为什么现在可以降下来**：4.9.45 之后按钮是**常驻的进程级窗口**，隐藏只改 `alpha`/可见性，**不再增删窗口**
→ 不可能再喂翻转 ✓。把去抖降到 **150ms**（低于感知阈值，仍能吞掉极短抖动）。

**改后实测**：

| 场景 | 之前 | 现在 |
|---|---|---|
| 切到别的应用（设置） | 1560 ms ✗ | **156 ms** ✓（`covered 23:39:13.904 → hidden 23:39:14.060`）|
| 打开我们自己的应用 | 1 ms ✓ | 1 ms ✓ |
| 桌面静置 60 秒的显隐次数 | — | **0 次**（可见性事件也是 0）✓ 无抖动 |
| 桌面点击浮钮 | — | `Switch done: floating-tap` ✓ |

**回归**：`:app:assembleDebug` / `:app:assembleRelease` ✓、143 单测 ✓。

#### 4.9.40 未启用分组里的图片不能再被设为壁纸

用户反馈：「当分组图片未启用时，里面的图片仍能设置为壁纸」。

原因：两条用户入口都**没有校验分组状态**——

- `WallpaperViewModel.setAsLiveWallpaper()`（点图片 → 系统动态壁纸界面）
- `WallpaperViewModel.setImageAsWallpaper()`（三点菜单 →「设为壁纸」→ 预览确认）

而引擎的目标切换曾经刻意"显式选择无视分组启用状态"（避免手动选中的图片被随机图替换），于是
用户能把已关闭分组里的图片设成壁纸；但轮换、预取、锁屏/桌面定时和 `getFirstFromEnabledGroups`
都只从**启用**分组里挑，下一次切换/重绘又把它换掉——等于设了个"注定被覆盖"的壁纸。

修法：

1. 两个入口在读取分组后立即判断 `group != null && !group.isEnabled` → 记录
   `setAsLiveWallpaper ignored: group N is disabled` / `setImageAsWallpaper ignored: ...`
   并提示「该分组未启用，请先打开分组开关」，**不移动 HOME 游标、不打开系统界面、不写任何状态**
   （守卫放在最前面，因此也不会留下 pending preview pick）。
2. 确认路径的兜底目标（预览会话已丢失 pending pick 时用 HOME 游标）新增
   `homeCursorForConfirmedPick()`：只有游标仍指向**启用且支持桌面**的分组媒体才返回，否则返回 0，
   确保"确认后推送"也不会复活已关闭分组的图片。
3. 引擎目标切换处的注释更新为"用户入口已拦截 + 内部调用自带校验"，避免后人误以为这里仍需放行。

**真机实测（Redmi 平板 25102RKBEC / 1200×2608，release 包）**：

| 操作（分组已关闭） | 日志 | 结果 |
|---|---|---|
| 点图片 | `setAsLiveWallpaper ignored: group 37 is disabled` | 系统界面不打开，壁纸不变 |
| 三点 →「设为壁纸」→ 确定 | `setImageAsWallpaper ignored: group 37 is disabled` | 无 `Wallpaper applied` / 无切换 |
| 打开分组开关后再点图片 | `setAsLiveWallpaper: id=… target=…` | 系统界面正常打开；返回取消 → `pick cancelled` + 游标复原 |

测试后已把该分组恢复为关闭；构建 + `:app:testDebugUnitTest`（130 条）全绿。

#### 4.9.41 回到桌面 / 退出软件时视频要停一下（可见性恢复被去抖）

用户反馈两件事，其实是同一个根因：

- 「设置视频为壁纸后，返回桌面要黑屏一会才开始播放」
- 「进入壁纸软件后，再退出，视频会卡一下再播放」

真机日志（Redmi 平板 25102RKBEC）里每次回到桌面都固定多出约 250ms：

```
07:03:54.910 LiveWallpaperService: App UI hidden: re-evaluating wallpaper state
07:03:55.162 VideoDecode: Video resumed (wallpaper visible again)   ← +252ms
07:03:55.202 WallpaperRenderer: Video frame rendered                ← 第一帧
```

原因：`refreshPowerSave()` 对**所有**恢复都套了 `VISIBILITY_DEBOUNCE_MS = 250ms` 去抖，本意是吸收
窗口/Activity 过渡期间成串的可见性回调（每次翻转在视频路径上都是一次解码器暂停+时钟重锚）。
但"我们自己的 App 退到后台 / 亮屏"是**确定性**转换——壁纸就在前台且可交互，去抖只会让画面白停
250ms；如果是刚被系统重建过的新引擎（还没有画过任何一帧），这 250ms 就是**纯黑屏**。

修法（`LiveWallpaperService.refreshPowerSave`）：恢复时先判断
`!appInForeground && powerSaveVisibleInput && isScreenInteractive()`（即"App 已退到后台、壁纸确实
可见、屏幕已亮"）→ 立即 `applyPowerSave(hint)`；其余情况仍走 250ms 去抖。抖动保护没有丢：随后
的 covered 报告由暂停路径处理，短暂 covered 仍会被 `VISIBILITY_PAUSE_COALESCE_MS` 合并
（日志里 `Visibility blip coalesced: …` 仍然生效）。

**实测（release 包，同一台平板）**：

| | 修改前 | 修改后 |
|---|---|---|
| `App UI hidden` → `Power save OFF` | +252ms | **+1ms**（两轮复测：+1ms、0ms） |
| 每轮进出 App 的暂停/恢复次数 | 1/1 | 1/1（无来回抖动，无 `blip` 误报） |

按 07:03:55 那次的实测数字推算：新引擎 + 已初始化解码器的情况下，返回桌面到第一帧由约 290ms
降到约 40ms（去抖残差 + 唤醒），"黑屏一会"与"卡一下"随之消失。

#### 4.9.42 返回桌面时视频停顿 1 秒：app-foreground 标志挂在 onStop 上

用户反馈（承接 §4.9.41）：「设置视频为壁纸后，返回桌面要黑屏一会才开始播放」「进入壁纸软件后，
再退出，视频会卡一下再播放」。

实测把 HOME 按键和日志时间对齐后，问题非常具体（Redmi 平板 25102RKBEC）：

```
HOME 按下                07:21:51.931
App UI hidden           07:21:53.035   ← 1104ms 之后
Video resumed           07:21:53.037
```

原因：引擎的"我们自己的 UI 在前台"标志由 `MainActivity.onStop()` 翻转，而 MIUI 要等**退出动画
走完**才回调 onStop（实测 +1104ms）。这段时间里 launcher 已经在前面、壁纸已经可见，但解码仍被
`app-foreground` 判为暂停，所以画面停住（新引擎还没画过一帧时就是黑屏）。壁纸自身的
`onVisibilityChanged` 在这台 ROM 上要晚 1.5-2.5s（这正是当初引入该标志的原因），所以只能换触发点。

修法：把 `setAppForeground(false)` 与 `WallpaperSwitchService.poke()` 从 `onStop()` 移到
`MainActivity.onPause()`——onPause 与窗口切换同拍触发（实测 +84~127ms）；`onStop()` 只保留缩略图
缓存回收（本来就有 60s 延迟）。引擎侧无需改动：§4.9.41 的"立即恢复"判断
(`!appInForeground && 可见 && 屏幕亮`) 现在能在正确的时刻生效。

**实测（release 包）**：

| | 修改前 | 修改后 |
|---|---|---|
| HOME → `onPause` | —（挂在 onStop） | **+84ms / +127ms** |
| HOME → 引擎恢复 | +1104ms | **+128ms** |
| HOME → 首帧 | ~+1123ms | **+158ms** |
| 两轮进出 App 的暂停/恢复次数 | 1/1 | 1/1（无抖动、无 blip 误报） |

注意（既有策略的延伸）：引擎对"回到桌面"采用**乐观恢复**——先恢复，等系统可见性回调到达再纠正，
所以"从我们 App 里打开别的应用"这种情况会比以前早约 1s 恢复解码/声音（原来是在 onStop 时恢复，
同样存在这个窗口，只是更晚）。这是"少 1 秒静音/黑屏" 与"多 1 秒后台解码"之间的取舍，与
`applyAppForeground` 里已记录的乐观恢复注释一致。

#### 4.9.43 为什么"进出壁纸软件"比进出别的应用更容易看到视频卡顿

用户问："壁纸软件进出视频就是比其他软件进出会卡顿"。同一台平板、同一段视频，用同一套
`am start` / `KEYCODE_HOME` 流程对照测量（Redmi 平板 25102RKBEC）：

| | 进入（视频暂停） | 退出（视频恢复） |
|---|---|---|
| 我们 App | **+105ms**（app-foreground 输入立即生效） | **+44~130ms**（onPause / 可见性回调） |
| 系统设置 | +649ms 收到"被覆盖"，+1253ms 真正暂停 | +126ms（可见性回调） |

差异来自两件**只有我们自己的 App 才会发生**的事：

1. **进入我们 App 时，暂停比系统回调早约 1.1s**。引擎把"我们自己的 UI 在前台"
   （`appInForeground`，由 `MainActivity.onStart/onPause` 维护）当作最快的暂停输入——这是为了
   「打开应用时声音立刻关闭」。代价是：**MIUI 的开启动画还没结束、壁纸仍然可见的时候，视频就冻住了**。
   别的应用不会有这个输入，暂停要等系统"壁纸被覆盖"回调（+0.6~1.3s），等它到达时壁纸早已被完全
   遮住，所以用户看不到那一帧的停顿。
2. **退出我们 App 时，引擎和动画在同一个进程里抢资源**。恢复由 onPause/可见性回调触发
   （+44~130ms），而此刻**我们自己 Activity 的关闭动画还在跑**——解码器、GL 上传、音频轨道重建
   都要和这个动画争 CPU/GPU，所以视频头几帧不均匀。别的应用退出时它的动画不牵扯我们的进程，
   引擎恢复时 GPU 是空的。

改动（退出侧）：新增 `APP_EXIT_RESUME_GRACE_MS = 250ms`——`appLeftAtMs` 记录 UI 离开的时刻，
`refreshPowerSave()` 里的恢复（无论先到的是 app-left 还是可见性回调）都不早于该时刻 +250ms，
让关闭动画先跑完。实测 HOME → 恢复由 +44ms 变为 **+350ms（= onPause + 252ms）**，动画期间不再有
解码竞争；两轮复测仍是各一次暂停/恢复。

进入侧**暂未改动**：要让画面在开启动画期间继续播放，必须把"立即静音"与"暂停解码"拆开
（立即 `audioSession.pause()`，解码延后到被覆盖），并在恢复时把音频**重新对齐到视频当前位置**
——否则音频会落后约 0.5s（音频线程在静音期间仍会写入并阻塞，恢复后从缓冲开头继续）。这需要动
音频管线，风险高于收益，先记录方案待确认。

#### 4.9.44 进入我们 App 时不再冻结画面（立即静音 + 延后暂停 + 音频重新对齐）

承接 §4.9.43 的对照结论：进入我们 App 时暂停由 `app-foreground` **立即**触发（实测 +105ms），而
系统"壁纸被覆盖"回调要 +0.6~1.3s——于是视频是在**开启动画仍在进行、壁纸仍然可见**的时候冻住的；
别的应用没有这个输入，等回调到达时壁纸早已被遮住，所以看不到那一顿。用户选择方案 A：进我们 App
时**画面继续播、声音立刻静音**。

实现（三处）：

1. **只静音、不停画面**：新增 `WallpaperRenderer.muteAudioKeepingVideo()`——立刻 `stopAudio()`
   （音频线程停、`AudioTrack.pause()` 立即无声），但**不动** `powerSaveMode`，解码与渲染继续。
   引擎侧 `applyAppForeground(true)` 改为此调用 + **延后** `refreshPowerSave()` 到
   `APP_ENTRY_PAUSE_GRACE_MS = 600ms`（若系统"被覆盖"回调更早到达，则按回调立即暂停，符合实际遮挡）。
2. **恢复时把音频重新对齐到画面**：新增 `unmuteAudioReanchored()`，用
   `startAudio(uri, gen, lastVideoPositionUs)`（与"视频声音开关"同一条 re-anchor 路径）。静音期间
   画面一直在走，若让音频从原处继续就会落后整个静音时长。
3. **顺序修正（实测发现）**：解除静音必须发生在 `powerSaveMode = false` **之后**——写在前面时
   `unmuteAudioReanchored()` 会因为 `powerSaveMode` 仍为 true 而提前返回，声音再也回不来；同时把
   "状态没有变化但需要解除静音"（快速进出 App，从未真正暂停）也覆盖。`startVideo()`/`release()`
   清掉该状态，避免跨视频泄漏。

**实测（release 包，两轮复测一致）**：

| 阶段 | 改前 | 改后 |
|---|---|---|
| 进入 App · 静音 | +105ms（同时冻结画面） | **+155ms 静音，画面继续播** |
| 进入 App · 画面暂停 | +105ms（动画中，可见） | **+535ms（系统"被覆盖"回调后，不可见）** |
| 退出 App · 画面恢复 | +44~130ms（与关闭动画抢资源） | **+251ms（关闭动画结束后）** |
| 退出 App · 声音 | 随画面一起恢复（可能落后） | **`Audio unmuted, re-anchored at Nms` 接回当前画面** |
| 每轮音频线程 | — | 1 次结束 + 1 次启动，无泄漏/无重复线程 |

#### 4.9.62 切到其他应用时视频声音必须"立刻"停：合并延迟只对自己的悬浮窗生效

**现象**：以前切到别的应用（微信/浏览器…）视频壁纸的声音立刻停，后来要等一下才停。

**根因**：`refreshPowerSave()` 的注释写着"A pause is still applied immediately … 声音没立刻关"，
但实现里对**只有 `visibility` 一个原因**的暂停做了 `VISIBILITY_PAUSE_COALESCE_MS = 600ms` 的合并延迟
（本意是吸收悬浮按钮窗口 add/remove 引起的 ROM"壁纸被覆盖"抖动，实测抖动 ~250-400ms）。
**切到其他应用时 reasons 恰好只有 `visibility`**（屏幕亮着、我们的 UI 不在前台、壁纸不可见），
于是这条真实暂停也被推迟 600ms，声音自然"不能立刻停"。

**修法**：只把"我们自己刚动过悬浮按钮窗口（1.5s 内）"的覆盖报告当作可合并的抖动，其余一律立即暂停：

```kotlin
val blipPossible = ScreenPowerPolicy.isVisibilityBlipOnly(reasons) &&
    SystemClock.elapsedRealtime() - floatingWindowChangedAtMs <= BLIP_COALESCE_WINDOW_MS  // 1500ms
if (blipPossible) { /* postDelayed(600ms) */ } else { applyPowerSave(hint) }
```

`floatingWindowChangedAtMs` 由 `noteFloatingWindowChange()` 在悬浮按钮 show/hide/remove 时刷新
（`hideFloatingButtonNow`、`updateFloatingButton` 的显示/隐藏分支、以及去抖隐藏的 runnable）。
悬浮按钮默认关闭，所以对绝大多数用户来说这条合并路径根本不会触发。

**平板实测（25102RKBEC / Android 17，正在播放带 AAC 音轨的视频壁纸 → 打开微信）**：

```
19:07:21.4    am start com.tencent.mm
19:07:21.929  Wallpaper covered: pausing decode/audio      ← 系统"被覆盖"回调
19:07:21.932  Video audio paused (wallpaper not visible)   ← 3ms 后音频线程暂停
19:07:21.936  Power save ON (visibility | preview=false)   ← 立即，不再 +600ms
```

剩下那 ~0.5s 是系统自己上报"壁纸被覆盖"的延迟（应用上层的回调没有更早的信号），不属于本应用可控范围；
修复前是"系统延迟 + 我们的 600ms"。

**同批次一起修的"进本应用"路径**（用户先反馈的另一半）：`muteAudioKeepingVideo()` 去掉了
`isVideoPlaying` 判断并改为每次重新断言；引擎/渲染器重建时（安装 APK、MIUI 重建壁纸）若我们的 UI
已在前台，创建处直接静音；`AudioSession` 增加 `policyMuted`，使音频线程自己的
`ensurePlaying()/resume()/restart()`（每个播放循环、解码器换格式、首帧可见）无法把声音重新打开。
平板实测：`onStart` → **2ms** 后 "Audio muted" → 64ms 后音频线程退出；应用在前台时连续切换媒体，
日志只有 `Video audio paused (wallpaper not visible)`，**没有** `Video audio started`。

#### 4.9.61 审查批次四：Android 14 部分授权 + 失败路径（全量代码审查的落地）

对整个 `app/src/main`（约 2.2 万行 Kotlin）做了一次通读式审查，以下按修复顺序记录。
共 8 项，全部在本机 `assembleRelease + testDebugUnitTest`（178 例）通过后逐条验证。

1. **`READ_MEDIA_VISUAL_USER_SELECTED` 未声明（High）** —— 代码在 `MediaProbe` 里检查这个权限，
   但 manifest 没声明它。Android 14 起用户在权限对话框选「选择部分照片」时，**未声明该权限的应用
   会被系统降级为完全无媒体访问**，媒体库看起来是空的、切换静默失败，只在导出日志里留一行中文。
   修：manifest 声明该权限；`GroupDetailScreen` 的请求列表在 API 34+ 一并请求它，并把准入条件改成
   「任一读媒体权限已授予即放行」（与 `MediaProbe`、权限回调的 `any {}` 规则一致）。
   **AOSP 14 实测**：① `pm grant … READ_MEDIA_VISUAL_USER_SELECTED` 成功（未声明时该命令会以
   "has not requested permission" 失败）；② 权限对话框出现三项
   `Select photos and videos / Allow all / Don't allow`；③ 选 1 张并允许后，应用照常打开
   「选择文件夹」并 `scanFolders: found 1 folders`，首页也不再出现权限提示卡
   （此时 `VISUAL_USER_SELECTED granted=true`，`READ_MEDIA_IMAGES/VIDEO granted=false`）。
2. **资源守卫补两条（Medium，测试）** —— `LocaleResourcesTest` 原本只比对**键集合**；
   占位符写错（`%1$d` 写成 `%1$s`）不会编译失败，只会在**特定语言 + 特定界面**上运行时抛异常。
   新增：① 同一 key 的 `%n$X` 说明符集合跨 7 种语言必须一致；② 每个 `plurals` 必须有 `other`
   （缺了会在渲染该数量时抛异常）。本轮手工核对 7 种语言全部一致，现在有了自动守卫。
3. **`AppLog` 大小上限单位混用（Medium）** —— 计数用 `String.length`（UTF-16 单元），而阈值比较的是
   `File.length()`（字节）：中文日志一行约 3 字节/字，2MB 上限实际约 6MB 才触发。修：新增纯函数
   `AppLog.lineBytes()`（UTF-8 字节 + 换行）并单测（`"壁纸"` → 7 字节）。
4. **SAF 文件夹扫描只认扩展名（Medium）** —— `queryDocumentFolder` 用 `isSupportedMedia(f.name)` 过滤、
   `detectMediaType(f.name)` 分类；而 `MediaTypes` 自己的注释就写明「非小米设备的 SAF 返回无扩展名
   display name」，这类文件被**静默跳过**（自动扫描 worker 复用同一函数，双路径受影响）。
   修：改为 MIME 优先（`isScannableDocument(name, mime)` + `MediaTypes.fromMimeOrName`），扩展名只做
   兜底；新增单测覆盖「无扩展名 + video/mp4」「无扩展名 + null」等 8 种组合。
5. **首页权限提示卡（Medium）** —— 权限缺失此前只有日志，用户无从自查。新增与「引擎未运行」同款卡片：
   仅在「缺权限 **且** 库里存在 `content://media/...` 媒体」时显示（纯 SAF 导入的库不需要该权限，
   不打扰），带「去授权」按钮跳应用详情页，ON_RESUME 与 5 秒轮询都会刷新。
   配套 DAO `getMediaStoreRowCount()`（Flow，进 `homeUiState`）。
6. **导出日志的隐私提示（Low）** —— 报告含设备型号与文件夹名末两段（`LogText` 的刻意折衷），
   导出前在「运行日志」区块加一行说明；7 种语言都有。
7. **洗牌 id 列表缓存（Low，性能）** —— 洗牌每次切换都要把整个 id 列表拉进内存（18k 库 = 一次全表
   id 扫描 + 18k 元素分配）。新增缓存，键 = `(失效版本号, MediaStore generation, 该槽位启用数)`：
   版本号由 `poke()`、删组/删媒体、`dropGoneMedia` 递增；generation 兜住扫描导入；**启用数**兜住 worker
   导入这种既不 poke 也不走删除路径的变化。两个调用方本来就已经查过该数量，所以键不额外查库。
   新增测试用假 DAO 计数断言：未变时 0 次重读、generation 变、显式失效、计数变各触发 1 次重读。
8. **三个失败路径（Low/Nit）** —— ① `WallpaperApplier.loadMediaBitmapWithTimeout`：超时与线程写回之间
   存在窗口，调用方可能既没拿到结果、又没回收那张全屏位图（等 GC），并把这次 tick 记成 FAILED；
   改成「双方各 `getAndSet` 一次」的握手，保证恰好一方持有并回收。② `FirstFrame.video`：先整帧解码再
   缩放，4K/8K 会瞬时分配 ~33MB；API 27+ 改用 `getScaledFrameAtTime` 直接取屏幕上够用的大小（失败回退）。
   ③ `FloatingSwitchButton` 里 4 处窗口 `updateViewLayout` 的静默 catch 补日志——「悬浮按钮不见了/卡住」
   这类反馈此前没有任何线索。

#### 4.9.50 主题色 / 悬浮按钮颜色：Material 风格网格选色器

需求（用户给了参考截图）："主题和悬浮按钮颜色支持这种选择" —— 即色相×明度**网格**选色 + **透明度滑块**
（棋盘格轨道）+ 预览条 + 取消/保存。

**实现**（沿用项目惯例：纯逻辑进 `engine/` 并配单测，UI 只负责画）

| 文件 | 内容 |
|---|---|
| `engine/ColorPickerGrid.kt` | 网格的纯数学：`COLUMNS=12`（每列 30°）、`TONES=[0.95,0.80,0.65,0.50,0.35,0.20]`（上浅下深）、`colorAt(col,row)`、`hslToRgb`、`toHex`、`withAlphaPercent`、`parseHex`、**`nearestCellOf(hex)`**（把当前颜色映射回格子，用于白圈标记） |
| `ui/screens/ColorGridPicker.kt` | `ColorGridPicker`（预览条 + 网格 + 可选透明度滑块，棋盘格用 `drawBehind` 画）+ `ColorGridPickerDialog`（标题/取消/保存；**选中只改本地状态，按保存才生效**） |
| `SettingsScreen.kt` | `ThemeColorPickerDialog` 内部改为新选色器（保留「跟随系统 Monet」行，存空串）；悬浮按钮颜色行新增**彩虹「自定义」圆点** → 打开带透明度滑块的选色器（透明度写的就是既有的 `setFloatingButtonAlpha`，与设置页的「透明度」滑块是同一个值、互相同步） |

**为什么 `parseHex` 自己实现**：`util.parseHexColorInt` 走 `android.graphics.Color`，在 JVM 单测里不可用 ✗
（第一版有 3 个测试因此失败 ✗），而"当前颜色落在哪个格子"必须可测 ✓。

**单测**（`ColorPickerGridTest`，8 例）：列间距与行单调变暗 ✓、每格不透明 ✓、三原色位置 ✓、HSL 边界（黑/白）✓、
hex 往返、alpha 映射（50% → 0x80，四舍五入 ✓）、**全矩阵往返**（每格颜色都能映射回自己 ✓）、非法输入返回 null ✓。

**真机验收**（截图 `picker_button.png` / `picker_theme.png`）：网格 12×6 上浅下深 ✓、当前色白圈标记正确
（默认蓝 `#1E88E5` 落在第 9 列第 4 行 ✓）、透明度滑块带棋盘格+渐变 ✓、取消/保存 ✓、两个入口均可用 ✓、无崩溃 ✓；
`:app:testDebugUnitTest` **151 条全绿**（新增 8 条）、`lintDebug` 0 error ✓。

**说明**：选色器的透明度显示的是数据库里已有的值（默认 10%；若用户曾拖到 100% 就显示 100%），它只是如实
镜像该设置，不是 bug。

#### 4.9.51 自定义主题色的可读性（对比度）

用户报"软件界面有些文字和图形会随主题颜色变化，影响识别"。根因在 `Theme.kt` 的自定义配色方案里，
on-色槽**写死了**：

```kotlin
onPrimary = Color.White,        // 淡色主题色 + 白字 = 看不见
onPrimaryContainer = primary,   // 用主题色当"容器上的文字"
onSecondaryContainer = primary,
onSurfaceVariant = primary,     // ✗✗ 这是全应用的"次要文字"颜色 → 浅底浅字
```

`onSurfaceVariant` 被用来画说明/副标题（"最多 4 个字…"、"已设置…"、分组副标题…），所以用户一选浅色
（淡黄/淡绿）就整片读不清 ✗；选深色 + 深色模式同理 ✗。

**修法**（纯逻辑 + 单测，沿用项目惯例）

| 文件 | 内容 |
|---|---|
| `engine/ColorContrast.kt` | WCAG 相对亮度、对比度、`readableOn(background)`（在黑/白里挑对比度更高的那个 → 用于 `onPrimary`）、`ensureReadable(color, background, 4.5)`（**沿亮度轴**把颜色推到达到 AA，色相饱和度不变）、`toHsl` |
| `ui/theme/Theme.kt` | `customLightColorScheme` / `customDarkColorScheme` 改为：`onPrimary = readableOn(primary)`、`onSurfaceVariant`/`onPrimaryContainer`/`onSecondaryContainer = readableAccent(...)`；并用 `CompositionLocalProvider(LocalAccentColor provides colorScheme.onSurfaceVariant)` 把"可读的强调色"暴露给界面 |
| 三个界面 | 8 处**在表面上用主题色画的文字/装饰条**（"修改"、间隔值、透明度百分比、分组标题、自定义时间、选中计数、扫描状态、分组标题竖条）改用 `LocalAccentColor.current` |

**测试**（新增 11 条，套件 162 条全绿）

* `ColorContrastTest`（8 条）：亮度/对比度极值（黑白 = 21:1）✓、`readableOn` 对深/浅色分别给黑白 ✓、
  淡黄在浅色表面上被**压暗**且**色相不变** ✓、深色在深色表面上被提亮 ✓、已经达标的颜色**原样返回** ✓、
  HSL 往返（容忍 ±1/255）✓。
* `CustomColorSchemeTest`（3 条）：对 9 种"刁钻"主题色（纯白、纯黑、淡黄、浅灰、近黑、高饱和绿、品红…）
  断言浅色与深色两套方案的 `onSurfaceVariant` 达到 **AA（≥4.5:1）**、`onPrimary` 达到 **≥3:1**、
  `onPrimaryContainer` 达到 AA ✓。

**这个测试当场抓到一个真 bug** ✓：第一版对着**应用自定义的** `DarkColorScheme.surface` 计算强调色 ✗，而
`customDarkColorScheme` 实际产生的是 **Material 默认**表面色 ✗ → 深色模式下部分强调色达不到 AA ✗。
改成 `lightColorScheme().surface` / `darkColorScheme().surface` 后通过 ✓。

**诚实口径**：本机脚本始终没能点中选色网格（对话框布局与推算不一致 ✗），所以**没有**留下"浅色主题下的
截图"✗；可读性由上述单测保证 ✓（比截图更硬），肉眼确认可以自己选一个淡黄色试一下 ✓。

**回归**：`:app:assembleDebug` / `:app:assembleRelease` ✓、**162 单测全绿**（新增 11 条）✓、`lintDebug` 0 error ✓。

## 五、服务与后台组件

### 5.1 WallpaperSwitchService (定时切换服务)

**类型**：前台服务 (Foreground Service)，`foregroundServiceType="specialUse"`，子类型 `wallpaper_auto_switch`。

**通知**：渠道 `wallpaper_switch_service`（IMPORTANCE_LOW，无角标），通知 ID 1001，点击打开 MainActivity。

**工作流程**（基于"调度锚点"而非"固定 delay"）：
1. 以 `timer_last_switch_wall_ms` 为锚点，下一次切换到期时间 = 锚点 + `global_interval_ms`（下限 10 秒）
2. 未到期：`delay(剩余时间)` 后重新复查（屏幕/开关/分组/锚点），因此锚点被改动时立即生效
3. 到期：引擎运行时发 ACTION_SWITCH 广播；引擎未运行时 `WallpaperApplier.applyNext()` 静态切换（`staticApplyInProgress` 防并发 setBitmap）；切换后锚点前移到当前时间
4. 每次 tick 复查 `service_enabled`，被关闭立即自停（防僵尸）
5. 连续 3 次无启用分组 → 自动停止并同步开关状态
6. 异常退避：10 秒 × 失败次数（上限 6 次）

**熄屏暂停 + 从亮屏开始计时（低功耗）**：
- 熄屏/锁屏：取消两个定时循环（无唤醒、无切换、无解码），**熄屏时长不计入间隔**；同时动态壁纸的视频/GIF 完全暂停（见 §4.5 / §4.6）。
- 亮屏（`ACTION_SCREEN_ON`）：把桌面与锁屏两个锚点都前移到"现在"，再启动循环 —— 下一次切换是**完整一个间隔之后**。
  熄屏期间本该到期的那一拍**直接作废**，不会在亮屏瞬间补切一刀（旧行为是保留锚点 + 立即补切）。
- **表面就绪保护**：切换前等动态壁纸 EGL 表面就绪（`LiveWallpaperService.isRenderSurfaceReady()`，250ms 轮询；普通 tick 最多 5 秒，超时重试的 catch-up 最多 15 秒/3 次），避免渲染层丢弃却标记"已显示"导致壁纸空白。
- **与"解锁切换"协调（仅 catch-up 时）**：真正迟到的 tick（服务被杀/被冻结后重启，`SwitchSchedule.isCatchUp == true`）且"解锁切换"开启时会先等 3 秒协调期，只有 `ScreenUnlockReceiver` 真的派发（`notifyUnlockSwitchDispatched()`）才让出这一拍；熄屏→亮屏不再产生 catch-up，所以不会和解锁切换重复。解锁切换本身也会把锚点重置为当前时间。
- **重启续算**：进程被杀重启（自愈/开机/应用更新）后从持久化锚点续算；锚点超过 24 小时视为过期，重新开始计时。
- **重新开启定时切换**、手动切换、手动设置壁纸都会把锚点重置为当前时间。
- 纯调度数学集中在 `engine/SwitchSchedule.kt`（`resolveAnchor` / `waitMs` / `isCatchUp`），有单元测试 `SwitchScheduleTest`。

**自愈与已知限制**：
- `ensureRunning()`（App 回到前台时）+ 引擎侧 watchdog（壁纸可见时）+ `BootReceiver`（开机 / 应用更新 `MY_PACKAGE_REPLACED`）都会在"开关开启但服务已死"时重启服务。
- **平台限制**：Android 12+ 禁止后台应用启动前台服务，所以静态壁纸模式下若 OEM 在锁屏期间强杀了服务，代码层面无法在解锁瞬间自动拉起（动态壁纸模式有系统绑定的 WallpaperService 常驻进程，不受此限制）。此时需要用户手动打开一次 App（会立刻按锚点补切），或在系统里给应用加省电白名单/自启动。

### 5.2 BootReceiver (开机自启动)

**监听**：`ACTION_BOOT_COMPLETED` / `ACTION_MY_PACKAGE_REPLACED`（manifest 注册）
- `goAsync()` + `withTimeout(8000ms)` 保护
- 读取 `service_enabled`，为 true 则 `startForegroundService`

### 5.3 ScreenUnlockReceiver (解锁切换)

**监听**：`ACTION_USER_PRESENT`（在 `WallpaperSwitcherApp.onCreate` 中动态注册 —— manifest 注册的隐式广播在 Android 8+ 不会投递；API 33+ 用 `RECEIVER_EXPORTED`）

**逻辑**：
1. `goAsync()` + `withTimeout(8000ms)` 保护
2. 读取 `unlock_switch_enabled`，为 true 且引擎已运行（最多等待 1.2s 重试）则发送 ACTION_SWITCH 广播，并调用 `WallpaperSwitchService.notifyUnlockSwitchDispatched()` 通知定时器：这一拍由解锁切换处理，且锚点从当前时间重新计时
3. 引擎未运行（静态模式）时跳过 —— 设置页开启时已有 Toast 提示（此时定时器会自行补切）

### 5.4 LiveWallpaperService (动态壁纸服务)

**类型**：Android `WallpaperService`（`BIND_WALLPAPER` 权限，manifest 注册）

**引擎生命周期**：
```
onCreate → onCreateEngine → onSurfaceCreated → onSurfaceChanged
    → onVisibilityChanged(true/false) ↔ onVisibilityChanged(true/false)
    → onSurfaceDestroyed → onDestroy
```

**广播接收器**（引擎 onCreate 动态注册，onDestroy 注销）：
- `ACTION_SWITCH`：RECEIVER_NOT_EXPORTED（仅本应用可触发切换），携带可选 `EXTRA_TARGET_ID`
- `ACTION_SCREEN_OFF/ON`：熄屏/亮屏功率节省

**触摸事件**：`setTouchEventsEnabled(true)` 在引擎 `onCreate` 开启一次；`onSurfaceCreated` 里通过 `reassertTouchEvents()` **异步 + 防重入**重设（部分启动器在表面重建时清除触摸标志）。不可在主线程同步重设：在 `onSurfaceCreated` 里同步调用 `setTouchEventsEnabled(true)` 会在部分设备（Android 16 平板/HyperOS）同步重入 `updateSurface()` → 再次触发 `onSurfaceCreated`，造成无限递归 → `StackOverflowError`（小米平板实测崩溃/黑屏根因）。双击检测是自定义 DOWN/UP 双向判定（部分启动器吞掉一个 DOWN 事件时从 UP 对识别），300ms 窗口 + 40dp 容差。

**悬浮按钮**：`FloatingSwitchButton` 为 `TYPE_APPLICATION_OVERLAY` 窗口，需 `SYSTEM_ALERT_WINDOW` 权限；可拖拽（位置持久化）、颜色/透明度可配置、实时更新；仅在壁纸可见且无应用遮挡时显示；双击直接调用引擎 `requestSwitchFromOutside()`（免广播回环）。

**状态保护**：
- 切屏/旋转后 EGL 表面重建，渲染线程与 GL 资源存活
- 可见性切换只降速不停止播放；恢复可见时按 `LAST_IMAGE_ID` 重绘
- 洗牌状态在 onDestroy 时以 800ms 上限写回数据库（防主线程 ANR）
- 全局运行标志 `engineRunning`/`activeEngine` 只由**真实**引擎认领：系统动态壁纸对话框会创建本服务的 **preview 引擎**（`isPreview=true`），旧实现让预览引擎也认领并在对话框关闭时清空这两个标志，于是真实引擎还活着、应用却以为没运行 —— 下一次定时切换就走静态路径，把用户刚设置的动态壁纸覆盖成静态图片（"设置后又要重新设置"）。现在 `onCreate` 里 `if (!isPreview)` 才认领。
- 所有**桌面**静态写入前都会调用 `LiveWallpaperService.isHomeLiveWallpaper()`（基于 `WallpaperManager.getWallpaperInfo()`）：只要当前桌面就是本应用的动态壁纸，就拒绝静态写入、改为把切换交给引擎。引擎进程被 ROM 后台杀掉而壁纸仍是"动态"的这段时间，这条保护能避免动态壁纸被静态图片替换；锁屏（FLAG_LOCK）不受影响，仍按设计写静态图。
- 失效媒体自动清理：加载失败时先用 `contentResolver.openInputStream()` 区分「文件已被删除/移动/权限被回收」与临时错误（提供方忙、解码超时）。前者 `dropMediaIfGone()` 直接删除该行并复位 `LAST_IMAGE_ID`，避免每次定时切换都重新读取一个已失效的 URI（平板日志里同一张丢失图片被重试 50+ 次）；后者只做本次会话内的 `failedMediaIds` 屏蔽，媒体行保留

### 5.5 FolderAutoScanWorker (文件夹自动扫描)

- `PeriodicWorkRequest`，间隔下限 15 分钟，约束 `BatteryNotLow`，唯一工作名 `folder_auto_scan`
- 扫描所有 `isFromFolder=1` 的已导入文件夹（SAF content:// 走 DocumentFile，MediaStore 路径走 MediaStore 查询），按 URI 去重后插入新文件
- 每个文件夹一个事务，100 行一批，原子提交

---

## 六、UI 界面

### 6.1 导航结构

```
WallpaperSwitcherApp (Scaffold)
├── TopAppBar (标题 + 返回按钮)
├── NavigationBar (首页 / 设置)
└── Content
    ├── Screen.Home → HomeScreen
    ├── Screen.GroupDetail → GroupDetailScreen
    └── Screen.Settings → SettingsScreen
```

使用 sealed class `Screen` 管理导航状态（`rememberSaveable` + 自定义 Saver，跳系统动态壁纸选择器后仍返回原页面），不使用 Navigation 组件。

### 6.2 HomeScreen (首页)

- 服务总开关卡片（运行中/已停止 + 渐变背景 + "立即切换壁纸"按钮）
- 引擎未运行时的醒目警告卡片（定时/双击/解锁切换无法生效）
- 分组列表：名称 + 媒体数徽章 + 启用开关 + 类型图标
- "新建分组"对话框
- **分组多选**：标题右侧的清单图标（或长按任意分组卡片）进入多选模式，顶栏换成工具条
  ——退出 / 全选（`allIds` 直接来自列表，不需额外查询）/ 已选 N/M / **批量启用** / **批量删除**。
  选中卡片用主色边框 + 主色底高亮，右侧开关换成只读 `Checkbox`（点卡片本身切换选中，避免与开关抢点击）。
  选择状态用 `SnapshotStateMap<Long, Boolean>` per-key 读取，勾选一项只重组那一张卡片。
  批量删除带确认对话框（分组里的媒体记录会一起移除，手机里的文件不动）；
  `WallpaperViewModel.deleteGroups()` / `setGroupsEnabled()` 分别复用单条删除的游标清理逻辑
  （`clearCursorsOfDeletedMedia()`：HOME/锁屏/最近写入/手动选择五处 id 若已悬空则清零）与一次
  `WallpaperSwitchService.poke()`（批量启用只唤醒一次定时循环，而不是每个分组一次）。

### 6.3 GroupDetailScreen (分组详情)

- 分组信息头部（名称、媒体数、删除按钮 + 确认对话框）
- 操作栏："添加壁纸" / "批量操作" / "清理失效"（扫描无法打开的媒体并批量删除）
- 媒体网格：`LazyVerticalGrid` 自适应列宽（104dp），**打开时全量加载**（不分页），**返回前台（ON_RESUME）自动刷新**（自动扫描新增的图片立即可见），Coil 200px 缩略图（视频用 VideoFrameDecoder）
- 选择模式：per-key 快照选择（不整屏重算），全选/取消全选/删除所选（500 一批 DELETE 防 SQL 变量上限）
- 右侧快速滚动条（Grid/List 通用，拖拽/点按跳转）
- 添加对话框：单张 / 多张 / 扫描到的文件夹（可搜索、排序、多选、样本缩略图、**一键重新扫描**媒体库）/ 系统文件夹
- 壁纸预览对话框（设为壁纸确认）
- 「选择文件夹」对话框排版：搜索框 → **工具行**（`共 N 个文件夹 · 已选 M` + `全选` + `重新扫描`）→ **单行横向滚动的排序 chip**（媒体多优先/名称排序/时间排序）→ 分隔线 → 文件夹列表 → 取消/导入所选。`重新扫描` 属于动作而非排序条件，原先与排序 chip 混排既容易被误认成排序项、也会把那一行挤到换行并把列表压矮；排序 chip 现在放在 `horizontalScroll` 容器里，窄屏（手机上）也不会折成两行。列表项第二行在数量之后补上相对路径（`Pictures/wstest`），用于区分同名文件夹（例如同时存在 `Pictures/wstest` 与 `Movies/wstest`）。
- 「选择文件夹」不用 Material3 `AlertDialog` 的标准按钮区：它会把内容与按钮之间撑开（文本区 24dp + 按钮区 8dp ≈ 32dp）并在按钮下方再留 48dp，列表短时看起来就是"列表和导入所选之间一大片空白"。现在改为自绘 Material 表面（`Dialog` + `Surface(shape = extraLarge, surface, tonalElevation = 6dp)`），操作行紧跟列表：列表→按钮 4dp、按钮→底边 12dp，实测（1440×3200 @600dpi，即 384×853dp，与平板一致）列表与按钮文字之间的可见空白约 26dp、对话框整体高度比 AlertDialog 版少约 46dp。
  宽度用 `LocalConfiguration.screenWidthDp` 显式算出 `min(92% 屏宽, 560dp)`；**不要**用 `fillMaxWidth(...)` 与 `widthIn(...)` 组合（两者会产生互相矛盾的约束，对话框会变成没有内容的白板——"导入所选不见了"就是这个原因）。自定义 `Dialog` 用平台默认宽度时窗口是 `WRAP_CONTENT`，内部 `fillMaxWidth()` 会在无界约束下测量失败，同样要避免。
  稳健性：外层 Column 限制 `heightIn(max = 92% 屏高)`，中间内容列与列表都用 `weight(1f, fill = false)`，内容放不下时先压缩列表，**操作行（取消/导入所选）永远不会被挤出可视区**（大字号/小屏同样成立）。自绘对话框下 `uiautomator dump` 有时抓不到其节点，验证时以截图/窗口尺寸为准。
- 设为动态壁纸的提示：`setAsLiveWallpaper` 打开系统对话框前会发出 `hintMessage`，由 `HintOverlay` 显示成**非聚焦、不可触摸的悬浮提示条**（`TYPE_APPLICATION_OVERLAY`，默认 **5 秒**，系统对话框弹出时依然可见、且不遮挡操作）。普通 Toast 只有约 2 秒，且 Android 12+ 会丢弃后台 Toast，用户往往来不及看清"要点哪个按钮"；没有「显示在其他应用上层」权限时自动回退为按 3 秒间隔重复显示的 Toast，保证总时长同为 5 秒。
  提示词按分组「应用位置」区分：**桌面**分组 →「请选择“主屏幕”（该分组只用于桌面）」；**两者**分组 →「请选择“主屏幕和锁定屏幕”」；**锁屏**分组 →「点击“设置壁纸”即可，锁屏会自动显示为该分组图片」（系统动态壁纸界面没有"锁屏"选项，确认后应用会按分组「应用位置」把图片补写到锁屏槽位，所以只需点确认）。引号内的选项名/按钮名在提示条（及回退 Toast）里自动**加粗强调**（`HintOverlay.emphasize()`，无需在文案里写标记）。
  提示条会在**离开系统动态壁纸界面时立即消失**：应用回到前台（`ON_RESUME`）时 `HintOverlay.dismiss()`；真实壁纸引擎被创建（= 已点「设为壁纸」）时也会立即消失，不必等到 5 秒到期。

### 6.4 SettingsScreen (设置)

| 分组 | 设置项 | 类型 |
|------|--------|------|
| 壁纸设置 | 切换间隔 | 对话框（10 秒~24 小时 + 自定义秒数） |
| 壁纸设置 | 切换模式 | FilterChip（随机/顺序/洗牌） |
| 壁纸设置 | 缩放模式 | FilterChip（填充/适应/拉伸） |
| 壁纸设置 | 清晰度增强 | FilterChip（自动/关闭/增强） |
| 切换方式 | 定时切换 / 解锁切换 / 双击切换 / 悬浮双击按钮 / 切换过渡动画 | Switch |
| 悬浮按钮外观 | 透明度滑块（5%~100%，200ms 防抖写库）+ 9 色板 | Slider + 色板 |
| 文件夹自动扫描 | 开关 + 间隔（1/6/12/24 小时） | Switch + 对话框 |
| 外观 | 主题颜色（16 色 + 跟随系统） | 色板对话框 |

---

#### 4.9.52 审查批次一：不再"把没成功的事当成成功"

全仓审查（69 个 Kotlin 文件 / 24411 行）里最集中的一类缺陷是**操作结果被丢弃后仍宣称成功**。本批修四处。

| 编号 | 位置 | 问题 | 修法 |
|---|---|---|---|
| R1 | `engine/WallpaperApplier.kt:249,311` | `writeWallpaper()` 走 `setBitmap` 回退时丢弃返回值并无条件 `return true`；调用点又写死 `applied = true` | 回退分支改为 `return manager.setBitmap(...) > 0`；调用点 `applied = writeWallpaper(...)`，失败时记日志 |
| R2 | `ui/screens/ColorGridPicker.kt:228` | 「按钮颜色」对话框按**保存不关闭** —— 设置页只在 `onDismiss` 里清 flag，`onConfirm` 不清 | 确认按钮内应用后直接 `onDismiss()`（主题色对话框之所以正常，只是因为它恰好顺手清了 flag） |
| R3 | `wallpaper/LiveWallpaperService.kt:2052` | 看门狗 `if (WallpaperSwitchService.running) return` 把"服务对象活着"当成"循环活着"：两个循环在息屏时 `return` 退出（**故意的**，零唤醒），只靠 `ACTION_SCREEN_ON` 复活；漏一次广播就永久失效 | 服务在跑时改为调用 `poke(applicationContext)`（`wakeLoopsInPlace()` 会取消并重启两个循环，且循环自身会因定时器关闭而退出，所以仍然安全）。30s 节流与"仅在可见时"的调用条件不变，零唤醒契约不变 |
| R4 | `viewmodel/WallpaperViewModel.kt:1366` | 首页选中后 `sendTargetBroadcast(...)` 发完即忘，却置 `applied = true` → 引擎在读取标志与发送之间死掉时，游标前移 + 提示成功而屏幕未变；`sendTargetBroadcast` 随之成为死代码 | 改用带返回值的 `LiveWallpaperService.pushConfirmedPickToEngine(id)`，只用它的结果决定是否宣称成功；**保留** `LAST_IMAGE_ID` 的写入（其语义是"记录用户的选择"，无活引擎时下次启动渲染它 —— 见该函数的 KDoc），删除 `sendTargetBroadcast` |

**审查结论的一处更正** ✓：审计报告称 `WallpaperManager.setBitmap(...)` 返回 Boolean 且被丢弃 ✗ —— 编译期证明它返回的是 **Int（壁纸 id）** ✗，因此正确的失败判据是 **id ≤ 0** ✓（本批按此实现 ✓）；同一分支里那句日志文案 "setBitmap returned false" 也是错的 ✓，一并改正 ✓。

**验证**：`:app:compileDebugKotlin` ✓、`:app:testDebugUnitTest` **162 条全绿** ✓、`:app:assembleRelease` ✓。

**诚实口径**：R1/R3/R4 本轮**没有新增单元测试** ✗ —— 它们的判定依赖 `WallpaperManager`、`Service` 生命周期这些 Android 框架对象 ✓，在本仓库的 JVM 单测环境里需要 Robolectric ✗（等于引入新依赖 ✗，与"不改公共 API / 不引入新依赖"的约束冲突 ✓）；把 `id > 0` 抽成纯函数再测只是同义反复 ✗，没有价值 ✓。R2 是 Compose 交互 ✓，本次尝试装机验证时**设备未连接** ✗（`adb: device not found` ✓）→ 待下次接上设备后按脚本 `.repair/verify_r2_dialog.py` 复验 ✓（该脚本只点「保存」并断言对话框标题消失 ✓，不依赖命中色块 ✓）。

#### 4.9.53 审查批次二：选色网格无障碍 + 透明度下限对齐

* **无障碍**：选色网格原来 72 个色块都是无标签的 `Box + clickable`，TalkBack 只能读成「未标记按钮」。
  现在每格带 `semantics { role = Role.RadioButton; selected = isSelected; contentDescription = "色相 N°，明度 M%" }`，
  标签由 `ColorPickerGrid` 的同一组数字生成，读出来的颜色不会和看到的颜色漂移 ✓。
  遗留（需决策）：12 列布局在 AlertDialog 文本槽里每格约 20dp，仍低于 48dp 建议；**降列数解决不了**（要到 48dp 得降成
  5 列，完全不像参考图），真正的修法是把对话框换成全宽 Dialog（更接近参考截图），尚未实施。
* **透明度下限**：选色器的滑块原本是 `0f..100f`，而 `setFloatingButtonAlpha` 会 clamp 到
  `FLOATING_BUTTON_ALPHA_MIN = 5`，于是可以显示 0–4% 而实际存 5%。现在 `ColorGridPicker` 增加
  `alphaMinPercent` 参数，设置页传入该常量，显示值与存储值一致 ✓。
* **Locale（R10）经实验证伪** ✗：见 4.9.54 与 `ColorPickerLocaleTest` —— `%X` 不受 Locale 影响，
  原报告描述的"非 ASCII 十六进制 → 颜色静默不生效"不成立；`Locale.ROOT` 仅作防御性写法保留 ✓。
* **回归**：`:app:assembleDebug` / `:app:assembleRelease` ✓、**164 单测全绿** ✓、`lintDebug` 0 error ✓。

#### 4.9.54 审查批次三：对比度要按"文字真正落在哪个背景上"校准

审计指出 4.9.51 的对比度修正**只对 `surface` 校准** ✗，而 `onPrimaryContainer` / `onSurfaceVariant`
的文字实际画在**半透明容器**上（`primary.copy(alpha = 0.15f)` 等叠在 surface 之上）✓ —— 饱和主题色会把
背景拉向文字颜色 ✗，而测试断言的正是 `surface` ✗，所以永远看不出这个缺口 ✓。

**实现**：
* `engine/ColorContrast.composite(fg, bg, alpha)`：新增纯函数做 alpha 合成 ✓（可单测 ✓）。
* `Theme.kt`：`readableAccent()` 改为对**复合后的容器**校准 ✓，并在"容器 15%/30%"与"surfaceVariant
  10%/18%"两个背景里取**较难的那个** ✓；容器/variant 的 alpha 提升为具名常量 ✓，供测试复用 ✓（测试断言的
  背景与实现的背景不会再漂移 ✓）。
* **余量**：校准目标从 4.5 提到 **4.7**（`ACCENT_TARGET_RATIO = AA_NORMAL + 0.2`）✓。原因是几个背景之间
  只差千分之几 ✓，按 4.5 精确校准会让另一个背景停在 **4.49:1** ✗（纯白主题色就是这种情况 ✓，见下）。
  留 0.2 的余量视觉上无差别 ✓，但让**所有**背景都稳过 AA ✓。

**这个过程本身值得记录** ✓：改完之后 5 条测试变红 ✗，其中包含**改动前是绿的**两条 ✓ —— 失败信息给出
`FFFFFFFF on surfaceVariant gives 4.49:1` ✓，正好证明"只对某一个背景校准"是治标不治本 ✓；另外一条是我自己
把测试期望写反了 ✓（alpha=0 时显示的是**背景**色 ✓，不是前景 ✓），已修正 ✓。**没有靠放宽断言蒙混过去** ✓。

**回归**：`ContainerContrastTest`（3 条 ✓：合成数学 + 浅色/深色两套方案对复合容器达 AA ✓，8 种刁钻主题色 ✓）、
全套 **167 单测全绿** ✓、`lintDebug` 0 error ✓。

#### 4.9.55 审查批次三：渲染路径资源与等待预算

本批处理全仓审查里"渲染/拆解路径上的资源"这一类缺陷（R6/R15/R16）以及两处等待超预算（R8）。

| 编号 | 位置 | 问题 | 修法 |
|---|---|---|---|
| R6 | `wallpaper/WallpaperRenderer.kt`（`setupEglContext` / `cleanupAll`） | 所有 renderer 都用 `eglGetDisplay(EGL_DEFAULT_DISPLAY)`，**同一进程共用同一句柄** ✓（主屏与预览可并存 ✓）；`eglInitialize` 失败时该句柄被保留 ✗，于是 `cleanupAll()` 会对一个**本实例从未初始化成功**的 display 调 `eglTerminate` ✗ —— init/terminate 计数失衡，可能减掉兄弟 engine 的最后一个引用并终结其 context（壁纸冻结） | 新增 `eglInitializedHere` 标志 ✓：仅在 `eglInitialize` 成功后置位 ✓，`cleanupAll()` 用它守卫 `eglTerminate` ✓，末尾复位 ✓ |
| R15 | `WallpaperRenderer.kt`（`openAudioDescriptor` / `decodeLoop` 的 `VideoOpen`） | "超时判定"与"发布结果"不是原子操作 ✗：超时若落在 `abandon.get()` 与 `result.set(afd)` 之间，等待方已 `return null` 走人 ✓ → 该 fd **再无人关闭** ✗ → 云盘/SAF provider 每超时一次漏一个 ✓ 累积 `TooManyOpenFiles` | 发布后再查一次 `abandon` ✓，命中则 `compareAndSet(afd, null)` **取回并关闭** ✓（CAS 保证调用方与辅助线程不会重复关闭 ✓）；两处对称修改 ✓ |
| R16 | `WallpaperRenderer.kt`（EGL context 重建分支） | context 因 `eglMakeCurrent` 失败被销毁重建时，只重建了 image/black 资源 ✗；`surfaceTexture`/`codecSurface`/`videoTexId` 仍指向**已死 context** 的对象 ✗，而 `reuseGl()` 只判断"非 0 即复用" ✗ → 每帧 `updateTexImage()` 失败 ✓ → **视频冻结在最后一帧** ✓ 直到换媒体 ✓ | 在重建分支里先调 `cleanupVideoResourcesOnRenderThread()` ✓（释放 ST + Surface、删除纹理并把 `videoTexId` 归零 ✓，对已清理状态是 no-op ✓） |
| R8a | `service/WallpaperSwitchService.kt`（锁屏 tick 争用守卫） | 循环**先 delay 一整步再检查是否超预算** ✗ → `STATIC_APPLY_WAIT_MAX_MS = 6000` 实际累计 **9.25s** ✗（250+500+1000×7 ✓） | 每步先算剩余预算并夹住 sleep ✓，为 0 直接退出 ✓ |
| R8b | 同上（`withStaticApply`，**手动"立即切换"走的正是这条** ✓） | 同一 overshoot 的第二份拷贝 ✗ | 同样的夹取 ✓ |
| — | `ui/screens/ColorGridPicker.kt` | 选色对话框沿用 `AlertDialog` 默认宽度 ✗ → 12 列被挤进约 260dp，每格约 **20dp** ✗ 远低于 48dp 触控建议 ✓ | 加 `properties = DialogProperties(usePlatformDefaultWidth = false)` + `modifier = Modifier.fillMaxWidth(0.94f)` ✓ → 每格约 **45–90dp** ✓（并顺带解决 R5 的触控目标遗留 ✓，视觉更接近参考图 ✓） |

**尚未处理**（明确留档，避免遗忘）：R9（锁屏路径**先推进锚点再执行** ✓ → `applyNext` 返回 null 时白等一个完整间隔 ✓）；`updateTexImage` 的 catch **未按 5s 限流** ✗（相邻两处已限流 ✓，纯日志噪音 ✓）。

**诚实口径** ✓：R6/R15/R16 都只能靠**代码推理**验证 ✓（依赖 libEGL 引用计数、并发窗口期、GL context 语义 ✓）—— 本仓库的 JVM 单测覆盖不到 ✗，需要仪器测试（双 engine ✓ / 假 provider 卡超时 ✓ / 播放中重建 context ✓）✓，而当前**没有可用设备** ✗（`adb: device not found` ✓）。因此这三条**已修但缺自动化回归** ✓，行为正确性需真机复核。

**回归**：`:app:assembleDebug` / `:app:assembleRelease` ✓、**167 单测全绿** ✓、`lintDebug` 0 error ✓。

## 五、服务与后台组件

### 5.1 WallpaperSwitchService (定时切换服务)

**类型**：前台服务 (Foreground Service)，`foregroundServiceType="specialUse"`，子类型 `wallpaper_auto_switch`。

**通知**：渠道 `wallpaper_switch_service`（IMPORTANCE_LOW，无角标），通知 ID 1001，点击打开 MainActivity。

**工作流程**（基于"调度锚点"而非"固定 delay"）：
1. 以 `timer_last_switch_wall_ms` 为锚点，下一次切换到期时间 = 锚点 + `global_interval_ms`（下限 10 秒）
2. 未到期：`delay(剩余时间)` 后重新复查（屏幕/开关/分组/锚点），因此锚点被改动时立即生效
3. 到期：引擎运行时发 ACTION_SWITCH 广播；引擎未运行时 `WallpaperApplier.applyNext()` 静态切换（`staticApplyInProgress` 防并发 setBitmap）；切换后锚点前移到当前时间
4. 每次 tick 复查 `service_enabled`，被关闭立即自停（防僵尸）
5. 连续 3 次无启用分组 → 自动停止并同步开关状态
6. 异常退避：10 秒 × 失败次数（上限 6 次）

**熄屏暂停 + 从亮屏开始计时（低功耗）**：
- 熄屏/锁屏：取消两个定时循环（无唤醒、无切换、无解码），**熄屏时长不计入间隔**；同时动态壁纸的视频/GIF 完全暂停（见 §4.5 / §4.6）。
- 亮屏（`ACTION_SCREEN_ON`）：把桌面与锁屏两个锚点都前移到"现在"，再启动循环 —— 下一次切换是**完整一个间隔之后**。
  熄屏期间本该到期的那一拍**直接作废**，不会在亮屏瞬间补切一刀（旧行为是保留锚点 + 立即补切）。
- **表面就绪保护**：切换前等动态壁纸 EGL 表面就绪（`LiveWallpaperService.isRenderSurfaceReady()`，250ms 轮询；普通 tick 最多 5 秒，超时重试的 catch-up 最多 15 秒/3 次），避免渲染层丢弃却标记"已显示"导致壁纸空白。
- **与"解锁切换"协调（仅 catch-up 时）**：真正迟到的 tick（服务被杀/被冻结后重启，`SwitchSchedule.isCatchUp == true`）且"解锁切换"开启时会先等 3 秒协调期，只有 `ScreenUnlockReceiver` 真的派发（`notifyUnlockSwitchDispatched()`）才让出这一拍；熄屏→亮屏不再产生 catch-up，所以不会和解锁切换重复。解锁切换本身也会把锚点重置为当前时间。
- **重启续算**：进程被杀重启（自愈/开机/应用更新）后从持久化锚点续算；锚点超过 24 小时视为过期，重新开始计时。
- **重新开启定时切换**、手动切换、手动设置壁纸都会把锚点重置为当前时间。
- 纯调度数学集中在 `engine/SwitchSchedule.kt`（`resolveAnchor` / `waitMs` / `isCatchUp`），有单元测试 `SwitchScheduleTest`。

**自愈与已知限制**：
- `ensureRunning()`（App 回到前台时）+ 引擎侧 watchdog（壁纸可见时）+ `BootReceiver`（开机 / 应用更新 `MY_PACKAGE_REPLACED`）都会在"开关开启但服务已死"时重启服务。
- **平台限制**：Android 12+ 禁止后台应用启动前台服务，所以静态壁纸模式下若 OEM 在锁屏期间强杀了服务，代码层面无法在解锁瞬间自动拉起（动态壁纸模式有系统绑定的 WallpaperService 常驻进程，不受此限制）。此时需要用户手动打开一次 App（会立刻按锚点补切），或在系统里给应用加省电白名单/自启动。

### 5.2 BootReceiver (开机自启动)

**监听**：`ACTION_BOOT_COMPLETED` / `ACTION_MY_PACKAGE_REPLACED`（manifest 注册）
- `goAsync()` + `withTimeout(8000ms)` 保护
- 读取 `service_enabled`，为 true 则 `startForegroundService`

### 5.3 ScreenUnlockReceiver (解锁切换)

**监听**：`ACTION_USER_PRESENT`（在 `WallpaperSwitcherApp.onCreate` 中动态注册 —— manifest 注册的隐式广播在 Android 8+ 不会投递；API 33+ 用 `RECEIVER_EXPORTED`）

**逻辑**：
1. `goAsync()` + `withTimeout(8000ms)` 保护
2. 读取 `unlock_switch_enabled`，为 true 且引擎已运行（最多等待 1.2s 重试）则发送 ACTION_SWITCH 广播，并调用 `WallpaperSwitchService.notifyUnlockSwitchDispatched()` 通知定时器：这一拍由解锁切换处理，且锚点从当前时间重新计时
3. 引擎未运行（静态模式）时跳过 —— 设置页开启时已有 Toast 提示（此时定时器会自行补切）

### 5.4 LiveWallpaperService (动态壁纸服务)

**类型**：Android `WallpaperService`（`BIND_WALLPAPER` 权限，manifest 注册）

**引擎生命周期**：
```
onCreate → onCreateEngine → onSurfaceCreated → onSurfaceChanged
    → onVisibilityChanged(true/false) ↔ onVisibilityChanged(true/false)
    → onSurfaceDestroyed → onDestroy
```

**广播接收器**（引擎 onCreate 动态注册，onDestroy 注销）：
- `ACTION_SWITCH`：RECEIVER_NOT_EXPORTED（仅本应用可触发切换），携带可选 `EXTRA_TARGET_ID`
- `ACTION_SCREEN_OFF/ON`：熄屏/亮屏功率节省

**触摸事件**：`setTouchEventsEnabled(true)` 在引擎 `onCreate` 开启一次；`onSurfaceCreated` 里通过 `reassertTouchEvents()` **异步 + 防重入**重设（部分启动器在表面重建时清除触摸标志）。不可在主线程同步重设：在 `onSurfaceCreated` 里同步调用 `setTouchEventsEnabled(true)` 会在部分设备（Android 16 平板/HyperOS）同步重入 `updateSurface()` → 再次触发 `onSurfaceCreated`，造成无限递归 → `StackOverflowError`（小米平板实测崩溃/黑屏根因）。双击检测是自定义 DOWN/UP 双向判定（部分启动器吞掉一个 DOWN 事件时从 UP 对识别），300ms 窗口 + 40dp 容差。

**悬浮按钮**：`FloatingSwitchButton` 为 `TYPE_APPLICATION_OVERLAY` 窗口，需 `SYSTEM_ALERT_WINDOW` 权限；可拖拽（位置持久化）、颜色/透明度可配置、实时更新；仅在壁纸可见且无应用遮挡时显示；双击直接调用引擎 `requestSwitchFromOutside()`（免广播回环）。

**状态保护**：
- 切屏/旋转后 EGL 表面重建，渲染线程与 GL 资源存活
- 可见性切换只降速不停止播放；恢复可见时按 `LAST_IMAGE_ID` 重绘
- 洗牌状态在 onDestroy 时以 800ms 上限写回数据库（防主线程 ANR）
- 全局运行标志 `engineRunning`/`activeEngine` 只由**真实**引擎认领：系统动态壁纸对话框会创建本服务的 **preview 引擎**（`isPreview=true`），旧实现让预览引擎也认领并在对话框关闭时清空这两个标志，于是真实引擎还活着、应用却以为没运行 —— 下一次定时切换就走静态路径，把用户刚设置的动态壁纸覆盖成静态图片（"设置后又要重新设置"）。现在 `onCreate` 里 `if (!isPreview)` 才认领。
- 所有**桌面**静态写入前都会调用 `LiveWallpaperService.isHomeLiveWallpaper()`（基于 `WallpaperManager.getWallpaperInfo()`）：只要当前桌面就是本应用的动态壁纸，就拒绝静态写入、改为把切换交给引擎。引擎进程被 ROM 后台杀掉而壁纸仍是"动态"的这段时间，这条保护能避免动态壁纸被静态图片替换；锁屏（FLAG_LOCK）不受影响，仍按设计写静态图。
- 失效媒体自动清理：加载失败时先用 `contentResolver.openInputStream()` 区分「文件已被删除/移动/权限被回收」与临时错误（提供方忙、解码超时）。前者 `dropMediaIfGone()` 直接删除该行并复位 `LAST_IMAGE_ID`，避免每次定时切换都重新读取一个已失效的 URI（平板日志里同一张丢失图片被重试 50+ 次）；后者只做本次会话内的 `failedMediaIds` 屏蔽，媒体行保留

### 5.5 FolderAutoScanWorker (文件夹自动扫描)

- `PeriodicWorkRequest`，间隔下限 15 分钟，约束 `BatteryNotLow`，唯一工作名 `folder_auto_scan`
- 扫描所有 `isFromFolder=1` 的已导入文件夹（SAF content:// 走 DocumentFile，MediaStore 路径走 MediaStore 查询），按 URI 去重后插入新文件
- 每个文件夹一个事务，100 行一批，原子提交

---

## 六、UI 界面

### 6.1 导航结构

```
WallpaperSwitcherApp (Scaffold)
├── TopAppBar (标题 + 返回按钮)
├── NavigationBar (首页 / 设置)
└── Content
    ├── Screen.Home → HomeScreen
    ├── Screen.GroupDetail → GroupDetailScreen
    └── Screen.Settings → SettingsScreen
```

使用 sealed class `Screen` 管理导航状态（`rememberSaveable` + 自定义 Saver，跳系统动态壁纸选择器后仍返回原页面），不使用 Navigation 组件。

### 6.2 HomeScreen (首页)

- 服务总开关卡片（运行中/已停止 + 渐变背景 + "立即切换壁纸"按钮）
- 引擎未运行时的醒目警告卡片（定时/双击/解锁切换无法生效）
- 分组列表：名称 + 媒体数徽章 + 启用开关 + 类型图标
- "新建分组"对话框
- **分组多选**：标题右侧的清单图标（或长按任意分组卡片）进入多选模式，顶栏换成工具条
  ——退出 / 全选（`allIds` 直接来自列表，不需额外查询）/ 已选 N/M / **批量启用** / **批量删除**。
  选中卡片用主色边框 + 主色底高亮，右侧开关换成只读 `Checkbox`（点卡片本身切换选中，避免与开关抢点击）。
  选择状态用 `SnapshotStateMap<Long, Boolean>` per-key 读取，勾选一项只重组那一张卡片。
  批量删除带确认对话框（分组里的媒体记录会一起移除，手机里的文件不动）；
  `WallpaperViewModel.deleteGroups()` / `setGroupsEnabled()` 分别复用单条删除的游标清理逻辑
  （`clearCursorsOfDeletedMedia()`：HOME/锁屏/最近写入/手动选择五处 id 若已悬空则清零）与一次
  `WallpaperSwitchService.poke()`（批量启用只唤醒一次定时循环，而不是每个分组一次）。

### 6.3 GroupDetailScreen (分组详情)

- 分组信息头部（名称、媒体数、删除按钮 + 确认对话框）
- 操作栏："添加壁纸" / "批量操作" / "清理失效"（扫描无法打开的媒体并批量删除）
- 媒体网格：`LazyVerticalGrid` 自适应列宽（104dp），**打开时全量加载**（不分页），**返回前台（ON_RESUME）自动刷新**（自动扫描新增的图片立即可见），Coil 200px 缩略图（视频用 VideoFrameDecoder）
- 选择模式：per-key 快照选择（不整屏重算），全选/取消全选/删除所选（500 一批 DELETE 防 SQL 变量上限）
- 右侧快速滚动条（Grid/List 通用，拖拽/点按跳转）
- 添加对话框：单张 / 多张 / 扫描到的文件夹（可搜索、排序、多选、样本缩略图、**一键重新扫描**媒体库）/ 系统文件夹
- 壁纸预览对话框（设为壁纸确认）
- 「选择文件夹」对话框排版：搜索框 → **工具行**（`共 N 个文件夹 · 已选 M` + `全选` + `重新扫描`）→ **单行横向滚动的排序 chip**（媒体多优先/名称排序/时间排序）→ 分隔线 → 文件夹列表 → 取消/导入所选。`重新扫描` 属于动作而非排序条件，原先与排序 chip 混排既容易被误认成排序项、也会把那一行挤到换行并把列表压矮；排序 chip 现在放在 `horizontalScroll` 容器里，窄屏（手机上）也不会折成两行。列表项第二行在数量之后补上相对路径（`Pictures/wstest`），用于区分同名文件夹（例如同时存在 `Pictures/wstest` 与 `Movies/wstest`）。
- 「选择文件夹」不用 Material3 `AlertDialog` 的标准按钮区：它会把内容与按钮之间撑开（文本区 24dp + 按钮区 8dp ≈ 32dp）并在按钮下方再留 48dp，列表短时看起来就是"列表和导入所选之间一大片空白"。现在改为自绘 Material 表面（`Dialog` + `Surface(shape = extraLarge, surface, tonalElevation = 6dp)`），操作行紧跟列表：列表→按钮 4dp、按钮→底边 12dp，实测（1440×3200 @600dpi，即 384×853dp，与平板一致）列表与按钮文字之间的可见空白约 26dp、对话框整体高度比 AlertDialog 版少约 46dp。
  宽度用 `LocalConfiguration.screenWidthDp` 显式算出 `min(92% 屏宽, 560dp)`；**不要**用 `fillMaxWidth(...)` 与 `widthIn(...)` 组合（两者会产生互相矛盾的约束，对话框会变成没有内容的白板——"导入所选不见了"就是这个原因）。自定义 `Dialog` 用平台默认宽度时窗口是 `WRAP_CONTENT`，内部 `fillMaxWidth()` 会在无界约束下测量失败，同样要避免。
  稳健性：外层 Column 限制 `heightIn(max = 92% 屏高)`，中间内容列与列表都用 `weight(1f, fill = false)`，内容放不下时先压缩列表，**操作行（取消/导入所选）永远不会被挤出可视区**（大字号/小屏同样成立）。自绘对话框下 `uiautomator dump` 有时抓不到其节点，验证时以截图/窗口尺寸为准。
- 设为动态壁纸的提示：`setAsLiveWallpaper` 打开系统对话框前会发出 `hintMessage`，由 `HintOverlay` 显示成**非聚焦、不可触摸的悬浮提示条**（`TYPE_APPLICATION_OVERLAY`，默认 **5 秒**，系统对话框弹出时依然可见、且不遮挡操作）。普通 Toast 只有约 2 秒，且 Android 12+ 会丢弃后台 Toast，用户往往来不及看清"要点哪个按钮"；没有「显示在其他应用上层」权限时自动回退为按 3 秒间隔重复显示的 Toast，保证总时长同为 5 秒。
  提示词按分组「应用位置」区分：**桌面**分组 →「请选择“主屏幕”（该分组只用于桌面）」；**两者**分组 →「请选择“主屏幕和锁定屏幕”」；**锁屏**分组 →「点击“设置壁纸”即可，锁屏会自动显示为该分组图片」（系统动态壁纸界面没有"锁屏"选项，确认后应用会按分组「应用位置」把图片补写到锁屏槽位，所以只需点确认）。引号内的选项名/按钮名在提示条（及回退 Toast）里自动**加粗强调**（`HintOverlay.emphasize()`，无需在文案里写标记）。
  提示条会在**离开系统动态壁纸界面时立即消失**：应用回到前台（`ON_RESUME`）时 `HintOverlay.dismiss()`；真实壁纸引擎被创建（= 已点「设为壁纸」）时也会立即消失，不必等到 5 秒到期。

### 6.4 SettingsScreen (设置)

| 分组 | 设置项 | 类型 |
|------|--------|------|
| 壁纸设置 | 切换间隔 | 对话框（10 秒~24 小时 + 自定义秒数） |
| 壁纸设置 | 切换模式 | FilterChip（随机/顺序/洗牌） |
| 壁纸设置 | 缩放模式 | FilterChip（填充/适应/拉伸） |
| 壁纸设置 | 清晰度增强 | FilterChip（自动/关闭/增强） |
| 切换方式 | 定时切换 / 解锁切换 / 双击切换 / 悬浮双击按钮 / 切换过渡动画 | Switch |
| 悬浮按钮外观 | 透明度滑块（5%~100%，200ms 防抖写库）+ 9 色板 | Slider + 色板 |
| 文件夹自动扫描 | 开关 + 间隔（1/6/12/24 小时） | Switch + 对话框 |
| 外观 | 主题颜色（16 色 + 跟随系统） | 色板对话框 |

---


#### 4.9.56 未启用分组里的图片不能再被设为壁纸

用户反馈：「当分组图片未启用时，里面的图片仍能设置为壁纸」。

原因：两条用户入口都**没有校验分组状态**——

- `WallpaperViewModel.setAsLiveWallpaper()`（点图片 → 系统动态壁纸界面）
- `WallpaperViewModel.setImageAsWallpaper()`（三点菜单 →「设为壁纸」→ 预览确认）

而引擎的目标切换曾经刻意"显式选择无视分组启用状态"（避免手动选中的图片被随机图替换），于是
用户能把已关闭分组里的图片设成壁纸；但轮换、预取、锁屏/桌面定时和 `getFirstFromEnabledGroups`
都只从**启用**分组里挑，下一次切换/重绘又把它换掉——等于设了个"注定被覆盖"的壁纸。

修法：

1. 两个入口在读取分组后立即判断 `group != null && !group.isEnabled` → 记录
   `setAsLiveWallpaper ignored: group N is disabled` / `setImageAsWallpaper ignored: ...`
   并提示「该分组未启用，请先打开分组开关」，**不移动 HOME 游标、不打开系统界面、不写任何状态**
   （守卫放在最前面，因此也不会留下 pending preview pick）。
2. 确认路径的兜底目标（预览会话已丢失 pending pick 时用 HOME 游标）新增
   `homeCursorForConfirmedPick()`：只有游标仍指向**启用且支持桌面**的分组媒体才返回，否则返回 0，
   确保"确认后推送"也不会复活已关闭分组的图片。
3. 引擎目标切换处的注释更新为"用户入口已拦截 + 内部调用自带校验"，避免后人误以为这里仍需放行。

**真机实测（Redmi 平板 25102RKBEC / 1200×2608，release 包）**：

| 操作（分组已关闭） | 日志 | 结果 |
|---|---|---|
| 点图片 | `setAsLiveWallpaper ignored: group 37 is disabled` | 系统界面不打开，壁纸不变 |
| 三点 →「设为壁纸」→ 确定 | `setImageAsWallpaper ignored: group 37 is disabled` | 无 `Wallpaper applied` / 无切换 |
| 打开分组开关后再点图片 | `setAsLiveWallpaper: id=… target=…` | 系统界面正常打开；返回取消 → `pick cancelled` + 游标复原 |

测试后已把该分组恢复为关闭；构建 + `:app:testDebugUnitTest`（130 条）全绿。

#### 4.9.57 回到桌面 / 退出软件时视频要停一下（可见性恢复被去抖）

用户反馈两件事，其实是同一个根因：

- 「设置视频为壁纸后，返回桌面要黑屏一会才开始播放」
- 「进入壁纸软件后，再退出，视频会卡一下再播放」

真机日志（Redmi 平板 25102RKBEC）里每次回到桌面都固定多出约 250ms：

```
07:03:54.910 LiveWallpaperService: App UI hidden: re-evaluating wallpaper state
07:03:55.162 VideoDecode: Video resumed (wallpaper visible again)   ← +252ms
07:03:55.202 WallpaperRenderer: Video frame rendered                ← 第一帧
```

原因：`refreshPowerSave()` 对**所有**恢复都套了 `VISIBILITY_DEBOUNCE_MS = 250ms` 去抖，本意是吸收
窗口/Activity 过渡期间成串的可见性回调（每次翻转在视频路径上都是一次解码器暂停+时钟重锚）。
但"我们自己的 App 退到后台 / 亮屏"是**确定性**转换——壁纸就在前台且可交互，去抖只会让画面白停
250ms；如果是刚被系统重建过的新引擎（还没有画过任何一帧），这 250ms 就是**纯黑屏**。

修法（`LiveWallpaperService.refreshPowerSave`）：恢复时先判断
`!appInForeground && powerSaveVisibleInput && isScreenInteractive()`（即"App 已退到后台、壁纸确实
可见、屏幕已亮"）→ 立即 `applyPowerSave(hint)`；其余情况仍走 250ms 去抖。抖动保护没有丢：随后
的 covered 报告由暂停路径处理，短暂 covered 仍会被 `VISIBILITY_PAUSE_COALESCE_MS` 合并
（日志里 `Visibility blip coalesced: …` 仍然生效）。

**实测（release 包，同一台平板）**：

| | 修改前 | 修改后 |
|---|---|---|
| `App UI hidden` → `Power save OFF` | +252ms | **+1ms**（两轮复测：+1ms、0ms） |
| 每轮进出 App 的暂停/恢复次数 | 1/1 | 1/1（无来回抖动，无 `blip` 误报） |

按 07:03:55 那次的实测数字推算：新引擎 + 已初始化解码器的情况下，返回桌面到第一帧由约 290ms
降到约 40ms（去抖残差 + 唤醒），"黑屏一会"与"卡一下"随之消失。

#### 4.9.58 返回桌面时视频停顿 1 秒：app-foreground 标志挂在 onStop 上

用户反馈（承接 §4.9.41）：「设置视频为壁纸后，返回桌面要黑屏一会才开始播放」「进入壁纸软件后，
再退出，视频会卡一下再播放」。

实测把 HOME 按键和日志时间对齐后，问题非常具体（Redmi 平板 25102RKBEC）：

```
HOME 按下                07:21:51.931
App UI hidden           07:21:53.035   ← 1104ms 之后
Video resumed           07:21:53.037
```

原因：引擎的"我们自己的 UI 在前台"标志由 `MainActivity.onStop()` 翻转，而 MIUI 要等**退出动画
走完**才回调 onStop（实测 +1104ms）。这段时间里 launcher 已经在前面、壁纸已经可见，但解码仍被
`app-foreground` 判为暂停，所以画面停住（新引擎还没画过一帧时就是黑屏）。壁纸自身的
`onVisibilityChanged` 在这台 ROM 上要晚 1.5-2.5s（这正是当初引入该标志的原因），所以只能换触发点。

修法：把 `setAppForeground(false)` 与 `WallpaperSwitchService.poke()` 从 `onStop()` 移到
`MainActivity.onPause()`——onPause 与窗口切换同拍触发（实测 +84~127ms）；`onStop()` 只保留缩略图
缓存回收（本来就有 60s 延迟）。引擎侧无需改动：§4.9.41 的"立即恢复"判断
(`!appInForeground && 可见 && 屏幕亮`) 现在能在正确的时刻生效。

**实测（release 包）**：

| | 修改前 | 修改后 |
|---|---|---|
| HOME → `onPause` | —（挂在 onStop） | **+84ms / +127ms** |
| HOME → 引擎恢复 | +1104ms | **+128ms** |
| HOME → 首帧 | ~+1123ms | **+158ms** |
| 两轮进出 App 的暂停/恢复次数 | 1/1 | 1/1（无抖动、无 blip 误报） |

注意（既有策略的延伸）：引擎对"回到桌面"采用**乐观恢复**——先恢复，等系统可见性回调到达再纠正，
所以"从我们 App 里打开别的应用"这种情况会比以前早约 1s 恢复解码/声音（原来是在 onStop 时恢复，
同样存在这个窗口，只是更晚）。这是"少 1 秒静音/黑屏" 与"多 1 秒后台解码"之间的取舍，与
`applyAppForeground` 里已记录的乐观恢复注释一致。

#### 4.9.59 为什么"进出壁纸软件"比进出别的应用更容易看到视频卡顿

用户问："壁纸软件进出视频就是比其他软件进出会卡顿"。同一台平板、同一段视频，用同一套
`am start` / `KEYCODE_HOME` 流程对照测量（Redmi 平板 25102RKBEC）：

| | 进入（视频暂停） | 退出（视频恢复） |
|---|---|---|
| 我们 App | **+105ms**（app-foreground 输入立即生效） | **+44~130ms**（onPause / 可见性回调） |
| 系统设置 | +649ms 收到"被覆盖"，+1253ms 真正暂停 | +126ms（可见性回调） |

差异来自两件**只有我们自己的 App 才会发生**的事：

1. **进入我们 App 时，暂停比系统回调早约 1.1s**。引擎把"我们自己的 UI 在前台"
   （`appInForeground`，由 `MainActivity.onStart/onPause` 维护）当作最快的暂停输入——这是为了
   「打开应用时声音立刻关闭」。代价是：**MIUI 的开启动画还没结束、壁纸仍然可见的时候，视频就冻住了**。
   别的应用不会有这个输入，暂停要等系统"壁纸被覆盖"回调（+0.6~1.3s），等它到达时壁纸早已被完全
   遮住，所以用户看不到那一帧的停顿。
2. **退出我们 App 时，引擎和动画在同一个进程里抢资源**。恢复由 onPause/可见性回调触发
   （+44~130ms），而此刻**我们自己 Activity 的关闭动画还在跑**——解码器、GL 上传、音频轨道重建
   都要和这个动画争 CPU/GPU，所以视频头几帧不均匀。别的应用退出时它的动画不牵扯我们的进程，
   引擎恢复时 GPU 是空的。

改动（退出侧）：新增 `APP_EXIT_RESUME_GRACE_MS = 250ms`——`appLeftAtMs` 记录 UI 离开的时刻，
`refreshPowerSave()` 里的恢复（无论先到的是 app-left 还是可见性回调）都不早于该时刻 +250ms，
让关闭动画先跑完。实测 HOME → 恢复由 +44ms 变为 **+350ms（= onPause + 252ms）**，动画期间不再有
解码竞争；两轮复测仍是各一次暂停/恢复。

进入侧**暂未改动**：要让画面在开启动画期间继续播放，必须把"立即静音"与"暂停解码"拆开
（立即 `audioSession.pause()`，解码延后到被覆盖），并在恢复时把音频**重新对齐到视频当前位置**
——否则音频会落后约 0.5s（音频线程在静音期间仍会写入并阻塞，恢复后从缓冲开头继续）。这需要动
音频管线，风险高于收益，先记录方案待确认。

#### 4.9.60 进入我们 App 时不再冻结画面（立即静音 + 延后暂停 + 音频重新对齐）

承接 §4.9.43 的对照结论：进入我们 App 时暂停由 `app-foreground` **立即**触发（实测 +105ms），而
系统"壁纸被覆盖"回调要 +0.6~1.3s——于是视频是在**开启动画仍在进行、壁纸仍然可见**的时候冻住的；
别的应用没有这个输入，等回调到达时壁纸早已被遮住，所以看不到那一顿。用户选择方案 A：进我们 App
时**画面继续播、声音立刻静音**。

实现（三处）：

1. **只静音、不停画面**：新增 `WallpaperRenderer.muteAudioKeepingVideo()`——立刻 `stopAudio()`
   （音频线程停、`AudioTrack.pause()` 立即无声），但**不动** `powerSaveMode`，解码与渲染继续。
   引擎侧 `applyAppForeground(true)` 改为此调用 + **延后** `refreshPowerSave()` 到
   `APP_ENTRY_PAUSE_GRACE_MS = 600ms`（若系统"被覆盖"回调更早到达，则按回调立即暂停，符合实际遮挡）。
2. **恢复时把音频重新对齐到画面**：新增 `unmuteAudioReanchored()`，用
   `startAudio(uri, gen, lastVideoPositionUs)`（与"视频声音开关"同一条 re-anchor 路径）。静音期间
   画面一直在走，若让音频从原处继续就会落后整个静音时长。
3. **顺序修正（实测发现）**：解除静音必须发生在 `powerSaveMode = false` **之后**——写在前面时
   `unmuteAudioReanchored()` 会因为 `powerSaveMode` 仍为 true 而提前返回，声音再也回不来；同时把
   "状态没有变化但需要解除静音"（快速进出 App，从未真正暂停）也覆盖。`startVideo()`/`release()`
   清掉该状态，避免跨视频泄漏。

**实测（release 包，两轮复测一致）**：

| 阶段 | 改前 | 改后 |
|---|---|---|
| 进入 App · 静音 | +105ms（同时冻结画面） | **+155ms 静音，画面继续播** |
| 进入 App · 画面暂停 | +105ms（动画中，可见） | **+535ms（系统"被覆盖"回调后，不可见）** |
| 退出 App · 画面恢复 | +44~130ms（与关闭动画抢资源） | **+251ms（关闭动画结束后）** |
| 退出 App · 声音 | 随画面一起恢复（可能落后） | **`Audio unmuted, re-anchored at Nms` 接回当前画面** |
| 每轮音频线程 | — | 1 次结束 + 1 次启动，无泄漏/无重复线程 |


#### 6.4 悬浮按钮在"别的应用"里晚 0.6~0.8s 消失（决定：维持现状）

现象（用户报告）：进我们自己的 App 时悬浮按钮瞬间消失，进别的应用却要过一会。

实测（Redmi 平板 25102RKBEC，`am start -a android.settings.SETTINGS`）：发起 → **+615ms** 收到系统
"壁纸被覆盖"回调 → **+777ms** 按钮收起。我们自己的 App 是 +0ms，因为它走 `MainActivity` 生命周期
（`setAppForeground(true)` 立即 `hideFloatingButtonNow()`）。

原因：悬浮按钮是 `TYPE_APPLICATION_OVERLAY`，永远画在所有应用之上，**必须我们主动收**；而"另一个
应用到了前台"这件事，系统只在壁纸窗口真正被完全遮住时才回调我们（就是那 0.6~0.8s，也正是视频/
音频暂停的时刻）。Android 10+ 把 `getRunningTasks()` / `getRunningAppProcesses()` 限制为"只能看到
自己"，所以没有免权限的即时信号。

备选与结论：

- **B. 申请「使用情况访问」+ 轮询前台应用**：可做到瞬间收起，但需要一个特殊权限 + 轮询耗电，
  且 MIUI 可能限制查询频率 → 用户未选。
- **C. 桌面闲置后自动淡出**：曾实现并真机验证（5s 闲置稳定淡出），但实测**这台启动器不把桌面触摸
  转发给壁纸**（三个位置点按均无 `Touch DOWN` 日志），"完全隐藏后靠触摸桌面唤回"不成立；改成
  "淡成 8% 幽灵态"虽然可用，但已偏离用户要的行为 → **用户最终选择 A：维持现状**，代码已完整回退。
- **A（当前行为）**：按钮在桌面上始终显示；进我们 App 立即消失；进别的应用晚 0.6~0.8s 消失。
  这段时间它悬在新应用画面上——已知且接受。

#### 6.5 主题：浅色/深色模式、Monet、更多配色

三项一起做（用户需求）：

1. **浅色/深色模式**：新增设置项 `theme_mode`（`system` / `light` / `dark`，缺失或未知一律按 `system`）。
   `WallpaperViewModel.themeMode` 走和主题色同一条 `settingsUiState` 通路；`MainActivity` 把它翻译成
   `ThemeMode.from(...).isDark()` 传给 `WallpaperSwitcherTheme(darkTheme = …)`，所以切换即时生效
   （不需要重建 Activity）。设置界面在「外观」区块顶部加了「主题模式」三个 `FilterChip`。
2. **Monet（Android 12+ 跟随壁纸取色）**：`Theme.kt` 原本就有一条 `Build.VERSION.SDK_INT >= S →
   dynamicLight/DarkColorScheme(context)` 的分支，但只有"主题颜色"为空时才会走到，界面上写着"跟随系统"
   ——用户看不出它其实是 Monet。现在：设置行在该分支生效时显示 **「跟随系统（Monet）」**，颜色对话框的
   第一项也标成 **「跟随系统 Monet」**（旧版本显示"跟随系统"，走内置配色）；自定义颜色仍然优先于 Monet。
3. **更多配色**：颜色选择器换成 `ColorGridPicker`——**12 个色相 × 6 档色调**（0.95 近白 → 0.20 近黑）
   的网格（`engine/ColorPickerGrid.kt`，纯数学、有单测），另有「跟随系统 Monet」一行；网格本身
   `verticalScroll`，小屏可滚动查看，并且只有点「保存」才生效（取消真的取消）。

**真机验证（Redmi 平板 25102RKBEC）**：

| 操作 | 结果 |
|---|---|
| 主题模式 = 浅色 | 截图平均亮度 **179.7** |
| 主题模式 = 深色 | **62.8** |
| 主题模式 = 跟随系统 | 随系统深色 → 53.9（设置项回到默认，行为与改动前一致） |
| 打开颜色对话框 | 色相×色调网格 + 「跟随系统 Monet」可见、可滚动；设置行副标题显示「跟随系统（Monet）」 |

#### 6.6 主题色只影响"强调色"，不再染指中性文字与图形

用户报告：「软件界面有些文字和图形会随主题颜色变化，影响观感」。

原因：`customLight/DarkColorScheme()` 除了强调角色之外，还把 **`onSurfaceVariant`** 与
`surfaceVariant` 设成了主题色。本 App 大量次要文字、卡片副标题、设置项图标都是
`tint = MaterialTheme.colorScheme.onSurfaceVariant`（全仓库上百处），所以一旦选自定义颜色，
界面上一大片文字与图形跟着变色——这正是用户看到的现象。

修法（`ui/theme/Theme.kt`）：

- 新增 `accentScheme(base, dark, accent)`：**只覆盖强调角色**——`primary`/`onPrimary`、
  `primaryContainer`/`onPrimaryContainer`、`secondaryContainer`/`onSecondaryContainer`、
  `inversePrimary`、`surfaceTint`；其余角色（`onSurface`、**`onSurfaceVariant`**、`surface`、
  `background`、`surfaceVariant`、`outline`、`error`…）**逐位沿用内置 `LightColorScheme` /
  `DarkColorScheme`**。
- 深浅两套由同一个 hex 向白/黑混合得到（迷你色调板）：深色模式把深色号提亮到约 M3 tone 80，
  容器用 tone 90/30、其上的文字用 tone 10/90 —— 修掉了旧实现"深色容器上放原始深色 hex"几乎没有
  对比度的问题（旧的 `readableAccent` 只能补救文字，救不了容器）。
- `LocalAccentColor`（少数**故意**用强调色的文字，如批量选择栏的「已选 N/M」）改为提供
  `readableAccent(选中色, 表面色)`，不再借用 `onSurfaceVariant`：可读性保住了，"哪些文字用强调色"
  也变成显式选择，而不是顺带把全局次要文字染色。

测试：重写 `CustomColorSchemeTest`（现共 162 条单测）——对 9 个极端色号（淡黄、纯白、浅灰、默认紫、
默认蓝、纯黑、近黑、高饱和绿、品红）断言：

1. `onSurfaceVariant` / `surfaceVariant` / `onSurface` / `surface` / `outline` / `error` 与内置方案
   **逐位相等**（这就是"文字和图形不再随主题色变化"的回归测试）；
2. 强调色自身的对比度：`onPrimary` vs `primary`（≥AA_LARGE）、`onPrimaryContainer` vs
   `primaryContainer`、`onSecondaryContainer` vs `secondaryContainer`（≥AA_NORMAL，深浅两套都测）；
3. `readableAccent()` 的结果在表面色上 ≥AA_NORMAL。

真机验证（Redmi 25102RKBEC）：把主题色设成自定义色后，首页副标题（`onSurfaceVariant`）实测色度
**chroma ≈ 9**（中性；默认 Monet 下为 8.3，两者一致），不再被主题色染色；测完已把主题色恢复为
「跟随系统（Monet）」。

#### 6.7 悬浮按钮"按钮颜色 → 自定义 → 保存"后对话框不关闭

用户报告：「悬浮按钮选择自定义颜色保存后界面不退出」。

原因：`ColorGridPickerDialog`（主题色与按钮颜色共用的色相×色调选择器）的「保存」只回调
`onConfirm` / `onConfirmAlpha`，**自己从不关闭**，把关闭交给每个调用方：

```kotlin
// 主题色：调用方顺手关了 → 看起来正常
ThemeColorPickerDialog(onSelect = { viewModel.setThemeColor(it); showColorDialog = false })
// 按钮颜色：调用方只写设置、没关 → 对话框一直停在屏幕上
ColorGridPickerDialog(onConfirm = { viewModel.setFloatingButtonColor(it) },
                      onDismiss = { showButtonColorDialog = false })
```

修法：让「保存」= 应用 **且** 关闭——在 `ColorGridPicker.kt` 的 confirm 分支里，回调之后追加
`onDismiss()`。已经在自己回调里关闭的调用方（主题色）不受影响（同一个标志位再置一次是空操作），
以后新增调用方也不会再踩这个坑。取消仍然只走 `onDismiss`，不写任何设置。

真机验证（Redmi 25102RKBEC）：悬浮按钮外观 → 按钮颜色 → **自定义** → 保存 → 对话框中只剩设置页本身
（`按钮颜色` 行仍在、对话框标题与「保存/取消」都已消失）；保存写入的值与打开前一致（本次只点保存
未改色，所以按钮颜色没有被改动）。

#### 6.8 语言切换（多语言）——7 种语言，全界面已本地化

目标：界面可切换语言，支持多国语言。

**机制（已完成）**

- 设置项 `app_locale`：`"system"`（跟随手机）或某个语言标签（如 `"en"`）。
  `WallpaperViewModel.locale` 走和主题色/主题模式同一条 Room flow。
- `ui/AppLocale.kt`：语言按 Android 的标准方式应用 —— 在
  `MainActivity.attachBaseContext()` 里用选定 locale 包一层 Context，然后 `recreate()` 让新语言
  生效（不需要 AppCompat，也不需要重建整个进程）。
  **注意（踩过的坑）**：最初是用 `CompositionLocalProvider` 覆盖 `LocalContext`/`LocalConfiguration`
  实现的（"即时切换、不重建"），结果**打开设置页必崩**：没有原始 Activity Context，
  `rememberLauncherForActivityResult` 解析不到 `ActivityResultRegistryOwner`，
  抛 `IllegalStateException: No ActivityResultRegistryOwner was provided via
  LocalActivityResultRegistryOwner`（真机 dropbox 里 10 条崩溃记录，最早一条正好是那次安装之后）。
  改成 `attachBaseContext` 后不再有副作用，而且对"非 Compose 环境"（通知文案、提示条等）同样有效。
  设置值另外镜像一份到 SharedPreferences：`attachBaseContext` 早于 Room 可用，语言标签必须在
  数据库打开之前就能读到；点击语言时先**同步写镜像**（`commit()`）再 `recreate()`，否则新
  Activity 会读到旧值（实测过这个竞态）。
- `"system"` 时优先跟随手机；“多语言系统设置”里如果给本 App 单独设过语言（Android 13+ 的
  「应用语言」页），也会被采纳（`LocaleManager.getApplicationLocales()`，API 33+，
  因为我们没用 AppCompatDelegate，否则这个值会被忽略）。
- `res/xml/locales_config.xml` + manifest 的 `android:localeConfig`：让 Android 13+ 的
  「应用语言」页列出我们支持的语言；App 内的选择器用同一份列表
  （`SettingsKeys.TRANSLATED_LOCALES`），两处不会打架。
- **选择器只列出真正带翻译的语言**（`values-<tag>/strings.xml` 存在）。列一个没有翻译的 tag 会
  静默回落到默认（中文），用户会以为切换坏了。语言名用**该语言自己的写法**（`简体中文` / `English`），
  选错语言的人也能认出来。

**文案抽取（已完成）**

原本 319 条界面文案硬编码在 Kotlin 里，资源文件只有 7 条。现在**整屏文案全部走资源**，
7 个语言文件各 **268 条 string + 4 条 plurals = 272 个键，键集完全一致**：

`values/`（简体中文，默认）、`values-zh-rTW/`、`values-en/`、`values-ja/`、`values-ko/`、
`values-es/`、`values-ru/`。

抽取覆盖：底部导航与顶栏、设置页整屏、**首页**（服务状态卡三种状态与提示、引擎未运行警告、
「没有分组应用桌面」警告、分组列表标题与计数、多选工具栏、删除分组对话框、新建分组对话框、
空态）、**分组详情**（分组信息头与「应用位置」chips、操作栏、批量选择工具栏、清理失效对话框、
删除/重命名对话框、添加壁纸对话框、扫描到的文件夹对话框全部文案与三种排序、壁纸预览对话框、
空态）、**颜色选择器**（透明度/保存/取消/跟随系统 Monet）、**Toast 与悬浮提示**（ViewModel 里
22 处 emit 全部改为 `str(R.string.…)`）、**时长与相对时间**（`formatInterval`/`formatAgo`）。

抽取过程中顺带修掉的"资源化后必然踩到"的坑：

- 悬浮按钮颜色预设表 `List<Pair<String,String>>` → `List<Pair<String,Int>>`（`stringResource`
  不能在顶层 val 里调用），分享日志的纯函数改用 `context.getString(...)`；
- `ThemeMode` / `WallpaperTarget` 的标签改为 `@StringRes`（分组卡片的「两者」chip 与
  「已设为桌面壁纸！」Toast 都从这里取）；
- `formatInterval` / `formatAgo` 拆成「纯函数算数量与单位」+「Composable 查资源」两层：
  原实现把中文字面量写在纯函数里（不可本地化），现在仍可单测边界；
- **数量用 `<plurals>`**：写成 `<string>` 会在英语/西班牙语/俄语出现 `1 groups` / `1 группа`…
  中文/日文/韩文只给 `other`，英语/西语给 `one`+`other`，俄语给 `one/few/many/other`。

**新增守卫测试 `LocaleResourcesTest`（3 例）**：语言选择器只列出
`SettingsKeys.TRANSLATED_LOCALES`，而 Android 对缺失的键会**静默回落中文**——看起来就是
「切换坏了」。所以测试直接比对资源文件：默认文件无重复键、每个可切换语言与默认文件的键集
完全相同（多一个少一个都失败）、没有"有翻译却没人能选"的文件夹。`app/src/test` 仍在
168 → **171** 例，全绿。

**模拟器验证（AOSP 14，2560×1600 平板）**

```
# 系统「应用语言」（模拟外部切语言）
adb shell cmd locale set-app-locales com.wallpaperswitcher --locales {zh-TW,ja,ko,es,ru}
→ 冷启动界面逐语言核对：首页（状态卡/分组列表/空态）、设置页整屏、分组详情、
  添加壁纸对话框、文件夹选择器（含三种排序与「导入所选 (n)」）、批量工具栏、
  颜色选择器（「透明度 10%」/「Отмена」/「Сохранить」）                          ✓

# App 内选择器（选择器列出 跟随系统 + 7 种语言，名称用各自语言写法）
→ English → 日本語 → Español → Русский 依次切换，界面即时变、语言行回显所选语言   ✓
→ 再选「跟随系统」+ 清空系统应用语言 → 回到手机语言                                ✓

# 回归：切语言后设置页可正常打开（ActivityResultRegistry 崩溃已不复现）、
#       分组数据保留、服务开关与「立即切换」正常（日志 "Wallpaper applied (home)"）
```

实测：`1 group`（英文单数）、`3 файла · Оба`（俄语 few）等数量文案均正确。

**已知取舍**：悬浮按钮默认文字仍是 `切`（`SettingsKeys.FLOATING_BUTTON_TEXT_DEFAULT`）。
它是按钮的固定标识，且该值参与"用户是否清空过输入框"的判断，改成随语言变化会牵动
引擎侧渲染与设置语义，故保持不变。

#### 6.9 语言切换的两个后续修复（提示词语言 / 设置页排版）

用户反馈三条：①系统壁纸界面的提示词没换语言；②「未启用分组」的提示没换语言；
③换语言后设置页排版出问题。前两条同一根因，第三条是布局问题。

**① ② 只在 Compose 之外读字符串的地方**（ViewModel 的 Toast / 浮动提示气泡、前台服务的通知）

这些文案不在 composition 里，走的是 `getApplication().getString(...)`。语言只应用在
`MainActivity.attachBaseContext`，**Application 的上下文永远跟随系统语言**，于是提示词一律
回落默认（中文）。实测证据（真机运行日志）：

```
10-01 10:46:23 HintOverlay: Hint shown for 5000ms: “홈 화면 및 잠금 화면”을 선택하세요
```

界面已经是俄语，提示词还是上一次启动时的韩语。修复分三层：

- `AppLocale.localized(context)`：给 ViewModel / 服务用的取词入口，按**当前**存储的 tag
  （每次重新读，换语言立刻生效）返回一个包好 locale 的 Context，并按 tag 缓存，Toast 不会
  每次新建 Context；
- `WallpaperSwitcherApp.attachBaseContext()` 也包一层 locale，让进程启动阶段就正确的还有
  通知渠道名/描述；
- **`systemBase`**：`localized()` 在「跟随系统」时必须用**未包装**的 base 来解析。`attachBaseContext`
  里的包装会跟随整个进程生命周期，直接用 Application 会卡在「启动时的语言」——上面那条日志
  就是这么来的。所以 `attachBaseContext` 先把原始 base 交给 `AppLocale.rememberSystemBase()`
  存起来，「跟随系统」时用它（它带系统的/系统的按应用语言）。

前台服务通知同样改为 `AppLocale.localized(this)` 取词。

**③ 设置页：标签被挤成 0 宽 → 每行一个字 → 近 900px 空行**

`Row { Icon; Spacer; Text(weight(1f)); Row(chips) }` 里，标签量到的是"chip 之后剩下的宽度"。
俄语/西语/英语的 chip 文字很长，chip 占满整行后标签宽度为 0，而 **0 宽的 Text 会按"一行一个
字符"排版**：标签既看不见，行高还被撑到 12 行。真机测得（1200×2608，俄语）：

| | 中文 | 俄语（修复前） |
|---|---|---|
| 「切换模式」行 | y=531 | y=804（标签不渲染） |
| 「缩放模式」行 | y=750 | y=1825（与上一行相差 1025px） |

像素统计也确认 973→1825 整条带没有任何墨迹（纯空白）。修复：抽出
`SettingsChoiceRow()`，用 `FlowRow` 排「标签 + 选项」：放得下就是一行（标签左、chip 右，
中文/韩文/日文观感与之前完全一致），放不下 chip 自动换到第二行，标签用
`maxLines = 1 + Ellipsis` 保证永远不会变成竖排单字。应用位置：切换模式、缩放模式、
清晰度增强、旋转方向、主题模式五行。

实测（同一台平板，设置页首屏行距）：

| 语言 | 切换模式行 | 缩放模式行 | 观感 |
|---|---|---|---|
| 简体中文 | 501 / chips 541 | 720 / chips 760 | 一行（与修复前一致） |
| 한국어 | 501 / 541 | 720 / 760 | 一行 |
| 日本語 | 501 / 541 | 720 / chips 850 | 显示模式换行，标签可见 |
| English | 501 / chips 631 | 810 / chips 850 | 两行 |
| Español | 501 / 631 | 810 / 940 | 两行 |
| Русский | 501 / 631 | 810 / 940 | 两行 |

**回归验证（真机 25102RKBEC + 模拟器）**

- 提示词：俄语下点击分组图片 → 日志 `Hint shown for 5000ms: Выберите «Главный экран и
  экран блокировки»` ✓（修复前是韩语）
- 未启用分组的 Toast：同一句提示词，俄语弹窗窗口 **960×212**（两行），中文 **852×164**
  （一行）—— 长度与换行都符合两种语言的文本 ✓
- 7 种语言设置页逐屏 dump：标签全部可见、无异常空行；中文行距与修复前逐像素一致（无回归）✓
- `assembleRelease` + 171 条单测通过；真机安装后进程启动、通知、分组数据（12 组）均正常 ✓

#### 6.10 全语言排版审查（按钮被截断 / 计数被挤没）

在 1200×2608 的平板（**400dp 宽**，和手机同量级）上，把 7 种语言的
首页 / 设置页（6 屏）/ 分组详情 / 加壁纸弹窗 / 文件夹选择器 / 时长弹窗 / 颜色弹窗
逐个 dump（uiautomator 的 text+bounds）并配合截图像素分析，找出三处同类问题：
**一行里放多个控件时，把"会伸缩的那个"挤到 0 宽或截断**。

| 位置 | 表现（真机实测） | 修复 |
|---|---|---|
| 分组详情操作栏（添加壁纸/批量操作/清理失效） | 俄语三个按钮文字节点宽度都=228（可用宽上限），墨迹在 314 处断开、322-352 是三颗省略号点 → 实际显示 `Добавить об…`；英语 `Add wallpap…` / `Clean brok…`；韩语 `배경 화면 추가` 同理 | 标签 `maxLines = 2`（**不截断**，只换行）。中文/日文仍是一行 60px 高；英/西/俄/韩 120px 两行 |
| 分组详情 / 首页的批量选择工具栏 | 俄语 `Выбрано 303/303` 节点只有 **58px**（显示成"…"）；首页那条更严重——计数**整个消失**（权重被按钮吃掉） | 改用 `FlowRow`：每个控件保留自身宽度，按钮放不下就换第二行。中文仍是单行（`取消全选 / 已选 303/303 / 删除所选` 同一行，与之前一致）；俄语第二行放 `Удалить выбранное` |
| 设置 → 运行日志两个按钮 | 俄语 `Очистить журнал` 在按钮内被压成 **134×240** 的单列（每行一个字），整行被撑到 288px 高 | 两个按钮改 `FlowRow`，各自占一行、标签不再换行（`Экспорт и отправка` / `Очистить журнал` 各 60px 高） |

同时确认**没有问题**的地方：设置页 5 组"标签+chip"（6.9 已修）、分组详情头部的应用位置
chip、加壁纸弹窗四个选项、文件夹选择器的搜索框/排序 chip/列表行、时长弹窗的 radio 列表、
颜色选择器的文字与滑块、首页分组卡片的名称与计数（`9 файлов` / `303 файла` / `12395 файлов`
俄语复数正确）。

验证方式：7 种语言各 dump 首页 + 设置页 + 分组详情 + 批量工具栏，
用「宽度 < 220 且高度 > 100」筛"竖排单字"型异常节点 —— **全部为 0 条**；
关键行逐条比对节点宽度与可用宽度，确认不再出现 `w == 上限`（=被截断）的情况。
中文/繁体/日文/韩文的行高与修复前逐项一致，没有为长语言牺牲紧凑排版。

#### 6.11 设置页列对齐（"排版不整齐"）

6.9 把设置行的"标签 + 选项"改成 FlowRow 后修掉了竖排单字，但顺手破坏了对齐：标签用了
`weight(1f, fill = false)`，只占自己的固有宽度，于是 **chip 跟着标签长度左右漂**
（实测中文：3 个字的「切换模式」chip 从 x=486 开始，5 个字的「清晰度增强」从 x=535 开始，
「旋转方向」从 x=340 开始 —— 同一页三组选项三个起始位置）。真机逐行量出来的其余三处：

| 问题 | 实测 | 修复 |
|---|---|---|
| chip 起始位置随标签长度变化 | 486 / 535 / 340 三种 | 标签改回 `weight(1f)`（填满剩余空间）→ 所有 chip 组右端对齐到同一条边（1056）。中文与改造前逐像素一致，俄语仍是"标签一行 + chip 第二行" |
| 「旋转方向」标签比其他行左移 120px | x=96，其余行 x=216 | 无图标行补 40dp（24 图标 + 16 间距）占位，标签回到 x=216 |
| 「扫描间隔」行没有「修改」，另两行有 | 该行右端空着 | 补上 `action_modify`，右侧动作列（跟随系统 / 修改 / 修改 / 82%）统一贴到 x=1104 |

重构后实测（中文）：`切换模式 / 缩放模式 / 清晰度增强` 三行的 chip 都在 x=558 / 764 / 970，
「旋转方向」两个 chip 在 680 / 928 且右端同为 1056 —— 四行选项对齐在两条竖直线上；
标签全部在 x=216。俄语/西语：标签 x=216，chip 换行后统一从 x=144 起排；
日语/韩语/中文：单行，chip 右端对齐。

#### 6.12 设置行改成"标题一行、选项一行"

6.10/6.11 之后仍有两个问题，用户反馈"有些文字显示不全，标题与选项应该换行显示"：

1. **chip 里的文字被裁掉**：`切换模式` 那组三个 chip 原本是被调用方包在一个普通 `Row` 里交给
   FlowRow 的，FlowRow 只看到一个整体，放不下时就压缩最后一个 chip 而不是让它换行 ——
   Material3 的 chip 高度是固定的，标签在 chip 内换到第二行就画到胶囊外面（俄语实测
   `Перемешивание` 文字节点 109px 高，同排另外两个 chip 是 49px）。中文/日/韩/英/西/俄里
   只有俄语触发，但根因对所有语言成立。
2. **标题和选项在同一行时两边都不宽裕**：长语言里标题被压、选项被压，观感也不整齐。

改法（用户要求）：`SettingsChoiceRow` 变成两行 ——
第一行 `图标 + 标题`（占满整行，`maxLines = 2` + 省略号兜底），第二行是选项 chip。
同时把五个调用点里的 `Row { chips }` 去掉，chip 直接作为 FlowRow 的项，这样**选项放不下时是
chip 之间换行，而不是 chip 内部换行**，标签永远是单行完整显示。

窄屏（1200×2608 @480dpi = 400dp，模拟器改成与平板同参数）7 种语言实测：

| | 标题 | 选项 |
|---|---|---|
| 中文/繁中 | `切换模式` x=216 h=70（单行） | 随机/顺序/洗牌 都在 y=577、h=61 ✓ |
| 日本語 | `切り替えモード` h=70 | ランダム/順番/シャッフル 同一行 ✓ |
| 한국어 | `전환 모드` h=70 | 무작위/순차/셔플 同一行 ✓ |
| English | `Switch mode` h=57 | Random/Sequential/Shuffle 同一行 ✓ |
| Español | `Modo de cambio` h=57 | Aleatorio/En orden/Barajar 同一行 ✓ |
| Русский | `Режим смены` h=57 | Случайно/По порядку 一行，**Перемешивание 换到第二行**（h=49，单行完整）✓ |

整页（7 屏 × 3 种语言）再扫一遍"高 > 75 且窄"的节点：只有顶栏标题和俄语
`Интервал для экрана блокировки`（两行完整显示）——**没有任何被裁掉的文字**。

代价是每组多一行（中文每行约 +100px），这是用户明确要求的取舍。

#### 6.13 多选工具栏：「启用 / 删除」固定第二行

首页分组多选（以及分组内的媒体多选）工具栏原来把所有控件塞在一行：退出 / 全选 / 已选 n/m /
启用 / 删除。长语言下要么计数被压成"…"（俄语 58px），要么按钮文字被截断；即使用 FlowRow
让它按需换行，位置也随语言变化。按要求改成固定两行：

- 第一行：`✕` / 全选 / `已选 n/m`（计数 `weight(1f)` + 右对齐，这行只有三个控件，永远挤不到）
- 第二行：动作按钮（首页是「启用 / 删除」，分组内是「删除所选」），右对齐；用 FlowRow 兜底，
  万一某个语言的动作名太长就再往下换，而不是被裁

400dp 窄屏实测（4 种语言，坐标均为真机 dump）：

| 语言 | 第一行 | 第二行 |
|---|---|---|
| 简体中文 | 取消全选 x=264-435、已选 1/1 右对齐到 1152 | 启用 782-868、删除 1030-1116 |
| 日本語 | すべて解除 264-472、1/1 選択中 到 1152 | 有効化 740-868、削除 1030-1116 |
| Español | Deseleccionar todo 264-635、1/1 seleccionados 到 1152 | Activar 658-795、Eliminar 957-1116 |
| Русский | Снять выбор 264-527、Выбрано 1/1 到 1152 | Включить 582-785、Удалить 947-1116 |

计数全部完整显示（修复前俄语只有 58px、显示成"…"）。

#### 4.9.61 七个新功能：分组独立节奏 / 一键暂停 / 下一张预览 / 磁贴与小组件 / 场景规则 / 过渡动画 / 配置备份

本轮一次性补齐了用户列出的 7 项功能。总原则依旧是**默认路径逐字节不变**：每一项都先有一个
"关闭 / 跟随全局"的状态，只有用户主动打开才走新代码，所以老安装升级后行为与升级前完全一致。

**① 每个分组独立设置间隔 / 模式**

- 数据层：`wallpaper_groups` 增 `intervalMs`（0 = 跟随全局）与 `switchMode`（"" = 跟随全局），
  新增 `group_schedule(groupId, slot, lastSwitchAt, lastMediaId)` 记录每个分组在各屏幕的上次出图时间
  与该分组自己的游标；`shuffle_shown` 主键扩为 `(slot, groupId, mediaId)`，让每个分组的洗牌牌堆
  互不消耗。三者与 `MIGRATION_6_7` 同时落地（v7 尚未发布，改动直接折进同一个迁移）。
- 调度：`engine/GroupPacing.kt` 是纯函数调度器——只要**存在**一个自己带间隔的分组，服务就切换成
  "每个分组按各自 `lastSwitchAt + 间隔` 到期，谁先到期谁切"；没有这种分组时仍走原来的屏幕级
  anchor + 间隔（`nextScreenTick` 里 `groups.none { it.intervalMs > 0 }` 的早退分支）。
  媒体为空、或不在"时间规则"窗口内的分组不参与竞选（不会占着调度空转）。
- 出图：定时器广播时带上 `EXTRA_GROUP_ID`，动态壁纸引擎用 `GroupPick`（`GroupPickDao` 的分组内查询）
  只在该分组内取图，用分组自己的模式与游标；静态路径（锁屏 / 无引擎时的桌面）由
  `WallpaperApplier.applyNextOutcome(..., groupId)` 走同一套 `GroupPick`。
- 熄屏语义保持一致：亮屏时 `group_schedule.reanchorAll(now)` 把**已有记录**的分组重新计时
  （从未出过图的分组保持"立即到期"，不会被推迟一整个间隔）。
- UI：分组详情头部新增"间隔 / 模式 / 时段"三个 chip（`GroupRhythmSection`），每项都带"跟随全局"。

**② 一键暂停（稍后切换）**

- 新设置 `pause_until`（wall-clock ms，0 = 正常）。两个定时循环在取图之前检查：未到期就
  `delay(min(剩余, 30s))` 后重新检查，**不消耗 tick**——所以在暂停期间到点的切换会在恢复瞬间补切一次，
  即使这段时间应用被关掉。解锁切换（`ScreenUnlockReceiver`）同样被暂停拦住；手动按钮 / 悬浮按钮 /
  磁贴的"切换"仍然可用（那是明确的手动动作）。
- 入口：首页服务卡片上"暂停 / 立即继续"按钮（15 分钟 / 30 分钟 / 1 小时 / 2 小时 / 到明天 8 点）、
  快速设置磁贴、桌面小组件（暂停按 24 小时，再点一次立即恢复）。暂停中卡片直接显示"已暂停到 HH:mm"。

**③ 下一张预览**

`engine/NextPreview.nextHome()` 用与真实切换**同一套判定**（跟随全局时用屏幕级模式与游标；存在独立分组时
先问 `GroupPacing` 下一个该轮到谁）算出下一张媒体，但**只读**：不写游标、不写洗牌牌堆、不写壁纸。
首页"预览下一张"弹出对话框，用 Coil（视频走 `VideoFrameDecoder`）显示缩略图，可直接"设为壁纸"。

**④ 快速设置磁贴 + 桌面小组件**

- `tile/SwitchWallpaperTileService`：单击 = 立即切换壁纸（与首页按钮同一入口）。
- `tile/PauseWallpaperTileService`：单击 = 暂停一天 / 立即恢复，磁贴标题与激活态跟随 `pause_until` 实时刷新。
- `widget/WallpaperWidgetProvider` + `widget_wallpaper.xml`：两个按钮（切换 / 暂停），走显式
  `PendingIntent` 广播；provider 非导出，系统仍可投递 APPWIDGET_UPDATE。

**⑤ 时间 / 场景规则**

- 时间规则（分组级）：`activeFromMinute` / `activeToMinute`（分钟数，-1 = 全天，支持跨午夜，
  见 `engine/GroupRules.windowContains`）。窗口内的分组才参与 `GroupPacing` 竞选；只有**所有**分组
  都跟随全局间隔时，调度才沿用旧的屏幕级路径，因此老用户的行为不变。窗口打开的瞬间由
  60 秒兜底轮询（`HOME_IDLE_RECHECK_MS`）或任意一次 poke 触发。
- 场景规则（全局）：`scene_pause_on_power_save`（省电模式暂停）与 `scene_pause_on_low_battery`
  （电量 ≤15% 暂停），与一键暂停共用"不消耗 tick、恢复即补切"的语义。

**⑥ 过渡动画多样化**

原来只有"黑场淡入"。现在 `switch_transition` 有四个取值：`fade`（默认，历史行为）/ `slide` / `zoom` / `none`，
旧的 `switch_fade_enabled` 仍被尊重（`none` 会把它写回 false）。滑动与缩放由
`WallpaperGeometry.applyTransition(quad, mode, progress)` 直接改四边形的 NDC 坐标（纯函数、可测），
渲染线程按和淡入相同的 8×25ms 节奏重绘：图片从纹理重绘、视频重算 quad，因此图片 / GIF / 视频
三种媒体拿到一致的动画；`progress = 1` 时与原来的 FIT/FILL/STRETCH 布局完全相同。
手动切换（悬浮按钮 / 双击 / 立即切换）依旧跳过过渡，保持"点一下立刻换"的手感。

**⑦ 配置导出 / 导入**

`engine/ConfigBackup.kt` 把分组（名称 / 应用位置 / 启用状态 / 独立间隔 / 独立模式 / 时间窗口）与
壁纸相关的全局设置写成一个 JSON 文件（SAF 保存，无需权限）。刻意**不**导出媒体文件与设备相关设置
（主题、语言、悬浮按钮外观、日志开关、任何本机 URI）；导入时只接受白名单内的设置键，分组一律
新建（媒体引用换台手机就失效，因此不做合并）。JSON 读写由本文件内的最小实现完成（不依赖
`org.json`，因为它在单元测试里是桩），转义、损坏输入、版本过新、字段越界都由 `ConfigBackupTest` 覆盖。

**新增测试**：`GroupPacingTest`（14）、`GroupRulesTest`（6）、`WallpaperGeometryTransitionTest`（6）、
`ConfigBackupTest`（9），连同原有用例一起在 `testDebugUnitTest` 全绿（合计 213 条）。

**AOSP 14 模拟器实测（release 包，2560×1600）**：

- 首页新增的"暂停 / 预览下一张"按钮渲染正常；暂停对话框选 15 分钟后，卡片显示"已暂停到 02:26"，
  服务日志 `Paused (899937ms left): home timer holding`，点"立即继续"后 `resumeNow`，
  定时切换随即补切一次（`sendSwitch(timer)`）。
- 分组详情头部三个 chip（`Interval / Switch mode / Time window`）正常；把某分组设为 30 秒独立间隔后，
  日志出现 `Switch requested: timer target=null group=1`，并在 02:23:30 / 02:24:01 按 **31 秒**
  的节奏切图，且只在这个分组内取图（`wstest1.png → wstest2.png`）。
- `group_schedule` 表已按分组写入 `(groupId, slot, lastSwitchAt, lastMediaId)`，v6→v7 迁移在真机
  数据库上成功执行；`pragma foreign_key_list(group_schedule)` 显示 `ON DELETE CASCADE`。
- 过渡动画选"滑动"后，定时切换的日志为 `Transition requested: slide`（手动切换按设计仍然跳过过渡）。
- 配置导出：SAF 保存到 Downloads，得到 695B 的 `wallpaper-switcher-config.json`（分组 + 全局设置，
  含 `intervalMs: 300000`）；导入该文件日志为 `importConfig: 2 groups`，新分组带回了间隔设置。
- 磁贴与小组件：`dumpsys package` 中两个 `TileService` 均带 `BIND_QUICK_SETTINGS_TILE` 权限注册，
  `dumpsys appwidget` 中 `WallpaperWidgetProvider` 已被系统登记。

---

#### 4.9.62 分组批量「不启用」、自定义暂停时长、手动点击也走过渡动画

三处按用户反馈的调整：

**① 分组多选增加「不启用」**

首页分组多选的第二行原本只有「启用 / 删除」，现在补上「不启用」（`onDisable` →
`setGroupsEnabled(ids, false)`，与单个分组的开关走同一条写入路径，最后只 poke 一次服务）。
视觉上「启用」是实心按钮（主操作）、「不启用」用 tonal 按钮（次要）、「删除」保持错误色，
三者用 FlowRow 排列，长语言下会整体换行而不是被压扁。

**② 一键暂停支持自定义时间**

「稍后切换」对话框在 15 分钟 / 30 分钟 / 1 小时 / 2 小时 / 到明天早上 8 点之外，新增
「自定义」输入（单位分钟，只收 ASCII 数字，`1…10080`，即最长 7 天——与 ViewModel 里
`snooze()` 的钳制范围一致，确定按钮在越界时保持禁用）。做法与「切换间隔」对话框里的
自定义秒数相同。

**③ 手动点击也播放过渡动画**

`maybeFade()` 原来对用户主动触发（悬浮按钮 / 双击 / 「立即切换壁纸」/ 确认选图）直接返回，
只有自动切换才播放过渡。现在这条分支被删除（连同只被它使用的 `currentSwitchSource` 字段，
避免留下死代码），手动触发与自动切换走同一套 `requestTransition(过渡动画设置)`
（淡入淡出 / 滑动 / 缩放 / 无）。保留的唯一例外是**快速连击**：距上次切换不足
`RAPID_SWITCH_FADE_SKIP_MS`（700ms）的后续切换仍然跳过过渡，这样连点悬浮按钮不会把动画叠起来。

**平板实测（f617007e，release 包）**

- 多选一张分组后工具栏为 `启用 / 不启用 / 删除`；点「不启用」后
  `select isEnabled from wallpaper_groups` 由 1 变 0，再点「启用」恢复为 1（用户的原始状态已还原）。
- 暂停对话框出现「自定义 / 分钟 / 可填 1–10080 分钟（最长 7 天）」；输入 5 并确定后日志
  `snooze: 300000ms`，卡片显示「已暂停到 10:49」，点「立即继续」后 `resumeNow`。
- 在桌面点悬浮按钮（浮窗位置取自 `floating_button.xml`：pos_x=1008, pos_y=2281），
  日志为 `Switch to: 0062.jpg` 紧接 `Transition requested: zoom`——手动切换已进入过渡分支
  （此前这里只会留下 "Manual switch, skipping fade"）。

---

#### 4.9.63 下一张预览与随机/洗牌一致 + 默认取消过渡动画

**① 为什么预览和实际切换不一致（并且每次点都不一样）**

RANDOM 用 `ORDER BY RANDOM()` / 随机 OFFSET，SHUFFLE 用 `Random.nextInt` 从"未出过的牌"里抽——
**每次调用都重新掷骰子**。预览调一次、真正的切换再调一次，得到的就是两张不同的图；连点两次预览
自然也不一样。这不是流程问题，而是"随机源"本身不可复现。

**② 修法：把随机改成"由挑选状态决定"的伪随机（可复现）**

`SwitchPicking.stableIndex(count, seed)` = `Random(seed).nextInt(count)`，种子由
`pickSeed(cursor, deckSize, universeSize, seq)` 组合：

- `cursor`：屏幕游标（`LAST_IMAGE_ID`）或分组自己的 `lastMediaId`；
- `deckSize` / `universeSize`：洗牌牌堆大小与候选素材数；
- `seq`：`SettingsKeys.PICK_SEQ`，**只有真正切换成功后才自增**的计数器
  （引擎在媒体真的上屏后 `incrementLong`，静态路径在写壁纸成功的事务里自增）。

于是：预览与紧随其后的切换读到**完全相同**的种子 → 同一张图；点两次预览 → 同一张图；
切换成功后 `seq` 和游标都前进 → 下一次必然换一张。

只把 `cursor` 当种子是不够的：那让"下一张"成为游标的固定函数，而 N 个点上的随机映射期望在
约 `0.6·√N` 步后进入环——20 张的分组会很快在同样 2~3 张图之间打转。`seq` 单调递增把这条
环彻底打断，`SwitchPickingTest.theAppliedSwitchCounterBreaksShortCycles` 就是守这条的。

**③ 默认取消过渡动画**

`SWITCH_TRANSITION_DEFAULT = none`：新安装（或清数据后）不再有任何过渡动画，设置里的
淡入淡出 / 滑动 / 缩放 仍然保留，用户主动选了才会播放；已经有存储值的设备沿用用户自己的选择。
同时删掉了没有任何调用方的 `WallpaperRenderer.requestFade()`（`requestTransition` 之后它就成了死代码）。

**平板实测（f617007e，release 包，模式=随机）**

| 操作 | 结果 |
|---|---|
| 连点两次「预览下一张」 | 两次都是 `0051.jpg`（修复前每次不同） |
| 立即切换壁纸 | `Switch to: 0051.jpg id=121759` —— 与预览一致 |
| 再预览一次 | `04_TinyAsa_Zenith_bunny_full_outfit_04.jpg`（已换新的一张） |
| 立即切换壁纸 | `Switch to: 04_TinyAsa_Zenith_bunny_full_outfit_04.jpg id=58750` —— 一致 |

模式切到「洗牌」后同样验证：两次预览都是 `孔雀海：kongque.org_10.jpg`，实际切换
`Shuffle pick: deck=47/45965 -> Switch to: 孔雀海：kongque.org_10.jpg id=130250`，完全一致；
验证完把模式改回用户原来的「随机」（`global_switch_mode=RANDOM`）。

过渡动画：平板上的 `switch_transition=none` / `switch_fade_enabled=false` 保持不变，
切换日志中不再出现 `Transition requested`（此前会出现 slide/zoom/fade）。

---

#### 4.9.64 过渡动画重写：帧时钟驱动 + 缓动曲线 + 不再从纯黑开始

**问题**：三个动画都由固定 25ms 的 `postDelayed` 步进（8 步 / 200ms）驱动，且首帧是"全黑"
（淡入 alpha=1、滑动从屏幕外、缩放到 0.85 留 15% 黑边）。步进没和 vsync 对齐，同一档 alpha
有时占 1 帧有时占 2 帧 → 肉眼可见的抖动；首帧纯黑 → 像闪一下。

**改法**

1. **帧时钟驱动**：`WallpaperRenderer` 用 `Choreographer.FrameCallback` 采样动画，
   每显示一帧算一次进度（60/90/120Hz 面板各自按自己的刷新率走），不再是 8 个离散档。
   切换发生时先立刻画出起始帧（`applyTransitionProgress(easeOut(0))`），再交给帧回调推进；
   动画被新的切换打断时用 `fadeGeneration` + `removeFrameCallback` 干净地替换掉。
2. **缓动曲线**：`engine/TransitionCurve.kt`（纯函数、可测）给出 `DURATION_MS = 220`、
   `easeOutCubic` 和 `fadeAlpha`。ease-out 让大部分位移发生在前段，短动画也"跟手"。
3. **不再有全黑首帧**：
   - 淡入淡出：黑场起始 alpha 由 1.0 改为 `FADE_START_ALPHA = 0.72`，媒体始终可见；
   - 滑动：位移由 2.0 NDC（整整一屏，媒体完全在屏幕外）改为
     `TRANSITION_SLIDE_TRAVEL_NDC = 0.36`（约 18% 宽度，只剩一条窄黑边）；
   - 缩放：起始比例由 0.85 改为 `TRANSITION_ZOOM_START_SCALE = 0.93`。
4. 每次过渡结束写一行诊断日志（**每次一条，不是每帧**）：
   `Transition done: zoom frames=14 duration=220ms`——帧数与耗时之比就是实际采样率，
   用户导出的日志里可以直接判断是否够顺。

**平板实测（f617007e，120Hz 面板，release 包）**

| 模式 | 日志 | 实际采样 |
|---|---|---|
| 缩放 | `Transition done: zoom frames=27 duration=220ms` | ≈123fps（贴近 120Hz） |
| 缩放（60Hz 场景） | `Transition done: zoom frames=14 duration=220ms` | ≈64fps |
| 淡入淡出 | `Transition done: fade frames=27 duration=220ms` | ≈123fps |

手动切换（桌面悬浮按钮）与自动切换走同一条帧循环；验证完把过渡动画设置恢复为验证前的「无」。

**测试**：新增 `TransitionCurveTest`（缓动端点/单调性/ease-out 特性/黑场起始值/进度钳制），
并更新 `WallpaperGeometryTransitionTest`（滑动不再从屏幕外开始、缩放黑边 < 10%、进度钳制）。

---

#### 4.9.65 快速双击不再吞掉过渡动画

**问题**：`maybeFade()` 里有一道"距上次切换不足 700ms 就跳过动画"的规则
（`RAPID_SWITCH_FADE_SKIP_MS`）。它当初是为了让连点不卡，但用户连点/双击的间隔恰好就在这个
窗口内，于是第二次之后的切换动画全部消失——"双击切换过快，过渡动画会消失"。

**改法**

- 删除这条规则及只为它服务的 `wasRapidSwitch()` 与常量；`maybeFade()` 不再区分"快/慢"，
  只保留两条真正"没人看得见"的早退：壁纸不可见（power save）与 EGL 表面未就绪。
- 视频首帧那条路径原本也用它决定要不要淡入（`fadePendingForFirstFrame = !wasRapidSwitch()`），
  现在恒为"要"（除转屏重绘 / 省电恢复这两种本来就该抑制的情况）。
- 连续的切换由渲染器处理：新切换会取消正在跑的帧回调并从起点重新播放
  （`startTransition()` → `cancelTransition()`），因此每次切换都有完整的 220ms 动画。
  相邻点击落在同一瞬间时仍由 `requestSwitch` 合并成一次切换（这是刻意保留的，避免堆积解码）。
- `lastSwitchCompletedAt` 仍保留：预取（prefetch）那条"只在快速连点时预解码"的启发式还在用它。

**平板实测（f617007e，release 包，临时选「缩放」并把某个分组设为 30 秒以制造连续切换）**

```
11:30:33.671 Switch to: SELF_T (14).jpg
11:30:33.914 Transition done: zoom frames=28 duration=220ms
11:30:34.214 Transition requested: zoom      <- 距离上一次结束仅 300ms
11:30:34.471 Transition done: zoom frames=27 duration=220ms
11:30:34.635 Switch to: 1 (6).jpg            <- group 24/27 连续到点
11:30:35.678 Switch to: VID_....mp4 (VIDEO)  <- 与下面这次只隔 27ms
11:30:35.705 Switch to: 0085.jpg
11:30:35.795 Transition requested: zoom      <- 每一次切换都有动画
11:30:35.884 Transition requested: zoom
11:30:36.144 Transition done: zoom frames=27 duration=220ms
11:30:37.199 Switch to: 1 (113).jpg
11:30:37.597 Transition requested: zoom
11:30:37.856 Transition done: zoom frames=28 duration=220ms
```

日志中不再出现 `Rapid switch, skipping fade`；验证完把该分组的间隔改回「跟随全局」
（`select count(*) from wallpaper_groups where intervalMs > 0` = 0），过渡动画也改回用户原来的「无」。

---

#### 4.9.66 悬浮按钮"不跟手"：每次点击都执行 + 手动切换后总是预解码

**从用户会话日志里量出来的两个原因**（11:36:35 那一段，用户连点悬浮按钮）：

1. **没有预取时，点击后要等一次 ~250ms 的整屏解码**：

   ```
   35.497 Shuffle pick -> 35.497 Switch to: 孔雀海24.jpg
   35.497 Loading image bitmap: content://media/…/169379
   35.765 Bitmap loaded: 1955x2608            <- 268ms 的现解码
   ```

   而预取命中时同一段路只要十几毫秒：

   ```
   35.250 Floating button tap -> switch
   35.267 Switch to: ac4c…d4fba534.jpg  (Using prefetched bitmap)   <- 17ms
   ```

   旧规则里预取只在"距上次切换 <3s"时才做（`PREFETCH_RAPID_GAP_MS`），所以**停顿一下再点
   的第一下、第二下都要现解码**，只有第三下起才快——这正是"不跟手"。

2. **连点时第 3 下起会被静默合并掉**：所有非定时请求共用一个 `pendingAutoSwitch` 布尔标志，
   队列里已经有一个待执行的请求时，后续点击只打一行 `Switch coalesced into pending request`
   就丢掉了（用户日志里 11:36:36.339 / 36.544 各丢了一次）。

**改法**

- `maybePrefetchNext()`：**用户手动切换（悬浮按钮 / 双击 / 立即切换）之后总是预解码下一张**，
  不再看间隔；定时切换仍然完全不预取（保持"不额外访问照片视频"的既有优化），
  解锁 / 恢复 / 修类型这些自动来源沿用原来的 3s 启发式。
- `requestSwitch()`：手动点击走自己的小队列 `pendingUserSwitches`，**每次点击都排一次切换**
  （含正在执行的那次最多 [MAX_PENDING_USER_SWITCHES]=3 个），超出才折叠，避免连点留下很长的尾巴；
  队列计数在请求执行完（`consumeSwitches` 的 finally）释放，消费者异常退出时清零，不会把点击永久吞掉。
  定时 / 解锁 / 恢复仍走原来的合并逻辑（它们不需要"每下都有反应"）。

**效果**（同一份日志里的实测对照）：预取命中时"点击 → 换图"是 17~35ms；没有预取时是 ~270ms。
改完之后第一次点击之后总会留下预取，因此后续每一次点击都走快路径。

> 备注：平板的悬浮按钮不接受 adb 注入的点击（MIUI 限制），所以这条改动是靠
> 用户自己那一段日志（点击 → 预取命中 17ms / 未命中 270ms）+ 代码路径确认的；
> 若真机上第一下仍嫌慢，可以再加"亮屏/回桌面时预解码一张"（代价是每次亮屏多一次媒体读取）。

---

#### 4.9.67 分组间隔支持自定义时间

分组详情里的「间隔」原来只有固定档位（跟随全局 / 30 秒 / 1 分钟 / 5·15·30 分钟 / 1·2·6·12·24 小时）。
现在对话框底部多了「自定义时间」，和设置里的「切换间隔」同一套做法：

- 只收 ASCII 数字（`isDigit()` 会放过阿拉伯-印度数字，`toLongOrNull()` 又不认，所以按字符过滤），
  最多 7 位；
- 单位秒，取值范围 **10 秒 – 7 天（604800 秒）**——下限与引擎的
  `SwitchSchedule.MIN_INTERVAL_MS` 一致，超出范围时输入框标红且「确定」保持禁用；
- 当前值不是预设档位时，输入框用它的秒数预填（打开对话框就能看到"当前是多少"）；
- 确定后写 `wallpaper_groups.intervalMs`，调度器（`GroupPacing`）按该分组自己的节奏到期。

文案 `group_interval_custom_hint` 已加到全部 7 个语言文件（`LocaleResourcesTest` 会校验键集合一致）。

**平板实测（f617007e，release 包）**：在分组详情里输入 `45` → 确定 →
日志 `setGroupInterval: group=37 interval=45000ms`，分组 chip 显示「间隔: 45秒」，
数据库 `intervalMs=45000`；验证完改回「跟随全局」（`setGroupInterval: group=37 interval=0ms`，
`select count(*) from wallpaper_groups where intervalMs > 0` 回到 0）。

---

#### 4.9.68 分组自定义间隔取消 7 天上限（并修掉"超过 24 小时就不生效"的老 bug）

**① 上限取消**：上一版把自定义间隔限制在 10 秒 – 7 天。现在只保留引擎真正需要的下限：
输入框只收数字（不再截断位数），只要 ≥ **10 秒**（`SwitchSchedule.MIN_INTERVAL_MS`）即可确定，
提示语改为「最少 10 秒，不设上限」。超长数字（`toLongOrNull()` 溢出）会让「确定」保持禁用。

**② 顺带修掉一个真 bug**：调度器里"锚点太旧就当作没切过"的窗口是固定的 24 小时
（`SwitchSchedule.MAX_CATCH_UP_AGE_MS`）：

```kotlin
if (age < 0L || age > SwitchSchedule.MAX_CATCH_UP_AGE_MS) return nowMs   // 旧代码
```

对 ≤ 24h 的间隔没问题，但**任何超过一天的间隔都会失效**：分组切过一次后，过了 24 小时锚点
被判定为"陈旧"，于是重新以 now 计时——7 天的间隔实际会变成"每天切一次"，永远到不了它自己的
到期时刻（前一条 `ancientAnchorMakesTheGroupDueNow` 的用例正是按旧语义写的）。屏幕级的
`currentScheduleAnchor` 有同样的问题（全局间隔填 > 24h 时定时器会一直顺延，永远不切）。

现在两者都按间隔放大窗口：

```kotlin
fun staleAfterMs(intervalMs: Long) = maxOf(
    SwitchSchedule.MAX_CATCH_UP_AGE_MS,       // 至少 24h：设备关一天不会补切一堆
    intervalMs.coerceAtMost(Long.MAX_VALUE / 2) * 2   // 至少两个完整间隔
)
```

分组用 `GroupPacing.staleAfterMs`，屏幕级把间隔传进 `currentScheduleAnchor(dao, key, interval)`。

**测试**：`GroupPacingTest` 新增 4 条（7 天/30 天间隔不会被"陈旧"判定打断、陈旧窗口随间隔放大
且不会因 `Long.MAX_VALUE` 溢出成负数、超过两倍间隔才重置），`SwitchScheduleTest` 新增 1 条
（自定义陈旧窗口保留 2 天前的锚点，默认窗口仍然重置）。合计 233 条全绿。

**平板实测（f617007e，release 包）**：分组详情输入 `2592000`（30 天）→ 确定 →
日志 `setGroupInterval: group=37 interval=2592000000ms`，chip 显示「间隔: 30天」，
`group_schedule` 写入该分组的 `lastSwitchAt/lastMediaId`；此后 15 秒内（其余分组按全局间隔
正常轮换）**没有再请求过 group=37**，说明 30 天的到期时间被正确保留、没有被当成陈旧锚点重置。
验证完改回「跟随全局」（`interval=0ms`，`intervalMs>0` 的分组数为 0）。

---

#### 4.9.69 分组独立模式不生效 + 跟随全局的分组"各切各的"把频率放大

**① 只设了模式、间隔还是「跟随全局」时，模式被忽略**

服务与预览判断"要不要按分组调度"时只看了 `intervalMs > 0`：

```kotlin
if (groups.none { it.intervalMs > 0L }) { /* 走屏幕级取图 */ }   // 旧代码
```

而在屏幕级取图里，分组自己的 `switchMode` 从来不参与——它只被 `GroupPick`（分组内取图）读取。
所以用户把某个分组设成「顺序」，只要间隔还是「跟随全局」，它就一直按**全局模式**（随机）切换：
这就是"分组的切换模式顺序模式有问题"。

改法：新增 `GroupRules.drivesOwnRhythm(group) = intervalMs > 0 || switchMode != ""`，
服务和「下一张预览」都用它判断（两边必须一致，否则预览与实际切换又会不一致）。

**② 顺带修掉频率被放大 N 倍的问题**

一旦有分组"自成节奏"，原来的 `GroupPacing` 会给**每个**分组各算一个到期时间：跟随全局的那些
分组的间隔都是全局间隔，于是每个分组每隔 interval 各切一次 → 12 个分组、10 秒间隔就会变成
**每秒切一次**（平板日志里能看到两个分组在同一时刻各切一张）。

新的 `engine/GroupSchedulePlan.kt` 把两种时钟分开：

- 有自己间隔的分组：照旧按 `lastSwitchAt + 自己的间隔` 走；
- 跟随全局（或只设了模式）的分组：**共用屏幕锚点**——到点只切其中一个，并推进屏幕锚点，
  选谁按"上次出图最早"轮换。屏幕的节奏因此与原来的屏幕级路径一致：每个全局间隔只切一次。

`usesScreenClock` 随 tick 传递：claim / restore 时该动屏幕锚点还是分组自己的行，由它决定；
分组那一行仍然写入（用于轮换顺序和分组自己的取图游标）。屏幕级与分组级两条路径的
`currentScheduleAnchor` 也都按间隔放大"陈旧窗口"（见 4.9.68）。

**③ 文案**：分组自定义间隔的说明按要求只保留「最少 10 秒」（`interval_min_10s`），
上一版新增的 `group_interval_custom_hint` 已从 7 个语言文件里删除。

**平板实测（f617007e，release 包）**

用户自建的两个测试分组：`1`（模式=顺序）、`3`（模式=随机），间隔都是「跟随全局」，全局间隔 10 秒。

| | 修复前 | 修复后 |
|---|---|---|
| 切换节奏 | 12:17:30、12:17:40 每次 tick 里 group 37 与 40 **各切一张**（约 5 秒一张） | 12:20:55 → 12:21:05 → 12:21:15 → 12:21:25 → 12:21:35，**每 10 秒一张** |
| 顺序模式 | group 1 的图片乱序（按全局随机） | group 37（=分组「1」）的 id 依次 141983 → 141984 → 141985 → 141986 |
| 轮换 | 两个分组同时切 | group 37 / 40 交替（按"上次出图最早"轮换） |

验证完把分组「3」的模式恢复成它原来的「随机」（分组「1」保持用户设置的「顺序」）。
测试：`GroupRulesTest` 新增 2 条（只设模式也算自成节奏 / 普通分组仍走屏幕级），合计 235 条全绿。

---

#### 4.9.70 手动切换（悬浮按钮 / 双击 / 立即切换 / 解锁）也要遵守分组自己的模式

上一版让"只设了模式"的分组进入分组调度，但**定时切换**才走那条路。手动路径
（`switchNow` → 广播；解锁接收器 → 广播或静态写入）始终发的是"屏幕级"请求
（`groupId = 0`），于是：

- 取图走 `MediaPick`（屏幕级），用的是**全局模式**；
- 分组自己的 顺序/洗牌 完全不参与 —— 用户看到的就是"分组选了顺序，全局是随机/洗牌时，
  这个分组的图片还是随机/洗牌"。

**改法**

1. `GroupSchedulePlan.nextGroupId(db, slot, now)`：把"下一个该切哪个分组"的判断抽出来
   （与定时循环、下一张预览同一份逻辑，0 = 屏幕级）。`nextHomeGroupId(context)` 是给
   只有 Context 的调用方的入口。
2. `WallpaperSwitchService.switchNow()` 改成先在 ioScope 里解析这个分组，再按
   `dispatchManualSwitch(app, source, groupId)` 分发：动态壁纸走广播时带
   `EXTRA_GROUP_ID`，静态模式走 `runStaticTick(..., groupId)`。手动切换不再重置/抢占分组
   的定时节奏，只是显示"下一个到期分组的下一张"。
3. 解锁切换同理：`ScreenUnlockReceiver` 先取 `nextHomeGroupId`，再
   `requestSwitchFromOutside(SOURCE_UNLOCK, groupId)` 或
   `applyStaticTickNow(..., groupId)`（两者都加了 `groupId` 参数，默认 0 保持兼容）。
4. **预取跟着分组走**：`maybePrefetchNext(source, sincePreviousSwitchMs, groupId)` 现在按
   该分组的模式与游标预解码它的下一张，缓存记录 `prefetchedGroupId`；消费时只有"同一个分组"
   （或同为屏幕级）才使用，否则丢弃重解码。否则手动点击会拿到别的分组的预取图，
   或者为了省一次解码而显示错的图。

**平板实测（f617007e，release 包）**

把全局模式临时改成**随机**，分组「1」(id 37, 模式=顺序) 与分组「3」(id 40, 模式=随机)：

```
12:37:19.475 Switch (manual): ... (group=40)
12:37:19.481 Switch to: t2.png id=142294
12:37:23.595 Switch (manual): ... (group=37)
12:37:23.600 Switch to: mackg4-project-27-bonus_1080p.mp4 id=141983    <- 顺序：上一张是 141982
12:37:27.712 Switch (manual): ... (group=40)
12:37:27.718 Switch to: tn.png id=142293
```

分组「1」的 id 严格按组内顺序前进（141982 → 141983，中间 141979-141981 属于别的分组，
数据库核对过），分组「3」在自己的组内随机；两者按"上次出图最早"轮换。
验证完把全局模式恢复成用户原来的「顺序」，两个分组的模式保持用户设置（1=顺序、3=随机）。

---

#### 4.9.71 取消分组级「切换模式」（模式统一由全局设置决定）

分组级的切换模式来回调整了几轮（只设模式不生效 → 手动点击又不遵守 → 频率被放大），
用户最终要求取消它。现在：

- **UI**：分组详情头部只剩「应用位置 / 间隔 / 时段」，`GroupModeDialog` 与
  `onModeChange` 一路从 `GroupRhythmSection`、`GroupDetailScreen` 删除；
- **逻辑**：`GroupRules.drivesOwnRhythm` 只看 `intervalMs > 0`（模式不再触发分组调度），
  取图处一律使用全局模式 —— `LiveWallpaperService.executeSwitch`、
  预取 `maybePrefetchNext`、`WallpaperApplier.applyNextOutcome`、`NextPreview.nextHome`
  里的"分组模式优先"分支全部去掉；`WallpaperViewModel.setGroupSwitchMode` 与
  `WallpaperGroupDao.updateSwitchMode` 随之成为死代码，一并删除；
- **数据**：`switchMode` 列保留（删列要做整表重建，会通过外键级联删掉所有媒体行，
  `Entities.kt` 早有这条注释），但新增 **MIGRATION_7_8** 把历史值清空
  （`UPDATE wallpaper_groups SET switchMode = ''`），实体字段标注为 LEGACY / inert，
  以后不会有旧值"复活"。

**平板实测（f617007e，release 包）**

- 分组详情头部：只剩 `应用位置 / 间隔: 跟随全局 / 时段: 全天`（切换模式 chip 已消失）；
- 安装后数据库 `pragma user_version` = 8，两个测试分组的 `switchMode` 都被清空
  （`37||0`、`40||0`）；
- 悬浮按钮点击的日志回到屏幕级取图：`Switch requested: floating-tap target=null group=0`
  → `Switch to: 097.jpg id=142296`，即完全按全局模式轮换，不再被分组模式分流。

测试：`GroupRulesTest` 里"只设模式也算自成节奏"的用例改成反向断言（存了模式的旧分组
也只能跟随全局），连同其余用例 **235 条全绿**。

---

#### 4.9.72 新功能：视频播完再切 / 按星期 / 跟随深色模式 / 收藏权重 / 分组筛选与顺序

本轮按用户清单实现 1、2、3、5、6 五项（7 小组件增强、12 运行状态面板待续），
数据库一次性升到 **v9**（`MIGRATION_8_9`），所有新列默认值与旧行为一致：

| 新列 | 位置 | 默认 | 作用 |
|---|---|---|---|
| `activeDays` | groups | 0 | 星期掩码（bit0=周一…bit6=周日；0/0x7F=每天） |
| `activeThemeMode` | groups | '' | 跟随深色模式：'' 不限 / 'LIGHT' / 'DARK' |
| `filterMode` | groups | '' | 筛选：'' 全部 / 'IMAGE' / 'MOTION' / 'FAVORITE' |
| `sortOrder` | groups | '' | 顺序：'' 加入顺序 / 'NEWEST' 新的在前 |
| `isFavorite` | images | 0 | 收藏 ★ |
| `recent_shown` | 新表 | — | "最近 N 张不重复"历史（slot, mediaId, shownAt） |

**① 视频播完再切（设置里可开关）**：新增设置 [VIDEO_PLAY_TO_END]。渲染器在每次视频播完一遍时
回调 `onVideoPassCompleted`；引擎收到**定时**切换请求时，若正在放视频且该开关打开，就把它挂起
（`pendingVideoEndSwitch`），等这一遍播完再执行。手动点击 / 解锁不受影响（点了就换），
转屏 / 省电恢复这类本来就不做过渡的情况也不受影响。

**② 时间规则 · 星期 + ③ 跟随深色模式**：`GroupRules.isActiveAt(group, now, isDarkMode)` 现在串联
三个过滤器——时段 → 星期（`dayAllowed`，周一为 bit0）→ 主题（`themeAllowed`）。
时段对话框里加了七个星期 chip（全选=每天，存 0），分组头部新增「主题: 不限/仅浅色/仅深色」chip。
服务和「下一张预览」都从 `ThemeState.isDark(context)` 读取手机当前模式，两者判断一致。

**⑤ 收藏与权重 + 最近 N 张不重复**：媒体三点菜单可「加入收藏 / 取消收藏」，批量收藏留给
`setFavorites()`。`SettingsKeys.FAVORITE_BOOST`（默认开）让 ★ 在随机/洗牌里权重 ×3
（`SettingsKeys.FAVORITE_WEIGHT`），`RECENT_NO_REPEAT`（默认关，可选 5/10/20/50）让随机模式避开
最近出现过的图片——两者都由 `PickOptions` 统一读取、`PickOptions.recordShown()` 在切换成功后写历史
（关闭时不写任何数据）。加权抽取走 `MediaPick.weightedPick()` / `SwitchPicking.pickUnseen(..., favoriteIds, favoriteWeight)`，
仍然是"确定性种子"，所以下一张预览与实际切换继续一致。

**⑥ 分组筛选 / 顺序**：`GroupPickDao` 的所有查询都加了 `:filter`（'' / IMAGE / MOTION / FAVORITE）
与两个新查询（`getNewestInGroup` / `getSequentialInGroupBefore`），顺序模式可按"新的在前"切换，
与分组网格的显示顺序一致；带筛选或顺序的分组自动进入分组调度（`GroupRules.drivesOwnRhythm`），
屏幕节奏仍由 4.9.69 的共享时钟控制。

**验证**：`assembleRelease` + `testDebugUnitTest` 全绿（239 条，含星期/主题新用例）；
平板上装了一版（v8→v9 迁移成功）后 USB 掉线，随后在 AOSP 14 模拟器复验：安装成功、
启动无崩溃、`pragma user_version = 9`、`recent_shown` 表与上述新列都已创建。

---

#### 4.9.73 运行状态面板 + 小组件增强（4.9.72 的第 7、12 项）

**运行状态面板**（设置 → 运行状态）：`WallpaperViewModel.statusSnapshot()` 一次性读取
引擎是否运行（`LiveWallpaperService.engineRunning`）、当前壁纸（`LAST_IMAGE_ID` → 名称）、
累计切换次数（`PICK_SEQ` 计数器）、距下次桌面切换（`TIMER_LAST_SWITCH_WALL_MS + GLOBAL_INTERVAL_MS`）、
内存（`Debug.MemoryInfo.totalPss`），由刷新按钮重新采集。出问题时不必翻日志就能看出瓶颈。

**小组件**：`widget_wallpaper.xml` 增加当前壁纸名称与一个倒计时 `Chronometer`
（`setChronometerCountDown(true)`，基准 = 下次切换的墙上时间）——倒计时由小组件自己每秒刷新，
不需要应用频繁更新小组件（RemoteViews 更新很贵且受系统限流）。`WallpaperWidgetProvider.fillStatus()`
填充这两行，定时循环每完成一次切换后会 fire-and-forget 刷新一次小组件。

**验证（AOSP 14 模拟器，release 包）**：安装启动无崩溃；设置页运行状态实测显示
`Engine not running (static wallpaper)` / `Current wallpaper: wstest1.png` / `Switches this run: 525` /
`Next desktop switch in: 34s` / `Memory (PSS): 76 MB`，刷新按钮工作正常。

#### 4.9.74 分组「仅图片」按悬浮按钮仍切到视频（引擎直连触发的取图泄漏）

**现象**：分组素材设为「仅图片」，定时切换出的都是图片，但按悬浮按钮仍会切到该分组里的视频。

**根因**：定时切换的广播带 `EXTRA_GROUP_ID`，而悬浮按钮在引擎存活时走更快的直连路径
（`FloatingSwitchButton.performSwitch` → `LiveWallpaperService.requestSwitchFromOutside(SOURCE_FLOATING)`），
这条路径没有分组作用域（`groupId = 0`），取图于是退化为"屏幕级全量池"——该池只按启用分组过滤，
完全不看分组自己的「仅图片 / 仅视频」设置，所以会挑到视频；桌面双击（`SOURCE_DOUBLE_TAP`）同理。
平板日志对照：同一秒内 `switch requested: timer group=37 → IMAGE`，而
`floating-tap group=0 → VIDEO`（16:02 的复现记录）。

**修复**：
- `consumeSwitches` 对用户手动触发（悬浮按钮 / 双击 / 立即切换）先用
  `GroupSchedulePlan.nextGroupId` 解析出"下一个该切的分组"再交给 `executeSwitch`。解析放在
  消费者协程里（不在点击主线程查库），预取也用同一个分组，保证"预取的下一张"与实际切换一致。
- 分组内的失败换图（`failedMediaIds` 重试）补传 `scopedFilter`：否则重试会退回不带过滤的
  `getRandomInGroup*`，同样可能拿到视频。
- 「视频播完再切」挂起的那次定时切换记住所属分组，视频播完后继续在该分组内切，不再回到屏幕级池。
- 分组内图片解码失败后的自动恢复切换携带 `scopedGroupId`，不会跳到被过滤掉的素材。
- 预取缓存消费前用 `GroupRules.allowsMedia` 复核素材类型：切换设置后残留的旧缓存（例如设置
  「仅图片」之前缓存的视频）会被丢弃并重新取图。
- `GroupPickDao.countsForSlot` 把分组自己的 `filterMode` 计入计数：只剩视频的「仅图片」分组
  不会再被调度器选中后空转一个周期。

**验证**（平板 25102RKBEC，release 包）：分组 37 设为「仅图片」后连点悬浮按钮 4 次，日志为
4 次 `User tap floating-tap: resolved group=37` + 4 次 `Switch to: ... (IMAGE)`，无一次 VIDEO；
同一时段定时切换与预取也都停在 `group=37` 的图片集合内。单元测试 240 条全绿（新增
`aMediaFilterAlsoDrivesPerGroupPicking`、`mediaFilterClassifiesImagesVideosAndGifs`）。

#### 4.9.75 「视频播完再切」被第二个定时周期切断（挂起未拦截 + 热循环回调缺失）

**现象**：开启「视频播完再切」后，视频仍在播放中被定时切换切走。平板日志（修复前）：
`16:17:13 Timed switch held` → 10 秒后 `16:17:23 Switch to: <另一个视频>`，视频被中途截断。

**两个叠加的根因**：

1. `executeSwitch` 的挂起条件带了 `!pendingVideoEndSwitch`：只有**第一个**定时会被挂起，
   第二个周期因为"已有挂起"反而放行，直接执行切换（把视频切断）。
2. 更深一层：视频循环正常走的是"热复用"路径（`keepWarm`，codec + GL 不重建），
   而该分支在到达 `if (eof)` 之前就 `continue` 了，`onVideoPassCompleted()` 只在冷重建路径
   被调用 —— 也就是说循环播放的视频**从来不会**触发"播完"通知，挂起的切换永远不会执行。

**修复**：
- `SwitchPicking.videoEndHold(optionEnabled, videoPlaying, holdPending)` 把这条规则抽成纯函数：
  选项开启且已有挂起时，后续每个定时都必须丢弃（`DROP_TICK`），直到片尾回调执行挂起的那次切换；
  中途 `isVideoPlaying` 短暂变 false（解码器重建）也不放行。关闭选项则释放挂起并立即切换。
- `WallpaperRenderer` 把 `onVideoPassCompleted` 的通知提升到 `keepWarm` 分支之前，
  热复用与冷重建两条循环路径都只通知一次（按 `eof && passFramesPresented > 0` 判定）。

**验证**（平板 25102RKBEC，release 包，10s 间隔，视频 2464×1386@60fps）：
`16:27:17 Timed switch held` → 之后每 10s 一条 `Timed switch still held: waiting for the video to end`
（共 30+ 次，期间没有任何 `Switch to:`）→ `16:33:25 Video pass finished: running the held timed switch
(group=37)` → `Switch requested: video-end group=37` → `Switch to: 269-minutes-of-axenanim_1080p.mp4`。
单元测试 244 条全绿（新增 4 条 `videoEndHold` 用例）。

> 注意：该选项按字面语义等视频**整段**播完，分组里若有 1 小时 / 4.5 小时的素材，
> 壁纸就会停留到片尾（播放帧率不足时墙钟时间还会更长）；需要"最长等待 N 分钟"上限可再加。

#### 4.9.76 在线壁纸源：Bing 每日图 / 指定 URL / WebDAV 目录

**功能**：设置 →「在线壁纸源」→ 添加来源（Bing 每日图 / 指定 URL / WebDAV 目录），
选择拉取到哪个分组（默认自动创建「在线壁纸」分组）、更新间隔、保留数量、是否仅 Wi-Fi /
仅充电更新。每个来源按自己的节奏下载新图片进分组，桌面/锁屏的轮换逻辑完全不变。

**数据模型（v10 迁移，纯新增表）**：

- `online_sources`：来源配置 + 最近一次结果（`lastResult` 是语言无关的结果码，
  如 `ok:8:0` / `err:auth`，由 UI 本地化；这样切换语言不会让历史记录变成另一种语言）。
- `online_items`：`(sourceId, remoteKey)` 主键，记录已下载的远端标识
  （Bing=日期、URL=内容哈希、WebDAV=href）、内容哈希、对应的 `wallpaper_images.id` 和文件路径。
  `MIGRATION_9_10` 只 `CREATE TABLE`，不动任何已有列，升级后分组/媒体/设置原样保留
  （平板实测：45968 张媒体、12 个分组全部保留）。

**下载与存储**：

- 文件落在 `filesDir/online/<sourceId>/<sha256>.<ext>`（应用私有目录，不进系统相册）；
- 单文件上限 40MB、单次最多 8 张新图，下载前按文件头解码校验（HTML 错误页不会被当成壁纸）；
- 按内容哈希去重：同一张图以不同 URL/文件名再次出现时只记 `online_items` 占位行，
  不重复插入媒体；用户从分组里删掉的图片会保留远端标识（不会被反复重新下载），文件会在
  下一次同步时清掉；
- `keepCount`（默认 30，0=不限）保留最新 N 张，超出的按 `fetchedAt` 从旧到新删除
  媒体行 + 文件（模拟器实测 keepCount=1 时 2 张只留最新 1 张）；
- URL 来源保存 ETag / Last-Modified，第二次同步若服务器返回 304 则零下载；

**网络与隐私**：

- 使用 OkHttp（Coil 已引入，显式固定 4.12.0）：Android 平台 `HttpURLConnection`
  会拒绝 WebDAV 的 `PROPFIND` 方法（实测 `ProtocolException: Expected one of [...] but was PROPFIND`）；
- 公网地址必须 HTTPS；只有 localhost / 10.x / 172.16-31.x / 192.168.x / 169.254.x / .local / .lan
  这些私有地址允许 http（局域网 NAS 无法提供受信任证书）；
- WebDAV 密码用 Android Keystore 里的 AES-256/GCM 加密后存库（`passwordCipher`），
  明文不落库、不进配置导出、不写日志；Keystore 失效时返回空并在界面上提示重新填写；
- 运行日志只记来源 id/类型、主机名和 HTTP 状态码，不记完整 URL（可能带 token）和凭据；
  跨主机重定向时 OkHttp 会自动去掉 Authorization 头；
- XML 解析禁用 DOCTYPE/外部实体（XXE），并且对 Android 不支持 XInclude/实体开关做了容错
  （这正是最初 WebDAV 207 却解析为空的原因）。

**耗电**：

- 每个来源一个 `WorkManager` 周期任务（最短 15 分钟，UI 选项 15 分钟～7 天），
  按来源设置施加 `NetworkType.UNMETERED/CONNECTED` + `requiresCharging` 约束；
- `OnlineSync` 全局互斥，同一时刻只有一个下载；OkHttp 连接池设为 0 空闲连接，
  同步结束后不保留 socket、不留下唤醒源；
- 失败退避重试最多 3 次（网络/超时/5xx/429），账号错误等永久失败只记录结果，等下一个周期。

**验证（AOSP 14 模拟器 + 平板 release 包）**：

- 模拟器 v9→v10 迁移：3 个分组 / 9 张媒体保留；平板 v9→v10：12 个分组 / 45968 张媒体保留；
- Bing：`BING www.bing.com -> 200` → `ok:8:0`，8 张 Bing_2026xxxx.jpg 落库、自动建组；
- URL：本机临时 WebDAV/HTTP 服务 `ok:1:0`；WebDAV：`WEBDAV 10.0.2.2 -> 207` → `ok:2:0`；
- 保留数量：keepCount=1 的 WebDAV 来源下载 2 张后只保留 1 张（媒体行 20→21，文件 2→1）；
- 界面：来源列表显示类型、目标分组、上次更新时间、`Updated: 2 new` / 失败原因，
  「立即更新 / 修改 / 删除」可用；单元测试 255 条全绿（新增 11 条 OnlineSourceRules 用例）。

**已知限制**：

- 只下载图片（jpg/png/webp/gif/bmp/heic/heif），不下载视频；WebDAV 目录里的视频会被跳过；
- 配置导出/导入暂不包含在线来源（避免把凭据导出到文件）；删除来源会同时删除它下载的
  图片和文件，界面有确认提示；
- 自签名 HTTPS 证书会按证书校验失败处理（不会静默忽略），HTTP 局域网地址可正常工作。

#### 4.9.77 在线壁纸源新增「美人图 (meirentu.club)」站点抓取

**站点结构**（实测）：列表页（首页或 `/group/xxx.html`）每个专辑卡片链接到
`/pic/<albumId>.html`；专辑页每页 3 张**高清原图**（实测 1866×2800，约 350–420KB/张），
分页为 `/pic/<id>-2.html`、`-3.html`…（该专辑有 38 页）。列表页的封面只是 560×850
缩略图，所以在线源抓的是专辑页原图。图片 CDN（p12/cdn20.mmdb.cc）**必须带
`Referer: https://meirentu.club/`**，否则返回 403（已实测）。

**实现**：

- 新增来源类型 `TYPE_MEIRENTU`（美人图），`url` = 列表页地址（默认
  `https://meirentu.club/`，也可以填某个 `/group/xxx.html` 分类页）；数据库无需迁移
  （`type` 是字符串列）。
- `OnlineSourceRules` 用纯函数解析：专辑链接（`/pic/<id>[-N].html`，按页面顺序去重）、
  专辑页图片（只保留路径含 `/<albumId>/` 的 src/data-src，推荐位其他专辑的图会被排除）、
  分页 URL、origin（Referer）。
- 每本专辑抓取**前 2 页**（约 6 张），一次同步最多 8 张；按列表顺序跳过已处理的专辑，
  某本专辑处理完写入 `album:<id>` 标记（存在 `online_items`，保留数量统计会排除它，
  否则标记会被清理导致专辑被反复重抓）。一次同步没抓完的专辑不写标记，下次继续。
- 图片 `remoteKey` = 图片完整 URL，跨专辑/跨次同步按内容哈希去重；列表页与 CDN 请求都带
  `Referer`。

**验证**（模拟器 + 平板 release 包）：

- 模拟器：首次同步 `GET meirentu.club -> 200` ×4 → `ok:8:0`，8 张 1866×2800 落库；
  点「立即更新」第二次 → `ok:8:0`，继续抓第 2 本专辑剩余图 + 第 3 本专辑，
  `album:` 标记 1→2（第 3 本未抓完，不标记）；
- 平板：新增「美人图」来源后首次同步 8 张（平板网络较慢，约 75 秒完成；
  期间每页 HTTP 200），图片进入「在线壁纸」分组。

**注意**：每本专辑只取前 2 页（不是全部 38 页），这是为了控制流量与耗电；想多要可以点
「立即更新」，每次会继续往后抓一批。

#### 4.9.78 美人图来源支持「手动选图 + 自定义数量」

**需求**：不想只按「最新专辑、每本 2 页、每次 8 张」自动抓，想自己挑图片、自己定数量。

**数据（v11 迁移，`online_sources` 加 3 列，全部有默认值、不影响旧来源）**：

- `pagesPerAlbum`（默认 2，1–10）：自动模式下每本专辑抓几页（每页 3 张）；
- `maxPerRun`（默认 8，1–50）：单次同步最多下载几张（Bing / WebDAV / 美人图都生效）；
- `selectedImages`（默认空）：手动选中的图片 URL，一行一个。非空 = 只下载这些（手动模式），
  空 = 自动模式。

**选图界面**（`MeirentuPicker`）：来源编辑页「选择图片」→ 全屏选择器：

1. 拉取列表页解析专辑卡片（封面 + `alt` 模特名 + 专辑 id，`&amp;` 等 HTML 实体已解码）；
2. 点专辑 → 三列网格显示该页 3 张高清大图（Coil 带 `Referer` 头加载，CDN 需要）；
3. 点图切换勾选（已有勾选跨专辑保留），顶部实时显示「已选 N 张」；
4. 「加载更多」翻专辑的下一页（1–10 页），「全选本页」批量勾选，「完成」写回来源；
5. 保存后同步只下载选中的 URL（按 `maxPerRun` 分批，下完为止）；「清空选择」回到自动模式。

**踩坑记录**：选择器第一版用了 `CircularProgressIndicator`，打开即
`NoSuchMethodError: KeyframesSpec$KeyframesSpecConfig.at(...)` 崩溃 —— 本项目所有页面都
刻意用「静态图标 + 文字」（见 HomeScreen / GroupDetailScreen 注释），选择器已改成同样写法。

**验证**（模拟器，release 包）：

- v10→v11 迁移成功，新列默认值正确；
- 手动模式：`selectedImages` 填 2 个 URL → 同步 `added=2`，只落这 2 张、无专辑标记；
- 选择器：专辑列表实测加载出「鱼子酱Fish / 王馨瑶 / 徐丽芝Booty …」及封面；
  进入专辑显示 3 张高清图，已选 2 张带勾选标记；`maxPerRun`/`pagesPerAlbum` 选项正常；
- 单元测试 263 条全绿（新增数量夹取、选图列表往返、专辑卡片解析、HTML 实体解码）。

#### 4.9.79 主界面新增「订阅」：阅读订阅源（Legado 兼容）

**功能**：底部导航新增第三个页签「订阅」：

- 订阅源管理：添加（名称 + RSS/Atom 地址）、启用/停用、立即更新、删除；
- **导入阅读 (Legado) 订阅源**：粘贴阅读分享的订阅源 JSON、`legado://import/rssSource?src=…`
  分享链接（`src` 为 URL 编码 JSON 或 base64），或直接选择导出的 `.json` 文件；
- 阅读：点来源进入文章列表（标题/时间/摘要/首图），点文章看正文摘要并可「打开原文」；
- 「全部刷新」+ 每 6 小时的后台自动刷新（WorkManager，需联网）。

**数据（v12 迁移，两张新表，不影响任何已有数据）**：

- `rss_sources`：名称、`sourceUrl`、Legado `type`、启用、**原始 Legado JSON（`rawJson`，
  规则字段原样保留，便于以后升级规则解析/再导出）**、最近结果；
- `rss_articles`：`(sourceId, guid)` 主键，标题/链接/摘要/正文/首图/发布时间/已读。
  重新抓取是 REPLACE 更新，**已读状态会被保留**；每个来源最多保留 300 篇（超出按时间删旧）。

**解析能力**（`FeedParser`，纯函数、DOM 禁用 DOCTYPE/外部实体）：

- RSS 2.0 / RSS 1.0(RDF) / Atom：title、link（Atom 的 `rel=alternate` href）、
  description/summary、`content:encoded`、pubDate/published/updated（RFC1123 / ISO8601 /
  `yyyy-MM-dd HH:mm:ss`）、guid/id；图片取 `enclosure` / `media:content` / `media:thumbnail`
  或正文第一个 `<img src>`（相对路径按文章链接补全）；
- JSON Feed（`items[]`）同样支持；
- 阅读的「规则型」订阅源（JS 选择器）本版不执行：源可导入，但抓取会提示解析失败
  （原文 JSON 已保存，后续可加规则引擎）。

**验证**（模拟器 + 平板 release 包）：

- 模拟器 v11→v12 迁移成功；手加 `hnrss.org/frontpage` → `RSS hnrss.org -> 200`、
  `fetched=20 new=20`，订阅页显示来源卡片，文章列表显示 20 篇（标题/相对时间/摘要）；
- 平板 v11→v12 迁移成功（13 分组 / 45992 图片 / 在线来源保留）；加 `sspai.com/feed`
  实测 `RSS sspai.com -> 200`、新增 10 篇，订阅页与「全部刷新 / 导入阅读(Legado)订阅源」
  入口正常（验证后已删除测试源）；
- 单元测试 275 条全绿（新增 12 条：Legado JSON/分享链接/base64/远程链接导入、
  RSS/Atom/JSON Feed 解析、HTML 去标签与首图提取、非法输入不崩溃）。

#### 4.9.80 阅读 (Legado) 订阅源规则引擎：从"仅标准 Feed"到"规则型源可用"

**背景**：阅读官方 `gedoor/legado` 仓库已被清空（只剩一份法律公告），但其继承项目
`Luoyacheng/legado-E`（GPL-3.0）仍在维护。本实现**参考它的规则语义独立编写**，
不复制其代码（GPL 代码直接搬入会让本应用受 GPL 约束）。

**规则引擎**（`engine/legado/`，配合 Jsoup 1.16.2 / JsonPath 2.10.0 / JsoupXpath 2.5.3 /
Rhino 1.8.1）：

- 规则切分：`@` 链式（忽略 `[]`/`()`/引号内的分隔符）、`&&`（合并）、`||`（取第一个非空）、
  `%%`（交叉合并）、`##正则##替换[##first]`；
- 默认 CSS 选择器：`class.X` / `id.X` / `tag.X` / `text.X` / `children` / 原生 CSS，
  兼容阅读的"只取点号后第一段"（`class.post.grid` = class "post"）与链式时
  **对每个匹配元素继续下钻**（导航栏的 `clearfix` 不会吞掉正文列表）；
- 索引：`.N` / `!N` / `[n]` / `[start:end:step]`（负索引、反向区间）；
- 取值：`text` / `textNodes` / `ownText` / `html` / `all` / 属性名（href、src、data-src…）；
- JSONPath（`$.…` / `@Json:`）、XPath（`@xpath:` / 以 `/` 开头，JsoupXpath）；
- **JS**：`<js>…</js>` / `@js:…` 用 Rhino 执行，绑定 `result`、`baseUrl`、`cookie`
  和 `java` 帮助对象（`ajax` / `base64Encode` / `base64Decode` / `md5Encode` / `timeFormat` / `log`）；
  积分结果按阅读的写法去掉 `.0`；
- URL 模板：`{{page}}` / `{{变量}}` / `{{JS表达式}}`、`,{method/body/headers/…}` 请求选项、
  相对路径按 `sourceUrl` 补全；`sortUrl` 按阅读语义解析为分类列表
  （`名称::路径[|下一页规则]`，默认取第一项，如"最新"）。

**兼容性取舍**：导入的阅读订阅源允许公网 `http://`（阅读本身允许，用户的源里就有
`http://3w.8012359.xyz`）；在线壁纸源仍保持 HTTPS-only。

**平板实测（用户导入的 3 个真实源）**：

| 源 | 规则特点 | 结果 |
|----|----------|------|
| www.xiurendao.net | `class.clearfix@class.art` + `@js:` 追加请求头 | `ok:20:0`，20 篇，标题/链接/封面正常 |
| 3w.8012359.xyz | 公网 http + 纯 CSS + `a||b` 回退 | `ok:12:0`，12 篇 |
| www.xiurenai.com | `class.grids@class.post.grid` + `@js:` | `ok:30:0`，30 篇 |

单元测试 287 条全绿（新增规则切分/索引/组合/替换/JSONPath/XPath/JS/分类列表等用例）。

**尚未覆盖**（阅读引擎的剩余部分，后续可继续）：`ruleNextPage`/`sortUrl` 的
`|下一页规则` 翻页、`loginUrl` 登录与持久 Cookie、`@webjs:`（WebView 执行）、
`java.getString/java.getElements` 等更完整的 JS 绑定。

#### 4.9.81 阅读规则源翻页：ruleNextPage / sortUrl 的 `|下一页规则` / `<1,2,3>` 页码

**实现**（`LegadoRss.fetch`）：每次刷新在第 1 页解析完后继续最多 **3 页**，
按阅读的语义决定下一页地址：

- 分类行自带的 `|下一页规则` 优先，其次 `ruleNextPage`；
- `ruleNextPage` 为 `PAGE`（忽略大小写）时，页码 +1 并重新套用 URL 模板；
- 否则用规则在当前页 DOM 上求值（`getString(..., isUrl = true)`），相对地址按当前页
  补全；为空或与当前地址相同（死循环）即停止；
- URL 模板中的 `<1,2,3>` 页码列表按 `<page>` 取第 N 项（超出取最后一项），
  与阅读 `AnalyzeUrl` 的 `pagePattern` 一致；`{{page}}` / `{{JS}}` 同样生效；
- 页内列表规则带前导 `-` 时按阅读语义把该页结果反转；
- 多页文章按 `guid` 去重后一起入库（`RssSync` 仍只保留最新 300 篇/源）。

**验证**：

- 模拟器本地 3 页测试页（每页 2 篇、`class.next@href` 翻页）：
  `fetched=6 new=4`，Page1/2/3 A/B 共 6 篇全部入库；
- 平板真实源（每源最多 3 页）：
  `www.xiurendao.net fetched=60 new=40`、`www.xiurenai.com fetched=90 new=60`、
  `3w.8012359.xyz fetched=12 new=0`（该站分类首页之后无有效下一页，符合源规则）。

单元测试 289 条全绿（新增页码列表 `<1,2,3>` 与 `|下一页规则` 解析用例）。

#### 4.9.82 订阅文章：按需抓正文 + 正文图片 + 勾选图片加入壁纸分组

**按需正文**：文章列表只带标题/摘要时，点开文章会即时请求详情页并把 `ruleContent`
的规则结果缓存进 `rss_articles.content`（下次打开直接用缓存）；非规则源回退到
feed 自带的 description/content。图片从正文 HTML 里用 `<img src|data-src>` 全量提取，
相对地址按文章链接补全（与封面去重）。空图片规则按"空"处理，不再产生一张指向页面
本身的伪图片。

**勾选图片 → 加入分组**（`RssMediaImporter`）：

- 文章详情里以三列网格显示正文图片，点图勾选，支持「全选 / 清空」；
- 「加入分组（N）」→ 选择目标分组（含"自动创建在线壁纸"）→ 逐张下载：
  单张上限 30MB、Referer 用源站点 origin、BitmapFactory 边界解码校验、
  按 SHA-256 命名存到 `files/rss/<sourceId>/`，并按内容哈希 + URI 去重；
- 插入正常的 `wallpaper_images` 行（folderPath=`rss/<sourceId>`），因此直接参与
  桌面/锁屏轮换、分组统计与清理逻辑；导入后刷新随机/洗牌的 id 缓存；
- 中断残留的 `.tmp` 会在下一次导入时清理（超过 1 小时）。

**验证**（模拟器，本地 3 页测试站）：

- 文章正文 `class.content@html` 抓取成功，详情显示 `Article images (2)` 且两张图
  正常渲染；
- 「全选 → 加入分组（2）→ 在线壁纸」后，DB 新增 2 行 `folderPath='rss/5'`，
  图片 2560×1600，文件落在 `files/rss/5/`；
- 首轮测试服务器单线程导致第二张图 `ProtocolException`，重试后正常，说明下载/校验/
  入库链路稳定；随后补了临时文件清理。

单元测试 289 条全绿（`allImageUrls` 等纯函数变更未新增用例数量，规则引擎用例仍覆盖）。

**仍未覆盖**：`loginUrl` 登录与持久 Cookie、`@webjs:`（WebView 执行 JS）、
`java.getString/java.getElements` 等完整 JS 绑定。

#### 4.9.83 订阅源会话 Cookie + loginUrl + 正文缓存修复

**持久 Cookie（`RssCookieStore`）**：一个按域名匹配的 OkHttp `CookieJar`，
把站点返回的 `Set-Cookie`（含域名/路径/过期/secure）持久化到
SharedPreferences，App 重启后会话仍在；订阅抓取、正文抓取、`java.ajax` 共用它。
在线壁纸源不使用该 jar（它们只访问用户配置的地址，无会话状态）。

**loginUrl**：源里配置了 `loginUrl` 时，每次刷新前先请求一次（同一源 6 小时内只请求一次，
失败不阻塞），站点设置的会话 Cookie 随后的请求自动携带 —— 这是阅读里"先登录再抓"的
最简等价实现；`loginCheckJs`/登录表单等更完整的登录流程仍未实现。

**正文缓存修复**：规则源的列表项本身没有正文，阅读态正文是点开文章后按需抓取并缓存的。
原来的刷新会用空正文 REPLACE 掉已缓存的正文，现已改为
`content = 新正文.ifBlank { 已有正文 }`，同时保持已读状态不变。

**验证**（模拟器，本地测试站）：

- 手动写入 `CACHED-BODY` 后触发两次刷新，正文仍为 `CACHED-BODY`；
- 平板打开源 4（xiurenai）文章实测正文缓存 6668 字符；其图片域名
  `xr.afxfl.com` 直接可访问，文章图片可正常显示与导入；
- 源 2（xiurendao）的文章图片 URL 在站点侧返回 404（PC 与平板一致），属于源本身
  的图像路径失效，不是解析问题。

单元测试 289 条全绿。**仍未覆盖**：`@webjs:`（WebView 执行 JS）、
`source.getVariable/setVariable`、`java.getString/java.getElements` 等完整 JS 绑定。

#### 4.9.84 阅读 JS 绑定补全：source 变量 / java.getString / java.getElements

- **`source` 绑定**（`RssSourceVariables`）：`getVariable()` / `setVariable(v)` /
  `putVariable(v)` 读写按来源的运行时变量；`put(k,v)` / `get(k)` 提供键值存储。
  语义与阅读一致（进程内缓存，按来源隔离）。源 2 的"搜索"分类 URL 模板
  `{{(source.getVariable()==''||…)?source.setVariable('薄纱'):source.getVariable()}}`
  就是这种用法。
- **`java.getString(rule)` / `java.getElements(rule)`**：在 JS 里用当前页面内容再跑一遍
  规则引擎（同一个 baseUrl / 变量 / 来源 id），返回文本或元素列表；`java` 与 `source`
  指向同一个帮助对象（阅读里两者都可访问）。
- **数字格式**：JS 数字是 double，整数值按阅读的写法去掉 `.0`
  （`java.getElements(...).length` → `2` 而不是 `2.0`）。

**验证**：单元测试新增 1 条（source 变量读写、`java.getString` 取标题、
`java.getElements(...).length` 计数），共 **290 条全绿**。手机（24117RK2CC）
从 v6 直升 v12 迁移成功，2 分组 / 821 张媒体完整保留。平板此轮掉线，
装的是上一版（正文图片 + 持久 Cookie），下次连接后补装本版。

**仍未覆盖**：`@webjs:`（WebView 执行 JS）、`loginCheckJs` 等更完整的登录流程。

#### 4.9.85 `@webjs:`：隐藏 WebView 在页面里执行 JS

**用途**：有些源的内容/图片只在站点自己的脚本跑完后才存在，普通规则拿不到。阅读用
`@webjs:` 在页面上下文里执行 JS，这里做了等价实现（`WebJsRunner`）：

- 主线程创建隐藏 `WebView`，`loadDataWithBaseURL(baseUrl, html)` 加载已抓取的页面，
  站点脚本执行完毕后 `evaluateJavascript` 跑规则脚本；`blockNetworkImage` 省流量；
- 绑定 `result` = 当前规则链的值（JSON 编码）；返回的字符串写回规则链；
- 10 秒超时 / 取消时销毁 WebView；在后台线程调用（主线程调用直接返回 null，避免死锁）；
  未初始化上下文（单元测试）时 fail-soft；
- 兼容两种写法：表达式（`document.querySelector(...).innerHTML`）与函数体
  （`...; return x;`）——先直接执行，语法错误时自动包一层 IIFE 重试。

**验证**（模拟器，本地测试页）：页面自身脚本把 `<p>JS body</p><img src="/img/a.png">
<img src="/img/b.png">` 注入 `.content`，规则
`@webjs:return document.querySelector('.content').innerHTML` 取回渲染后的 58 字符 HTML
并缓存进 `rss_articles.content`，图片随之进入文章图片网格。

单元测试新增 1 条（`@webjs:` 段落识别 + JVM 无 WebView 时 fail-soft），共 **291 条全绿**。
手机（24117RK2CC）与模拟器已装最新包（v12，数据完整）。

**阅读引擎至此覆盖**：规则切分/组合/替换、CSS/JSONPath/XPath、Rhino JS 与
`source` 变量 / `java` 帮助对象、`@webjs:`、URL 模板与 `<a,b,c>` 页码、分类列表、
`ruleNextPage` 翻页、持久 Cookie 与 `loginUrl`（交互式登录见 4.9.86/4.9.87）。

#### 4.9.86 交互式登录：订阅卡片「登录」按钮 + `loginUrl` 对纯 RSS 源生效

**背景**：`loginUrl` 此前只做"抓取前静默 GET 一次（6h TTL）"，对需要验证码/扫码/
表单会话的站点无效；而且它从 `parseRules()` 读取，纯 RSS 源（`type=0`、没有
`ruleArticles`）会被判定为"普通 Feed"，`loginUrl` 直接丢失。

**改动**：

- 新增 `ui/screens/RssLoginScreen.kt`：内嵌真实 `WebView` 打开源的 `loginUrl`
  （没有则退化为源地址本身），用户手动完成登录后点「完成登录」；
  `CookieManager.getCookie(loginUrl)` 经 `RssCookieStore.injectCookieHeader`
  注入持久 CookieJar，随后自动刷新一次该源；「取消」不改动任何状态。
- 订阅卡片新增「登录」入口（`Screen.RssLogin(sourceId)`，标题/返回/底部导航高亮
  与其它子页面一致，进程重建可恢复）。
- `LegadoRss.loginEndpoint()` 改为先读原始 JSON 里的 `loginUrl`/`sourceUrl`
  （新增私有 `rawMap()`，与 `parseRules()` 共用解析），因此**纯 RSS 源同样生效**；
  相对路径按 `sourceUrl` 解析，`{{变量}}` 模板沿用既有替换逻辑。
- 7 种语言新增 `rss_login` / `rss_login_title` / `rss_login_hint` /
  `rss_login_finish` / `rss_login_done`。

**实测**（AOSP 14 模拟器，本地 fixture 服务器，release 包）：

1. 源 `http://10.0.2.2:8129/feed` + `loginUrl=…/login`，未登录 → 「更新」报
   "账号或密码错误"（401 映射），0 篇文章；
2. 点「登录」→ WebView 打开 fixture 登录页 → 点页面内登录链接（站点写入会话
   Cookie）→ 点「完成登录」→ 自动刷新得到 2 篇文章；
3. `am force-stop` 后冷启动再「更新」仍然成功 —— Cookie 已持久化到
   `shared_prefs/rss_cookies.xml`，不依赖 WebView 自身状态。

单元测试新增 3 条（纯 RSS 源 `loginUrl` 生效 / 相对路径按 `sourceUrl` 解析 /
无 `loginUrl` 回退源地址），共 **294 条全绿**。

#### 4.9.87 `loginCheckJs`：登录页自动判定"已登录"

**背景**：4.9.86 的登录页要用户自己判断"登好了没有"再点「完成登录」。阅读用
`loginCheckJs` 在页面里跑一段脚本，返回非空/`true` 即视为登录成功。

**实现**：

- `LegadoRss.loginCheckJs()` 从原始 JSON 读取该字段（与 `loginUrl` 同源，纯 RSS
  源同样生效）；`WallpaperViewModel.rssLoginCheckJs()` 透出给 UI。
- 登录页的 `WebViewClient.onPageFinished` 里执行脚本：含 `return` 的按函数体
  包一层 `(function(){…})()`，否则按表达式 `(function(){return (…)})()`；
  `null` / `false` / `0` / `undefined` / 空串都视为未登录，保持页面不动；
  判定成功则**自动**注入 Cookie、刷新该源并返回列表，无需再点「完成登录」；
  每个登录页只自动触发一次（`autoChecked`），脚本异常 fail-soft 不影响手动完成。
- 没配 `loginCheckJs` 的源行为与 4.9.86 完全一致。

**实测**（AOSP 14 模拟器，本地 fixture，release 包）：源带
`loginCheckJs = document.cookie.indexOf('sid=') >= 0`；打开登录页（未登录）时判定为
false、页面保持打开；点页面内登录链接后站点写入 `sid`，页面重载时判定为真，
应用自动完成登录并刷新出 1 篇文章（未点「完成登录」）。

单元测试新增 1 条（有/无 `loginCheckJs` 的读取），共 **295 条全绿**。

**阅读引擎至此覆盖**：规则切分/组合/替换、CSS/JSONPath/XPath、Rhino JS 与
`source` 变量 / `java` 帮助对象、`@webjs:`、URL 模板与 `<a,b,c>` 页码、分类列表、
`ruleNextPage` 翻页、持久 Cookie、`loginUrl` 静默预登录 + 交互式登录 +
`loginCheckJs` 自动判定。剩余未覆盖：`loginHeader`/`jsLib` 等次要字段。

#### 4.9.88 对照 legado-E 订阅源代码逐项补齐（header / 脚本登录 / 规则备选 / sortUrl JS）

**背景**：以 `Luoyacheng/legado-E`（GPL-3.0）的订阅源实现为基准逐字段对照，并
用**用户平板上真实存在的订阅源**反查真实用法：5 个源**全部**带 `header`，其中一个
还带 `loginUrl(@js:)` + `loginUi`。据此补齐以下差距（只对齐语义与行为，代码按本
项目风格重写）：

- **`header` 是 JSON**（`{"Referer":"…","User-Agent":"…"}`），也支持 `@js:` / `<js>`
  生成；此前按 `Key: Value` 行解析 → JSON 头被整段丢弃。这是真实源全部命中的 bug，
  现在改为先 JSON、失败再退回旧的行格式（兼容我们自己早期导入）。纯 RSS 源与规则源
  共用同一解析（`LegadoRss.parseHeaderMap`）。
- **脚本登录**（阅读 `source.login()`）：`loginUrl` 为 `@js:` / `<js>` 时不再当网址打开，
  而是渲染 `loginUi` 表单（`[{"name":"账号","type":"text"},{"name":"密码","type":"password"}]`），
  填好后运行脚本定义的 `login()`。新增 JS 绑定：`source.getLoginInfoMap/getLoginInfo/
  putLoginInfo/putLoginHeader`、`java.post(url, body, headers)`、`java.get(url, headers)`、
  `java.encodeURI`、`cookie.setCookie/getCookie`；返回对象带 `body()/statusCode()/headers()/url()`。
  脚本抛出的错误原样显示在表单下方（如「请先填写账号和密码」）。登录信息与登录头
  （`RssLoginStore`）落盘保存，Cookie 写入既有持久 CookieJar。
- **`loginCheckJs` 参与抓取**：列表页与正文页每次请求后都会执行该脚本（`result` = 响应体），
  抛错或返回 false/0/null 即判定登录失效 → `auth` 失败，UI 提示重新登录；
  未配置该字段的源行为不变。
- **规则备选**（阅读 `splitSourceRule`）：`ruleTitle/ruleImage/…` 支持顶层逗号分隔的
  多条候选规则，取第一条有值的结果；`[]`/`()`/`{}`/引号内的逗号、`<js>…</js>` 块、
  `@js:` 之后的部分都不参与切分（`RuleAlternatives`）。
- **`sortUrl` 三种形态**：字面量（换行或 `&&` 分隔）、`<js>` / `@js:` 脚本生成（结果
  按源缓存，同阅读的 ACache 语义）；列表为空时回退到 `sourceUrl`。
- **`jsLib`**：作为共享 JS 库拼接到该源所有脚本（规则、URL 模板、登录脚本）之前执行。
- **`enabledCookieJar=false`**：该源改用不带 CookieJar 的 OkHttp 客户端，不写不读会话。

**顺带修掉一个 release 专用 bug**：这些 JS 面向对象的方法（`java.getString` /
`source.getVariable` / `cookie.setCookie` …）没有任何 Kotlin 调用点，R8 会把它们
整个删掉——release 包里所有 `@js:` 规则都会静默失败（debug/单元测试看不出来）。
已在 `proguard-rules.pro` 增加 `LegadoJsHelpers` / `LegadoCookieHelper` /
`LegadoJsResponse` 的整类保留规则，并用 mapping 文件确认方法保留。

**实测**（AOSP 14 模拟器，release 包 + 本地 fixture）：

1. 请求头：同一台服务器，带 `{"Referer":"https://ref.example/"}` 的源
   「Updated: 1 new」，不带该头的源「Update failed: Access denied(403)」——
   证明 JSON 请求头真正生效且是必需的。
2. 脚本登录：`loginUi` 表单渲染出账号/密码两栏；空表单提交显示脚本抛出的
   「请先填写账号和密码」；填入账号密码后脚本 `java.post` 登录成功，
   `cookie.setCookie` 写入会话，自动刷新出 1 篇文章；**冷启动后再「更新」仍成功**
   （登录信息/Cookie 均已落盘）。

单元测试新增 4 条（规则备选切分、header 三种形态、`&&` 分类列表、JS 登录识别与剥离），
共 **299 条全绿**。

**尚未覆盖**（阅读里存在、与本 App 的"取图"用途无关或优先级低）：`loginUi` 的
复杂控件（按钮/选项）、`concurrentRate` 限速、`preload`/`cacheFirst`、
WebView 阅读类字段（`style`/`enableJs`/`loadWithBaseUrl`/`injectJs`/`preloadJs`/
`startHtml`/`startStyle`/`startJs`/`shouldOverrideUrlLoading`）、`coverDecodeJs`、
`searchUrl`、`contentWhitelist/Blacklist`、把 `loginHeader` 应用到抓取请求
（阅读的 RSS 抓取路径本身传 `hasLoginHeader=false`）。

#### 4.9.89 图集文章（`{{@@规则}}` + `_N.html` 分页）与订阅源编辑器

**问题一：3w 源文章只显示一张图**。该源 `ruleContent` 是一段 HTML 模板
（`<div id="box">{{@@tag.img@html}}</div>`）加一段页面脚本：脚本在浏览器里
`fetch()` 兄弟页 `19566_1.html … 19566_9.html`，把整套图集塞进 `#box`。
两个坑：① 我们的 `{{}}` 只支持变量，`{{@@tag.img@html}}` 保持原样；
② `tag.img@html` 只取第一个匹配元素，而阅读的 `getString` 会**把全部匹配结果
拼起来**。于是正文里只剩列表头图一张。

修复（对齐阅读语义）：

- `LegadoRuleEngine.getString(..., joinAll = true)`：全部匹配值按换行拼接；
  `html`/`all` 与阅读一致（先去 script/style 再取 outerHtml）。
- 正文模板：`{{@@规则}}` / `{{规则}}` 作为**内层规则**对页面求值后替换
  （`expandContentTemplate`），其余 `{{}}` 仍按变量/JS 处理。
- 图集分页：当正文规则含 `<script>`（说明该源的图集靠页面脚本加载）时，
  从文章页读出最大的 `_N.html` 序号，顺序抓取这些兄弟页（上限 40），
  过滤站标（`/template/`、含 logo/icon）与小封面目录（`/pic/`），
  把新增图片以 `<img>` 追加进正文，交给既有图片列表/加入分组逻辑。

**实测（平板，真实源 3w.8012359.xyz）**：打开 `XiuRen/19566.html` 后
`rss_articles.content` 由 0 → 2979 字符、`<img>` 由 1 → 12 个，文章详情网格
显示整套图集（此前只有头图）。

**问题二：不能编辑订阅源**。新增「编辑」入口（订阅卡片 → 编辑）与整页编辑器，
对齐 legado-E 的源编辑器：

- 基本信息：名称、地址、分组、类型（网页/图片/视频）、启用、CookieJar 开关；
- 列表与正文规则：`sortUrl`、`ruleArticles`、`ruleNextPage`、`ruleTitle`、
  `ruleLink`、`ruleImage`、`ruleDescription`、`rulePubDate`、`ruleContent`；
- 登录：`loginUrl`（网址或 `@js:` 脚本）、`loginUi`、`loginCheckJs`；
- 其它：`jsLib`、`variable`、`header`（JSON）；外加「原始 JSON」直编开关。
- 保存用 `RssSourceEditor` 把改动**合并回原始 JSON**：没动的字段（含本 App
  不认识的 `customOrder`/`articleStyle` 等）原样保留，留空即删除该字段；
  地址必须是 http(s)，原始 JSON 会被校验。

**实测（平板）**：临时源改「分组=grp1」保存后 `sourceGroup` 生效，而未改动的
`ruleTitle`/`header`/`customOrder` 全部保留；测试源随后已删除。

单元测试新增 6 条（html 全量拼接、模板内层规则、编辑器合并/留空/类型写入/坏 JSON），
共 **305 条全绿**。7 种语言的编辑器文案已补齐（`LocaleResourcesTest` 校验通过）。

**另记**：xrw26.com（源 7）在 20:54 起更新报 `network`，经核查是站点侧问题 ——
平板 `ping xrw26.com` 正常，但本机与平板访问 `https://xrw26.com:443` 均超时，
与本次改动无关。

#### 4.9.90 订阅列表精简、进入即加载、源内分类切换

**交互调整**（按用户要求）：

- 订阅卡片上的「阅读」「立即更新」「删除」按钮全部移除，整张卡片可点击 →
  进入该源的分类/文章列表；「登录」「编辑」保留。删除移到「编辑订阅源」页底部
  （带二次确认），避免误触。
- **点击进去就加载**：进入源时按记住的分类自动刷新一次，加载期间显示静态
  加载提示（不新增崩溃风险，见下）。
- 底部导航顺序改为 首页 / **订阅** / 设置，订阅位于中间。

**源内分类**（阅读 `sortUrl`）：

- 文章列表顶部显示该源的分类 chips（`sortUrl` 的 `::` 名称，支持 `&&`/换行与
  `<js>`/`@js:` 生成），点选即切换并刷新。
- 数据库 v13：`rss_articles` 新增 `sort` 列（迁移 `ALTER TABLE … ADD COLUMN`），
  文章按分类存放；列表按当前分类过滤，切换分类不会串台、也不丢其他分类的
  已读状态。上次选择的分类按源记在设置表（`rss_category_<id>`），
  定时/后台刷新沿用同一分类。
- 修掉一个分类路径解析 bug：`/cat` 这类绝对路径此前会被拼成
  `…/源地址/cat/cat`；现在按阅读的语义用 URI 解析（`/cat` 覆盖路径，
  `cat` 相对源地址目录），`loginUrl` 的相对解析也统一走这条路径。

**顺带修的 release 崩溃**：文章列表初次使用的 Material3 `LinearProgressIndicator`
会在运行时报 `NoSuchMethodError`（`KeyframesSpecConfig.at(Object,int)`，与项目
锁定的 animation-core 版本不匹配）——项目里其它页面早已改用静态图标规避，
本次同样改为静态图标 + 文案。

**实测**（AOSP 14 模拟器，双分类本地 fixture，release 包）：
进入源自动加载出「分类一文章」；点「分类二」chip 后列表切到「分类二文章」，
库中两条记录分别带 `sort=分类一/分类二`。平板已装同一版本，卡片仅剩
「登录 / 编辑」、底部导航为 首页 / 订阅 / 设置。

单元测试新增 1 条（分类路径解析），共 **306 条全绿**。

#### 4.9.91 对照 legado-E 的 HTTP 层：网络失败与加载慢的根因修复

**症状**：多个订阅源显示「网络不可用」，文章/图片加载慢。

**排查**（平板真机 + 同网络 PC 双向验证）：

- 旧客户端 `ConnectionPool(0, 1ms)` 把连接池关掉了：每个请求都要重新
  TCP + TLS 握手。列表、正文、图集分页（3w 一篇文章要抓 9 个兄弟页）
  全部为此付出代价 —— 这就是「慢」。
- 旧默认 UA 是 `WallpaperSwitcher/1.1 (Android)`；不少图站只认浏览器 UA。
- 失败源的异常详情（本轮补的日志）显示两类：
  ① `UnknownServiceException: CLEARTEXT communication not enabled`
  （我自己引入的回归：显式 `connectionSpecs` 时漏了 `CLEARTEXT`，已修复，
  3w/xiurenai 等 http/规则源恢复）；
  ② `SocketTimeoutException: failed to connect … after 15000ms` /
  `InterruptedIOException: timeout` —— 属网络侧：平板上
  `toybox nc <host> 443` 对 xrw26/misskon/everia 超时，PC 端对这些站点的
  HTTP 请求也全部超时或 SSL 握手失败（TCP 通但无响应）。

**按 legado-E 对齐的修复**（`engine/RssHttp.kt`，订阅源专用）：

- **连接池复用**（默认池 + keep-alive，HTTP/2 可用），不再每次握手；
- **信任所有证书 + `COMPATIBLE_TLS`**：legado-E 的 `HttpHelper` 用
  `SSLHelper.unsafeSSLSocketFactory`，大量图站证书过期/自签名，默认校验
  直接失败；现在与 Legado 一致（仅订阅源流量；在线壁纸源仍强制 HTTPS 校验）；
- **浏览器 UA** 作为默认（源自带 `header` 仍优先）；
- 超时对齐 Legado（连接 15s / 读 60s / call 60s）；
- 图集分页**并发抓取**（4 路，`Semaphore`），打开图集文章不再串行等待；
- 文章缩略图与图集网格的图片请求带上**该源自己的请求头**
  （`Referer`/`UA`，等价 Legado 的 `GlideHeaders`），减少 403 与卡顿；
- 失败原因区分 `timeout` 与 `network`，不再一律显示「网络不可用」。

**新增：订阅源代理（legado-E `getProxyClient` 的等价物）**

- 订阅页新增「代理」，填 `host:port`（支持 `socks5://`、`http://` 前缀），
  留空=直连；保存在设置表并在启动时注入 HTTP 层，改完立即生效。
- 用途：部分站点在本网络不可达（见上），legado-E 靠代理设置解决，现在
  本应用也能；若用户的 VPN 是分应用代理，把本应用加入即可。

**实测**（AOSP 14 模拟器 + 本机代理/夹具服务器）：把代理设成
`10.0.2.2:9632` 后进入源，代理端日志打印 `GET http://10.0.2.2:9631/cat`，
文章经代理抓取成功入库；清除代理后恢复直连。

单元测试 **306 条全绿**（无新增；本轮为 HTTP/UI 改动）。平板当时掉线，
复测请在设备重新连接后进行。

#### 4.9.92 订阅图片加入分组：并行下载 + 批量入库

**优化点**（`engine/RssMediaImporter.kt`）：

- **共享 HTTP 客户端**：改用 `RssHttp.client`（连接池复用 + COMPATIBLE_TLS +
  浏览器 UA），不再自建一个把连接池关掉的客户端；
- **带源自请求头**：下载每张图都带上该源的 `header`（Referer/UA/…，等价
  Legado 的 GlideHeaders），并把源地址的 origin 作为 Referer 兜底 —— 不带的
  站点会 403/挂起，重试即慢；
- **并行下载**：`Semaphore(4)` 控制并发，8 张图从串行 8 次往返变成 2 批；
- **批量入库**：校验通过的行一次性 `insertAll`（失败再退化为逐行插入，
  避免一行坏数据拖垮整批），替代原来每张一次 insert；
- 结果上报更准确：批内重复（内容哈希/URI 去重）与非法 URL 分别计入，
  不再把「已存在」算成失败。

**实测**（AOSP 14 模拟器）：夹具提供 8 张不同内容、**每张延迟 400ms**、且必须带
`Referer` 才返回的图片。选全 → 加入分组后：

- 服务端日志显示 4 张一批、两批完成，**总耗时 0.93s**（串行约 3.2s，约 3.4×）；
- 分组内新增 **8 行**（`folderPath='rss/20'`），无 403、无重复行。

单元测试 **306 条全绿**。

#### 4.9.93 取消「在线壁纸源」功能

按用户要求下线设置里的在线壁纸源（Bing 每日图 / 指定 URL / WebDAV / 美人图）：

- **设置页入口删除**：`settings_section_online` 整段（含「在线壁纸源」行与跳转回调）
  从 `SettingsScreen` 移除，`onOpenOnlineSources` 参数一并去掉；
- **界面与导航删除**：`ui/screens/OnlineSourcesScreen.kt` 整文件删除
  （其中被订阅页共用的错误文案函数抽到新的 `OnlineStatusText.kt`）；
  `Screen.OnlineSources` 路由、顶部标题/返回、底部导航高亮、返回键分支、
  进程重建的 save/restore 映射全部移除；
- **后台任务停止**：`OnlineSourceScheduler.ensureScheduled()` 改为**只做清理** ——
  启动时取消旧的周期任务（`online_source_<id>`）与一次性刷新任务
  （`online_refresh_<id>`），不再排新的；
- **数据保留**：`online_sources`/`online_items` 表与已下载的图片、分组都不删，
  避免误伤用户已有壁纸；订阅源（阅读）功能与 `OnlineSourceRules` 等共享代码
  完全不受影响。

**实测**（AOSP 14 模拟器）：设置页不再出现「在线壁纸源」入口，订阅页
（全部刷新/代理/导入阅读订阅源）正常；**306 条单元测试全绿**。

#### 4.9.94 订阅源分页：游标 + 滚动到底自动续载（对齐阅读的懒加载）

**问题**：来源列表每次刷新固定只抓 `MAX_PAGES_PER_REFRESH = 3` 页，且没有任何
「加载更多」，所以源里更早的页永远看不到（「分页内容无法全部显示」）。
阅读的做法是按需懒加载：读到列表末尾再取下一页。

**实现**：

- `LegadoRss.fetchPages(source, rules, categoryIndex, startCursor, maxPages)`：
  把原来的一次性循环改成**游标驱动**，返回 `PageResult(articles, nextCursor)`。
  游标两种形态：
  - `page:<n>` —— 索引型源（`{{page}}` / `<a,b,c>` 列表 / `ruleNextPage=PAGE`）；
  - 绝对 URL —— `ruleNextPage` 从页面里取下一页链接的源。
  `nextCursor == null` 表示到底（无规则、取到空页、或下一页与当前页相同）。
- `RssPaging`：游标按源存进设置表（`rss_next_<id>`）；**刷新**从第一页重来并写入
  新游标，**切换分类**与**编辑源**会清空游标。
- `RssSync.loadMore()`：读游标 → 续抓（每次最多 3 页）→ 合并去重（保留已读与已抓
  正文）→ 更新游标；`Report.reason == "end"` 表示没有更多。
  初次进入源只抓 2 页（更快），其余交给懒加载。
- UI：文章列表底部有页脚——到底时自动触发加载更多（`LaunchedEffect` 在页脚被
  组合时启动），也可手动点「加载更多」；加载中显示提示，结束后显示「没有更多了」。
  7 种语言新增 `rss_load_more` / `rss_no_more`。

**实测**（AOSP 14 模拟器，4 页夹具 × 每页 3 篇）：
进入源 → 日志 `RSS refresh ok: fetched=6`（2 页）+ 游标 `page:3`；列表滚到底自动
续载 → `RSS load more ok: fetched=6 new=6 more=false`（第 3、4 页；第 5 页为空即停），
库里共 **12 篇**、游标清空、界面显示「没有更多了」。

单元测试 **306 条全绿**。

#### 4.9.95 真机分页验证 + 修掉「CSS 属性选择器被当成索引」

**平板实测（mtldss.top，真实源）**：

- 打开源 → 首次刷新 2 页（日志 `RSS refresh ok: fetched=58`），游标写入
  `https://mtldss.top/index.php/topics/first-watch/page/3/`；
- 列表滚到底自动续载两次：`load more ok: fetched=60 new=40 more=true`、
  `fetched=60 new=60 more=true`，该源文章由 78 → **184 篇**，说明懒加载在真机可用；

**顺带发现的显示 bug**：这些新抓的文章标题全为空（列表只能退化显示链接）。
根因在 `JsoupAnalyzer.selectSingle` —— 它把规则里任何结尾的 `[...]` 都当索引列表，
于是真实源常用的 `h2[class="item-heading"]@text` 被解析成「先选 `h2` 再按空索引
过滤」→ 永远空结果。修复：只有括号内容确实是索引（`0` / `-1` / `0:2` / `!1,3`）
时才走索引，否则按标准 CSS 属性选择器交给 jsoup。

**验证**：修复后重开该源，58 篇被重新解析的文章标题正常（例如
「星之迟迟 – 26.05 写真本《夜明》」）；索引语法回归测试同样通过。
新增 1 条单元测试（属性选择器 + `tag.a[-1]@href` / `tag.h2.1@text` 索引），
共 **307 条全绿**。

#### 4.9.96 文章首屏与图片加载提速

**问题**：打开一篇图集文章要等「文章页 + 全部 `_N.html` 兄弟页」抓完才显示任何内容；
图片解码用的是软件位图，栅格滚动/加载偏慢。

**改动**：

- **正文与图集拆分（progressive）**：
  - `LegadoRss.fetchArticleBase()` 只抓文章页并套用正文规则，返回
    `(html, rawPage, url)`；
  - `LegadoRss.expandGalleryImages()` 单独负责兄弟页抓取，返回图片 URL 列表
    （`fetchArticleContent()` 仍保留旧语义：base + 图集拼接）；
  - `RssSync.fetchContent()` 现在**立刻返回首屏内容**，`RssSync.loadGalleryImages()`
    在后台补齐图集，并把合并后的正文写回 `rss_articles.content`（下次打开直接命中缓存）；
  - 阅读对话框先显示首屏图片，图集加载时提示「正在加载图片…」（新增 7 语言文案）。
- **图集并发** 4 → **6** 路（`GALLERY_PARALLELISM`）。
- **图片解码**：文章缩略图/图集网格的 Coil 请求显式 `allowHardware(true)`（全局仍保留
  软件位图以保证兼容，这两处不需要回读像素），减少解码与绘制开销。

**实测**（AOSP 14 模拟器，6 个兄弟页 + 图片各延迟 300ms）：

- 点开文章 **1.2 秒**时对话框已显示首屏 2 张图并提示「Loading images…」（旧逻辑要等
  全部兄弟页抓完）；
- 随后自动补齐：缓存正文 685 字符、**14 张图**（2 首屏 + 6 页 × 2），再次打开直接读缓存。

单元测试 **307 条全绿**。

#### 4.9.97 对齐阅读的加载性能：JS 缓存 / DOM 复用 / 预取 / 磁盘缓存

针对「阅读的订阅源加载很快」的四点差异逐项对齐：

1. **Rhino 编译缓存 + 共享 scope**（`LegadoJs`）：脚本按内容缓存为 `Script`
   只编译一次；每个 `jsLib` 只求值一次并作为基 scope，各次求值在子 scope 里跑
   （等价阅读的 `scriptCache` + `SharedJsScope`）。`LegadoRuleEngine.evalJs`
   改为复用同一套引擎，不再每个 `@js:` 规则新建 `Context`/标准对象。
2. **DOM 只解析一次**（`LegadoRuleEngine.documentOf`）：同一次抓取内按字符串
   身份/相等复用同一个 Jsoup `Document`，CSS / XPath / 模板展开不再反复
   `Jsoup.parse`。
3. **图片预取**：文章对话框拿到图片列表后，后台把前 12 张（约两屏）预热进
   Coil 缓存（等价阅读的 `preload`），滚动即出图。
4. **订阅源磁盘缓存**：`RssHttp` 增加 20MB OkHttp `Cache`；正常请求仍走网络，
   仅当网络失败时用 `only-if-cached` 回退到已访问过的页面（不影响新鲜度）。

**实测**（AOSP 14 模拟器，40 条/页 × 每条 2 个 `@js:` 规则 = 80 次 JS 求值）：
首次刷新 **2292ms**（含脚本编译），再次刷新 **663ms** —— 缓存命中后约 **3.5×**；
新增耗时日志 `RSS refresh took Nms` 便于后续对比。

单元测试 **307 条全绿**。

#### 4.9.98 视频源播放与入库对齐阅读：抄站点播放器的请求头

Rule34 这类站点用 `kt_player`：播放地址带动态哈希，CDN 还会校验 JS 现场生成的
凭证，纯 HTTP 重放（Referer + UA + Cookie）仍返回 403。本轮改为「让 WebView 先跑
站点自己的播放器，再把它的请求原样交给 Media3」：

1. **捕获真实流请求**（`RssWebScreen`）：`shouldInterceptRequest` 命中媒体后缀
   （mp4 / m3u8 / m4v / webm / mov / ts / m4s）时，把该请求的**完整请求头**
   （Referer / Origin / UA / sec-ch-ua / Accept …）按 URL 存下来；丢弃
   `Host`、`Connection`、`Range`、`Content-Length`、`Accept-Encoding`
   （这些必须由 ExoPlayer 自己生成，否则分片 Range 会错）。
2. **优先播放列表**：从捕获集合里按 `m3u8 → 整文件 → 分片` 的顺序挑选，并与规则
   给出的 URL 做同名匹配；命中后暂停页面播放器（避免双份声音），用捕获头启动
   `RssVideoPlayer`（Media3）。
3. **只提供真实流**：视频源的可选列表剔除广告视频、单个 `.ts` 分片和
   `_TPL_.mp4`（站点播放器的封面占位文件，实际是静态图）；`looksLikeVideoStream`
   把 `.m3u8` 也纳入白名单，避免过滤为空后退化成「把整页图片都列出来」。
4. **预选正在播放的那条流**：`用这些图` 回传 `(urls, headers, 正在播放的流)`，
   对话框默认只勾选这条流，避免误选封面图。
5. **下载器跟着升级**（`RssMediaImporter`）：
   - `downloadHls` 支持主播放列表（按 `BANDWIDTH` 选最高码率）、`#EXT-X-MAP`
     初始化段（fMP4 拼接必需）、按分片类型决定输出 `.mp4` / `.ts`；
   - 加密播放列表（`#EXT-X-KEY` 非 NONE）明确跳过并记日志，而不是落一个坏文件；
   - `looksLikeVideoFile` 接受 `styp` / `moof`（fMP4 分片）；
   - 全流程补诊断日志：`GET …`、`hls playlist failed`、`http NNN`、
     `hls merged N segments -> …`、`import done: added=… failed=…`。

**平板实测（小米 25102RKBEC / Android 14）**

- `rule34video.com` 文章：WebView 捕获到 `svacdn77.tsyndicate.com/…/840x480.mp4.m3u8`
  （连同 Referer/Origin/UA 等），Media3 播放成功、无 `RssVideo: playback failed`；
-「用这些图」列出 2 条真实码流（850x480 / 440x240），默认勾选正在播放的那条；
- 加入分组：`hls merged 3 segments -> rss_xxx.ts (2412KB)` → `added=1 failed=0`，
  分组缩略图正常显示（说明合并后的 TS 可解码）；
- 91porn 源：捕获站点自带签名地址 `la.btc620.com//mp43/…mp4?st=…`，画面正常
  （此前 WebView 只出声音、画面黑）；
- 测试产生的 3 条导入记录（2 张站内分类图 + 1 条 TS）已在验证后清理，
  「在线壁纸」分组数量回到 656。

单元测试 **308 条全绿**。

#### 4.9.99 全屏页改为叠层：退出后列表停在原位置

旧结构里全屏浏览器用 `if (webArticle != null) RssWebScreen(...) else when(screen)`
替换整屏，于是**打开文章就把文章列表整棵组合树销毁**：退出后 `LazyColumn` 的滚动
位置（以及对话框、分页状态）全部回到初始值，用户被丢回源列表顶部，同时列表的
`LaunchedEffect(sourceId, rawJson)` 会再跑一次重新读分类。

现在改为**叠层**：

- `Box` 里先渲染当前屏幕，再把 `RssWebScreen` 包在 `Surface`（不透明背景）里
  叠在上面，列表始终保持组合与滚动位置；
- 浏览器结果（`rssBrowserResult` / `rssBrowserHeaders` / `rssBrowserSelected`）
  由仍然存活的列表收集，行为不变；
- 返回键优先级明确化：浏览器打开时只注册「关闭浏览器」的 `BackHandler`
  （`currentScreen != Home && rssWebArticle == null` 才注册屏幕级返回），顶栏返回
  箭头同理（先关浏览器再退屏幕）；
- 底部导航三个标签在切换时一并清掉 `rssWebArticle`，避免叠层挡住其它页面。

**平板实测（小米 25102RKBEC / Android 14）**：源内下滑到第 5 条后点进文章再返回，
可见条目与 y 坐标完全一致（`740 / 1106 / 1472 / 1838 / 2204`）；
「用这些图」照常弹出选择框（72 张，71 张预选）；浏览器打开时点底部「首页」能正常
回到主页。

单元测试 **308 条全绿**。

#### 4.9.100 分页改为「底部追加」：不再跳到刚抓来的那一页

源内「加载更多」过去会**跳到新抓来的内容**上，根因有三层：

1. **排序键被当成"更新时间"用**：列表 SQL 是
   `ORDER BY publishedAt DESC, fetchedAt DESC`，而无日期的源 `publishedAt` 全为 0，
   实际顺序完全由 `fetchedAt` 决定。旧代码用 `System.currentTimeMillis()` 给
   "下一页"打时间戳 —— 下一页反而比已有内容"更新"，于是插到最顶部。
   现在 `buildRows` 用 `fetchedAt = base - index` 把**页内顺序**写进排序键，
   `loadMore` 取 `MIN(fetchedAt) - 1` 作为 base，下一页必然落在已有条目**之后**。
2. **REPLACE 把老条目挪位**：`insertAll` 是 `REPLACE`，已显示的条目被重新写入时
   `fetchedAt` 会变，位置随之漂移。新增 `insertNew`（`IGNORE`）供分页使用。
3. **上限太小**：`KEEP_PER_SOURCE = 300` 且列表 `LIMIT 300`，翻第二页时旧页被
   prune 掉、列表窗口也被顶替。现在统一为 `LIST_LIMIT = 1000`
   （`observeBySource` / `observeBySourceSort` / `KEEP_PER_SOURCE` 共用），
   到上限时把游标置空，底部显示「没有更多」而不是继续抓。

同时修掉两个连带问题：

- **列表查询不再取 `content`**（文章正文缓存的列，单条可达数十 KB）：改在
  `RssSync.fetchContent` 里按需 `getContent(sourceId, guid)` 回读，1000 条列表
  不会把正文全拉进内存；
- **分页不再被滚动取消**：页脚用 `LaunchedEffect` 触发，滚动时页脚离开组合会把
  协程连同网络请求一起取消（日志里的 `LeftCompositionCancellationException`，
  且行已入库但游标没前进，导致重复抓同一页）。改为 `viewModel.requestRssLoadMore`
  在 ViewModel 作用域执行，结果回调更新 `hasMore`。

**平板实测（小米 25102RKBEC / Android 14，源 25）**：加载更多进行中与结束后，
可见条目与 y 坐标完全一致（`848 / 1214 / 1580 / 1946`），新页在下方追加
（`total 330 → 360 → 450`，日志 `new=30` / `new=60`），不再跳页。

单元测试 **308 条全绿**。

#### 4.9.101 选择器只显示全屏页里的图（去掉静态解析的杂图）

全屏浏览器「用这些图」之后，选择界面里除了文章自己的图片，还会多出几张**与文章
无关的封面**（3w 源里就是 `[XIUREN] Collection / Anran / Aimee` 这类推荐位封面）。
原因是对话框把两份列表**合并**了：

```
images = (initialImages + images).distinct()   // initialImages = 浏览器收集
                                              // images        = 规则静态解析
```

用户看到的「全面屏显示的图片」是浏览器渲染出来的那批，静态解析（站点推荐位、
图集封面、相邻文章缩略图）并不在页面上，于是就成了"其他杂图"。

现在与阅读一致：**浏览器结果直接替换静态列表**（`images = initialImages.distinct()`），
只有浏览器没收集到任何东西时才回退到静态解析结果。

另外，全屏浏览器的收集结果原先也没有过源过滤（对话框版本有），一并补上：
`keepCollectedImage(ruleContent, url)` —— 有源脚本过滤就跟随它，否则用通用图集
启发式（丢 `/template/`、`/pic/`、logo/icon 等站点 chrome），为空时再回退原始列表。

**平板实测（小米 25102RKBEC / Android 14）**

- 3w 源同一篇文章：修复前网格 `83` 张（77 张文章图 + 6 张推荐位封面），最后两行
  明显是别的图集封面；修复后 `browser images=79 → grid=79`，全部属于该文章；
- 秀人网源：`90 → grid=91`（多出的 1 张即静态封面）→ 现在网格与页面一致；
- 视频源仍只列出码流，并默认勾选正在播放的那一条。

单元测试 **308 条全绿**。

#### 4.9.102 选择器网格改为懒加载：只下载看得见的图

「图片选择器的图片加载有点慢」的根因是**网格一次性全渲染**：
`Column(verticalScroll)` 里用 `images.chunked(3)` 铺满所有行，打开 80 张的图集时
80 个 `AsyncImage` 同时进入请求队列（每个都是几 MB 的原图），首屏要和 70 多张
没人看的图抢带宽；另外预取又抢了一遍前 12 张。

现在：

- **超过 9 张切到 `LazyVerticalGrid`**（固定高度 = 屏高的 46%，夹在 240–420dp），
  只组合/请求视口内的瓦片，滚动到哪加载到哪；9 张以内仍走原来的紧凑行布局
  （视频源只有一两条码流时不会出现大片空白）；
- 顺手把瓦片抽成 `RssPickerTile`，两种布局共用；
- **预取改成"下一屏"**（`images.drop(12).take(12)`）并延后 700ms，让首屏先下；
  正文文本在网格下方，超长时单独滚动（最多 140dp）。

调试中还发现 `heightIn(max=…)` 的包裹式网格在对话框里会自己往下漂
（日志里 `firstVisibleItemIndex` 从 21 一路涨到 72），因此改用**确定高度**，
网格稳定停在顶部（`grid first=0`）。

**平板实测（小米 25102RKBEC / Android 14，85 张图集）**：打开选择器后 4 秒内
首屏 9–12 张全部显示（此前要等 80 张排队），下滑即时加载新行，网格停在顶部
不漂移。

单元测试 **308 条全绿**。

#### 4.9.103 选择器排版整理

- **去掉「打开原文」按钮**：浏览器模式本身就有入口，选择框里它是多余的一个跳转
  （长标题时还会被挤成竖排）；
- 「全选 / 清空」移到「文章图片（N）」同一行的右侧，与它们作用的对象对齐，
  省掉一整行；
- 标题最多两行（原来三行，长标题会把图片挤出屏幕）；
- 正文为空时的「没有可显示的正文」提示只在**没有图片**时显示（有网格时它是噪声）；
- 底部按钮整理为一行：`重新抓取`（次要）+ `加入分组（N）`（主要），`确定` 单独
  靠右；`加载中` 改为计数旁的小图标。

单元测试 **308 条全绿**。

#### 4.9.104 新源进入即加载第一个分类 + 提示文案修正

「点击对应的分类才加载」对老源是对的（进入即秒显缓存列表），但对**刚添加的源**
就变成"进去一片空白、不知道要点哪里"。现在按"有没有缓存"分流：

- 进入源时先读 `rssArticleCount(sourceId)`；
- **0 篇（新源）**：直接把当前分类（默认第一个）抓下来 ——
  `rssSelectCategory(source, index)`（内部 `refresh(initialPages = 2)`，索引型源
  会并发抓两页），期间界面显示进度；
- **已有文章**：保持原样，只显示缓存列表，点分类才走网络。

提示文案同步修正：

- 旧的空列表提示是「还没有文章，点来源卡片上的「立即更新」」——来源卡片上早已
  没有这个按钮了。改为「这个分类还没有内容，点上方分类可重新加载」；
- 新增 `rss_loading_category`（`正在加载「%1$s」…`），加载中显示具体分类名，
  而不是笼统的「加载中…」；7 种语言（zh / zh-rTW / en / es / ru / ko / ja）一起补。

**平板实测（小米 25102RKBEC / Android 14）**

- 清掉源 25 的缓存后进入：显示「正在加载「PURE MEDIA」…」，
  `RSS refresh ok: source=25 fetched=60 new=60`（3.9s）后列表直接出现内容；
- 再次进入同一源：秒显已存列表（无新的 `RSS refresh ok`），仍是"点分类才加载"。

单元测试 **308 条全绿**。

#### 4.9.105 订阅源列表保留滚动位置

进入某个源再返回时，订阅卡片列表会跳回顶部 —— 因为 `when (screen)` 切屏会把
`SubscriptionScreen` 整棵组合树销毁，`LazyColumn` 的滚动状态（局部 `remember`）
随之丢失。现在把状态**提到调用方**：

```kotlin
// WallpaperSwitcherApp
val subscriptionsListState = rememberLazyListState()   // 在 when 之外
...
is Screen.Subscriptions -> SubscriptionScreen(..., listState = subscriptionsListState)
```

`SubscriptionScreen` 的 `listState` 参数带默认值（`rememberLazyListState()`），
其它调用点不受影响。

**平板实测（小米 25102RKBEC / Android 14）**：源列表下滑三屏后进入
`www.xiurenai.com`，返回时可见卡片与坐标与进入前完全一致
（`392 / 442 / 506 / 548 / 752 / 802 / 866 / 908 / 1112 / 1162`），不再回到顶部。

单元测试 **308 条全绿**。

#### 4.9.106 cosplaytele 这类源「阅读有内容、壁纸软件空白」的修复

用户导入的 `cosplaytele` 在阅读里正常，在壁纸软件里始终 `err:not_found`。实测
（平板 curl，含 VPN）：**首页 200，但 `sortUrl` 的前两个分类是死链**——

```
https://cosplaytele.com/                     -> 200
https://cosplaytele.com/category/video-cosplay/ -> 404   ← 默认分类
https://cosplaytele.com/category/nude/          -> 404
https://cosplaytele.com/category/cosplay/       -> 200
```

新逻辑又默认加载第一个分类，于是整源空白。两处修复：

1. **分类失效回退首页**（`LegadoRss.fetchPages`）：首页抓取失败（HTTP 404 等）或
   解析为空，且当前是分类的第一页时，自动改用 `sourceUrl` 再抓一次
   （阅读打开源时展示的也是首页流），日志记
   `category 'X' failed (not_found); using https://…/`；
2. **按分类判断是否需要自动加载**：进入源时改看"当前分类有没有缓存"
   （`countOfSort`），不再只看整源数量 —— 之前只要该源别的分类有内容，
   当前空白分类就不会自动加载。

顺带确认：`articleFrom` 生成的条目要求标题或链接非空；首页流解析出 66 条带标题、
带封面的文章。

**平板实测**：进入 cosplaytele → 日志
`category 'Video Cosplay' failed (not_found); using https://cosplaytele.com/` →
`RSS refresh ok: source=29 fetched=66 new=66`（2.1s），列表出现 66 条带缩略图的
文章；点进第一篇，浏览器模式收集到 42 张图，可正常加入分组。

单元测试 **308 条全绿**。

#### 4.9.107 编辑器覆盖源的全部字段

之前的编辑页只列了 `RULE_FIELDS` / `LOGIN_FIELDS` / `OTHER_FIELDS`，像
`enableJs`、`loadWithBaseUrl`、`singleUrl`、`cacheFirst`、`preload`、`showWebLog`、
`articleStyle`、`shouldOverrideUrlLoading`、`style`、`sourceIcon`、`customOrder`、
`concurrentRate`、`lastUpdateTime` 这些字段在表单里看不到（只在原始 JSON 里有）。

现在新增「其余字段（源 JSON）」区：

- `RssSourceEditor.remainingKeys(rawJson)` 列出所有**没有被带标签控件覆盖**的键
  （按 JSON 顺序），每个键一行文本框，长值自动多行；
- 底部可**新增字段**（键 + 保存后写入 JSON），把不存在的键补齐；
- 保存仍走 `applyChanges`：留空即删除该字段，未触碰的字段原样保留；
- 类型不再被字符串化：`coerceToOriginalType` 让布尔/数字字段改完仍是 JSON 的
  `true/false/123`（新增键按 `true/false` 猜测，其它按文本）；
- 「启用 CookieJar」开关改为显式写 `true/false`（之前打开开关时传 null，
  已存的 `false` 不会被改掉）。

顺带修掉一个导入 bug：`LegadoImport.parse` 只读 `name`，而阅读的 RSS 源 JSON 用的
是 **`sourceName`**，于是所有导入源的标题都退化成域名（`meirentu.club` 而不是
「美人图」）。现在优先 `name`、回退 `sourceName`；编辑页保存时只在名称真的被改过
才回写 `sourceName`，URL 始终与表单同步。

**平板实测（小米 25102RKBEC / Android 14）**：编辑页出现 `articleStyle / cacheFirst /
concurrentRate / enableJs / …/ style`；原样保存后对比数据库 JSON，
**26 个键一个不少、类型不变**（仅 `sourceName` 与旧版不一致的源除外）。

单元测试 **314 条全绿**（新增 `RssSourceEditorTest` 5 条 + `LegadoImportTest` 1 条）。

#### 4.9.108 订阅界面精简 + 订阅文章不再本地缓存

**界面**：订阅源列表去掉「全部刷新」和「代理」两个按钮（以及代理对话框、ViewModel
里的 `refreshAllRssSources` / `rssProxyValue` / `rssProxySave`），页面只剩源卡片
和「添加订阅源」浮动按钮。代理的 HTTP 支持保留在引擎里（老配置仍生效），但没有
入口了。

**不再缓存（省存储）**：

1. **进源实时抓**：`SubscriptionArticlesScreen` 进入时无条件
   `rssSelectCategory(source, index)`（内部 `refresh(initialPages = 2)`），列表不再
   吃本地缓存；
2. **退出即清空**：`DisposableEffect(sourceId) { onDispose { viewModel
   .clearRssSourceCache(sourceId) } }` —— 在 ViewModel 作用域执行
   `DELETE FROM rss_articles WHERE sourceId = ?`，连正文缓存（`content` 列）一起删，
   只保留用户加入分组的壁纸文件；
3. **启动清残留**：`WallpaperSwitcherApp.onCreate` 里把上一次运行遗留的
   `rss_articles` 行全部删除（实测启动时一次清掉 3311 行）；
4. **取消后台定时刷新**：`RssScheduler.ensureScheduled` 改为只
   `cancelUniqueWork("rss_refresh")`（6 小时周期任务不再排队），
   `refreshAllNow` 一并删除；`RssRefreshWorker` 类保留（避免历史 WorkManager 记录
   指针悬空），但不再被任何代码排入队列。

代价：进入源需要一次网络往返（约 2 秒），换来的是订阅内容不占手机存储。

**平板实测（小米 25102RKBEC / Android 14）**：启动后 `rss_articles` 由 3311 行
降到 0；进入源 → `RSS refresh ok: source=30 fetched=60 new=60`、库内 60 行；
返回源列表 → 日志 `RssCache: cleared cached articles of source=30`、库内 0 行。

单元测试 **314 条全绿**。

#### 4.9.109 订阅导入的媒体：占用说明与「删行不删文件」修复

**占不占存储**：占。`RssMediaImporter` 把订阅里勾选的图片/视频下载到应用私有目录
`files/rss/<源 id>/<sha256>.<ext>`（在线壁纸时代还有 `files/online/<id>/`），
这些就是加入分组的壁纸本体，会一直占着应用存储。

**原本的漏洞**：删除图片（单张 / 批量 / 删分组）只删数据库行，**文件留在磁盘上**，
删除订阅源也一样。实测平板上 `files/rss` 有 **959 个文件 / 280MB**，其中只有
**111 个**还被分组引用 —— 848 个是删行后留下的孤儿。

修复：

1. `deleteImage` / `deleteImages` / `deleteImagesByIds`（后者先 `getUrisByIds` 取
   URI 再删行）和 `deleteGroup` / `deleteGroups` 都会调用
   `deleteOwnedMediaFiles(uris)`；
2. 该助手**只删应用私有目录下的文件**（`files/rss/`、`files/online/`），相册 /
   文件夹来源的 uri 指向用户自己的文件，绝不触碰；
3. 新增 `OwnedMediaCleaner.sweep`：启动时扫这两个目录，把数据库里已无对应行的文件
   清掉（跳过 10 分钟内刚写入的下载），并删除清空后的目录。

**平板实测**：启动清理日志
`swept 848 orphan file(s) under files/rss (250MB freed)` +
`swept 16 orphan file(s) under files/online (6MB freed)`，
`files/rss` 由 280MB/959 个 → **28MB/111 个**（正好等于仍被引用的行数）；
随后在分组里批量删除一条导入图片：行消失、`files/rss/34/8b85….jpg` 同步消失。

单元测试 **314 条全绿**。

#### 4.9.110 设置：订阅图片下载目录（默认应用私有 / 可选到自选文件夹）

在「设置 → 文件夹自动扫描」区块下面新增一行 **订阅图片下载目录**：

- 默认「应用私有目录（默认，不占相册）」= `files/rss/<源 id>/`，和以前一样；
- 点这行用 SAF（`OpenDocumentTree`）挑一个文件夹，选完
  `takePersistableUriPermission` 持久化授权，行里显示该文件夹名，右侧多一个
  「恢复默认」；选中的是 `host:port` 之外的目录 URI（存 `app_settings.rss_download_dir`）。

引擎侧（`RssMediaImporter`）：

- 下载 + 校验仍在私有目录的临时文件里做（`BitmapFactory` / 文件头校验不变），
  校验通过后用 `DocumentFile` 按 `<sha256>.<ext>` 写进用户目录，行里存
  `content://` 文档 URI，然后删掉临时文件；
- 同名文档已存在就**复用**（去重），写失败则回退到私有目录（日志
  `publish failed: …`）；
- 批量入库日志会记 `published N file(s) into …`。

删除与清理同步跟上：

- `deleteOwnedMediaFiles` 现在同时处理 SAF 文档：只删**落在用户所选目录树内**的
  `content://` 文档（`RssDownloadDir.isInside` 比对 documentId 前缀），相册 /
  文件夹来源的 uri 依旧不碰；
- `OwnedMediaCleaner` 启动清理会顺便扫这个目录，只删**我们自己命名**的文件
  （64 位十六进制 + 扩展名）中已无对应行的，用户自己的文件不动。

**平板实测（小米 25102RKBEC / Android 14）**：选了一个自选文件夹后，
`import start: 1 url(s)` → `published 1 file(s) into content://…tree/primary%3A…`，
设备上出现 `7a93e88e….webp`（146KB）且行 URI 为 `content://…/document/…`；
在分组里删除该图后，行与文件同时消失（`row 143291: 0`、文件计数 0）；
最后点「恢复默认」，设置清空回到私有目录。

单元测试 **314 条全绿**。

#### 4.9.111 删除订阅图片不再卡顿 + 配置导出带上订阅源

**删除提速**。`guardedWrite` 跑在 `viewModelScope`（主线程），而 4.9.109 加的
`deleteOwnedMediaFiles` 是同步逐文件删除 —— 一次选择上千张时，上千次
`File.delete()` / SAF `deleteDocument`（binder 调用）全压在主线程上，界面直接卡住。

现在：

- 文件删除整体挪进 `Dispatchers.IO`，用 `Semaphore(8)` **8 路并行**，逐个删完
  在后台收尾；
- 取 URI 也**分片查询**（每次 500 个 id）：SQLite 旧设备绑定变量上限 999，一次
  select-all 删除几千张会把语句撑爆（和已有分片 DELETE 同一原因）；
- 新增日志 `MediaDelete: deleted N file(s) in Xms` 便于观察。

**平板实测（小米 25102RKBEC / Android 14）**：临时分组导入 77 张订阅图后
「批量操作 → 全选 → 删除所选」，**6.3 秒内**行数与文件同时清空
（`MediaDelete: deleted 77 file(s) in 6284ms`，`files/rss/34` 归零），
期间界面可正常操作（删除后立即 dump UI 有响应）。

**配置导出增加订阅内容**。`ConfigBackup` 升到 `version: 2`，`Config` 增加
`sources: List<SourceConfig>`（name / url / type / enabled / rawJson），
导出文件新增 `"sources": [...]`；导入时按 URL 去重后插入新订阅源，
返回 `ApplyResult(groups, sources)`，提示语改为「已导入 %1$d 个分组、%2$d 个订阅源」。
老版本（version 1）的配置文件仍可导入（sources 视为空）。

**平板实测**：导出配置 → 文件 `version: 2`，其中 `rawJson` 条目 23 个（= 当时的
订阅源数量）；单元测试新增 3 条（订阅源往返、缺 url 跳过、v1 文件兼容），
总计 **317 条全绿**。

#### 4.9.112 取消设置里的「运行状态」面板

设置页删掉整块 **运行状态**（引擎是否运行 / 当前壁纸 / 本次运行切换次数 /
图片解码次数 / 媒体读取次数 / 内存 PSS / 距下次切换 / 刷新按钮），连带清理：

- `SettingsScreen` 里该 `SettingsSection` 与 `StatusLine` 组件；
- `WallpaperViewModel.statusSnapshot()` 与 `StatusSnapshot` 数据类；
- 7 种语言里的 `settings_section_status`、`status_*` 共 11 条字符串。

`LiveWallpaperService.engineRunning` 仍被切换服务使用，保留。

单元测试 **317 条全绿**。

#### 4.9.113 订阅源列表多选（仅批量删除）

订阅源列表加上多选，交互与分组列表一致：

- 入口：列表右上角的 ☑ 图标，或长按任意订阅卡片（长按会直接选中该源）；
- 多选态工具栏复用抽出来的 `MultiSelectActionsBar`（`HomeScreen` 与
  `SubscriptionScreen` 共用，`GroupSelectionToolbar` 已迁移到该文件）：
  第一行「退出 / 全选 / 已选 n/m」，第二行动作按钮；
- 卡片在多选态显示 ○ / ✓ 与高亮底色，右上角的单删按钮隐藏，「添加订阅源」浮动
  按钮也收起；
- **订阅源的多选只保留「删除」**（不做批量启用/停用）：把工具栏的
  `onEnable` / `onDisable` 传 null 即可隐藏那两个按钮，分组列表仍然保留启用/停用。
  删除前有确认框（`dialog_delete_sources_message`，说明已导入分组的图片会保留），
  实现为 `viewModel.deleteRssSources(ids)`。

**平板实测（小米 25102RKBEC / Android 14）**：点右上角 ☑ 进入多选 → 工具栏为
「✕ / ☐全选 / 已选 0/22」；点一张卡片变「已选 1/22」并出现唯一的动作按钮「删除」；
再点 ✕ 退出、单删按钮与浮动按钮恢复。

单元测试 **317 条全绿**。

#### 4.9.114 UI 重构（参考 HyperIsland / Miuix）：设计令牌、大标题与悬浮胶囊底栏

**动手前的备份**：`D:\WallpaperSwitcher-backups\src_20261003_213942`（robocopy /MIR，
排除 `app\build`、`build`、`.gradle`、`.idea`、`.repair`、`.recover`、`kt_probe`，
151MB，日志 `robocopy_src_20261003_213942.log`）。

**参考对象**：`github.com/1812z/HyperIsland`（安卓端基于 **Miuix**，即 HyperOS/MIUI
设计语言）。从它的源码里提取到的规范：页面左右 16dp、卡片间距 12dp、卡片圆角 16dp、
设置行内边距 18/14dp、行首图标与文字间距 16dp、分区标题是 16sp 强调色文字
（Miuix `SmallTitle`）、底栏是悬浮胶囊（外 64dp / 内 56dp、圆角 28dp、距底 12dp、
选中项药丸高亮）、状态色为 `#36D167 / #FF5A52`（配 `#DFFAE4 / #FFE5E3` 底色）。

新增 `ui/theme/HiUi.kt` 把这些落成可复用件：`HiDims` 令牌、`HiCard`（16dp 圆角、
纯色、无描边无阴影）、`HiSectionTitle`、`HiRow`（图标 + 标题/说明 + 行尾）、
`HiNavigationBar`（悬浮胶囊 + 药丸选中）、`HiStatCard` / `HiStatusCard`（状态配色）。

本轮应用范围：

- **顶栏**：`CenterAlignedTopAppBar` → 左对齐 24sp 粗体标题、透明底
  （内容从标题下方滚过），返回箭头与浏览器叠层逻辑不变；
- **底栏**：Material `NavigationBar` → `HiNavigationBar` 悬浮胶囊，
  三个标签（首页/订阅/设置）选中态用药丸 + 强调色；
- **首页**：「壁纸分组」标题改为 Miuix 小号强调色标题；分组卡片 20dp→16dp 圆角、
  去掉描边、图标从渐变圆形改为 44dp 圆角方块（14dp 圆角），行内边距 18/14；
- **设置页**：`SettingsSection` 去掉标题前的强调条，改为一整行小号强调色标题，
  分组卡片 20dp→16dp 圆角并去掉描边。

**验证**：`AOSP 14` 模拟器上编译安装后截图确认——左对齐大标题、悬浮胶囊底栏
（选中药丸）、首页强调色小标题与 16dp 圆角卡片均按预期渲染。

单元测试 **317 条全绿**。

#### 4.9.115 UI 重构（续）：分组详情、订阅列表与顶栏返回逻辑

同一套 `HiDims` 令牌继续铺开：

- **分组详情**：顶部信息卡 20dp→16dp 圆角并去掉 2dp 阴影；媒体格子圆角 14dp→12dp，
  选中描边 3dp（primary）→ 2.5dp（跟随主题强调色）；
- **订阅列表**：源卡片 16dp 圆角、零阴影、行内边距 18/14；文章行 16dp 圆角，
  缩略图 10dp 圆角，内边距 14/12，底色与源卡片统一到 45% surfaceVariant；
- **顶栏返回箭头**：原来「非首页」就画返回箭头，导致「订阅」「设置」这两个底栏
  顶层标签看起来像详情页。现在只有真正的子页面（分组详情 / 源内文章 / 登录 / 编辑源）
  才显示箭头。

**验证**：模拟器截图确认订阅页为左对齐大标题 + 16dp 圆角卡片 + 胶囊底栏；
新包同时装到模拟器与手机（`0A0AA84189A00540`，21:58）。

单元测试 **317 条全绿**。

#### 4.9.116 UI 重构（续二）：首页服务控制卡

首页最显眼的服务控制卡从 Material 观感收敛到参考项目（HyperIsland）的状态卡：

- 运行时底色改为参考项目的绿色状态底 `#DFFAE4`、状态点用 `#36D167`
  （深色主题回退到强调色容器以保对比度）；停止时保持中性 surfaceVariant；
- 圆角 24dp→16dp、去掉 3dp 阴影与停止态描边（Miuix 卡片无阴影无描边）；
- 「暂停 / 预览下一张」由 OutlineButton 改为 tonal 按钮 + 14dp 圆角，
  「立即切换」同样收敛为 14dp 圆角，与卡片内其它元素对齐。

**验证**：模拟器截图确认首页状态卡为绿色底 + 绿色圆点 + 16dp 圆角 + 圆角药丸按钮；
新包已装到模拟器与手机。单元测试 **317 条全绿**。

#### 4.9.117 UI 重构（续三）：分组详情操作栏

分组详情顶部的「添加壁纸 / 批量操作 / 清理失效」原来是一个 tonal + 两个描边按钮，
与新的卡片规范不一致。现在三个按钮统一为 **tonal + 14dp 圆角**、最小高度 44dp
（保持原有的可点区域与窄屏两行文字策略），整栏观感与首页状态卡的按钮一致。

**验证**：编译安装到模拟器与手机；单元测试 **317 条全绿**。

#### 4.9.117 UI 重构（续四）：对话框圆角统一

主题 `AppShapes.extraLarge`（`AlertDialog` 默认取这一档）28dp → **20dp**，
全部对话框（新建分组、删除确认、暂停时长、间隔设置、图片选择等）一次性收敛到
HyperOS/Miuix 对话框的观感，同时与页面卡片的 16dp 保持层级差；页面卡片、
按钮、底部胶囊不受影响。

**验证**：编译通过并装到模拟器与手机；单元测试 **317 条全绿**。

#### 4.9.117 UI 重构（续五）：空态提示

首页「还没有分组」空态原来是一个 80dp 裸图标加淡色文字。现在改为 HyperOS/Miuix
的容器式空态：44dp 图标放进 96dp、28dp 圆角的浅色方块里（图标取主题强调色），
标题文字加深到 `onSurfaceVariant`，与新建的卡片/图标语言一致。

**验证**：编译通过并装到模拟器与手机；单元测试 **317 条全绿**。

#### 4.9.117 UI 重构（续六）：订阅空态

订阅页「还没有订阅源」的空态也改成容器式图标：96dp、28dp 圆角浅色方块内放
44dp 的 `MenuBook` 图标（强调色），间距 12→18dp，与首页空态、卡片图标语言统一。

**验证**：编译通过并装到模拟器与手机；单元测试 **317 条全绿**。

#### 4.9.117 UI 重构（续七）：浏览器页按钮

全屏浏览器底部按钮区（系统播放器 / 取消 / 阅读 / 用这些图）的「取消」「用这些图」
补上 14dp 圆角，与全局按钮规范一致（其余两个是纯文字链接样式，保持原样以免
四个按钮挤在一起时显得过重）。

**验证**：编译通过并装到模拟器与手机；单元测试 **317 条全绿**。

#### 4.9.118 设置页归组 + 全局加载态统一

**设置页归组**（把之前零散、串组的项放回它该在的卡片）：

- 「订阅图片下载目录」原本混在「文件夹自动扫描」卡片里（它是存储位置设置，
  与扫描无关），现在单独成组，复用早已存在但一直没人用的分区标题
  `settings_section_online`（在线壁纸源 / Online sources），七种语言都有现成译文；
- 「切换方式」的分区标题原来叫「切换方式/悬浮按钮外观」，而悬浮按钮外观有自己
  的独立卡片（紧跟其后），标题里的斜杠是合并时期的残留 —— 七种语言统一改回
  「切换方式」；
- 清掉了扫描卡片与悬浮按钮卡片之间的双重 `Spacer`（页面其它分区都是 8dp）。

**全局加载态统一**：新增 `ui/theme/HiUi.kt` 的 `HiLoadingHint` / `HiLoadingState`
（静态沙漏图标 + 文字；全应用刻意不用动画进度圈，捆绑的 animation-core 缺少 M3
`CircularProgressIndicator` 所需方法，历史上会崩 NoSuchMethodError）。替换掉
各页各自手写的加载行，图标尺寸 / 间距 / 字色收敛到一处：

- 分组详情的首屏网格加载与文件夹扫描态；
- 首页「下一张预览」对话框；
- 订阅列表的源内文章加载、分页加载、文章选择器的图片加载；
- 美女图选择器首屏、RSS 登录页取源、源编辑器等源尚未就绪时的等待态。

**空态也抽成共享组件**：`HiEmptyState`（96dp / 28dp 圆角的浅色方块 + 44dp 强调色
图标 + 标题 + 可选说明）取代首页、订阅、分组详情三处各自手写的空态 —— 上一轮
只统一了首页与订阅，分组详情仍是 64dp 裸图标 + 40% 透明文字。

**验证**：编译通过并装到模拟器与手机；单元测试 **317 条全绿**；模拟器截图确认
设置页出现独立的「Online sources → Subscription download folder」卡片、原扫描
卡片只剩自动扫描与扫描间隔、顶部区块标题已从「Switching & button」变为
「Switching」；首页与分组详情在重装后正常渲染。

#### 4.9.119 UI 重构（续八）：动效层（HyperOS/Miuix Motion）

新增 `ui/theme/HiMotion.kt` 动效令牌：时长 150 / 240 / 360ms，曲线用
EmphasizedDecelerate（进场）、EmphasizedAccelerate（退场）、Standard（轻量），
另有 selection / press 两条弹簧。所有动效都从这里取参数。

**页面切换**（`WallpaperSwitcherApp`）：`AnimatedContent` 只播「进场」、
`ExitTransition.None` —— 旧页面立即释放，动画期间只有目标页在合成，保留历史
版本「整屏 crossfade 两页同屏导致掉帧」的教训。方向按页面层级变化决定：

- 进入子页面（分组详情 / 源内文章 / 登录 / 编辑源）：从右滑入 1/8 宽 + 淡入；
- 返回：从左滑回；顶层标签之间：轻微上浮 1/28 高 + 淡入；
- 顶栏标题交叉淡入，返回箭头随子页面淡入 + 0.8→1 缩放；
- 浏览器叠层淡入 + 3% 上浮（关闭不动画：WebView 立即释放更重要）。

**控件动效**：

- 悬浮胶囊底栏：药丸底色、图标/文字颜色、图标缩放共用同一个 0→1 进度
  （弹簧），选中切换是连贯的一段动画而不是三个硬切；
- `HiCard` 按压反馈：按下 0.975 缩放 + 默认涟漪，松手回弹（只走
  graphicsLayer，不触发重新布局）；
- 展开/收起（淡入 + expandVertically / shrinkVertically）：设置页里依赖开关的
  子项（切换间隔、旋转方向、锁屏间隔）、首页两张警告卡与服务卡按钮区、首页与
  订阅页的多选工具栏、分组详情的扫描进度卡；
- 首页服务卡：运行/停止底色与状态点颜色都用进度插值，不再硬切。

**回归修复（真机级别）**：底栏的弹簧会轻微过冲（1→0 时短暂低于 0），而
`Color.copy(alpha = v)` 对越界值直接抛 `IllegalArgumentException`
（`red = …, blue = …, alpha = -0.0019…`），在模拟器上切换标签时崩溃过一次。
颜色用的进度现在一律先 `coerceIn(0f, 1f)`；同样的导航脚本重跑无崩溃。

**验证**：编译通过并装到模拟器与手机；单元测试 **317 条全绿**；模拟器上跑
「三个标签来回切 + 进分组 + 返回」×3 与设置页开关的展开/收起，logcat 无
FATAL；真机装包后进程正常、无崩溃日志。

#### 4.9.120 首页服务卡运行底色：跟随主题色的渐变

运行态的底色原来是参考项目的固定薄荷绿（浅色 `#DFFAE4`，深色用
primary/secondary 容器色）。实际观感是「绿底 + 强调色的标题 + 强调色的 tonal
按钮」三个色系撞在一起（默认蓝紫主题下尤其明显），因此改为**全程跟随主题色**：

- 渐变从 `secondaryContainer`（也就是卡内 tonal 按钮的颜色）出发，向右下过渡
  到 `lerp(secondaryContainer, primary, 0.25)`；
- 绿色语义只保留在左侧的实心状态点上；标题/说明继续用 `onPrimaryContainer`；
- 停止态仍是中性灰（surfaceVariant 0.85 → 0.55），运行↔停止之间依旧用进度
  插值平滑过渡（4.9.119）。

一个坑：最初直接写成 `primaryContainer → secondaryContainer`，但在自定义主题
方案里这两个角色是**同一个颜色**，而 `FilledTonalButton` 用的也是
`secondaryContainer` —— 结果整张卡和三个按钮同色，按钮"消失"在底色里。
终点向 `primary` 压 25% 后，按钮在卡片中下部重新变成可辨认的浅色药丸。

**验证**：浅色 + 深色两种主题各截图确认（浅色 #D9E1F8 → #B4C1E0 的蓝紫渐变、
深色蓝灰渐变，绿点与按钮都可辨认）；单元测试 **317 条全绿**；装到模拟器与手机。

#### 4.9.121 提示文案精简（七种语言同步）

把界面里偏啰嗦的提示语统一改短，覆盖 20 条 key × 7 个语言目录：

- **首页**：引擎未运行 / 没有桌面分组 / 缺少媒体权限 三条警告（原来最长 59 字，
  警告卡占两行）以及运行 / 仅锁屏 / 已停止 三句状态说明；
- **设置行**：旋转适配、自动扫描与间隔、视频声音、视频播完再切、过渡效果、
  导出日志、日志隐私提示；
- **使用指南** 4 步；**系统壁纸提示**（解锁直换、视频/GIF 需要动态壁纸）。

文案只做减法：保留「怎么修」和风险提示（权限、隐私、占位图、仅锁屏），
删掉重复的限定语。例如首页引擎警告由「动态壁纸引擎未运行：定时切换仍可用
（静态壁纸模式），但双击切换不可用；如需动态效果，请在系统壁纸设置中选中
『壁纸切换』」（59 字）改为「引擎未运行：双击与视频动效不可用，定时切换仍
可用；请在系统壁纸设置中选择本应用」（40 字），在平板/手机上从两行降为一行。

批量替换用「key → 新文案」映射表 + 逐键校验（每个 key 必须唯一命中，否则
报错退出），七个 `strings.xml` 全部改完仍是合法 XML（544 条 / 文件），
UTF-8 无 BOM、CRLF 保持不变。

**验证**：单元测试 **317 条全绿**；模拟器（应用语言切到简体中文）截图确认首页
警告缩为一行、设置行说明变短；编译通过。

#### 4.9.122 选择器界面重构：选择文件夹 + 图片选择器

**「添加壁纸」入口**（`AddWallpaperDialog`）：四个 TextButton 选项改为 Miuix 选项
行 —— 强调色图标 + 文字 + 行尾箭头，整行可点、12dp 圆角按压高亮。

**选择文件夹对话框**（`FolderPickerDialog`，多选文件夹导入）：

- 搜索框换成 Miuix 填充式（`hiCardColor()` 底、无描边、14dp 圆角），不再是
  Material 描边输入框；
- 排序 chip 圆角 12dp；行高 52 → 56dp，缩略图 10dp 圆角；
- 选中底色由 `secondaryContainer` 改为**跟随主题强调色**（12% 透明），并用
  `animateColorAsState` 过渡；勾选框也用强调色；
- 空态改为紧凑版容器式（64dp / 20dp 圆角方块 + 30dp 强调色图标）；
- 确认按钮改为 tonal 药丸（14dp 圆角），标题用 titleLarge。

**图片选择器**（订阅文章 → 用这些图）：网格方块 8 → 12dp 圆角，选中态由
「黑色蒙层 + 白色对勾图标」改为 **2.5dp 强调色描边 + 强调色实心圆形角标 +
18% 轻压暗**；确认按钮「加入分组 (N)」改为 tonal 药丸。这与分组媒体网格、
（暂未接线的）美人图选择器共用同一套选中语言。

**美人图选择器**（`MeirentuPickerDialog`）同样统一了：圆形返回/关闭按钮 +
左对齐标题 + 「已选 N 张」药丸（点一下即清空）+ tonal 完成按钮；相册列表
改为无分割线的卡片行、行尾箭头；网格选中态与图片选择器一致；底部分页按钮
改 tonal 药丸。注意：全工程搜索确认该对话框**目前没有任何入口**（死代码），
这里只做样式统一，等在线源接线后即可用。

**验证**：单元测试 **317 条全绿**；模拟器截图确认 —— 选择文件夹对话框（选中
行出现强调色底 + 勾选、计数变为「已选 1」、确认按钮变「导入所选 (1)」）、
订阅文章图片选择器（两张图全部选中态为强调色描边 + 角标、加入分组药丸）；
新包已装到模拟器与真机。

#### 4.9.123 修复：选择文件夹长列表与操作行之间的大片空白

用户反馈（890 个文件夹时截图）：文件夹列表在对话框里只占了大约 6 行就结束，
下面一大片空白，然后才是「取消 / 导入所选」。

原因：列表被写死 `heightIn(max = 340.dp)`，而列表右侧的 `ListFastScroller`
用的是 `fillMaxHeight()` —— 它会把外层 Box 撑到对话框给的全部剩余空间，
Box 的高度因此是 `max(列表 340dp, 滚动条 撑高)`，多出来的部分就成了空白。

修复：去掉列表的 340dp 上限，让它用满对话框剩下的空间（对话框本身仍有 92%
屏高的上限，操作行始终贴着列表下方）；顺带给列表加 8dp 底部内边距，最后一行
不再紧贴边缘。短列表（本机 3 个文件夹）仍然按内容收缩，对话框保持紧凑 ——
这一点在模拟器上截图确认过。长列表的完整复现需要在真机上验证（模拟器的
MediaStore 由 adb 灌入，应用侧只可见少量媒体行，无法造出几百个文件夹）。

**验证**：单元测试 **317 条全绿**；编译通过；新包已装到模拟器与真机。

#### 4.9.124 修复：推次元等「@js: 分类 + `||` 备选规则」的源报 bad_url / 0 篇

用户反馈「推次元这个源地址无法访问」，设备上该源的 `lastResult` 是
`err:bad_url`。源地址本身正常（`https://a2cy.com/phone/home/` 返回 200），
问题出在规则引擎的两处兼容性缺口：

1. **分类地址是 JS**：该源 `sortUrl` 写成
   `正片::@js:'…/phone/list' + (page > 1 ? '/index_' + page + '.html' : '')`，
   而 `pageUrl()` 只会替换 `{{…}}`，不执行 `@js:`。整段 `@js:…` 被当成 URL
   交给端点校验 → `EndpointPolicy.INVALID` → `bad_url`。
   现在分类路径若以 `@js:` / `<js>` 开头，会以 `page` 为变量用 Rhino 求值
   （与 `header` / `ruleContent` 的处理一致），再走原来的分页替换。
2. **`||` 备选没有被拆**：`ruleTitle` 之类写成
   `h2 a@text||h3 a@text`，而 `RuleAlternatives` 只拆 `,`。整段进入规则引擎
   后会按 `@` 拆成 `["h2 a", "text||h3 a", "text"]`，中间那段选不到任何元素，
   取值变空 —— 列表页明明抓到 10 条，却因为标题/链接为空被逐条丢弃
   （日志 `page=1 … items=10` 但 `fetched=0`）。现在 `||` 在括号/引号之外也
   作为备选分隔符拆开，语义仍是「取第一个非空」。

另外在每页抓取后补了一行诊断日志（`page=N url=… bytes=… items=…`），这类
「抓到了但存不下来」的问题以后可以直接从日志判断卡在哪一步。

**验证**：单元测试 **322 条全绿**（新增 5 条：`@js:` 分类分页、纯路径分类、
无分类回退、真实列表片段解析、`||` 拆分边界）；模拟器上导入同一份源，刷新从
`ok:0:0` 变为 `fetched=20 new=20`，文章列表 20 条带缩略图；新包已装到用户设备。

#### 4.9.125 图片选择器：去掉网格下方的正文文字

订阅文章的图片选择器（「用这些图」）原来在网格下面还渲染一段文章正文
（`FeedParser.stripHtml(html)`，最多 140dp、可滚动），用户反馈「图片下面有
文字信息」。选择器只负责选图，这段正文现在整个移除；只有「还没加载到图片」
时才保留原来那句空态提示。同时删掉了只为这段正文准备的 `body` 计算。

**验证**：单元测试 **322 条全绿**；模拟器打开推次元文章 →「用这些图」，对话框
只剩标题、`文章图片 (N)` + 全选/清空、图片网格与底部按钮；新包已装到模拟器与
用户设备。

#### 4.9.126 图片选择器排版优化

在 4.9.125 去掉正文之后，再把选择器本身的排版收紧、把空间让给图片：

- **标题**用 `titleLarge`（22sp）而不是对话框默认的 24sp 大标题，并保持两行截断；
- **网格高度上限**从 46% 屏高（240–420dp）提到 52%（260–480dp）——模拟器实测
  可见网格从约 555px 提到 730px；
- **格子比例** 0.75 → 0.8（略矮），同样高度能多看到内容；格子间距 6 → 8dp，
  与分组网格 / 美人图选择器一致；
- **表头**：`文章图片 (N)` 左侧留白，右侧在已有选择时显示一枚强调色药丸
  「已选 N 张」（复用美人图选择器同一条文案），「全选 / 清空」改为紧凑文字
  按钮（内边距 8/4dp）；区块间距 10 → 12dp，底部按钮间距 4 → 8dp。

**验证**：单元测试 **322 条全绿**；模拟器截图确认（标题变小、已选药丸、两行
六格可见、底部三个按钮排布）；新包已装到模拟器与用户设备。

#### 4.9.127 图片选择器：窄屏不再折行，「确定」放到最右

4.9.126 的「已选 N 张」药丸在用户手机（393dp 宽）上把「文章图片 (14)」挤成了
两行（「文章图片」/「(14)」），底部三个按钮（确定 / 重新抓取 / 加入分组）也被
`AlertDialog` 的 FlowRow 折成上下两排。按用户要求重排：

- **表头单行**：`文章图片 (N)` 限一行 + 省略号；**全选 / 清空合并成一个按钮**
  （已全选时显示「清空」，否则「全选」）；「重新抓取」从底部移到表头做成
  48dp 图标按钮（a11y 触控目标），表头因此只有「计数 + 刷新 + 全选/清空」；
- **底部单行**：把「加入分组 (N)」放进 M3 的左槽（dismissButton）、「确定」放进
  右槽（confirmButton）——用户要求「确定」在最右边；两枚按钮在 393dp 下同排；
- 网格加 8dp 底部内边距，最后一行被裁切时不贴边。

**验证**：把模拟器临时改成 1080×2400 / 440dpi（≈393dp 宽，与用户手机一致）后
截图确认：表头一行、底部「加入分组（14）| 确定（最右）」一行；单元测试
**322 条全绿**；模拟器屏幕参数已还原，新包装到模拟器与用户设备。

#### 4.9.128 订阅 / 文章两页：去掉顶栏下方那条空白

用户反馈「订阅与文章间有空白，影响美观」（附订阅列表与文章列表两张截图）。
根因是两页各自在列表上方放了一整行操作：

- **订阅源列表**：`多选` 图标独占一行（右侧对齐），标题下面空出一条 **~70dp** 的带；
- **文章列表**：`登录 / 编辑` 一行 + 分类 chip 一行，标题到第一篇文章之间累计
  **~140dp** 空白。

改动：

- 订阅源列表的「多选」入口搬到 **TopAppBar 的 actions**（app 层维护
  `sourceSelectionRequest` 计数，列表页消费后回调清零，避免返回列表时又自动
  进入多选）；
- 文章列表的 `登录 / 编辑` 同样搬进顶栏 actions（它们本来就属于「这个源」），
  列表页那一行整体删除；分类 chip 的上下内边距 8 → 6dp，列表顶部内边距 8 → 4dp；
- 顺带修掉公共多选栏第一行紧贴屏幕边缘的问题（左右各加 8dp）。

**验证**：模拟器按 1080×2400 / 440dpi（≈393dp 宽）复现用户机型 —— 订阅列表
标题下方直接是卡片，文章列表标题下方直接是分类 chip；顶栏「多选」图标点击后
正常进入多选模式（✕ / 全选 / 已选 0/2 一行）；单元测试 **322 条全绿**；模拟器
屏幕参数已还原，新包装到模拟器与用户设备。

#### 4.9.129 登录 / 编辑返回时回到打开它的文章列表

用户反馈：在某个源的文章列表里点「编辑 / 登录」，退出后直接退到了订阅的源列表
（`onDone = Screen.Subscriptions`），丢掉了刚才读的源。登录/编辑本来就是从
「这个源的文章列表」打开的，返回目标应该是它：

- 顶栏点「登录 / 编辑」时先记下 `returnToSourceId = screen.sourceId`；
- 新增 `leaveSourceEditor()`：有记录时回到 `SubscriptionArticles(id)`，没有时
  才回源列表；顶栏返回箭头、系统返回手势、两个编辑页自身的 `onDone` 共用它；
- 回到文章列表后，文章的 `LaunchedEffect(sourceId, source?.rawJson)` 会因为
  rawJson 变化重新拉取，正好把编辑后的规则立即生效。

**验证**：模拟器（1080×2400 / 440dpi）上走「订阅 → 推次元 → 编辑 → 顶栏返回」，
回到的是同一个源的文章列表（分类 chip 与 20 篇文章都在）；单元测试 **322 条
全绿**；模拟器屏幕参数已还原，新包装到模拟器与用户设备。

#### 4.9.130 文章列表加载时不再出现两个「正在加载」

用户截图反馈同屏出现两个加载提示：一个是分类 chip 下方的行内提示，另一个是
空列表时居中的加载态 —— 两者都由 `loading` 驱动，首次进入某个分类时会同时
显示。

改动：行内提示加条件 `loading && articles.isNotEmpty()`，只在「已经有内容、
又在重新拉取」时出现；列表为空时统一由居中的那个负责。首次加载/切换分类因此
只剩一个加载态。

**验证**：模拟器（1080×2400 / 440dpi）上切换「写真」分类，截图确认只有居中
一个「正在加载「写真」…」；单元测试 **322 条全绿**；模拟器屏幕参数已还原，
新包装到模拟器与用户设备。

#### 4.9.131 订阅源加载失败时显示具体原因 + 重新抓取

用户要求：加载失败要说明原因。原来的文章列表只会显示「这个分类还没有内容」，
分不清「真的没内容」和「抓取失败」。

改动（`SubscriptionArticlesScreen`）：

- 读取源上的 `lastResult`（`err:<reason>`，由 `RssSync` 写入），用与订阅卡片
  相同的 `OnlineSourceRules.decodeResult` + `onlineErrorText` 映射成本地化原因
  （网络不可用 / 地址格式不正确 / 解析失败 / 需要 HTTPS / 证书校验失败 …）；
- **列表为空且失败**：完整错误态 —— 容器式图标 + 「刷新失败：<原因>」+
  「重新抓取」按钮（重新触发 `LaunchedEffect`，走一次真实抓取）；
- **已有内容但本次刷新失败**：只在列表上方加一行「刷新失败：<原因> · 重新抓取」，
  不打断阅读、也不清掉旧内容。

**验证**：在模拟器上把测试源的地址改成不存在的域名（改完会还原），刷新结果
`err:network`，界面显示「刷新失败：网络不可用」+「重新抓取」；单元测试
**322 条全绿**；测试源已还原为 a2cy.com，新包装到模拟器与用户设备。

#### 4.9.132 切换间隔对话框：11 行单选 → 可换行 chip

定时切换与锁屏定时切换共用同一个 `IntervalPickerDialog`。原来 11 个预设间隔
各占一整行（单选 + 12dp 内边距），对话框被撑到几乎整屏（用户反馈「占用整个
屏幕」）。改成与设置页「切换模式 / 缩放模式」同一套控件：

- 预设间隔用 `SettingsOptionChip` 放进 `FlowRow` 自动换行 —— 手机上 4 行放完
  11 个选项，实测对话框高度从接近整屏降到约 55%；
- 选中项即点即生效并关闭（保持原行为）；
- 「自定义时间」的秒数输入框换成填充式（无描边、14dp 圆角，与选择文件夹的
  搜索框同款），确定按钮改 14dp 圆角药丸；标题用 titleLarge；
- 整段内容仍保留 `verticalScroll`，小屏 / 横屏也不会被挤出对话框。

**验证**：模拟器按 1080×2400 / 440dpi（≈393dp 宽）截图确认新的 chip 布局与
自定义输入行；单元测试 **322 条全绿**；模拟器屏幕参数已还原，新包装到模拟器
与用户设备。

#### 4.9.133 订阅导入支持直接粘贴订阅地址

用户反馈：把订阅地址（`https://ycoo.net/.../xxx.json`）粘进「导入阅读订阅源」
后导入失败。地址本身正常（HTTP 200，返回 Legado 源 JSON 数组），问题在应用：
导入只识别「JSON 文本 / `legado://` 分享链接（内联或 src= 远程地址）/ 选择的
文件」，**不认裸的 http(s) 地址**，于是判成内容格式错误。

改动：

- `LegadoImport.remoteUrlToFetch()`：统一给出「需要先下载的地址」——既包括
  `legado://…?src=<url>` 里的远程地址，也包括用户直接粘贴的 `http(s)` 地址；
- `WallpaperViewModel.importLegadoSources()` 改用它：解析不出源时下载该地址再
  解析（阅读的「导入网络文件」就是这么做的）；
- 导入对话框的输入框换成填充式（无描边、14dp 圆角），提示文案补上「订阅地址」
  （七种语言同步）。

**验证**：单元测试 **323 条全绿**（新增 `bareSubscriptionUrlIsDownloadedAndParsed`：
裸地址会被识别、前后空白被裁掉、JSON/普通文本不会被误判）；端到端在模拟器上
起了一个本地 HTTP 服务托管测试源 JSON，应用里粘贴 `http://10.0.2.2:8765/src.json`
→ 导入成功（源列表出现「URL导入测试」，测试数据与本地服务随后已清理）。

#### 4.9.134 识别阅读的「JS 源 / 加密源」并说明原因

用户导入 yckceo 的 `https://www.yckceo.com/yuedu/rss/json/id/376.json` 后提示
「返回内容无法解析」。抓包分析（该站点屏蔽境外 IP，经 CORS 代理取回）：

- 该 JSON 是标准 Legado 数组，能正常导入；
- 但源本身（XH发布页）**没有 `ruleArticles`**：规则由远程混淆 JS 库在运行时
  生成 —— `header` 是 `<js>eval(String(getJs()));</js>`，`jsLib` 指向一份 5 万
  字节的混淆脚本，另有 25KB 的 `variableComment` 加密载荷（阅读的「加密源」）。

本应用没有这套运行时，于是把它当普通源抓取，最后以 `err:parse` 收场。改动：
`LegadoRss.requiresJsRuntime()`（声明了 `jsLib` 且解析不出静态规则）+ `RssFetcher`
在抓取前抛出 `unsupported_js`，七种语言新增
`online_error_unsupported_js`（「该源依赖阅读的 JS 库（本应用暂不支持）」），
由订阅卡片与文章列表的错误态显示。

**验证**：单元测试 **326 条全绿**（新增 3 条：JS 源判定、带 jsLib 但仍有静态
规则时不算、普通源不算）；把该源的真实 JSON 灌进模拟器数据库后刷新，结果为
`err:unsupported_js`，界面显示「刷新失败：该源依赖阅读的 JS 库（本应用暂不
支持）」+「重新抓取」（测试源随后已删除）；新包装到模拟器与用户设备。

#### 4.9.135 单 URL / 网页型源的浏览器兜底

用户导入「Pixiv 书源」卡片（`https://pixivsource.pages.dev`）报「返回内容无法
解析」。该 JSON 里**没有任何规则字段**（连 `ruleArticles` 都没有），`sourceUrl`
指向的是一个 VitePress 文档站（HTML）——阅读里这类「单URL」源就是直接当网页
打开的，而我们的应用把它当订阅源去解析，自然失败。

改动：错误态里当原因是 `parse`（内容不是 feed）或 `unsupported_js` 时，多一个
「用浏览器打开」按钮 —— 用应用内置的全屏浏览器（`RssWebScreen`）打开源的地址，
既能浏览站点，也能用同一套「用这些图」收集图片。七种语言新增
`rss_open_in_browser`。

顺带记录 PixivSource 项目的订阅文件（`btsrk.json`，9 条）构成：3 条 JS 源
（Pixiv / Linpx / 兽人小说站，都带 `jsLib`，属于 4.9.134 的「暂不支持」），
其余 6 条（Pixiv 书源卡片 / 一键导入 / 兽人控游戏索引 / 兽人游戏库 / 兽展日历 /
兽聚汇总）都是网页型，现在都能用浏览器兜底打开。

**验证**：单元测试 **326 条全绿**；把「Pixiv 书源」卡片灌进模拟器数据库，刷新
得到 `err:parse`，错误态出现「用浏览器打开」，点击后内置浏览器成功加载该文档站
（截图留档，测试源随后已删除）；新包装到模拟器与用户设备。

#### 4.9.136 单 URL / 网页型源：点卡片直接进浏览器

用户要求：网页型源不要再当订阅源解析，点开就用全屏浏览器打开。

判定（`LegadoRss.isBrowseOnly`）：`singleUrl == true`、没有静态规则
（`parseRules` 为空）、也没有 `jsLib`（JS 源仍走「暂不支持」提示）。原始 JSON
同时兼容对象与 `[ {…} ]` 两种形态（`rawFields`）。

- 订阅源卡片：状态行显示「网页型源 · 点卡片用浏览器打开」，且不再用红色报错；
- **点击卡片直接 `rssWebArticle = <该源地址>`**，走应用内置全屏浏览器
  （`SubscriptionScreen.onOpenBrowser`），不再进入文章列表、不做解析；
- `RssSync.refresh` 对这类源直接跳过（`Report(true, 0, "browse")`），后台定时
  刷新不会再把它们标成「更新失败」。

**验证**：单元测试 **329 条全绿**（新增 3 条：卡片判定为网页型、普通 feed /
JS 源不算、带静态规则的 singleUrl 源不算）；模拟器上导入「Pixiv 书源」卡片，
卡片显示网页型提示、点卡片直接打开该文档站（测试源随后已删除）；新包装到
模拟器与用户设备。

#### 4.9.137 h视频这类「JSON API + {{规则}}」源：解析对齐阅读

用户反馈 h视频（`https://api.sgapiaba.xyz`，整源走 JSON API）解析结果和阅读
不一样。定位到规则引擎的三处差异（都是通用问题，不止这一条源）：

1. **`{{}}` 里只认变量/JS，不认规则**：源里写着
   `ruleLink = /api/videoplay/{{$.id}}?uuid=1`、`ruleImage = {{$.coverbase64.url}}`、
   `rulePubDate = 📆{{$.updated_at## .*}}  ⏱️{{$.playtimes}}` —— 阅读会在 `{{}}`
   里按规则取值，我们取不到就把字面量留下，链接于是变成
   `/api/videoplay/{{$.id}}?uuid=1`。新增按「变量 → 规则 → JS」求值的
   `resolveBracedExpressions()`。
2. **JSONPath 选中的条目再走嵌套 JSONPath 会失败**：条目是 Map，`toString()`
   不是 JSON，`$.title` 之类全部解析为空。`analyzeJson` 现在对 Map/List 先
   `Json.encode` 再交给 JsonPath。
3. **替换后是字面值时被当选择器**：`{{$.playtimes}}` → `3`、
   `📆{{…}} ⏱️{{…}}` → 一整行文本，被当成 CSS 选择器解析后返回整个条目对象；
   `/api/videoplay/1?uuid=1`、`/c/1.jpg` 这类字面地址被当成 XPath。新增
   「模板无规则骨架 → 直接当字面值」与「地址字面值」两条判定
   （`templateHasRuleMarkers` / `looksLikeUrlLiteral`）。

源里的 `{{v=source.getVariable();…}}`（搜索分类）走的 JS 分支本来就支持
`source.getVariable/setVariable`，无需改动。

**验证**：新增 `VideoSourceRulesTest` 三条（91porn视频、Rule34视频、h视频的
JSON API 规则：`$.rescont.data[*]` 列表、`{{$.id}}` 链接、`{{$.coverbase64.url}}`
封面、`📆{{…## .*}}` 日期、`$.rescont.next_page_url` 翻页），单元测试
**332 条全绿**；新包装到模拟器（用户设备当时未连接，连上后再装）。

#### 4.9.138 修复：h视频刷新「未知错误」（Android ICU 正则 + 规则降级）

用户真机日志（`cache/logs/runtime.log`）给出了真实原因：
`PatternSyntaxException: Syntax error in regexp pattern near index 13`，出错的模式
正是 4.9.137 引入的骨架检测 `\{\{[\s\S]*?}}` —— **未转义的 `}}` 桌面 JVM 容忍，
Android 的 ICU 正则引擎直接抛异常**，所以单元测试全绿而真机必崩；更糟的是它
没被兜住，整次刷新因此报 `err:unknown`。

修复：

1. 骨架检测改用 `RuleSplitter.innerRule`（自带的括号匹配）去掉 `{{…}}`，
   不再依赖正则引擎；
2. `firstValue` / `firstElements` / `firstValueJoined` 对**每条备选规则**加
   try/catch：单条规则出问题只当「这条取不到」，其余备选继续 —— 与阅读的逐条
   降级一致，任何单条规则都不该让整次刷新失败；
3. `RssSync` 的失败日志补上前 6 帧堆栈，这类没有上下文的异常以后能直接定位。

**验证**：模拟器导入该源真实 JSON，刷新 `ok:40:0`（两页 40 条，无报错），
单元测试 **334 条全绿**；测试源已清理。用户设备在安装前又断开了，连上后补装。

#### 4.9.139 与阅读的规则一致性审计（探针测试 + 阅读源码对照）

用户要求核对「本 App 的订阅源解析规则和阅读还有什么不同」。做法分两条线：

1. `LegadoConformanceProbeTest` —— 40 多条常见写法（选择器、索引、
   `&&/||/%%`、JSONPath、XPath、`{{}}`、`@js:`、`<js>`、`java.*` 助手、
   `##替换`、请求选项…）逐条跑一遍并打印实际结果，作证据清单；
2. 直接对照阅读源码（`AnalyzeRuleCore` / `AnalyzeByJSoup` / `AnalyzeByXPath`
   / `UrlOptionSerializer` 的当前实现），确认逐段语义。

**已对齐**：`class./id./tag.`、裸 CSS、`@css:`、索引（`.N` / `!N` / `[n]` /
`[a:b]` / `[::step]`）、`&&/||/%%`、`text/textNodes/ownText/html/all/属性`、
`:contains/:matches`、JSONPath（含过滤、递归、`@json:`）、`@xpath://…`、
`##替换`（含 `##first`）、`{{变量}}/{{JS}}/{{规则}}`、`@js:`、`<js>`、
`java.getString/base64Decode/md5Encode/timeFormat`、`source.setVariable/getVariable`、
`@webjs:`（真机走隐藏 WebView，单元测试环境按 null 跳过）。

**本轮修掉的四条真差异**：

1. **`…@href@js:…` 这类「取值后接 JS」链**。阅读 `splitSourceRule` 先把 `@js:`
   拆出去，剩下的 `class.item@href` 交给 jsoup 分析器，其中**只有最后一段**
   是取值（`getResultLast`：`text`/属性名…）。我们此前把中间段 `href` 当成
   CSS 选择器，导致 JS 里的 `result` 是空串（源里用来给链接追加请求选项的
   `',{"headers":…}'` 因此丢掉了地址）。现在「JS/JSON/XPath 之前的最后一段」
   按取值处理（纯属性名或 `text` 家族），形如 `class.x` 的才继续当选择器。
2. **XPath 绝对路径与 `//a/@href` 属性写法**。老版阅读用 JXDocument，`/html/…`
   直接语法报错；新版换成 jsoup `selectXpath` 并自行拆 `/@`，两样都支持。
   现在两种都可用：JXDocument 返回空/抛错时回退 jsoup，`/@attr` 先选元素再取属性。
3. **网页编码嗅探**。阅读按「响应头 charset → `<meta charset>` → UTF-8」解码，
   我们此前只用 OkHttp 的 `body.string()`（只认响应头），GBK/GB2312 站点会乱码。
   新增 `ResponseCharset`：`httpGet` / 离线缓存回退 / `fetchTextSync` /
   JS 的 `java.get/post` 全部走同一条解码链。
4. **`@@` 转义**。阅读把 `{{@@…}}` 的内层按规则解析（`isRule`：以 `@` 开头即规则，
   `@@` 剥掉后照常解析）。好壁纸的正文规则 `{{@@tag.img@html}}` 以前被我们原样
   当文本返回，列表里于是显示出一行规则原文；现在正确取到 `<img …>` 标记
   （列表预览经 `stripHtml` 后自然隐藏）。

**仍然不同（有意保留或暂不支持）**：

1. **JS 源 / 加密源（`jsLib` + `getJs()`）**：只识别并提示「该源依赖阅读的 JS
   库（本应用暂不支持）」，不执行；静态规则源不受影响。
2. **请求选项 `,{headers:…}`**：阅读把它交给 HTTP 层（附加请求头/Cookie），
   我们目前只做到「剥离后正确取值」。文章页在 WebView 里打开，附加头暂无处可用。
3. **阅读器专用字段**：`coverDecodeJs`、`injectJs`、`style`、`contentWhitelist` /
   `contentBlacklist`、`shouldOverrideUrlLoading`、`concurrentRate`、`articleStyle`、
   `loadWithBaseUrl`、`enableDangerousApi` 等未实现（与排版/阅读体验相关，
   不影响列表解析）。

**验证**：单元测试 **342 条全绿**（335 + `ResponseCharsetTest` 5 条 +
`LegadoRuleEngineTest` 2 条断言版回归）；
探针清单留在 `LegadoConformanceProbeTest` 里，以后改引擎可随时重跑对照。
新包已装到模拟器（冒烟：首页、订阅页、好壁纸文章列表都正常，列表里的规则原文
已消失）。

#### 4.9.140 请求选项真正生效 + 阅读器字段落地

用户要求把上一条清单里剩下的两批做完（`待办` 里的第 2、3 项）。

**一、链接请求选项 `,{…}` 真正生效**（阅读 `AnalyzeUrl` + `UrlOptionSerializer`）

1. 新增 `UrlOptions`：按阅读 `AppPattern.urlParamPattern`（逗号后紧跟 `{`）拆出
   地址与选项；支持 `headers` / `method` / `body`；严格 JSON 解析失败时按阅读的
   宽松解析兜底（源里常见的 `,{headers:{Referer:'…'}}` 单引号、不带引号的键）。
2. `LegadoRss.buildRequest`（从 `httpGet` 提出来的组装步骤）把选项并进请求：
   选项请求头**覆盖**源 `header`（阅读里 URL 选项优先级更高），`method`/`body`
   可把这次抓取变成 POST；`java.ajax` / `java.get/post` 走同一条路径。
3. 链接选项里的请求头随文章落库：`rss_articles.requestHeaders`（库版本 13 → 14，
   `MIGRATION_13_14`），文章页 WebView 加载、正文抓取、封面 Coil 抓取、图片下载
   都带上它 —— 图床/正文页要求 `Referer` 时才不会再 403。
4. `concurrentRate`：`ConcurrentRate` 复刻阅读 `ConcurrentRateLimiter` 的双模式
   （`1000` = 请求间隔 + 同时只跑一个；`3/1000` = 每 1000ms 最多 3 次），
   挂起版给 OkHttp、阻塞版给 JS 的 `java.get/post/ajax`。

**二、阅读器字段**

| 字段 | 实现（对齐阅读 3.x `ReadRssActivity`） |
|------|------------------------------------------|
| `style` | 正文 HTML 前拼 `<style>…</style>`（同阅读 `clHtml`） |
| `injectJs` | 页面加载完 `evaluateJavascript` |
| `contentBlacklist` / `contentWhitelist` | `shouldInterceptRequest` 按「前缀或正则」拦截 / 放行，拦截回空响应 |
| `shouldOverrideUrlLoading` | JS 规则、绑定 `url`，返回 `true`/`1` 即拦下这次跳转 |
| `loadWithBaseUrl` | `false` 时 `loadDataWithBaseURL(null, …)`，`true` 保留 baseUrl |

不做并说明原因的两个字段：`coverDecodeJs`（阅读对订阅源只做字段映射，
3.x / 新版的订阅列表都没有实际调用）、`articleStyle`（阅读器主题，本应用没有
主题系统）。`enableDangerousApi` 同理不适用（源 JS 不接触危险 API）。

**验证**：单元测试 **357 条全绿**（新增 `UrlOptionsTest` 6 条、
`ConcurrentRateTest` 3 条、`LegadoRequestOptionsTest` 6 条 —— 后者直接断言
`buildRequest` 产出的 OkHttp 请求：URL 已剥离选项、选项头生效且覆盖源 header、
POST + body 生效）。新包已装到模拟器并刷新「好壁纸」源验证列表正常。

#### 4.9.141 阅读的「JS 源 / 加密源」运行时

上一条清单里最后一项：`jsLib` + `getJs()` 的 JS 源。阅读的实现分散在三处
（`SharedJsScope.getScope` 下载并 eval jsLib、`BaseSource.evalJS` 提供绑定、
源自己的 `header` 规则执行 `eval(String(getJs()))`），新增 `LegadoJsSource`
把整条链复刻出来：

1. **jsLib 两种形态**：内联 JS，或 `{"名称":"https://…/jsLib.js"}` —— 后者按
   URL 下载（`cache/legado_js/<md5>`，同阅读的 ACache），解析结果按源缓存。
2. **`source` 是一份可写字段表**：源的原始字段（`sourceUrl`/`header`/
   `variableComment`…）先铺进去，再挂上 `getKey()`、`getVariable()/setVariable()`、
   `get/put`、`getLoginInfo/putLoginInfo/putLoginHeader`；规则脚本写回
   `source.ruleArticles` / `ruleTitle` / `ruleLink` / `sortUrl` … 后由
   `LegadoRss.rulesFrom()` 读回成正式规则。
3. **绑定**：`java` = 本应用的 JS 助手（`md5Encode`、`base64*`、`ajax`、
   `get/post/head`、`createSymmetricCrypto` …），`cookie` = 持久 CookieJar，
   `cache` = `get/put/delete`（内存 + 磁盘）。加密源常用的
   `aesBase64DecodeToString` / `hex*` / `createSymmetricCrypto(...).decryptStr()`
   在 `SymmetricCrypto` 里实现（密钥与 IV 按 UTF-8 取字节，同阅读
   `JsEncodeUtils`）。
4. **类访问白名单**：JS 源的库会 `new JavaImporter(Packages.okhttp3)`，
   因此作用域保留 Rhino 的 Java 包，但用 `ClassShutter` 只放行
   `okhttp3.* / okio.* / org.json.*` 与一批 `java.util / java.net / java.security`
   工具类 + 本应用的 JS 助手；`java.io`、`Runtime`、反射、类加载器全部不可见
   （`dangerousJavaClassesStayInvisible` 有回归用例）。这是为了跑第三方 JS 源
   必须付出的取舍，作用域只服务于 JS 源解析。
5. **接线**：`LegadoRss.rulesFor()`（suspend）先静态规则、再 JS 运行时，替换了
   刷新 / 正文 / 分类三条路上的 `parseRules()`；拿不到规则仍报
   `unsupported_js`，文案改为「该源的 JS 规则没能取到（jsLib 下载或脚本执行失败）」。

**验证**：单元测试 **364 条全绿**（新增 6 条：jsLib 生成规则、`source.getKey`/
`cache` 可用、无 header 时自动调用 `getJs()`、jsLib 下载失败软着陆、
危险类不可见、静态源不进入运行时）。另有 `RealJsSourceProbeTest`（默认 @Ignore）
用真实 XH发布页 jsLib 验到「下载 jsLib → `getJs()` → 拉 de.js → 解密 payload」
这一段全部走通；payload 里剩余的规则数据存在源 JSON 的加密字段里，而 yckceo
从开发网络不可达，拿不到完整源做端到端验证 —— 用户设备上导入真实源即可确认。
调试时设 `WS_JS_DEBUG=1` 会把 payload 与读回的字段打到 stderr。

#### 4.9.142 模拟器实测：数组形式的源 JSON 修好了

在模拟器上把仓库里的三个视频源（数组形式 `[{…}]`）、一个 JS 源、btsrk 规则订阅
一起导入实测，发现并修掉一个真 bug：

**`rawMap()` 只认对象形式**，而用户手上和导出工具的 JSON 常常是数组
（`[{…}]`：仓库里的 `rssSource_h视频.json` / `91porn视频` / `Rule34视频`、
yckceo 分享链接都是这种）。这类源会被当成普通 RSS，抓回来的 JSON 交给
FeedParser 自然解析不了 —— 卡片显示「刷新失败：返回内容无法解析」，规则本身
其实完全正常。修复：

- `LegadoRss.sourceFields()` 统一处理两种形态（对象取本身、数组取第一条），
  `parseRules` / 抓取 header / 源编辑器 / 登录字段读取全部改走它；
- 源编辑器保存时按第一条对象合并（数组包装不保留，字段不丢）；
- 回归用例：`VideoSourceRulesTest.arrayFormSourceJsonIsParsedLikeTheObjectForm`。

**模拟器实测结果**（2026-10-04，Android 14 emulator）

| 源 | 结果 |
|----|------|
| h视频（数组形式） | ✅ `ok:40:0`，两页 40 条，`$.rescont.next_page_url` 翻页生效，分类 chips 正常 |
| JS 源测试（jsLib + `getJs()` 运行时生成规则） | ✅ `ok:12:0`，规则由脚本生成后正常抓取列表 |
| btsrk-Pixiv / Linpx / 兽人小说站 | ✅ 正确识别为「网页型源，点卡片用浏览器打开」 |
| 好壁纸（对象形式，CSS 规则） | ✅ `ok:12:0` |
| 91porn视频 | 规则与分类解析正常（chips 正确），站点从当前网络不可达 → `err:network` |
| Rule34视频 | 同上，`rule34video.com` 连接超时（环境网络问题，非规则问题） |

**验证**：单元测试 **366 条全绿**（新增数组形式回归 1 条 + btsrk 网页型识别 1 条）。

**挂上代理后的复测**（应用内 `rss_proxy = 10.0.2.2:7890`，模拟器自身也设了系统代理
以便 WebView 走同一条线）：

| 源 | 结果 |
|----|------|
| 91porn视频 | ✅ `ok:24:0`，分类胶囊 + 缩略图 + 标题正常 |
| Rule34视频 | ✅ `ok:47:0`（两页：`latest-updates/2/` 翻页生效） |
| 推次元（a2cy） | ✅ `ok:20:0`（两页 `index_2.html`），之前一直是「网络不可用」 |

结论：三个视频源 + 推次元的规则解析全部正常，之前的失败只是网络可达性；
应用内代理设置（`rss_proxy`）对订阅抓取生效，WebView 走系统代理。

**真机复测**（小米 25102RKBEC / Android 17，`versionName 1.1`，11:28 装机后）：

| 源 | 结果 |
|----|------|
| h视频 | ✅ `ok:40:0`（数组形式修复前在真机同样报「返回内容无法解析」） |
| 推次元（a2cy） | ✅ `ok:20:0` |
| 美人图 / meirentu.club | ✅ `ok:60:0` |
| cosplaytele | ✅ `ok:66:0`（此前「连接超时」，挂上 VPN 后正常） |
| 3w.8012359.xyz / xiurenai / mtldss / xiurendao / misskon / everia / xiuren.biz 等 | ✅ `ok:24~72:0`（少数 `ok:0:0` 表示本轮没有新增，属正常） |
| Pixiv 书源 | ✅ 显示「网页型源 · 点卡片用浏览器打开」（旧的 `err:parse` 是历史记录） |

真机上 h视频 的文章列表、正文与播放器（m3u8 播放、已收集 1 张）都跑通了。

#### 4.9.143 设置页去重 + 多选项统一成「当前值 + 下拉」

用户贴了 HyperIsland 的「其他」设置页截图，要求：多选项按这个形态重构，重复的设置删掉。

**删掉的重复**：

1. 主设置页里 6 个**从未被触发**的对话框（切换间隔 / 锁屏间隔 / 主题色 / 语言 /
   扫描间隔 / 悬浮按钮颜色）——这些设置早就搬进各自的子页面，主页面再留一份
   等于同一设置两处入口，改起来容易两边不一致；
2. 「切换方式」页里与主设置页重复的「悬浮切换按钮」开关：按原设计注释，
   总开关只留主界面（最常用），点进「悬浮按钮」只管外观；
3. 因此不再被引用的 `AutoScanIntervalDialog`、`LanguagePickerDialog`。

**多选项统一形态**（与截图一致：行右侧显示当前值 + 下拉箭头，选项就在这一行
下面展开、选中项打勾；见 `HiOptionPickerRow`）：

| 设置 | 原来 | 现在 |
|------|------|------|
| 切换间隔 / 锁屏间隔 | 「修改」→ 全屏对话框（chip 列表 + 秒数输入） | 下拉面板（10 秒 ~ 24 小时 + 「自定义…」打开秒数输入框） |
| 文件夹扫描间隔 | 「修改」→ 单选对话框 | 下拉面板（1/6/12/24 小时） |
| 语言 | 整行点击 → 语言对话框 | 下拉面板（跟随系统 + 各语言，行右侧显示当前语言） |

新增 `HiOptionPickerRowOf`（选项文案已落地的版本）：语言名来自系统 Locale，
没有对应的 string 资源，`HiOptionSpec` 装不下。

顺带把主界面那一节的标题从「悬浮按钮外观」改成「悬浮按钮开关」——里面只有
一个开关，标题和内容对不上（7 个语言一起改）。

**验证**：`HiOptionLogicTest` 新增两条 —— 间隔表 key 回环 + 自定义值归一化到
「自定义…」、扫描间隔表回环 + 未知值落到 1 小时；全套单元测试 **451 条全绿**。
模拟器逐页核对：切换方式页「切换间隔 1 分钟 ⌄」（点开面板在行下面展开、
当前项打勾）、文件夹扫描页「扫描间隔 24 小时 ⌄」、外观页「语言 / 主题模式
跟随系统 ⌄」，主界面只剩一个悬浮按钮开关（切换方式页里那份已删）。

#### 4.9.144 开关归位：悬浮按钮开关进「切换方式」，间隔改回弹窗

用户按实际使用习惯又调了一轮布局：

1. **悬浮按钮开关挪进「切换方式」**（紧挨双击切换），主设置页那一节删掉 ——
   开关和它影响的切换方式放在一起；「外观 / 颜色 / 文字 / 图片」仍在
   「设置 → 悬浮按钮」子页。
2. **「切换方式」里的「悬浮按钮」外观入口删掉**：同一页既有开关又有外观入口
   会让人以为入口是重复的；外观只从设置主界面进。
3. **切换间隔 / 锁屏间隔改回弹窗**（预设档 + 「自定义」秒数输入）：行内下拉
   放不下自定义输入，弹窗一次看全。文件夹扫描间隔保持行内下拉不变。
4. **取消「解锁切换」的提示词**（`hint_unlock_static_mode`）：开启解锁切换时
   不再弹「静态模式下解锁直接换图」，7 个语言的字符串一并删除。
5. 顺带清掉搬走后遗留的代码：主设置页里未使用的悬浮按钮图片选择器、
   `SwitchMethodsScreen` 的 `onOpenButtonAppearance` 回调参数。

**验证**：单元测试 **450 条全绿**（间隔面板那两条测试随下拉一起删掉，其余不变）；
模拟器核对：设置主界面无「悬浮按钮开关」节、切换方式页有「悬浮切换按钮」且点
「切换间隔」弹出预设 + 自定义对话框。

#### 4.9.145 分组里点图片不弹系统动态壁纸：MIUI「动态壁纸服务」+ 菜单丢项

用户反馈两件事：「分组里的图片点击不弹出系统动态壁纸」以及「无法设置为壁纸」。
真机（Redmi 平板 25053RP5CC / HyperOS）排查后是两个互不相关的回归，一起修掉。

**1. 点击图片后系统界面 20ms 就自己关了（不是应用闪退）**

真机 logcat（`setAsLiveWallpaper: id=1221 target=BOTH` → 立刻回到
`MainActivity`）里，系统选择器只留下一行警告：

```
06:03:43.954  7219  7219 W CHANGE_LIVE_WALLPAPER: No permission to change wall paper
06:03:43.955  wm_on_create_called: LiveWallpaperChange
06:03:43.973  wm_on_destroy_called: LiveWallpaperChange
```

把设备上的 `LiveWallpapersPicker.apk` 拉下来用 `apkanalyzer dex code` 反编译
`LiveWallpaperChange` 得到判定逻辑（`withoutChangePermission()` / `init()`）：

```java
// 系统包直接放行，其余包必须 appOps 10045 == MODE_ALLOWED，否则 finish()
int mode = appOps.checkOpNoThrow(10045, appInfo.uid, packageName);
return mode != MODE_ALLOWED;
```

`10045` 是 MIUI 私有的 app-op「动态壁纸服务」（权限管理 → 其他权限 → 设置相关），
`appops get com.wallpaperswitcher 10045` 当时返回 `ignore`；`appops set ... 10045 allow`
后点击图片立刻恢复正常（`LiveWallpaperChange` 停留在预览页）。重装应用会拿到新
UID，这个开关跟着掉回默认拒绝 —— 用户上一次「另一个 AI 改坏了」正是重装之后，
但 `startActivity` 本身成功，应用侧完全看不到失败，所以既没有报错也没有提示。

修法：

- 新增 `engine/LiveWallpaperPermission`（纯判定核心 `isBlocked()` 带单测）：
  仅当确实运行在 MIUI/HyperOS（`ro.miui.ui.version.name` / 厂商为 Xiaomi）且
  app-op 读得出来且不等于 `MODE_ALLOWED` 时才判定被拦；其它 ROM、隐藏 op、
  读取抛异常一律按「允许」，不会误伤正常设备。
- `setAsLiveWallpaper()` 在**移动任何游标之前**检查：被拦时只发
  `liveWallpaperBlocked` 事件并返回（不再写 `LAST_IMAGE_ID` /
  `MANUAL_PICK_*`，也不会留下 pending preview pick）。`setImageAsWallpaper()`
  的动态壁纸分支（无引擎时视频/GIF）同样走这把闸门。
- UI（`WallpaperSwitcherApp`）收到事件后弹对话框说明原因，按钮「去开启」用
  `miui.intent.action.APP_PERM_EDITOR` → `PermissionsEditorActivity`
  （`extra_pkgname`）打开该应用的权限页，退回「应用详情」作为兜底；用户在那里
  点「其他权限 → 动态壁纸服务 → 始终允许」即可。
- `launchLiveWallpaperPicker()` 改成返回值：两个 intent 都起不来时发
  `toast_picker_unavailable`，不再静默。

**2. 三点菜单少了「设为壁纸」**

`d8459f9`（设置页改版那笔）把九宫格菜单里的「设为壁纸」项删掉了，只剩「删除」，
但 `onSetWallpaper` 参数、`previewImage` 状态和 `WallpaperPreviewDialog`
都还在 —— 预览对话框成了永远打不开的死代码，用户自然「无法设置为壁纸」。
菜单项已恢复（点击图片仍然按设计走系统动态壁纸界面，静态设置走菜单）。

**验证**：单元测试 **454 条全绿**（新增 `LiveWallpaperPermissionTest` 5 条 +
7 语言字符串齐全由 `LocaleResourcesTest` 把守）；真机回归：

| 设备状态 | 操作 | 结果 |
|---|---|---|
| `appops get ... 10045` = `ignore` | 点分组里的图片 | 弹「需要开启动态壁纸服务」对话框，不移动游标 |
| 同上 | 点「去开启」 | 打开 MIUI 权限页（`PermissionsEditorActivity`） |
| 在权限页 其他权限 → 动态壁纸服务 → 始终允许 | （appops 变 `allow`） | — |
| `allow` | 点图片 | 系统动态壁纸预览正常打开，不再闪退 |
| `allow` | 三点 → 设为壁纸 | 预览对话框正常弹出 |

#### 4.9.146 去重：悬浮按钮开关只留「切换方式」，外观入口只留设置；分组页去掉大图浏览

用户反馈：「设置里悬浮按钮外观那一节的悬浮切换按钮」和「切换方式里的悬浮切换
按钮」重复；「切换方式里的悬浮按钮」和「设置里的悬浮按钮」重复；并要去掉分组页
的「大图浏览」。

前两条其实是 §4.9.144 的结论被 `0ba7231`（回到 hub + 子页布局那笔 revert）又带
回来了：那份修改本来就把主设置页的「悬浮按钮外观」开关节删掉（开关只留在
「切换方式」，和双击/解锁这些切换方式放在一起）、把「切换方式」里的外观入口删掉
（外观只从设置主界面进）。这次按同一结论再删一遍：

1. `SettingsScreen` 删掉 `settings_section_button` 那一节（连同 `floatingButtonEnabled`
   读取），7 个语言的 `settings_section_button` 字符串一并删除；
2. `SwitchMethodsScreen` 删掉「悬浮按钮」外观入口与 `onOpenButtonAppearance`
   参数（`WallpaperSwitcherApp` 的传参一起删）；「悬浮切换按钮」开关保留；
3. `ButtonAppearanceScreen` 里同样没用的 `floatingButtonEnabled` 读取顺手清掉。

「大图浏览」：`GroupDetailScreen` 顶部那个整行按钮（`onBrowse`）删除，参数和
`WallpaperSwitcherApp` 的传参一并去掉；**首页分组卡片上的 ▶ 入口保留**，所以
「一张一张挑」仍然可用，只是不再在分组页里重复出现。

**验证**：单元测试 **454 条全绿**；真机截图核对：设置主界面「设置」节只剩
壁纸设置 / 切换方式 / 悬浮按钮 / 文件夹扫描 / 外观（无开关节），「切换方式」页
有「悬浮切换按钮」开关且没有外观入口，分组详情页操作栏只剩
「添加壁纸 / 批量操作 / 清理失效」。

#### 4.9.147 设置多选项改成 HyperIsland（Miuix）的浮层下拉样式

用户要求：「参考 hyperisland 设置的多选样式，重构设置多选样式」——即设置里
「当前值 + 箭头 → 选一个」的那些行（[HiOptionPickerRow]，壁纸设置 4 行、切换方式
的过渡动画、外观的语言 / 主题模式）。参考对象是 **GitHub 最新的 HyperIsland**
（`1812z/HyperIsland` main），它用的是 Miuix 的 `WindowDropdownPreference`
（`compose-miuix-ui/miuix`）：

- 下拉列表是**贴着行的窗口浮层**（`WindowListPopup`）：16dp 圆角、
  `surfaceContainer` 底色、8dp 阴影、从行的角落轻微放大淡入；
- 每个选项是 `DropdownImpl`：`selectable(role = RadioButton)`，正文 Medium
  字重，选中项文字用主题色（primary）+ 行尾 20dp 对勾；横向内边距 20dp，
  **首/末行纵向 20dp、中间行 12dp**（`DropdownDefaults`），所以列表两端
  看起来是"包住"内容的；
- 浮层与行**右对齐**（`PopupPositionProvider.Align.End`），下方放不下时翻到上方；
  点选项立即生效并关闭，点外部 / 返回只关闭不改值。

改动（`ui/theme/HiOptionControls.kt`）：

1. `HiOptionPickerRow` 不再在行下方展开行内面板（`HiInlineOptionPanel`），而是
   在行下缘弹出 `HiOptionDropdown`（`Popup` + 自定义 `PopupPositionProvider`）；
   行的标题 / 当前值 / 箭头布局保持不变，打开时箭头旋转。
2. 选项行按 Miuix 的度量重画（见上）。颜色预览圆点仍保留（`HiOption` 的
   `preview`）。
3. 行内面板时代的死代码（`HiOptionSheet` / `HiOptionSheetEntry` /
   `HiOptionSheetEmpty` / `HiOptionPanelCard` / `HiOptionSpacer` / `HiOptionGap` /
   `HiInlineOptionPanel` / `HiOptionRowPadding`）一并删除 —— 它们已经没有任何
   调用方。

调用方（各设置子页）零改动：它们只依赖 `HiOptionPickerRow` 的签名。

**验证**：`:app:assembleDebug` ✓、单元测试 **454 条全绿**（`HiOptionLogicTest`
覆盖的选项表 / 当前值归一化逻辑不变，只是外壳换了）。

#### 4.9.148 设置页导航收口：收藏 / 最近显示 / 存储与流量挂到「外观」下面，存储页恢复订阅源分项

用户要求：①「存储与流量、最近显示、收藏聚合页在设置没有入口，把它们放到外观
下面」；②「收藏退出后返回设置」；③「存储与流量增加，订阅源下载占用」。

三个页面（`FavoritesScreen` / `RecentScreen` / `StorageScreen`）在 `d8459f9` 的
设置改版后都只剩页面：收藏只在返回分支里出现过，最近 / 存储连入口都没有，只能
从别的流程绕进去。改动：

1. `SettingsScreen` 的「设置」一节在「外观」行后追加三行入口：收藏
   （`FavoriteBorder`）、最近显示（`History`）、存储与流量（`Storage`），都用
   `SettingsPageEntry` + 分隔线，点行进对应页面；新增
   `settings_page_favorites_desc` / `settings_page_recent_desc` /
   `settings_page_storage_desc` 三条描述字符串（7 个语言，`LocaleResourcesTest`
   管键对齐）。
2. 返回路径统一回设置：`WallpaperSwitcherApp` 顶栏返回箭头原先对设置子页一律
   `currentScreen = Screen.Home`，和页面自己注册的 `BackHandler`（回设置）不一致，
   现在两者都回 `Screen.Settings`；`FavoritesScreen` 的 `onBack` 也从 `Home` 改成
   `Settings`。
3. 「存储与流量」页把 `d8459f9` 写好却从未接上的订阅源分项卡接上：总览卡下面用
   `StorageDirCard` 显示 `usage.rss`（`storage_rss` = 订阅源下载 + 文件数 + 大小，
   右侧主题色数字），顺手删掉只为已删除的「在线壁纸下载」留的 `CloudDownload`
   import。总量仍含 `files/online` 的历史残留（由孤儿清理负责），所以分项数字
   可能小于总量 —— 这正是分项的目的。

**验证**：单元测试 **454 条全绿**、`:app:assembleDebug` ✓、已装机（启动无崩溃）；
本轮按用户要求不做截图分析，页面效果由用户直接核对。

#### 4.9.149 取消场景规则功能

用户要求：「取消场景规则功能」。场景规则是 §4.9.61 ⑤ 引入的两个全局开关
（省电模式时暂停切换 / 电量低于 15% 时暂停切换）：切换服务的两个定时循环在 tick
前查询设备状态，命中就 hold 住本轮（不消费 tick，恢复后立即补切）。

整条链路删除：

1. **设置界面**：`SettingsScreen` 的「场景规则」一节（两个 `SettingsSwitchItem`）
   与两个状态读取删除；7 个语言的 `settings_scene_rules` /
   `settings_scene_power_save` / `settings_scene_low_battery` /
   `settings_scene_hint` 四条字符串及注释块一并删除。
2. **数据与 ViewModel**：`SettingsKeys.SCENE_PAUSE_ON_POWER_SAVE` /
   `SCENE_PAUSE_ON_LOW_BATTERY` / `SCENE_LOW_BATTERY_PERCENT` 删除；
   `WallpaperViewModel` 的两个 setter、两个 StateFlow、`settingsUiState` 的合并
   输入与 `SettingsUiState` 字段删除，`SettingsField` 索引表重排（24..26）。
   数据库里已存在的两个键**不做迁移**：从此无人读取，是惰性的历史数据。
3. **切换服务**：`WallpaperSwitchService.scenePausesSwitching()`（PowerManager
   省电模式 / BatteryManager 电量查询）与首页、锁屏两个循环里的调用删除；
   「一键暂停」（`PAUSE_UNTIL`）的 hold 逻辑保持不变，相关注释同步改为只提它。
4. **配置导出**：`ConfigBackup.EXPORTED_SETTINGS` 去掉两个键；旧备份文件里带着
   它们的，导入时按现有的 `key !in EXPORTED_SETTINGS -> continue` 跳过，不报错。
   `GroupRules` 的文件注释也从「时间/场景规则」改回「时间规则」。

**验证**：单元测试 **454 条全绿**、`:app:assembleDebug` ✓、已装机。

#### 4.9.150 通知栏按钮 / 分享入库 / 首启自检向导 / 订阅下载策略 + 缓存 TTL / 静态图微动效

用户按产品设计清单一次点了五件事。每一项都遵守"老安装行为不变"：新开关默认
关闭，默认路径与改动前一致。

**① 通知栏按钮**（`WallpaperSwitchService`）

- 前台通知增加两个 action：**下一张**（与首页按钮 / 磁贴 / 小组件同一个
  `switchNow`，来源标记 `LiveWallpaperService.SOURCE_NOTIFICATION`）和
  **暂停 1 小时 / 继续**（共用 `PAUSE_UNTIL` 一键暂停键）。
- 两个定时循环把刚读到的暂停状态交给 `syncNotificationPause()`，所以从首页 /
  磁贴 / 小组件发起的暂停也会让通知按钮变成"继续"；通知自身的切换动作会唤醒
  两个循环。新增 3 个矢量图标与 `notification_action_*` 两条字符串（7 语言）。

**② 分享入库**（ACTION_SEND / ACTION_SEND_MULTIPLE）

- `MainActivity` 成为系统分享目标（`singleTop` + `onNewIntent`）：图片 / 视频
  打开分组选择对话框（可勾选「标为收藏」）；文本 / 链接跳到订阅页并预填现有的
  导入对话框（`RssImportDialog(initialText=…)`）。
- `engine/SharedMediaImporter` 立即把分享流复制到
  `files/shared/<sha256>.<ext>`（ACTION_SEND 的读授权随 Activity 失效，不能只
  存 URI），校验图片 bounds、按内容哈希 + 分组去重、插入普通 `WallpaperImage`
  行（`folderPath = "shared"`）。
- `files/shared` 纳入 `OwnedMediaCleaner` 与 `storageUsage()`，分享入库的副本会
  出现在「存储与流量」里，也会被孤儿清理 / TTL 覆盖。

**③ 首启自检向导**

- 全屏对话框，三步实时自检（`ON_RESUME` 重新检查）：媒体权限（三个权限任一）、
  内容（分组有媒体或订阅源非空）、动态壁纸（MIUI app-op +
  `LiveWallpaperService.isHomeLiveWallpaper`）。
- 动态壁纸服务被关闭时按钮直接打开 MIUI 权限页（`LiveWallpaperPermission`），
  否则打开系统动态壁纸选择器；完成 / 稍后再说都写入 `setup_wizard_done`。
  设置主界面新增「使用向导」入口可随时重开。
- 首次安装自动弹出一次；本版本之前的老安装（键不存在）也会弹一次，之后不再打扰。

**④ 订阅下载策略 + 缓存 TTL**

- 设置 →「存储与流量」新增订阅下载策略卡：仅 Wi-Fi 下载（默认关）、每日上限
  （不限 / 50 / 100 / 200 / 500 MB）、残留自动清理（关闭 / 7 / 30 / 90 天）。
- `engine/RssDownloadPolicy`：纯决策核心（仅 Wi-Fi 优先于每日上限）+ 计量网络
  判断 + 每日字节计数（日期变化自动归零）。`RssMediaImporter` 在**任何网络请求
  之前**检查策略，结束时把本次实际下载的字节计入当天（校验失败的文件也计入——
  流量确实花了）。
- TTL：`OwnedMediaCleaner.sweepExpired()` 只删"数据库无人引用 + 超过 TTL"的
  app 自建文件（保留原有 10 分钟保护期；自选 SAF 目录只认 `<sha256>.<ext>`
  命名），每次打开 App 跑一次，关闭时不动作。

**⑤ 静态图微动效（Ken Burns）**

- 「壁纸设置」新增开关（默认关）：静态图以 24 秒为一个周期做 0 → 6% → 0 的缓慢
  缩放。几何是 `WallpaperGeometry.applyKenBurns()` 纯函数——相位 0 / 1 与静止
  布局完全一致、只缩放不平移（FIT 的黑边不会漂移），6 条单测覆盖。
- 渲染端用 ~15fps 的 ticker 重绘**已上传的纹理**（`renderImageFromTexture`，不
  重新解码）；不可见（`powerSaveMode`）时停、GIF 自播时让位、视频播放时不抢屏；
  切换媒体时从 1x 重新开始。设置改动即时生效
  （`LiveWallpaperService.applyKenBurnsFromSettings`），引擎每次切换前也会自行
  读取该设置。

**验证**：单元测试 **467 条全绿**（新增 `RssDownloadPolicyTest` 7 条 +
`WallpaperGeometryKenBurnsTest` 6 条，含 7 语言键对齐），`:app:assembleDebug` ✓、
已装机；按用户要求本轮不做截图分析，页面效果由用户直接核对。

#### 4.9.151 画质增强（超分）：低画质图片与视频

用户要求："搜索有关 AI 画质增强的内容，对于低画质图片和视频增加 AI 画质增强的
功能"。技术调研（一手来源、含失败结论）见
`docs/research-ai-upscaling-2026-10-05.md`。核心结论：

- **真 NN 逐帧视频超分在手机上不可行**：TF 官方 TFLite 超分示例自己把"用蒸馏
  模型做视频超分"列为 Future work；Anime4K 官方 README 的对比把 waifu2x /
  Real-ESRGAN 归为非实时。
- **真 NN 静态图超分可行但代价明确**：示例的 `ESRGAN.tflite` 实测 4,993,712
  字节、输入 50×50 → 输出 200×200（4x）；模型来源（TF Hub
  `captain-pool/esrgan-tf2`）**拿不到一手许可证声明**（TF Hub 页面静态不可抓、
  对应 GitHub 仓库 404），因此本期不进包。
- **实时路线只能是 GPU 着色器**：FSR 1.0 的 EASU 依赖 `gather4`（需要 GLES 3.1），
  Anime4K 是 mpv 的多 pass 大着色器；都不适配当前 GLES 2.0 单 pass 引擎。
  高通的 SGSR 只有 Unity/URP 包，是游戏渲染管线组件，不适用。

本期实现（不新增任何依赖）：

1. `WallpaperGeometry.enhancementStrength()`：素材放大 <1.25x 不增强，线性到
   4x 满强度；FIT 取较小轴、FILL/STRETCH 取较大轴（与既有清晰度曲线同一套放大
   倍数口径）。
2. 图像与视频 fragment shader 增加 `uEnhance` / `uSrcTexel` 通路：4-tap
   Catmull-Rom 双三次（每对样本用一个硬件双线性取样复现 16 tap 双三次；配对
   数学在 `WallpaperGeometry.cubicPairs()` 里与直接权重对照做单测）+ 2x 强度的
   unsharp。`uEnhance == 0` 时完全走原路径，行为与 GPU 成本不变。
3. 视频的双三次步长用**实际解码尺寸**（decode-cap 之后），图片用 bitmap 尺寸；
   每帧/每次绘制作为 uniform 传入。
4. **着色器自动回退**：新源编译失败就用改动前的
   `IMAGE/VIDEO_FRAGMENT_SHADER_FALLBACK`，增强静默失效——任何驱动都不能因为
   这次改动出现黑屏。
5. 设置 →「壁纸设置」新增「**画质增强（超分）**」（默认关）。运行中的引擎由
   `applyQualityEnhanceFromSettings` 即时切换（静态图立即重绘，视频下一帧生效），
   每次切换/重绘前也会重读设置，引擎被系统重建后同样生效。

命名按调研结论保持克制：实现是 GPU 着色器超分而非神经网络，因此设置名不写
"AI"。二期若要做真 NN，只建议对静态图做离线 4x 超分，前提是先解决模型许可证
与 patch 推理耗时（一张 720p 约 350 个 50×50 patch）。

**锯齿反馈修复（同日晚些时候）**：用户报告"低画质视频和图片超分后有很多锯齿"。
排查出两个叠加的根因，都在渲染端：

1. **过锐**：增强路径把清晰度 unsharp 的强度乘了 `(1 + 2 * uEnhance)`，低画质
   素材本来就带着 magnifyBoost，最坏时等效强度约 2.6，而普通路径的上限只有
   ~0.86 —— unsharp 过冲在强边缘上直接表现为锯齿/描边。
2. **mediump 精度**：双三次要在 `uv / uSrcTexel`（0..4096 量级）上取小数部分，
   移动 GPU 的 mediump 常常是 fp16（1024–2048 区间的步进已经是 1 texel），
   小数被量化掉后权重失真——这正是"越放大锯齿越多"的来源。

修复：

- 两个增强 fragment shader 改用
  `#ifdef GL_FRAGMENT_PRECISION_HIGH → precision highp float / varying highp`
  （老 GLES2 设备仍回退 mediump，程序保证可编译）。
- 锐化改成 **RCAS 式对比度自适应**：用 5 个采样点的亮度 min/max 算出局部对比度，
  高对比边缘只给 40% 的清晰度强度、平坦区域给满，并且总强度封顶 0.6（**永不高于
  普通路径**）——过冲来源被去掉。
- 增强路径新增**按源分辨率**的十字轻微混合（随 `uEnhance` 增长，最多约 17.5% 的
  权重），专门磨平放大后的斜边阶梯；普通路径与关闭开关时完全不受影响。

**按用户反馈合并（同日）**：把「清晰度增强」的第三个选项「增强」直接换成
「画质增强（超分）」，不再单独立一个开关：

- 清晰度面板 = 自动 / 关闭 / **画质增强（超分）**。归一化由一份纯逻辑
  `engine/ClarityMode` 统一负责：旧存的 `"strong"`（增强）自动迁移为 `"super"`
  （也就是升级到超分），未知值仍等同"自动"，"关闭"依旧是彻底关闭（清晰度 0，
  也不走超分）。
- 独立的 `quality_enhance_enabled` 开关、`SettingsUiState` 字段、ViewModel setter
  与引擎的专用推送全部删除；旧开关值由一次性迁移
  （`migrateLegacyQualityEnhance`，打开 App 时执行）合并进 `clarity_mode` 后删除
  旧键，开发期装过中间版本、开着独立开关的用户不会丢设置。
- 实时路径不变：引擎仍收集 `CLARITY_MODE` 的流并即时重绘当前静态图，超分标志与
  清晰度强度在同一次 `applyClarity(scale, qualityBoost)` 里落地；每次切换前的
  `applyClarityMode()` 也会同时设置两者（旧 `"strong"` 值在引擎侧同样归一化为
  超分）。
- 新增 `ClarityModeTest` 5 条（含旧值迁移），`HiOptionLogicTest` 的清晰度选项表
  同步改成 `auto / off / super`。

**验证**：单元测试 **479 条全绿**（新增 `WallpaperGeometryEnhanceTest` 7 条 +
`ClarityModeTest` 5 条），
`:app:assembleDebug` ✓、已装机（按用户要求不做截图分析；GPU 超分的观感由用户
在真机上确认）。

#### 4.9.152 两处界面反馈：底栏设置子页高亮 + 最近显示改为纯缩略图

**① 底栏 tab（用户反馈："点击设置里下一个界面时底栏会从设置跳到首页"）**

底栏 tab 的计算只认 `Screen.Settings` 本身：壁纸设置 / 切换方式 / 文件夹扫描 /
悬浮按钮 / 外观 / 收藏 / 最近显示 / 存储与流量这些设置子页全部落到 `else`，
于是底栏高亮跳回「首页」。现在统一走 `Screen.isSettingsPage()`（设置主界面 +
8 个子页），底栏保持「设置」；首页子页（分组详情 / 大图浏览）仍是「首页」、
订阅子页（文章 / 登录 / 编辑）仍是「订阅」。新增 `SettingsPageTest` 2 条，把这份
页面集合钉住，避免以后加设置子页再漏。

**② 最近显示改为纯缩略图网格（用户要求："设置的最近显示只显示缩略图"）**

`RecentScreen` 从"竖排卡片列表（64dp 缩略图 + 文件名 + 分组名 · 相对时间 + 桌面/
锁屏角标）"改为**纯缩略图网格**，与收藏页同一套参数：`GridCells.Adaptive(104dp)`、
正方形、12dp 圆角、8dp 间距、Coil 176px 解码（ARGB_8888 + 硬件位图、视频挂
`VideoFrameDecoder`）。

- 点一下仍然推回这张图**上次显示的那块屏**（`recentForceSlot`：锁屏项只写锁屏、
  桌面项按分组目标），推回后置顶与位移动画不变；
- 视频 / GIF 的角标保留（它提示"锁屏只能写第一帧"）；名称 / 分组 / 时间 / 屏标
  文字全部从界面移除；
- 随行改动的相对时间包装（`recentAgoLabel` / `RecentAgoLabel` / 30s 时钟 tick）
  与对应单测一并删除；设置页那条 `agoParts`（"上次扫描：3 分钟前"）保留，有
  自己的 `FormatAgoTest` 覆盖。

**验证**：单元测试 **477 条全绿**、`:app:assembleDebug` ✓、已装机。

#### 4.9.153 画质增强三件套：FXAA + 降噪分支、订阅原图优先、重复图片去重

用户要求把 4.9.151 调研里列出的下一步做出来："FXAA + 降噪分支 + 原图优先 + 去重"。

**① FXAA + 降噪分支（渲染端，图片与视频共用）**

- 降噪检测 `engine/ImageQuality.denoiseStrength`：静态图解码后跑一次（4 个 16x16
  角 patch，最多 1024 个采样点），取"每像素与四邻最大亮度差"的**中位数**当像素级
  噪声地板：< 8 亮度级视为干净（不降噪）、≥ 32 用满强度、上限 0.6。边缘/纹理不会
  拉高中位数（只有极少数像素差大），JPEG 块效应 / 压缩噪点会。视频不做逐帧检测，
  按放大倍数给固定的 `0.30 × uEnhance`。
- 着色器在双三次之后先做**双边加权平均**（权重 `1/(1+60·颜色距离²)`）：颜色差小
  的像素级杂讯被平均，颜色差大的纹理/边缘权重极低、几乎不动；强度由新 uniform
  `uDenoise` 控制（检测偏保守也不会糊内容）。
- **FXAA-lite**：用屏幕像素邻域的亮度判断边缘方向（比较 x/y 方向的亮度变化），
  沿边缘方向混合（最多 `0.5 × uEnhance`），专门磨掉放大后的斜边阶梯；之后才走
  4.9.151 的对比度自适应锐化（封顶 0.6，绝不比普通路径更锐）。
- 新增 `ImageQualityTest` 7 条：干净 / 噪点 / 硬边 / 渐变 / 低对比织物 / 硬纹理 /
  强度上限。

**② 原图优先（订阅源）**

- 抽取层（`FeedParser.imageUrlOfTag`）：懒加载画廊的 `src` 常是占位图，旧实现按
  "标签里先出现的属性"取值——占位图会赢。现在收集全部候选并按
  `data-original > data-src > data-lazy-src > data-echo > data-url > srcset(第一项)
  > src` 排序（srcset 仍取站点自己标的第一个，列表/选择器用它做展示尺寸）。
- 下载层（`RssMediaImporter`）：`engine/OriginalImageUrl.upgrade` 去掉
  WordPress 的 `-300x200` 尺寸后缀与 `?w=&h=&quality=&x-oss-process=…` 这类缩放
  参数；按升级后的 URL 去重（同一张图的缩略图与原图只下一次），升级地址
  404/403/不是图片时**回退到页面给的 URL**——猜错不丢图。
- 新增 `OriginalImageUrlTest` 5 条、`FeedParserTest` +2 条。

**③ 重复图片去重（同图不同尺寸，保留最大）**

- `engine/MediaDedupe`：只扫应用自己下载的图片（`files/rss`、`files/online`、
  `files/shared`），解码到 9x8 亮度差分（dHash 64 bit）。判定：**同一分组内** +
  宽高比接近（2% 容差）+ 汉明距离 ≤ 5 + 面积不同 → 保留面积最大的；面积相同只有
  哈希完全一致才算重复（避免误删相似的图）。跨分组不合并（桌面组 / 锁屏组各留
  一份是合法用法）。相册 / SAF 原图不动。
- 入口：设置 →「存储与流量」新增「重复图片清理」卡片，点一下扫描并清理，行内显示
  删除数量与释放空间，并刷新整页统计（总数 / 分项 / 可清理量）。
- 行删除后只有"没有任何行再引用"的文件才会被删；新增 `MediaDedupeTest` 7 条
  （方向性、保留最大、跨组、宽高比、同尺寸不同哈希、完全相同哈希、空输入）。

**验证**：单元测试 **499 条全绿**、`:app:assembleDebug` ✓、已装机（按用户要求不做
截图分析；FXAA / 降噪的实际观感由用户在真机确认）。

#### 4.9.154 超分算法可选：FSR1 EASU/RCAS 与 Anime4K（各一个开关）

用户要求："做 FSR1 EASU/RCAS，Anime4K，设置增加对应开关"。

**实现（图片 / 视频共用同一条增强通路）**

- 新增 uniform `uEnhanceMode`（0 = 内置 4-tap 双三次、1 = FSR1、2 = Anime4K）与
  `uEasuScale`（输入/输出尺寸比）。只有「清晰度增强 = 画质增强（超分）」且素材被
  放大时才会进入增强分支，两条新算法都在这个分支里替换基础采样。
- **FSR1 EASU**：从 AMD FidelityFX FSR 1.0（MIT，`ffx-fsr/ffx_fsr1.h`）移植的
  12-tap 边缘自适应椭圆滤波。参考实现用 `textureGather` 取 2x2 quad；这里直接把它
  需要的 12 个纹素用普通 `texture2D` 取出来，所以既不需要 GLES 3.1 也不需要加
  FBO，仍然是一个 pass。`FsrEasuSetF` 的四个编译期分支展开成四次带权调用；
  con0 的缩放用 shader 里的 `uEasuScale` 现算（`ip = uv / (texel * scale)`）。
- **FSR1 RCAS**：参考实现是 EASU 之后的第二个 pass；这里用同一套屏幕像素邻域采样
  （t0..t3）做单 pass 适配：保留 noise 检测（`nz`）、peak limiter、`FSR_RCAS_LIMIT
  = 0.1875` 与 `FSR_RCAS_DENOISE` 的 lobe 缩放，锐度由清晰度强度映射
  （`clamp(uSharp * 2.5, 0, 1)`）。FSR1 模式下 RCAS 取代通用的对比度自适应锐化。
- **Anime4K**：移植 `bloc97/Anime4K`（MIT）v4 的 `Upscale: Original x2` 线稿算法
  （v3.2 同源）：luma Sobel → 官方多项式（P5..P0）算 refinement 值 dval → 沿梯度
  方向在 x/y 邻域之间按比例混合；dval < 0.1 时回退双三次。它是 Anime4K 里可实时、
  可单 pass 化的那个算法；v4 的 CNN / GAN 变体（18KB~1MB 的生成着色器、多 pass +
  LUT）没有移植，设置文案如实写明是 Original x2 单 pass 移植。
- 降噪 / FXAA / 锐化的后处理链对三种模式都保留（FSR1 的锐化阶段换成 RCAS）。

**设置**

- 壁纸设置 →「清晰度增强」下面新增两个开关：「FSR1 EASU/RCAS 超分」与
  「Anime4K 超分」。互斥（打开一个自动关另一个），都默认关闭，文案注明"仅超分模式
  生效"。运行中的引擎由 `applyEnhanceModeFromSettings` 即时切换（静态图立刻重绘、
  视频下一帧生效）；每次切换前 `applyClarityMode()` 也会重读两个开关。
- 新增 `EnhanceModeTest` 4 条（模式映射 + 同时打开时 Anime4K 优先）。

**验证**：单元测试 **503 条全绿**、`:app:assembleDebug` ✓；两个 fragment shader
（含 EASU / RCAS / Anime4K）用 glslang 16.6.0 以 `#version 100`（ESSL）离线校验
通过——视频 shader 里一处 RCAS 结果的 vec3→vec4 赋值就是这一步抓出来并修掉的；
着色器编译失败仍有旧 fallback 着色器兜底。已装机（不截图）。

**Anime4K 锯齿反馈修复（同日）**：用户反馈 Anime4K 模式"锐利、锯齿多"。三处都改回
原版语义：

1. 混合基底从 Catmull-Rom 双三次换成**双线性**——原版的 Apply pass 叠在 2x 双线性
   放大图上，叠在更锐的双三次上等于做了两遍锐化；
2. 沿梯度方向的邻域采样从"一整个源纹素"改成**半个源纹素**——原版移动的是它 2x
   输出上的一个输出像素，折算到源空间就是半个纹素；整整一个纹素会把边缘推过头；
3. Anime4K 模式下把后面的通用对比度自适应锐化**减半**（算法本身已经在强化边缘）。

两个 shader 重新过 glslang ESSL 校验（exit 0）；503 条单测、`assembleDebug`、
装机启动均正常。

**"不明显"与"仍有锯齿"修复（同日第二次反馈）**：

1. **视频门槛用错尺寸（真 bug）**：判断素材是否被放大时用的是视频的**原始尺寸**，
   而实际纹理是按屏幕像素规则解码后的尺寸——4K 片段解码到 1080 后明明在放大，门槛
   却按 4K 算出"没有放大"，FSR1 / Anime4K 基本不生效。现在改用解码后的实际尺寸
   （`videoSrcW/H`，为 0 时才回退到标称尺寸）。
2. **强度曲线重标定**：从"1.25x 起步、4x 满强度"改成"**1.0x 起步、2x 满强度**"。
   1080p 素材在 1440p 屏上（1.33x）的强度从 ~0.03 提到 ~0.33，2x 及以上直接满
   强度；`WallpaperGeometryEnhanceTest` 同步更新（含 1.33x→0.33 的新用例）。
3. **Anime4K 抗锯齿加权**：Anime4K 模式下把 FXAA-lite 的沿边混合乘以 1.6（上限
   0.6），专门压它推边缘后留下的斜边阶梯。

两个 shader 重新过 glslang ESSL 校验；504 条单测全绿、`assembleDebug`、装机启动
均正常。

**"360p 放到 3.2K 不明显"修复（同日第三次反馈）**：

360p 在 3.2K 平板上是 4~5 倍放大，强度早已是满值，所以问题不在门槛上。两处修正：

1. **开关的隐性前提（产品层）**：两个算法开关只有在「清晰度增强 = 画质增强（超分）」
   时才生效；用户很可能只打开了开关、清晰度还停在"自动"，于是算法根本没运行。现在
   **打开任一算法开关会自动把清晰度切到「画质增强（超分）」**（7 语言提示同步改成
   "打开即自动切到…"），不再有"开关没用"的状态。
2. **高倍率下的细节增强（渲染层）**：锐化量乘上 `(1 + 0.7×uEnhance)`，上限从 0.6
   分档提到 0.95（`mix(0.6, 0.95, …)` 从 1.5x 放大开始生效）；同时把高倍率下会
   "磨平"细节的地方收了一点——图片降噪上限 0.6 → 0.45，视频降噪 0.30 → 0.22，
   源空间平滑 0.15 → 0.10。

两个 shader 重新过 glslang ESSL 校验；504 条单测全绿、`assembleDebug`、装机启动
均正常。

#### 4.9.156 取消"离线 NN 超分"

用户要求："取消真正的离线 NN 超分"。该功能在 `15f32e4` 落地（TFLite + 官方
ESRGAN 模型、50x50 分块 4x、模型首次使用时下载到 `files/nn/`、分组图片 ⋮ 菜单
「AI 超分（4x）」），现在整条链路移除：

1. **依赖与体积**：`tensorflow-lite` / `tensorflow-lite-gpu` 两个依赖，以及当时
   为它加的 `abiFilters`（arm64-v8a / armeabi-v7a）一并删除。
2. **引擎与入口**：`engine/NnUpscaler` 与 `NnUpscalerTest` 删除；
   `WallpaperViewModel` 的 `upscaleImageWithNn` / `NnLoad` / `loadForNnUpscale`
   删除；分组九宫格 ⋮ 菜单的「AI 超分（4x）」项与 `onNnUpscale` 回调删除；
   7 个语言的 8 条 `nn_upscale_*` 字符串删除。
3. **保留的一小部分**：`files/nn` 仍留在 `OwnedMediaCleaner.MANAGED_DIRS` 与
   `storageUsage()` 统计里——设备上已经下载的模型（约 5MB、没有任何媒体行引用）
   和已生成但被删行的 4x 副本还能被回收；`StorageUsage.nn` 只参与总量。
4. 实时超分三模式（内置双三次 / FSR1 EASU+RCAS / Anime4K）不受影响。

**验证**：单元测试 **504 条全绿**（`NnUpscalerTest` 4 条随功能删除）、
`:app:assembleDebug` ✓、已装机；设备上 `files/nn/esrgan.tflite` 已删除。

#### 4.9.157 细节增强：锐化半径从"屏幕像素"改到"源纹素"

用户反馈"图片看起来糊"（360p 在 3.2K 平板上）。根因：之前唯一的锐化用的是 **1 个
屏幕像素**的邻域——在 4~5 倍放大下只相当于 0.2~0.25 个源纹素，提不出任何"源像素
之间"的结构；同时高倍率下还有两处柔化（源空间平滑、降噪）。

改动（两个 fragment shader，图片/视频共用）：

1. 新增**源纹素尺度的细节增强**：用 s0..s3（±1 源纹素）的平均做广域 unsharp，
   `detailAmount = 0.40 × uEnhance`（Anime4K 模式 ×0.75，因为它本身已强化边缘），
   把源像素之间的过渡重新拉开——这是"看起来不糊"的来源。
2. 源空间平滑从 `0.10 × uEnhance` 降到 `0.05 × uEnhance`，少一点自我柔化。
3. 屏幕像素尺度的对比度自适应锐化保持不变（它负责细部锐度与抗锯齿的平衡）。

**验证**：两个 shader 过 glslang ESSL 校验（exit 0）、504 条单测全绿、
`assembleDebug` ✓、已装机（不截图）。

**"跟关闭没区别"——截图 A/B 实测与加强（同日）**：用户要求截图核对。用
uiautomator 把清晰度切到「关闭」拍一张、再切到「画质增强（超分）」（Anime4K 开）
拍同一张壁纸，对同一块区域（脸部 / 脚跟）算 Laplacian 均值（高频能量）：

| 版本 | 脸部 | 脚跟 | 结论 |
|---|---|---|---|
| 初版（super 档 1.25，细部 0.40） | 4.899 → 5.395 | 2.515 → 2.81 | 只强 ~10%，几乎看不出 |
| 加强后（super 档 1.5，细部 0.60 + 第二尺度 0.25） | 4.899 → **6.230** | 2.515 → **3.006** | +27% / +20%，可见 |

两图差异均值 2.3/255（同一张 900x1349 webp、2.37x 放大），确认差异全部来自增强。
改动：`ClarityMode.SUPER` 的锐化档 1.25 → 1.5；源纹素细节 0.40 → 0.60；
新增 **2 源纹素半径的局部对比**（`0.25 × uEnhance`，Anime4K ×0.75）——高倍率下
人眼对中频对比最敏感，这层"通透感"最显眼。glslang 在这一步抓出一处插错位置
（视频 shader 少一处、图片 shader 重复且用了未声明变量），修正后两个 shader 重新
校验通过。

#### 4.9.158 清晰度增强改为开关 + 超分算法二选一

用户要求："清晰度增强只有开启和关闭，开启 FSR1 EASU+RCAS / Anime4K 可选"。
4.9.154 的「三选项 + 两个互斥算法开关」在界面上确实绕：算法开关的隐含前提是
清晰度必须处于「画质增强（超分）」，用户很容易只打开算法开关却看不到任何变化。

**设置形态**

- 「清晰度增强」变成一个**开关**（默认开），下面缩进一级出现子行「超分算法」，
  二选一：**FSR1 EASU/RCAS**（默认）或 **Anime4K**（Original x2 线稿算法）。
  子行沿用 [HiOptionPickerRow] 的弹出面板形态，与「旋转方向」缩进一致。
- 存储语义：`clarity_mode` 只有显式 `"off"` 是关，`"auto"` / `"super"` / 旧的
  `"strong"` / 键缺失都按**开**处理（`ClarityMode.isEnabled`，开启统一用
  4.9.157 加强后的锐化档 1.5）——老安装的默认行为一字不变。
- 新键 `enhance_algo` = `"fsr1"` / `"anime4k"`。`EnhanceMode.fromKey`（未知 →
  FSR1，通用素材更稳）与界面 `enhanceAlgoOf` 同一口径，行右侧永远不会显示一个
  用户选不到的值。

**旧键迁移（4.9.154 的两个互斥开关 → `enhance_algo`）**

- 启动时 `migrateLegacyQualityEnhance` 把非 on/off 的清晰度值归一成 `"on"`；
  `enhance_algo` 缺失时按旧开关推导（Anime4K 开 → `anime4k`，否则 `fsr1`），
  然后删除 `fsr1_enhance_enabled` / `anime4k_enhance_enabled` / 4.9.151 的
  `quality_enhance_enabled`。开发期装过中间版本的用户不会丢设置。
- 引擎侧 `currentEnhanceMode()` 在 `enhance_algo` 还没写入时也回退读旧键，
  迁移执行前重建的引擎同样走对算法；异常兜底从"内置双三次"改为 FSR1。
- ViewModel API 同步更名：`setClarityMode` → `setClarityEnabled`，
  `fsr1EnhanceEnabled` / `anime4kEnhanceEnabled` 两个 StateFlow →
  `clarityEnabled` + `enhanceAlgo`。

**文案（7 语言）**：删除 `clarity_auto` / `clarity_off` / `settings_quality_enhance` /
`settings_fsr1_hint` / `settings_anime4k_hint`；新增 `settings_clarity_hint` /
`settings_enhance_algo` / `settings_enhance_algo_hint`；`settings_fsr1` /
`settings_anime4k` 现在作为两个算法选项的标签（"FSR1 EASU/RCAS 超分" /
"Anime4K 超分"）。

**验证**：503 条单测全绿（`ClarityModeTest`、`EnhanceModeTest`、`HiOptionLogicTest`
改测新语义）、`:app:assembleDebug` ✓、已装机；真机数据库实测启动迁移后
`clarity_mode=on`、`enhance_algo=fsr1`（设备此前的
`fsr1_enhance_enabled=true` / `anime4k_enhance_enabled=false` 被换算；旧键的
`deleteKey` 已执行，SQLite 页里残留的旧字节属正常的 checkpoint 行为）。

#### 4.9.159 订阅源导入：同 URL 就地更新，不再新增重复行

用户要求："订阅源导入时如果列表已有项目，则更新，而不是新建个列表"。

**根因**：「导入阅读订阅源」原来对解析出的每个源无条件 `insert`，而 `RssSource.id`
是自增主键——同一份清单再导入一次（改了规则、或者只是重复点了一次导入）就会多出一
行同名订阅源；文章缓存与登录信息都挂在 `sourceId` 上，重复行等于把用户自己维护的
已读/登录状态割裂成两份。配置导入那条路相反：同 URL **跳过**，规则永远更新不进来。

**实现**：新增 [engine/RssSourceImport]（纯逻辑，可单测）

- 身份 = 裁剪空白后的 URL（沿用配置导入原先的去重口径）；名称/标题变化不影响判定。
- 已有同 URL → `merge` 就地更新：`name` / `type` / `rawJson` 跟随导入，
  **保留 id、createdAt、抓取状态与本地 `enabled`**（用户在列表里关掉的源不会被
  重新导入悄悄打开，列表顺序也不跳）；导入的 name 为空则保留原名。
- 同一批里重复出现的 URL 折叠成一条（最后一条生效）；无 URL 的条目忽略并计入跳过。
- 两条导入路径都改走它：`importLegadoSources`（阅读订阅源导入）与
  `ConfigBackup.apply`（配置导入，原来只跳过，现在会更新）。手动添加订阅源时若
  地址已在列表里，也只更新名称（类型与规则 JSON 保持不动）。
- 导入结果 Toast 从"已导入 N 个（跳过 M 个）"改为三参：
  **已导入 %1$d 个订阅源，更新 %2$d 个（跳过 %3$d 个）**，7 语言的导入说明也补了
  "列表中已有的订阅源会就地更新"。

**验证**：510 条单测全绿（新增 `RssSourceImportTest` 7 条：新增/更新/保持本地开关/
同批折叠/trim 匹配/空名保留/空 URL 忽略）、`:app:assembleDebug` ✓、已装机
（设备仍是安全锁屏，未做界面走查——按用户要求不截图）。

#### 4.9.160 大分组网格分页/懒加载（窗口化，滚动条仍按整组跳转）

用户要求："做大分组网格分页/懒加载"。起因见「十、已知限制」第 6 条：分组详情原来
一次把**整组**读进内存（`getImagesByGroupSync`），几百上千张时开页与每次
`refreshImages()` 都要重查重传一遍元数据。

**模型**：`engine/MediaWindow`（纯逻辑，可单测）把"看哪一段"与"查哪一段"分开

- 窗口 = 连续一段 `PAGE = 200` 张；网格按**整组总数**渲染（`items(count = total)`），
  没加载到的下标先画同尺寸占位格子（正方形 + 12dp 圆角，与真格子一致，所以布局和
  滚动条不跳）。
- 可见范围离窗口边缘进入 `PREFETCH = 40` 格就补页：靠尾部**后接一页**、靠头部
  **前插一页**；离得远（拖快速滚动条）直接**换成以目标为中心的一页**——所以
  滚动条仍然覆盖整组、可以秒跳到任意位置，而中间的数据永远不会被读进来。
- 拼接（顺序、前插起点、跳转整段替换）都在 `MediaWindow.applied` 里，ViewModel
  只负责执行 SQL；同一时刻只跑一个补页任务，滚动期间到达的请求合并成一个范围。
- 请求范围本身宽于一页时（大屏平板一次可见上百格 + 预取），跳转窗口改为从范围
  **头部**开始覆盖，其余由后接的页补齐——居中会让窗口两头都够不着、下一轮又要跳；
  补页循环另有"窗口没有变化就收工"的安全阀，任何边界情况都不会转不出去。
- 查询用 `getImagesByGroupPage(groupId, limit, offset)`，`ORDER BY addedAt DESC,
  id DESC` 与整组查询完全一致（`addedAt` 单独不唯一：文件夹导入会给每一行盖同一
  毫秒的时间戳，OFFSET 分页必须有序全序）。

**两个使用方**

- `GroupDetailScreen`：`items(count = totalCount)` + `snapshotFlow { layoutInfo
  .visibleItemsInfo }` 请求范围；快速滚动条的 `itemCount` 从"已加载张数"改成
  整组总数；头部的"（已加载 N）"提示不再显示（窗口化后它永远小于总数，没有意义）。
  「全选」本来就查 `getAllImageIds`（数据库授权）、计数用 `totalImageCount`，
  所以批量选择/删除的行为不变。
- `MediaBrowseScreen`（单张全屏浏览）：位置改用**整组绝对下标**（`windowStart`
  偏移），翻页时预取相邻下标；窗口还没盖到那一张时显示加载态而不是"没有素材"。

**验证**：524 条单测全绿（新增 `MediaWindowTest` 14 条：覆盖/后接/前插/远跳/
边界夹取/空页/拼接顺序/超宽范围）、`:app:assembleDebug` ✓、已装机（崩溃扫描干净）。
真机数据库上按同一 SQL 逐页取回：252 行 = 252 唯一行、0 重复、无缺口
（`page_parity_ok = true`）。设备仍是安全锁屏，界面滚动/跳转未做人工走查。

---

## 七、权限声明

| 权限 | 用途 | API 级别 |
|------|------|----------|
| `READ_MEDIA_IMAGES` | 读取图片 | 33+ |
| `READ_MEDIA_VIDEO` | 读取视频 | 33+ |
| `READ_EXTERNAL_STORAGE` | 读取存储 | ≤32 (maxSdkVersion=32) |
| `FOREGROUND_SERVICE` | 前台服务 | 全版本 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | 特殊用途前台服务 | 34+ |
| `POST_NOTIFICATIONS` | 通知权限 | 33+ |
| `RECEIVE_BOOT_COMPLETED` | 开机自启动 | 全版本 |
| `WAKE_LOCK` | 唤醒锁（预留） | 全版本 |
| `SET_WALLPAPER` | 设置壁纸 | 全版本 |
| `SYSTEM_ALERT_WINDOW` | 悬浮按钮 | 全版本 |

---

## 八、关键技术实现

### 8.1 Room 数据库

- 单例模式（`@Volatile` + `synchronized`）
- Flow 响应式查询（UI 自动更新）
- 扩展函数简化设置读写（`getBool`/`setBool`/`getLong`/`setLong`/`getString`）

### 8.2 Coil 图片加载

- 全局自定义 `ImageLoader`（`WallpaperSwitcherApp.initCoil()`）
- 内存缓存：20% 可用内存（为壁纸引擎视频缓冲/GL 纹理留余量）
- 磁盘缓存：启用
- 硬件 Bitmap：禁用（软件位图兼容性）
- Bitmap 配置：`RGB_565`（16-bit，缩略图省内存）
- 视频缩略图：`VideoFrameDecoder`

### 8.3 SAF (Storage Access Framework)

- `DocumentFile` 访问系统选择的文件夹
- `takePersistableUriPermission` 持久化读取权限
- 递归扫描子文件夹，云盘/网络挂载文件打开有超时保护

### 8.4 协程使用

- `viewModelScope`：ViewModel 生命周期绑定
- `CoroutineScope(Dispatchers.IO + SupervisorJob())`：服务/引擎级作用域
- `CancellationException` 正确传播（不被 catch 吞没）
- `goAsync()` + `withTimeout`：广播接收器中的安全异步操作
- 阻塞调用（位图解码/视频打开）跑在守护线程 + 超时，协程取消不悬挂

### 8.5 渲染架构

- 所有 GL/EGL 操作集中在渲染线程（`WallpaperRenderer` 的 HandlerThread），上下文跨 Surface 重建存活
- 图片切换 = `stopVideoAndRender()` 单次 handler post 内完成"释放视频资源 → 上传图片 → swap"，无闪黑
- 渲染线程任务统一 try/catch，单次 GL 异常不会拖垮整个壁纸进程
- 视频呈现**不再有 30fps 上限**：原先渲染线程用 `now - lastVideoFrameSwappedAt < 33L` 丢弃一半帧，50/60fps 素材实际只按 ~30fps 呈现（平板日志里用户的 .mov 是 60fps）。现在下限改为 16ms（≈60fps），30/60fps 素材按源帧率呈现，只有异常高帧率素材会被限制；屏幕不可见/熄屏的省电仍由解码循环按 ~1fps 节流（`powerSaveMode`），与呈现无关。
- 帧率可verify：每个播放周期结束时输出 `Video pass: N frames presented in Tms (X fps)`（`cleanupVideoResourcesOnRenderThread`），与 `Video started: WxH @ Nfps` 对照即可确认是否与源帧率一致。短片的每分钟诊断（`Video render rate`）因为每次循环都会重置窗口而不会触发，所以补了这条按周期的日志。
- 真机实测（Redmi 平板 1440×3200，Android 17）：**30fps 源 → 29.9–30.0fps（100% 一致）**；**60fps 的 2160×3840 HEVC（解码后 1708×3038）只有 28–38fps（源的 46–63%）**，播放周期最长被拖到 +24%，并出现过一次 `Video stalled: no frame for 14s` 后触发恢复切换。原因是每帧成本与像素数成正比（解码 + 纹理上传 + 1440×3200 合成），该 GPU 撑不住 5M 像素 × 60fps。
- 因此解码上限增加**帧率感知**：`KEY_FRAME_RATE >= 48` 的素材把长边上限压到 1920（其余保持原上限），即 2160×3840 的 60fps 视频解码为 1080×1920（像素数降到约 1/3.5），由 GPU 下采样到屏幕。依据是用户明确的偏好「更在意顺滑、能接受稍糊」，同时也能消除这类素材的卡顿与恢复切换。
- **帧率不影响解码分辨率**：50/60fps 素材与其它素材走同一条规则（按屏幕像素解码——FIT 按适配尺寸、FILL/STRETCH 按屏幕长边，上限 3200）。实测：模拟器（1600×2560、拉伸）中 2160×3840@60fps 解码为 **1372×2440**（此前 1920 上限版本为 1080×1920）。清晰度最大化后，呈现帧率就完全取决于设备 GPU 的填充能力（平板这类 60fps 素材大约 34–48fps 或更低），而**播放速度始终 1:1**（节奏按帧时间戳推进）。曾实验过的「帧率优先/画质优先」开关与固定 1920 上限均已移除。
- 另：视频铺满屏幕（填充/拉伸/全屏适应）时不再每帧 `glClear` 整屏——不透明画面本来就会覆盖每一个像素，省下的正是一次全屏填充（60fps 场景下由 GPU 填充率决定帧数的关键开销）。只有「适应」留下黑边时才清屏。
- 视频播放节奏按**帧时间戳**（`bufferInfo.presentationTimeUs`）推进，而不是按容器里可能缺失/异常的 `KEY_FRAME_RATE` 计算固定间隔。旧实现把缺失帧率兜底成 15fps，导致帧率低于 15（如 1fps 的延时/幻灯片视频）的素材按 15fps 消费，3.4 秒的视频 0.85 秒就循环一次（日志里连续 `Video started`）；帧率缺失的 30fps 素材则会变成慢动作。现在只保留 16ms 的帧间隔下限（防止 60fps+ 素材空转），省电模式仍按每帧 ≥1s 节流。

### 8.6 视频壁纸声音（可选，默认关闭）

设置项 `video_sound_enabled`（设置 →「切换过渡动画」下方「视频壁纸播放声音」，默认**关**）。
打开后只在**桌面壁纸可见**时出声，进入应用/熄屏/锁屏/系统壁纸预览界面都会静音。

- **独立音频线程**：`WallpaperRenderer` 里视频解码线程只负责画面（按帧时间戳推进，任何阻塞都会卡住播放），
  音频跑在单独的 `VideoAudio` 守护线程上，自带 `MediaExtractor` + 音频 `MediaCodec` + `AudioTrack`，
  线程优先级为 `THREAD_PRIORITY_AUDIO`（不是 BACKGROUND：视频解码 + GL 上传同时跑时，音频线程被饿到就会
  出现爆音/断音，而画面优先级的活可以让路）。
- **管线全程复用（v2 关键修复）**：`MediaExtractor` + 音频 `MediaCodec` 在整段视频期间**只创建一次**，
  每个播放周期用 `seekTo(0) + codec.flush()` 回头，`AudioTrack` 更是整个渲染器共用一个
  （`audioSession` 字段）。v1 是"每个周期重建整条管线并 pause+flush 音轨"，平板日志里表现为
  `Video pass` 每 5.7 秒一次、每次都跟着 `Flushed=2048` 与 `thread finished/started` 成对出现 —— 听感就是
  **每循环一次都有断口和咔哒声**。现在 15 个连续循环里只有一次 `Video audio started`、`Flushed=0`。
- **音画按播放周期对齐**：视频每开始一个播放周期都会执行 `signalVideoPassStart(gen)`（把 `videoPassCounter`
  +1 并唤醒等待者；带 gen 校验，已作废的旧解码线程不能冒充新视频的首个周期），音频线程在
  `awaitNextVideoPass()` 里等到信号才回头续播。因为音频的 350ms 缓冲足以覆盖视频重建解码器的
  50–150ms，听感连续；同时**不会累积漂移**（用 `MediaPlayer.setLooping` 那种做法每圈慢一个解码器启动时间，
  几十圈后就明显对不上）。`startVideo` 会把 `videoPassCounter` 归零，避免新视频一开始就先响 50ms 再回头。
- **不阻塞、不截断**：PCM 一律用 `AudioTrack.WRITE_NON_BLOCKING` 写入 + 5ms 重试（阻塞写在这里是致命的：
  壁纸不可见时 `AudioTrack` 暂停、缓冲区永不消费，写线程会挂到屏幕亮起）；写入位置由
  `writeToTrack()` 归一化（框架是否自行推进 buffer position 属实现细节，靠它会导致重复发送同段 PCM）。
  隐藏时被打断的那段 PCM 会存进 `audioPending` 并在恢复后原样写回，不再丢掉半个 chunk（听感上的小 tick）。
- **输出格式以解码器为准**：`INFO_OUTPUT_FORMAT_CHANGED` 之后用**解码器输出**的采样率/声道/`KEY_PCM_ENCODING`
  重建音轨（HE-AAC、下混、float PCM 都和容器声明不一致；把 float PCM 当 16-bit 播会直接变噪音）。
  声道掩码支持 mono/stereo/5.1/7.1：多声道素材交给系统下混，而不是塞进立体声轨道（那样会 3 倍速播放）。
- **暂停/恢复与画面同一套规则**：复用 `powerSaveMode`（可见性/熄屏），日志成对出现
  `Video audio paused (wallpaper not visible)` / `Video audio resumed (wallpaper visible again)`；
  恢复时不会快进补播，和画面一样从停住的位置继续。
- **音量**：`AudioAttributes.USAGE_MEDIA` + `CONTENT_TYPE_MOVIE`，跟随系统媒体音量与静音开关，没有独立音量。
- **静音场景**：系统动态壁纸选择器的预览引擎（`isPreview`）强制不播放；切到图片/GIF、切换媒体、
  引擎销毁都会立刻（`Thread.interrupt()` + 生成号失效 + `AudioTrack.pause()`）静音。
- **不做无意义的分配**：壁纸不可见时**不创建** `AudioTrack`（平板日志里出现过"开始播放 → 1ms 后暂停"，
  纯属白建一条轨道），改为可见后的第一帧再建，之后整机一直复用这一条。
- **日志卫生**：切换/旋转把上一段视频的 `Codec` 拆掉时，`Decode pass interrupted` /
  `Video GL setup failed` 现在按"被取代（superseded）"降为 D 级（`Decode pass ended (superseded)` /
  `Video GL setup abandoned (superseded)`），只有真正在当前视频上发生的失败才记 E —— 否则平板日志里
  每次重设壁纸都会冒出两条假错误，真实问题反而被淹没。
- **冷启动竞态**：Room 的设置流是异步首次发射，渲染器可能先于它出生（平板日志里出现过"视频在放、音频线程
  已退出"）。渲染器创建后额外同步读一次设置（`getBool(VIDEO_SOUND_ENABLED)`），确保首段视频就有声。
- **无音轨素材**：探测不到 `audio/` 轨道时只记一条 `Video has no audio track: <uri>` 就不再重试；
  云盘/SAF 源的音频打开有 12 秒超时保护，且优先用视频侧已经落地的缓存副本。
- **可测部分**：`engine/VideoSound.kt` 把采样率兜底、声道/掩码映射、PCM 编码映射、
  `AudioTrack` 缓冲区大小算成纯函数，由 `VideoSoundTest` 覆盖（7 条）。
- **实测（AOSP 14 模拟器，1600×2560，release 包）**：5.7 秒、44100Hz 立体声 AAC 素材 →
  `Video audio started: 44100Hz 2ch pcm=16bit buffer=350ms mime=audio/mp4a-latm`，随后 **15 个连续循环
  没有任何一次音频重启**；`dumpsys media.audio_flinger` 中本进程 track 为
  `PCM16 / mask 3 / 44100Hz`，`Flushed=0`；回桌面 Active、打开应用变暂停、关闭开关后 track 从列表消失。
  平板（Redmi 1440×3200）实测 `Underruns=0`；模拟器因音频 HAL 为软件模拟，长时间统计里会出现少量
  underrun 计数，但在新的 AUDIO 优先级 + 350ms 缓冲下与真实机表现一致地连续可听。

---

## 九、构建配置

### 9.1 编译选项

- `versionCode = 2`，`versionName = "1.1"`
- `compileSdk = 34`，`minSdk = 26`，`targetSdk = 34`
- `jvmTarget = "17"`，`kotlinCompilerExtensionVersion = "1.5.8"`

### 9.2 Release 构建

- `isMinifyEnabled = true`（R8 混淆）+ `isShrinkResources = true`
- ProGuard 规则：保留 Room 实体/数据库、Coil、协程内部类

### 9.3 GitHub Actions CI

每次 push 到 main 执行 `gradle assembleDebug`，产出 debug APK 工件（保留 30 天）。

---

## 十、已知限制

1. **Android 15+ 后台限制**：长时间运行的前台服务可能在后台被系统停止，靠"回到前台自愈"和"壁纸引擎 watchdog"重启；极少数 OEM 杀进程场景下定时切换可能中断。
2. **启动器触摸转发**：部分 Android 16/17 启动器不把桌面触摸转发给壁纸窗口，双击切换失效 —— 用悬浮按钮兜底。
3. **视频格式**：依赖系统 MediaCodec 支持；MKV/AVI 等容器在部分设备无硬件解码器会失败并触发恢复切换。
4. **云盘/SAF 媒体**：网络挂载文件的首次打开可能较慢（15 秒超时保护），播放中网络中断由健康监控恢复。
5. **自动扫描时序**：WorkManager 周期任务的实际执行时间由系统调度，非精确间隔。
6. **内存使用**：分组网格自 4.9.160 起改为窗口化分页（只保留一段 200 张的窗口，其余画占位），超大分组不再全量入内存；缩略图仍由 Coil 的内存缓存管理，壁纸引擎另有视频解码缓冲 + GL 纹理开销。
