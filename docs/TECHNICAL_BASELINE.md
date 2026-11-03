# 首期技术路线与构建基线

确定日期：2026-10-30。

本记录确定独立 Android APK 的技术路线和构建版本。制定基线时尚未创建应用工程；后续已进入正式开发，首个 APK 与验证进度见 [开发记录](DEVELOPMENT_STATUS.md)。交接材料中的英文提示词快照保持原样。

## 1. 技术路线

| 部分 | 首期选择 | 理由与边界 |
| --- | --- | --- |
| 平台与语言 | 原生 Android、Kotlin | 系统服务、权限和手机执行能力是核心；首期以 Pixel 4 / Android 13 验证 |
| 应用界面 | Jetpack Compose + Material 3 | 用于任务输入、模型配置和运行状态界面 |
| 运行协调 | 手机本地的 Kotlin 状态机，使用 Coroutines / Flow | 控制权、阶段历史、预算与模型请求归属由本地统一管理；核心规则与 Android 执行接口分离 |
| 手机执行与观察 | AccessibilityService | 优先验证系统截图、手势和可编辑节点文本输入；具体动作契约与能力验收另行确定 |
| 截图备选 | MediaProjection | 仅在后续能力验证表明有需要时评估，授权和生命周期单独处理 |
| 模型推理 | 手机通过 HTTPS 直接访问远端服务 | GUI Agent 与 Planner 的服务地址、模型名、Key 和会话分别管理 |
| 网络接入 | 独立模型适配层，采用 OkHttp 接入兼容 Chat Completions 的接口 | 明确请求超时、取消和实际尝试次数；显式控制传输层重试，避免网络库生成未记账的额外模型请求 |
| 密钥保护 | Android Keystore 保护本地凭据 | Key 不进入源码、普通日志、截图或导出文件 |
| 本地数据 | 截图及会话正文仅内存；Key 密文及最小元数据在私有不备份目录 | 依照 [数据边界](DATA_AND_LOGGING_BOUNDARIES.md)，不保存截图历史，人工期间不采集画面或轨迹 |
| 初始工程组织 | 单个 app 模块，按界面、运行协调、模型、手机执行和数据职责划分包 | 运行协调逻辑通过接口调用模型与手机能力，便于使用可控响应验收 |

ADB 和 Android CLI 用于开发、安装及调试。日常任务中的观察、动作派发和接管协调在手机内完成。

Qwen3.8-Max 为首期默认模型；当前模型适配与提示词见 [1.1.0 版本约定](MODEL_ADAPTER_AND_PROMPT_VERSIONS_v1.1.0.md) 和 prompts/v1.1.0。1.0.0 规格和提示词包作为历史快照保留。它采用原生 tool_calls 封装和本项目手机工具，不将通用工具调用能力称为供应商既有的专用手机协议。端点兼容性和真实执行效果仍待最小 APK 验收。

## 2. 固定构建版本

| 项目 | 固定值 |
| --- | --- |
| Android Studio | Rabbit 2 / 2026.2.2，构建 AI-262.10968.63.2622.16730706 |
| IDE 运行时 | Studio 自带 JBR 25.0.4 |
| Android Gradle Plugin | 9.5.0，正式版 |
| Gradle | 9.6.0，后续通过 Gradle Wrapper 固定 |
| 构建 JDK | Eclipse Temurin 17.0.20.1+1 |
| Java source / targetCompatibility | 17 |
| Kotlin JVM target | 17，与 Java 目标保持一致 |
| Kotlin 编译器 | AGP 9.5.0 内置 Kotlin / KGP 2.2.10 |
| Compose Compiler 插件 | org.jetbrains.kotlin.plugin.compose 2.2.10 |
| Compose BOM | 2026.06.01 |
| Compose UI / Runtime / Foundation | 1.11.4，由 BOM 管理 |
| Material 3 | 1.4.0，由 BOM 管理 |
| compileSdk | 36 |
| targetSdk | 36 |
| minSdk | 30 |
| SDK Platform | android-36，revision 2 |
| SDK Build Tools | 36.0.0 |

选择说明：

- AGP 9.5.0 的官方兼容表给出 Gradle 9.6.0、JDK 17、Build Tools 36.0.0。当前安装的 Studio Rabbit 2 覆盖 AGP 9.5。
- AGP 9.5 最高支持 API 37；这表示兼容上限。本轮实际确认并安装了正式 SDK Platform 36，因此首期固定 compileSdk / targetSdk 36。
- minSdk 30 对应优先截图路径的 API 下限。Pixel 4 当前为 API 33；安装下限不等于已经验收其他手机和系统版本。
- compileSdk / targetSdk 36 不要求 Pixel 4 升级到 Android 16。较新系统 API 的实际调用必须进行版本判断。
- AGP 9 已默认启用内置 Kotlin；应用模块不重复应用 org.jetbrains.kotlin.android。
- Compose Compiler 必须与实际 Kotlin 编译器同版。Compose BOM 管理 Compose 库，不管理 Compiler 版本。
- Compose BOM 固定为 2026.06.01：实际读取 UI / Foundation / Runtime 1.11.4 和 Material3 1.4.0 的 Android AAR，确认其 minCompileSdk 分别为 35 / 35 / 34 / 35，SDK Extension 均为 0，可搭配 SDK 36。较新的 BOM 2026.09.00 中 UI / Foundation 1.12.1 要求 compileSdk 37，因此本基线不采用。
- Gradle --version 显示的 Kotlin 2.3.21 是 Gradle 自身的嵌入版本，不用于替代本表中的应用编译器 2.2.10。
- 其余应用依赖在工程创建时选择兼容的正式版本，检查 Kotlin 与 AAR 的最低编译要求并写入版本目录；本轮没有提前加入依赖或锁文件。

