# 真实计算器截图与坐标诊断 · 0.3.4-diag

日期：2026-10-31。**本轮已观察到模型任务决策错误，没有观察到 APK 坐标转换或落点改写错误；截图严格像素往返保真仍有未解决项。** 两项分别登记，不能将本轮写成截图全链路 PASS，也不能追溯确定此前 0.3.3 任务的原因。

## 条件与改动

最终安装 0.3.4-diag／versionCode 10，APK 33,755,765 字节，SHA-256 `15c88b79e81714b236307a96beca5be7aa4a383ae2366a2593f55c3dfe4c0df4`。[构建记录](results/calculator-fidelity-0.3.4/build.json)：签名校验通过，现有 JVM 122 项通过，失败／错误／跳过均为 0。

生产侧仅增加 `BuildConfig.DEBUG` 保护、正常使用时未设置的内存观察器，查看编码源位图、即将提交的原生手势描述及真实回调。没有修改截图几何、坐标公式、执行分支、模型配置、提示词或预算。原交接材料及三个提示包 manifest 哈希保持原值，仍用提示包 1.2.0、适配器 1.3.0。

完整任务通过独立 debug 入口调用真实 Coordinator、请求组装器、QwenGateway、原单次 OkHttp 传输和 AccessibilityService；这不是正常任务页入口的重复验收。任务原文与上次相同：

> Open the authorized Calculator App. Use the calculator to add 23 and 47 then multiply that sum by 6. Leave the final result visible and finish. Do not open other Apps.

从 Home 开始，仅授权计算器，Harness 开启、GUI 30 步。无接管、GUI 重试或业务修正；独立答案 420 未进入模型输入。所有合法落点均按原动作对象派发，即使命中错误按键也不吸附、替换或拦截。[预先计划](results/calculator-fidelity-0.3.4/plan.json)保留了原四键校准计划，实际未完成。

## 证据与归因

| 层次 | 实测 |
| --- | --- |
| 实际请求图片 | 30/30 请求中的 Base64 解码字节与对应原 PNG 一致，没有本地裁剪、替换或重新缩放 |
| 原始回复与解析 | 30/30 原始工具名、参数 JSON 与解析动作一致，协议均 VALID |
| 规范化坐标到原生手势 | 29/29 提交路径、回调路径符合 `floor(value × full_dimension / 1000)`，同一帧、默认显示器，无落点改写 |
| 按键与实际效果 | 29/29 截图／派发时按键归属和页面状态一致；19/19 数字点击正确追加，29 个点击均 completed |
| 公式画面与时序 | 30/30 时间戳不早于本次采集请求；29/29 公式区域与稍后参考的暗色掩码完全一致，零个 RGB 通道差大于 8 的像素；参考前后节点状态稳定 |
| 独立终态 | 表达式 `23+47`、预览 `70`，G30／P0，budget_exhausted，没有合法 finish，任务未达成 |

实际模型选键：

```text
(2 → 3 → ＋ → 4 → 7 → × → 6 → 清空) × 3
→ 2 → 3 → ＋ → 4 → 7
```

模型没有先求出加法结果或输入括号，反复生成同一未完成任务的操作序列。请求、解析、原生路径及数字效果一致，支持 `MODEL_TASK_DECISION_FAILURE_OBSERVED`，并列保留 `SCREENSHOT_FIDELITY_OPEN`。这是对已观察动作序列的归因，不推测模型隐藏推理，也不宣称排除了所有截图因素。

见[完整固定诊断元数据](results/calculator-fidelity-0.3.4/workflow-attempt1.json)、[独立终态](results/calculator-fidelity-0.3.4/final-oracle.json)、[摘要](results/calculator-fidelity-0.3.4/summary.json)及[复核结论](results/calculator-fidelity-0.3.4/verdict.json)。92 条[最小事件](results/calculator-fidelity-0.3.4/workflow-attempt1.events.jsonl)通过 Schema 1.1.0、连续序号、同一会话和 30 对请求终态检查；无 error／control 事件。

## 保留的失败与未解决项

- [首次校准](results/calculator-fidelity-0.3.4/calibration-attempt1.json)受锁屏限制中止：0 截图、0 模型、0 点按。
- [第二次校准](results/calculator-fidelity-0.3.4/calibration-attempt2.json)只执行数字 1：路径、真实效果和公式区域截图变化通过，但 `Bitmap.sameAs` 未通过。[当时构建](results/calculator-fidelity-0.3.4/build-before-pixel-probe-fix.json)保留。
- 补充逐像素 ARGB 读取后，[最终校准](results/calculator-fidelity-0.3.4/calibration-final.json)首图仍有 5／2,462,400 个像素不同，按原严格条件在数字 1 后停止。两图都未通过严格比较，只有前图记录差异数量。色彩空间 ID 均为 7，alpha／premultiplied 标志一致；原因未证实。不能擅自说是 ±1 舍入或元数据误判。**四键校准仍为 FAIL，没有完成 1、5、9、0 全部校准。**
- 完整任务 30 帧也都未通过严格 ARGB 往返相等：计算器每帧差异 5–347 像素，Home 为 3756 像素。比较的是源软件位图与 PNG 解码图经 `getPixels()` 得到的 ARGB 值，不是直接比较 HardwareBuffer 原始字节。HTTP PNG 字节一致是另一项检查。
- 参考比较了 20 个键的中央区域；首帧全部暗色掩码一致，其后 28 帧各有一个区域不同，暗色掩码差异 13–171 像素。按键动画是可能解释，尚未证实；未保留差异区域的按键身份。公式 RGB 检查有通道容差 8，不能写成逐值完全相等。

下一项图像诊断应独立定位差异通道幅度与区域，区分颜色表示、动画和真正的空间偏移，不因此修改模型坐标或加入业务纠正。当前安装包不再更新。

## 方法边界和收尾

先按原生产边界取得原图，再等待固定 350ms 获取一次仅供内存比较的参考图，然后发送原图。参考图不替换模型图片、不调用第二次 `observe`、不更改 frame token；模型请求因此推迟，不能声称整体时延完全相同。诊断器原有的点按后 200ms 延迟已移除，避免掩盖下一张生产截图过早的问题。

辅助参考路径检查状态／控制器／服务／敏感页，并在比较后再次核对计算器节点，但未在回调边界独立复查前台包。不能扩展为严格逐 App 图像隔离保证；本次参考前后计算器状态均一致，未发生人工接管。后续调试版本应补齐该边界后再扩展场景。

原生路径核验是提交给 Android 的路径，不是被动读取目标 App 的原始 MotionEvent；实际数字效果提供了额外送达证据。[官方时间戳文档](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService.ScreenshotResult#getTimestamp())支持使用 uptime 比较捕获时间，不证明图片语义正确。

完整任务前另有一次锁屏期间的[零请求预检中止](results/calculator-fidelity-0.3.4/workflow-preflight-attempt1.json)，未截图或执行模型动作。解锁后只有一轮实际商业模型任务：GUI 30、Planner 0，没有隐藏重试。全部失败记录保留。

截图、参考图、坐标、像素摘要、表达式中间历史和原始回复只在内存处理，没有保存或导出。额外导出仅为固定按键枚举、尺寸、差异计数与布尔结论；普通最小日志未扩大内容。密钥及个人模型配置未修改或写出。

控制器、服务 flags 和停止入口已恢复，截图隐藏状态解除；空草稿、空授权范围、Harness 开启、30 步保持原值，见 [UI 收尾](results/calculator-fidelity-0.3.4/final-ui.json)和[服务收尾](results/calculator-fidelity-0.3.4/final-readiness.json)。没有事后将计算器改成 420。
