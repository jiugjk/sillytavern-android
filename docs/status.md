# 实施状态

## 当前任务：P0 应用图标

- 交付 `build/releases/sillytavern-android-0.3.0-p0.apk`，版本0.3.0 / versionCode 3。
- 来源：ST `public/img/logo.svg`，背景沿用`apple-icon-512x512.png`。
- 已完成七档mipmap、自适应前景/背景与monochrome、最近任务、通知小图标/彩色大图标。
- 按用户统一要求清理启动器同类文案/注释，保留版权和许可证原文；未改ST源码，未实施P1/P2。
- 图标6项、App JVM14项、Python28项、Node32项通过；APK静态资源/签名/对齐检查通过。设备图标测试已编译，等用户确认实际显示。
- 文件与原因、素材指纹：`docs/p0-icons.md`。

## 先前阶段记录（方案A）

用户已回传一次Android arm64探针成功结果，并进一步反馈真实ST App“能正常运行和显示”。据此确认用户设备上的前台基础链路，但不推断远程生成/保存、双页大小或锁屏成功。现已构建v0.2后台保护实验版，仍等设备结果。

## 已完成的工程内容

- 外层git工程、`.gitmodules`、官方ST固定commit与lockfile hash；ST工作树干净，无ST patch。
- Node26.8.2官方源码下载/校验、Android14+ arm64共享库构建配方、Node构建patch及来源说明。
- Gradle8.13/AGP8.13.2/Kotlin2.3.21/NDK30固定配置，诊断包`dev.stshell.probe`。
- JNI `node::Start`、独立`:node`服务进程、最小原生诊断界面、原子JSON报告。
- 12项运行时capability探针、两次服务进程运行的instrumentation、静态ELF/APK检查及设备报告收集/验证工具。
- 新增`tools/payload/`：官方ST归档+锁定生产依赖、确定性ZIP、逐文件库存/校验、受限解包；`runtime/mobile/`提供外层启动与配置策略。
- 新增真实ST宿主回归：认证、实际监听、配置迁移、WASM、PNG卡片、状态持久化/恢复、SSE转发与取消及篡改拒绝。
- v0.2探针新增手机一键双轮自检、真实系统/API/page size采集、精简指纹汇总、复制/保存JSON。UI与instrumentation共享协调器和每轮校验器；探针仍不加载ST payload。
- 独立`android-app/`：真实payload、复用M1 JNI、`:server`进程、内核状态锁、Native健康检查、同源WebView及文件选择器；保留当前已提交的默认中文逻辑。
- v0.2新增用户主动开启的UI进程specialUse FGS、可见通知/停止按钮、工作驱动CPU锁、主frame受限活动桥和Node HTTP生命周期计数；无fetch/生成/保存逻辑替换。

## 已执行与收到的证据（区分来源）

