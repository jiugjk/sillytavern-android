# SillyTavern Android 启动器

## 当前方案：首次联网安装 ST Staging 与内置全局插件

APK 只带 Node26 与安装器。首次启动下载 ST Staging 和 ST-Prompt-Template、JS-Slash-Runner、st-theater 的最新源码并安装生产依赖；普通启动离线复用。重新下载入口保留用户数据。实现、信任边界、构建命令和真机待验项见 **[首次安装说明](docs/first-run-install.md)**。

以下阶段记录中的固定 ST payload、历史包大小和时间不是当前 APK 的验收结论。

## 历史阶段：P1 全区域WebView

P0已获用户确认。当前交付：`build/releases/sillytavern-android-0.4.1-p1.apk`。ST占满系统安全区域；控制入口为贴边小竖条，向内滑动展开、上下调整位置，键盘弹出时隐藏。窗口背景改为深色，修正透明状态栏下露出的白色底色。改动文件、原因和验证命令见 [P1记录](docs/p1-fullscreen.md)；图标来源保留于 [P0记录](docs/p0-icons.md)。

以上是历史 P1 交付记录，不限制当前首次联网安装方案。

以下保留既有构建与阶段记录。

非官方 Android 壳工程。用户已选择方案 A 并批准开始实施：**Node26、独立APK、Android14+、仅arm64、侧载、远程API、后台/锁屏继续生成**。

用户已确认v0.1真实ST“能正常运行和显示”。当前新增 **v0.2后台保护实验APK**：`build/experiments/st-android-background-v0.2.apk`，可覆盖安装，不要卸载/清除数据。

启动ST并等待“观察器就绪”，再点**后台保护**、允许通知并确认；随后尝试发送长回复再锁屏。保护服务有持续通知，空闲不持CPU锁，停止生成调用酒馆原有接口。**锁屏持续生成与保存仍待你实测，不承诺Doze/强杀后继续；导出/语音仍未完成。** 操作与诊断字段见[后台实验说明](docs/background-experiment.md)，前一版边界保留于[前台实验说明](docs/foreground-experiment.md)。

原v0.2运行时探针保持不变。已收到用户回传的Android arm64单次12/12成功结果及Native退出码0；完整M1仍需补齐汇总。新增实验代码不等于放行M1/M3/M3.5，也没有改写ST生成逻辑。

- 需求/总计划：[docs/decisions.md](docs/decisions.md)、[docs/plan.md](docs/plan.md)
- 当前实测状态：[docs/status.md](docs/status.md)
- CI构建与发布：[docs/ci-release.md](docs/ci-release.md)
- 上游：官方 `upstream/SillyTavern` submodule，固定commit，**无ST源码修改**。
- Node：官方26.8.2源码构建为`libnode.so`；来源、校验和、工具链见`upstream.lock.json`。
- 所有适配位于外层。Node构建补丁及已知配置差异见[patches/README.md](patches/README.md)。

## 0. 构建当前实验APK

实验App放在独立`android-app/`构建中，避免使已有v0.2探针指纹失效；复用同一份M1 JNI桥源码，不改ST。完成下节环境配置并已有匹配的 Node runtime 后（`prepareApp` 会准备安装器）：

```bash
(cd android-app && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug)
python3 tools/app/verify.py --variant debug --require-signature android-app/app/build/outputs/apk/debug/app-debug.apk
```

App包名`dev.stshell.app`，与探针并存；当前约244MiB，建议留1.5GiB以上空间。点击“诊断”可复制不含密钥/聊天的活动、前台服务、唤醒锁和HTTP请求状态，必要时再人工检查“启动日志”。不是正式可交付或已验证后台可靠性的版本。

## 0.1 GitHub Actions构建（手动触发）

Actions里的 **Android APK** 工作流手动触发，可选只出`release`或`debug`，按`patch/minor/major`推进
`android-app/version.properties`并把版本提交回当前分支，随后把APK发到Releases。Node交叉编译产物与
首次安装器分别缓存；ST 与插件在设备首次启动时下载。缺少匹配 Node 缓存或改动锁/补丁时需要重编 Node。

