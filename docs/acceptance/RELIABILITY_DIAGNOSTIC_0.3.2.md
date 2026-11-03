# 点击与回复协议诊断 · 0.3.2-dev

日期：2026-10-31。设备为已授权 Pixel 4／Android 13。目标是确认 APK 的信息传递、严格解析和原样执行，不以提升模型任务成功率为目标。

## 旧失败的证据边界

[旧真实任务记录](REAL_TASK_0.3.1.md)保留不变：一次 tap 报告 completed，结束后核验点击计数为 0；后两次回复为 INVALID_OUTPUT。旧日志没有落点和具体协议拒绝原因，原始回复、截图不持久化，因此不能事后还原这三次输出，也不能确定旧计数为 0 是模型落点、执行或观察问题。

本次没有把模型推理错误当成 APK 故障，没有把自然语言回答转换成动作、把结束转换成研究成功，或用测试按钮的隐藏位置修正模型坐标。Qwen 的[官方视觉文档](https://help.aliyun.com/zh/model-studio/vision)给出归一化 [0,999] 坐标约定，不据旧计数直接改成图片像素坐标。[Function Calling 官方文档](https://help.aliyun.com/en/model-studio/qwen-function-calling)不保证 `tool_choice: required` 必然产生工具调用，未作为修复启用。当前 `max_completion_tokens` 有[官方参数文档](https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions)依据；不凭耗时推断思考或截断。

## 本次增加的诊断

- 严格解析器与诊断复用同一路径，增加固定拒绝原因，保持允许的动作和参数集合。
- 正式最小事件使用新 [Schema 1.1.0](../data-contracts/minimal-event.v1.1.schema.json)：INVALID_OUTPUT 可附固定 protocol_reason，显式重试增加 retry_requested。冻结 1.0.0 和旧事件不改写，进程中断补尾沿用原会话版本。
- DUMP 保护的 debug 入口分别核验模型原始点按、独立中心点对照和结束协议。只输出白名单枚举、布尔和计数；原始回复、图像、坐标、节点文本和图像摘要都不写入文件。
- 无自动 HTTP／模型重试。诊断输出成功不代替独立任务核验。

## 单步真实请求

使用诊断 APK（同为 0.3.2-dev／versionCode 8，后续修改需另记哈希）：32 MB 级，SHA-256 `227a903cd954ddf599fc637b5350ba5e41676674c9c9a35b1bd55f8077737ac7`。GUI／Planner 服务及角色选择保留，使用冻结提示包 1.2.0，未改提示词或请求参数。

[点按对照](results/pixel4-grounding-0.3.2-attempt1.json)：GUI 1、Planner 0，7.000 秒。模型返回单一合法 tap；截图、几何、帧票据及目标位置在 dispatch 前稳定，归一化落点在按钮内，原参数经同一 PhonePort 执行后计数恰好增加 1。独立中心点对照另取新图、单独执行一次，计数也增加 1；其它合成状态不变。没有把中心点作为模型动作或重试。两次截图只在 RAM。

[结束协议](results/pixel4-finish-protocol-0.3.2-attempt1.json)：GUI 1、Planner 0，5.002 秒。模型在重置后的当前合成页返回单一合法 finish，finish_reason=tool_calls、content 和 reasoning 均为空。此入口只核验输出，未执行任何动作；计数和其它状态保持。

这两项只证明两次单步调用，不构成对旧现场的追溯归因，也不代表多步自主任务或长期输出通过率。

## 完整链路复现与兼容修复

通过独立 debug 入口调用真实 Coordinator／QwenGateway／PhonePort，使用逐字相同的旧任务，从解析得到的默认 Home 启动，范围仅合成页，Harness 开启、GUI 额度 8，不接管、不自动或手动重试。这个入口绕过日常任务页，不伪称重新验收了完整日常 UI。

[修复前复现](results/pixel4-full-workflow-0.3.2-attempt1.json)使用旧 adapter 1.2.0，APK SHA-256 `3de1499690e8f0b1c8be3bffbe7f6734df8c0d2a592d7011e4e533336e102a13`（32,587,233 字节）。G1 返回 launch_app，G2 返回 tap：截图后读取的按钮范围与派发前一致，原参数与 PNG 未改写，原生终态 completed，但落点在该范围外，计数不变。因为首张目标图处在启动后的观察边界，截图前节点读数为空，不能仅靠包名／几何／PNG 未改写证明截图的按钮像素与后读节点绝对一致。因此先记为“落点相对派发时节点范围 miss”，不完全排除截图时序，不直接定性为模型推理错误。

G3 为 finish_reason=tool_calls、单一 TAP 工具、空 reasoning，却附带 3,730 字符 content。旧规则在 content 检查处以 GUI_CONTENT 拒绝，未继续校验工具参数，不能据此声称那条过期动作一定合法。阻断后诊断器显式停止，source none，G3／P0，无重试，目标未达成；[12 条最小事件](results/pixel4-full-workflow-0.3.2-attempt1.events.jsonl)均按新日志 1.1.0 校验，适配器仍准确记录为 1.2.0。该证据解释本轮拒绝位置，不追溯冒认旧两条回复内容相同。

随后增加 [适配器 1.3.0](../MODEL_ADAPTER_1.3.md)：允许合法单一工具调用携带字符串说明，丢弃说明，只保留原 call／参数；其余封装、阶段及参数校验完整执行。Planner 和冻结提示 1.2.0 不改，适配器版本独立记录。

[新规则首次完整运行](results/pixel4-full-workflow-0.3.2-attempt2.json)使用 APK SHA-256 `674e269e52b1abd55e54f8e8e84c7284d859c0c944ecde52c6e3db501b9470b8`（32,595,949 字节），G3／P0、13.338 秒。launch／tap／finish 均通过，模型点在按钮范围内，计数恰好增加 1，最终仍为 1，其它状态不变；[11 条最小事件](results/pixel4-full-workflow-0.3.2-attempt2.events.jsonl)保留。该次三条 content 均为空，不能声称是真实非空 content 接受率实验；该兼容路径由专门的合成响应测试证明。

本轮额外增加的三点固定背景检查未通过，因此诊断状态保留 DIAGNOSED／FIXTURE_SCREENSHOT_RENDER_EVIDENCE_INSUFFICIENT，而不是全部 PASS。当时此检查尚需先校准；没有把辅助判据失败直接当成 APK 截图缺陷，也没有删除这条不利记录。

## 取图时序的零网络对照

先校准诊断本身：[第一次本地校准](results/pixel4-render-calibration-0.3.2-attempt1.json)发生在覆盖安装后服务尚未绑定时，SERVICE_DISCONNECTED，零截图／请求／动作。确认实际绑定后显式重跑，[稳定合成页校准](results/pixel4-render-calibration-0.3.2-attempt2.json)通过：单一可见 ScrollView、范围在 PNG 内、图片几何正确，三个背景点全部匹配。GUI／Planner／动作均为 0，截图 1 次、只在 RAM。安装的测试页 APK 与本地产物 SHA-256 相同（`b1d25c14dd2472af0c6a409ecf746db2fc5ff86536946cf79fee7c081086e85c`），排除错误标记版本。手机转场／窗口动画倍率读取为 1.0，未改系统设置。

再在已有“launch_app 完成后立即 observe”的零网络测试里，于真实点击之前检查同一张目标 PNG，不增加截图或模型请求。旧生产逻辑只要求目标包名跨两个展示帧，可能在约数十毫秒内满足，却尚在 Surface 转场动画中。[修复前](results/pixel4-launch-render-repro-0.3.2.json)复现 FAIL／FIXTURE_RENDER_NOT_CONFIRMED_BEFORE_TAP，2.529 秒，perform 尝试 2、完成 1，没有继续点击。该复现 APK SHA-256 为 `a6dd828e0a5261656dbea58f59ff49ab68f3db78e9b6b18256735cd803f99207`。

生产修复只延长 launch 后的有限就绪握手：目标必须持续前台至少 750ms 且跨过两个展示帧；每帧先检查采集、代次、范围、控制者、停止入口及原生输入状态，再查询包名。离开目标会重新计稳定时长；总超时仍为 3 秒。接管、关闭采集或更换会话仍清除 pending，不追旧目标，不增加截图／模型／动作重试，不改变模型坐标。

[同场景修复后](results/pixel4-launch-render-fixed-0.3.2.json)通过，5.515 秒，perform 尝试 4、完成 3：点击前背景匹配、原参数点击计数效果、原生终态、未授权和无范围取图拒绝、启动失效后观察当前 Home 均通过。多出的两次操作是测试在前置检查通过后继续执行既有点击和失效检查，不是隐藏重试。生产等待修复针对本机默认动画实测；750ms 不是对任意设备、极端动画倍率或所有 App 动画完成的数学保证，未据此宣称截图永远稳定。

## 最终完整复测

[最终任务](results/pixel4-full-workflow-0.3.2-attempt3.json)使用 adapter 1.3.0、冻结提示 1.2.0、上述取图修复，PASS／FULL_WORKFLOW_CONFIRMED，17.669 秒，G3／P0。launch_app、tap、finish 均合法，所发原 PNG 的背景谓词通过，落点命中，原动作与 PNG 未改写，原生终态已确认，计数恰好增加 1 且最终仍为 1，其它合成状态保持。无接管、规划、显式或隐藏重试；共享额度核对一致。[11 条最小事件](results/pixel4-full-workflow-0.3.2-attempt3.events.jsonl)保留。

这证明本轮合成链路通过，且前后对照支持启动取图时序修复；不能追溯断定旧 0.3.1 两条未留存的回复具体内容，也不能把所有历史偏点完全归因到同一个动画问题。三点背景仅加强当前页面绘制证据，不等于全图像素语义证明。两个新规则实际运行中的 content 均为空；非空 content 接受及其余非法协议继续拒绝，由专门合成响应测试覆盖，不虚构真实服务的非空 content 成功样本。

本轮真实请求总计 GUI **11**、Planner **0**（两次单步、三个 G3 完整会话），不筛掉失败会话，也不将任何本地测试记为商业模型调用。

## 最终构建与故障处置

88 项 JVM 测试通过，包含严格／兼容封装、原参数和辅助文字历史隔离、无自动重试、UTF-8／缓冲清除、共享预算／阶段规则及 10 项审计写故障测试。首次新增反射测试误把 Compose 注入的静态整数字段判为隐私违规，已在确认字段类型后修正测试，不放宽到字符串或任意对象；修正后的测试均通过。

新增生产审计故障锁存：任一审计写异常先停止采集与新请求，取消模型；不得继续无日志实验或重试坏日志接口。已派发的原生输入仍等待真实终态，5 秒未确认保持 UNCERTAIN，不把超时当作完成。用户可结束并检查存储后重启；日志不可写时不能假称终止事件已成功保存，重启时尽可能补 interrupted 尾记录。

最终已安装 0.3.2-dev／versionCode 8，APK 32,614,609 字节，SHA-256 `3c77f81e6918a232688dc484cbcec5b6c9de3ae52bd1e2a4442b431d3d61f288`，签名验证通过。所有数据型故障均不把模型结束直接记为独立任务成功。

[最终四组 PhonePort 回归](results/pixel4-local-regression-0.3.2-final.json)全部通过，26.135 秒，零网络；接管迟到回复／原生屏障等既有检查保持。[收尾就绪](results/pixel4-final-readiness-0.3.2.json)确认实际服务连接、controls 恢复原 app.runtime、停止入口可用，悬浮控件未留在截图隐藏态。个人授权范围、角色配置与密钥未写入改动，任务草稿仍为易失内存；本轮没有重新执行完整 37 场景或脱离电脑真人实验。

三个完整会话共 34 条新最小事件全部通过 Schema 1.1.0 与连续序号检查；原两轮 22 条事件仍通过冻结 1.0.0。新记录没有隐去失败、没有回写旧研究结果。

## 数据与版本

冻结 1.0.0／1.1.0／1.2.0 提示 manifest 分别保持原 SHA-256：`faef79795ed6c1bbf33953372980a9d823a1cb78dfa56daea9a254c345a01f73`、`0434d82c2f01bc4bd39b93df1b65ef176c25031760ef86d9b6ec8988f4c294ad`、`be0723200c4159ae7f4b53b6bdccc78e82eb7ef5b6ce716beef4fae4ac6d003c`。日志结构扩展与提示版本明确区分。

个人密钥不离开手机存储与 HTTPS 鉴权，普通日志和诊断导出没有密钥、原始回复、坐标或截图。诊断只临时授权合成页，不更改本人授权范围、预算、Harness 开关或角色选择。
