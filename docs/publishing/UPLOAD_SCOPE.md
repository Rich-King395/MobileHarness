# GitHub 上传范围与本地保留

本页最初记录本地整理阶段。现已获用户授权，将源码提交至 [Rich-King395/MobileHarness](https://github.com/Rich-King395/MobileHarness)。保留远程已有的 Apache-2.0 `LICENSE` 和提交历史；按本页范围暂存与推送，不创建 APK Release，不操作手机，不改 APK、签名、包名或权限。

## 文件清单

- [UPLOAD_FILES.txt](UPLOAD_FILES.txt)：当前未被项目忽略规则排除的**拟提交文件**，按相对路径逐项列出。包含下方仍需审阅的材料，不是“已获准公开”或已上传的声明。
- [REVIEW_FILES.txt](REVIEW_FILES.txt)：首次公开前需复核的历史材料、本机路径引用或其他提示；只列路径和类别，不输出可能的敏感内容。
- [upload-inventory.json](upload-inventory.json)：机器可读分类、文件摘要、忽略规则检查和冻结引用完整性结果。生成文件本身不计算自引用摘要。

更新清单：`python -B tools/prepare_github_inventory.py`。该工具只在系统临时目录使用空 Git 仓库检验真实忽略规则；项目目录不会新增 `.git`，工具不调用网络或手机。

清单 JSON 的 `uploaded=false` 和 `project_git_initialized_by_tool=false` 描述的是清单工具自身的行为，不代表项目整体没有远程提交。实际同步状态以 Git 历史和远程分支为准。`.gitattributes` 保留文件原始换行与字节；Gradle Wrapper 的执行权限在 Git 索引中单独保留。

## 建议提交

| 范围 | 用途与处理 |
| --- | --- |
| 根双语 README、`LICENSE`、`.gitignore`、`.gitattributes` | APK 功能、用户使用、Apache-2.0 许可与本地文件边界 |
| `android-app/` 的源码、资源、合成测试、工程文件与 Wrapper | 保留 Wrapper JAR；debug 源码是开发诊断，不等于会进入正式 APK |
| `prompts/`、`docs/data-contracts/`、`docs/runtime-policies/` | 版本化方法与合同，不为上传而改写冻结内容 |
| `HandoffHarness_APK_Handoff/` 及相关规范文档 | 先复核公开权属／内容；两份 Markdown 被冻结 manifest 引用，需原样保留才能完整进行离线溯源校验 |
| `tools/` 与必要说明 | 本地分析、合成验证和维护工具；本机路径与历史设备工具先按复核清单检查，不宣称已适配 CI |
| `docs/publishing/` 与开发／方法文档 | 发布方案及方法解释；项目许可沿用远程已有 Apache-2.0 |

`HandoffHarness_APK_Handoff/` 手稿不是 APK 的运行依赖，但属于部分离线摘要验证的输入。不能简单删掉它们、修改旧 manifest 或忽略失败后声称溯源完整。

## 默认不提交，原件继续保留

| 路径或类型 | 原因 |
| --- | --- |
| `baselines/` | 原始基线、调试 APK、源码 ZIP 与证据归档；保持本地校验值，不删改 |
| `docs/acceptance/results/` | 原始验收元数据、设备／机器线索及会话摘要，不作为公开样例直接提交 |
| `docs/previews/` | 本地设计与图片；需要公开的素材应逐份审阅后另行准备 |
| `docs/local-archive/` | 本次保留的原开发 README 等本地原件 |
| `.agents/`、`.codex/` | 本机开发助手与第三方 skills，不是 APK 运行依赖；本地许可保留，日后分发时须保留各自许可及来源 |
| 缓存、IDE 状态、`local.properties`、构建产物 | 机器相关或可生成文件；APK 日后作为 Release 附件分发 |
| 签名文件、私钥、环境凭据、个人导出及设备私有目录副本 | 不进入源码仓库或公开附件；模型 Key 也不随安装包分发 |

`.gitignore` 不会删除本地文件，也不能撤回已经进入 Git 历史的文件；因此仍须在首次提交前复核暂存内容。[Git 官方说明](https://git-scm.com/docs/gitignore)

## 公开范围与复现边界

- 历史文档可能仍链接到被排除的基线／验收原件。它们记录的是本地已完成的验证，公开克隆不会包含全部原始证据；不能据此宣称任意机器都可直接复跑全部实机试验。
- 冻结文件中的本机路径按历史快照保留；路径引用本身不等于密钥泄露。工具只提供检查提示，不自动修改文件或打印匹配内容。
- 候选文本扫描不覆盖被排除的本地档案，不承诺发现所有形式的秘密。上传前仍需复核实际暂存文件；不直接把整个项目打包上传。
- 项目许可证沿用用户创建远程仓库时已有的 Apache-2.0；第三方材料保留各自的许可及必要声明，不因根许可证而被重新授权。

## 本次提交前的内容复核

已对拟提交范围进行针对性内容检查：测试中的凭据样式文本属于合成哨兵；路径提示属于开发环境或历史说明，未发现需要阻止本次源码提交的实际 API Key、设备序列号、个人邮箱或原始用户操作轨迹。冻结手稿保留原样，原始实机结果不提交。`REVIEW_FILES.txt` 保留这些分类提示，供后续维护复核；提示项不等于检测到秘密，清单工具也不代替完整许可或安全审计。

## 正式发布前的未实施事项

1. 源码提交目标已确认为上述仓库，并保留其 Apache-2.0 许可；提交后以远程分支核对实际源码版本。
2. 按[签名及并存方案](SIGNING_AND_CHANNELS.md)确认正式版应用 ID，并实施独立长期签名及渠道适配；保留当前手机开发安装及实验记录。
3. 整理干净环境构建与 CI，验证签名 Release APK、首装和升级，再准备 Release 附件及用户安装说明。

本页和清单是上传准备成果；不表示正式签名、独立渠道、CI、安装迁移或公开 APK 已经完成。