| 检查 | 状态 |
|---|---|
| `python3 tools/verify_project.py` | 通过：官方submodule SHA、干净工作树、package-lock与wrapper校验 |
| Python工具单元测试 | 28项通过；原M1工具16项，新增payload路径/类型/hash/大小限制/原子发布/平台依赖选择等12项 |
| Node宿主probe集成测试 | 通过，内部12项capability全部成功；**mode=host，不是Android成绩** |
| Gradle项目配置 | 通过 |
| Kotlin主程序与instrumentation源码编译 | 通过（独立语法编译诊断，未用此结果冒充APK构建） |
| 缺设备/缺报告门禁 | 均非0退出，符合fail-closed要求 |
| Node arm64共享库完整构建 | **通过**；113,725,528字节，导出符号/依赖白名单/16KB ELF静态检查通过；日志`build/node-build.log` |
| Node patch复核 | 3份patch对原始锁定archive文件全部可应用，与实际构建的patched文件逐字节一致 |
| 探针APK/JNI与测试APK完整构建 | **通过**：`assembleDebug`、`assembleDebugAndroidTest`成功 |
| APK静态验证 | **通过**：debug签名、minSdk34/target36、仅arm64、16KB ELF+ZIP对齐、原样libnode hash、probe源码指纹、许可证打包检查 |
| Android Lint（v0.2） | 0 errors、8 warnings（工具/target更新提示、仅arm64、保留的API自检和少量诊断文字拼接）；未整体suppress |
| 新增Kotlin/JVM校验器 | 6项通过、0 skip：完整结果/cpus=0、错误平台/Node、失败/字符串true、缺少/重复/skip测试、nonce、native失败及PID复用 |
| v0.2一键双轮UI/协调器 | 编译/静态检查通过；旋转、取消、复制/保存及实际双轮流程仍待手机验证，不把JVM测试当Android执行 |
| Android arm64基础运行 | **收到用户手动回传：Node26.8.2，12/12 capability通过，Native退出码0**；不是本机构建机ADB采集 |
| Android连续两次进程运行 | 新版已提供自动采集，但尚未收到完整双轮汇总/runner证据 |
| 4KB / 16KB页设备覆盖 | 本次回传不含page size，不能分配到任一页大小类别或声称完整覆盖 |
| 新增配置策略单元测试 | 6项通过：策略优先级、无新增生成超时、原型污染/异常数据拒绝、启动参数、凭据持久化及symlink拒绝 |
| 真实ST宿主回归 | 16个场景通过（Node test计数含父测试为17）；全量Node宿主套件共24项通过、0 skip |
| 确定性payload构建 | 两次独立`npm ci`/打包得到相同payloadId、manifest SHA和ZIP SHA；`cmp current.json`通过 |
| 官方源码一致性 | 归档中的全部官方tracked文件与固定submodule逐文件一致，运行后payload文件内容和库存未变 |
| 前台实验App构建/静态检查 | 已通过：真实payload与Node库入包、签名、仅arm64、16KB ELF/ZIP、资产hash；**Android执行未验证** |
| App JVM测试 | 14项通过：7项既有边界/中文/真实payload测试，7项资源状态机测试 |
| App启动日志包装器 | 1项新增Node测试通过：只记录启动、保留输出语义及退出码；全量Node宿主现为32项通过 |
| 实验App Lint（v0.2） | 0 errors、37 warnings；保留WakeLock无固定超时提示、Context/WebView持有风险及工具/文字警告，未隐藏；需设备资源释放/耗电验证 |
| 前台ST/WebView设备反馈 | 用户明确反馈能正常运行与显示；没有补造API生成或保存成绩 |
| instrumentation | 已编译`AppSmokeTest`及FGS通知/启停/空闲释放控制断言，未在构建机设备执行；这些控制断言本身不证明锁屏生成 |
| 观察器宿主测试 | 新增5项JS观察器、2项Node diagnostics_channel真实HTTP测试通过；含dry-run、保存前流对象保留、取消、群聊、隐私及写失败不影响请求 |
| 新增宿主启动测量 | 冷启动12.236秒、相同state重启4.111秒、复制state到新路径后3.912秒；包含全量hash检查和运行期Webpack，**不是Android成绩** |

## 用户回传的Android单次证据

报告时间：2026-09-13T15:44:30.938Z–15:44:32.697Z；nonce `0eee6ef1936540a6b63b78ee83083f40`，PID `20487`。归档：`research/user-reports/2026-09-13-0eee6ef1936540a6b63b78ee83083f40.json`（保留用户JSON值及Native exit，外加来源标签；不是自动采集的完整device报告）。

已做一致性核验：12个测试名称与探针要求完全一致、全部passed、无skip；Node版本匹配当前锁，`platform=android` / `arch=arm64`；Native exit与JS报告的PID/nonce一致、退出码0、error为null。

- ESM/TLA、原子文件操作、crypto、ICU/Unicode、WASM/SIMD、Worker、HTTP流、DNS、TLS证书/hostname检查、外网HTTPS均报告成功。
- `execPath=/system/bin/app_process64`符合JNI嵌入式运行方式。
- `cpus=0`不是该探针失败：Android可能限制CPU信息读取，`availableParallelism=3`且Worker通过。它不表示物理CPU为0核或3核；完整ST/Webpack在Android上的行为仍需测。
- `Temporal=undefined`符合探针禁用Temporal的已记录配置，不是新发现的异常。
- 报告内探针区间约1.759秒，**不含完整APK启动/资源解包等，不是冷启动耗时**。
- 完成时RSS约210.2MiB、JS heapUsed约10.6MiB；RSS包含承载Node的Android进程开销，不是Node增量、峰值或整App总PSS。

