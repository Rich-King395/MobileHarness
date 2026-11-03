# 中英文界面：0.6.0

日期：2026-11-02。设备：Pixel 4／Android 13。0.6.0-dev／versionCode 15 已保留数据覆盖安装。

## 使用与实现范围

任务结束后，点击主页面右上角地球图标，选择 **跟随系统／简体中文／English**。默认跟随系统；本轮验证后已恢复用户原来的跟随系统设置，不修改手机全局语言。

任务页、模型配置、记录、数据告知、按钮、运行状态、固定错误提示、悬浮控制及通知文案已资源化。默认英文 245 个键、中文 244 个键，差异为不翻译的品牌名。英文使用简洁标签，例如 Running／Take over／Return／Stop。三组件悬浮布局、状态颜色和暂停屏障保持原设计。

语言仅影响显示。任务原文、用户配置名、第三方 App 名称、模型回复和未知自由文本保持原样；旧配置的中文名称不会被改写。模型提示词资源、动作及坐标约定、固定执行反馈、历史、预算、Planner 上限和最小事件合同均不改变。

Android 13 以上使用系统 `LocaleManager` 保存应用语言，与系统的单应用语言设置一致；Android 11／12 的实现仅在本机私有偏好存一个语言枚举，并通过资源上下文原位刷新，不新增库或改全局 Locale。实现依据 [官方单应用语言说明](https://developer.android.com/guide/topics/resources/app-languages) 和 [LocaleManager API](https://developer.android.com/reference/android/app/LocaleManager)。低版本实际设备尚未验证。

主页面处理 locale／layoutDirection 配置变化，以新的资源包装上下文更新 Compose；保留原 Activity 包装链，让权限／导出 Launcher 仍能找到 ActivityResultRegistryOwner。不会把任务放入 Bundle、SavedStateHandle 或磁盘。运行中、启动中、配置写入中或等待导出时，界面语言选择禁用。服务只原位刷新固定标签、通知渠道和当前通知，不重绑、重置任务或添加模型调用。

## 最终构建与验证

最终 candidate3：APK 34,537,707 字节，SHA-256 `ae6f2643c013c67c72ccd36a5ec005cf7329a1239747ff7dcbdca318e5f59455`，v2 签名验证通过。

| 层级 | 结果与证据 |
| --- | --- |
| JVM | 150 项通过，0 失败／错误／跳过，[构建](results/localization-0.6.0/build.json) |
| 离线资源与版本 | 双语键／格式占位符一致，正常 Main 界面无中文硬编码；8 个核心／模型／存储源文件和 59 个冻结引用哈希保持不变，[记录](results/localization-0.6.0/offline-localization.json) |
| 固定文案 | 65 个检查通过，含全部状态、规划次数、错误码、未知文本原样保留及已存在 RAM 提示的重新本地化，[最终记录](results/localization-0.6.0/device-localization-final-attempt1.json) |
| 悬浮控件 | 13 状态 × 3 字号 × 2 语种，共 78 变体通过；触点、间距、禁用、固定状态与截图隐藏守卫检查通过；不修改真实语言偏好 |
| 正常页面 | 跟随系统→中文→英文；切换前确认合成任务已输入，切换及切页后草稿均保留；英文任务／模型／记录／数据说明正常，原 23 个授权和两份加密配置保留，同意未被自动勾选，[记录](results/localization-0.6.0/ui-final-attempt1.json) |
| 英文生产截图／避让 | 悬浮区域生产图与隐藏参考一致，恢复收起；一次真实 perform 保持原参数与帧身份、控件让开、原生终态确认和控制器恢复，[记录](results/localization-0.6.0/device-capture-en-final-attempt1.json) |
| 英文物理接管 ON／OFF | ADB 实际触摸悬浮入口、接管、屏障、人工、归还和中止均通过；本地模型替身 ON G2/P1、OFF G2/P0，[ON](results/localization-0.6.0/physical-overlay-on-english-final-attempt1.json)／[OFF](results/localization-0.6.0/physical-overlay-off-english-final-attempt1.json) |
| 原语言恢复 | 已通过正常界面恢复跟随系统，系统应用 Locale 列表为 `[]`，[记录](results/localization-0.6.0/ui-final-system-restore.json) |
| 最终状态 | 安装哈希与最终构建一致，IDLE、服务实际绑定、控制器匹配；任务框空、同意未勾选、原授权数量和加密保存标记保留，[记录](results/localization-0.6.0/final-device-state.json) |

本轮远端 GUI／Planner 调用均为 0。人工阶段不读目标画面／轨迹；验证页面和模型为合成内容。字号在隔离控件上下文中测试，没有更改手机全局字号。未覆盖实际低版本设备、完整横屏／键盘布局或 TalkBack，也未重跑全部 37 场景与旧 97 变体。不能将资源翻译或这些有限验证外推为所有终端已验收。

## 失败及候选记录

- candidate1 的独立文案／悬浮检查通过，但正常页面初始化因裸资源上下文丢失 ActivityResultRegistryOwner 而失败。仅从本应用 UID 的崩溃中提取固定原因标记，没有保存原日志。改为保留 Activity 的 ContextWrapper 后修复；[失败原因](results/localization-0.6.0/main-initialization-candidate1.json)、[原构建](results/localization-0.6.0/build-candidate1.json) 和 [未完成的 UI 检查](results/localization-0.6.0/ui-attempt1.json) 保留。
- candidate2 覆盖安装后首次检查处于短暂服务未就绪窗口；稍后实际绑定正常，[记录](results/localization-0.6.0/ui-candidate2-attempt1.json) 保留。没有代用户重开权限。
- candidate2 一次切换后草稿断言未通过，当时缺少“切换前实际写入”前置确认，不能确定丢失发生在哪个环节。[原失败](results/localization-0.6.0/ui-candidate2-attempt2.json) 保留。增加切换前断言后，[同候选复测](results/localization-0.6.0/ui-candidate2-attempt3.json) 与最终包完整的系统／中文／英文往返均通过，没有据此宣称所有配置重建或进程死亡可保留任务。
- 最后补充语言切换失败横幅的固定文案映射与检查，形成最终候选。旧候选的结果不冒称在最终哈希上完成；最终相关回归单独留档。

## 数据边界与预览

截图仍仅按 GUI／Planner 必需观察在内存中使用，无目标截图历史；无人工画面／操作轨迹采集。密钥仍在手机加密保存，未读出、写入代码或导出。语言偏好为单独的公开界面设置，不进入模型历史或最小事件字段。

[双语原生控件合成预览](../previews/localization-0.6.0/native-controls.png) 只由固定状态的自家 View `root.draw(Canvas)` 绘制，不是屏幕截图，不含其他 App 像素；手机临时合成 PNG 已删除。
