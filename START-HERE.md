# TermLens Android 启动与交接（0.5.2 独立测试，2026-10-25）

本轮新增手动输入、铅笔编辑、逐层返回和美式 Andrew 语音；独立测试版已获用户验收并授权更新源码；生产升级包必须使用原签名电脑的旧签名，缺少该签名时不得交付替代签名包。当前测试方法见 `MANUAL-INPUT-TEST-20261025.md`。

当前手机端源码在本目录。当前电脑端以根目录 `README-目录导航.txt` 和 `Start-TermLens.ps1` 指向的 `release/Termlens` 为基准；旧 `TermLens/HANDOFF-20261001-UI-BACKEND-REFACTOR.md`、`WORK-PLAN.md` 是历史设计参考，不代表正在使用的电脑端。不要把打包目录、旧分支、签名文件或个人数据当成可清理的构建缓存。

## 使用与界面

原 App 原生选中文字 → 一次短按悬浮 ✦ → Explain → 关闭回到原页面。无 OCR、屏幕取词或剪贴板猜词。未锁定时长按悬浮按钮拖动，松手记住位置；设置支持锁定、左右下角归位、半透明、键盘出现时隐藏。荣耀“在其他应用上层显示”已授权时用应用悬浮窗，否则用无障碍悬浮层；取选区仍需要无障碍服务。

首页只放入口；历史页固定顶部筛选、搜索和备份入口，正文使用可复用列表；设置中的模型、声音、阅读场景折叠显示。结果页头部、关闭和底部工具栏固定，正文独立滚动。选中结果文字后按“解释选中”，返回箭头回父层，关闭回原 App。

本次最终构建复核 Chrome 已登录 X 帖子的主层、二级解释和原页返回。Edge 网页本轮较早构建通过；AnkiDroid 普通卡片、阅读、Via 的前次实测见 README 历史记录。包过滤名单不是全部兼容证明；无原生选区的页面仍无法取词。

## 数据契约与操作入口

- `AppData`：私有 `history.sqlite3`；查询、收藏快照、长期选词账本以及电脑端旧索引的兼容表。ID 稳定，UTC 时间；新记录保留 `sourceApp`、场景快照和父子语境。
- 历史与收藏 → 备份：历史 JSON、收藏 JSON、JSON 导入、含音频 ZIP 的导入/导出。JSON 使用当前电脑端 `termlens-history` version 2 契约：`records`、`favorites`、`selection_events`；收藏快照维持 PascalCase 字段。JSON 只有音频元数据，完整音频用 ZIP。
- ZIP：`history.json`、`prompt-library.json`、`history-audio/<queryId>/`、`favorites/<favoriteId>/`。每个目录是 `manifest.json` 加 MP3；清单记录文件、字节数和 SHA-256。备份排除 API 密钥、连接设置与签名材料。
- 普通解释和音频保留 15 天；选词账本和已收藏快照长期保留。收藏音频存独立永久目录。无法归属、日期未知或校验不符的文件不会被自动清理。清理在应用使用中调度，不承诺停用应用后准点执行。
- 导入先校验，重复 ID 不覆盖本机内容；同名异内容音频拒绝导入；无效记录回滚数据库合并。场景库追加不冲突方案，保留本机当前选择。整个 ZIP 的数据库和文件操作不是跨介质原子事务，写入失败应保留原备份并重试。
- 已用实际电脑端程序的 JSON、Favorite、HistoryStore 读取器验证手机格式；没有改电脑端界面，也没有新增电脑端“导入手机 ZIP”入口。不要直接覆盖正在使用的电脑数据库。后续互传 UI 应复用这些契约并做合并。
- 0.4.1 仅保存过收藏哈希标志，无法恢复当时未存储的完整解释；再次查询对应词时会迁移收藏标志。不得把本版本新增持久化描述成完整恢复旧历史。

## 请求、语音和缓存

`LlmClient` + `StreamAnswer` 接收 SSE，首段立即展示，后续合并更新；`ExplainActivity` 保留正文 View 和滚动位置。DeepSeek 普通模式显式关闭 thinking，temperature=0.2，和当前电脑端普通查询对齐。中断和长度截断保留部分回答但不标记完成。首字、完成、首帧和绘制时间仅记录耗时/数量，不记录密钥。

