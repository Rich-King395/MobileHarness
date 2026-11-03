# GitHub 自动检查

入口：[android-ci.yml](../.github/workflows/android-ci.yml)。工作流覆盖开发版构建、现有测试、冻结提示词完整性和敏感文件边界。正式签名继续通过[本机签名工具](publishing/SIGNING_AND_CHANNELS.md)完成。

## 触发与结果

- 推送到 `main`、创建或更新 Pull Request，以及在 Actions 页面手动运行时触发。
- 在仓库的 **Actions** 页面查看各步骤结果；PR 页面显示对应检查状态。
- 首期只保存有限期限的合成单元测试报告，不上传 APK、正式密钥或手机实验记录，不自动创建 Release。

本地验证与 GitHub 执行分别记录。配置文件存在不表示远端已运行成功，首次远端结果以 Actions 中对应提交的运行记录为准。

## 检查范围

| 检查 | 内容 |
| --- | --- |
| 开发版构建 | 使用仓库 Gradle Wrapper 构建 `:app:assembleDebug`，发现编译与依赖问题 |
| 现有测试 | 运行 `:app:testDebugUnitTest` 及 `tools/tests/test_*.py`，复用 Harness、预算、解析、存储和离线工具测试 |
| 提示词完整性 | 核对三个冻结版本的 manifest、引用文件与字节摘要；意外修改、缺失或越界引用导致失败 |
| 敏感文件 | 检查 Git 跟踪文件中的密钥库、私有配置、构建输出、用户数据路径与可识别的凭据模式 |

现有 Android 测试使用 JUnit4、接口注入和合成 fake；应用界面为 Compose。本阶段复用这些基础设施，没有增加 UI、截图或设备测试框架。测试数量随开发变化；既有 242 项 JVM 测试是接入时的基准，不作为未来固定数量门槛。

运行环境为 Ubuntu 24.04、JDK 17、Python 3.12、Android API 36 / Build Tools 36.0.0，以及仓库固定的 Gradle Wrapper 与校验值。构建仍需下载工具链和依赖；测试不调用商业模型服务。

## 本地运行

在项目根目录运行离线 Python 检查和测试：

```text
python -B tools/ci/check_sensitive_files.py
python -B tools/ci/check_prompt_integrity.py
python -B -m unittest discover -s tools/tests -p "test_*.py"
```

Android 检查需要本机已配置 JDK 和 Android SDK。在 `android-app` 目录运行：

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Windows PowerShell 使用：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

CI 不需要模型 API Key、正式密钥或签名密码。本机保留的凭据不应复制到 workflow；普通 PR 检查仅申请读取仓库内容的权限。

Runner 使用临时调试签名，不复用本机原开发密钥。CI 构建的 APK 不作为覆盖原开发包的更新包，首期也不上传该产物。

## 阅读检查结果

- 编译、依赖下载和检查工具错误按具体失败步骤处理，不能归为模型决策错误。
- 敏感检查仅报告定位信息或固定原因码，不打印命中的凭据内容。规则检查当前 checkout 中的跟踪文件，未覆盖完整 Git 历史，也不能证明所有形式的敏感数据均不存在。
- 冻结提示词变更应经过方法设计和版本审查；不要只为消除报错而重算旧版本摘要。
- 自动检查验证已覆盖的软件规则。真实手机的截图时序、无障碍动作、通知、授权和真实 App 兼容性继续按实机验收方案验证；模型判断结束仍与任务独立核验分开记录。

正式签名与分发、分支保护规则及多人维护权限应分别配置，不由本工作流自动变更。
