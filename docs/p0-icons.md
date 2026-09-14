# P0：SillyTavern 图标

## 素材与生成方式

- 图形：官方 submodule `upstream/SillyTavern/public/img/logo.svg`。
- 背景色：`public/img/apple-icon-512x512.png` 的原始背景 `#2E2E2E`。
- 固定源版本/源文件SHA与生成资源SHA见 `tools/icons/manifest.json`；没有修改ST文件。
- SVG路径直接转换为Android矢量，等比例居中；自适应前景放入108dp画布的66dp安全圆内。
- 普通/圆形PNG包含ldpi、mdpi、tvdpi、hdpi、xhdpi、xxhdpi、xxxhdpi七档；像素尺寸36/48/64/72/96/144/192。
- 自适应图标包含前景/背景，API33资源还包含monochrome。
- 通知小图标使用同一图形的白色透明矢量；通知大图标从彩色自适应资源绘制。

## 本轮文件

| 文件 | 变更原因 |
|---|---|
| `android-app/app/src/main/res/mipmap-{ldpi,mdpi,tvdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher{,_round}.png` | 七档普通/圆形图标 |
| `res/mipmap-anydpi-v26/ic_launcher{,_round}.xml`、`res/mipmap-anydpi-v33/ic_launcher{,_round}.xml` | 自适应与主题图标引用 |
| `res/drawable/ic_launcher_foreground.xml`、`ic_launcher_monochrome.xml`、`ic_stat_sillytavern.xml` | 彩色前景与两种单色用途 |
| `res/values/branding.xml` | 应用名称和图标背景色 |
| `AndroidManifest.xml` | 应用icon/roundIcon/label指向新资源 |
| `LauncherBranding.kt` | 最近任务TaskDescription与通知彩色图标共用品牌资源 |
| `BackgroundProtectionService.kt` | 通知smallIcon/largeIcon改用酒馆图标 |
| `MainActivity.kt` | 设置最近任务图标；按统一要求清理标题、启动弹窗、关于页等文案 |
| `SessionController.kt`、`StService.kt`、`GenerationState.kt` | 清理同类文案/注释，诊断scope改为`android-launcher` |
| `runtime/android/generation-observer.js`、`AppSmokeTest.kt` | 注释改为直接描述实现/测试行为 |
| `tools/app/app_inputs.py`、`prepare.py`、`verify.py` | 删除相关构建元文案；构建时校验图标来源、资源及APK打包结果 |
| `android-app/app/build.gradle.kts` | versionCode=3 / versionName=0.3.0，供覆盖更新 |
| `tools/icons/{generate.py,verify.py,test_icons.py,requirements.txt,manifest.json}` | 可复现生成、校验和测试；CairoSVG/Pillow仅用于宿主资源生成，不增加Android UI依赖 |
| `LauncherIconTest.kt` | 设备端检查应用、自适应、任务标签与通知图标资源 |
| `README.md`、`docs/status.md`、`docs/decisions.md`、本文 | 记录交付版本、修改范围、验收和逐项确认规则 |

表中`res/`和Kotlin文件均位于`android-app/app/src/main/`，设备测试位于`src/androidTest/`。版权和许可证原文保持不变。P1布局与P2样式未实施。

## 本机命令

```bash
python3 -m pip install --target build/icon-tools -r tools/icons/requirements.txt
python3 tools/icons/generate.py --check --preview build/reports/p0-icons-preview.png
python3 tools/icons/verify.py
python3 -m unittest discover -s tools/icons -p 'test_*.py' -v
(cd android-app && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug)
python3 tools/app/verify.py android-app/app/build/outputs/apk/debug/app-debug.apk
```

设备测试命令：

```bash
(cd android-app && ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.stshell.app.LauncherIconTest)
```

## 产物与检查结果

- 主APK：`build/releases/sillytavern-android-0.3.0-p0.apk`，versionCode=3 / versionName=0.3.0。
- 大小256,020,166字节，SHA256：`98891dcc4370d0b51467614f600987e11453f41554f0fdda74c096bdbf52fda5`。
- 设备测试APK：`build/releases/sillytavern-android-0.3.0-p0-androidTest.apk`。
- 图标生成/校验及6项图标测试通过；14项App JVM、28项Python宿主、32项Node宿主测试通过。
- APK签名、16KB对齐、七档已打包PNG尺寸、应用名称及自适应层检查通过。图标预览：`build/reports/p0-icons-preview.png`。
- 设备图标测试已编译；构建机当前无ADB设备。桌面、最近任务和通知的最终显示由用户安装后确认。

交付后等待用户确认P0；确认后才给P1的两种方案。
