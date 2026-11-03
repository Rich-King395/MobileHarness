# Harness 确定性验收覆盖矩阵

验收合同：1.0.0。此表初始盘点日期：2026-10-31；**初始表是本次补测前的静态审计，不是新的通过报告**。冻结 CT／HX／BP 场景文件不改写。当前生产提示包、响应适配器、构建和日志版本须由实际执行记录分别填写。

37 个主场景包含参数化变体；C（协调器）、A（组装）、D（设备）分别记账。单个方法名、测试数量、普通任务成功或四组旧设备回归均不能自动关闭一个主场景。以下“已有”是初始盘点，最终执行结果见本文执行登记和[验收报告](HARNESS_DETERMINISTIC_0.3.3.md)，不将初始缺口文字冒认成通过或删除其历史。

## 既有证据索引

- **C1**：[CoordinatorTest](../../android-app/app/src/test/java/org/mobileharness/app/core/CoordinatorTest.kt)：`takeoverDoesNotWaitForHttpCancellationAndLateReplyCannotEnterHistory`、`ownershipEventQueuedBeforeDispatchRejectsAlreadyParsedCandidate`。
- **C2**：同文件 `submittedInputNeedsItsTerminalAndStopIntentWinsAfterFiveSeconds`、`completedPreTakeoverInputIsRetainedOnlyAfterItsTerminal`、`environmentFailureWaitsForInputThenBlocksWithoutPretendingUserStop`。
- **C3**：同文件 `returningScreenshotFailureKeepsManualAndDoesNotConsumeTheCycle`、`unauthorizedReturnKeepsManualWithoutPlannerBudgetOrLateInput`、`secondTakeoverSafelyEndsRatherThanOfferingAnotherReturn`。
- **C4**：同文件 `sharedBudgetIncludesCancelledOriginalWaitAndBothFinishDeclarations`、`secondPlannerFinishEndsWithZeroGuiBalanceFromBothLegalEntrances`、`lastReadonlyWaitEndsForBudgetWithoutInventingFinishOrP2`、`invalidReadonlyLastStepIsModelErrorAndCannotCreateP2Entry`。
- **C5**：同文件 `firstPlannerFailureFallsBackToOriginalHistoryWithoutRepairCall`、`secondPlannerInvalidVerifyFallsBackWithoutAThirdPlannerOrCheckPhase`、`successfulConfirmationEndsWithoutSecondPlanner`、`retryAfterCheckingErrorPreservesReadonlyPhaseAndPlannerCount`。
- **C6**：同文件 `preflightFailureDoesNotChargeAnAttemptAndExplicitRetryDoes`、`stopInvalidatesLatePlannerFinishAndDoesNotTurnStopIntoCompletion`、`environmentCancellingSentP1CanOnlyRetryAsOriginalFallback`。
- **C7**：同文件 `onOffKeepTheSameTaskReturnFrameBudgetAndNoticeButUseDifferentLegalHistories`、`runtimeAuditContainsOnlyMetadataNotTaskArgumentsReportsOrReplies`。
- **A1**：[QwenGatewayTest](../../android-app/app/src/test/java/org/mobileharness/app/model/QwenGatewayTest.kt)：`guiRequestHasOnlyCurrentImageAndNativeHistoryPairs`、`newPhaseHasOnlyItsOwnHistoryAndObservationTools`、`coordinatorNewPhaseReturnNoticeZeroIsAllowedAndNeutral`、`harnessOffOriginalInformationEqualsOriginalFallbackInput`。
- **A2**：同文件 `plannerUsesOnlyCompletedActionsAndIndependentMinimalImages`、`preflightRejectsInvalidImagesAndDuplicateBoundaryIds`、`allFrozenPlannerAndGuiFixturesMatchProtocol`、`secondPlannerFinishIsStrictAndDoesNotRequestGuiDone`。
- **A3**：[AutomaticStartAssemblyTest](../../android-app/app/src/test/java/org/mobileharness/app/model/AutomaticStartAssemblyTest.kt)：全部阶段工具／采样／单图与旧包比较、相同公开证据的 P1／P2 HTTP body 比较、自动模式预检、真实 Gateway 与本地凭据解析。
- **D1～D4**：[DeviceVerification](../../android-app/app/src/debug/java/org/mobileharness/app/verification/DeviceVerification.kt)：D1 为原样原生动作；D2 为 Harness off 人工期间不观察、归还新图与迟到 tap；D3 为真实长按完成终态前不授予人工；D4 为 VERIFY 最后 wait 额度耗尽和 VERIFY unable 后零余额 P2 FINISH 两个变体。
- [CoordinatorAuditFailureTest](../../android-app/app/src/test/java/org/mobileharness/app/core/CoordinatorAuditFailureTest.kt) 的 10 项写故障用例为额外故障回归，不替代 CT／HX／BP 所需参数矩阵。

