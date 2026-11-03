# 合成测试目标 App

独立 APK：`org.mobileharness.fixture`，名称 **MobileHarness Test**。入口 Activity 为 `org.mobileharness.fixture.MainActivity`。仅供设备验收；正式用户任务和 MobileHarness 运行均不依赖此模块。

没有网络、存储或其他权限，没有文件、数据库或日志写入。输入、计数和保存状态只在 Activity 内存中存在；重新创建 Activity 会重置。“保存”仅更新屏幕上的状态与快照文字，不代表磁盘持久化。

## 控件 ID 与初始状态

所有完整资源 ID 前缀为 `org.mobileharness.fixture:id/`。

| ID | 内容／行为 |
| --- | --- |
| fixture_title | 固定“专用测试页面” |
| fixture_input | 可见单行 EditText，初始 `initial` |
| fixture_click | 点击一次后增加点击计数 |
| fixture_click_count | 初始“点击次数：0” |
| fixture_long_press | 长按增加长按计数；消费长按事件 |
| fixture_long_press_count | 初始“长按次数：0” |
| fixture_save | 仅将当前输入复制到可见已保存内容标签 |
| fixture_save_state | 初始“保存状态：未保存”；保存后“保存状态：已保存”；再编辑后恢复未保存 |
| fixture_saved_value | 初始“已保存内容：无”；保存后“已保存内容：当前输入” |
| fixture_scroll | 可垂直滑动的静态区域 |
| fixture_row_01 … fixture_row_16 | 每行 60dp，文字“静态测试行 1”…“静态测试行 16” |

计数、输入和保存控件在滚动区域外。Instrumentation 优先通过真实服务的 fixture 限定节点获取可见 bounds，再按主 App 的全屏坐标约定调用 PhonePort；若使用 UiAutomation，只可采用 DONT_SUPPRESS_ACCESSIBILITY_SERVICES，不能抑制被测服务。输入框不会唤起软键盘，测试不读取或显示私人输入联想。不要把 fixture 状态作为传入模型的隐藏评分。

## 重置

向本 Activity 发送显式 Intent，action 为 `org.mobileharness.fixture.action.RESET`，并使用 `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP`，可在已有实例的 `onNewIntent` 中重置输入、计数、保存标签和滚动位置。Intent 不读取附加任务内容，只会修改本测试 App 的合成内存状态。

模块纳入根 settings 后由根工程构建 `:fixture:assembleDebug`。本目录未修改根工程配置。
