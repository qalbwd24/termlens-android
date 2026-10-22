# TermLens Android 划词测试版 0.5.1

## 0.5.1 音频预取修复（2026-10-16）

- 创建查询后立即预取原文音频，与模型请求并行；完整解释一到就独立预取解释音频，打开历史记录也会补齐缺失音频。
- 播放使用独立执行器；网络合成不再占用音频文件全局锁，已缓存原文不会被较慢的解释合成阻塞。
- 同一内容的并发预取/播放合并为一次合成。新查询和进程重启后可按 Provider/Voice/Rate/文本指纹复用其他历史记录的持久音频，并写回当前查询的兼容清单。
- 命中缓存直接播放，不再每次先显示“正在准备朗读”；等待超过 700 ms 才提示音频正在生成。新增点击到缓存就绪/开始播放计时。
- 保留当前桌面端音频清单格式、15 天规则与收藏永久音频；声音或语速变化会重新生成匹配音频。

最终构建和本轮回归记录：`D:\Termlens\outputs\android-audio-prefetch-2026-10-16\final\`。临时文件：`D:\Termlens\tmp\android-audio-prefetch-2026-10-16\`。首次遇到的新文本仍须联网合成；缓存不能预先拥有尚未生成的解释。

## 0.5.0 当前更新（2026-10-16）

- 首页、历史、设置重新分组，模型/语音/场景设置折叠；历史搜索与筛选分开，完整备份收进“备份”菜单。
- 模型回答流式展示；新增首段/完成/绘制耗时诊断，二级查词保留根原文和父层语境。
- 手机内置 Edge TTS 接口，独立合成、播放和缓存，不再依赖 TTS Server 或电脑。原文、解释、永久收藏音频分开储存。
- 查询、收藏、选词账本、音频清单按当前电脑端格式保存；支持 v2 JSON 与含音频 ZIP 导入/导出。普通解释/音频 15 天，选词/收藏长期保存；暂无服务器同步。
- 加入与电脑端一致的阅读场景库和每次查询的方案快照，预留来源路由与语音 Provider 扩展点。

当前行为、数据契约、启动基准、后续扩展和构建入口请先读 [START-HERE.md](START-HERE.md)。最终构建在 `D:\Termlens\outputs\android-latency-2026-10-16\verified\`，本轮临时文件在 `D:\Termlens\tmp\android-latency-2026-10-16\`。以下版本记录保留供追溯，不代表当前能力。

## 0.4.1 当前更新（2026-10-16）

- 悬浮按钮默认可长按拖动，松手自动记住位置；短按仍直接 Explain。快速滑过或长按拖动均不会触发翻译。
- 设置 → 交互 → 锁定位置：开启后禁止拖动和自动避让；关闭后可以重新长按移动。左下角、右下角可一键归位，自定义位置可恢复最后一次拖动位置。
- “键盘弹出时隐藏按钮”默认开启，收起键盘后恢复原位置；不读取键盘输入内容。
- 结果页收紧留白，原文与朗读按钮同排；关闭按钮及底部操作栏固定在屏幕内，正文独立滚动。
- 在译文或解释中选词，点底部“解释选中”直接进入二级解释；请求传入新选词、根原文及当前父层结果。返回时恢复父层缓存和阅读位置。
- 主、二级提示词及请求规则取自本机正在使用的电脑端“编程与技术阅读”方案。只同步提示词和规则，不导出密钥；手机旧提示词在本机偏好中留有迁移备份。后续电脑端修改不会自动同步，需要重新导入或更新资源。
- 本次真机通过 Chrome 已登录 X 帖子、Edge 网页的“划词 → 一次点击悬浮按钮 → Explain → 返回原页面”。AnkiDroid、阅读、Via 的上一版验证记录保留在下方；未把包过滤名单当作全部兼容的证明。

当前 APK、提示词快照及验证报告：`D:\Termlens\outputs\android-compact-2026-10-16\`；最新 APK 在 `movable\`。源码备份、构建中间文件、ADB 截图/XML 位于 `D:\Termlens\tmp\android-compact-2026-10-16\`。

下面保留 0.3.3 的路线记录及更早历史。

## 当前路线（2026-10-16）

长按选中文字 → 点固定下角的 ✦ → Explain → 关闭后返回原页面。

- 按钮默认常驻、半透明。设置支持左下角、右下角、自动避让；高度固定，不跟着单词上下移动。
- 需要开启 TermLens 划词按钮无障碍服务。可另在荣耀设置中授权“在其他应用上层显示”；授权后使用普通应用悬浮窗，未授权时使用无障碍悬浮层。
- 常驻模式只订阅窗口变化，点击按钮时才查询当前源 App 的原生选区/文字处理菜单。不会持续扫描正文。
- 如果源 App 不提供可读取选区，按钮会通过无障碍动作调用该 App 的 Explain 项；必要时自动展开、滚动源选词菜单。用户只点一次按钮，但部分系统菜单会短暂展开。
- 不截图、不做 OCR、不自动复制原文，不通过剪贴板猜测上一次选中文字。
- 没有选区时提示先划词；不使用预设词条代替原文。模型密钥与已有提示词保留。
- 按钮在系统权限设置和自身结果页隐藏，离开后恢复。自动避让依赖源 App 暴露的选区手柄或菜单位置，不能保证避开所有正文。

真机验证记录与本次 APK 位于 `D:\Termlens\outputs\android-process-text-2026-10-16\`；构建、调试和修改前源码副本位于 `D:\Termlens\tmp\android-process-text-2026-10-16\`。
已验证 AnkiDroid 普通卡片、阅读 3.23.110211 和 Via 的直接按钮流程；不推断所有浏览器、所有卡片模板或 App 都兼容。
旧 `experiments/ankidroid-termlens` 测试牌组会自行拦截原生选词，仍属于另一条卡片模板路线，本次未改动或删除。

以下内容保留 0.3.0 及更早版本的历史记录，不能代替上面的当前行为说明。

## 0.3.0 历史说明

目标设备：荣耀 Magic7，MagicOS 10 / Android 16。

这是独立的原生 Android APK，不修改 AnkiDroid，也不是只能适配 Anki 的插件。它通过 Android 无障碍服务监听可访问的选区，并在当前 App 上方打开 TermLens 全局结果页。

0.3.0 新增：选区临时 Explain 按钮、可选常驻按钮、全屏全局结果页、两层提示词、Markdown 基础渲染、朗读/收藏/复制图标、设置页和提示词 JSON 导入导出。包过滤包括 AnkiDroid、Via、Chrome、Edge、X、YouTube 和 ChatGPT；是否能取得选区仍取决于源 App 是否暴露原生可访问文本。

Manifest 只申请 INTERNET；不申请截图或常规悬浮窗权限。无障碍服务仍需手动授权。本次真机调试通过 USB ADB 开启服务。未配置 API 密钥时，结果页会提示进入设置；配置 OpenAI 兼容接口后才会发送查询。

使用独立包名 `cn.weilin.termlens.probe`；Windows TermLens、AnkiDroid 和现有数据目录未改动。

## 安装与测试

### 0.2.3 三个目标 App 的兼容性探测（2026-10-03）

APK 为 `APK/TermLens-selection-probe-0.2.3.apk`，已覆盖安装至同一荣耀手机。
监听包包括 TermLens、Via、X、YouTube、ChatGPT；服务仍由用户开启，已有其他无障碍服务保留。
读取标记为非重要的子节点；收到目标 App 页面事件后，延迟扫描可见节点的有效选区。
扫描最多 300 节点、24 层，不记录正文；ADB 诊断仅返回结构、文字长度和选区索引。

实测：X 帖子详情可以选词，但服务没有获得正文选区；ChatGPT 回答可以选词，自动浮钮未出现，
「更多 → Explain」准确传入 `conversely`（10 字符），关闭后继续原回答阅读。
YouTube 标题、简介、评论长按未出现原生选区，也未出现 Explain 浮钮。
内置正文回归通过：独立按钮打开弹窗，收到 `limitation can have a significant impact.`（41 字符），返回首页。
尚未增加 OCR、剪贴板读取或 LLM；OCR 备用交互需先确认用户是否接受。

### 0.2.0 悬浮按钮技术验证（2026-10-03）

APK 为 `APK/TermLens-selection-probe-0.2.0.apk`，已通过 ADB 安装至荣耀手机。
首页「测试选词悬浮按钮」进入测试页，在设置手动开启「TermLens 划词按钮测试」。
技术栈为原生 Java / AccessibilityService / TYPE_ACCESSIBILITY_OVERLAY / 浮动 Activity。
不需要常规 SYSTEM_ALERT_WINDOW 权限，但必须手动开启无障碍服务。

真机结果：内置正文选中 limitation 后，独立 Explain 按钮出现；点击收到准确的 10 字符原文，返回原测试页。
Via 网页选中 documentation 时收到事件，但源 WebView 文字为空，选区索引为 -1，事件文本为空。
子节点也没有找到有效选区，设备没有 SDK 36.1 的公开 getSelection() 接口，因此该网页未出现按钮。
这证明悬浮层可用，不证明全局自动取词可用。详细记录见 `OVERLAY-TEST-20261003.md`。

本版按钮先放在文字节点下方或屏幕右下侧，没有实现精确跟随单词位置。
取消选区、稍后滚动、离开测试应用、熄屏或超时 12 秒时隐藏。
测试页「停止划词按钮服务」可关闭服务，已有的系统「更多 → Explain」入口保留。

### 0.1.2 真机修复（2026-10-03）

已通过 USB ADB 在荣耀 Magic7（Android 16 / API 36）安装验证。
此前内置选词菜单没有第三方入口的根因：程序创建的 TextView / EditText 没有 View ID，
手机框架的 `TextView.canProcessText()` 会在 ID 为 `NO_ID` 时返回 false。
现已给测试控件分配 ID；没有手工向菜单插入 Explain，也没有新增权限。

本机实测内置正文：选中 `limitation` → 更多中找到 Explain → 弹窗原文为 `limitation`
→ 系统返回回到 MainActivity。APK 为 `APK/TermLens-selection-probe-0.1.2.apk`，已覆盖安装。
详细验证范围见 `DEVICE-TEST-20261003.md`；下文 0.1.1 部分保留为历史诊断记录。

全局入口位置由源 App / 系统决定。提高 Manifest 优先级不能强制放到第一排。
独立悬浮按钮的研究结论和开源参考见 `SELECTION-ENTRY-RESEARCH-20261003.md`。

### 0.1.1 诊断更新

0.1.0 的手机反馈：示例弹窗正常，但内部与外部选词菜单（包括「更多」）都没有 Explain。
弹窗已获用户反馈通过；系统菜单发现入口仍失败，根因未确定。

安装 `APK/TermLens-selection-probe-0.1.1.apk` 可直接覆盖更新，包名和签名保持一致。
打开首页的「检查 Explain 入口」：

1. 查看「系统查询能找到 Explain」是／否，以及导出、启用、权限状态。
2. 「测试系统调用入口」使用系统的文字处理选择器调用，不显式指定本包。
3. 分别在普通正文、可编辑输入框长按选词，查看页面下方观察到的菜单项目。

只观察原生菜单，未手动添加 Explain；内部菜单成功也不能当作其他 App 已兼容。
新增 `<queries>` 仅允许本诊断页发现文字处理入口，无权限弹框，不能改变其他 App 的包可见性。
诊断信息只在当前页面显示，无文件日志、上传或永久保存。
原来的 0.1.0 APK 保留，用于对照。

### 原始测试流程

1. 将 `APK/TermLens-selection-probe-0.1.0.apk` 发送到手机，点开安装。
2. 打开桌面的 **TermLens Test**。先长按示例英文，找菜单或「更多」中的 **Explain**。
3. 点击 **Explain**：应打开底部卡片，背后仍能看到原页面；「选中的原文」应与选区完全一致。
4. 点击右上角关闭按钮、系统返回，或弹窗外部，检查能否继续原位置阅读。
5. 去浏览器、外刊 App、阅读器或 PDF 中重复步骤 2—4。分别测试一个单词、一句话、较长段落。
6. 也在一个可编辑的文字输入框里测试：关闭弹窗后，原文字应保持不变。

首页的「打开示例弹窗」只证明本 App 能打开自己的弹窗，不能证明外部 App 的选词入口可用。
本版的「测试结果」是固定文字，尚未接入 LLM。

反馈只需记录：App 名称；Explain 是否出现（直接可见／更多里／没有）；原文是否准确；弹窗是否正常；关闭后是否回到原位置。

| 场景 | Explain 入口 | 原文准确 | 弹窗正常 | 返回原位置 |
|---|---|---|---|---|
| 内置测试文字（0.1.2） | 更多中实测出现 | limitation 一致 | 实测正常 | 系统返回到首页 |
| 浏览器中的外刊正文 | 待手机验证 | 待手机验证 | 待手机验证 | 待手机验证 |
| 外刊 App 正文 | 待手机验证 | 待手机验证 | 待手机验证 | 待手机验证 |
| 阅读器 / PDF | 待手机验证 | 待手机验证 | 待手机验证 | 待手机验证 |
| 可编辑输入框 | 待手机验证 | 待手机验证 | 待手机验证 | 待手机验证 |

部分 App 会使用自定义选词菜单、不提供文字处理接口，或将第三方入口隐藏在「更多」里。
入口名称和位置由源 App / MagicOS 决定；注册 `PROCESS_TEXT` 不代表全部 App 已兼容。
手机上的安装、显示、选择与返回行为仍需实测，不以本地编译结果代替。

## 构建

```powershell
Set-Location -LiteralPath 'D:\Termlens\android-probe'
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
```

复用本机 `D:\codex-tools\android-build` 的 JDK 17、SDK Platform 35 和 Build Tools 35。
编译使用 API 35 的平台类；Manifest 的目标 API 是 36（Android 16），最低 API 是 23。
不需要安装 Android Studio，不下载 Gradle 或第三方依赖。

`build.ps1` 支持 `-ToolRoot` 和 `-Python` 参数。
构建目录为本项目的 `build/work/<唯一标识>`，每次生成独立目录，不删除或覆盖其他项目。
`signing/` 保存本测试包的签名材料；签名密码使用本机 Windows DPAPI 保护。
请保留它们用于后续升级，但不要分享或放入公开版本库。

`APK/build-report.json` 和 `APK/verification.txt` 记录实际产物的签名、Manifest、权限、SHA256 和验证范围。

## 实现入口

- `AndroidManifest.xml`：导出的 Explain Activity、`PROCESS_TEXT`、`text/plain`、目标 API 36。
- `src/cn/weilin/termlens/probe/ExplainActivity.java`：接收 `EXTRA_PROCESS_TEXT` 并显示浮动底部窗口。
- `src/cn/weilin/termlens/probe/MainActivity.java`：安装后说明、可选择的样例和直接弹窗测试。
- 原文不通过 Activity 结果写回；始终返回 `RESULT_CANCELED`，保护可编辑源文字。

官方接口：https://developer.android.com/reference/android/content/Intent#ACTION_PROCESS_TEXT
