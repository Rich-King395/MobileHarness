# YouTube 频繁暂停：只读日志诊断

日期：2026-11-02。版本：0.4.0-dev。用户确认最近两轮均为脱离电脑进行的 YouTube 搜索／播放试测。本轮只读最小事件，没有读取目标 UI、截图、操作轨迹或 Key，没有执行重试、停止、更新 APK 或更改授权。

[机器摘要](results/youtube-pause-0.4.0/summary.json) · [已结束会话的无内容事件](results/youtube-pause-0.4.0/previous_ended.events.jsonl)

## 已确认的直接触发点

| 会话 | 事件与结果 |
| --- | --- |
| 上一轮，已结束 | GUI 18 次、Planner 1 次，4 次显式重试。第 4 步 tap 已完成后，后续出现 DEVICE／AUTHORIZATION_UNAVAILABLE；第 6 步 press_key、第 12 步 RECOVERY input_text、第 15 步 RECOVERY press_key 均返回 device_error，随后记录 DEVICE／INPUT_FAILED。 |
| 当前轮，读取时仍暂停 | GUI 5 次、Planner 0 次。第 5 次模型回复通过解析后，input_text 返回 device_error，随后记录 DEVICE／INPUT_FAILED。没有触发本轮诊断侧重试。 |

合计 5 次错误：2 次文字输入动作失败、2 次按键动作失败、1 次设备可用性检查失败。相关回复均已通过协议解析与协调器校验；两轮没有记录 API_ERROR、TRANSPORT_ERROR、INVALID_OUTPUT 或 SCREENSHOT_FAILED。23 次 GUI 请求中 22 次 succeeded，另 1 次在接管时 cancelled；Planner 1 次 succeeded。

因此，本批暂停的直接触发点在手机执行／设备可用性检查环节。此结论不等于已证明底层根因，也不把合法动作在不合适界面被请求的可能性排除；没有目标观察证据，不能直接判为模型推理错误或 APK 坐标问题。

上一轮记录了 takeover_requested → manual_granted → return_requested → return_committed，之后一次 Planner 决策并进入 RECOVERY。该链证明发生了真实用户交接的事件路径，不单独证明消息历史隔离、人工期截图数为零或真实迟到回复丢弃。最终是 GUI 声明结束，任务实际是否成功未独立核验。

## 已确定的实现边界及诊断缺口

1. `Coordinator.kt:380–402` 将 `phone.perform()` 的普通异常统一变成 `ActionResult("device_error")`，最终记录 INPUT_FAILED。即使原异常是安全的 RunFailure，也会丢失原分类／错误码。因此同一码可能来自焦点、文字写入、按键能力、帧／权限守卫或系统操作拒绝。
2. `HarnessAccessibilityService.kt:505–508` 对 `press_key("enter")` 固定拒绝，因为当前没有可验证的 IME 提交连接。这是已知执行能力限制。当前日志只记录 press_key，不记录 key 参数，**不能确认本批两次按键失败就是 Enter**；工具合同本身要求不具备安全 IME 能力时拒绝，不能暗换动作。
3. `setText()` 要求 FOCUS_INPUT 节点存在、可见、已聚焦、可编辑、启用、非密码、属于允许 App，并支持 ACTION_SET_TEXT；写入操作本身也可能拒绝。本批 input_text 失败不能仅凭日志区分这些条件。
4. AUTHORIZATION_UNAVAILABLE 是运行状态／当前窗口检查结果，不等同于 YouTube 未授权。沿用用户现有授权范围，不要求重复授权，也不扩大到系统窗口来绕过守卫。
5. 失败动作的工具反馈目前仅为 `execution_status=device_error, message=""`（`ModelRequestAssembler.kt:69–71`）。模型恢复时得不到具体设备子因；重试会取得新观察并重新决策，继续成功不能反推此前原因。预算不会因重试重置。

## 后续修复方向

先保留执行器已有的安全错误码，并为焦点缺失、文字写入拒绝、按键不支持及执行前守卫等增加不含个人内容的白名单原因，让界面能够显示准确原因；再定向复测 YouTube 输入与提交场景。若修改给模型的失败反馈，需明确记录适配／提示版本和信息条件变化。

现阶段不以自动重试、替换按键、改坐标或调模型推理提示词掩盖失败。当前会话保持原状态。完整事件只归档已结束会话；活动会话仅保存截至 seq 17 的诊断结论，没有完整导出。两轮事件均先通过 Schema 1.1.0、连续序号及相对时间检查。普通最小日志没有正文、动作参数、图片或 Key。
