# 首个公开 Beta：v0.10.0-beta.1

发布日期：2026-11-03。发布页：[GitHub Release](https://github.com/Rich-King395/MobileHarness/releases/tag/v0.10.0-beta.1)。本次为正式签名的 **Pre-release**，保留其他手机试用和正式版完整模型流程的待验证范围。

## 发布产物

| 项目 | 值 |
| --- | --- |
| 版本／版本码 | `0.10.0-beta.1 / 22` |
| 应用 ID | `org.mobileharness.app.release` |
| APK | `MobileHarness-0.10.0-beta.1-release.apk`，24,588,138 字节 |
| APK SHA-256 | `725ebd04a2f2b200b351af0f382d701ae7d9d884a61d10158a3ae5f9ca14719f` |
| 正式证书 SHA-256 | `f06391df26dc56a209ffa93a77280c2237c0eb7c3caa27d923771da197e385c6` |
| 源码 tag | `v0.10.0-beta.1` |
| 实际构建提交 | `4d65eaaf114c5f42752ce7dd97e2486ec610b06d` |
| Android | 最低 API 30（Android 11），target API 36 |
| APK 原生库架构 | arm64-v8a、armeabi-v7a、x86、x86_64；不表示这些设备均已实测 |

三个手动上传的公开附件为 APK、`SHA256SUMS.txt` 和 `RELEASE-METADATA.json`。校验清单覆盖 APK 与元数据，元数据只写版本、源码身份、公开证书、摘要和明确的验收范围。GitHub 自动提供的源码归档由同一 tag 生成。

## 本轮验证

- 从干净源码提交清理生成目录，重建开发包并通过 24 个套件、242 项 JVM 测试（无失败、错误或跳过）。
- 70 项 Python 测试通过，包括 15 项发布打包工具正反向检查；[源码 CI](https://github.com/Rich-King395/MobileHarness/actions/runs/41624564648)两项任务通过。
- 使用原正式身份在本机签名；两个 APK 独立核验通过：版本、证书、双语名称、入口／组件隔离和 59 份冻结提示词引用。
- 正式 APK 非 debuggable，无调试或 fixture 代码；签名密码原文残留检查通过。
- Release 发布后，不使用 GitHub 凭据回下载三个附件，确认均可公开访问、字节与本地相同，校验清单正确，tag 解析到记录的源码提交。
- 本轮未操作手机安装、无障碍开关或模型配置，未采集手机画面、迁移实验记录或调用远端模型。

AGP 的 APK 内部 VCS 记录没有有效提交，因此源码身份由干净构建过程和公开元数据记录，不宣称 APK 内含提交证明或已达到逐字节可复现构建。

## 验收边界与维护待办

同版本较早的正式 APK 已在 Pixel 4 / Android 13 验证安装、启动和并存；本次重新构建后的 APK 摘要不同。元数据把旧构建证据与本包分开，未将旧包的实机结果视为新包已测试。正式包完整模型流程、覆盖升级和其他手机继续待验；开发版用户报告的任务／接管成功保持原有证据属性。

真实正式密钥的便携加密备份已提供[本机操作步骤](publishing/SIGNING_AND_CHANNELS.md)，用户尚未确认导出与独立离线保存完成。备份文件、恢复口令、私钥、DPAPI 配置和本机实验资料均未进入 Release 或 Git。

本地过程证据在 `docs/acceptance/results/first-release-v0.10.0-beta.1/`，不上传：干净构建日志、SDK 与签名检查、CI 状态、上传回执和公开下载核验。此前渠道验收保留在[原记录](RELEASE_CHANNELS_0.10.0.md)，未用新摘要覆盖旧产物证据。