## CT：接管与迟到结果

| 场景 | 合同层级 | 已有局部断言 | 本次补齐／明确的缺口 |
|---|---|---|---|
| CT-01 | C、D | C1；D2 在无在途输入时立即 MANUAL、无需 HTTP 取消确认 | 对应 D 通知／悬浮状态一致性及唯一 manual_granted 需独立确认 |
| CT-02 | C、A、D | C1、D2：off、新 GUI 已发后、迟到 tap；C4 等间接计账 | on/off × 四到达时点 × tap/completed/unable × 重复回调未全覆盖；需 A 确认下次实际组装不含旧结果 |
| CT-03 | C、A、D | C1 在 typed reply 返回边界排入 takeover，perform=0、后续 history 空 | D 最终派发前门控、组装后的未配对工具／旧候选排除未闭环 |
| CT-04 | C、D | C2 completed 终态；D3 真实 onCompleted 前 PAUSING | 独立 onCancelled、重复终态、HTTP 取消先到与计数不变变体需补齐 |
| CT-05 | C、D | C2 精确 4999／5000ms；随后走 stop | 保持 takeover 意图的重复 recheck、迟到 completed/cancelled 后唯一 manual_granted；D 超过5秒回调门控未覆盖 |
| CT-06 | C、D | C2 在 UNCERTAIN 后 stop、completed 后 ENDED | PAUSING 起始、取消终态、旧模型／图／暂停回调与旧 return 无 transient MANUAL 未全覆盖 |
| CT-07 | C、A、D | C3 归还采图失败／授权错误；D2 归还新图和人工不观察 | 接管前 pending screenshot 在三时点迟到、pre 帧有无、边界槽释放、失败后再取新图及 A 的公开帧集合需补齐 |
| CT-08 | C、D | C1 已解析候选失效 | 旧 wait／队列回调在人工及归还后重复投递；不新增观察／请求／派发的完整计数断言缺失 |
| CT-09 | C、A、D | C3 有重复 giveBack 不新增 P1 的局部断言 | 旧 task／epoch／状态 token 与通知 PendingIntent、悬浮共同入口及 on/off 首次新图门控需补齐；通知权限须真实有效 |
| CT-10 | C、D | C3：off、无输入、第二次 takeover 安全结束 | on/off、在途输入、旧 return／模型／图／wait 回调；D 目标页保持和无第二次归还需补齐 |

## HX：阶段历史与信息边界

| 场景 | 合同层级 | 已有局部断言 | 本次补齐／明确的缺口 |
|---|---|---|---|
| HX-01 | C、A | C4 首 VERIFY 空历史、下一步只含 wait；A1 首阶段单图／只读工具 | 把实际 Coordinator 两个上下文送进 assembler，验证原文／目标／哨兵／唯一当前图的端到端断言 |
| HX-02 | C、A | C5 COMPLETE→CONFIRM 空历史；A1 CONFIRM 模板／单图工具 | 当前原任务必需值与 Planner 目标分离、旧证据哨兵及无逐条件完成门槛需成套检查 |
| HX-03 | C、A | C4 P2 EXECUTE 首 RECOVERY 空历史；C7 P1 EXECUTE；A1 新阶段 | P1/P2 两入口首次及下一次实际组装、旧检查／Planner诊断哨兵缺席、只累积 RECOVERY 自身历史 |
| HX-04 | C、A、D复核 | C5 P1错误恢复原历史；A1 off 与原任务 continuation 同构 | 合法 P1/P2 FALLBACK、恢复后用户合成状态不被重置、切换本身零派发、最新图和无剩余目标注入 |
| HX-05 | C、A | C1/C7 off 保留原历史、无 Planner；A1 continuation 单图 | 实际 off continuation 全消息／通知及预算、无边界帧和无恢复阶段需联合验证 |
| HX-06 | C、A | C7 原文／frame／PNG／budget／notice 存在性；A1、A3 请求参数固定 | exact notice 字节、GUI profile/采样/image/tool 能力逐字段成对相同；只比较合法方法差异以外条件 |
| HX-07 | C、A、D复核 | A1 两个工具turn+唯一当前图；D2归还新图 | ORIGINAL/VERIFY/CONFIRM/RECOVERY/FALLBACK/off 的连续 F1/F2/F3 与保留文本；legacy 混合原始messages入口范围见下方注记 |
| HX-08 | A | A1 检查旧 call id、角色及配对位置 | 参数 JSON／结果文本完整逐字段相等、两／三轮与 fallback/off；不得声称有不存在的旧server混合消息清洗器 |
| HX-09 | C、A | A2 P2两图、P1缺 pre、duplicate id 拒绝；A3 P1/P2 body一致 | 三槽上限／用途、current唯一最新／图文一一对应、C 去重与失效释放；P2保守仅当前图不是‘必须附满三张’ |
| HX-10 | C、A、D复核 | C1/D2迟到模型不执行；C7 metadata无内容 | 迟到截图多时点、原任务与所有新阶段实际 body 中私有／人工／旧结果哨兵缺席、采集入口计数 |
| HX-11 | C、A | C4 P2只含 tap/wait，不含阶段finish；A2过滤 device_error/unknown | rejected/cancelled/仅提议/finish/人工来源全矩阵；注意 assembler 信任 completedActions 来源，C 必须证明该列表只来自实际完成动作 |
| HX-12 | C、A | C4 两种合法查证入口零余额 P2 FINISH；A2第二次finish严格 | VERIFY completed/unable、CONFIRM unable、verification_failed、非零余额变体全覆盖；P2 后零 GUI组装／派发、用户评价独立 |

