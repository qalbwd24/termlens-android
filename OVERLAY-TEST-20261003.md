# Explain 悬浮按钮真机验证

日期：2026-10-03。设备：荣耀 Magic7 / MagicOS 10，Android 16 / API 36。
使用 USB ADB 和真实手机，未使用模拟器。

## 最终产物

- APK：APK/TermLens-selection-probe-0.2.0.apk
- versionCode：4；包名：cn.weilin.termlens.probe
- SHA256：038f8ac54c16d60f1f8af864ce1c2c6dc646f10bef22c2f0e4fcc0a062cea63b
- 已覆盖安装；签名、Manifest、DEX、资源和对齐检查通过。

## 技术栈及范围

原生 Java，不引入第三方运行依赖。
AccessibilityService 接收 TYPE_VIEW_TEXT_SELECTION_CHANGED。
WindowManager TYPE_ACCESSIBILITY_OVERLAY 显示不夺取输入焦点的 Explain 按钮。
点击后显式启动现有 ExplainActivity，读取传入的选中文字并显示底部浮动窗口。
无 uses-permission；服务由 BIND_ACCESSIBILITY_SERVICE 保护，只允许系统绑定。
用户已在手机设置手动启用服务；不是通过 ADB 修改 enabled_accessibility_services 绕过授权。
仅监听 cn.weilin.termlens.probe 和 mark.via.gp。
不联网、不截图、不读取剪贴板、不记录或保存所选正文；统计和当前选词只在内存中。
测试的 ADB 截图是电脑侧验证证据，不是 APK 的截图功能。

## 实测结果

| 场景 | 结果 | 证据 |
|---|---|---|
| 内置正文选中 limitation | 独立 Explain 按钮出现，原生菜单仍显示，选区保持 | overlay-final-button.png |
| 点击独立按钮 | ExplainActivity 显示 limitation，字符数 10 | overlay-final-popup.xml |
| 系统返回 | 回到 OverlayTestActivity | ADB 前台 Activity 确认 |
| 滚动正文 | 按钮消失，继续阅读 | overlay-after-scroll.png |
| Via 网页选中 documentation | 没有可用选区，按钮未出现 | via-overlay-retest.png、overlay-via-final.xml |

证据目录：build/device-20261003。只有上述对应文件为此次最终判断依据。
其他调试图片有旧页面或选中诊断信息的过程，不应当作 limitation 成功证据。

Via 测试使用浏览器打开的公开 example.com 页面，没有执行网页脚本或修改浏览器设置。
设备实际事件：包 mark.via.gp，源类 android.webkit.WebView，文字长度 0，
event.fromIndex / toIndex 均为 -1；节点传统选区索引也为 -1；事件 text 列表为空。
增加有界子节点查询后仍无有效选区。公开 getSelection() 在手机上不存在（NoSuchMethodException），
并非通过反射调用隐藏接口；该公开接口由官方文档标为 SDK 36.1 新增。

https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#getSelection()

## 限制与下一步

已证明这台手机可以显示无障碍悬浮按钮，并从可读取选区的原生正文完成点击弹窗。
没有证明所有 App 可用，Via 的此次网页测试失败。
按钮定位依据整个文字节点边界，尚未获取具体选中字符的边界。
当前不增加 OCR、自动点击复制、剪贴板监听或 LLM 来掩盖取词失败。
如需覆盖该类网页，需要独立验证框选 OCR 或浏览器内的选区桥接等另一条取词途径。

临时本地网页服务器与本次 ADB reverse 映射已清理。原有其他无障碍服务未关闭。
TermLens 测试服务保留开启，用户可通过测试页「停止划词按钮服务」关闭。
