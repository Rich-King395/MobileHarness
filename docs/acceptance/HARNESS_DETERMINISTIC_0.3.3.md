# Harness 确定性验收 · 0.3.3-dev

本轮验证 Harness 合同，远程 GUI／Planner 请求均为 **0**。用可控模型替身安排迟到、失败和规划分支；真机使用已授权 Pixel 4／Android 13 及合成测试页，不依靠商业模型偶然给出某种回答。

完整场景依据仍是冻结的 37 个主场景。执行证据按 C（协调器）、A（真实请求组装／解析）和 D（设备集成）登记，见 [分层矩阵](HARNESS_DETERMINISTIC_MATRIX.md)。不能将测试方法数量、合成轨迹数、真机变体数相加后称为场景通过率。

## 已复现并修复的边界

[修复前记录](results/harness-deterministic-0.3.3/before-fix.json)：114 个 JUnit 方法中，HX-11 的 A 输入表失败。给组装器提供一条 `result=completed` 的 finish 时，旧过滤只看 completed，结束声明会进入 Planner 的 own_action_history。

当前协调器从不把 finish 加入 completedActions，因此这不是已经观察到的真实任务历史泄漏，也不是模型决策错误。本次在组装出口独立排除 finish，避免结束声明被当作已完成的手机动作。没有改动合法动作参数、原任务、模型推理、坐标或 Planner 分支规则。

App 升为 0.3.3-dev（versionCode 9）；自动任务提示包仍为 1.2.0，原响应适配器 1.3.0 保持。旧模式及多数方法夹具使用冻结 1.1.0，真实组装大小边界测试使用 HOME_AUTO／1.2.0。冻结提示词、旧实验与日志 Schema 均未改。

## C／A 确定性结果

**122 项 JUnit 方法通过，0 failure／error／skipped。** 其中新增 34 个方法：

| 文件 | 方法数／轨迹 | 主要断言 |
|---|---|---|
| HarnessInformationAcceptanceTest | 11 方法，54 个 C 轨迹和 1 个 A 输入表 | on/off × 迟到时点 × tap／两种 finish，旧图隔离，阶段历史、fallback、归还通知、当前单图与自身成功动作 |
| HarnessControlTerminalAcceptanceTest | 3 方法，18 个 C 轨迹 | 5 秒不造终态，PAUSING／UNCERTAIN 的停止优先，completed／cancelled，一轮用尽后的安全停止 |
| HarnessBudgetAcceptanceTest | 15 方法，104 个 C 轨迹 | B=6／30，共享计账，所有合法／非法规划路由，只读拒绝、P2 零余额直接结束、错误回退与无持续重规划 |
| AssemblerPreflightBoundsTest | 5 方法 | 实际坏 PNG／几何、20MB 原图与 Base64 part 上限、真实 JSON 序列化 64MB 总请求上限及正对照 |

大请求测试通过约 10.7MB 合成 NUL 文本的真实 JSON 转义产生超过 64MB 的 body，不靠降低阈值或替身抛错。它在本次测试堆中实际执行，没有使用内存不足的 assumption 跳过。被拒绝的请求均未进入 transport.exchange；正对照只进入本地计数 transport，不是 HTTP。

图片头部预检不是完整 PNG CRC／像素解码器；在设备上另用原生截图和 Bitmap 解码检验实际图像。重复完成单次 suspend／deferred 在接口处被拒绝，也不等同为已经对 Android 原始回调做了重复注入。

## D 层测试方式与构建

[candidate1](results/harness-deterministic-0.3.3/candidate1.json) 的 83 个设备变体先行通过：[规划／预算 63 项](results/harness-deterministic-0.3.3/device-BP-attempt1.json)和[接管／迟到 20 项](results/harness-deterministic-0.3.3/device-CT-attempt1.json)。使用真实 Coordinator 和 PhonePort；GUI／Planner 为进程内受控结果，未调用 Key 解析器或 HTTP。

[candidate2](results/harness-deterministic-0.3.3/candidate2.json) 增补后统一验收 97 个变体。生产修复与 candidate1 相同，新增项位于 debug：

