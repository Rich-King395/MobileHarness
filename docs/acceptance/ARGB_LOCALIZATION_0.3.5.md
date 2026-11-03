# ARGB 差异定位与 GUI 识别检查 · 0.3.5-diag

日期：2026-10-31。**本轮已把可复现差异定位到颜色空间／profile 解读为 sRGB ARGB 时的数值差异：原生有效像素字节保持一致，真实画面 RGB 每通道最多差 1、透明度不变。使用原生产 PNG 的清除键识别任务也达成。没有修改截图生产转换或模型落点。**

结论限定于 Pixel 4／Android 13、当前截图与解码链路及本轮样本；没有证明所有模型在两种颜色表示下输出完全一致。

## 构建与方法

安装版本 0.3.5-diag／versionCode 11，APK 32,715,320 字节，SHA-256 `f6da5251e69b14934ae454424c9975f41d206c45f9dedda1b1b075eeb6908dd2`。[构建记录](results/argb-localization-0.3.5/build.json)：签名验证通过，130 个 JVM 测试通过，失败／错误／跳过均为 0。新增 8 项统计测试覆盖通道幅度、直方图、alpha、区域边界、连通分量与非法输入。

新增内容为 DUMP 保护的 debug 诊断入口、纯统计分析器及同帧对照探针。生产截图转换、请求／响应适配、模型配置、提示词、坐标映射和 Harness 规则未改。原交接材料及三个提示包 manifest 哈希保持原值。

[计划](results/argb-localization-0.3.5/plan.json)分为：

1. 零联网分析 Home、Calculator 各两帧，以及 sRGB／Display P3、全不透明／含透明度的四张合成图。没有目标控件点击、输入或模型调用；仅启动 Home 和 Calculator 作为采样页面。
2. 一轮正常任务页的清除键识别检查，使用手机原配置和原生产 PNG，不向模型提供任何诊断副本或独立核验结果。

同帧比较使用截图编码前的软件 Bitmap 与同一 PNG 的默认解码结果。另用 `copyPixelsToBuffer()` 按有效行比较原生存储，排除 row padding；用 `getPixels()` 比较非预乘 sRGB ARGB。**没有使用稍后的第二张画面来解释同帧差异**，因此按键动画不进入此项比较。

## 位置与幅度

四帧真实截图均为 1080×2280，源图和默认解码图均为 ARGB_8888，Java 色彩空间 ID 为 7，PNG 为 8-bit RGBA，包含 293 字节的 iCCP chunk。

| 样本 | 不同 ARGB 像素 | A 最大绝对差 | R／G／B 最大绝对差 | 原生有效像素字节不同数 |
| --- | ---: | ---: | --- | ---: |
| HOME_1 | 3696 | 0 | 1／1／1 | 0 |
| HOME_2 | 3701 | 0 | 1／1／1 | 0 |
| CALCULATOR_1 | 11 | 0 | 1／0／1 | 0 |
| CALCULATOR_2 | 11 | 0 | 1／0／1 | 0 |

Calculator 两帧分布一致：

| 区域 | 不同像素 | 差异包围框 `[left, top, right, bottom)` |
| --- | ---: | --- |
| Clear 键中央 | 9 | `[92, 1044, 194, 1124)` |
| 结果预览 | 2 | `[926, 574, 946, 627)` |
| 公式、数字键、其余运算／功能键、系统栏 | 0 | 无 |

11 个像素均位于源图的高对比度边缘邻域，且差异像素前后均不透明。这里的“边缘”是相邻亮度差至少 24 的图形指标，不是 OCR 识别结果。Clear 完整区域与中央区域覆盖同一批 9 个点，**不能相加成 18 个**。

Home 差异全部位于 APP_CONTENT：包围框分别为 `[61,316,1020,1874)`、`[61,284,1020,1874)`，系统状态栏／导航栏为 0。本轮未进一步分割壁纸、图标或文字，不能声称这些差异具体属于哪类内容。完整通道直方图、区域统计和最大的 16 个四邻接连通分量见[原始固定统计](results/argb-localization-0.3.5/local-attempt1.json)，没有导出逐像素颜色或图像。

## 颜色对照与成因

对八个真实／合成样本，观察均为：

- 原生有效像素存储逐字节一致。
- 只复制默认解码 Bitmap，原 ARGB 差异仍保留。
- **只对诊断副本设置源 Java ColorSpace 后，ARGB 差异全部为 0，原生像素仍一致。**
- 指定源颜色空间解码也使差异归零。

合成图控制：sRGB 全不透明与带透明度样本均为 0；Display P3 全不透明样本有 187 点、带透明度样本有 134 点，默认读回 RGB 最大绝对差均为 1，A 为 0。P3 全不透明图也能复现，且真实差异像素均不透明，故本轮不支持把真实差异归因于 alpha 变化。

