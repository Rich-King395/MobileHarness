# 动作失败反馈与继续：0.5.0

日期：2026-11-02。Pixel 4／Android 13。范围是减少已知执行失败造成的人工重试，保持模型原动作、Harness 及数据边界。

## 原因与修复

用户两轮 YouTube 旧会话中的 5 次暂停为：input_text 2 次、press_key 2 次、观察可用性 1 次。相关回复均已通过动作解析，不能据此归为模型推理错误或坐标映射错误。旧代码把不同执行拒绝压为 INPUT_FAILED；Enter 在缺少支持的 IME 连接时也被一律暂停。旧日志没有参数及具体原因，不能追溯断言每次按键均为 Enter。

本版将执行结果拆为固定原因。没有聚焦输入框、文本操作被拒、Enter 能力不可用、手势明确取消等已知终态，在 ORIGINAL／RECOVERY 自动重新观察，把固定失败反馈放进同阶段原生工具结果，再由模型选择下一动作。失败保留为失败，不重放旧动作、不修正坐标，不新增 Planner 调用，也不重置共享额度。只读查证和接管屏障仍按原合同执行。

锁屏、授权／控制入口不可用、受保护输入、截图不可用或输入结果不确定仍会阻止自动操作。HTTP／协议错误不按设备动作失败处理。暂停原因使用固定文字与错误码，避免通用提示覆盖具体原因；不展示异常原文。

规格见 [执行反馈与版本补充](../EXECUTION_FEEDBACK_0.5.0.md)。提示词包仍为 1.2.0，自动模式适配器升为 1.4.0，执行策略记为 1.1.0，新事件 Schema 为 1.2.0。旧提示词、Schema 与交接快照保持不变；新反馈改变模型可见执行信息，不能称为与旧实验完全一致。

## 构建与有限验证

最终 candidate2：0.5.0-dev／versionCode 13，34,118,141 字节，SHA-256 `35c20e822d8fadb3909e90276e14dc637c4a6e6a31d57d6e217a76e8fd8e79a0`。签名 v2 验证通过。

| 层级 | 结果与证据 | 范围 |
| --- | --- | --- |
| JVM，最终候选 | 150 项通过，0 失败／错误／跳过，[构建](results/action-recovery-0.5.0/build.json) | 新增 16 个协调器变体、4 个反馈组装检查；含新观察、不重放、预算终止、原参数、成功历史隔离、接管／停止竞争、只读与暂停原因保留 |
| 离线日志边界 | 12 项通过 | 固定 18 个原因、历史 Schema 不变、正文／坐标／Key 等字段拒绝导出 |
| 冻结资源 | 最终 59 个 manifest 引用文件及独立历史 Schema 1.1 保持一致，[记录](results/action-recovery-0.5.0/frozen-resources-candidate2.json) | 原提示词、方法与历史数据合同；先前 57 项记录另保留 |
| 真机 Service＋本地模型替身，两个候选 | 各 17 个旗标通过，[最终记录](results/action-recovery-0.5.0/device-action-recovery-candidate2-attempt1.json) | 实际 NO_FOCUSED_INPUT 与 IME_SUBMIT_UNSUPPORTED 后继续，均 G5／8、P0，截图 5 次、perform 4 次，网络为 0；检查实际输入效果，恢复原控制器 |
| 真机孤立日志，candidate1 | 19 个旗标通过，[记录](results/action-recovery-0.5.0/device-audit-attempt1.json) | 新 Schema 88 条，历史 1.1／1.0 各 3 条；孤立目录清理，无用户日志／配置删除 |
| 物理悬浮控制，两个候选 | ON／OFF 各自均通过，[最终 ON](results/action-recovery-0.5.0/physical-overlay-on-candidate2-attempt1.json)／[OFF](results/action-recovery-0.5.0/physical-overlay-off-candidate2-attempt1.json) | ADB 实际触摸自家控件，经暂停屏障、人工、归还、停止；ON G2/P1，OFF G2/P0，人工期间不读取目标画面，均为本地模型替身 |
| 最终安装与自家 UI | [核验通过](results/action-recovery-0.5.0/final-device-state.json) | 手机 APK 哈希等于最终构建，实际服务连接、控制器匹配、IDLE；授权数量 23、加密配置标记 2，任务框为空、发送同意未勾选 |

首次构建有一个新增单测预期不匹配，已修正未知异常与无原因旧结果的区分，失败记录保留于 [build-attempt1](results/action-recovery-0.5.0/build-attempt1.json)。首个通过候选的 [148 项构建记录](results/action-recovery-0.5.0/build-candidate1.json) 保留。之后只修复暂停文案被覆盖并加两项测试，产生最终 candidate2；不能把 candidate1 的远端试测写成在最终哈希上重复运行。

最终 UI 核验器曾因窗口字段名称、PowerShell 返回语句间距及 Compose 节点形态未能建立结论，未改变模型配置或授权。修正为实际 `window-title`、无文本 EditText、CHECKABLE View 后通过；[原未建立结论记录](results/action-recovery-0.5.0/final-device-state-checker-attempt1.json) 保留，不把核验脚本问题记为 APK 故障。

## 真实 YouTube 试测

通过正常任务页，复用用户已有授权范围与模型配置，Harness 开启、额度 30。任务限定搜索 Mr.Beast 并保留搜索结果，不播放、不修改账户或设置。实际授权范围为 23 个 App，本轮不重新授权、不代选或重存范围。

本轮 candidate1，GUI 10 次、Planner 0，35 条事件通过 Schema、顺序与结束校验。详见 [汇总](results/action-recovery-0.5.0/youtube-search-attempt1/summary.json) 与 [白名单事件](results/action-recovery-0.5.0/youtube-search-attempt1/events.jsonl)。

- GUI3 返回 HTTP 400；此前只有 launch_app 与 wait 的成功历史，还未发生动作失败。明确进行一次正常 UI 诊断重试，GUI4 被接受。这不是自动动作恢复，拒绝原因仍未确定，未保存厂商正文、请求、截图或密钥。
- GUI6、GUI9 的 input_text 均明确为 NO_FOCUSED_INPUT。分别在 292ms、979ms 后进入下一次正常计账的 GUI 调用；其间没有人工重试事件。模型自己选择后续动作，APK 没有代点或修改落点。
- 模型在 GUI10 声明结束。独立核验仅确认当前搜索框与任务词一致；没有完整核验结果列表或视频，任务成功保持未确定。首次独立核验脚本有布尔字面量错误，未得到有效结论；错误记录保留，修正后才记录上述谓词。

这条真实路径支持“已知焦点失败可以反馈并继续，解析与执行没有因该失败强制暂停”，不证明所有 App／模型／输入能力长期稳定。该轮额外 HTTP 400 仍为单独开放项；不把所有 400 设为自动重试。

## 数据与后续边界

截图仅按 GUI／Planner 必要观察在内存中使用，人工期间采集关闭；没有保存目标截图历史、任务／模型正文、动作参数或人工轨迹。固定执行原因是新增的最小事件元数据，Key 保持手机本地加密存储。实验记录应区分模型声明、执行结果、独立任务结果。

本轮未重跑旧 97 个设备变体或全部 37 场景的所有层级；已有旧记录不改写。模型可能重复选择无效动作直到共享额度耗尽，这是模型决策与预算边界，不通过修改模型落点避免。
