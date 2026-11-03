# 正式签名与开发版并存方案

状态：**待实施的技术方案**。源码托管按用户授权另行进行；本页方案尚未改动应用身份、生成签名密钥、构建或安装正式包。

## 1. 已核实的现状

| 项目 | 当前状态 |
| --- | --- |
| 应用身份 | `applicationId`、源码 `namespace` 均为 `org.mobileharness.app` |
| 当前版本 | `0.9.0-dev`，`versionCode = 21` |
| 签名 | 当前实验安装使用 debug 签名；尚未配置正式 `signingConfigs` |
| 开发证书 SHA-256 | `bd86ce1e682b3901b2198f65615cabfa90ddfa7bec250fe6b656154e7f60de80`，仅用于核对现有安装身份 |
| 数据 | 上次实机复核保留 18 组事件记录和加密任务附件；本次未访问或更改手机数据 |
| 私有存储 | 模型配置、授权范围、任务附件使用应用私有目录与 Android Keystore；记录位于 `noBackupFilesDir` |
| 备份 | `allowBackup=false`；备份规则同时排除云备份与设备迁移 |
| 调试入口 | 诊断 Activity/Receiver 定义在 `src/debug`，正式 APK 尚待产物级核验 |

依据：[构建配置](../../android-app/app/build.gradle.kts)、[主 Manifest](../../android-app/app/src/main/AndroidManifest.xml)、[调试 Manifest](../../android-app/app/src/debug/AndroidManifest.xml)、[存储实现](../../android-app/app/src/main/java/org/mobileharness/app/storage)。

