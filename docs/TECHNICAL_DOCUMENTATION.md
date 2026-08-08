# 壁纸切换 (WallpaperSwitcher) — 功能技术文档

> 版本：v1.0 | 最后更新：2026-08-08

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
| 最低 SDK | API 26 (Android 8.0) |
| 目标 SDK | API 34 (Android 14) |
| 构建工具 | Gradle KTS + KSP |

### 依赖清单

```
androidx.compose:compose-bom:2024.01.00
androidx.compose.material3:material3
androidx.compose.material:material-icons-extended
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
├── WallpaperSwitcherApp.kt          # Application 入口
├── data/                             # 数据层
│   ├── Entities.kt                   # Room 实体定义
│   ├── Daos.kt                       # 数据访问对象 (DAO)
│   ├── AppDatabase.kt               # Room 数据库
│   └── SettingsKeys.kt              # 设置键常量
├── engine/                           # 壁纸引擎
│   ├── BitmapUtils.kt               # 位图工具类
│   └── WallpaperApplier.kt          # 静态壁纸应用 (WallpaperManager)
├── receiver/                         # 广播接收器
│   ├── BootReceiver.kt              # 开机自启动
│   └── ScreenUnlockReceiver.kt      # 解锁切换触发
├── service/                          # 前台服务
│   └── WallpaperSwitchService.kt    # 定时切换服务
├── wallpaper/                        # 动态壁纸
│   └── LiveWallpaperService.kt      # 动态壁纸服务 (核心)
├── viewmodel/                        # 视图模型
│   └── WallpaperViewModel.kt        # 业务逻辑 + 状态管理
└── ui/                               # 界面层
    ├── MainActivity.kt               # 主 Activity
    ├── WallpaperSwitcherApp.kt       # Compose 根组件 + 导航
    ├── theme/Theme.kt                # Material 3 主题
    └── screens/
        ├── HomeScreen.kt             # 首页 (分组列表)
        ├── GroupDetailScreen.kt      # 分组详情 (图片网格)
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

**外键约束**：`groupId` → `wallpaper_groups.id`，级联删除 (ON DELETE CASCADE)。
**索引**：`groupId` 字段建立索引以加速查询。

#### app_settings (设置表)

| 字段 | 类型 | 说明 |
|------|------|------|
| key | String (PK) | 设置键名 |
| value | String | 设置值 (字符串存储) |

### 3.2 设置键定义

| 键名 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| service_enabled | Boolean | false | 自动切换服务是否开启 |
| double_tap_enabled | Boolean | true | 双击切换是否开启 |
| unlock_switch_enabled | Boolean | false | 解锁切换是否开启 |
| last_image_id | Long | 0 | 最后显示的图片 ID |
| sequential_index | Long | 0 | 顺序模式当前索引 |
| global_interval_ms | Long | 60000 | 切换间隔 (毫秒) |
| global_switch_mode | String | "RANDOM" | 切换模式: RANDOM/SEQUENTIAL/SHUFFLE |
| global_scale_mode | String | "FIT" | 缩放模式: FILL/FIT/STRETCH |
| shuffle_shown_ids | String | "" | 洗牌模式已显示 ID (逗号分隔) |
| shuffle_all_count | Long | 0 | 洗牌模式总图片数 |

### 3.3 数据库版本迁移

- **版本 1 → 2**：`wallpaper_images` 表新增 `mediaType`、`isFromFolder`、`folderPath` 三个字段。
- **兜底策略**：`fallbackToDestructiveMigration()` — 如果迁移失败，销毁重建数据库。

---

## 四、功能模块详解

### 4.1 分组管理

**功能**：
- 创建、重命名、删除壁纸分组
- 每个分组独立启用/禁用
- 分组内的壁纸独立管理

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
| 文件夹导入 | `ActivityResultContracts.OpenDocumentTree` + 递归扫描 | 自动扫描子文件夹 |

**支持的媒体格式**：
- 图片：JPG、JPEG、PNG、WebP、BMP
- 动图：GIF
- 视频：MP4、MKV、WebM、AVI、MOV、3GP

**媒体类型检测**：通过文件扩展名自动判断 `mediaType` 字段。

**文件夹导入优化**：
- 递归扫描子文件夹
- 每 100 张一批 `insertAll` 写入数据库
- 通过 `isActive` 检测支持取消
- 显示扫描进度

### 4.3 壁纸切换

#### 4.3.1 切换模式

| 模式 | 枚举值 | 算法 |
|------|--------|------|
| 随机 | `RANDOM` | 从启用分组中随机选取，排除当前显示的图片 |
| 顺序 | `SEQUENTIAL` | 按添加顺序依次切换，使用 `sequential_index` 记录当前位置 |
| 洗牌 | `SHUFFLE` | 随机不重复，全部显示完后重新洗牌。状态持久化到数据库以应对引擎重建 |

#### 4.3.2 切换触发方式

| 触发方式 | 实现组件 | 触发条件 |
|----------|----------|----------|
| 定时切换 | `WallpaperSwitchService` | 前台服务定时发送广播 |
| 双击切换 | `GestureDetector` in `LiveWallpaperService` | 动态壁纸模式下双击屏幕 |
| 解锁切换 | `ScreenUnlockReceiver` | 监听 `ACTION_USER_PRESENT` 广播 |
| 手动切换 | `switchNow()` | 用户在 App 内点击"立即切换" |
| 设置壁纸 | `setImageAsWallpaper()` | 用户选择特定图片/视频设为壁纸 |

#### 4.3.3 切换核心流程

```
触发源 (定时/双击/解锁/手动)
    ↓
