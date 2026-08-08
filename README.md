# 壁纸切换 (WallpaperSwitcher)

一款轻量级 Android 壁纸自动切换应用，支持分组管理、多种切换模式和图片适配方式。

## 功能特性

### 🔄 切换方式
- **定时切换**：支持最低 10 秒的定时切换间隔
- **双击切换**：双击屏幕即可切换（动态壁纸模式）
- **解锁切换**：每次解锁屏幕自动切换壁纸
- **手动切换**：一键立即切换

### 📁 壁纸来源
- 添加单张图片
- 批量选择多张图片
- 添加整个文件夹（自动扫描图片）
- 支持 JPG / PNG / WebP / BMP / GIF / MP4 格式

### 📂 分组管理
- 自定义壁纸分组
- 全局设置切换间隔、切换模式、缩放模式
- 分组可独立启用/禁用
- 支持批量选择和删除

### 🎲 切换模式
- **随机**：完全随机选取
- **顺序**：按添加顺序依次切换
- **洗牌**：随机不重复，全部轮完后重新洗牌

### 🖼️ 图片适配
- **填充**：裁剪多余部分，填满屏幕，保持比例
- **适应**：完整显示图片，可能有黑边
- **拉伸**：强制拉伸填满屏幕

### ⚡ 低功耗
- 使用 Kotlin 协程调度，不依赖 AlarmManager
- 前台服务占用内存极小
- 支持开机自启动

## 架构

```
├── data/           # Room 数据库 (Entity, DAO, Database)
├── engine/         # 壁纸引擎核心逻辑（位图加载、静态壁纸应用）
├── service/        # 前台服务
├── receiver/       # 广播接收器 (开机、解锁)
├── wallpaper/      # 动态壁纸服务
├── viewmodel/      # ViewModel
└── ui/
    ├── theme/      # Material 3 主题
    └── screens/    # Compose 界面
```

## 技术栈

- **语言**：Kotlin
- **UI**：Jetpack Compose + Material 3
- **数据库**：Room
- **图片加载**：Coil
- **异步**：Kotlin Coroutines + Flow
- **架构**：MVVM

## 构建

1. 用 Android Studio 打开项目根目录
2. 同步 Gradle
3. 连接设备或启动模拟器
4. Run → 运行

## 权限说明

| 权限 | 用途 |
|------|------|
| READ_MEDIA_IMAGES | 读取图片（Android 13+） |
| READ_MEDIA_VIDEO | 读取视频（Android 13+） |
| READ_EXTERNAL_STORAGE | 读取存储（Android 12 及以下） |
| FOREGROUND_SERVICE | 后台前台服务 |
| POST_NOTIFICATIONS | 通知（Android 13+） |
| RECEIVE_BOOT_COMPLETED | 开机自启动 |
| SET_WALLPAPER | 设置壁纸 |

## 使用指南

### 静态壁纸模式（后台服务）
1. 打开应用 → 新建分组
2. 进入分组 → 添加壁纸图片
3. 设置切换间隔和模式
4. 进入图片 → 「设为壁纸」应用静态壁纸
5. 回到首页 → 开启自动切换，后台会定时切换静态壁纸

### 动态壁纸模式（支持双击切换、视频/GIF 动画）
1. 系统设置 → 壁纸 → 动态壁纸
2. 选择「动态壁纸切换」
3. 设置为壁纸
4. 双击屏幕即可切换
