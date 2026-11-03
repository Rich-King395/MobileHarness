# 正式签名与开发版并存

本页面向维护者，记录渠道身份、本地签名和恢复方法。普通用户的安装与使用说明见 [README](../../README.md)。

**本轮进度（2026-11-03）：**正式身份已生成，两个渠道已构建并通过 APK 核验，正式版已与原开发版并存安装并启动。原开发版及其数据未变。便携恢复备份尚未创建；正式版模型配置、服务授权及真实任务仍待单独验证。详见 [0.10.0 验收范围](../RELEASE_CHANNELS_0.10.0.md)。

## 1. 渠道身份

| 项目 | 开发版 `debug` | 正式渠道 `release` |
| --- | --- | --- |
| 应用 ID | `org.mobileharness.app`，保留现有身份 | `org.mobileharness.app.release` |
| 版本 | `0.10.0-dev` | `0.10.0-beta.1` |
| `versionCode` | `22` | `22` |
| 英文名称 | `MobileHarness (Dev)` | `MobileHarness (Release)` |
| 中文名称 | `MobileHarness（开发版）` | `MobileHarness（正式版）` |
| 签名 | 保留原开发签名 | 专用长期正式密钥 |
| 配置与记录 | 延续原开发安装的私有数据 | 独立 UID、Keystore 和全新私有数据 |

源码 `namespace` 继续使用 `org.mobileharness.app`。安装身份由 `applicationId` 决定，不能把两个概念一起全局替换。首个正式渠道版本带有 `beta.1`，表示仍需完成发布验收，并非已通过所有设备测试。

原开发证书 SHA-256：

```text
bd86ce1e682b3901b2198f65615cabfa90ddfa7bec250fe6b656154e7f60de80
```

正式证书 SHA-256（公开身份，后续正式 APK 应继续匹配）：

```text
f06391df26dc56a209ffa93a77280c2237c0eb7c3caa27d923771da197e385c6
```