引擎是否运行？
├─ 是 → 发送 ACTION_SWITCH 广播 (可携带 EXTRA_TARGET_ID)
│        LiveWallpaperService.switchReceiver 接收
│        doSwitch(targetId)
└─ 否 → WallpaperApplier.applyNext() → WallpaperManager.setBitmap (静态壁纸)
    ↓
从数据库获取下一个图片 (pickNextImage)
    ↓
更新 LAST_IMAGE_ID 到数据库
    ↓
停止当前媒体 (stopVideo / pauseGif)
    ↓
根据 mediaType 分发:
  ├── IMAGE → loadBitmap → showBitmap (Canvas)
  ├── VIDEO → startVideo (MediaPlayer + MediaMetadataRetriever)
  └── GIF → playGif (ImageDecoder + AnimatedImageDrawable)
```

### 4.4 缩放模式

| 模式 | 枚举值 | 说明 |
|------|--------|------|
| 填充 | `FILL` | 裁剪多余部分，填满屏幕，保持比例 |
| 适应 | `FIT` | 完整显示图片，可能有黑边 |
| 拉伸 | `STRETCH` | 强制拉伸填满屏幕，不保持比例 |

**实现**：`calcDestRect()` 计算目标矩形，通过 `Canvas.drawBitmap(bitmap, null, destRect, null)` 绘制。

### 4.5 视频壁纸

#### 架构

```
MediaPlayer (muted, isLooping=true)  → 负责播放时序和循环
MediaMetadataRetriever               → 负责逐帧提取 (OPTION_CLOSEST)
帧缓冲队列 (LinkedBlockingQueue<Bitmap>, 容量8) → 生产者/消费者解耦
主线程 Handler + postDelayed          → 帧渲染定时器
```

#### 详细流程

**启动** (`startVideo`)：
1. 创建 `MediaPlayer`：设置数据源、静音、循环、prepare、start
2. 创建 `MediaMetadataRetriever`：设置数据源、读取视频元数据 (尺寸、帧率、时长)
3. 启动提取协程 (`runVideoExtractor`)：在 IO 线程逐帧提取
4. 启动渲染循环 (`startFrameRenderer`)：在主线程定时消费帧

**帧提取** (`runVideoExtractor`)：
- 在 IO 线程运行
- 使用 `OPTION_CLOSEST` 逐帧精确解码
- 提取的 Bitmap 放入 `LinkedBlockingQueue<Bitmap>(8)`
- 缓冲满时用 `yield()` 协作式等待
- 无额外 delay，提取全速运行

**帧渲染** (`startFrameRenderer`)：
- 在主线程通过 `Handler.postDelayed` 定时触发
- 每 tick 从缓冲队列 poll 最多 2 帧 (追帧策略)
- 保留最新帧，丢弃中间帧
- 调用 `showVideoFrame` 绘制到 Canvas

**停止** (`stopVideo`)：
- 取消提取协程和渲染 Job
- 清空帧缓冲队列
- 在主线程释放 MediaPlayer (`setSurface(null)` → `stop` → `release`)
- 在 IO 线程释放 MediaMetadataRetriever

#### 视频帧率优化

| 优化项 | 说明 |
|--------|------|
| 生产者/消费者解耦 | 提取和渲染独立运行，互不阻塞 |
| 帧缓冲队列 (8帧) | 提取提前跑，渲染有库存 |
| 无 delay 提取 | 提取全速运行，缓冲背压控制节奏 |
| 追帧策略 | 渲染每 tick 消费最多 2 帧，快速追上 |
| 主线程 Canvas | SurfaceHolder 操作在主线程，保证线程安全 |
| CancellationException 不吞没 | 协程正确取消，不死锁 |

### 4.6 GIF 壁纸

**实现**：
- API 28+：使用 `ImageDecoder` + `AnimatedImageDrawable`
- 通过 `Canvas` 逐帧绘制 GIF 动画
- 帧间隔 33ms (~30fps)
- `repeatCount = -1` 无限循环

### 4.7 图片壁纸

**实现**：
- `BitmapUtils.loadBitmap()` 加载图片
- 两次解码策略：第一次读取尺寸 (`inJustDecodeBounds`)，第二次实际解码
- 仅在图片超过屏幕 4 倍时降采样，保持画质
- 使用 `ARGB_8888` 配置保持全色深

### 4.8 设置壁纸

**流程** (`setImageAsWallpaper`)：
1. 保存目标图片 ID 到数据库 (`LAST_IMAGE_ID`)
2. 如果壁纸引擎已运行，发送 `ACTION_SWITCH` 广播 (携带 `EXTRA_TARGET_ID`)
3. 如果壁纸引擎未运行 (`!engineRunning`)，通过 `WallpaperApplier.apply()` 直接设置静态壁纸 (视频/GIF 取首帧)

**设置动态壁纸** (`setAsLiveWallpaper`)：
1. 保存目标图片 ID 到数据库 (`LAST_IMAGE_ID`)
2. 引擎已运行时发送广播切换；未运行时启动系统动态壁纸选择器

**引擎运行状态检测**：
- `LiveWallpaperService.engineRunning` 静态标志
- `onCreate` 设为 `true`，`onDestroy` 设为 `false`
- 避免不必要的系统 picker 启动 (会销毁引擎)

---

## 五、服务与后台组件

### 5.1 WallpaperSwitchService (定时切换服务)

**类型**：前台服务 (Foreground Service)

**通知**：
- 通知渠道：`wallpaper_switch_service` (低优先级，无角标)
- 通知 ID：1001
- 通知内容："壁纸切换" / "壁纸自动切换中"
- 点击通知打开 MainActivity

**工作流程**：
1. 启动后立即执行一次切换
2. 进入循环：等待 `global_interval_ms` 时间后切换
3. 引擎运行时通过广播切换动态壁纸；引擎未运行时通过 `WallpaperApplier` 切换静态壁纸
4. 如果没有启用的分组，自动停止服务以节省电量
5. 异常时 10 秒后重试

**Intent Actions**：
| Action | 说明 |
|--------|------|
| (默认) | 启动定时切换循环 |
| `SWITCH_NOW` | 立即发送一次切换广播 |
| `STOP` | 停止服务 |

**启动方式**：
- `start(context)` / `stop(context)` / `switchNow(context)` / `switchToTarget(context, targetId)`

### 5.2 BootReceiver (开机自启动)

**监听**：`ACTION_BOOT_COMPLETED`

**逻辑**：
1. 使用 `goAsync()` 延长广播处理时间
2. 在 IO 协程中读取数据库
3. 如果 `service_enabled` 为 true，启动 `WallpaperSwitchService`

### 5.3 ScreenUnlockReceiver (解锁切换)

**监听**：`ACTION_USER_PRESENT`

**逻辑**：
1. 使用 `goAsync()` + `withTimeout(8000ms)` 保护
2. 在 IO 协程中读取数据库
3. 如果 `unlock_switch_enabled` 为 true，发送 `ACTION_SWITCH` 广播

**注册方式**：在 `WallpaperSwitcherApp.onCreate()` 中编程式注册 (比 manifest 更可靠)。
- API 33+：使用 `RECEIVER_EXPORTED` 标志
- 优先级：`SYSTEM_HIGH_PRIORITY`

### 5.4 LiveWallpaperService (动态壁纸服务)

**类型**：Android `WallpaperService`

**引擎生命周期**：
```
onCreate → onCreateEngine → onSurfaceCreated → onSurfaceChanged
    → onVisibilityChanged(true/false) ↔ onVisibilityChanged(true/false)
    → onSurfaceDestroyed → onDestroy
