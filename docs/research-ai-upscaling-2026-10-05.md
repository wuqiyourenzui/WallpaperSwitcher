# Android 端 AI 画质增强 / 超分辨率调研

日期：2026-10-05
范围：为 WallpaperSwitcher（Kotlin + Compose，minSdk 26，EGL/OpenGL ES 2.0 动态壁纸引擎，
静态图以纹理四边形绘制、视频走 MediaCodec + SurfaceTexture 逐帧绘制）评估"低画质图片
与视频的画质增强"能落到什么实现。

说明：本报告的每条事实都尽量给出**一手来源**（官方仓库文件 / 官方页面 / 模型文件本
身）。本次网络环境无法访问 `raw.githubusercontent.com`（SSL 失败）与
`developer.android.com`（超时），相关结论已注明"未能取得一手来源"。

---

## 一、结论先行

1. **真神经网络（NN）的逐帧视频超分，当前在手机上不可行。**
   TensorFlow 官方的 TFLite 超分示例自己把"用蒸馏模型做视频超分"列为
   *Future work*（即当前模型不做视频）；Anime4K 的官方 README 也明确把
   waifu2x 与 Real-ESRGAN 归为**非实时**，只有 FSRCNNX / Anime4K 这类着色器
   方案是实时的。
2. **真 NN 的静态图超分可行，但要付出明确的代价**：TFLite 官方示例用的
   `ESRGAN.tflite` 实测 **4,993,712 字节（约 4.76 MiB）**，输入 **50×50**、
   输出 **200×200（4x）**，需要 TFLite 运行时（示例通过 NDK 用 C API + 可选 GPU
   delegate）。而且该模型的来源（TF Hub `captain-pool/esrgan-tf2`）**拿不到明确的
   模型许可证声明**：TF Hub 页面是 JS 应用、静态抓不到许可信息，对应的 GitHub 仓库
   404。**在许可证澄清之前，不建议把该模型打进 APK。**
3. **实时路线只有 GPU 着色器超分。** 两条主流开源实现：
   - **AMD FSR 1.0（MIT）**：核心是 EASU（Edge Adaptive Spatial Upsampling，单
     pass 自适应径向 Lanczos）+ RCAS（Robust Contrast Adaptive Sharpening），
     官方头文件明确"RCAS 必须在 EASU 之后单独一个 pass"；但 EASU 的参考实现用
     `gather4`，**不适合当前 GLES 2.0 引擎**（需要 GLES 3.1 / EXT_texture_gather）。
   - **Anime4K（MIT）**：官方 README 称其为"real-time anime upscaling/denoising
     algorithms"、"CNN 线条放大"（6 个变体），效果"similar to SRGANs"；但它是为
     mpv 写的多 pass 大着色器，移植到 GLES 的工程量与回归风险都高。
4. **厂商 SDK 不适用于本应用**：高通的 Snapdragon Game Super Resolution（SGSR）
   只有 Unity/URP 包（要求 Unity 6 + URP 17.3，v2 还是需要历史帧的 temporal 方案），
   是给 3D 游戏降低渲染分辨率再重建用的，不是给壁纸 App 的通用图片/视频超分 API。
5. **落入本应用的选择**：本次实现走**零新依赖的 GPU 实时超分**——
   4-tap Catmull-Rom 双三次采样（用 4 次硬件双线性取样复现 16 tap 双三次）
   + 提高强度的 unsharp，只在该素材被**放大 1.25x 以上**时启用，图片和视频共用
   同一套着色器通路。它是"实时超分"，不是神经网络；如果要把"AI"做实在，建议二期
   只对**静态图**做 NN 离线超分（模型/许可证问题先解决）。

---

## 二、方案对比

