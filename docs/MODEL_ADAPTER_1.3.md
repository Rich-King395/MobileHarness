# GUI 响应适配器 1.3.0

本增量只调整自动任务的 GUI 回复封装兼容，不修改冻结提示词、工具名称、参数 Schema、坐标、阶段、预算、结束方式或 Planner 合同。旧适配器 1.1.0／1.2.0 的空 content 限制与既有失败记录保留。

## 触发证据

0.3.2 旧接受规则的完整链路诊断第 3 次 GUI 回复含单一原生 tool_calls、非空字符串 content、空 reasoning_content，finish_reason 为 tool_calls，旧解析器在检查工具参数前以 GUI_CONTENT 拒绝。见 [诊断记录](acceptance/results/pixel4-full-workflow-0.3.2-attempt1.json)。这明确解释本次拒绝位置；由于参数尚未检查，不据此声称该旧回复的动作参数一定合法，也不补执行已经过期的回复。

## 新接受规则

自动任务使用 adapter_version=1.3.0，prompt_bundle_version 仍为 1.2.0。两者分别记入 session_started。冻结 request-profiles.json 中的 adapter_version 是该历史快照的标识，运行器的真实接受规则版本以 SessionOptions 与会话事件为准；不把二者相同当成必须条件。

对于 GUI 回复，message.content 可以缺失、null 或字符串。非空字符串只作为可丢弃的附带说明，不解析其中的动作、结束声明或指令，不执行、不持久化、不传给 Planner，也不加入后续成功动作历史。GuiReply 仍只含原生 call id、PhoneAction 和原 arguments JSON；历史 assistant.content 仍为 null。

只有以下校验全部通过才可执行：完整且非截断的单一 choice、assistant 角色、预期 finish_reason=tool_calls、空思考字段、恰好一个原生 function 调用、合法 id、当前阶段白名单工具、完整严格 JSON 参数及其 Schema。字符串 content 不使其它校验获得豁免；数组、对象或数字 content 仍拒绝。

纯文本 STOP 仍为 GUI_STOP_WITHOUT_TOOL；LENGTH 仍为 RESPONSE_TRUNCATED。缺失／多个／未知工具、错误类型、额外参数、越界、重复键和只读阶段写操作继续拒绝。不生成替代动作、不猜坐标、不补全截断 JSON、不自动重试；失败仍消耗 GUI 额度，显式重试重新观察。

Planner 仍按原四字段严格 JSON 和两次调用／一轮查证合同。第二次 Planner 判断完成时直接结束，不额外请求 GUI done。

## 可比性

这是响应接受规则修复，不能称为与旧实验完全一致。后续分析须按 adapter_version 分组；提示文本和请求参数未变。旧模式 SELECTED_APP 保留 adapter 1.1.0 与默认严格解析；HOME_AUTO 使用新适配器，Harness 开／关共用完全相同的 GUI 封装规则。

该适配器改善合法动作的交付稳定性，不保证模型落点、推理或任务结果正确；task success 仍独立核验。普通日志仅保留版本、动作类型、结果和固定错误原因，图片、回复和坐标仍只在进程内。
