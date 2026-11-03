# 0.4.0：任务页与悬浮控制

日期：2026-11-02。Pixel 4／Android 13。已保留数据覆盖安装 `0.4.0-dev`（versionCode 12），当前服务实际绑定、控制入口归属原运行器。GUI／Planner 真实网络请求均为 **0**。

## 改动

- 任务页使用单一白色输入区、持续标签、可换行的授权／Harness／预算控件；接收服务摘要紧凑显示，发送详情和主动同意保留。
- 正文独立滚动，底部保留开始按钮及首个准确阻断原因；启动条件、授权保存、同意失效点与主页前台规则不变。
- 悬浮面板分开显示状态、简短说明、计数及主动作。活动收起态 112×56dp、空闲态 56×56dp；48dp 触点、8dp 间距；展开高度按内容测量，受安全区域限制。
- 状态球仍只展开／收起，停止保持独立；命令集合、拖动、截图完整隐藏／恢复收起、原落点避让及节点排除规则不变。通知只改中文状态文案及 Planner `/2` 显示。
- 新增 DUMP 保护的 debug 物理交互验证入口；不属于日常任务流程，不读取本人凭据，没有 HTTP Gateway。

构建工具与依赖未升级，提示词包 1.2.0、适配器 1.3.0、最小日志合同不变。[97 个未修改文件的校验](results/ui-overlay-0.4.0/unchanged-contract-after.json)包含核心协调、模型／存储实现、主 Manifest、资源、提示词与数据合同。三处生产修改为 `MainActivity.kt`、`OverlayControlPanel.kt` 和通知文案所在服务文件。

## 完成的验证

| 项目 | 结果与范围 |
| --- | --- |
| 构建／签名／JVM | [最终构建](results/ui-overlay-0.4.0/build.json)：130 项测试，无失败／错误／跳过；APK v2 签名通过，设备安装文件摘要与构建相同。 |
| Compose 合成预览 | [四个预览](../previews/0.4.0/)渲染并查看：空任务、普通任务、360×720／1.3 字号、752×393 横屏。图片均为离线合成内容。 |
| 任务页真实交互 | [25 个旗标通过](results/ui-overlay-0.4.0/task-ui-attempt4.json)：空任务原因，任务框／开始在真实 IME 上方，切页保留 RAM 草稿，两份加密配置保留，预算键盘及完成可达，授权搜索／保存按钮在 IME 上方，取消不改范围，同意可达／可勾选／可清除。未点击开始，没有发送任务。 |
| 悬浮截图／避让 | [通过](results/ui-overlay-0.4.0/overlay-capture-attempt2.json)，4.002 秒。展开可见基线有差异，生产取图与隐藏参考匹配，恢复收起且入口可用；合成页一次生产 tap 避让成功，原参数／帧身份不变且收到原生终态。图片只在手机 RAM 比较。 |
| 真实触摸，Harness ON | [通过](results/ui-overlay-0.4.0/physical-overlay-on-attempt2.json)，7.638 秒。ADB 实际触摸自家状态球、接管、归还、停止；G2／8、P1 均为本地替身计数，HTTP 为 0。 |
| 真实触摸，Harness OFF | [通过](results/ui-overlay-0.4.0/physical-overlay-off-attempt1.json)，7.464 秒。同样物理控制路径，G2／8、P0；归还后没有第二次接管入口。 |
| 通知命令 | [ON／OFF 两变体通过](results/ui-overlay-0.4.0/notification-attempt1.json)：真实 PendingIntent、私有接收器、旧 token／状态校验。它不是通知面板物理点按或全部锁屏认证 UI 的证明。 |
| 系统字号 | [字号检查旗标](results/ui-overlay-0.4.0/layout-scale-rotation-attempt5.json)中 1.3 倍字号的开始可见、窗口内位置、同意可达及设置生效通过；该文件整体仍为 FAIL，原因是另一横屏前置条件未建立。 |

物理交接场景使用真实 Coordinator、截图和一次原生长按。为确定性观察暂停中，暂扣的是**已收到的真实结果在 PhonePort 返回边界的交付**，并非拖延 OS 回调。真实接管命令先关闭采集，确认没有归还按钮；释放已到达的终态后才进入人工。人工阶段只读自家控件，不读目标画面、窗口、节点或操作轨迹；归还后确认恰好一个新观察、共享额度未重置，最后停止并恢复旧 controls。没有在本轮安排目标 App 内的人工业务操作。

## 未通过记录与归因

- [截图首轮](results/ui-overlay-0.4.0/overlay-capture-attempt1.json)在采图前被前台守卫阻止，随后确认手机锁屏；截图及目标执行均为 0，controls 已恢复。用户解锁后同包通过。
- 首个电脑端物理交互脚本的参数绑定错误发生在启动调试场景后；修正脚本后首次完整流程[未到达恢复](results/ui-overlay-0.4.0/physical-overlay-on-attempt1-failure.json)，按超时中止并安全清理。旧场景仍在运行时重新拉起 singleTask 调试 Activity 是已识别的测试风险；首轮归还的具体阻断原因没有独立记录，不能确诊 APK 故障。脚本现拒绝重入活动场景，并在点击前核对布局后的自家控件坐标；清理后的 ON／OFF 通过，不把复测通过当作首轮原因的唯一证明。
- 任务页检查 [attempt1](results/ui-overlay-0.4.0/task-ui-attempt1.json)～[attempt3](results/ui-overlay-0.4.0/task-ui-attempt3.json)未完整通过。检查器曾假设默认任务标签及只按 Activity 标题选择窗口，遗漏了使用独立标题的 Compose 弹窗；固定标签诊断确认弹窗和预算 30 实际存在。修正观察器后 attempt4 完整通过，没有为此修改 APK。
- 字号／旋转诊断的多轮原始结果保留。曾使用“必须有滚动范围”和“滚动后仍须看见任务框”的错误判据，已改为关键控件可达。**横屏真机控件验收尚未通过**：窗口方向条件未可靠建立，[方向诊断](results/ui-overlay-0.4.0/rotation-observer-diagnostic.json)也未取得可用原生窗口方向，不据此归因 APK 或宣称横屏通过。横屏目前只有合成 Compose 预览；本轮停止扩大该专项。

## 最终设备与数据

[最终任务页](results/ui-overlay-0.4.0/final-ui.json)确认：空任务、原有 **18 个授权 App**、Harness 开、30 步、同意未勾选，开始入口可见。[最终服务](results/ui-overlay-0.4.0/final-readiness.json)确认 runtime 为 IDLE、原 controls 恢复、无障碍及悬浮／通知备用入口就绪。[系统设置](results/ui-overlay-0.4.0/layout-settings-final.json)已恢复原字号 1.0、锁定竖屏 0、屏幕超时 1800000ms。

保留原配置、Key 与个人日志，没有卸载／清数据、强停主应用、修改无障碍安全开关或执行 instrumentation runner。调试探针只持久化状态、计数及布尔旗标；用于真实点按的自家控件坐标仅通过临时广播返回，不写入结果文件。没有个人截图、模型正文或密钥导出。

本轮不是重新验收全部 97 个历史设备变体或 37 个主场景，也不证明所有屏幕／字号／系统、真实网络取消或模型业务成功。研究逻辑、数据边界和 ARGB 暂缓决定继续沿用。
