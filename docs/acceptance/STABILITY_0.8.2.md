# 0.8.2 稳定性验收

**状态：本轮规定范围通过，已安装到手机。** 日期：2026-11-03。调试包：`0.8.2-dev / versionCode 20`，执行策略 `1.2.0`。机器汇总见 [summary.json](results/stability-0.8.2/summary.json)；这不表示跨天长跑、所有设备或原 37 个主场景的每种平台变体已通过。

本轮在[冻结的 0.8.1 基线](../../baselines/2026-11-03-mobileharness-0.8.1/README.md)上补齐可重复的故障与控制组合。目的在于验证 APK 调用、解析、采集、派发和收敛过程；不以提升模型推理、修正模型落点或提高任务成功率为目标。冻结归档与历史失败证据均保留。

## 范围与当前进度

| 层次 | 本轮检查 | 当前状态与含义 |
| --- | --- | --- |
| JVM 回归 | 22 个 suite、220 个 JUnit 方法；新增 17 个方法，内部展开 63 条参数轨迹 | 最后一次构建全部通过，0 失败／错误／跳过；[22 份原始 XML](results/stability-0.8.2/unit-tests.json)已归档。参数轨迹不额外算作 JUnit 方法 |
| 手机 Harness 兼容场景 | 当前默认策略下适用的 81 个受控变体，分 22 批 | 全部通过；原生取消、迟到回复／截图、等待取消、输入终态屏障、规划与预算分支均按各自断言记录 |
| 手机自动恢复 | R1–R8，连续 3 轮 | 三轮均通过；每轮仍是相同的 8 个场景，不代表 24 种独立能力 |
| 通知控制 | Harness ON / OFF 两个变体 | 通过，使用真实通知 PendingIntent 和私有接收器；未宣称手指点击通知 UI |
| 隔离存储 | 32 个固定检查标志 | 全部通过；标志不是 32 个真实任务 |
| 原有数据保留 | 18 份事件文件、18 份加密任务附件，以及配置和授权密文摘要 | 全部逐文件 SHA-256 一致；正常页面仍能读取两份已加密配置和 24 个授权 App |

以上数字代表不同证据层，**不能相加为“总验收场景数”**。旧策略的 12 个只读拒绝后等待 BLOCKED、4 个显式 Retry 设备断言继续保留，不强行套入默认自动恢复策略，也不把历史 97 个设备变体当作本版已全部重测。当前策略的失败恢复和计账由新增组合测试及 R1–R8 单独验证。

## 新增确定性覆盖

[恢复与 Harness 组合测试](../../android-app/app/src/test/java/org/mobileharness/app/core/CoordinatorRecoveryHarnessAcceptanceTest.kt)新增 12 个方法、50 条参数轨迹，重点覆盖：

- 取消、自动恢复、只读查证、恢复执行共用 GUI 预算；最后一步 wait 耗尽时不追加 Planner 或结束调用。
- 两次 Planner / 一轮查证上限；P2 完成后直接结束，零 GUI 余额也不额外要求 GUI done。
- 阶段历史隔离、回退原历史、Planner 仅参考自身成功动作，以及交付后不持续重规划。
- 恢复中的迟到截图与不同形态回复，不能穿过人工、归还或新任务边界。
- Harness ON / OFF 接管通知、新观察和预算信息条件一致；已使用的接管周期不因恢复而重置。

[生命周期组合测试](../../android-app/app/src/test/java/org/mobileharness/app/core/CoordinatorLifecycleStabilityTest.kt)新增 5 个方法、13 条参数轨迹，使用实际 `PhoneBridge` 与虚拟端口验证：

- 硬设备信号取消在途 GUI 或恢复退避，旧回复不能在新任务派发输入。
- 人工期间断连仍保留人工控制；服务替换后，归还须成功取得新画面才提交。
- 替换服务不能掩盖旧端口未决原生输入；超时仍保持屏障，等待真实 completed / cancelled 终态。
- 锁屏信号中断 wait 后，不继续截图、调用或额外扣步。

这些测试注入的是生命周期信号与端口结果，**不等于本轮真的锁屏、杀进程或断开无障碍服务**。人工期间服务中断会释放旧边界图；重连归还时 Planner 仅使用新图，这是对既有行为的验证。

## 手机层的证据边界

[自动恢复诊断](../../android-app/app/src/debug/java/org/mobileharness/app/verification/AutomaticRecoveryVerification.kt)使用本机合成测试页、真实 Accessibility 截图和派发路径，以及受控模型回复和故障注入。它覆盖恢复、原生动作、停止、人工控制、归还、Planner 直接结束及重复运行隔离；原始结果见 [第 1 轮](results/stability-0.8.2/recovery-round-1.json)、[第 2 轮](results/stability-0.8.2/recovery-round-2.json)、[第 3 轮](results/stability-0.8.2/recovery-round-3.json)。

| 场景 | 验证内容 |
| --- | --- |
| R1 | 单次采图失败后重新观察，GUI finish 独立计账 |
| R2 | 请求超时后新截图／新决策，合成页真实点击一次并独立核验 |
| R3 | 连续三次采图失败仅恢复两次，结束为 runtime_failure，不等待 Retry |
| R4／R5 | 恢复延迟中接管／中止，取消后无新增采图或模型调用 |
| R6 | 归还后 VERIFY 历史隔离、GUI 错误恢复、零余额合法 P2 采图恢复、Planner 直接结束 |
| R7 | Harness OFF 归还及自动恢复，保持原历史／通知、新画面，无 Planner |
| R8 | 同协调器连续任务，实际生成的旧迟到 finish 不得结束新任务 |

