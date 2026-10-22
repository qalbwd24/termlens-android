# Android 选词原型真机验证

日期：2026-10-03。设备：荣耀 Magic7 / PTP_AN00，Android 16，API 36。
验证方式：USB ADB 操作、UI 层级、屏幕截图；没有使用模拟器。

## 产物与结果

- 包名：cn.weilin.termlens.probe；版本：0.1.2 / versionCode 3。
- APK：APK/TermLens-selection-probe-0.1.2.apk，20957 字节。
- SHA256：8071671098a1029d5a70e573cd8ff4bd00af5642a777023f3ca7448f54185d1e。
- ADB 覆盖安装成功，保留原包签名。
- 内置正文选中 limitation，原生「更多」菜单出现 Explain。
- 点击该菜单项目后，ExplainActivity 显示 limitation，字符数 10。
- 系统返回关闭弹窗，前台回到 MainActivity，测试正文仍在。
- 没有给原生菜单手动插入项目；Manifest 无申请权限。

证据位于 build/device-20261003：
menu-retest-more.png、verified-popup.png、verified-popup.xml、verified-return.xml。
fixed-popup.* 是调试时误点浏览器的中间记录，不能作为 Explain 验证证据。

APK/build-report.json 记录构建时检查，构建程序本身不连接手机；真机结果以本文为准。

## 根因

从本机 /system/framework/framework.jar 读取的 TextView.canProcessText() 判断显示：
没有 View ID 的控件不提供 PROCESS_TEXT。原型的程序创建控件默认没有 ID。
Ui.text() 和 DiagnosticsActivity 的 EditText 已分配 View.generateViewId()。
修改后真机原生菜单恢复第三方文字处理入口。

## 验证边界

用户已反馈外部的系统选词面板最后一页能找到 TermLens Explain。
本次自动操作闭环验证的是内置原生正文；尚未完成各浏览器、外刊 App、PDF 和可编辑输入框的逐项验证。
没有实现或验证独立悬浮按钮、无障碍取词、LLM、OCR。
不能据此宣称全部 App 兼容或 Explain 可直接显示在第一排。