这些控制支持：**问题发生在颜色空间／profile 到 sRGB 数值的解读环节，未观察到 PNG 原生像素载荷丢失或空间位置变化。** 两侧 Java ColorSpace ID 相同，不保证底层 profile 的所有数值逐位一致。

机制参考：[Bitmap 文档](https://developer.android.com/reference/android/graphics/Bitmap)说明 `getPixels()` 返回非预乘 sRGB ARGB，原生 buffer 读取保留存储表示；[Android 13 SkICC 源码](https://android.googlesource.com/platform/external/skia/+/refs/heads/android13-release/src/core/SkICC.cpp)包含 ICC 参数定点序列化；[ColorSpace 源码](https://android.googlesource.com/platform/frameworks/base/+/4fda9095ba9b/graphics/java/android/graphics/ColorSpace.java)包含标准空间的容差匹配。因此 ICC 参数数值精度是合理机制解释，**本轮没有直接测出底层 ICC 矩阵／传递函数的差值，不能声称已经定位到某一行内部量化代码。**

主动转成 sRGB 的诊断解码并不总等价：P3 半透明合成图在此控制下出现更大数值差异，R／G／B 最大为 54／24／38。该控制从未用于模型，不能把“强制统一为 sRGB”当作自动修复。实际生产 PNG 保持原样。

## 对 GUI 识别的检查

预先固定的七档亮度阈值为 32／64／96／128／160／192／224：Calculator 所有阈值掩码均无翻转；Home 仅在阈值 32 有 8 个点翻转，其余为 0。它们是结构代理指标，不能等同于所有模型的视觉表征不变。

随后通过正常任务页、Home 自动启动模式、Harness 开启、额度 30，运行一次：打开 Calculator，点 Clear／AC 一次，使公式与预览为空，然后结束。该目标覆盖了本地分析发现的 9 个 Clear 中央差异点所在控件。

- [前置核验](results/argb-localization-0.3.5/recognition/precondition.json)：公式实际非空，仅导出布尔值，未输出原算式。
- GUI 3 次、Planner 0：launch_app、tap、finish 各 1 次，全部响应通过协议校验、动作结果 completed；无 error／control、重试或人工纠正。
- [独立终态](results/argb-localization-0.3.5/recognition/calc_recognition.oracle.json)：真实公式和预览均为空。
- [11 条最小事件](results/argb-localization-0.3.5/recognition/calc_recognition.events.jsonl)通过 Schema 1.1.0、连续序号及 3 对请求终态核验。会话 12.935 秒；未安装局部像素探针，正常截图不承担本地分析耗时。

因此，本轮**未观察到该差异使当前 GUI Agent 识别／执行失败**。正常任务未安装逐帧像素探针，其具体输入帧的差异数量未测量；前述 11 点来自本地独立采样，未宣称完全相同的那张 PNG 被送给模型。没有进行“重新标记副本与原图分别送模型”的反事实 A/B 试验，不能把一次正常点击写成全部模型输出等价证明。本次定位不要求修改生产图片或模型坐标，可以继续正常 GUI 试测。

最初一次前置窗口检查未取得计算器控件，[记录保留](results/argb-localization-0.3.5/recognition/precondition-attempt1.json)；唤醒并返回目标窗口后才开始唯一一轮模型任务，不计模型重试。

## 收尾与历史记录

像素探针每帧分析约 3.98–4.10 秒，仅用于本次独立 debug 入口；日常 GUI 不安装该观察器。原图 generation 和 PNG 字节未改变，副本、像素数组及临时 buffer 结束后释放／清零。没有保存截图、颜色栅格、原始回复、密钥或用户算式；额外导出仅含固定区域名、包围框、计数和通道幅度。普通最小日志契约未扩展。

旧版严格 ARGB 相等检查的 FAIL 和四键校准未完成记录保留，没有放宽阈值或改写旧实验。新结论解释本轮复现的同类读回差异，不能追溯定位旧每一帧像素，也不改写 0.3.3 原先的未确定归因。

控制器、service flags、observer、采集开关与临时范围均已恢复；原空授权范围、空草稿、未勾选的发送同意、Harness 开启及 30 步经[最终 UI 检查](results/argb-localization-0.3.5/final-ui.json)确认，无障碍与双控制入口状态见[就绪记录](results/argb-localization-0.3.5/final-readiness.json)。密钥与模型配置未修改。

[机器核验摘要](results/argb-localization-0.3.5/summary.json)可由 `tools/summarize_argb_localization035.py` 重新校验。原确定性设备矩阵未在本诊断版重复，不新增其覆盖声明。
