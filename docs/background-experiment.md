# 后台保护实验版 v0.2

用户已反馈v0.1“能正常运行和显示”。据此记录真实ST/Android页面链路的用户确认；**没有据此推断远程生成、保存或锁屏已经通过**。

v0.2新增可手动开启的后台会话保护与观测。它仍须在设备实测，不能靠前台服务存在或wakeHeld=true宣布后台生成可靠。

## APK与操作

- 主包：`build/experiments/st-android-background-v0.2.apk`
- 同包名`dev.stshell.app`，versionCode=2，可覆盖安装前台实验版；不要卸载或清除数据。
- 大小256,032,678字节（约244.2MiB）。
- SHA256：`e26d6ce22208e8f96eb7ca4ff83636cd5eec153f163e66cf2f78c31859773c5c`
- 仪器包：`build/experiments/st-android-background-v0.2-androidTest.apk`，SHA256 `763b3265b9b7c63b4620f63643d6e5ae07f58199a7478360def8a54c584dbff3`。

手机操作：

1. 覆盖安装，启动ST，等待顶部出现**观察器就绪**。
2. 点击**后台保护**，允许通知权限，并确认“会话期间持续通知”的说明。默认不会静默开启。
3. 保护开启后通知会持续存在，**包括会话空闲时**；空闲不持有CPU唤醒锁。可从通知停止当前生成或关闭保护。
4. 先用测试聊天和临时凭据发起一次较长的**文本/聊天生成**，切后台或锁屏，等候后返回。
5. 检查回复是否完整、停止后重新打开聊天是否保留，再点**诊断**复制JSON。
6. 若空闲后仍持续“CPU唤醒中”，先复制诊断，再关闭保护，避免额外耗电。必要时用**电池设置**由你自行调整系统后台策略；App不会偷偷申请/修改电池白名单。

不要划掉最近任务或强行停止来代替普通锁屏测试：这些是主动关闭/强杀场景，本版不自动重新生成或恢复旧流。

## 实现与取舍

### 前台服务为什么在UI进程

- `BackgroundProtectionService`与WebView在同一UI进程，用户主动开启；原Node服务仍在`:server`，通过原有`BIND_IMPORTANT`连接。
- 保护UI/WebView进程的存活优先级，而不是只保护Node、却任由消费流的WebView被回收。仍保留renderer重要性设置，不在onStop调用pauseTimers。
- 前台服务类型选`specialUse`，真实用途声明为**用户开启的本地交互式WebView+Node会话**，不是本机模型推理，也不是一次性data-sync批任务。选择依据是服务用途，不是通过降低targetSdk或冒用类型绕过配额。
- 本项目侧载，但这不意味着获得系统/OEM/Doze豁免；不宣称此设计已通过商店政策审核。系统仍可终止进程。
- 服务`START_NOT_STICKY`，没有开机接收器或自动重新发送生成请求。关闭保护不主动取消ST请求；停止整个服务/移除任务则沿用原停止流程。

### CPU唤醒如何释放

- 用户开启保护后，仅当观测到页面生成、未清理的非取消流处理器、群聊包装任务或服务端生成/保存请求时，持有PARTIAL_WAKE_LOCK。
- 工作结束后保留2秒收尾宽限再释放。这是**资源释放宽限，不是生成超时**；不会取消、缩短或重试API请求。
- 关闭保护、Node/页面失效、service销毁都会释放；保留持续通知及用户停止入口。
- Lint的`WakelockTimeout`警告未被隐藏：当前为活动驱动的无固定生成总时限持锁，错误/丢失活动信号仍可能导致额外耗电，需要设备验证。不要把它当作已经解决了所有电池问题。

### 只观察，不改酒馆生成逻辑

