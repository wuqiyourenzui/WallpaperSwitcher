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
| clarity_mode | String | "auto" | 清晰度增强: auto/off/strong |
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
3. **更多配色**：预置色从 17 个扩到 **28 个**（按色相排列：紫罗兰→深紫→玫红→桃红→玫瑰红→红→砖红→
   深橙→橙→琥珀→黄→橄榄→绿→翡翠→青→深青→蓝→海洋蓝→靛蓝→紫→蓝灰→黑灰→棕→灰玫瑰…），
   对话框内容加 `heightIn(max = 420.dp)` + `verticalScroll`，7 行色卡在小屏上也能滚动查看。

**真机验证（Redmi 平板 25102RKBEC）**：

| 操作 | 结果 |
|---|---|
| 主题模式 = 浅色 | 截图平均亮度 **179.7** |
| 主题模式 = 深色 | **62.8** |
| 主题模式 = 跟随系统 | 随系统深色 → 53.9（设置项回到默认，行为与改动前一致） |
| 打开颜色对话框 | 28 个色项可见、可滚动；设置行副标题显示「跟随系统（Monet）」 |

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
6. **内存使用**：全量加载分组网格（数百张 200px 缩略图）在超大分组下占用较多内存；壁纸引擎另有视频解码缓冲 + GL 纹理开销。
