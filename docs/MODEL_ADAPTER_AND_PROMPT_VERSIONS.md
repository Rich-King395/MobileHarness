# 首期模型适配与提示词版本

版本：1.0.0。确定日期：2026-10-30。

状态：协议与离线检查版本；尚未创建 Android 工程，也未向用户的 Token Plan 服务发送请求。文档中的示例均为合成内容。

## 1. 来源与适用范围

- 原始设计及英文提示词快照保留在 HandoffHarness_APK_Handoff 中，本轮不修改。
- 新 APK 的可执行约定集中在 prompts/v1.0.0；该目录是新项目版本，不宣称与旧实验逐字或逐协议一致。
- 首期 GUI Agent 与 Planner 默认都使用 qwen3.8-max，服务地址、模型名、Key、HTTP 客户端及会话分别管理。
- GUI 采用 Qwen 原生 Chat Completions tool_calls 容器，手机函数由本项目定义。Qwen 的通用工具调用能力不等于已有专用手机动作协议。
- 后续适配其他 GUI 模型时，另设适配器，保留其原生消息、图像、坐标和结束约定，不强制转换为本版本的手机工具。

## 2. 服务与请求参数

配置基准：

- Base URL：https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1
- 请求地址：Base URL 加 /chat/completions。
- 若用户粘贴完整请求地址，规范化为上述形式，避免重复追加路径。
- Key 只由手机安全配置注入 HTTPS 请求；提示词、版本文件、普通日志和导出中不包含 Key。

实际 HTTP body 的公共参数由 request-profiles.json 固定：

| 参数 | 首期值 |
| --- | --- |
| model | qwen3.8-max |
| enable_thinking / preserve_thinking | false / false |
| temperature / top_p | 0 / 1 |
| max_completion_tokens | 4096 |
| stream / n | false / 1 |
| vl_high_resolution_images | false |

GUI 另设 tool_choice=auto、parallel_tool_calls=false。Planner 另设 response_format={"type":"json_object"}，不提供手机工具。

这些是新项目的显式初始参数，不是从旧实验推定的参数。关闭思考是本版本选择，不代表 Qwen3.8-Max 的思考模式不能与结构化输出并用。直接使用 HTTP 时，enable_thinking 等供应商参数置于 body 顶层，不额外套一层 SDK 的 extra_body。

本地继续执行四字段 Schema、分支与调用次数校验，不将 JSON Object 或服务端 Schema 等同于已通过语义核验。参数组合、图片、工具调用及多轮关联仍需在实际 Token Plan 端点验收。供应商不接受某参数时显式报错；不在同一会话中暗中删参数、换模型或补发格式修复请求。

qwen3.8-max 是服务端模型名称，可能由供应商更新路由。记录请求模型、响应模型及供应商返回的请求标识或指纹；固定提示词与请求参数不等于可以证明服务端权重始终不变。

## 3. 图像与坐标

### 3.1 图像

- 客户端发送实际全屏 PNG，使用 image_url.url 的 Base64 Data URI，不发送手机本地文件路径。
- 首期不在客户端裁剪、填边或缩放。实际文件尺寸写入对应图像说明；Pixel 4 当前纵向尺寸为 1080×2280，运行时重新读取，不硬编码。
- image.content-part.template.json 的 max_pixels 位于图像 content part 顶层，与 type、image_url 同级，固定为 2621440；vl_high_resolution_images=false。
- Pixel 4 当前截图为 2462400 像素，低于该上限。服务端仍会执行自身的视觉编码；不声称知道其内部张量尺寸。
- 每张图先放文本 Public image evidence: 对应编号，随后实际附该图像 content part。编号、尺寸和数据必须一一对应，按编号去重。
- 普通 GUI 决策使用当前画面及本阶段已有历史；Planner 可使用接管前边界画面、归还画面和查证后画面，最新一张明确标识为当前状态。
- 发送前检查 PNG、Data URI 和总请求大小。按当前官方限制采用保守的十进制字节上限，见 request-profiles.json；Token Plan 若有更小限制，以实测后新版本为准。
- 超限或缺少有效画面时显式暂停，不静默压缩、摘要、删除历史或添加模型请求。全量阶段历史超出接口或上下文限制时同样处理。