- `runtime/android/generation-observer.js`通过WebView的origin受限、main-frame限定消息桥安装，使用ST的公共`getContext().eventSource/eventTypes`和已有`document.body.dataset.generating`。
- 不重写fetch/Generate/saveChat/stopGeneration。通知“停止生成”只调用已有公共`stopGeneration()`。
- `GENERATION_STARTED`含dry-run/命令中止场景，不能直接当成一个持锁任务；`GENERATION_ENDED`可能早于`saveChatConditional()`完成。
- 因此联合观察DOM、流处理器是否仍存在且未取消、group wrapper状态；正常流处理器在保存后清理，避免一收到ENDED就立刻释放。
- Native消息仅含固定布尔状态、序号、可见性和流事件计数；不传提示词、回复、聊天名、Cookie或API密钥。校验origin/main-frame、当前页面token、递增序号和消息形状，旧页面/多余字段会拒绝。
- 不支持`WEB_MESSAGE_LISTENER`的WebView不会退回宽泛`addJavascriptInterface`；仍可前台用，但不能开启此保护，需更新System WebView。

### Node侧请求观测

- 外层`request-observer.mjs`使用Node26 `diagnostics_channel`的HTTP server request channel，只订阅目标本机端口。
- 观测chat-completions、text-completions、Kobold、NovelAI、Horde文本生成及chat/group保存，监听原始response的finish/close。
- 不读取/记录请求body、headers或查询参数，不替换HTTP处理函数、不增加路由、不改变响应/取消语义。诊断写失败不会抛回HTTP路径。
- 结果在私有`request-activity.json`；Native只使用当前服务PID的数据。
- `generationFinished`仅说明HTTP响应正常结束，**不证明模型结果正确或聊天已保存**。`saveFinished`也不能单凭时间推断对应某次生成；请核对实际回复并重开聊天验证。
- 图像/语音等其他扩展的后台任务未覆盖，不能以聊天实验推断全部能力。

## 看诊断时注意

- `background.enabled/foreground/wakeHeld`表示保护服务/资源状态，不能单独作为成功结论。
- `activity.frontend`：bodyBusy/streamBusy/groupBusy、页面心跳年龄、流事件计数、隐藏期间观察到的增量。后者只计连续hidden采样区间，避免把锁屏前的最后一批误算为后台进度；不是模型token数，也不等于锁屏独有进度。
- `activity.backend`：当前生成/保存请求及完成/断开/失败累计。
- `background.deviceIdle/batteryOptimizationExempt/keyguardLocked`是采样时系统状态；返回前台再复制时锁屏字段可能已恢复false。
- 网页心跳过期不会被偷偷转换成生成超时/自动取消。renderer死亡或连接断开仍可能丢失未保存内容，本版没有重写ST为原生自主生成/结果重放引擎。

## 当前配置与数据提醒

保留仓库现有已提交策略，未擅自改回：`allowKeysExposure=true`、`backups.chat.enabled=false`、lazyLoadCharacters=true、useDiskCache=false。这与最初调研默认不同；诊断不含密钥不代表UI/API禁止查看密钥。继续只用测试数据，勿作为唯一副本。

重建payload是为了匹配该已提交策略。中文首启逻辑也已保留。ST submodule仍无源码修改。

## 验证状态

本机已通过：

- App JVM共14项（7项原边界/语言/真实ZIP测试，7项资源状态机测试）。
- Node宿主共32项，含新增5项前端观察器测试和2项真实HTTP diagnostics_channel测试。
- Python28项；APK签名/ABI/16KB ELF+ZIP及资产hash静态检查。
- Android instrumentation已增加“观察器就绪、通知权限、FGS启停、空闲不持CPU锁”的控制测试，但**尚无设备执行结果**。它不发送模型请求，不代替锁屏生成验收。

尚未通过：锁屏/切后台真实生成与保存、长时间Doze/OEM矩阵、renderer死亡、强杀恢复、所有扩展/全部API提供商、完整导出备份和正式分发安全/许可门禁。

本机命令：

```bash
(cd android-app && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug)
python3 tools/app/verify.py android-app/app/build/outputs/apk/debug/app-debug.apk
node --test tests/host/*.test.mjs
```
