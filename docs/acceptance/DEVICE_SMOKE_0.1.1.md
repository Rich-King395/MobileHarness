# Pixel 4 本地能力验收 0.1.1

日期：2026-10-30。当前包：0.1.1-dev，Android 13／API 33。结果：[无内容 JSON 记录](results/pixel4-local-0.1.1.json)。

## 已通过的范围

| ID | 真实设备验证 | 对应场景及限制 |
| --- | --- | --- |
| D-01 | 内存 PNG 尺寸；点击；2 秒长按；可编辑字段完整替换及空串清除；fixture 内存“保存”；滑动 | 真实 PhonePort 基础能力；fixture 保存只更新合成页面，不验证真实 App 磁盘业务持久化。未测试 IME enter |
| D-02 | 模型等待时接管；人工期间观察入口关闭且采集次数不增；Harness 关闭归还取新图；新请求发出后旧回复仍不派发 | CT-01／CT-02、CT-07、HX-05／HX-10 的部分 D 覆盖。没有记录真实人工轨迹；不是开关成对完整信息条件验收 |
| D-03 | 真实已提交手势仍在途时保持 PAUSING；完成回调后才进入 MANUAL；成功动作保留在原任务自身历史 | CT-04 的完成终态分支；不代表已验证真实取消、丢失回调、五秒未确认及改停止的全部变体 |
| D-04 | 最后 wait 用完额度且无 finish，直接按额度结束、不调 P2；最后合法 VERIFY finish 后，P2 在零 GUI 余额直接结束 | BP-09／BP-14 的普通 VERIFY 入口；P2 为可控模型结果，不能证明真实 Planner 对任务状态判断正确 |

四组均 PASS，总耗时 26,281ms。使用真实无障碍服务和 Coordinator，模型与审计为进程内替身。没有请求商业模型、读取本人 API Key、保存截图或采集人工过程；测试帧只存在于内存。

另有 37 个 JVM 测试通过（Coordinator 20、PhoneBridge 2、模型适配 15），以及 4 项设备存储／组件测试通过。存储测试覆盖加密、跨服务密钥隔离、日志中断修复、所选会话导出／单次删除及活动会话限制。数量不等于 37 个验收主场景已全部通过。

## 测试方式与修复

默认 `am instrument` 会在开始／结束时强停目标应用，导致它的 AccessibilityService Binder 失效；`FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` 不能消除这种目标进程重启。本轮首次 instrumentation 尝试中，四项存储测试通过，四项 Live 测试因服务无法绑定而失败，未执行到真实动作；不将这次失败冒充产品功能验证。

已改用 `src/debug` 内的自检入口，在原应用进程中借用真实服务。入口只存在于 debug 构建，并由系统 DUMP 权限保护；发布构建不含它。原 instrumentation Live 测试已标记 Ignore，避免再次使用错误驱动方式。依据：[AOSP Android 13 AMS](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/services/core/java/com/android/server/am/ActivityManagerService.java)、[UiAutomation 标志](https://developer.android.com/reference/android/app/UiAutomation#FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)。

还修正了两项验收夹具问题：Android 13 下 fixture 的跨应用 RESET action 需要匹配其导出的 Intent filter；空 EditText 的无障碍文本可能是 hint，不能误当作实际输入值。没有改动冻结提示词、预算或恢复分支。Intent 行为依据：[Android 13 导出组件限制](https://developer.android.com/about/versions/13/behavior-changes-13#intents)。

测试同时修复了生产桥接层的一处屏障风险：服务断连／更换不能把旧端口仍在途的输入抹掉。现在保留旧端口直到其真实终态，并由两项 JVM 测试覆盖；该断连极端分支没有被本批 D 场景宣称为真实故障注入通过。

## 复现

先确认主应用没有运行中的本人任务，且无障碍由用户手动开启。构建并用 `adb install -r` 覆盖主应用与 fixture，保持数据和授权；不使用卸载、清数据或修改 secure 授权设置。

```powershell
adb shell am start -n org.mobileharness.app/.DebugVerificationActivity
adb exec-out run-as org.mobileharness.app cat no_backup/device-verification.json
```

fixture 必须已安装。只会操作 `org.mobileharness.fixture`；若其他 App 成为活动窗口，立即关闭采集并中止。人工控制阶段不读根节点、画面或轨迹；用户可随时使用悬浮停止入口。首个失败后不自动开始后续用例。

结果文件仅包含版本、相对耗时、用例 ID、状态和无内容错误码。正常清理等待真实输入终态，恢复原控制器并关闭采集；终态未确认时不得伪造 IDLE。验收后已核实授权与绑定仍有效、Crashed services 为空，应用私有目录没有截图文件，并返回 MobileHarness 首页。

下一步为手机端真实模型配置与连通性／任务链路验证。全部通知、锁屏、旋转、异常恢复及 User Study 场景仍按主验收合同继续，不据本批四组结果扩张结论。