### 3.2 坐标

采用 Qwen3.8 官方视觉定位惯例：整数坐标范围 0…999，并沿用官方示例的分母 1000：

    x_px = floor(x_normalized * image_width / 1000)
    y_px = floor(y_normalized * image_height / 1000)

例如 1080×2280 画面上的 (500,500) 映射到 (540,1140)；(999,999) 映射到 (1078,2277)。不改用分母 999，也不将这些值当原始像素坐标。

宿主将响应绑定到产生它的 task、phase、控制权代次、frame_id 和显示几何。已知旋转、尺寸、控制权或观察归属失效时，拒绝派发；不能拿旧坐标补偿猜测。全屏坐标不减状态栏或导航栏高度。

截图到实际触控显示的几何一致性、浮层遮挡和推理期间动态界面漂移需真机验证。上述结构校验不等于能保证所有动态 App 的目标始终静止。

## 4. GUI 工具与结束协议

执行阶段工具定义见 gui/tools.execution.json：

| 工具 | 含义 |
| --- | --- |
| tap | 点击当前画面一点 |
| long_press | 长按，300…2000ms |
| swipe | 一次滑动，100…2000ms |
| input_text | 完整替换当前聚焦、可见、可编辑字段；空串清空 |
| press_key | back、home 或经执行器验证可用的 IME enter |
| launch_app | 启动授权范围内可启动的 App 包 |
| wait | 等待当前页面刷新，100…2000ms，之后取新画面 |
| finish | 结束本 GUI 阶段，outcome 为 completed 或 unable |

这些范围是本版手机执行协议的明确边界。工具描述不承诺所有 App 支持相应动作。输入、IME、启动与手势均需执行器校验；不静默替换为其他动作。

初始 App 和可启动包名是用户授权设备上的执行能力元数据，不是固定任务集或脚本入口。包名清单不限制用户人工期间自由操作，也不要求新增同名配置页。人工操作不会自行改写原任务。

每次完整模型响应必须包含恰好一个合法工具调用。零个、多个、未知工具、非法参数或截断响应均不执行动作，不挑第一个执行。message.content 不是动作通道；单独的自然语言与 finish_reason=stop 不表示任务结束。length、content_filter、缺失或未完成响应均不能用于动作派发。

本版本预期 GUI 的完整停止原因为 tool_calls，Planner 的完整停止原因为 stop；两者仍分别通过工具参数或四字段输出校验。停止原因异常时显式记录协议错误，不把生成停止本身当作任务完成。

非流式响应必须有且仅有一个 choices 项，与 n=1 一致；不从多个候选中挑选可执行项。

严格解析 JSON，拒绝重复键、非有限数字及宽松语法；坐标不能将小数或字符串静默转换成整数。Planner 四字段 JSON 同样拒绝重复键。

保留模型原生 assistant/tool 消息关联及 tool_call_id；工具返回使用 role=tool。正文若伴随合法工具返回，只保留为本阶段消息内容，不将其解析成额外动作或成功声明。终止工具得到本地确认后直接结束对应阶段，不追加一次模型“总结”调用。

| 阶段 | finish(completed) | finish(unable) |
| --- | --- | --- |
| ORIGINAL / RECOVERY | 模型认为原任务已完成 | 无法继续或无法确认完成 |
| VERIFY | 获取了该检查要求的可见证据；不是原任务完成 | 当前画面及等待无法取得证据 |
| CONFIRM | 原任务最终态与必需值在当前画面可见 | 当前画面无法确认 |

finish 的 message 是一个简短整体报告，不是逐条件状态字段。程序不再增加逐条件完成门槛。模型判断、用户判断与研究者核验分别记录。

## 5. 用户已选择的只读能力

首期确认采用 current-frame-only-v1：

- VERIFY 和 CONFIRM 请求只提供 gui/tools.observation.json，即 wait、finish 两项。
- 不提供点击、长按、滑动、输入、按键或启动 App，也不借“只读”名义导航。
- 无法仅靠当前画面与等待刷新取得证据时，GUI 返回 unable；第一次 Planner 可预先选择 FALLBACK。
- 执行器再次检查阶段权限，不只依靠提示词限制。等待不主动写入业务数据；外部服务或 App 自身刷新仍可能改变画面。
- 不设新的逐条件状态字段，也不新增第二轮查证。查证目标和剩余共享 GUI 额度共同限定本轮。