## BP：预算与规划上限

| 场景 | 合同层级 | 已有局部断言 | 本次补齐／明确的缺口 |
|---|---|---|---|
| BP-01 | C、A、D复核 | C4 共享6步，含取消、wait、两次finish | 逐检查点 G/P、人工前后不变及 A 实际请求；D预算显示/尝试数独立复核 |
| BP-02 | C、A、D变体 | C1取消迟到；C4非法只读；C6 retry；协议非法矩阵A2 | timeout/API/JSON/schema/multi/unknown/length/native reject 参数化收费与单终态，D回调门控；显式新尝试另计 |
| BP-03 | C、A、D变体 | C6 GUI预检不扣费；A2无效图，A3缺自动reader/范围 | GUI/Planner 缺图／无权／超限本地预检；D实际取图／授权失败未进入started |
| BP-04 | C、A、D复核 | C4预算2/6等若干路径 | 默认30与设置6的最后 completed/unable／普通动作全部；最后合法finish不能误拒、普通动作不能免费补finish |
| BP-05 | C、A、D只读 | C4/C5覆盖INCOMPLETE VERIFY/EXECUTE、COMPLETE VERIFY、错误回退；A2 frozenfixtures | 六个合法P1分支、COMPLETE VERIFY零余额、非法完成组合；D确认/查证只读拒绝 |
| BP-06 | C、A、D | C4一次wait；A1只读工具；D4只读末步 | 同阶段 wait→wait→finish 与 VERIFY/CONFIRM 全非法写工具；不得另起第二查证阶段 |
| BP-07 | C、A | C4 VERIFY completed/unable、C5 CONFIRM completed、C4零余额CONFIRM unable | 四结束组合的后续实际请求/报告/最新图/verification_failed 与错误不能冒充unable需联合参数化 |
| BP-08 | C、A | C4 P2 FINISH/EXECUTE、C5非法VERIFY回退；A2 frozenfixtures | VERIFY和CONFIRM unable双入口×所有合法/非法P2组合；不得新建P3/二次查证 |
| BP-09 | C、A、D复核 | C4两入口零余额P2 FINISH；D4 VERIFY unable零余额 | VERIFY completed/CONFIRM unable及有余额、零余额EXECUTE/FALLBACK对照、P2后零 GUI调用/finish/派发 |
| BP-10 | C、A、D传输 | C5 P1 API、P2非法VERIFY；C6环境取消P1回退 | P1/P2各timeout/API/JSON/schema及失权/stop/无GUI余额，不修格式重试，D传输门控 |
| BP-11 | C、A、D错误恢复 | C4正常RECOVERY到finish；C5检查阶段retry | P1/P2 EXECUTE/FALLBACK交付后的 GUI completed/unable/模型错误/设备错误及显式重试，P保持不变 |
| BP-12 | C、A、D入口 | C3重复giveBack不增P1、C6取消P1仅fallback | P1在途/只读/P2在途/交付后重复返回／回调／终态，通知/悬浮不重置规划预算 |
| BP-13 | C、A、D屏障 | C6 P1 EXECUTE迟到后不覆盖stop | P1 VERIFY、P2 COMPLETE FINISH与重复回调、组装/派发增量0；D停止屏障 |
| BP-14 | C、A、D复核 | C4 VERIFY末wait/非法tap；D4末wait不调P2 | CONFIRM同类边界、错误/无效输出/原生终态未知、清理最新帧引用；不能混同BP09合法finish入口 |
| BP-15 | C、A、D日志 | C5有效CONFIRM、C4P2来源；C7 metadata哨兵缺席 | unable非错误、API/设备/格式类别、独立incomplete评价、真正MinimalAudit导出Schema及敏感哨兵排除 |

## 需要在补测中保留的解释边界