| 方案 | 真 NN？ | 图片 | 视频 | 实时性 | APK 体积 | 许可证 | 来源 |
|---|---|---|---|---|---|---|---|
| TFLite 示例 ESRGAN（50×50→200×200, 4x） | ✅ | ✅ 离线 | ❌（官方列为 future work） | 静态图离线 | +4.99 MB 模型 + TFLite 运行时 | 代码 Apache-2.0；**模型许可不明** | [README](https://github.com/tensorflow/examples/tree/master/lite/examples/super_resolution/android) / [SuperResolution.h](https://github.com/tensorflow/examples/blob/master/lite/examples/super_resolution/android/app/src/main/cc/SuperResolution.h) |
| waifu2x / Real-ESRGAN（ncnn/Vulkan） | ✅ | ✅ 离线 | ⚠️ 小模型也非实时 | ❌ | 模型 2.4–63.9 MB + ncnn | BSD-3 / MIT | [Real-ESRGAN releases](https://github.com/xinntao/Real-ESRGAN/releases) / [waifu2x-ncnn-vulkan](https://github.com/nihui/waifu2x-ncnn-vulkan) |
| Anime4K（GLSL） | ❌（CNN 衍生的固定着色器） | ✅ | ✅ | ✅ | ~0 | MIT | [Anime4K](https://github.com/bloc97/Anime4K) |
| FSR 1.0 EASU+RCAS | ❌ | ✅ | ✅ | ✅（需 2 pass / gather4） | ~0 | MIT | [FidelityFX-FSR](https://github.com/GPUOpen-Effects/FidelityFX-FSR) |
| Snapdragon SGSR | ❌（v1 空间 / v2 temporal） | ❌ | ❌（游戏渲染管线） | ✅ | 不适用 | BSD-3（Unity 包） | [SGSR for Unity](https://github.com/SnapdragonGameStudios/com.qualcomm.snapdragon.sgsr.for.unity) |
| 本次实现：4-tap 双三次 + 自适应锐化 | ❌ | ✅ | ✅ | ✅（放大时 4 次额外取样） | 0 | 本仓库代码 | 见 §四 |

---

## 三、各方案细节与来源

### 3.1 TFLite 官方超分示例（真 NN）

- 模型：ESRGAN（[ESRGAN 论文](https://arxiv.org/abs/1809.00219)），由 TF Hub
  [`captain-pool/esrgan-tf2/1`](https://tfhub.dev/captain-pool/esrgan-tf2/1) 转换而来
  （见示例 README 的 "The model used here is ESRGAN ... converted from this
  implementation"）。
- 输入输出：`kInputImageHeight = 50`、`kInputImageWidth = 50`、
  `kUpscaleFactor = 4` → 200×200（[SuperResolution.h](https://github.com/tensorflow/examples/blob/master/lite/examples/super_resolution/android/app/src/main/cc/SuperResolution.h)）。
  **按 patch 推理**，一张 1280×720 需要 25×14 ≈ 350 个 patch。
- 模型文件：`https://storage.googleapis.com/download.tensorflow.org/models/tflite/esrgan/ESRGAN.tflite`
  （示例的 [download.gradle](https://github.com/tensorflow/examples/blob/master/lite/examples/super_resolution/android/app/download.gradle)）；
  2026-10-05 实测 HTTP HEAD `Content-Length = 4993712` 字节。
- **视频**：示例 README 的 "Future work: Use a distilled version to do video
  super resolution" —— 当前模型不做视频。
- **许可证**：示例代码 Apache-2.0（文件头）；模型本体的许可证在 TF Hub 静态页面上
  没有声明，`captain-pool/ESRGAN-TF2` 在 GitHub 上 404（本次核实）。**未能取得
  模型许可证的一手来源。**

### 3.2 Anime4K（实时 CNN 衍生着色器）

- 官方 README 原文（[仓库](https://github.com/bloc97/Anime4K)）：
  "Anime4K is a set of open-source, high-quality real-time anime upscaling/denoising
  algorithms that can be implemented in any programming language"；
  "What Anime4K does provide is a way to upscale, in real time, 1080p anime for 4K
  screens while providing a similar *effect* to SRGANs and being much better than
  waifu2x"；特性列表含 "Real-time, high quality line art CNN upscalers. (6 variants)"；
  对比页写明 "FSRCNNX and Anime4K are real-time while waifu2x and Real-ESRGAN are not"。
- 许可证：MIT（GitHub API 仓库元数据；`LICENSE` 文件原文）。
- 落地风险：shader 为 mpv 的多 pass GLSL（v4 有多个 CNN/Denoise 变体），移植到
  GLES 2.0 引擎必须重写成单 pass 或加 FBO 多 pass；本仓库当前的无 FBO 单 pass
  渲染结构不适合一次性引入。

### 3.3 AMD FSR 1.0（EASU / RCAS）

- 官方头文件 [ffx-fsr/ffx_fsr1.h](https://github.com/GPUOpen-Effects/FidelityFX-FSR/blob/master/ffx-fsr/ffx_fsr1.h)
  原文："The core functions are EASU and RCAS"；"[EASU] Edge Adaptive Spatial
  Upsampling ....... 1x to 4x area range spatial scaling, clamped adaptive elliptical
  filter"；"[RCAS] Robust Contrast Adaptive Sharpening .... A non-scaling variation on
  CAS"；"RCAS needs to be applied after EASU as a separate pass"；"EASU runs in a
  single pass, so it applies a directionally and anisotropically adaptive radial
  lanczos"；"EASU uses gather4 to reduce position computation logic"。
- 许可证：MIT（仓库元数据 + `license.txt`）。
- 结论：算法本身不是神经网络；`gather4` 依赖让它无法直接塞进 GLES 2.0 的
  `texture2D` 着色器，需要 GLES 3.1 或 EXT_texture_gather。

### 3.4 ncnn / waifu2x / Real-ESRGAN（真 NN，端侧推理）

- ncnn：[Tencent/ncnn](https://github.com/Tencent/ncnn)，源码 BSD-3-Clause
  （LICENSE 原文：source code licensed under BSD 3-Clause，另含第三方组件），
  提供 Android + Vulkan 预编译包（README 的 Android 下载表）。
- waifu2x-ncnn-vulkan：[nihui/waifu2x-ncnn-vulkan](https://github.com/nihui/waifu2x-ncnn-vulkan)，
  MIT；README 面向 Windows/Linux/macOS **桌面可执行文件**，命令行批处理图片，
  没有官方 Android APK/库封装；waifu2x 本体 [nagadomi/waifu2x](https://github.com/nagadomi/waifu2x)
  也是 MIT。
- Real-ESRGAN：[xinntao/Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN)，
  BSD-3-Clause；官方 release 资产实测体积（GitHub Releases API，2026-10-05）：
  `realesr-animevideov3.pth` **2.4 MB**、`realesr-general-x4v3.pth` **4.7 MB**、
  `RealESRGAN_x4plus_anime_6B.pth` **17.1 MB**、`RealESRGAN_x4plus.pth` **63.9 MB**。
  论文：[Real-ESRGAN (arXiv:2107.10833)](https://arxiv.org/abs/2107.10833)。
- 实时性：Anime4K 官方对比把 waifu2x / Real-ESRGAN 列为非实时；**本次未能找到
  手机端逐帧 NN 超分的官方耗时 benchmark（未取得一手来源）**，但即使取 2.4 MB 的
  animevideov3，也是"每帧一次前向"的负载，与 30/60fps 的壁纸视频不匹配。

### 3.5 厂商 SDK 与平台 API

- 高通 SGSR：官方 [SnapdragonGameStudios/com.qualcomm.snapdragon.sgsr.for.unity](https://github.com/SnapdragonGameStudios/com.qualcomm.snapdragon.sgsr.for.unity)
  （BSD-3-Clause）README：要求 **Unity 6 + URP 17.3**；v1 是 spatial
  "Lanczos-like filter with adaptive edge-directed sharpening"，v2 是 temporal
  （需要历史帧）。它是游戏渲染管线组件，不是通用图片/视频超分 SDK。
- 联发科 / ARM：本次未找到可下载、可集成的一手 SDK 页面（未取得一手来源）。
- Android 平台 API：本环境无法访问 `developer.android.com`（超时），**未能取得
  "平台没有公开超分 API" 的官方出处**；就本次检索到的 Android 14/15 媒体 API
  面（Ultra HDR 等）而言，没有看到超分 API。此条留待后续用官方文档复核。

---

## 四、对 WallpaperSwitcher 的落地建议

### 已实现（本次）

- 设置 →「壁纸设置」新增开关（默认关）：**画质增强（超分）**。
- 渲染端在素材被放大 ≥1.25x 时启用：4-tap Catmull-Rom 双三次采样
  （`uEnhance` / `uSrcTexel`，图像与视频共用同一 fragment shader 通路）
  + 提高强度的 unsharp；放大 <1.25x 或关闭开关时走原路径，行为与成本不变。
- 强度曲线：`WallpaperGeometry.enhancementStrength`，1.25x→0、4x→1；
  FIT 取较小轴、FILL/STRETCH 取较大轴。
- 着色器有**自动回退**：新 shader 编译失败时用改动前的 shader 源，增强静默失效，
  不会出现黑屏。

### 建议的二期（需要你确认）

只对**静态图**做真 NN 离线超分：用 TFLite + ESRGAN.tflite（约 4.76 MB）在解码后
做 4x 放大并缓存。上线前必须先解决 **模型许可证**（当前拿不到一手许可声明）与
patch 推理耗时（50×50 patch，一张 720p 约 350 个 patch）两个问题；视频继续用本
次的实时 GPU 路径。

### 给实现的硬性约束（15 行内）

1. 视频逐帧 NN 超分不做（官方示例与 Anime4K 对比都指向不可行）。
2. 实时增强必须只在"被放大"时生效，原生/缩小素材零额外成本。
3. 任何新 shader 必须有旧 shader 回退，编译失败不能黑屏。
4. 若引入 TFLite：模型许可证未澄清前不得进包。
5. 若做 NN 静态图：限制在 4x、按 patch 推理、结果缓存（内存 + 可选磁盘）。
6. 不做 Unity/游戏 SDK（SGSR）集成。
7. 不引入 GPL 组件；MIT/BSD/Apache-2.0 可接受，需在 About/文档中列明。
8. 设置项与文案要区分"真 NN 超分"与"实时 GPU 超分"，不要混称。