```

**核心状态**：
| 状态 | 说明 |
|------|------|
| surfaceReady | Surface 是否就绪 |
| isVisible | 壁纸是否可见 |
| videoMode | 是否在视频模式 |
| videoPlaying | 视频是否正在播放 |
| videoStopFlag | 视频停止标志 |
| isSwitching | 是否正在切换中 (AtomicBoolean) |
| engineRunning | 引擎是否运行 (静态) |

**触摸事件**：
- 启用触摸事件 (`setTouchEventsEnabled(true)`)
- 使用 `GestureDetector` 检测双击
- 双击触发 `doSwitch(null)` (随机切换)

**可见性管理**：
- 可见时：恢复视频播放 / 重新绘制当前图片
- 不可见时：暂停视频 / 暂停 GIF 动画

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

使用 sealed class `Screen` 管理导航状态，不依赖 Navigation 组件。

### 6.2 HomeScreen (首页)

**功能**：
- 服务总开关卡片 (运行中/已停止 + Switch)
- "立即切换壁纸" 按钮
- 分组列表 (LazyColumn)
  - 每个分组卡片显示名称 + 启用/禁用开关
  - 点击进入分组详情
- "新建分组" 按钮 + 对话框

### 6.3 GroupDetailScreen (分组详情)

**功能**：
- 分组信息头部 (名称、图片数量、删除按钮)
- 操作栏："添加壁纸" + "批量操作" 按钮
- 批量操作栏：
  - 全选/取消全选
  - 已选数量显示
  - "删除所选" 按钮
- 导入进度显示
- 图片网格 (LazyVerticalGrid, 3列)
  - 缩略图 (Coil AsyncImage, 400x400)
  - 视频/GIF 类型指示器
  - 选择模式勾选框
  - 更多菜单 (设为壁纸 / 设为动态壁纸 / 删除)
- 分页加载 (每页 50 张，滚动到底部自动加载)

**添加壁纸对话框**：
- 选择单张图片
- 选择多张图片
- 从文件夹添加

**壁纸预览对话框**：
- 显示图片/视频预览 (800x800)
- 显示文件名和媒体类型
- 确认/取消按钮

### 6.4 SettingsScreen (设置)

**设置项**：

| 分组 | 设置项 | 类型 |
|------|--------|------|
| 服务 | 自动切换服务 | Switch |
| 壁纸设置 | 切换间隔 | 对话框选择 (10秒~24小时 + 自定义) |
| 壁纸设置 | 切换模式 | FilterChip (随机/顺序/洗牌) |
| 壁纸设置 | 缩放模式 | FilterChip (填充/适应/拉伸) |
| 触发方式 | 解锁切换 | Switch |
| 触发方式 | 双击切换 | Switch |
| 使用指南 | 如何使用 | 文本 |
| 使用指南 | 电量消耗 | 文本 |
| 关于 | 版本信息 | 文本 |

**间隔选择器**：预设选项 + 自定义输入 (最少 10 秒)。

---

## 七、权限声明

| 权限 | 用途 | API 级别 |
|------|------|----------|
| `READ_MEDIA_IMAGES` | 读取图片 | 33+ |
| `READ_MEDIA_VIDEO` | 读取视频 | 33+ |
| `READ_EXTERNAL_STORAGE` | 读取存储 | ≤32 |
| `FOREGROUND_SERVICE` | 前台服务 | 全版本 |
| `POST_NOTIFICATIONS` | 通知权限 | 33+ |
| `RECEIVE_BOOT_COMPLETED` | 开机自启动 | 全版本 |
| `SET_WALLPAPER` | 设置壁纸 | 全版本 |

---

## 八、关键技术实现

### 8.1 Room 数据库

- 单例模式 (`@Volatile` + `synchronized`)
- Flow 响应式查询 (UI 自动更新)
- 手动分页查询 (`LIMIT :limit OFFSET :offset`)
- 扩展函数简化设置读写 (`getBool`, `setBool`, `getLong`, `setLong`, `getString`, `setString`)

### 8.2 Coil 图片加载

- 全局自定义 `ImageLoader`
- 内存缓存：25% 可用内存
- 磁盘缓存：启用
- 硬件 Bitmap：禁用 (Canvas 兼容性)
- Bitmap 配置：`RGB_565` (16-bit，节省内存)
- 视频缩略图：`VideoFrameDecoder`

### 8.3 SAF (Storage Access Framework)

- 使用 `DocumentFile` 访问用户选择的文件夹
- `takePersistableUriPermission` 持久化权限
- 递归扫描子文件夹
- 支持 `content://` URI