缺失字段没有猜测补填：实际Android API/系统版本、page size、runtime/probe manifest指纹、第二次运行与runner PID。证据保持`manual-single-run`类别，没有写入`device-*.json`或修改正式验收器放宽门禁。当前构建机ADB仍无设备。

## 构建过程中发现并记录的问题

1. GYP host/target生成目录冲突：Node patch `0001`。
2. Linux host mksnapshot缺少POSIX/simulator trap实现与atomic链接：Node patch `0002`，配合真实host GCC libatomic目录；不启用Android目标的trap handler、不打包host库。
3. Android OpenSSL误选x86汇编：采用Node官方Android配置同款`--openssl-no-asm`；保留SSL功能。
4. zlib遗漏Android CPU features实现导致OpenSSL链接失败：Node patch `0003`链接固定NDK自带实现，不硬编码CPU能力。
5. 当前无Rust/Cargo cross工具链：探针显式`--v8-disable-temporal-support`，不依赖configure自动降级；完整ICU/WASM/线程保留。这个可选特性差异需在正式runtime策略前复核。

细节与移除条件见`patches/README.md`，所有来源/版本/patch hash在`upstream.lock.json`。没有降低Node主版本，也没有要求安装Termux。

## 未完成，不作承诺

- **完整M1尚未通过**：已有单次Android基础探针成功回传；仍缺系统/页大小与构建绑定信息、重复进程运行汇总，以及真实ST在Android上的依赖兼容与生命周期覆盖。
- 前台运行/显示已有用户确认；后台FGS/唤醒锁只是新增实验实现，锁屏生成与保存、Doze/OEM/renderer失败等尚未实测通过。完整下载导出、更新回滚和正式分发仍未完成。
- HTTP Basic/WebView初次挑战、重定向、服务退出端口接管竞态等安全路径仍未完成设备对抗验证；HTTP loopback不是相互认证TLS。只使用测试数据/临时凭据，详见`foreground-experiment.md`。
- 旧配置alias可能在上游迁移时覆盖新策略键；启动器先调用上游原有迁移函数、再施加策略并复核，已覆盖legacy config/env测试，无ST patch。
- 前台实验Service通过内核`native.lock`独占状态后才清理旧JS锁，避免PID猜测；实现与设备崩溃恢复仍需验证。宿主停机复制state回归不是完整产品备份功能。
- 仅禁用了本地模型自动下载，不声称已移除本地provider UI/端点；39项npm许可资料仍待复核。
- 保留当前仓库已提交配置：allowKeysExposure=true、聊天自动备份关闭、lazyLoadCharacters=true/useDiskCache=false；不是本轮擅自切换，已告知风险。旧payload与该配置不同，已重建。
- 探针需保持界面可见；其120秒watchdog限制的是诊断进程，不是产品生成时长。
- 后台生成仍按M3.5验证浏览器消费流、服务端abort及聊天保存链，不删除酒馆的正常取消/超时逻辑。

## 已生成的测试产物

当前为 **v0.2-m1-selftest / versionCode 2**，可覆盖安装原探针。

- 主APK：`build/diagnostics/st-node26-selftest-v2.apk`（同Gradle的`runtime-probe-debug.apk`）
  - 大小：118,191,079字节（约112.7MiB），**不含ST前后端/模型，不是完整酒馆App**。
  - SHA256：`67b14ff67706f4254f7b82482d4555e6dd0fd63aa9ea9ebd83c76514195181c7`
- 仪器测试APK：`build/diagnostics/st-node26-selftest-v2-androidTest.apk`
  - 大小：2,160,237字节；手机按钮自检不需要另装此包，仅ADB自动化使用。
  - SHA256：`220685b7ebfa3753d8c467450b0d6fdafdd160747d3fd71def3bd2836150133d`
- `build/runtime/lib/libnode.so` SHA256：`1a54789a32811c50e6847c0a9102aa88aece2eee3777741017e062667018180b`