这项限制是用户为首期 APK 作出的明确选择，可能降低需要导航才能核实状态的任务的查证覆盖率。它不是对旧实验查证能力完全相同的声明。

## 6. 两次 Planner 的合同

两个阶段分别使用自己的 system.txt 和本地 JSON Schema：

| 调用 | completion_status | decision | 后续 |
| --- | --- | --- | --- |
| 第一次 | COMPLETE | VERIFY | 新 GUI 确认阶段，只读当前画面 |
| 第一次 | INCOMPLETE | EXECUTE / VERIFY / FALLBACK | 剩余执行、检查或回退 |
| 第一次 | UNVERIFIABLE | VERIFY / FALLBACK | 当前页可查则查，否则回退 |
| 第二次 | COMPLETE | FINISH | 协调器直接结束，无 GUI 请求或 done |
| 第二次 | INCOMPLETE | EXECUTE / FALLBACK | 剩余执行或回退 |
| 第二次 | UNVERIFIABLE | FALLBACK | 回到原任务继续 |

输出仍严格只有 completion_status、decision、objective、completion_conditions 四字段。

FINISH 仅为“第二次 Planner 判断完成，协调器终止”的序列化标签，既不是新 GUI 动作，也不新增恢复策略。此时 objective=""、completion_conditions=[]，不向 GUI 注入任何目标，也不伪造一次 GUI finish 事件。

第二次规则覆盖普通查证后和最终确认失败后的两种入口。根据用户最新规则，verification_failed 作为上次检查的上下文，不再一概禁止第二次 COMPLETE；只有新的当前可见证据确实支持原任务最终态时才能这样判断。执行器报告仍不能代替图像证据。

第一次 COMPLETE 的确认若成功，直接按 GUI 确认结束；若失败，再调用第二次 Planner。普通 VERIFY 结束后，无论它是否取得证据，都以新的当前画面进入第二次 Planner。

最多两次 Planner 请求和一轮查证。第二次不允许 VERIFY；EXECUTE 不能伪装为再查证。目标交付后不持续重规划。API、JSON 或 Schema 错误记录后走已有的原任务回退路径，不用额外模型请求修复；只有当前画面与控制权仍有效且有 GUI 额度时才能继续执行，否则暂停或按额度结束。

JSON Schema 能校验结构、枚举和分支组合，不能证明截图事实正确或完整自动判断任意自然语言目标是否保留了所有必需值。原任务原文始终提供给 GUI Agent，作为恢复目标的约束。

## 7. 输入与对话组织

Planner 输入示例在 examples/planner.first.input.json 和 planner.second.input.json。它们没有附真实图像，不能直接作为完整 API 请求。

组装实际请求时：

1. 第一条消息为对应阶段的系统提示。
2. user.content 的文字部分包含序列化的输入对象。
3. 对 images 中每个编号，依次追加一条 Public image evidence: 编号文本与实际 PNG 图像 content part。
4. current_frame_id 必须指向唯一最新图像；无接管前画面时设 pre_handoff_frame_id=null，并删除该图像及描述。
5. 第二次输入补充检查目标、所需证据、未独立核验的执行器报告及 verification_failed，当前图像替换为检查后新取得的一张。

输入规则统一由阶段系统提示承担，不再将原快照中有冲突的 branch_constraints 原样复制到动态输入，也不创建同名 UI 配置项。

Planner 的 own_action_history 仅包含 Agent 自身已完成执行的动作，不含人工动作、失败或未知动作。完成输入派发或手势执行，不等于业务修改已保存；历史动作与可见状态的含义分开。

GUI 消息组织：

- ORIGINAL 初始：gui/system.txt + 原任务模板 + 当前观察。
- VERIFY / CONFIRM：分别新建对话，使用对应任务模板；只积累该阶段动作和观察。
- RECOVERY：新建对话，包含原任务原文、完整剩余目标、可观察完成条件和当前画面。
- FALLBACK：恢复原任务对话，以原任务文本继续，附归还通知与新观察，不引入剩余目标或查证旧反馈。
- Harness 关闭：保留同一原任务对话，附相同的归还通知与新观察，不调用 Planner，不切换历史或注入恢复目标。