### 8.4 协程使用

- `viewModelScope`：ViewModel 生命周期绑定
- `CoroutineScope(Dispatchers.IO + SupervisorJob())`：服务/引擎级作用域
- `SupervisorJob`：子协程失败不影响其他协程
- `CancellationException` 正确传播 (不被 catch 吞没)
- `goAsync()` + `withTimeout`：广播接收器中的安全异步操作

### 8.5 Canvas 渲染

- `SurfaceHolder.lockCanvas()` / `unlockCanvasAndPost()` 绘制
- 所有 Canvas 操作在主线程执行 (SurfaceHolder 线程安全要求)
- 可复用 `RectF` 对象避免每帧分配
- 缓存屏幕尺寸避免重复查询

---

## 九、构建配置

### 9.1 编译选项

- `compileSdk = 34`
- `minSdk = 26`
- `targetSdk = 34`
- `jvmTarget = "17"`
- `kotlinCompilerExtensionVersion = "1.5.8"`

### 9.2 Release 构建

- `isMinifyEnabled = true` (代码混淆)
- `isShrinkResources = true` (资源压缩)
- ProGuard 规则：`proguard-android-optimize.txt` + `proguard-rules.pro`

### 9.3 GitHub Actions CI

项目配置了 GitHub Actions 自动构建，在每次 push 时运行 Gradle 编译检查。

---

## 十、已知限制

1. **视频帧率**：`MediaMetadataRetriever.getFrameAtTime(OPTION_CLOSEST)` 每帧需 10-30ms，实际帧率取决于视频编码和关键帧间隔，通常 15-30fps。
2. **视频格式**：依赖系统 MediaMetadataRetriever 支持的格式，部分设备可能不支持某些编码。
3. **内存使用**：视频帧缓冲队列 (8帧 Bitmap) 在高分辨率视频下占用较多内存。
4. **电池消耗**：定时切换服务持续运行会消耗少量电量，但使用协程调度，开销极低。
5. **系统限制**：Android 8+ 对后台服务有严格限制，使用前台服务 + 通知保活。
