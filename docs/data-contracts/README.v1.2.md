# Minimal event schema 1.2.0

新会话写 `minimal-event.v1.2.schema.json`。冻结的 1.0.0、1.1.0 文件不修改；历史事件和中断补尾沿用所属会话版本。schema 1.2.0 在 `session_started` 中记录 `execution_policy_version: "1.1.0"`，当前桌面自动选 App 模式的 `adapter_version` 为 `1.4.0`，旧指定 App 模式仍为 `1.1.0`。

执行策略 1.1.0 允许把明确终止、可重新观察的动作失败交给 GUI Agent：重新采集当前画面，使用同阶段历史和固定失败反馈，由模型选择下一步。每次实际新请求继续计入共享额度；执行器不会自动重放或修正模型动作。输入结果不确定、授权或控制权不可用等情况仍暂停。这改变了模型所收到的执行反馈，不应宣称与旧实验的信息条件完全一致。

`action` 与 `error` 可携带闭集枚举 `execution_failure_reason`。动作事件只允许在 `result` 非 `completed` 时添加，错误事件只允许在 `category: "DEVICE"` 时添加。枚举不包含输入文字、坐标、节点内容、App 名称、异常正文、密钥或截图；既有 `protocol_reason` 仍只用于 `MODEL / INVALID_OUTPUT`。缺少细分字段的历史记录不能回推具体原因。

`action.result: "completed"` 仍仅表示本机执行结果，`session_ended` 仍仅表示自动流程结束；任务成功需要独立判断。提示词资源、图片与坐标约定、Planner 规则以及人工期间的数据边界不因本次日志升级改变。
