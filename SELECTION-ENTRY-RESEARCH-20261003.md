# 划词入口位置与悬浮按钮技术调查

调查日期：2026-10-03。目标：荣耀 Magic7 / MagicOS 10 / Android 16。

## 系统菜单优先级

普通第三方应用无法通过提高 intent-filter priority 强制把 Explain 放到所有 App 第一排。
Android 官方文档明确：非特权应用请求大于零的优先级时，该值被限制为零。
android:order 只区分同一个应用内部的多个匹配过滤器。

https://developer.android.com/guide/topics/manifest/intent-filter-element

AOSP Editor.ProcessTextIntentActionsHandler.onInitializeMenu() 在创建第三方项目时调用
setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)，即默认进入更多菜单。
手机实际 framework.jar 的对应字节码也设置 showAsAction=0。
自己应用的 ActionMode 可以改布局，但不能改变其他应用的菜单。

https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/widget/Editor.java

## 更短的交互路径

候选方案：用户选词后，无障碍服务在可读取选区的场景显示独立 Explain 按钮；
点击按钮才打开结果弹窗。需要用户在设置启用服务；目前尚未实现或实测。
可以使用 TYPE_ACCESSIBILITY_OVERLAY 显示按钮，或者常规悬浮窗权限实现应用悬浮层。
关键的不确定性是读取选区：不同源 App 是否提供事件、文字和选区索引，决定兼容范围。
官方 TYPE_VIEW_TEXT_SELECTION_CHANGED 文档描述 EditText 选区事件，不能由此推断所有网页都提供。

https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent#TYPE_VIEW_TEXT_SELECTION_CHANGED
https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_ACCESSIBILITY_OVERLAY

## 实际查看的开源参考

### qiyexey/screen-translator

https://github.com/qiyexey/screen-translator

已阅读 ScreenReaderService.kt 和 OverlayService.kt，而非只依据项目简介。
ScreenReaderService.onAccessibilityEvent() 接收 TYPE_VIEW_TEXT_SELECTION_CHANGED；
handleSelectionEvent() 读取 event.fromIndex / toIndex 和 event.source.text，
检查范围后截取选中文字，再调用翻译和悬浮显示。
项目 README 明确保留 PROCESS_TEXT，补足 WebView 等不发送选区事件的情况。
该实现证明有可参考的选区监听代码，不能证明荣耀所有 App 都支持。
可借鉴监听流程，把自动翻译改成先出现 Explain 按钮、点击后才请求。

https://github.com/qiyexey/screen-translator/blob/main/app/src/main/java/com/hunter/screentranslator/service/ScreenReaderService.kt
https://github.com/qiyexey/screen-translator/blob/main/app/src/main/java/com/hunter/screentranslator/service/OverlayService.kt

### aidanpark/screen-translator

https://github.com/aidanpark/screen-translator

提供拖动取词指针、截图 OCR 和悬浮结果的另一条交互路线。
它不是自动获取其他 App 原生选区的保证，也不能改变系统选词菜单第一排。
如果浏览器不提供选区，可以评估定点或框选 OCR，但这会改变选词操作并增加识别成本。

## 下一步验证目标

先制作不联网的无障碍选区探针，在内置正文、Via 网页和常用外刊阅读页面测试：
是否收到选区、原文是否准确、显示按钮会否取消选区、点击后能否打开和关闭弹窗。
通过后再确定正式框架及 LLM 接入。全局第一排和全部 App 自动划词均未找到公开接口保证。