1. 现有四组设备记录只覆盖明确的分支，不给其余 D 变体填通过。真实平台难以制造的时序，可在设备内门控回调，但必须写清楚注入层级；不能将替身终态当成 Android 内核已经实际发生该回调。
2. 当前新 APK 的历史类型是 `Turn(reply, result)`，并无旧server原始混合 messages 导入入口。HX-07/08 的 legacy image_url/Base64 用户混合消息清洗不是现存组件；应明确该变体的适用性，而不是凭空新增迁移器或虚构测试通过。实际 typed 历史须证明原文、工具关联和唯一当前图。
3. `queuePlanner(P2)` 采用只附最新图的保守策略；没有通用必要图语义判定器。三槽是上限而非每次附满的要求。重复同一 id 的输入拒绝与 Coordinator 槽位去重应分别判定。
4. Planner 的 `completedActions` 由 Coordinator 从实际完成输入建立；assembler 过滤完成状态并不独立证明来源合法。原生完成只是动作证据，不能升级成隐藏任务成功事实。
5. 新适配器 1.3.0 允许合法工具调用附带文字，但仍不把文字作为动作／成功历史。冻结提示词和历史实验不重写；本次确定性执行要记录实际包／适配器版本。

## 本次执行登记

待主代理完成补测后，追加实际执行编号、构建及 manifest 哈希、C/A/D分层结果和新测试方法映射。没有实际运行记录的项保持待跑／未覆盖；不能将本静态矩阵重命名为37场景全部通过。

### 本次新增实现索引（编写时尚未执行）

| 实现 | 目标场景／层级 | 证据边界 |
|---|---|---|
| [HarnessInformationAcceptanceTest](../../android-app/app/src/test/java/org/mobileharness/app/core/HarnessInformationAcceptanceTest.kt) | CT-02/04/07/08、HX-01～11 的 C/A 补充 | 11 个测试方法含参数矩阵，使用真实 assembler 与合成时序；不是设备结果 |
| [HarnessBudgetAcceptanceTest](../../android-app/app/src/test/java/org/mobileharness/app/core/HarnessBudgetAcceptanceTest.kt) | BP-02～15 的 C 补充；BP-01 沿用既有共享预算主路 | 15 个测试方法含合法／非法分支与预算矩阵；raw JSON 类型／额外字段／截断仍由 A 的协议测试覆盖 |
| [HarnessDeviceControlCases](../../android-app/app/src/debug/java/org/mobileharness/app/verification/HarnessDeviceControlCases.kt) | CT 等 D 门控补充 | 由主代理整合与运行；必须按具体 Case ID 记录真实完成／取消和注入边界，不能按文件存在判通过 |
| [HarnessDevicePlanningCases](../../android-app/app/src/debug/java/org/mobileharness/app/verification/HarnessDevicePlanningCases.kt) | BP-05～11 的 61 个 C/D 分支 | 真实 Coordinator 和 PhonePort，模型是进程内可控结果；不声称真实 HTTP 或原生响应解析 |
| [NotificationDeterministicVerification](../../android-app/app/src/debug/java/org/mobileharness/app/verification/NotificationDeterministicVerification.kt) | CT-09 通知 D：on/off 独立 | 真实自身通知 Action PendingIntent.send、真实 private ControlReceiver ordered broadcast；overlay 仅同一命令入口注入，不声称实际悬浮 UI 点击；通知权限不可用则 BLOCKED |

通知子项只读取自身 notification／状态 token 和计数；人工期间不查询 root、包名、节点或画面。归还观察门控仅用于检查重复触发；实际 capture ticket 另计，不能把 observation 入口数当成已取得画面。所有新实现尚须统一构建和执行，实际记录以主代理最终验收报告为准。

### C／A 本次已执行登记

以下是独立读取本次 JUnit XML 的登记，覆盖当前 0.3.3-dev／versionCode 9 的本地测试；**不将其提升为设备 D 通过**。`testDebugUnitTest` 共 13 个测试类、117 个测试方法，failure=0、error=0、skipped=0。此前 [修复前记录](results/harness-deterministic-0.3.3/before-fix.json)为 114 方法、1 失败，失败是 HX-11 的组装器把 completed 的 finish 声明当作成功手机动作。修复仅在组装器完成状态过滤中排除 finish；Coordinator 原本没有向该表写入 finish，不把该防御缺口说成实际已发生的手机误操作。失败记录保留。

本次对应 [candidate1 构建登记](results/harness-deterministic-0.3.3/candidate1.json)，APK SHA-256 为 `c2283c7d2f094503b76b34eb5170ffdbd2c5ed0c618ea557692af28cc6350cd7`。若设备阶段需要后续修复／重建，应新增候选登记，不能用新包覆盖这条已发生的 C/A 执行事实。

提示／适配器／事件版本分别仍为 1.2.0／1.3.0／1.1.0；JVM 里针对旧模式的组装测试仍显式使用冻结 1.1.0 请求包，自动模式的相同公开证据／采样与工具协议由 `AutomaticStartAssemblyTest` 对照。冻结 manifest 哈希保持 1.1.0=`0434d82c2f01bc4bd39b93df1b65ef176c25031760ef86d9b6ec8988f4c294ad`，1.2.0=`be0723200c4159ae7f4b53b6bdccc78e82eb7ef5b6ce716beef4fae4ac6d003c`。