维护开发安装时保持该签名兼容，并递增版本号。不得卸载旧开发包、清空数据、改用正式密钥覆盖开发包，或给开发包新增后缀来代替原地升级。常规更新需要兼容的应用身份和签名。[Android 官方签名说明](https://developer.android.com/studio/publish/app-signing)

## 2. 并存和数据边界

- 两个应用 ID 可以安装并存；同一时间只启用一个渠道的无障碍服务。新代码检查另一渠道的已启用服务，在冲突时阻止开始、归还和自动操作。手机上原开发版尚未覆盖升级，不具备新保护；两渠道不支持同时运行任务。
- 新正式版首次使用需自行配置模型与 API Key，并完成本包的无障碍、截图知情确认和授权 App 范围设置。开发版已有授权保持原状，不自动复制到正式版。
- 两个渠道不能把彼此的 MobileHarness 页面作为目标 App 操作或采集。通知和悬浮入口只控制所属应用的会话。
- 原开发包中的实验记录、加密任务附件和模型配置继续留在原包；本阶段不迁移、不重新加密、不复制其私有文件。
- 应用保持 `allowBackup=false` 和现有备份排除规则。分析 CSV 不能恢复加密任务附件或密钥；不能把它当作完整数据备份。
- 模型 API Key、任务记录、截图和本机授权设置不随 APK 或源码分发。Harness 状态机、共享预算、Planner 上限、人工期间不采集和截图临时保留规则保持不变。

本轮安装前后已复核原开发包：18 条实验记录及其加密任务附件、模型配置目录与授权文件的哈希和计数全部一致，原 APK 字节与版本未变。签名工具自身不访问手机或实验数据。

## 3. 本地正式签名

工具要求 **Windows、PowerShell 7.4 或更新版本、JDK 17**。默认本地路径：

| 文件 | 用途 | 是否可上传 |
| --- | --- | --- |
| `D:\Android\Signing\MobileHarness\mobileharness-release.p12` | PKCS12 正式私钥库 | 否 |
| `D:\Android\Signing\MobileHarness\signing.dpapi.json` | Windows DPAPI 保护的密码与本地路径 | 否 |
| `D:\Android\Signing\MobileHarness\certificate.public.json` | 公开证书指纹等元数据 | 可引用公开字段；不直接发布整套目录 |
| 单独指定的 `*.signing-recovery.json` | 带口令加密的便携恢复包 | 否 |

目录和私密文件限制为当前 Windows 用户与 SYSTEM 访问。工具拒绝仓库内路径、路径中的符号链接/目录联接、盘符根目录和已有签名工件的覆盖；不在项目中生成临时明文密码文件。

`signing.dpapi.json` 绑定当前 Windows 用户的 DPAPI 保护环境。单独复制此文件和 PKCS12 到另一台电脑，不能视为已经具备恢复能力。维护者需要另做便携恢复备份。

### 创建一次正式身份

在项目根目录的本地 PowerShell 终端运行：

```powershell
pwsh -NoProfile -File .\tools\Initialize-ReleaseSigning.ps1
```

默认生成别名 `mobileharness-release`、RSA-4096、`SHA256withRSA`、有效期 100 年的自签名证书，主体为 `CN=MobileHarness Release`。该主体不表示经过第三方出版者身份认证。PKCS12 使用随机密码，密码经 DPAPI 保存；初始化只打印文件位置与公开证书 SHA-256。

可用 `-SigningDirectory` 和 `-JdkHome` 覆盖本地路径。初始化先持久化受保护凭据，再生成密钥；如中断，保留所有工件用于检查。遇到已有文件或部分失败时，先查明状态，**不要删除后重建一把新密钥**。

### 本地构建正式 APK

```powershell
pwsh -NoProfile -File .\tools\Invoke-ReleaseBuild.ps1
```

工具临时提供以下进程环境变量；不要将密码值填入源码、命令行示例、Gradle 属性文件或 GitHub 公开配置：

```text
MOBILEHARNESS_RELEASE_STORE_FILE
MOBILEHARNESS_RELEASE_STORE_PASSWORD
MOBILEHARNESS_RELEASE_KEY_ALIAS
MOBILEHARNESS_RELEASE_KEY_PASSWORD
```

构建通过 Gradle Wrapper 运行 `:app:assembleRelease`，强制 `--no-configuration-cache --no-daemon`，结束后恢复进程环境。可设置 `-SigningConfig`、`-JdkHome`、`-GradleUserHome`；依赖已经缓存时可加 `-Offline`。工具不安装 APK、不上传，也不接触开发签名或实验记录。

不要在签名构建中启用转录、调试级日志或构建扫描。发布资料只记录版本、APK SHA-256 和证书公开指纹。首次正式分发前，核实 APK 使用正式证书，不能仅以“Gradle 构建成功”作为证明。

## 4. 便携恢复与离线保管

在本机交互终端执行导出。目标目录需支持 Windows 私有 ACL（例如 NTFS）；先导出到仓库外的专用空目录，再将加密文件复制到独立离线介质。以下路径可替换：

```powershell
pwsh -NoProfile -File .\tools\Export-ReleaseSigningRecovery.ps1 -OutputFile 'D:\Android\SigningBackups\MobileHarness\mobileharness-release.signing-recovery.json'
```

工具在安全输入提示中要求输入两遍至少 16 个字符的恢复口令。恢复口令不通过脚本参数或聊天提供。恢复包包含 PKCS12、签名密码、别名与证书指纹，整体使用 **PBKDF2-SHA256（600,000 次）与 AES-256-GCM** 加密；密码与私钥不以明文导出。

应将恢复包复制到独立离线介质，并将恢复口令单独保管。离线介质可用 FAT/exFAT，直接由导出工具创建私有目录则需 ACL 支持。只在开发磁盘创建另一份文件，不算完成离线备份。初始化生成的公开元数据中的 `backup_status` 是创建时快照，不会自动证明后来已完成导出、转存或恢复验证。

在另一台电脑或新的隔离目录中恢复：

```powershell
pwsh -NoProfile -File .\tools\Restore-ReleaseSigningRecovery.ps1 -InputFile 'E:\MobileHarness-Signing-Recovery\mobileharness-release.signing-recovery.json' -SigningDirectory 'D:\Android\Signing\MobileHarness-Restored'
```

恢复工具交互读取口令，检查加密认证与证书指纹，使用新 Windows 用户的 DPAPI 重新保护签名密码，拒绝覆盖任何现有签名身份。恢复后的构建通过 `-SigningConfig` 指向恢复目录中的 `signing.dpapi.json`。

首次分发前应在隔离目录验证一次恢复和签名：恢复证书 SHA-256 必须与原证书一致。不要把原正式目录当作恢复试验的覆盖目标，也不要为了试验更换已经发布的正式密钥。

## 5. 渠道兼容检查

| 检查点 | 本轮要求 |
| --- | --- |
| 主界面入口 | 使用实际 `MainActivity` 类的显式引用，适配 appId 与 namespace 分离 |
| 自身与配套渠道页面 | 两个 MobileHarness 包均排除在可操作范围，采集与执行入口维持同样保护 |
| 通知与控制广播 | 保持显式接收器、非导出组件、不可变 PendingIntent 和会话/状态 token，拒绝迟到控制 |
| 合并 Manifest | 核对真实 Activity/Service 类名、安装身份与中英名称 |
| 诊断入口 | `src/debug` 组件只进入开发 APK；正式 APK 不含调试诊断入口或 fixture |
| 密钥页保护 | 渠道变化不放开密钥输入弹层保护，不采集或导出密钥 |
| 两个服务 | 明确只支持一次使用一个渠道，不宣称已提供跨包自动互锁 |

相关源码：[构建配置](../../android-app/app/build.gradle.kts)、[主 Manifest](../../android-app/app/src/main/AndroidManifest.xml)、[服务](../../android-app/app/src/main/java/org/mobileharness/app/platform/HarnessAccessibilityService.kt)、[控制接收器](../../android-app/app/src/main/java/org/mobileharness/app/platform/ControlReceiver.kt)。

## 6. 验证与发布状态

截至本页更新时：

| 项目 | 状态 |
| --- | --- |
| 五个签名工具语法解析 | 已通过 |
| 隔离签名工具集成测试 | 16 项通过；临时测试身份完成真实生成、备份、恢复及拒绝覆盖、篡改检查，测试密钥已清理 |
| 正式签名目录创建与真实密钥初始化 | 已完成，DPAPI 与私有 ACL 保护 |
| 两个 APK 的证书、Manifest、双语名称、内容核验 | 通过；正式包无调试入口，开发证书保持一致，59 份冻结引用保真 |
| 缺少正式凭据的构建 | 明确拒绝，不回退开发签名 |
| 签名密码原文残留核验 | 源码、项目缓存、构建产物目录、构建日志及近期 daemon 日志均未发现；仅在内存匹配 |
| 真实正式身份的便携恢复包与离线转存 | 待用户在本机交互导出并单独保存；隔离工具测试不能代替这份备份 |
| 原开发安装与正式版安装并存 | Pixel 4 / Android 13 已验证，UID 独立、正式版能启动，原安装与文件哈希不变 |
| 正式版服务授权、真实任务与接管归还 | 待单独验证；本轮未复制 API Key 或改变任何服务开关 |
| 正式版兼容覆盖升级、全新手机使用 | 待验收 |
| GitHub Release 与 APK 上传 | 未执行 |

实机验收需保留原开发包的数据，分别核对两个渠道的图标、通知、配置、记录和授权状态；用正式版完成真实任务、一次接管与归还，并核对中英界面。后续版本以同一正式证书、递增 `versionCode` 覆盖正式包，验证加密配置与记录仍可读取。

发布附件只包含正式 APK、SHA-256 校验值、版本说明和已验证设备范围。签名私钥、受保护密码文件、恢复包和本地实验档案不得上传。

CI 首期只执行无密钥的检查与测试。若以后引入自动签名发布，应另建受保护的可信发布任务，按最小权限提供密钥，不能对普通 PR 或任意分支开放签名环境。[GitHub Actions Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)
