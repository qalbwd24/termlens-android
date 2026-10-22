# X / YouTube / ChatGPT 真机兼容性探测

日期：2026-10-03。设备：荣耀 Magic7 / MagicOS 10 / Android 16，ADB 确认 SDK 36。
本轮最终安装版本：TermLens 0.2.3-app-probe / versionCode 7。
APK：`APK/TermLens-selection-probe-0.2.3.apk`。
SHA256：`69529a89d3fb5bbe3b4c890ae63e12f377e50cdb26fa74934ee18ee6892b398c`。

结论：三个 App 的自动划词浮钮尚未通过。ChatGPT 通过系统「更多 → Explain」可准确打开弹窗。
保留了可运行的诊断版本，不将安装成功、服务连接或内部控件通过当作外部适配成功。

## 修改与验证

0.2.0 只监听 TermLens 和 Via，因此其他 App 无按钮首先是监听范围限制。
0.2.1 加入 `com.twitter.android`、`com.google.android.youtube`、`com.openai.chatgpt`，增加长按诊断事件。
0.2.2 增加 `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS` 和空父节点下的子节点选区查询。
0.2.3 增加目标 App 页面事件后的选区扫描，以及不包含正文的 ADB 服务诊断。
最终服务诊断确认 flags=18，packageNames 含上述五个包，服务已连接，无崩溃；其他已启用服务保留。
构建、签名 v1/v2/v3、对齐、Manifest 和 DEX 检查通过。

| App 与真机版本 | 测试页面 | 观察结果 | 本轮结论 |
|---|---|---|---|
| X 12.30.0-prod.01 | 首页帖子进入详情，选中 Opus | 原页面有高亮、手柄和自定义菜单；0.2.1–0.2.3 未取得正文选区；可访问节点的索引均未提供有效范围 | 自动浮钮失败；首页长按先进入详情，不能当作已选词 |
| ChatGPT 1.2026.265 | 现有英语单词会话的回答，选中 conversely | 回答正文可选，服务未收到有效正文选区；0.2.2 的两个事件来自空输入框；0.2.3 再次未出现独立按钮 | 自动浮钮失败；更多菜单中 Explain 成功传入 conversely，共 10 字符，关闭返回回答 |
| YouTube 21.39.522 | 视频标题、展开的简介、公开评论 | 0.2.2 长按未进入原生文字选区；0.2.3 检查页面节点，没有获得有效选区或目标正文 | 这三类文字尚未适配，不涉及字幕测试 |
| TermLens 0.2.3 | 首页原生测试 TextView | 独立按钮出现，点击收到 limitation can have a significant impact.，41 字符，返回 MainActivity | 内部悬浮按钮和弹窗回归通过 |

测试没有发送 ChatGPT 消息、评论、点赞、关注或订阅。YouTube 测试结束时已暂停视频。
点击 ChatGPT 首页示例曾填入未发送草稿；已清除后继续测试，未发送。

## 证据

目录：`build/device-20261003/`。

- `x-detail-select.png`、`x-0.2.2-button.png`：X 原生选区存在，但没有 TermLens 独立按钮。
- `chatgpt-0.2.3-select.png`：回答正文选中 conversely，独立按钮未出现。
- `chatgpt-more.png`：更多菜单中存在 Explain。
- `chatgpt-explain-popup.png`、`chatgpt-explain-popup.xml`：准确原文及 10 字符结果。
- `youtube-title-longpress.png`、`youtube-description.xml`、`youtube-comment-longpress.png`、`youtube-comments.xml`：YouTube 目标页面和长按观察。
- `app-stats-0.2.2.xml`：服务连接和三个 App 的计数；ChatGPT 的两个事件为输入框事件，不能当作回答选区。
- `native-0.2.3-button.png`、`native-0.2.3-popup.xml`：最终版本内部独立按钮和 41 字符弹窗。
- `compat-0.2.3-status.txt`：最终服务连接、监听包、计数，未保存正文。

截图是本轮 ADB 人工调试证据；APK 本身没有截图、OCR、网络或剪贴板读取实现。
测试限于以上版本和页面，不推断所有页面、所有版本或所有手机都相同。

## 技术判断与下一步

没有有效文字和选区时，不能只靠调整优先级或渲染悬浮层还原用户选中的词。
本轮页面节点未提供目标内容；具体是源 App 的自定义呈现还是系统敏感数据限制，尚未证明根因。
Android 官方说明敏感数据标记可以限制非辅助障碍人士的服务读取节点，不能将其误认作唯一确定原因。
不通过虚报 `isAccessibilityTool` 来声称完成兼容。

待用户选择的备用交互：点浮钮 → 框选可见文字 → 本机 OCR → 核对原文 → Explain 弹窗。
这是不同于原生划词的入口，有额外操作与识别误差，尚未实现或验证。
优先考虑内置 ML Kit 识别模型，避免首次使用依赖 Google Play 动态下载；需要先验证截图能力、识别和返回。
截图被源 App 禁止的页面仍可能无法支持，不能承诺绝对全局可用。

参考：

- [Android 选区索引](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#getTextSelectionEnd())
- [非重要节点读取标志](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#FLAG_INCLUDE_NOT_IMPORTANT_VIEWS)
- [Android 敏感无障碍数据限制](https://developer.android.com/blog/posts/enhancing-android-security-stop-malware-from-snooping-on-your-app-data)
- [ML Kit Android 文字识别与内置模型](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- [无障碍截图公开接口](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshot(int,%20java.util.concurrent.Executor,%20android.accessibilityservice.AccessibilityService.TakeScreenshotCallback))