| 新增测试类 | 本次方法数 | 方法内独立合成轨迹／表 | 对应层级和限制 |
|---|---:|---:|---|
| HarnessInformationAcceptanceTest | 11 | 55 | C＋A，含实际 assembler；不是55个设备或HTTP请求 |
| HarnessBudgetAcceptanceTest | 15 | 104 | C，typed结果／固定错误替身；raw JSON／多字段／截断由已有 A 测试交叉覆盖 |
| HarnessControlTerminalAcceptanceTest | 3 | 18 | C，CT-05 为4条、CT-06 为8条、CT-10 为6条；虚拟原生终态不是实际 Android 回调 |
| 原有测试类 | 88 | 不另汇总为场景数 | 原有 Coordinator／Gateway／协议／桥接／审计等回归，不能据88推37全部闭环 |

Information 的55条按方法分别为：CT-02四时点×双开关×三旧动作=24，CT-04两种归一化终态=2，CT-07迟到截图=6，归还失败=4，旧wait=2，VERIFY／CONFIRM至RECOVERY=2，P2回退=1，开关对照=2，五种执行结果=5，HX-11防御组装表=1，旧P1／P2结果跨任务=6。PAUSING 的旧结果是在本地状态发布检查点入队；没有声称远端HTTP在真实手机的对应微小时段碰巧到达。重复 `CompletableDeferred.complete` 被拒和单终态断言是规范化单结果接口的行为，不等于原始 Android callback 多次真实送达。

Budget 的104条按方法分别为：BP-02 7；BP-03 3；BP-04 12；BP-05 6；BP-06 13（1条多wait主路＋12条只读写工具拒绝）；BP-07 4；BP-08 18；BP-09 6；BP-10两个方法6＋3；BP-11 16；BP-12 1条横跨多个准备边界；BP-13 1；BP-14 6；BP-15 2。BP-01的共享6步主路由既有 CoordinatorTest 单独覆盖。

| 主场景／组 | 本次 C／A 增补对应方法 | 已验证内容，不超出断言 |
|---|---|---|
| CT-01/02 | `ct02AllLateGuiShapesAtAllFourBoundariesStayOutsideEffectiveHistory` | 双开关、四个本地时点和三种旧动作，迟到不增加预算／观察／派发，下一实际组装不含旧结果 |
| CT-03 | 既有 `ownershipEventQueuedBeforeDispatchRejectsAlreadyParsedCandidate` | 已解析 typed候选在最终所有权检查前被接管排除；不是 Android原始触摸回调测试 |
| CT-04/05 | `ct04BothNativeTerminalKindsAreRequiredBeforeManualAndOnlyCompletedActionsReachPlanner`、`ct05MissingNativeTerminalStaysUncertainAcrossRechecksUntilMatchingResult` | completed/cancelled、4999/5000ms、反复recheck、只有匹配终态才授予人工 |
| CT-06 | `ct06StopFromEitherPauseStateNeverBrieflyGrantsManualOrReturn` | PAUSING/UNCERTAIN×双开关×两终态，最新stop意图胜出、无瞬时归还机会 |
| CT-07/HX-09/10 | `ct07LateScreenshotsNeverBecomeCurrentOrBoundaryAndManualHasNoCaptureStarts`、`ct07FailedReturnPreservesCycleAndExplicitReturnGetsOneFreshFrame` | pre有无、三迟到时点、预检／采图失败、只保留合法新帧和当前图、准备后清除边界槽 |
| CT-08 | `ct08OldWaitDeadlinesCannotCaptureOrAppendHistoryBeforeOrAfterReturn` | 双开关旧wait越过虚拟截止时刻不刷新、不计新请求，不污染后续历史 |
| CT-09 | 同上归还失败方法、`bp12DuplicateReturnsAtEveryPreparationBoundaryCannotConsumeAnotherPlanner` | C重复归还只提交一次，规划计数不重置；通知实体与 token 仍看单独 D 结果 |
| CT-10 | `ct10UsedReturnCycleStillStopsSafelyButNeverPermitsASecondReturn` | 双开关×无输入/completed/cancelled，停止后旧回调不能重新归还、人工编辑状态保留 |
| HX-01/02/03/07 | `hx01To03ReadonlyAndRecoveryConversationsAccumulateOnlyTheirOwnTurns` | VERIFY/CONFIRM首对话与wait后自身历史，P2交付RECOVERY后只累积自身turn；唯一当前图、原任务／目标保留 |
| HX-04/08 | `hx04SecondPlannerFallbackRestoresOriginalPairsAtCurrentFrameWithoutUndo` | 合法P2回退原任务／原native工具关联，切换零派发、不撤销人工合成状态、不带旧检查正文 |
| HX-05/06/07/08 | `hx05And06OnOffShareParametersNoticeAndReturnImageWhileKeepingDifferentLegalHistory` | 双开关GUI profile、body非messages部分、通知字节、图像、能力和预算相同；off保留原turn，on合法新阶段 |
| HX-11 | 两个 `hx11...` 方法 | 完成状态全集来源与人工哨兵排除；assembler防御排除finish声明，即本次修复的失败项 |
| HX-12/BP-09 | 既有零余额P2方法、`bp09SecondPlannerAtZeroBalanceCanOnlyFinishAndNeverDispatchGuiDone` | 两个合法入口下P2直接结束，零余额EXECUTE/FALLBACK按额度结束，无新GUI/finish/派发 |
| BP-01～15 | 既有共享预算方法和本表15个 `bpXX...` 方法 | 以104条明确变体及已有C/A断言为限；不因方法名前缀就将所有D子项关闭 |