- 4 个原生取消变体：已跟踪的 2 秒手势发出后，在预授权合成页静态区域施加独立的 1ms debug 扰动。依据 [Android dispatchGesture 文档](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#dispatchGesture(android.accessibilityservice.GestureDescription,%20android.accessibilityservice.AccessibilityService.GestureResultCallback,%20android.os.Handler))，新手势会取消在途手势。测试必须收到**原已跟踪手势自己的 cancelled 结果**并确认屏障；不会因新扰动被接受就假定旧输入结束。扰动不是新的模型动作或产品的接管实现。
- 8 个终态延迟变体：真实 native completed／cancelled 已到达后，在 PhonePort 向协调器返回结果处门控暂扣，跨过 5 秒并反复 recheck／giveBack，验证仍是 UNCERTAIN；放行原真实结果后才进入 MANUAL 或完成停止。此证据是适配层交付延迟，不是把 OS 原始回调伪装成延迟 5 秒。
- VERIFY／CONFIRM 各一个多 wait 正向变体：wait → wait → finish 保持同一只读阶段，没有第二轮查证或额外规划。

迟到截图变体在原生截图完成后、PhonePort 返回前暂扣旧结果，之后在人工或归还后交付；期间没有再次读取人工页面或启动截图。旧 wait 使用真实计时取消跨过旧 deadline。它们不能被改称为底层 TakeScreenshotCallback 所有重复／迟到形式已注入。

所有设备场景独立复位、逐例归档；失败即停止，未知终态不清空标记或强制伪造空闲。截图只在 RAM，结果只含固定 Case ID、布尔、计数和固定错误码。

## 通知、日志与权限

[通知未授权时](results/harness-deterministic-0.3.3/notification-without-permission.json)正确 BLOCKED／NOTIFICATIONS_UNAVAILABLE，没有进入观察。该版诊断在建立计数基线前失败，错误地把全局 capture ordinal 172 写成增量；这不是新增截图 172 次。已修正为未建立基线时 0，并增加 baseline_initialized 标记；旧错误记录保留。

系统未记录 USER_SET／USER_FIXED 禁用，故为用户已要求的通知备用入口授予 POST_NOTIFICATIONS。通知内容只含控制状态。授权后的 [candidate1 通知测试](results/harness-deterministic-0.3.3/notification-authorized-attempt1.json)中 Harness on／off 均通过：真实系统通知 PendingIntent、private ControlReceiver 旧任务／旧 token／状态拒绝、重复归还一次采图／提交和正确 G／P 计数。overlay 部分是同一命令入口注入，不能写成真实 UI 点击。

日志使用真实 MinimalAudit 与 ContextWrapper 的孤立 noBackup 目录，测试自身生成的合成 metadata；不读个人会话、配置或 Key。P2／CONFIRM 模型结束与随后 user_reported_incomplete 独立记录；活跃任务不导出、不删除、不提前评分；未知字段、正文、坐标、错误 reason 的误用被排除；新旧 Schema 的进程中断补尾沿用原版本。实际导出另由 host 按冻结 1.0.0／新 1.1.0 Schema 逐条核验。

日志 probe 验证这些真实来源的记录及导出，不声称 MinimalAudit 对任意内部调用均是完整 Schema 解释器。进程中断 fixture 故意含有 started 而无模型终态，它只是合成日志输入，不能算作真实发出的 API 请求。

## 范围说明

- 新 APK 没有旧服务器 mixed messages 的迁移入口；实际 Turn 类型不能容纳历史图片 content parts。此类旧输入导入变体单列为结构边界，验证实际请求始终只组装当前图并原样保留文本／工具关系，不假造一个图片清洗器。
- 当前串行协调器在 P1 在途时不再消耗 GUI，不能通过反射减余额伪造“P1 发出后 GUI 余额自行变为 0”的正常路径。零余额归还结束与合法只读 finish 后零余额 P2 分别验收。
- 仍不把 OS 原始 callback 的所有重复注入形式、真人物理操作、真实网络取消或模型效果当成本套已证明事项。本套验收不新增多次归还、隐藏重试或逐条件结束门槛。

## 最终执行登记

| 证据 | 最终结果 |
|---|---|
| JVM C／A | 122 方法通过，0 failure／error／skipped |
| [统一设备套件](results/harness-deterministic-0.3.3/device-final-all-attempt1.json) | **97／97 PASS**，354.622 秒，全部收尾确认 |
| [通知 on／off](results/harness-deterministic-0.3.3/notification-final.json) | 2／2 PASS，4.329 秒；各 2 个 capture ticket 为初始图＋唯一归还图，归还增量为 1 |
| [修正后未授权通知](results/harness-deterministic-0.3.3/notification-without-permission-fixed.json) | 正确 BLOCKED，基线未初始化，观察／新截图／动作均为 0；随后已恢复授权 |
| [真实日志导出](results/harness-deterministic-0.3.3/audit-final.json) | 通过；52 条 Schema 1.1.0、3 条冻结 1.0.0 全部通过 host 校验和各会话连续序号检查 |
| [原四组 PhonePort 回归](results/harness-deterministic-0.3.3/device-original4-final.json) | 4／4 PASS，26.314 秒 |
| [最终就绪](results/harness-deterministic-0.3.3/final-readiness.json) | 服务已绑定、原 app.runtime 恢复、悬浮停止及通知备用入口可用、截图隐藏态已退出 |

97 项包含 BP 65／CT 32，具体 ID 与分母见 [机器汇总](results/harness-deterministic-0.3.3/summary.json)。既有 83 项先跑记录、未授权阻断、初次诊断计数问题及 HX-11 修复前失败均保留，没有只筛成功样本。最终条件下没有新的失败；本轮商业模型网络请求总计 GUI **0**、Planner **0**。模型替身与孤立日志中的 synthetic started 不是网络请求。

最终包已覆盖安装：**0.3.3-dev／versionCode 9**，33,303,321 字节，SHA-256 `6b665007d00060ecc965fd3083b57b4bde19cd117c1596c85d0695ba91e3a1df`，签名通过。没有卸载、清数据或写入变更个人模型配置／密钥／授权范围；通知权限为用户要求的备用入口保留开启。手机回到正常任务入口。

离线复核命令：`D:\Anaconda\python.exe tools/verify_harness_deterministic033.py`。它检查当前122项JUnit结果、97个唯一设备Case、通知两变体、四组回归、55条真实日志导出的版本／Schema／序号、收尾和APK哈希；不接触设备或服务。冻结提示 manifest 及旧事件 Schema 哈希均保持原值。

这关闭了本轮关键 Harness 逻辑与适用设备路径的确定性缺口。它不是“37 主场景所有层级、所有原始 OS 回调与物理 UI 排列均通过”的证书；上节边界和矩阵中的注入层级继续有效，不以修改模型推理、旧提示或隐藏重试换取通过。