release签名来自仓库secrets（`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、
`ANDROID_KEY_PASSWORD`）；用`bash tools/ci/make_signing_key.sh`在本机生成keystore再上传，
密钥不进仓库。缺密钥时release包保持未签名，此时工作流拒绝发布Release。参数、缓存与校验范围见
[docs/ci-release.md](docs/ci-release.md)。CI只做静态校验，不代表设备验收。

后台保护默认关闭、需前台用户主动开启；通知持续整个被保护会话（含空闲），CPU锁按工作释放。现有已提交配置包含`allowKeysExposure=true`和关闭聊天自动备份，本轮保留未擅改；请只使用测试数据和临时凭据。

## 1. 本机环境

初始交叉构建配方支持Linux x86_64，建议至少16GB内存和20GB可用磁盘。需要Git、Python3.12+（Node configure）、GCC/G++13.2+及host libatomic开发库、JDK17或21、Android SDK。Python工具本身仅使用标准库。

本工作机示例（其他机器替换路径）：

```bash
cd /home/opc/sillytavern-android
export ANDROID_HOME=/home/opc/android-sdk
export JAVA_HOME=/home/opc/opt/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
sdkmanager "ndk;30.0.16248370" "cmake;3.22.1" "platforms;android-36" "build-tools;35.0.0"
```

探针`minSdk=34`，`targetSdk=36`，ABI仅`arm64-v8a`。targetSdk不是最低Android版本。NDK30用于现代Bionic/16KB构建；NDK/Gradle/AGP/Kotlin均固定版本。不需要Termux，也不下载nodejs-mobile18的二进制充数。

## 2. 检查上游与宿主机测试

```bash
git submodule update --init --recursive
python3 tools/verify_project.py
python3 -m unittest discover -s tests/host -p 'test_*.py' -v
node --test tests/host/probe.test.mjs tests/host/mobile-policy.test.mjs
```

Node运行时探针测试需要宿主Node26和网络，会真实访问`nodejs.org`验证DNS/HTTPS；不会发送用户数据。测试还启动本机TLS fixture，核验未知证书和错误hostname都被拒绝。测试用私钥是刻意公开的fixture，不能用于生产。

宿主探针覆盖：ESM/TLA、fs写入/fsync/rename、crypto、完整ICU/Unicode、WASM/SIMD、worker_threads、HTTP流、fetch/Blob/Response、DNS、TLS验证。结果明确标为`mode=host`，Android验收工具拒绝把它当设备成绩。

## 2.1 构建ST payload与完整宿主回归（已实现）

此部分不要求Android设备，也不把宿主通过当作Android通过。需要锁定的Node26.8.2/npm11.19.1；首次构建会下载公开npm依赖，不调用收费AI接口。

```bash
python3 tools/payload/prepare.py
python3 tools/payload/verify.py
node --test tests/host/*.test.mjs
```

- 在临时目录展开官方`git archive`，执行`npm ci --omit=dev --ignore-scripts --bin-links=false --engine-strict`；不在submodule内安装/改文件，不运行依赖安装脚本，不保留`.bin`符号链接。
- ZIP内为`server/`（完整官方源码+生产依赖）、`shell/`（外层适配）、`dependency-inventory.json`。不包含用户state、API密钥或运行时config.yaml。默认内容/tokenizer与21个独立WASM保留，未做激进裁剪。
- 对官方源码逐文件核验；ZIP固定顺序/时间/权限，成员SHA256、大小与目录库存写入外置manifest。拒绝路径穿越、重复项、符号链接/特殊文件、超限项、常见native产物和缺失WASM；生产依赖若新增os/cpu/libc条件也必须停下复核，不能用Linux安装结果冒充Android依赖选择。
- 产物位于`build/payload/<payloadId>/{payload.zip,manifest.json}`，`build/payload/current.json`指向当前本机构建。**这些hash用于完整性，不是远程更新签名**；Android必须由APK内受信元数据决定期望hash，并在执行任何JS前校验。
- 依赖库存不是完整分发SBOM；当前39项需要人工许可复核（6项缺license metadata、34项无顶层license文件，两者有交集）。不能据此宣称已完成AGPL/第三方分发义务。

复核两次构建的确定性：

```bash
cp build/payload/current.json build/first-payload.json &&
python3 tools/payload/prepare.py &&
cmp build/first-payload.json build/payload/current.json
```

宿主回归会校验并解包到独立临时目录，运行真实官方`server.js`，测试：Basic/CSRF/Host、实际IPv4 loopback绑定、旧配置迁移/env覆盖、防并发启动、五种tokenizer、四种Jimp图像格式、PNG卡片导出导入、聊天完整性检查、settings/secrets落盘、重启与停机快照迁移、远程SSE转发/取消和篡改拒绝。远程API用本地HTTP mock，不需要真实API密钥。

结果：`build/reports/st-host.json`、`st-host.log`；报告明确`scope=linux-host-st-only`。测试使用可丢弃state，不读真实用户聊天。完整Node宿主套件缺payload时会失败，不自动跳过。

### 手工启动宿主服务端

先解包到**不存在的新目录**，不要覆盖现有安装/用户数据：

```bash
python3 tools/payload/verify.py --unpack build/st-local
python3 - <<'PY'
import json,pathlib
root=pathlib.Path.cwd()
pointer=json.loads((root/'build/payload/current.json').read_text())
(root/'build/host-launch.json').write_text(json.dumps({
    'schemaVersion':1, 'payloadRoot':str(root/'build/st-local'),
    'stateRoot':str(root/'build/st-manual-state'),
    'manifestSha256':pointer['manifestSha256'], 'port':18766
}))
PY
node build/st-local/shell/bootstrap.mjs "$PWD/build/host-launch.json"
```

18766仅为宿主示例端口；已有安装的origin不能静默更换。Ctrl-C正常关闭会释放状态锁。手工浏览器需要的Basic凭据存放在私有`stateRoot/transport.json`，不写入启动日志；不要上传此文件。自动回归无需手工读取凭据。

### 启动器的明确边界

- 先用上游原有`addMissingConfigValues()`迁移配置，再施加外层安全策略，并复核二次迁移不改变策略；没有改上游迁移函数/生成逻辑。
- 固定127.0.0.1/IPv4与Basic/CSRF，禁自动开浏览器、模型自动下载、服务器插件、扩展自动更新；不重置用户其他API配置，不另加生成总时限。
- `autoDownload=false`不是禁用本地provider endpoint/隐藏UI；本地模型能力仍不受支持，相关UI策略属于后续集成。可下载tokenizer与用户账号偏好保留上游行为。
- `bootstrap.lock`拒绝并发，正常退出清理；强杀留下的锁**不会由通用脚本盲目删除**。Android层确认旧服务进程已死后的恢复流程尚未实现。当前仅用于受控诊断，不应直接作为生产生命周期实现。
- 120秒只限制启动诊断，不限制生成；bootstrap审查进程环境不能替代Native在启动Node之前清理不可信环境。
- Python解包器是宿主工具，Android提取器/版本切换、WebView/FGS、SAF与正式更新回滚尚未实现。停机复制state的回归不等于完成产品备份/恢复功能。
- 仍按上游每次启动运行Webpack；没有宣称已预编译或消除冷启动成本。宿主回归当前在非Docker Linux运行，不能把容器中`dist/_webpack`路径差异误当Android行为。

## 3. 构建Node共享库

```bash
set -o pipefail
mkdir -p build
python3 tools/build_node.py --jobs 12 2>&1 | tee build/node-build.log
python3 tools/verify_runtime_artifacts.py
```

- 校验官方源码archive hash，只在`build/node-source/`解包和应用锁定patch，不修改ST。
- 首次完整编译耗时较长；失败时修复外层配方/记录补丁后再继续，不吞掉错误。
- `build/runtime/`包含经过strip的库、headers、Node license、文件hash manifest。
- 静态门禁检查AArch64 ELF64、导出的`node::Start`、native依赖白名单及16KB LOAD对齐。host glibc/Termux依赖不得混进APK。
- 已有源码目录的锁变更或缺少来源stamp时会拒绝构建；将旧`build/node-source/node-v26.8.2`移走再重建，不编辑stamp强行跳过检查。阶段内调试记录保存在本地`research/`，不随产品发布。
- `--prepare-only`只解包/应用patch；`--stage-only`只对同一锁的已完成构建做产物整理。两者都不是Android运行验证。

**探针构建差异**：显式禁用Temporal（当前无Rust/Cargo交叉构建链），保留完整ICU/WASM/线程；采用上游Android配方的`--openssl-no-asm`，保留TLS功能。没有宣称此探针拥有所有可选的桌面Node26特性；正式运行时策略仍需复核。

## 4. 构建并静态检查探针APK

```bash
(cd android && ./gradlew :runtime-probe:testDebugUnitTest :runtime-probe:assembleDebug :runtime-probe:assembleDebugAndroidTest)
python3 tools/verify_apk.py android/runtime-probe/build/outputs/apk/debug/runtime-probe-debug.apk
```

Gradle在编译/打包前强制验证runtime manifest；缺库、旧锁、错误ABI必须失败。**不要用`-x verifyRuntime`构建APK**；单独的Kotlin语法编译诊断不是可发布产物。

输出：

- 主探针APK：`android/runtime-probe/build/outputs/apk/debug/runtime-probe-debug.apk`
- 仪器测试APK：`android/runtime-probe/build/outputs/apk/androidTest/debug/runtime-probe-debug-androidTest.apk`

原生入口在独立`:node`服务进程的工作线程执行`node::Start`，使用APK内JNI/libnode，不把可执行node解压到filesDir再spawn。运行结束退出整个服务进程；UI进程不应受影响。`dev.stshell.probe`只是诊断包名，非最终品牌/正式产品ID。

## 5. 设备验证（v0.2支持手机直接完成，无需先连ADB）

### 手机上一键自检

1. 覆盖安装本轮提供的`build/diagnostics/st-node26-selftest-v2.apk`（versionCode=2，仍是非官方诊断包）。自己从源码构建时，用Gradle输出的`runtime-probe-debug.apk`即可。
2. 打开后点击 **“开始完整自检（自动两轮）”**，保持页面在前台并开启网络。
3. 等待 **“本设备两轮自检通过”**，点击 **“复制完整汇总 JSON”**，发送整个JSON，而不是只复制`runs`中的一轮。
4. 也可点击 **“保存汇总 JSON 文件”**，通过系统文件选择器保存UTF-8 JSON；不需要存储管理权限或Termux。此功能只导出诊断报告，不是酒馆数据的SAF导出功能。

新版汇总自动包含实际Android版本/API、arm64/64位状态、系统page size、runtime/probe指纹、两次Node报告及Native exit、runner/UI PID和主线程响应检查。指纹来自打包资产；若系统允许读取自身APK，还会额外记录APK SHA256。未能读取可选APK hash不会把12项capability变成另一套验收标准。

两轮会启动两个不同Node服务进程，等待第一轮进程退出后才开始第二轮。UI与instrumentation使用同一协调器/校验逻辑；旋转不主动重新启动诊断。失败、取消、旧版本结果或系统/页大小变化会明确提示，部分数据不标记为通过。退出无法确认时不允许重复启动，需强行停止此诊断App后重开。

汇总为精简格式，不附数千条header文件hash，避免粘贴报告被截断。4KB/16KB是两种设备运行条件，**两轮运行不等于两种页大小覆盖**；如果同一手机能切换页大小，切换前先保存已有JSON。

### ADB / instrumentation自动化

连接允许USB调试的Android14+ arm64设备，并启用网络：

```bash
adb devices -l
python3 tools/run_device_probe.py --serial YOUR_ADB_SERIAL
```

该命令安装探针、清理旧aggregate、运行真实instrumentation，然后通过**本工程debug包**的`run-as`导出`build/reports/device-<serial>-<pageSize>.json`。没有设备、未授权、测试失败、缺结果均非0退出。它不会访问Termux或其他App的数据。

instrumentation调用同一双轮协调器，核验实际Node版本/平台、所有capability结果、native退出码、服务进程PID变化及UI主线程仍响应。原始运行日志可看：

```bash
adb -s YOUR_ADB_SERIAL logcat -s STNode26
```

完整页大小覆盖需要4KB和16KB的真实arm64设备或arm64系统镜像分别执行上面的命令，再运行：

```bash
python3 tools/verify_runtime_report.py --reports build/reports
```

只检查单一页大小的诊断命令可显式指定`--require-pages 4096`或`16384`，但这**不等于完整M1通过**。源码/lock变更后旧设备报告会被拒绝。报告验证不是抵抗伪造测试报告的远程证明服务，必须以实际运行日志/设备来源为依据。

探针120秒watchdog及仪器测试等待超时仅限制诊断进程，**不是ST生成超时**。本工程不新增生产生成总时限。

### 构建机无法连接手机时：只用预构建APK和ADB

可把主APK与仪器测试APK复制到能连接手机的另一台电脑，无需在那台电脑交叉编译Node。若下载的是`st-node26-selftest-v2*.apk`副本，请替换下面命令的文件名或先按Gradle产物名重命名。以下在两个APK所在目录运行（bash/zsh示例；Windows重定向请保存UTF-8而非UTF-16）：

```bash
adb -s YOUR_ADB_SERIAL install -r runtime-probe-debug.apk
adb -s YOUR_ADB_SERIAL install -r runtime-probe-debug-androidTest.apk
adb -s YOUR_ADB_SERIAL shell am force-stop dev.stshell.probe
adb -s YOUR_ADB_SERIAL shell run-as dev.stshell.probe rm -f files/runtime-probe-device.json
adb -s YOUR_ADB_SERIAL shell am instrument -w dev.stshell.probe.test/androidx.test.runner.AndroidJUnitRunner
adb -s YOUR_ADB_SERIAL shell getconf PAGE_SIZE
adb -s YOUR_ADB_SERIAL exec-out run-as dev.stshell.probe cat files/runtime-probe-device.json > device-4096.json
```

文件名按实际页大小改为`device-16384.json`等；同一手机切换页大小后不要覆盖前一次报告。将JSON带回本工程`build/reports/`，运行上面的报告验证命令。`am instrument`可能在测试失败时仍返回shell状态0，**必须检查测试结果和JSON，不能只看ADB退出码**。

v0.1的“Run capability probe”只能给出单次结果；用户已经回传一份12/12成功及Native退出码0的记录。v0.2应使用上面的一键双轮自检，补齐版本/页大小/指纹/进程信息。完整页大小覆盖和ST/后台功能验收仍分别进行，不能混为一谈。

## 6. 后续边界

- M1基础运行能力、真实ST/tokenizer/图像导入兼容、完整安全策略与后台生成仍是不同门禁，不能互相替代。
- 后续优先官方ST入口+原生生命周期最小适配。WebView断流可能让ST取消远端请求，聊天保存也由前端触发，须按M3.5实测，不能仅靠通知/Node存活宣称成功。
- 真实ST/Node/WebView前台显示已有用户确认；现已加入主动开启的UI进程FGS、活动驱动CPU锁、公共事件/HTTP生命周期观察及文件选择器。后台/锁屏生成与保存尚未验收，完整下载导出/SAF、更新回滚及正式分发也未完成。

## 许可

自写壳/探针/工具按AGPL-3.0-only，见`LICENSE`；Node、V8/GYP、Gradle等保留各自许可，见`licenses/`及上游源文件。不要误称这是官方SillyTavern Android发行版。

这是开发阶段仓库，不是已完成M6的公开发行包。分发APK前须附对应源码、patch、构建说明、完整第三方NOTICE/SBOM；不分发API密钥、用户聊天、私有签名key或本地research数据。完整义务见`docs/plan.md` §11。
