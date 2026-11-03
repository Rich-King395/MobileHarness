# 真实计算器任务 · 0.3.3-dev

结论：**任务未达成，原因未确定。** 正常任务入口跑到 30 步额度耗尽，最终计算器显示 `2`，没有得到独立预期 `420`。30 次请求均通过当前解析器、30 个动作均报告 completed，没有记录错误；这些事实不能单独证明模型落点正确或 APK 视觉／坐标链路完全无误。

## 预先确定的试测条件

用户选择先测计算器。已安装 0.3.3-dev／versionCode 9，APK SHA-256 `6b665007d00060ecc965fd3083b57b4bde19cd117c1596c85d0695ba91e3a1df`；本轮没有构建、更新安装包、改变模型、提示词或请求参数。提示包 1.2.0、响应适配器 1.3.0。

通过正常 MobileHarness 任务页，从默认 Home 由 GUI Agent 自主打开唯一授权的 `com.google.android.calculator`。Harness 开启，额度 30，计划接管 0、重试 0；截图发送至用户手机中已配置的服务，正常界面的发送同意已选中。[计划](results/real-calculator-0.3.3/plan.json)在开始前保存。

任务为：

> Open the authorized Calculator App. Use the calculator to add 23 and 47 then multiply that sum by 6. Leave the final result visible and finish. Do not open other Apps.

标准答案 420 只用于独立核验，未加入模型任务、反馈或 Planner 上下文。启动前测试者用计算器清除键清空当前表达式，并确认 formula／preview 均为空；没有用 App 清数据或重装作为复位。正式开始后未点击目标 App、读取逐步页面或帮助模型纠正。

## 分层记录

| 层次 | 本轮观察 | 能得出的结论 |
|---|---|---|
| 模型服务／协议 | GUI 30 次，Planner 0，30 个 succeeded 终态，无 transport／API／INVALID_OUTPUT | 本轮回复符合当前接受规则；不证明决策正确。最小日志不含附带文字形态，不能统计其兼容样本数 |
| 动作派发 | launch_app 1、tap 29，全部 completed | 执行器返回了真实完成终态；不等于命中模型想点的控件，也不等于业务结果正确 |
| 控制／预算 | G30／B30，P0，budget_exhausted／source none，无合法 finish、接管或重试 | 按原额度结束，无退款、重置、额外结束请求或隐藏重试 |
| 独立结果 | 结束后计算器仍前台，formula=`2`，preview 为空，没有 420 | 本轮目标未达成，不能写成功任务 |

自动会话 119.335 秒，包含网络、截图和执行，不含前置 UI 配置。请求终态耗时中位数 3.204 秒，范围 2.748–8.133 秒，不作为服务器纯推理耗时。原始 [92 条最小事件](results/real-calculator-0.3.3/attempt1.events.jsonl)通过 Schema 1.1.0、连续序号及 30 个 started／terminal 配对检查。

运行中仅轮询固定最小事件；独立 UI 观察器在启动前停止。收到 session_ended 后才读取最终数值字段，见 [独立终态](results/real-calculator-0.3.3/attempt1-outcome.json)。第一次终态观察脚本未处理空 preview 的 null 值，修正后再次只读复核；未因此发新模型请求或新增计算器操作，不把观察脚本错误归到 APK。

## 归因与下一步

本轮没有逐步保留原图、原始响应、模型坐标或原生落点；这些内容也不能从任务结束后已清理的历史中追溯恢复。因此：

- 不把最终算错或超额直接记作 APK 流程故障。
- 不因没有 error 事件就宣布截图正确、坐标保真或 APK 已全部通过。
- 不把它确定为模型推理／定位错误；具体选择偏差与图像／映射问题仍须独立证据。

下一批优先增加真实 App 中的临时内存诊断：在原本必需的当前 PNG 和原动作上比较截图几何、时序证据、规范化坐标转换及实际派发，不改写或吸附模型动作，不向模型提供独立评分，也不保存图片／回复历史。定位后再扩展时钟／专用测试笔记，并做一次自由接管的 Harness on/off 试测。该建议不是已经实施的模型修复。

## 收尾

[结果摘要](results/real-calculator-0.3.3/attempt1-summary.json)保留 TASK_NOT_ACHIEVED／UNDETERMINED。原先空授权范围已恢复，额度仍 30、Harness 开启、任务草稿为空，个人模型配置和密钥未修改。计算器终态没有被事后改成标准答案，截图和原始回复未保存或导出。
