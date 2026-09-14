# P1：贴边竖条与全区域WebView

用户确认采用方案B。本轮仅做P1，P2样式保持待确认。

## 行为

- ST WebView占满系统栏、刘海和键盘之外的可用矩形；主页面不再保留标题、副标题、按钮行和状态行。
- 控制入口改为贴边4dp×48dp竖条，使用24dp×72dp触摸区域；向内滑动展开，纵向拖动调整位置。保留原来的停靠边与归一化纵向位置。
- 点击也可打开底部覆盖面板；收起、面板外区域或系统返回键关闭。面板只覆盖页面，不改变WebView尺寸。
- 窗口、启动底色和Root背景改为`#101010`，让透明状态栏透出深色；保留状态栏空间，并使用浅色系统图标。
- 操作纵向排列，可滚动查看全部；沿用当前系统控件样式。
- 键盘显示与动画期间隐藏悬浮按钮，收起后按保存的位置恢复。
- 统一合并systemBars/displayCutout/IME边距，底部取最大值而不是将键盘和导航栏重复相加。
- Activity重建时重新挂载SessionController已有WebView；没有改ST源码、服务器或生成流程。
- 首次打开且没有运行页面时展示控制面板；关闭后也可从悬浮入口启动。启动进度在空内容层显示，ST页面出现后该层隐藏。

## 文件及原因

| 文件 | 原因 |
|---|---|
| `android-app/app/src/main/java/dev/stshell/app/MainActivity.kt` | 删除固定控制区，建立覆盖面板，接入窗口边距、返回收起、位置/面板状态保存；原有操作回调保留 |
| `LauncherLayout.kt`（同目录） | WebView全区域容器、悬浮按钮拖动/停靠、面板覆盖、IME动画及系统手势区域处理 |
| `LauncherGeometry.kt`（同目录） | 边距合并、贴边坐标、纵向比例和向内手势判定 |
| `EdgeHandleView.kt`（同目录） | 绘制无系统按钮底板的小竖条 |
| `res/values/launcher_layout.xml` | 控制入口/面板文字及View IDs |
| `res/values/launcher_window.xml` | 深色窗口背景、透明系统栏及竖条颜色 |
| `AndroidManifest.xml` | 保留adjustResize/返回回调，接入窗口主题 |
| `android-app/app/build.gradle.kts` | versionCode=5 / versionName=0.4.1 |
| `src/test/.../LauncherGeometryTest.kt` | 10项几何/手势测试：系统边距、IME最大值、贴边、恢复、向内方向与极端尺寸 |
| `src/androidTest/.../LauncherLayoutTest.kt` | 设备端边距/覆盖层、实际拖动和重建、真实软键盘测试 |
| `src/androidTest/.../AppSmokeTest.kt` | 增加真实ST WebView实例和Node PID在Activity重建前后保持一致的断言 |

未新增Android UI依赖；P0图标、ST submodule和SessionController实现未修改。

## 本机结果与产物

- App JVM 24项通过，包含几何与手势10项。
- Python28项、Node32项回归通过；APK静态检查及构建通过。
- 设备测试已编译，构建机暂无ADB设备；横竖屏、真实键盘及拖动体验需安装后确认。
- 主APK：`build/releases/sillytavern-android-0.4.1-p1.apk`，256,052,309字节。
- SHA256：`a612647fc6f029e3449a73b7f2f34e61c6768dc4dcd17a3b74e38d3862a8071c`
- 设备测试APK：`build/releases/sillytavern-android-0.4.1-p1-androidTest.apk`。

## 验收顺序

1. 覆盖安装后启动，检查ST页面是否占满可用区域。
2. 打开/收起控制面板，确认底层WebView尺寸不变化，所有操作可找到。
3. 从贴边竖条向内滑动打开面板；上下拖动调整位置，旋转、重开后检查停靠边与位置。
4. 点击聊天输入框，检查键盘弹出时按钮隐藏、输入区域未被覆盖；收起键盘后按钮恢复、底部没有多留一段空白。
5. 横竖屏切换时检查页面未重载、当前内容保留。

```bash
(cd android-app && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug)
python3 tools/app/verify.py android-app/app/build/outputs/apk/debug/app-debug.apk
(cd android-app && ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.stshell.app.LauncherLayoutTest)
```

本次0.4.1仅修正P1反馈：透明状态栏露出应用白色背景，以及按钮改贴边竖条。ST欢迎弹窗布局、服务端、生成和后台保护逻辑未修改。P1交付后停止，等待用户确认后再进入P2。