## 3. 本机工具位置

| 用途 | 路径 |
| --- | --- |
| Android Studio | D:\Android\android-studio |
| IDE JBR | D:\Android\android-studio\jbr |
| 构建 JDK | D:\Android\Jdks\jdk-17.0.20.1+1 |
| Android SDK | D:\Android\Sdk |
| 已准备的 Gradle 发行包 | D:\Android\Gradle\gradle-9.6.0 |
| 本项目 Gradle 用户缓存约定 | D:\Android\GradleUserHome\MobileHarness |
| Android CLI 入口 | D:\Android\Sdk\cmdline-tools\latest\bin\android.exe |
| IDE 配置与缓存 | D:\Android\StudioData\AndroidStudio2026.2.2 |

后续工程配置规则：

1. Android Studio 的 Gradle JDK 明确选择上述 Temurin 17；IDE 自身继续使用 JBR 25。
2. 命令行构建在当前进程或项目专用启动环境中设置 JAVA_HOME 和 GRADLE_USER_HOME；不依赖当前系统 JAVA_HOME 指向的 JDK 8。
3. 工程使用 Gradle Wrapper 9.6.0，并设置发行包 SHA-256。已解压的 Gradle 只用于本次环境验证和后续 Wrapper 准备，不加入全局 PATH。
4. SDK 的机器路径在后续本机配置中指定，密钥不写入构建文件。
5. 使用正式版本和明确版本号；构建仓库使用 Google Maven、Maven Central 及必要的官方插件解析入口。

## 4. 本轮安装与验证

已完成：

- 从 Adoptium 官方元数据指定的发行地址下载 Temurin 17，校验 SHA-256，验证 java / javac 17.0.20.1 可运行。
- 从 Gradle 官方地址下载 9.6.0，校验 SHA-256；在上述 JDK 17、D 盘用户缓存下成功执行 --version。
- 通过 Android CLI 安装 SDK Platform 36 revision 2 和 Build Tools 36.0.0。
- 核对 SDK 安装清单、android.jar、平台及 Build Tools 的 source.properties。
- 验证 aapt2 和 apksigner 可启动。
- 从官方 AAR 中核对所选四项 Compose Android 库的最低编译 SDK、SDK Extension 及 AGP 要求；均在本基线范围内。
- 保留已有 Platform Tools 37.0.0；未改动用户级或机器级 JAVA_HOME。

校验值：

| 文件 | SHA-256 |
| --- | --- |
| OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip | e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0 |
| gradle-9.6.0-bin.zip | bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01 |
| Gradle 9.6.0 Wrapper JAR（官方发布值，供创建 Wrapper 时核对） | 497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7 |

验证边界：

- 以上是安装、工具启动、依赖声明及所列四项 AAR 编译要求层面的验证；完整依赖图留待首次工程同步确认。
- 尚未创建 Android 工程，未进行 Gradle Sync、Compose 编译、APK 签名或手机安装验收。
- 第一个最小工程需核对最终解析的 Kotlin / Compose 版本，并编译和安装到 Pixel 4。
- 无障碍截图、坐标、文本输入、手势、浮层、后台生命周期与接管竞态在最小 APK 阶段验证。
- 尚未向用户的模型服务发送请求或手机截图。个人 Token Plan 对自建交互式 APK 及后续多人研究的适用范围仍作为服务选型开放项。

## 5. 官方依据

- [AGP 9.5.0 发布与兼容表](https://developer.android.com/build/releases/agp-9-5-0-release-notes?hl=en)
- [Android Studio 与 AGP 兼容关系](https://developer.android.com/studio/releases#android_gradle_plugin_and_android_studio_compatibility)
- [Android 构建中的 JDK 与字节码版本](https://developer.android.com/build/jdks)
- [AGP 内置 Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)
- [AGP 9.5.0 实际发布 POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.5.0/gradle-9.5.0.pom)
- [Kotlin Compose Compiler 配置](https://kotlinlang.org/docs/compose-compiler-migration-guide.html)
- [Compose BOM 发布元数据](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/maven-metadata.xml)
- [Compose BOM 2026.06.01 POM](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2026.06.01/compose-bom-2026.06.01.pom)
- [Compose UI Android 1.11.4 AAR](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.11.4/ui-android-1.11.4.aar)
- [Compose Foundation Android 1.11.4 AAR](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-android/1.11.4/foundation-android-1.11.4.aar)
- [Compose Runtime Android 1.11.4 AAR](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-android/1.11.4/runtime-android-1.11.4.aar)
- [Compose Material3 Android 1.4.0 AAR](https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3-android/1.4.0/material3-android-1.4.0.aar)
- [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html)
- [OkHttp 维护仓库（原 square/okhttp 已重定向）](https://github.com/lysine-dev/okhttp)
- [Gradle Java 兼容表](https://docs.gradle.org/current/userguide/compatibility.html)
- [Gradle 正式版本元数据](https://services.gradle.org/versions/all)
- [Gradle 9.6.0 发行包校验值](https://services.gradle.org/distributions/gradle-9.6.0-bin.zip.sha256)
- [Adoptium JDK 17 Windows x64 元数据](https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&heap_size=normal&image_type=jdk&jvm_impl=hotspot&os=windows&vendor=eclipse)
- [AccessibilityService 系统接口](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
