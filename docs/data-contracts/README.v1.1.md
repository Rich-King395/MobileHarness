# Minimal event schema 1.1.0

`minimal-event.v1.1.schema.json` 是冻结的 `minimal-event.schema.json`（1.0.0）的完整结构副本，增量仅包括版本标识、标题、`error` 事件的 `data` 定义中的可选 `protocol_reason` 和使用约束，以及 `control` 的 `retry_requested` 值。1.0.0 文件不修改。

新建会话统一写 `schema_version: "1.1.0"`；历史 1.0.0 事件保持原样，读取、校验与分析按事件版本选择对应 schema。进程中断后的补尾事件沿用所属会话的版本，不把旧会话升级为 1.1.0。两个版本的共同字段保持相同含义，`action.result: "completed"` 仍只表示本机执行结果，不证明任务成功。

只有 `category: "MODEL"` 且 `code: "INVALID_OUTPUT"` 的错误可以附带 `protocol_reason`。该字段采用闭集枚举，区分响应封装、UTF-8、输出上限、工具调用、参数校验及协调器拒绝等原因。`VALID` 仅用于内存中的成功诊断，不属于错误枚举。旧事件缺少该字段时只能视为“具体原因未记录”，不得补推为模型决策错误、执行器错误或某个具体协议失败。

其中 `RESPONSE_TRUNCATED` 表示 GUI 或 Planner 返回了 `finish_reason: "length"`；`GUI_STOP_WITHOUT_TOOL` 表示 GUI 在要求工具调用时返回了 `finish_reason: "stop"`。其他缺失、未知或不匹配的结束原因仍记为 `FINISH_REASON`。这两项细分仍严格拒绝该回复，不把自然语言当作 `finish`，不执行截断动作，也不自动重试。响应耗时本身不用于推断截断或思考模式。

普通日志只增加固定原因，不保存回复原文、工具参数、未知工具名称、动态异常文本、截图、密钥或内容长度。诊断入口在进程内产生的固定形态和计数信息不因此进入普通日志或日志导出。原始响应与图像的数据边界、调用计数、无隐式重试和任务完成判断规则均保持原设计。

用户在界面明确选择重试时，写入 `control.transition: "retry_requested"`，以区分再次发起请求的来源。该事件不产生隐式网络重试，不重置或返还共享额度；实际新请求仍单独计数并产生 `model_call` 事件。

这个日志契约增量不修改冻结的提示词包、请求参数或动作协议。若后续改变这些实验条件，需要独立版本与记录。