Android 应用需要签名。常规原地升级需保持应用身份与签名兼容；更换为另一把不兼容的签名密钥，不能当作普通覆盖更新。正式分发不沿用 debug 签名。[Android 官方签名说明](https://developer.android.com/studio/publish/app-signing)

## 2. 推荐的渠道分工

| 渠道 | 应用 ID | 显示名称 | 数据与更新策略 |
| --- | --- | --- | --- |
| 现有开发安装 | **保留** `org.mobileharness.app` | 未来可标为 `MobileHarness（开发版）` | 保留现有 debug 签名、记录、配置与授权；后续兼容更新递增版本号 |
| 新正式安装 | **候选** `org.mobileharness.app.release` | 建议 `MobileHarness（发布版）` | 使用独立长期正式密钥；从全新私有数据空间开始 |

正式版应用 ID 和名称仍需用户最终确认；表中候选尚未写入代码。源码 `namespace` 可继续保持原值，**它与安装用的 `applicationId` 不等同**。

后续可为新开发渠道设计规范的后缀，但不要直接给现有安装添加 `.debug` 后缀：那会产生另一个应用，无法延续当前安装的数据空间。也不通过卸载旧包、清空数据或修改其授权来腾出身份。

两种应用 ID 可以安装并存；这不代表可以同时执行任务。两个无障碍服务都启用时可能出现双悬浮入口或控制冲突。首期使用规则为：**同一时间只启用并使用一个渠道的自动化服务，不同时运行任务**。跨包自动互锁尚未实现，不能视作已有保障。

## 3. 实验记录与新机使用

- 此前核验的 18 组实验记录及后续新增记录继续保留在原开发安装中；本次不访问手机、不迁移、不删改、不重新加密，也不承诺通过系统备份恢复。
- 新正式版具有独立 UID、Keystore 和应用数据，首次使用需自行配置模型与 API Key，并完成其无障碍及授权 App 范围设置。已有开发版的授权不重置，也不自动复制给正式版。
- 现有 CSV 是不含任务原文的分析导出，**不是**完整数据备份，不能恢复加密任务附件或密钥。不能把复制私有加密文件当作可行迁移方案。
- 如以后需要迁移，应另行定义用户主动发起的加密导出、导入与删除边界；本阶段不实现迁移工具。
- 其他手机从空数据安装开始。截图、API Key、个人任务记录和本机授权配置不随 APK 或源码分发。

## 4. 正式密钥管理

正式发布前再创建专用长期签名密钥，与现有开发密钥分开管理：

1. 密钥库及密码存放于仓库之外；密码无源码默认值，不写入普通日志、命令示例或发布附件。
2. 为密钥库制作加密离线备份，分别保存密码及恢复说明；核对证书指纹并确认维护人能够恢复使用。现有开发签名身份也应妥善保留，避免实验机后续兼容更新失效。
3. 首期在受控本地环境签名，发布资料只记录证书公开指纹、APK 校验值和版本。密钥库、密码文件、签名属性文件不进入 Git 历史。
4. CI 首期只做无密钥的检查和测试。以后如引入自动正式发布，单独设置受保护的可信发布任务，通过 GitHub Secrets / Environment 提供密钥材料，仅授权维护人触发；不对普通 PR、外部分支或任意脚本开放签名凭据。
5. 同一正式应用身份持续使用兼容签名，递增 `versionCode`。密钥轮换、丢失处理和 Google Play 签名托管如有需要，另行制定，不能默认已有恢复能力。

参考：[Android 签名与密钥管理](https://developer.android.com/studio/publish/app-signing)、[GitHub Actions Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)。

## 5. 渠道实施前必须处理的兼容点

以下为静态源码审查结果，**本次未修改代码**：

| 检查点 | 已发现情况 | 后续要求 |
| --- | --- | --- |
| 返回主界面 | `HarnessAccessibilityService` 两处通过 `"$packageName.MainActivity"` 拼接类名 | appId 与 namespace 分离后会指向错误类名；改为对真实 `MainActivity` 类的显式引用，再核验通知和悬浮入口 |
| Manifest 组件 | 主 Manifest 使用 `.MainActivity` 等相对组件名 | 检查正式版合并后的 Manifest、类路径和启动组件，不能只修改 Gradle appId 就认定兼容 |
| 自身 App 排除 | App 列表与采集检查使用运行时 `packageName` | 核对双渠道下两个 MobileHarness 是否会出现在彼此可操作范围；发布前明确阻止操作配套渠道的敏感页面，保持密钥页保护 |
| 控制广播 | `ControlReceiver` 为非导出组件；通知使用显式接收器，并带任务与状态 token | 核验两个渠道的通知各自只控制本包任务，过期 token 不生效；当前硬编码 action 不单独构成跨包授权 |
| 广播 action / URI | 现有 action 为 `org.mobileharness.app.INTERNAL_CONTROL`，控制 URI 使用 `mobileharness://control/...` | 若改为按 appId 派生，需同步发送、接收及测试；保留显式组件、不可变 PendingIntent 和 token 检查 |
| Provider authority | 主 Manifest 当前未定义 Provider | 后续如新增 FileProvider 等，使用应用身份派生 authority 并检查冲突，不复用两个渠道的固定 authority |
| 调试工具 | 诊断、ADB 脚本和 fixture 存在开发包名引用 | 仅按用途适配，正式包不引入诊断入口；不全局替换源码包名、提示词或历史记录 |
| 名称与通知 | 应用资源和通知标题当前尚未区分渠道 | 渠道配置时明确中英名称、图标或标识，避免操作者混淆 |

关键源码：[服务与通知](../../android-app/app/src/main/java/org/mobileharness/app/platform/HarnessAccessibilityService.kt)、[控制接收器](../../android-app/app/src/main/java/org/mobileharness/app/platform/ControlReceiver.kt)、[任务入口](../../android-app/app/src/main/java/org/mobileharness/app/MainActivity.kt)。

## 6. 首个正式 APK 的验收条件（待执行）

- **身份与签名：**核对 appId、版本、正式证书 SHA-256；`debuggable=false`，不使用 debug 签名。
- **产物边界：**检查合并 Manifest 和 APK 内容，无 `src/debug` 诊断组件、fixture、实机测试数据、截图、API Key 或签名私钥；保留运行所需提示词与资源。
- **共存：**在保留原开发包的设备上安装正式版，原记录、配置和授权不变；两个启动图标、通知及悬浮入口可以辨认，通知不会控制另一个包。分别验证单服务启用下的运行；不把双服务同时控制手机列为支持场景。
- **升级：**以同一正式证书和递增版本号覆盖正式版，验证其配置、加密记录、Keystore 读取及授权状态；不以“安装成功”替代数据保留检查。
- **新机：**验证首次安装、模型配置、无障碍开通、授权范围、真实任务、一次接管与归还，以及中英界面。Android 13 及以上的侧载限制按系统提示处理，不静默代替用户开启权限。
- **研究一致性：**Harness 状态机、共享预算、Planner 上限、人工期间不采集、截图临时保留、模型结束与任务成功的区分均不因渠道配置变化。
- **发布附件：**仅准备正式 APK、SHA-256 校验值、版本说明与已验证设备范围。源码归档不混入签名材料和本地实验档案；实际上传另行执行。

## 7. 决策与执行顺序

| 状态 | 事项 |
| --- | --- |
| 已核实 | 当前开发身份、版本、debug 签名指纹、存储隔离及两处主界面类名拼接问题 |
| 待最终选择 | 正式应用 ID、渠道显示名称、首个正式版本号、签名密钥维护人与备份位置 |
| 待配置 | 渠道与签名配置、组件身份适配、双渠道页面排除与标识；不改现有实验逻辑 |
| 待验收 | 正式产物核验、原包保留、共存、升级、新机与接管归还实测 |
| 当前不执行 | 生成密钥、修改手机安装、迁移记录、创建仓库、提交或上传 GitHub |
