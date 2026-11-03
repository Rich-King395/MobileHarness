# Contacts／Calendar／Clock 真实 App 首轮任务测试

日期：2026-10-31。**三个目标终态均经独立核验达成；本轮没有记录协议、手机执行或协调器错误。** 结论限定于下列三项搜索／导航任务，不等于所有 App 功能或长期运行均已验收。

## 测试条件

使用手机原有的 0.3.4-diag／versionCode 10，没有构建或安装新 APK，也没有修改 Android 源码、模型配置、提示词或坐标映射。直接读取安装包 SHA-256 并确认仍为 `15c88b79e81714b236307a96beca5be7aa4a383ae2366a2593f55c3dfe4c0df4`，见[基线](results/three-app-smoke-0.3.4/baseline.json)。

每项从正常任务页开始、默认 Home 起步，临时只授权该项 App；Harness 开启，额度均为 30，提示包 1.2.0、适配器 1.3.0，同一对 GUI／Planner 配置。每项一轮，不接管、不重试、不调参、不修正模型落点。必要截图按正常界面同意条件发送至手机配置的 GUI 服务。

[预先计划与任务原文](results/three-app-smoke-0.3.4/plan.json)包括禁止联系人通话／编辑、日程改动、秒表启动／重置和闹钟修改等要求。ADB 只用于配置本 App 的测试输入、读取最小事件和结束后的独立核验；目标 App 的任务操作由手机 APK 调用模型并执行，没有测试脚本代做业务步骤。

## 结果

| App | 任务目标 | GUI 步数 | 会话时长 | 独立终态 |
| --- | --- | ---: | ---: | --- |
| Contacts | 搜索合成字符串 `MH20261031Smoke`，保留查询 | 4／30 | 16.629 秒 | 实际搜索 EditText 的值完全匹配；不以零结果为要求，不导出联系人或结果数量 |
| Calendar | 显示 2026 年 11 月月视图 | 5／30 | 23.542 秒 | November 被选中；月份条年份边界、七列日期网格和 30 个日期的星期关系支持 2026-11 |
| Clock | 切到 Stopwatch，任务要求不启动／停止／重置 | 3／30 | 13.152 秒 | Stopwatch 标签选中且秒表内容面板存在，不读取计时值或闹钟内容 |

三项均由 GUI `finish` 声明结束，另由独立终态检查确认目标，未把声明本身当作成功。会话时长来自 APK 相对事件时间，不含电脑端配置／核验，不作为纯模型推理耗时。

实际动作类型：

- Contacts：launch_app ×1、tap ×1、input_text ×1、finish ×1。
- Calendar：wait ×1、launch_app ×1、swipe ×1、tap ×1、finish ×1。
- Clock：launch_app ×1、tap ×1、finish ×1。

合计 GUI **12** 次、Planner **0** 次，12 次回复通过当前协议适配；12 个动作结果记录为 completed，其中包含 3 条 finish 声明，不能都称为实际触屏动作。42 条最小事件通过 Schema 1.1.0、同会话／连续序号／时间不回退、12 对请求终态与预算核对；无 error／control 事件，无模型调用遗漏或隐藏重试。

## Calendar 的独立核验修正

Calendar 的年份标签 `2027` 位于 November、December 之后、January 之前，是相邻月份条的年界，不能单独据此判定选错年份。选中 November 在该年界左侧，支持年份为 2026；其日期网格也有 30 个日期，全部星期关系与 2026 年 11 月一致，1 日为 Sunday。

观察脚本最初不接受 `01–09` 的补零日期，只解析出 21 个日期，生成了保守的 false 结果。[原观察记录](results/three-app-smoke-0.3.4/calendar.oracle-before-zero-pad-fix.json)保留。修正日期解析后只读复核同一终态，未重跑模型、修改手机页面或改变验收月份。该问题属于电脑端观察器解析，不是 APK、模型或 Calendar 故障。

Calendar 的月视图判定采用 UI 网格和工具栏结构；它不读取 App 内部数据，且 Oracle 汇总了同包 APPLICATION 窗口，未单独保存“无重叠日期弹窗”的窗口计数。因此本轮是有限 smoke 核验，不扩展为所有模态窗口排列均已证明。

## 覆盖与边界

本轮验证了当前 APK 在三个不同真实 App 中的自动启动，以及所需点按、文本输入、滑动、等待和正常结束流程。独立目标核验与协议／执行结果分别记录；本轮没有发生可供归因的任务失败或协议／执行错误。

本轮没有启用计算器专项逐帧图片／原生路径探针，因此不能继承其逐次保真结论；此前严格 ARGB 往返差异继续为 OPEN，没有被这三项通过覆盖。也没有验证人工接管、恢复规划、多个模型兼容、联系人／日程创建删除或计时器运行功能。终态 Oracle 检查目标页面，不是对全部联系人、日程和计时状态做修改前后数据库不变性证明。

没有保存截图、原始回复、联系人、日程正文、账号、闹钟或计时内容；目标布局只在内存中生成固定布尔核验结果。测试结束后恢复原空授权范围、空任务草稿、未勾选的发送同意，Harness 开启和 30 步保持原值，密钥与模型配置未修改。无障碍、悬浮停止入口及通知入口可用。

## 证据

- [汇总](results/three-app-smoke-0.3.4/summary.json)
- Contacts：[最小事件](results/three-app-smoke-0.3.4/contacts.events.jsonl)、[独立终态](results/three-app-smoke-0.3.4/contacts.oracle.json)
- Calendar：[最小事件](results/three-app-smoke-0.3.4/calendar.events.jsonl)、[独立终态](results/three-app-smoke-0.3.4/calendar.oracle.json)
- Clock：[最小事件](results/three-app-smoke-0.3.4/clock.events.jsonl)、[独立终态](results/three-app-smoke-0.3.4/clock.oracle.json)
- [最终 UI 核验](results/three-app-smoke-0.3.4/final-ui.json)、[服务就绪状态](results/three-app-smoke-0.3.4/final-readiness.json)

宿主脚本为 `tools/Invoke-ThreeAppSmoke034.ps1`，固定元数据校验为 `tools/summarize_three_app_smoke034.py`；它们是开发侧测试工具，不是 APK 运行依赖或固定任务入口。