### 尚需保留的结构／层级边界

- 旧server混合原始messages的迁移／清图接口不是本新APK的输入面；对应 HX-07/08 只能按实际typed历史结构验证并单独记适用范围，不能造一个迁移器来凑通过。
- BP-05“P1已经在途而GUI余额随后降为零”在当前严格串行模型没有合法事件来源：P1在途不发送GUI，任务预算也不允许中途编辑。零余额归还应直接结束、不新发P1；不靠反射改变budget来伪造正常流程。
- candidate1 时 BP-03的请求体超限在C层由预检替身覆盖不收费，实际64MB组装边界尚未单测；candidate2 已补实际RAM组装边界，见下方更新，不能混同两个批次的证据。
- candidate1 设备 control 22例的 CT-02 为人工／归还后两个时点；四时点全矩阵来自C。CT-05/06的5秒与取消／重复归一化终态当时来自C；candidate2 计划补真实原始取消和PhonePort返回点门控，仍不能将后者称为OS回调本身被延迟，见下方更新。
- 设备planning 61例通过与否仍须实际逐例结果；它们替换模型结果源、不经过原生响应解析，A层仍由JVM承担。
- BP-15的FakeAudit内容哨兵和旧真实事件Schema是有限证据；真实MinimalAudit隔离目录里的错误／P2／确认／独立评价导出矩阵若未执行，仍须单列，不能从 FakeAudit 推断已测了文件序列化。

设备结果尚在主代理执行中，本节不填写任何新的 D PASS。最终报告需给出本次APK哈希和每个设备Case ID的状态，并保留上述不能由局部回归外推的限制。

### candidate2 C／A 实测与设备追加登记

[candidate2 构建登记](results/harness-deterministic-0.3.3/candidate2.json)：仍为 0.3.3-dev／versionCode 9，APK SHA-256 `6b665007d00060ecc965fd3083b57b4bde19cd117c1596c85d0695ba91e3a1df`（33,303,321 字节）。独立读取 JUnit XML，14 个测试类共 **122 方法，0 failure、0 error、0 skipped**。相对candidate1新增的5方法为 [AssemblerPreflightBoundsTest](../../android-app/app/src/test/java/org/mobileharness/app/model/AssemblerPreflightBoundsTest.kt)，均已实际执行而非小堆内存条件跳过：

| A 方法 | 实际边界与控制 |
|---|---|
| `invalidPngHeaderOrGeometryStopsGuiAndPlannerBeforeTransport` | 空／短／签名／IHDR／几何等9种合成无效帧，GUI和Planner的preflight/direct调用均不进入计数transport |
| `rawPngAtTwentyMegabytesIsRejectedBeforeEitherRoleCanExchange` | 20,000,000字节合成PNG前缀＋RAM padding，被两角色拒绝 |
| `firstPngSizeWhoseBase64PartCrossesTwentyMegabytesIsRejectedWithoutEncodingOrExchange` | 14,999,983字节原图低于原始上限，但Base64 part预测20,000,002字节，真实预检拒绝、transport=0 |
| `largestPngSizeBelowTheBase64PartLimitCanReachTheFakeTransportExactlyOnce` | 相邻14,999,982字节、Base64 part19,999,998字节，实际组装达到计数transport一次；无HTTP |
| `actualSerializedBodyAtOrAboveSixtyFourMegabytesIsRejectedBeforeTransport` | 约10.7MB合成NUL文本经真实JSON编码展开超过64MB，实际组装拒绝；小控制体能到达一次fake transport，超限体transport=0 |

这些是合成内存数据与实际组装／预检，不是手机截图或真实模型请求，也没有改变生产阈值来缩小边界。至此上方 BP-03 的实际组装超限缺口已补，C 的预算不收费与 A 的真实预检证据仍分别列示。