`SpeechProvider` 隔离语音服务；`EdgeSpeechProvider` + `EdgeToken` 在手机内通过 TLS/WebSocket 调用 Edge 朗读，原生 Java 实现，不依赖电脑、Python 或 TTS Server。协议及 token 算法参考 [edge-tts](https://github.com/rany2/edge-tts)，授权说明见 `licenses/`，上游协议变化可能需要更新适配器。

`SpeechOutput` 负责后台合成、音频分段、播放器、内存 LRU 和持久缓存。默认 en-US-AndrewNeural、+10%，可配置 Voice 和 Rate；指纹由 Provider、Voice、Rate、扩展名与纯文本组成。普通缓存带 QueryId、WordComplete/ExplanationComplete；永久收藏带 FavoriteId。原文与解释分开缓存，选中片段不覆盖整段原文缓存；播放失败不把空文件标记为有效。首次合成需要联网，已有且参数匹配的音频从本机读取。

0.5.1 在 AppData.begin 后、发起 LLM 之前提交原文预取，LLM 完成后独立提交解释预取；打开历史同样补齐缓存。后台三条工作线程和独立播放执行器分离，ASSET_LOCK 只保护文件/清单操作，不覆盖网络合成。每次任务冻结语音配置；preparing/downloading 的 FutureTask 合并同一查询/内容的并发工作，失败后移除任务允许重试。按内容指纹索引其他有效历史/收藏清单，跨进程、新查询也能离线复用，仍写入当前查询目录；不新增与桌面端冲突的持久缓存格式。导入 ZIP 后使索引失效。点击时缓存命中直接播放，延迟提示仅用于仍在准备的请求，诊断区提供 click_to_ready_ms/click_to_play_ms。

EdgeSpeechProvider 一次写出完整 WebSocket 帧并开启 TCP_NODELAY，避免逐字节写帧头；记录 TLS、WebSocket、首音频、完成阶段耗时。不同长度的新解释首次完整合成仍可能需要数秒到十余秒；当前播放器等待完整音频资产，不提供尚未完成音频的边合成边播。

## 多场景与后续扩展边界

`SceneProfiles` 按电脑端 PromptPresets/ActivePromptPresetId/TemplateId 保存方案，内置当前英语学习、技术阅读、聊天日常三个方案。每次查询冻结 `promptProfile`；二级词义携带 rootOriginal/rootAnswer 和直接 parentQuery/parentAnswer，不受查询后切换场景影响。

后续按来源适配的路由应放在选区入口与查询创建之间，通过 `sourceApp` 选择稳定场景 ID，再建立不可变快照；不要在 LLM、音频缓存、历史列表里分别写包名判断。来源取词扩展到 SelectionOverlayService/SelectionMenuBridge；界面只接收选区与上下文。当前只提供手动场景切换，未实现跨 App 自动分类。

后续服务器同步以稳定 QueryId/FavoriteId、UTC Updated、现有 JSON version 2 和音频内容哈希为边界；新增 transport/repository，同本机 AppData 合并，不让网络层直接改 SQLite。删除传播需要另外定义 tombstone、冲突和版本迁移；当前没有服务器、账号或后台同步。远端语音实现另一个 SpeechProvider，继续复用缓存与播放器。

## 构建与验证

Windows PowerShell：`./build.ps1 -ToolRoot <Android工具目录> -Python <python.exe> -Output <绝对产物目录> -WorkRoot <绝对tmp目录>`。需要 Python 3、JDK 17、Android build-tools 和 android.jar；工具布局见 build.py。脚本默认本机工具路径可用参数覆盖。签名保存在私有 signing/；源代码包不包含个人签名，重建会使用独立签名，不能覆盖安装已有签名版本。

`tests/CompatChecks.java` 是隔离测试数据目录的 instrumentation，用于 SSE、WebSocket、缓存、保留期、格式、ZIP 校验和路径边界；绝不对个人数据做清空。运行 instrumentation 会停止目标进程，可能需要重新绑定无障碍服务；最终设备 UI 回归应放在这类测试之后。

最终 APK、源码包、SHA-256 和报告：`D:/Termlens/outputs/android-audio-prefetch-2026-10-16/final/`。本轮截图、XML、构建中间文件和隔离测试数据：`D:/Termlens/tmp/android-audio-prefetch-2026-10-16/`。0.5.0 数据兼容基线和报告保留在对应 android-latency-2026-10-16 目录。最新验证范围和未验事项以产物中的 DEVICE-TEST.md 为准。
