# Android前台实验版 v0.1

这是用于验证**真实ST在Android运行和WebView接入**的实验包，不是已完成M3/M3.5的正式App。没有修改SillyTavern源码，没有改写生成/取消逻辑。

## 产物与手机操作

- 主APK：`build/experiments/st-android-foreground-v0.1.apk`
- 包名：`dev.stshell.app`，与`dev.stshell.probe`探针并存，不覆盖探针。
- Android14+、仅arm64、Node26.8.2；包含完整ST payload，无需Termux/首启下载npm依赖。
- 主APK：254,951,288字节（约243.1MiB）。首次还需解包资源、运行Webpack，建议至少留出1.5GiB空闲空间。
- SHA256：`2e3fd476eacf8b27d43675ec4a9a83721fc74c66ce9192598c898da0c1aa8f63`

操作：

1. 安装主APK，打开后点击**启动**并确认实验提示，保持前台。
2. 首次会依次显示copying/unpacking/starting/checking。解包约20,980个文件，启动时间尚无Android测量，不要把宿主时间当承诺。
3. Native自检通过后自动加载本机ST页面。可以尝试导入测试卡片、配置临时/受限的远程API凭据，做少量前台测试。
4. 如失败，先点击**诊断**，发回JSON。它不包含transport密码、Cookie、配置或聊天。
5. 诊断不足时可查看**启动日志**。日志仅在服务器开始提供服务前捕获，仍可能含敏感配置，分享前自行检查/移除密钥；不会自动复制。

**请勿把它作为聊天数据唯一副本。** 卸载/清除数据会删除私有聊天和API密钥；目前未完成手机端完整导出/备份。

## 目前实现了什么

- 独立`android-app/` Gradle构建，避免更改正在使用的v0.2探针输入指纹。
- 复用M1的原始JNI/CMake和`NativeNode.kt`，在独立`:server`进程的线程中执行Node，不在UI线程运行Node。
- 内置ZIP及manifest绑定在APK的`app-contract.json`；先核验整个ZIP，再核验所有成员路径、大小/hash与库存，全部写为普通文件。
- 版本化解包到`filesDir/runtime/<payloadId>`；发布前验证，已有目录再次验证。修改/损坏的运行目录拒绝启动，不自动覆盖或删除。
- ZIP的Unix symlink等属性不被执行；host构建器已核验regular-only，Android必须先通过固定archive SHA，不能把Java ZipFile当作任意不可信ZIP导入器。
- 用户state在`noBackupFilesDir/st-state`，与代码分离；系统备份/设备迁移已排除。密钥仍是私有文件，不宣称已Keystore加密。
- `native.lock`为内核文件锁。只有取得锁后才清理旧JS `bootstrap.lock`/ready标记，避免用PID猜测或盲目删锁。
- 端口安装级持久化，凭据保持不变；冲突时失败，不静默迁移origin。
- Native验证版本、未认证401、CSRF缺失403/有效204及OpenAI tokenizer，再让WebView加载。
- 单个WebView通过MutableContextWrapper在Activity重建时重新挂载；不会主动在onStop暂停其计时器或销毁页面。
- 本机同源Cookie/JS/DOM storage、文件选择器；外站链接仅在用户点击时交系统浏览器。
- 服务器/renderer退出可见报错，重启需用户操作，不自动重发生成请求。
- 初始config降低运行期日志等级；外层`runtime/android/entry.mjs`仅增加启动诊断及退出记录，不拦截fetch、修改ST路由或生成逻辑。

## 明确未完成/安全边界

1. **没有Android FGS/wake lock，也没有后台/锁屏持续生成保证。** 当前为Application绑定的服务；只作为前台试验。更高renderer优先级不能替代后台验收。
2. **HTTP Basic/WebView认证仍须真机验证。** Chromium回调给的是host、不含port（已核查`aw_http_auth_handler.cc`）。这里只对原生发起的首次可信本机加载提供一次凭据，后续挑战取消，依赖浏览器origin级缓存；不向JS传密码、不使用宽泛原生JS接口。
3. **这不是互相认证的TLS通道。** 本机端口不是UID隔离；Binder/ready验证降低风险，但尚未证明服务退出后的端口接管竞态已完全防住。正式安全门禁仍需考虑本机服务身份验证/固定证书等设计。
4. WebView限制直接访问外站。`shouldInterceptRequest`不是完整网络防火墙，重定向等路径仍须专项验证；不能宣称已阻断所有恶意重定向。仅用临时测试数据/凭据，不作为高信任日常环境。
5. Blob/认证下载导出未完成，麦克风/相机授权暂拒绝；部分直接访问外站的扩展、图片、OAuth流程可能不可用。
6. 不支持全局扩展对运行目录的变更；下次完整性检查会拒绝这些额外文件。不会偷偷丢弃它们，恢复/迁移功能待后续实现。
7. 仍运行上游Webpack、仍做完整性扫描；没有启动性能承诺。
8. M1完整页大小/重复运行验收和M3/M3.5设备测试仍未关闭；本包只是新增验证载体，不是用“写好了代码”替代验收。
9. 39项npm许可资料待复核，M6对应源码/SBOM/分发流程未完成。源码/patch/构建脚本均在本工程；不要把实验APK单独当正式发行版公开传播。

## 本机复核

保持既有SDK/JDK环境变量，项目根执行：

```bash
python3 tools/payload/verify.py
(cd android-app && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug)
python3 tools/app/verify.py android-app/app/build/outputs/apk/debug/app-debug.apk
node --test tests/host/*.test.mjs
```

已有：6项App JVM测试通过，包括真实payload的完整提取/复核；28项Python及25项Node宿主测试通过；APK签名、ABI、16KB ELF/ZIP、payload和Node库hash检查通过。

尚无设备连接，Android instrumentation未执行。设备可用时：

```bash
export ANDROID_SERIAL=YOUR_DEVICE
(cd android-app && ./gradlew :app:connectedDebugAndroidTest)
adb -s "$ANDROID_SERIAL" exec-out run-as dev.stshell.app cat files/foreground-smoke.json > build/reports/foreground-smoke.json
```

测试类`dev.stshell.app.AppSmokeTest`需等待真实ST自检及WebView chatInput/publicApi出现才通过；测试超时只限制诊断，不是生成总时限。无设备或失败不得算作通过。