candidate1 已保存两批设备结果：[BP 63例](results/harness-deterministic-0.3.3/device-BP-attempt1.json)、[CT 20例](results/harness-deterministic-0.3.3/device-CT-attempt1.json)均为 PASS，共83例。这里的BP63包括planning的61例及control的两个BP-13晚到停止变体；不是将61和63当作两批相加。结果由本地可控模型源驱动，不使用商业API，也不经过原生回复解析；A层不能由此替代。

通知子项先保留 [未授权BLOCKED](results/harness-deterministic-0.3.3/notification-without-permission.json)，原因为 NOTIFICATIONS_UNAVAILABLE，observation_entries=0。该旧debug结果误把全局captureOrdinal当成本次增量（显示172），**不代表本次采集了172张画面**；candidate2只修诊断基线未初始化时计数归零，并新增 `baseline_initialized` 标志。原文件保留。Root确认权限不存在USER_SET／FIXED标记后，为已要求的通知备用入口授权；随后 [通知授权后两个变体](results/harness-deterministic-0.3.3/notification-authorized-attempt1.json)均PASS：OFF为合成GUI2/P0、ON为合成GUI1/P1，两者observation_entries=2、actual_capture_tickets=2、perform=0。初始图与归还图各一次，归还本身只增一张；没有将本地合成调用计为商业请求。candidate2还会统一重跑并核对新基线字段。

candidate2 追加并计划统一重跑 **97 个设备变体**（原83＋以下14），编写本段时尚未收到最终结果，故以下不填PASS：

| 追加D变体 | 数量 | 证据边界 |
|---|---:|---|
| CT-04 原始native onCancelled | 4 | on/off × 接管后人工／停止；由独立1ms debug竞争手势刺激原手势取消，必须收到原手势匹配的 onCancelled 才放行。竞争手势是显式测试刺激，不是产品强制取消策略，不隐藏计作Agent动作 |
| CT-05/06 PhonePort终态返回门控 | 8 | on/off × 实际completed/cancelled × 人工／stop；先让真实原生结果产生，再在PhonePort返回点扣留超过5秒，验证UNCERTAIN/recheck/stop。此为执行器返回边界延迟，不冒称OS callback本身被延迟 |
| BP-06 多wait正向 | 2 | VERIFY和CONFIRM各自wait→wait→finish；补充此前正向只测VERIFY的变体 |

另有 [MinimalAuditDeterministicVerification](../../android-app/app/src/debug/java/org/mobileharness/app/verification/MinimalAuditDeterministicVerification.kt) 已编译、待执行：真实MinimalAudit写隔离目录，检查模型/API/设备错误、P2／确认完成与独立incomplete评价分离、活动记录不导出、白名单拒绝、旧／新schema中断补尾与隔离目录清理。它不实例化模型、ProfileStore或PhonePort，不观察手机画面；待设备结果及离线Schema验证后才可关闭 BP-15 的文件序列化／导出缺口。

仍不声称：原始Android回调被真实重复送达、所有设备转场／系统故障均穷尽、旧server原始混合历史迁移器存在，或37主场景的所有C/A/D物理细节全部通过。最终统一结果需在下一节追加，不改写候选1已发生的局部证据。

### candidate2 设备统一执行结果

已独立读取 [最终统一设备记录](results/harness-deterministic-0.3.3/device-final-all-attempt1.json)：run_id=`04b84a11-cadc-49fe-a91e-4ec8bb412161`，**97 个唯一 Case ID 全部 PASS**，总耗时354.622秒；GUI／Planner网络请求均为0，原控制接口和原生终态已确认恢复。这里的97是下表明确的设备分支集合，不是37个主场景×全部层级×全部物理变体。

| Case ID前缀 | 通过数 | 对应行为及层级边界 |
|---|---:|---|
| BP05 | 6 | P1六个合法分支各自交付正确阶段，真实Coordinator与PhonePort；typed模型结果源 |
| BP06 | 14 | 两只读phase×六写工具拒绝=12，加VERIFY和CONFIRM各wait→wait→finish=2；无真实模型调用 |
| BP07 | 4 | VERIFY／CONFIRM × completed／unable，后续请求／结束语义分离 |
| BP08 | 12 | 三个合法只读结束入口×P2四个合法分支；非法raw JSON／Schema变体由C/A承担 |
| BP09 | 9 | 三入口×零GUI余额下FINISH／EXECUTE／FALLBACK，P2直接结束不增GUI done |
| BP10 | 6 | 两个Planner ordinal×三个固定错误类别，按原任务回退而无修复规划调用 |
| BP11 | 12 | P1/P2 EXECUTE/FALLBACK交付后completed／unable及显式retry路径不重规划 |
| BP13 | 2 | P1和P2晚到结果不能覆盖用户停止 |
| CT02 | 12 | on/off × 人工／归还后 × tap/completed/unable；四个微小事件时点全矩阵仍由C承担 |
| CT04 | 4 | on/off × 人工／stop，独立debug竞争手势刺激原手势，等待原手势真实匹配的cancelled终态，不用协程取消充当该终态 |
| CT05 | 8 | on/off × 实际completed/cancelled × 人工／stop，在PhonePort返回点扣留已产生的真实终态超过5秒，UNCERTAIN/recheck/停止规则通过；覆盖CT-06的相应停止分支 |
| CT07 | 4 | pre有无 × 人工／归还后迟到观察，人工期间无新观察，旧帧不成为恢复当前图 |
| CT08 | 2 | on/off旧wait在人工及归还后不启动新的观察／请求 |
| CT10 | 2 | 已用一轮归还后无输入／真实长按在途，再次接管按停止处理，禁止第二次归还 |