APK包含许可/开发说明入口及第三方license assets；这不代替正式分发M6要求的完整对应源码/SBOM流程。Node源码及所有适配/构建脚本已在本工作区提供，公开转发前应一并提供对应源码。

## 新增payload产物（不是APK）

当前目录：`build/payload/5b788fede97725e45b4dd2398550c76fd6c4c296b10c2c7c4482669a91a1d030/`。

- `payload.zip`：135,606,685字节（约129.3MiB），展开文件内容355,843,900字节，20,980个文件，667个生产依赖，21个独立WASM；另有JS内嵌WASM。
- ZIP SHA256：`da49aa6eb430fc19f8992aa802ef3908d3388cc819c11b4f00bb708d6b73b45b`
- `manifest.json` SHA256：`fe3b32159a48ad30de876fab7e04bd0a06a4459410e7f39cf9489d3a4d805e23`
- 未检测到平台native addon；没有修改ST文件、未移除其默认资源，也未把测试state/API凭据打包。
- 许可复核队列39项：6项缺license metadata，34项未找到顶层license文件（有交集）；不等于断言这些包无授权，也不等于已完成分发SBOM。
- 报告：`build/reports/st-host.json`、`python-tests.log`、`node-tests.log`、`payload-repeat.log`。

payload与M1探针独立：本次v0.2只改善诊断采集，仍不包含ST payload。宿主启动命令见根README §2.1。

## 新增前台实验APK（包含ST，和探针并存）

- `build/experiments/st-android-foreground-v0.1.apk`
- 包名`dev.stshell.app`，大小254,951,288字节（约243.1MiB）。
- SHA256：`2e3fd476eacf8b27d43675ec4a9a83721fc74c66ce9192598c898da0c1aa8f63`
- 测试APK：`build/experiments/st-android-foreground-v0.1-androidTest.apk`，SHA256 `3a2d021a91dc933c72bb3051ea3e9863d49fac53db57c8118228003d7f2703ef`。
- 启动前校验全部资源，Native自检后再加载ST；无FGS/wake lock、无后台保证、未完成Blob/认证下载导出及音视频权限。
- 仅编译/JVM/静态检查通过，手机操作与安全限制见`docs/foreground-experiment.md`。原v0.2探针已再次核验，指纹/文件hash未变。

## 当前后台保护实验APK v0.2

- `build/experiments/st-android-background-v0.2.apk`，256,032,678字节，versionCode=2，可覆盖原App。
- SHA256：`e26d6ce22208e8f96eb7ca4ff83636cd5eec153f163e66cf2f78c31859773c5c`
- 同目录仪器包：`st-android-background-v0.2-androidTest.apk`，SHA256 `763b3265b9b7c63b4620f63643d6e5ae07f58199a7478360def8a54c584dbff3`。
- 当前payload：`e26445e23e7c9534b00512090d21f4028d14ea71f3be4655fe57e941bd28e4b8`；包含现有已提交策略，不含用户聊天。
- 说明/安全边界/字段含义：`docs/background-experiment.md`。此前v0.1和旧payload信息保留作历史记录。

## 下一步

覆盖安装v0.2，等待观察器就绪后主动开启“后台保护”，确认持续通知，再做测试聊天的锁屏生成/重开后持久化验证，发回“诊断”JSON。只有观察到完整回复与保存才推进M3.5，不以通知或wakeHeld=true代替成功。

完整M1资料也仍可直接覆盖安装v0.2主APK，点击“开始完整自检（自动两轮）”，等显示通过后复制整个汇总JSON或保存文件发回；无需先连ADB。它自动补充系统/页大小、实际指纹和两次运行信息，但不会凭空产生另一种页大小覆盖。

ADB自动化仍可用：

```bash
python3 tools/run_device_probe.py --serial YOUR_ADB_SERIAL
python3 tools/verify_runtime_report.py --reports build/reports
```

默认完整报告门禁要求4KB和16KB覆盖。本次手动回传有效推进了基础运行验证，但不能把未提供的页大小、重启或后台生成成绩补成通过；host测试与静态ELF验证也不能替代这些证据。
