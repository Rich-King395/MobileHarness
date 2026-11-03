# Pixel 4 远端协议与服务连接检查

日期：2026-10-30。安装版本：0.1.2-dev（versionCode 3）；Android 13。提示词包维持冻结的 v1.1.0。

GUI 和 Planner 已分别通过一次真实服务协议检查。这里只证明各自请求能被配置的服务接受、响应能被当前适配器解析；没有执行模型动作，也没有验证真实任务成功、完整接管恢复或第二次 Planner 的远端效果。

## 请求及阻断记录

| 执行记录 | GUI 实际尝试 | Planner 实际尝试 | 结果与解释 |
| --- | ---: | ---: | --- |
| [首次](results/pixel4-remote-0.1.2-attempt1.json) | 1 | 0 | GUI 传输失败；之后检查手机无默认网络，未得到 HTTP 响应，不能据此评价端点兼容性 |
| [联网后](results/pixel4-remote-0.1.2-attempt2.json) | 1 | 0 | GUI 原生 `finish` 工具响应通过；Planner 因本机未保存该角色 Key 而未发送；整次诊断保持 FAIL |
| [Planner 本地检查](results/pixel4-remote-0.1.2-planner-preflight.json) | 0 | 0 | 无障碍未连接，BLOCKED；不算模型调用失败，不增加实际请求数 |
| [Planner 单独验证](results/pixel4-remote-0.1.2-planner.json) | 0 | 1 | PASS；5,714 ms，第一次 Planner 的 `VERIFY` 四字段响应通过严格校验 |

已记录的真实请求总计 GUI 2 次、Planner 1 次；失败尝试保留，没有隐藏重试。所有远端检查的模型动作执行数均为 0。`finish`／`VERIFY` 只是响应类别，不是任务结果评分。

检查使用 debug 专用入口和独立 fixture 合成页面。每个请求从该页面获取必要的新截图，仅在手机内存暂存，发送到用户配置的对应服务。返回动作不派发，Planner 的 VERIFY 不继续进入查证或 P2。API Key 在手机本地解密供对应 HTTPS 请求使用；电脑只读取无内容的诊断结果，没有读取 Key、截图、完整请求或响应正文。

## 无障碍开关变化与当前状态

用户报告开启后会关闭。系统进程退出记录中，最近几次停止均标记为 `installPackageLI` 强停，另有更早的 instrumentation 开始／结束强停；这些属于开发安装／测试干扰。它们不能证明每一次开关变化都由安装造成。

本轮没有再次安装、强停、清数据或使用 instrumentation。两次检查之间曾看到 Enabled／Bound 暂时为空，之后恢复，同一应用进程保持存活且没有新退出记录。该短暂变化的具体触发尚未确定；不得把它直接记作应用崩溃或用户误操作。

用户再次确认已开启后，[连续连接观察](results/pixel4-service-stability-0.1.2.json)在约 48 秒内采样 10 次：开关启用、服务绑定、无崩溃记录，进程未变化。此段没有截图、模型请求或安装更新。随后 Planner 单次检查完成，服务仍绑定且崩溃列表为空。这不是长时间稳定性验收。

源码审查未发现 `disableSelf()`、系统无障碍设置写入或主动退出进程的路径。设备也配置了系统无障碍快捷按钮；它与应用的 **MH 控制球** 是两个不同入口。Android 13 对当前这种未请求 accessibility-button 回调的服务，可以通过系统快捷入口切换启停，见 [Android 13 系统实现](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java)。这只是有代码依据的候选原因，未取得触发时间证据，尚不能认定为本次原因。后续日常接管／归还使用 MH 控制球或应用通知入口。

## 构建与未覆盖范围

当前 APK：`android-app/app/build/outputs/apk/debug/app-debug.apk`，31,295,723 字节。

SHA-256：`8601a9e691407a05656ca92f8cc4f7bef3eeb872b719ff8b46189962f99e1adc`。

当前构建的 43 项 JVM 测试通过：协调器 20、PhoneBridge 2、QwenGateway 15、HTTP 状态 3、无内容传输错误分类 3。另有 [0.1.1 的设备验证记录](DEVICE_SMOKE_0.1.1.md)，本轮未为重复存储测试而强停已经配置的应用。

仍待验证：完整自由任务、真实模型动作、真实接管后恢复、通知／锁屏／旋转、断开电脑后整条流程、长时间服务连接，以及 37 个验收主场景的完整指定层级。User Study 与个人服务套餐适用范围继续作为开放项。冻结提示词中原先的“未实测”状态是该版本发布快照，本轮结果单独追加，不回写 manifest 或声称旧实验完全一致。