gui/control.returned.txt 用作有无 Harness 的同一中性归还通知。发生接管时先关闭派发入口；旧请求返回时由本地控制权代次拒绝，归还后也不会重新生效。通知发生过接管不等于提供人工过程。

模板替换对源模板只扫描一次，插入值不再递归解释占位符，以保留 ORIGINAL_TASK 中的原始文字。JSON 字段由 JSON 构造器序列化，不拼接未转义字符串。条件列表渲染为普通条目，不写入 pending 状态或程序维护的条件标记。不能将上一阶段动作反馈、内部诊断或旧对话摘要偷偷带入新 GUI 对话。

本版明确关闭 thinking 和 preserve_thinking；如果今后启用思考，必须另行记录版本与参数，并按供应商原字段处理阶段内 reasoning_content，不拼入 content，也不突破新阶段隔离。

## 8. 计步、错误与额度边界

- GUI 步数在每次实际 HTTP 尝试发出前记账；结束工具、无效输出、失败、已发出后取消或迟到的请求都消耗本次额度，丢弃迟到回复不退步数。
- 在本地预检发现缺图、超限或无控制权而根本未发送的请求，不伪造一次模型调用；记录实际本地原因。
- 人工操作不消耗、不补充 GUI 额度，阶段变化和回退不重置。wait 和 finish 都是 GUI 决策，各消耗一步。
- Planner 请求独立记账，实际尝试最多两次；HTTP 客户端和适配层均不隐式重试或重新格式化后补发。
- 第二次 Planner 的 FINISH 不消耗额外 GUI 步数，即使刚结束的查证已用完 GUI 额度，也不额外要求 GUI 结束声明。
- 第一次 COMPLETE 若没有足够 GUI 额度完成确认，不能跳过确认直接按完成结束；按额度结束并保留 Planner 判断。EXECUTE / FALLBACK 在无 GUI 额度时也不能继续。
- GUI API/输出错误暂停自动运行并记录模型错误；用户显式重试时按实际新请求继续计步。设备授权、截图和输入执行问题分别记录为设备问题；finish(unable) 记录阶段未完成，不能一概当模型调用错误。

## 9. 文件与版本规则

- prompts/v1.0.0/manifest.json 记录原快照哈希、配置版本和本包每个文件的 SHA-256。
- 改动系统提示、任务模板、工具、坐标、图像策略、请求参数、历史或只读能力时，发布新版本；不能静默复用旧版本号。
- 会话保存本包版本和内容哈希，不仅保存“用了哪个模型”。manifest 自身使用外部会话记录的整体哈希，避免循环自校验。
- 原交接材料作为来源快照保留。差异见 prompts/v1.0.0/CHANGELOG.md。

## 10. 后续验收

离线检查覆盖 JSON/Schema、合法和非法分支、工具参数边界、只读工具集合、坐标样例、模板占位符与文件哈希。它不能验证实际模型效果、手机执行成功或真实任务成功。

最小 APK 阶段再验证 Token Plan 参数、真实 PNG、单工具多轮关联、JSON 输出、旋转与坐标、文本输入、IME、启动 App、控制权竞态及截断/迟到处理。套餐的独立 APK 与多人 Study 适用范围继续作为服务选型开放项。

## 官方依据

- [Token Plan 个人版入口](https://help.aliyun.com/zh/model-studio/token-plan-personal-quick-start)
- [Chat Completions 参数与停止原因](https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions)
- [视觉输入、像素限制与归一化坐标](https://help.aliyun.com/zh/model-studio/vision)
- [官方坐标绘制示例](https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20251031/eamyrc/qwen3-vl-2d.py)
- [Function Calling](https://help.aliyun.com/zh/model-studio/qwen-function-calling)
- [结构化输出](https://help.aliyun.com/zh/model-studio/qwen-structured-output)
- [深度思考与历史字段](https://help.aliyun.com/zh/model-studio/deep-thinking)