以上原始取消测试的独立1ms竞争手势是显式debug刺激，不是生产Harness取消手势的实现，也不隐藏计为Agent提出的动作。PhonePort门控八例则是在真实原始回调已经到达后延迟向Coordinator提交结果；它验证归一化执行器边界的暂停屏障，不冒称内核／OS callback本身被延迟5秒。C的4999／5000ms精确虚拟时间和D的真实等待互相补充，不互相替代。

[修复后未授权边界](results/harness-deterministic-0.3.3/notification-without-permission-fixed.json)已实际重跑，状态正确为 BLOCKED／NOTIFICATIONS_UNAVAILABLE，175ms：`baseline_initialized=false`、observation_entries=0、actual_capture_tickets=0、perform=0，GUI／Planner网络请求均0。该检查关闭前述诊断计数基线误报，不将权限不足写成通知入口成功。Root随后恢复通知权限；candidate2授权后ON/OFF与MinimalAudit导出／旧四组的最终结果见下方闭环登记。

### 目前可报告的最大结论与剩余客观边界

可报告：新组装防御修复后122个JVM方法及上述97条可适用设备轨迹通过；candidate2授权通知两条、真实MinimalAudit导出55条及原四组设备回归均通过；未授权计数边界按预期阻断。C/A/D按具体实现入口联合支持接管失效、历史隔离、共享预算、规划上限与直接结束的确定性合同。**不报告“37主场景所有层级、每个物理故障排列都PASS”。**

保留边界：

1. 原始Android callback重复送达没有被强行制造；重复 `complete`／规范化模型结果只能证明单结果接口及协调器不会重复消费。CT-02设备时点也未穷尽四种微小时序，完整四点组合来自C门控。
2. 设备模型均为本地可控结果源；商业服务的JSON产生能力、HTTP实际取消／重复送达和模型任务能力不在本批确定性结论内。原生响应解析由A覆盖，不把D的typed结果当成真实Qwen响应。
3. 真实PhonePort与输入器使用已授权合成页；不是任意App、系统弹窗、锁屏、所有动画倍率／输入法／进程死亡路径的穷举。JVM大body边界也不证明Pixel 4在内存压力下的性能上限。
4. legacy原始混合messages不是新APK历史入口；必要图采用实际保守策略，没有通用语义最优选图器。结构不可达的“P1在途后GUI余额自行降低”未通过反射篡改伪造。
5. 通知PendingIntent是真实实体与Receiver通路；overlay的重复动作只在共同命令入口注入，未声称真人点开通知面板或点按悬浮按钮的UI验收。锁屏认证UI也未由本测试宣称通过。
6. 本批没有采集人工操作轨迹／画面来证明隐私；以采集入口计数、失效代次和请求内存断言验证。最小日志真实文件导出已由 candidate2 专用隔离探针通过，并分别校验新 1.1.0 的 52 条及旧 1.0.0 的 3 条；这是实际 MinimalAudit 文件与导出，不是从 FakeAudit 推断。


### 最终存储／通知／收尾闭环

- candidate2 [通知ON/OFF](results/harness-deterministic-0.3.3/notification-final.json) 两变体通过，4.329秒；每个变体只有一张归还新图。
- [真实MinimalAudit](results/harness-deterministic-0.3.3/audit-final.json) 的52条1.1.0和3条1.0.0记录通过对应Schema与会话序号校验，BP-15实际文件导出子项关闭；未读取个人会话或密钥。
- [原四组](results/harness-deterministic-0.3.3/device-original4-final.json) 通过，26.314秒；[收尾](results/harness-deterministic-0.3.3/final-readiness.json) 原控制器、服务、悬浮和通知停止入口恢复。
- [机器汇总](results/harness-deterministic-0.3.3/summary.json) 经离线脚本复核为 PASS_FOR_RECORDED_APPLICABLE_LAYERS；最终APK与已安装哈希均为 `6b665007d00060ecc965fd3083b57b4bde19cd117c1596c85d0695ba91e3a1df`。
- 总体解释、修复前失败、构建版本与原始回调/物理UI边界见[最终报告](HARNESS_DETERMINISTIC_0.3.3.md)，不将方法数或变体数冒充37个主场景所有排列通过。