手机 Harness、通知和自动恢复诊断都使用独立协调器及内存审计，完成后必须恢复实际 App 控制对象并确认原生终态。受测 MANUAL 区间只检查自身状态和计数，不读取目标画面、节点、包名或人工轨迹。隔离存储检查使用单独目录与测试数据，不操作用户现有记录。

这些设备诊断使用选定的合成 App 入口，不等于用户日常 `HOME_AUTO` 从桌面选择真实 App，也不等于商业模型能力验收。新增组合 JVM 测试使用 HOME_AUTO 和生产请求组装器，但回复由替身提供。本轮 GUI / Planner 远程请求均为 **0**。模型回复解析沿用并重跑现有网关、协议和请求组装回归；受控回复无法证明远端服务长期一定输出有效协议。截图失败和 TIMEOUT 为封装层注入，未伪称真实 TLS／系统截图故障；在飞截图接管竞态的本轮新覆盖主要在 JVM 层。

## 初始候选发现与处理

初始候选在 `CT04-D-OFF-NATIVE-CANCEL-MANUAL` 的精确事件计数断言失败。协调器在同一 Main 回合中先发布 MANUAL 状态，再同步写入 `manual_granted`；诊断在 Default 线程读取二者，可能读到中间状态。原生终态和人工状态已经出现，问题归类为 **debug 验收观察跨线程竞争**，不是模型决策或生产执行失败。

仅将 CT04 / CT05 的配对状态与审计计数读取放到 `Dispatchers.Main.immediate` 同一读取块。未放宽断言、增加等待重试或修改生产协调器。原始失败、APK 摘要与审查记录保留在 [initial-candidate](results/stability-0.8.2/initial-candidate/review.json)；[最终 CT04 四个变体](results/stability-0.8.2/harness-CT04.json)及 [CT05 八个变体](results/stability-0.8.2/harness-CT05.json)均已通过。

通知旧诊断器还使用固定中文标题查找动作，与英文环境下生产通知的本地化标题不符，首次报告 NOTIFICATION_ACTION_NOT_PRESENT。仅改为调用同一 `localizedLabel(context)`，没有修改生产通知或接管接收器，也没有切换用户语言。原阻断保留在 [审查记录](results/stability-0.8.2/notification-label-candidate/review.json)，[修正后的 ON／OFF](results/stability-0.8.2/notification.json)通过。电脑端最后的 UI 复核还修正了 CLI／PowerShell 节点解析和标题重名选择问题，[最终 UI 结果](results/stability-0.8.2/device-ui.json)通过；这些均非模型或 APK 执行失败。

81 个 Harness 变体和三轮恢复运行于 SHA-256 `9cfe89584309b3d1ee47d17dd44c317af839e7fdabe0ca52b4076ff9b211b9e7` 的候选包。其后仅修改上述一处 debug 通知标题选择器，重跑全部 220 项 JVM、通知和存储检查。最终安装包为 `9a2ffa288ef59563832a8222d1d6575cbbac8fbffb5c1f007b7e510dba74005c`，签名校验通过且安装后摘要匹配。汇总器逐项确认这两个候选的其他 APK 源码一致，不把所有原生结果伪称来自同一二进制包；详见汇总中的 `tested_builds`。

## 改动与数据边界

本阶段改动限于版本号、debug 诊断、单元测试、验收工具和文档。[源码差异摘要](results/stability-0.8.2/source-delta.json)确认生产 `src/main`、提示词、数据契约和运行策略保持与冻结基线相同，不以版本号变化暗示策略变化。

已有用户数据按加密文件和无内容事件文件逐项比较 SHA-256：[测前](results/stability-0.8.2/device-before.json)与 [测后](results/stability-0.8.2/device-after.json)完全一致。不把正文或密钥拉到电脑，不在电脑解密配置，不导出任务原文，不保存截图或原始模型回复。普通模型页仅在手机内正常读取配置。服务仍连接、控制对象已还原，原生测试终态已确认；未卸载、清数据、强停主 App 或重置授权。

采集工具：[run_stability_acceptance.py](../../tools/run_stability_acceptance.py)。离线汇总工具：[summarize_stability082.py](../../tools/summarize_stability082.py)。原始结果目录：[stability-0.8.2](results/stability-0.8.2)。最终汇总须核对每个期望结果、版本、策略、固定标志与测前测后摘要，不能只依据界面显示“结束”。

本轮归档可在源码仍与本验收一致时复核：`D:\Anaconda\python.exe -B tools/summarize_stability082.py`。冻结基线另用 `tools/verify_baseline.py baselines/2026-11-03-mobileharness-0.8.1` 校验。新一轮手机验收须使用新输出路径，采集器拒绝覆盖原始证据；它不自动清数据、重设权限或发起真实模型请求。

## 仍需单独验证

- 真正的进程死亡与重新启动、系统锁屏 / 解锁、旋转和 Activity 重建。存储重建能够验证 interrupted 补写，不证明整个 Android 进程生命周期都已覆盖。
- 多日使用、持续长任务、不同手机和 Android 版本、真实网络波动与远端服务行为。
- 真实 App 人工接管的任务结果、参与者研究流程和独立成功评估。既有用户实测报告继续保留，不能用本轮零网络诊断替代。

执行策略 1.2 的预期是：可恢复故障在有限次数内重新观察并请求新决策；持续或不可恢复故障安全结束为 `runtime_failure`，未决原生输入保持屏障。它不承诺任何设备或网络故障下永不结束，也不把模型声明完成等同于独立确认成功。
