# SillyTavern Android：调研与实施计划

> 初版为仅调研计划。用户已选择方案A并批准继续：M1探针/库已构建，已收到Android arm64单次12/12通过及Native退出码0的用户回传；完整页大小/构建指纹/重复进程验收待补齐。可独立验证的M2 payload/宿主准备已完成。未放行Android UI或后台生成门禁。实际状态见`docs/status.md`，命令见根README，ST源码仍未修改。
> 调研时间：2026-09-13 UTC。结论绑定下述 commit，不把滚动分支名称当版本锁。
> 状态标记：**已核实**＝源码/安装产物或明确注明的平台实测；**待验证**＝不能据此承诺 Android 可用；**待确认**＝需要项目所有者决定。

## 1. 结论、推荐与待确认项

### 1.1 关键结论

1. **当前上游不是“必须 Node 26”。** `package.json`：SillyTavern 1.18.0，`engines.node: >= 20`，ESM。Docker 用浮动的 `node:lts-alpine3.23`，也不是 Node 26 的版本声明。用户已确认 Node 26 是自己的要求，本方案保留 Node 26 目标，未经再次确认不降级。
2. **现成 nodejs-mobile 不能直接作为合格运行时。** 本次查询官方最新发布为 `v18.20.4`（2024-10-07），main 的 `src/node_version.h` 也还是 18.20.4，低于上游 engines，且 Node 18 已结束上游安全维护。不能用忽略 npm engine 警告来验收。
3. **还存在独立的 16 KB 页兼容阻塞。** 下载官方 Android 发布包后，`arm64-v8a/libnode.so` 的全部 ELF LOAD 段对齐是 `0x1000`（4 KB）。不能把它声明为已满足现代 Android 的 16 KB 兼容要求；仅调整 APK ZIP 对齐不能修复 ELF。
4. **主要业务依赖是 JS/WASM，而非必须交叉编译的 addon。** 按 lockfile 安装生产依赖后，未发现 `.node`、`.so` 或 `binding.gyp`；`tiktoken` 是 WASM；`sillytavern-transformers` 2.14.6 使用 `onnxruntime-web`，不依赖 native `onnxruntime-node`/`sharp`。这降低了移植难度，但不证明旧 nodejs-mobile 能运行全部代码。
5. **数据重定向不需要改上游源码。** 使用 standalone 模式、绝对路径 `--configPath`/`--dataRoot` 即可。APK assets 仍不能直接当 Node 文件系统：需要把代码/资源展开到沙盒，并处理代码目录的少量可写路径。
6. **安全上的易错点：`listen: false` 时 basicAuth 根本不挂载。** 如需抵抗其他 Android App 访问本机端口，可用上游现有参数 `listen: true` + 显式绑定 `127.0.0.1` + 关闭 IPv6 + basicAuth；`listen: true` 并不必然意味着绑定所有接口，必须同时钉死地址并实测。
7. **无系统 git 不是全站不可用。** 核心聊天可继续；内置 git 仅实现 clone，不能替代扩展管理里的 fetch/pull/branch/checkout。

### 1.2 推荐：门控式原生壳，而不是直接套旧 nodejs-mobile

**推荐 A：Kotlin 原生 WebView + 独立 Android 服务进程 + JNI 嵌入 Node 26；生成期间配合前台服务及经过验证的流式请求生命周期。** nodejs-mobile 的嵌入模式可借鉴，但必须先验证 Node 26 的 Android shared-library 构建及嵌入；这是高风险运行时工程，不是几行 JNI 包装。

- A 的 M1 未通过，不推进大规模 UI/插件适配，也不声称独立 APK 方案已可交付。另设 M3.5 后台流式生成门禁，不能把“Node 活着”当作生成成功。
- **Termux 已排除为交付方案**：用户要求独立 APK。保留 C 仅为技术对照、包配方参考；不得在 A 失败时擅自改为要求用户另装 Termux。
- 不推荐 B 作为 Android-only 首选：Capacitor 不提供 Node，它增加一层集成而未消除任何服务端阻塞。
- **只使用远程 API**：不提供本地 embedding/分类/caption/ASR/TTS 模型推理，不打包或自动下载这些模型。核心 tokenizer、卡片图像处理仍在手机运行，因此 WASM 兼容门禁不能删除。
- 目前不认定必须修改 SillyTavern。先按零 patch 验证，只有真实失败且外层解决不了时才提出最小 patch。

### 1.3 已确认需求与工程边界

已确认，记录在 `docs/decisions.md`：

| 项目 | 决策 |
|---|---|
| 运行时/交付 | Node26、独立APK，不降级、不依赖Termux |
| 系统/架构 | Android14起（`minSdk=34`），只支持`arm64-v8a` |
| API | 仅远程API；核心tokenizer/图像处理仍在本机 |
| 后台 | 切后台/锁屏继续生成，接受生成期间持续通知 |
| 分发 | 侧载，不以Play/F-Droid上架为当前交付条件 |
| 生成时长 | 不由壳新增总时限；生成参数、正常结束/取消和API超时沿用ST/provider语义 |

此前询问最长生成时间只是为耐久测试，不应作为必须由用户定义的需求。现在需求足以收敛架构，不再要求用户给“最长生成多久”。测试场景时长不是产品超时，也不是永久保活承诺。

`targetSdk`与`minSdk`不同；前者、NDK/FGS类型、具体测试设备和性能预算仍需工程验证。APK按构建期准备运行时/依赖验证，未获授权不引入首启自动下载运行组件；包体和启动速度先报告实测，不把估算当已批准SLA。侧载不消除Android权限/后台/ELF页兼容限制；Android14起也包含更高系统版本，不能只测Android14。

必要测试不得“跳过即通过”；已明确不提供的本地模型推理标为产品范围外，而不是测试遗漏。

### 1.4 后台继续生成：新增的独立风险

**前台服务只是必要的生命周期措施之一，不足以保证上游生成链继续。** 补充源码检查：

- `src/endpoints/backends/chat-completions.js` 多处用 `request.socket.on('close', ...)` 调用远端请求的 `AbortController.abort()`；`src/util.js:732–778` 的 `forwardFetchResponse()` 也会在下游 socket close 时销毁远端流。
- `public/script.js:onFinishStreaming()` 调用 `saveChatConditional()`；`saveChat()`（约7395行起）由浏览器发起 `/api/chats/save`。**当前生成结果不是完全由Node自主消费并落盘**。
- 因此 WebView 暂停、renderer回收、页面重建导致连接关闭或JS无法处理流时，即使Node服务还活着，也可能中止远端生成或丢失未保存内容。

计划分两步验证：先在不修改上游的条件下维持Activity/Service分离、避免后台销毁WebView/主动暂停网络，使用受控长SSE验证实际设备行为；若不能可靠通过，研究**外层持有生成连接、持久暂存结果、回前台恢复/提交**的机制。后者涉及请求身份、provider事件格式、reasoning/tool calls、取消、聊天完整性和去重，不能把简单代理缓冲当作完整解决方案。优先外层适配；若原前端恢复协议确实无法在外层接入，再提交最小patch提案供审批，不在本轮实现。

生成开始于可见界面时启动合法FGS，生成期间显示已获用户接受的持续通知并提供取消入口；完成/取消/失败后释放任务所需资源。按真实任务状态评估partial wake lock，结束必释放，资源管理的防泄漏措施不转化成生成总时限。不能用保持屏幕常亮满足锁屏要求，也不能声称wake lock消除了Doze网络限制。

具体FGS类型按Android14+实际用途选择，并验证系统版本/targetSdk组合的权限和时间限制；侧载不需要Play审核，但不能绕过系统约束。例如较新系统/targetSdk组合对dataSync的时间配额，不是ST参数或用户应指定的“最长生成时间”。不为绕过限时而随意冒用specialUse类型；系统确实强制停止时应正确清理并提示中断，不能承诺无限运行。

**不重做酒馆生成逻辑**：首选原样运行+最小生命周期适配。连接持有/恢复只是零patch方案实测失败后的调查候选，不是当前已决定要实现的大型生成引擎；需要改变上游语义时先报告，不擅自实施。

验收看**锁屏期间远端生成未被意外取消、结果完整可恢复、回前台无重复生成/重复消息且最终保存正确**，不是只看通知或进程。用户强制停止、系统强杀、断网是另外的中断/恢复测试，不承诺原流在这些条件下仍不中断。

## 2. 调研范围与证据

### 2.1 本地布局、版本与环境

当前调研工作区：`/home/opc/sillytavern-android`。

- `research/SillyTavern/`：从官方 `staging` shallow clone；未改 tracked 源码。
- commit：`c0a417b240c640037bab69529aa1e0c466c69397`；提交日期 2026-09-07。
- `research/host-baseline/`：由该 commit 的 `git archive` 展开，用于安装依赖/宿主机验证，不是 fork。
- `research/host-state/`：宿主机试验配置和数据；**不是产品数据、不要随 APK/源码发布**。
- 宿主机 Linux x86_64，Node v26.8.2，npm 11.19.1；生产安装使用 `npm ci --omit=dev --ignore-scripts --no-audit --no-fund`，667 packages，命令成功。
- Android SDK 在 `/home/opc/android-sdk`；JDK 21 在 `/home/opc/opt/jdk-21.0.12.1+1`，当前未加入 PATH；**`adb devices -l` 无设备**，未进行 Android 构建/真机测试，也未下载模型权重。
- 外部网页/源码、发布包、命令结果在 `research/`；这些都是调研材料，不作为未来壳仓库的源码依赖锁。

### 2.2 已做的宿主机验证（不是 Android 成绩）

数据：`research/host-results.json`，日志：`cold-loopback.log` / `warm-loopback.log` / `loopback-basic-auth.log`。

| 验证 | 结果 |
|---|---|
| 启动使用独立绝对 config/dataRoot，禁用自动开浏览器、自动下载模型 | 成功创建 `data/default-user`、`_storage`、`_webpack` 等；源码目录没有承载用户主数据 |
| 首次进程启动 → `/csrf-token` 可响应 | 9.547 秒；其中 Webpack 7.671 秒 |
| 相同数据/编译缓存下重新启动进程 | 1.514 秒；Webpack 0.452 秒 |
| `/`、`/lib.js` | 200 |
| `POST /api/ping`，有 session、无 CSRF header | 403 |
| 同 session、有效 `X-CSRF-Token` | 204 |
| `basicAuthMode=true` + `listen=false`，不带 Basic credentials | `/csrf-token` 200，证明 Basic 不生效 |
| `listen=true` + `listenAddressIPv4=127.0.0.1`，Basic 开启 | 无 credentials 401，正确 credentials 200；CSRF 仍生效 |
| 启用 hostWhitelist，Host 为 `untrusted.invalid` | 403 |
| 默认 forwarded whitelist 下，伪造非白名单 `X-Forwarded-For` | 403 |
| gpt2 / llama / claude / llama3 / openai encode，输入“你好 Android!” | 均 200；结果保存在 JSON |
| PNG / JPEG / WebP / AVIF 的 16×16 encode/read | 使用上游 fetch patch 和实际 JPEG options 后均成功；见 `host-image-results-correct-options.txt` |

图像探测特意保留了失败记录：脱离启动链单独 import Jimp 会遇到 `fetch(file://...)` 不支持；JPEG 未传 `jpegColorSpace` 会遇到参数校验失败。遵循上游 `src/fetch-patch.js` 和缩略图调用参数后通过，**不能把这两个孤立探测失败误报为 Android 移植缺陷**。

未验证：真实聊天 API/SSE、WebView Cookie/Basic challenge、真实 ONNX inference、语音/相机/下载、后台稳定性、Android 内存、Android 启动耗时。

### 2.3 可复核源文件

以下相对路径均指上述固定 commit：

| 主题 | 主要证据 |
|---|---|
| 入口/安装 | `package.json`、`package-lock.json`、`server.js`、`start.sh`、`Dockerfile`、`docker/docker-entrypoint.sh` |
| CLI/配置/监听 | `src/command-line.js:44–383`、`src/config-init.js`、`src/server-directory.js`、`src/server-startup.js`、`default/config.yaml` |
| 中间件/启动顺序 | `src/server-main.js:107–207, 283–360, 478–490` |
| 数据 | `src/constants.js:1–62`、`src/users.js:113–128, 556–640, 689–703`、`src/endpoints/settings.js`、`src/endpoints/secrets.js` |
| git | `src/git/client.js`、`src/endpoints/extensions.js`、`src/plugin-loader.js:236–294`、`src/util.js:136–173` |
| WASM/图像 | `src/transformers.js`、`src/endpoints/tokenizers.js`、`src/jimp.js`、`src/fetch-patch.js` |
| 构建 | `webpack.config.js`、`src/middleware/webpack-serve.js`、`docker/build-lib.js` |
| 安全 | `src/middleware/{whitelist,basicAuth,hostWhitelist}.js`、`src/users.js:135–194`、`public/script.js:640–710` |
| 许可证 | 根目录 `LICENSE`，尤其 §1、§4–6、§10、§13 |

固定源码浏览入口：<https://github.com/SillyTavern/SillyTavern/tree/c0a417b240c640037bab69529aa1e0c466c69397>。

## 3. 上游服务端的真实运行模型

### 3.1 启动流程

1. `start.sh` 先 `cd` 到脚本目录，检查 npm，设置 `NODE_ENV=production`，每次执行 `npm install --no-save ... --omit=dev --ignore-scripts`，再 `node server.js "$@"`。
   - 壳 **不执行 start.sh**，也不在 Android 首启执行 npm。构建期 `npm ci` 锁依赖，运行期直接嵌入 Node 入口。
   - `npm start` 本身只有 `node server.js`；没有必须执行的 native postinstall。
2. `server.js` 解析 CLI；`initConfig()` 设置配置路径并补齐/迁移 config；创建 data root；设置 `globalThis.DATA_ROOT` / `COMMAND_LINE_ARGS`；`process.chdir(serverDirectory)`；动态 import `src/server-main.js`。
3. `server-main.js` 加载上游 WASM file-fetch 补丁，创建 Express，挂载安全/session/静态和业务路由。
4. 初始化 `_storage`、默认用户及目录；迁移用户数据/模板/公开覆盖文件；安全检查；导入默认内容；初始化设置、统计、插件；**等待 Webpack 编译前端库**；最后 HTTP/HTTPS listen。
5. listen 完成后可自动开浏览器、写 heartbeat、发 `SERVER_STARTED` 事件。
6. `SIGINT`/`SIGTERM` 触发统计/插件/cache 清理，然后 `process.exit()`。嵌入时这可能结束宿主 Android 进程，所以建议 Node 位于独立 `:server` 进程。Node 没有可靠的“同进程 stop 后再次 node::Start”承诺。
7. 入口捕获 critical import 异常时只打日志；不要用“进程还活着/退出码为 0”判断成功。以有限超时内的 HTTP readiness + 期望版本 + 安全握手判断。

Docker 是 Linux 部署参考，不是 Android 运行时：安装 `git/git-lfs/gcompat/tini/su-exec` 等；构建期 `npm ci --ignore-scripts`、预编译；entrypoint `npm run init` 后 `node server.js --listen`。**不要照抄它的 `--listen`、Alpine node 二进制或 Linux node_modules native 产物到 APK。**

### 3.2 默认 host / port / 参数

默认配置的有效监听为 **HTTP `127.0.0.1:8000`**：`listen=false`、IPv4=true、IPv6=false、SSL=false。

- `listenAddress.ipv4=0.0.0.0` / ipv6=`[::]` 仅在 `listen=true` 时用于选择绑定地址。
- `listen=false` 且打开 IPv6 时绑定 `::1`；不能看到 YAML 的 `0.0.0.0` 就说默认暴露 LAN。
- `browserLaunch.enabled=true` 是配置默认值，Android 壳必须关闭。
- `--global` 使用 `env-paths` 平台目录，**会忽略 `--configPath` 和 `--dataRoot`**；壳不使用 global 模式。
- 配置优先级：CLI 非 null 值 > `SILLYTAVERN_*` 环境变量 > YAML >代码 fallback。例：`extensions.models.autoDownload` → `SILLYTAVERN_EXTENSIONS_MODELS_AUTODOWNLOAD`。
- 路径解析和后续 `chdir` 存在先后顺序，避免相对路径歧义：外层先准备目录，所有 state 路径都传绝对路径。

完整 CLI 已保存 `research/cli-help.txt`，分组如下：

| 类别 | 参数 |
|---|---|
| 配置/数据 | `--configPath`、`--dataRoot`、`--global` |
| 网络 | `--port`、`--listen`、`--listenAddressIPv4`、`--listenAddressIPv6`、`--enableIPv4`、`--enableIPv6`、`--dnsPreferIPv6`、`--enableKeepAlive` |
| 浏览器 | `--browserLaunchEnabled`、`--browserLaunchHostname`、`--browserLaunchPort`、`--browserLaunchAvoidLocalhost` |
| 安全 | `--whitelist`（不是 `--whitelistMode`）、`--basicAuthMode`、`--disableCsrf`、`--corsProxy` |
| TLS | `--ssl`、`--certPath`、`--keyPath`、`--keyPassphrase` |
| 出站代理 | `--requestProxyEnabled`、`--requestProxyUrl`、`--requestProxyBypass` |
| 其他 | `--heartbeatInterval`、`--help`、`--version`；旧 `--autorun*`/`--avoidLocalhost` 为弃用别名 |

### 3.3 数据目录与沙盒重定向

`getUserDirectories(handle)` 把模板中的每个目录拼为 `DATA_ROOT/<handle>/<relative-dir>`。即使未启用多用户，也使用 `default-user`，不是直接 `data/characters`。

| 数据 | 重定向后的相对位置 |
|---|---|
| 角色卡 | `<dataRoot>/default-user/characters/`，通常带嵌入 metadata 的 PNG |
| 聊天 | `default-user/chats/<character>/...jsonl`；群聊 `default-user/group chats/`；群组定义 `groups/` |
| 用户设置/连接配置 | `default-user/settings.json`，以及 `OpenAI Settings` / `TextGen Settings` 等模板目录 |
| API secrets | `default-user/secrets.json`，并非 Android Keystore 加密数据库 |
| 世界书、素材、向量索引、扩展 | `default-user/worlds`、`assets`、`vectors`、`extensions` 等 |
| 用户账号元数据 | `<dataRoot>/_storage/`（node-persist，非 native SQLite） |
| 会话签名 | `<dataRoot>/cookie-secret.txt`；应随安装持久保留，不应每次启动随机重建 |
| 临时上传/缓存 | `<dataRoot>/_uploads`、`_cache`（模型/下载 tokenizer）、`_webpack/<hash>`；另有 `_errors`、`_public` 等资源 |
| 备份 | 用户目录中的 `backups`；另有历史/全局 `backups/` 在 serverDirectory，必须区分 |
| 浏览器端状态 | WebView 自己的 Cookie、localStorage、IndexedDB；**不包含在 `--dataRoot` 中** |

配置文件也会由上游写回以补齐新字段，必须放在可写沙盒内。使用 Android `Context.filesDir` 得到路径，不硬编码 `/data/data/<包名>`，也不把角色/聊天写到 `cacheDir` 或公共 Download。

**dataRoot 不等于所有写入都已搬走：** `PUBLIC_DIRECTORIES` 会确保 serverDirectory 下的 `backups/`、`public/.../third-party/` 等存在；`plugins/` 固定在 serverDirectory；历史数据迁移也会读旧目录。建议每版本完整解包到私有 `filesDir/runtime/<payload-id>/`，预建必需目录；禁止运行期全局扩展/插件写入的策略须真实执行，不能仅声明代码目录只读。若未来允许这些功能，外壳负责保留可写内容/私有 symlink，而不是让升级覆盖它们。

SAF 的 `content://` 不是 Node 可用的 POSIX 路径；导入需由外层读取流、复制到私有暂存区，再交给 HTTP 上传；导出通过 SAF 写用户选定 URI。不要申请全盘存储权限来绕过此问题。

## 4. git、child_process、shell 清单

### 4.1 直接影响业务的调用

| 调用点 | 当前行为 | 普通 APK 无 git/shell 时 |
|---|---|---|
| `start.sh` / Docker entrypoint | bash/sh、npm、文件/权限命令、Linux 工具 | 不使用；改由构建期准备 + 外层直接启动 Node，不需要 ST patch |
| `src/util.js:getVersion()` | `command-exists.sync('git')`；simple-git revparse/show，比较本地 tracking ref；**不是自动联网 git pull 更新 ST** | 探测/调用异常被捕获；版本仍从 package.json 取得，commit/branch 可为 null。壳额外显示构建锁里的 SHA |
| `src/git/client.js` | auto/system/builtin，builtin 使用 isomorphic-git；接口只有 clone | builtin HTTP(S) clone 有希望保留；**即使显式 builtin，仍先 commandExistsSync('git')**，需验证探测失败能正常返回 false |
| `src/endpoints/extensions.js:/install` | 经 createGitClient 做 shallow clone，并读取 manifest | builtin 路线可工作，需 Android 网络/文件实测；不是必须有 git |
| 同文件 `/update`、`/version`、`/branches`、`/switch`、版本检查 helper | 仍直接 simple-git fetch/pull/branch/checkout/log | **直接不可用**，通常变成 endpoint 错误；`git.backend=builtin` 不解决 |
| 扩展 `/discover`、`/delete`、`/move` | 主要是 fs 操作 | 不因缺 git 必然失效，仍受权限和产品禁用策略影响 |
| `src/plugin-loader.js:updatePlugins()` | 插件启用且 autoUpdate 开启时检查 git，再 simple-git pull | 缺 git 时自动更新跳过；纯 JS 插件加载未必失败，但任意第三方插件可能 spawn/native，无法统一承诺 |
| `src/server-main.js:postSetupTasks()` → `open` | spawn 平台浏览器/xdg-open 等；默认浏览器自动打开 | 禁用，原生外层 loadUrl / Intent 代替；不能依赖桌面 `open` 逻辑 |

本 snapshot 的核心 `src/` 未发现直接 import child_process；实际子进程依赖主要通过以上 npm 包引入。`git-lfs` 出现在 Docker 系统包安装中，不是已发现的核心启动必需调用。

### 4.2 传递依赖与构建路径，不混淆“存在”和“会执行”

- `command-exists`：POSIX 路径执行 `command -v ...`，底层 `execSync`/`exec`，依赖 shell。同步探测有 catch，但 Android 上 shell 缺失/权限行为仍要验证。
- `simple-git`：spawn 外部 `git`；将 Linux git 复制到 assets 再 chmod 不是可行移植方法。
- `open`、`default-browser`、`default-browser-id`、`run-applescript`：桌面外部命令链，自动开浏览器关闭后不应走到。
- Webpack JS API 本身不需要 webpack CLI。它的 Terser minimizer 使用 `jest-worker`；当前配置优先 `worker_threads`，库里也含 child-process fallback。**不能把 worker_threads 等同于 spawn，也不能漏掉 fallback。** M1/M2 要验证 worker 能力；必要时构建期预编译或最小 patch 限制并行。
- 安装产物还含 `webpack/bin`、`protobufjs/cli`、`commander`、`cross-spawn`、`foreground-child`、`update-browserslist-db`、busboy 测试中的 child_process 引用。这些大多是 CLI/维护/测试路径，并非已证实的业务启动必需命令；清单在 `research/child-process-inventory.json`，扫描不代表完整动态调用图。
- 不将任何插件“支持 Android”默认为真；插件需单独登记权限、native、子进程和网络能力。

## 5. native addon / WASM 兼容评估

审计对象是 **当前 lockfile + `--omit=dev --ignore-scripts` 的实际安装产物**，不凭 npm 包名称猜测，不把未来扩展的依赖算进已验证范围。

| 依赖（锁定版本） | 实际产物/路径 | nodejs-mobile 上“能否编出来/运行” |
|---|---|---|
| `tiktoken` 1.0.22 | Rust 实现已编成 WASM；主 `.wasm` 5,593,287 B，lite 1,073,364 B；JS loader 用本地资源 | **无需 Android node-gyp 编译。** 保留 exports、encoders 和 wasm 的相对布局，验证 WebAssembly/内存/Unicode；宿主机 encode 已通过，Android 待测。只有重新构建 WASM 才需要 Rust/WASM 工具链 |
| `@agnai/sentencepiece-js` 1.1.1 | Emscripten WASM 以 base64 嵌入 `dist/index.js` | 无 `.node`；无需 NDK 编译包。检查嵌入文件、模型路径、首次初始化内存；宿主机 llama tokenizer 已通过 |
| `@agnai/web-tokenizers` 0.1.3 | Emscripten WASM 嵌入 `lib/index.js` | 无 addon；同上。宿主机相关 tokenizer 已通过，不能据此承诺 Android |
| `sillytavern-transformers` 2.14.6 | `onnxruntime-web` 1.14.0 + Jimp 0.22.12；`src/backends/onnx.js` 实际只 import ONNX_WEB，provider 为 wasm | **没有 onnxruntime-node / sharp 要交叉编译。** 不要照搬文件里遗留的“Node 用 native ONNX”注释。Android 真正风险是 WASM SIMD、内存、模型大小、性能及老 ORT 与新 Node 的兼容 |
| ONNX WASM | ST transformer `dist` 和 `onnxruntime-web/dist` 各有 scalar/simd/threaded 4 个 wasm，共约 73 MiB | ST 已设置 `numThreads=1` 且 wasmPaths 指向自己 dist；仍需 CPU/WASM feature test。单线程降低线程问题但推理更慢。去重/裁剪前必须证明路径与 fallback 不再使用，不能先删再说 |
| 主 Jimp 1.6.0 的 `@jimp/wasm-{png,jpeg,webp,avif}` | `@jsquash` WASM codecs（含部分多线程变体），BMP/GIF/TIFF 为 JS | 无 libvips/native sharp；Node 必须经过上游 `fetch-patch.js` 才支持限定目录下 `.wasm` file URL。Jimp WASM 应位于真实 serverDirectory 内，外置 node_modules symlink 可能触发安全路径检查，先不采用 |
| `vectra` 0.2.2、node-persist、write-file-atomic | JS、本地文件、Node fs/crypto | 不需要 Android SQLite addon；仍需验证 rename/fsync/权限与数据恢复 |
| `protobufjs` 6.11.4 | 本生产依赖锁中发现 hasInstallScript 的包；安装脚本被忽略 | hasInstallScript 不代表 native addon；上游同样 ignore-scripts。当前安装/启动通过，不需要盲目启用全部 npm scripts |
| Node/V8/OpenSSL/ICU 本身 | `libnode.so` + JNI + 可能的 C++ runtime | **真正必须为 Android/Bionic/目标 ABI 构建和维护的 native 部分。** 新 addon 若引入则另查 N-API/Node ABI、NDK、16 KB 与许可，不能复制 Linux 产物 |

补充约束：

- nodejs-mobile FAQ 明确警告部分 process/os/Unicode API 限制、同进程单 runtime、spawn 权限问题。尤其 tokenizer 对 Unicode 行为敏感，不能只验证 `console.log()`。
- 上游已经使用 `structuredClone`、fetch/Response/Blob、ESM/TLA 等；运行时要通过真实包 import 和业务测试，不仅比较版本字符串。
- `extensions.models.autoDownload=false` **只禁止缺失模型自动下载，不是禁用 transformer import 或所有推理 endpoint**。模型不在 cache 时调用会失败。
- `enableDownloadableTokenizers=false` 是另一个独立开关；缺少模型专用 tokenizer 会 fallback，可能改变计数准确性。
- 分类、embedding、caption、ASR、TTS 默认模型及 cache 在 `src/transformers.js`/配置中；配置的 embedding 默认 `Cohee/jina-embeddings-v2-base-en` 会覆盖代码 fallback。模型是可从数十/数百 MB 到更大的额外下载，不能计入一个“零成本离线功能”承诺。
- 当前 **未证明任何一个重点 npm 包在 Android 上“编译失败”**；可确认的是它们通常不需要 native 编译，Android 执行需要 M1/M4 验证。

## 6. localhost WebView 的安全与浏览器行为

### 6.1 使用一个真实 HTTP origin

WebView 应直接加载 `http://127.0.0.1:<固定安装级端口>/`，HTML、静态资源、fetch、SSE、Cookie 均来自这个 origin。不要把 `public/` 放进 `file://` 或 Capacitor 的 `https://localhost`，然后跨域请求另一个 Node 端口。

- 开启 JavaScript、DOM storage、第一方 Cookie；不为同源访问开启第三方 Cookie。
- CSRF：前端先 GET `/csrf-token`，服务端把随机 token 写入 cookie-session，后续 POST 带 `X-CSRF-Token`；Cookie 为 httpOnly、SameSite=Lax。保留 CSRF 与 session secret。
- 若每次换 host/port，localStorage/IndexedDB 的 origin 会改变。Cookie 本身不按端口隔离，多个 localhost 服务还可能发生 cookie 名碰撞；不能把换端口当安全隔离。
- 端口应由外层为安装选定并持久化，冲突时明确报错/走经过验证的迁移，不每次随机换；Basic credentials 每安装高熵生成。应用 ID、端口策略在决策阶段确认。
- 默认 CORS：origin `['null']`、methods `['OPTIONS']`、credentials=false。它不是允许 Capacitor origin 的配置；同源 WebView 无需放宽 CORS。CORS 不是认证，也阻止不了原生恶意 App。

### 6.2 whitelist / basicAuth / Host

- IP whitelist 默认 `127.0.0.1`、`::1`，对本机 WebView正常；**其他有网络权限的 Android App 也可连接 loopback**，它不是按 UID 隔离的服务。
- `enableForwardedWhitelist` 默认 true；直连壳建议配置 false，并保持 forwardedHeaders/SSO 关闭，避免伪造头影响判定。无反向代理时不需要信任这些头。
- basicAuth 默认 false；上游只在 `cliArgs.listen && cliArgs.basicAuthMode` 为真时挂载。默认 `listen=false` + “打开 Basic”并不能保护数据。
- **零 patch 候选安全启动策略**：强制 CLI `--listen true --listenAddressIPv4 127.0.0.1 --enableIPv4 true --enableIPv6 false --basicAuthMode true --whitelist true --disableCsrf false --corsProxy false --ssl false`；关闭浏览器自动打开；配置随机 Basic credentials、hostWhitelist=true、循环地址白名单、禁止 securityOverride。
  - 同时验证实际 socket 只绑定 loopback、LAN 连接失败；地址拼错会触发上游 fallback，必须 fail-closed 检查，不允许“启动成功但暴露所有网卡”。
  - WebView `onReceivedHttpAuthRequest` 只对确切允许的本机 origin/realm 提供凭据，处理失败次数，验证子资源、fetch、SSE 都能使用认证；不能仅给第一次 loadUrl 加 Authorization header。
  - 凭据不得通过 URL/query、页面 JS 或宽泛 Java bridge 暴露，也不能传给外站重定向。native readiness 用同一凭据。
  - 这是 localhost HTTP 身份鉴别，不等于磁盘加密或抵抗 root/同 UID 注入。
- `hostWhitelist.enabled` 默认 false；建议开启。上游 host validation 已把 localhost/IP 作为安全 host；任意 DNS 名仍需阻断，防 DNS rebinding。此项不代替身份鉴别。
- 如 WebView Basic challenge 无法稳定使用，先验证上游账号登录备选，再设计受认证的本机入口；**不得通过关闭 CSRF/关闭认证“修复”**，也不能先建一个所有本机 App 都能绕过的裸后端。

### 6.3 Android 壳额外负责的部分

- `INTERNET`；仅 loopback 必要范围允许 WebView 明文 HTTP，其他流量默认 HTTPS，不全局开启 mixed content/忽略证书错误。Android Network Security Config 不自动约束 Node 自己的 OpenSSL/raw socket 出站策略。
- URL 导航白名单按 scheme+host+port 精确匹配，外站用系统浏览器/Custom Tabs；`file://` 访问关闭。不暴露任意文件/命令的 `addJavascriptInterface`。不向第三方 iframe 授予原生能力。
- 上传需 `WebChromeClient.onShowFileChooser`、SAF；下载包括带认证/session 的响应、blob URL，不能只接 URL 字符串就交 DownloadManager；不泄露凭据。
- 麦克风/相机需要双层权限（Android runtime permission + WebView permission request）及 origin 校验。Web Speech API/系统语音识别在 WebView 不保证存在；网页语音、系统 TTS、服务器端 transformer 是三套不同能力。
- OAuth popup/callback、剪贴板、软键盘/viewport、音频播放需单独测试。未经适配，不把桌面浏览器表现视为 Android 已支持。

## 7. Android 障碍清单：解决方案与风险

风险含义：**阻断**＝不解决无法交付目标；**高**＝核心安全/数据/运行稳定性；**中**＝功能/性能影响且有替代；**低**＝可明确隔离的限制。

| 障碍 | 解决方案（外层优先） | 风险 / 直接影响 |
|---|---|---|
| nodejs-mobile 18 < upstream >=20，且不满足已确认的 Node26 要求 | M1 先验证受维护的 Node Android libnode；参考 Node/Termux 补丁移植，但不把 Termux executable 当 libnode；不允许降 engine 检查过关 | **阻断**；当前现成包不能作为受支持的 ST runtime |
| 官方 libnode ELF 4 KB 对齐 | 以适当 NDK/链接设置重建全部 native 库；AGP/ZIP 对齐一起查；4KB/16KB设备双测 | **阻断**（要求16KB的设备/分发）；旧二进制直接失败风险 |
| Android Bionic 与桌面 glibc/musl 不兼容，执行私有可写目录二进制受限 | JNI 加载 APK 内 `jniLibs/<abi>/*.so`；Node 单独线程；不要 assets 解压 node/git 后 exec | **阻断**；普通 Linux node、Docker镜像不能直接使用 |
| Node runtime 生命周期、process.exit、进程单实例 | `:server` Android Service 进程；JNI 内工作线程；IPC 只用于状态/控制；重启整个服务进程，超时/退避/崩溃日志 | **高**；否则服务退出可能杀 UI，重复启动崩溃 |
| Android 后台/Doze/OEM杀进程/前台服务限制 | Android14+，生成期间FGS和持续通知已确认；类型/权限及较新系统限额仍须验证。资源跟随任务释放，不新增壳级生成时限；specialUse不能当万能保活 | **高**；强制停止/系统强杀/系统限额触发后不保证原流继续 |
| WebView掌握流消费和聊天保存，下游关闭会取消远端请求 | 按§1.4及M3.5先验证原样生命周期；失败则研究外层连接持有/持久暂存/恢复协议，必要patch先审批 | **阻断**；仅Node保活不满足已确认的后台生成要求 |
| APK assets不是 fs，dataRoot未覆盖全部可写路径 | 版本化解包+校验+原子切换；config/data 独立 filesDir；目录例外见§3.3；不在公共存储运行 | **高**；首启失败或更新覆盖聊天/扩展 |
| Webpack 每次启动编译，缓存跨路径不可直接搬 | 先测真实成本；研究构建期预编译。Docker forceDist 输出在 Android不自动命中；host filesystem cache含路径，不能简单复制声称免编译；必要时最小 patch支持明确预构建 lib | **高**（启动/内存）；编译未结束时服务不会 ready |
| WASM/ICU/Worker 能力与保留依赖 | Runtime capability tests + 真实tokenizer/图像 + 启动必需模块import；本地模型推理已排除，不要求ONNX推理验收，也不自动下载模型 | **高**；核心tokenizer/图像失败仍影响聊天计数和卡片，远程API不能消除这些依赖 |
| 缺 git 与 shell | 配置 builtin clone、禁自动更新；壳分发受审计扩展。扩展更新管理可日后实现外层服务，但不是免费兼容 | **中**；**原有扩展更新/版本检查/分支切换直接不可用** |
| npm/第三方服务器插件任意执行 | 所有依赖构建期安装；插件默认保持上游关闭；审核后逐项支持 | **高**；**依赖git/python/ffmpeg/可执行文件或不兼容addon的插件不可用**；纯JS不一刀切判死 |
| 本机端口不按 App 隔离、Basic在listen=false失效 | §6认证+loopback绑定+Host/CSRF；服务与组件 exported=false；安全测试必须独立于UI存在 | **高**；不解决可被其他App读取聊天/设置 |
| WebView跨域/存储、CSRF | 同源loadUrl，固定安装origin，Cookie持久化；不放宽CORS/禁CSRF；处理进程重启/登录过期 | **高**；403、登录循环、端口切换丢浏览器状态 |
| 文件导入/导出、音视频、OAuth | SAF、WebChromeClient、权限origin校验、浏览器callback策略 | **中**；**未实现前桌面式下载/麦克风/弹窗流程可能直接不可用** |
| secrets/自动备份/卸载 | 私有存储；明确排除含密钥的系统备份；显式加密导出/恢复策略。API secrets目前明文文件，不宣称“已Keystore加密”；卸载会删私有数据 | **高**（数据与隐私） |
| staging与npm/模型供应链变化 | 固定SHA、lockfile、integrity、SBOM、构建清单；升级跑全矩阵；模型/扩展独立许可与hash，APK更新签名校验 | **高**；不在手机盲目git pull/npm update |
| 侧载更新/FGS/targetSdk要求 | 已确定侧载；构建期锁版本、签名APK更新，核验系统权限与后台规则。Play/F-Droid上架审批不属于本轮交付要求 | **中**（分发）/ **高**（系统后台规则）；侧载不绕过系统限制 |
| LAN/电脑localhost模型误解 | App里127.0.0.1指手机，不是PC；远端API需要可达地址/TLS/授权；LAN支持如需启用是另一个需求，不改变server绑定 | **中**；PC localhost服务不会自动可用 |

### 7.1 明确的功能边界

- **有望原样保留、必须实测**：Web聊天UI、角色卡/聊天记录/设置、世界书、远端LLM请求与流式回复、已有纯前端扩展。
- **无系统git时确定缺失**：上游扩展管理的更新/状态查询/列分支/切分支；启动页git commit详情退化。builtin安装clone是单独的待测能力，不把整个扩展系统判为不可用。
- **不提供桌面运行环境**：shell启动/更新脚本、Docker、依赖外部程序的插件、Electron壳不会在普通APK中直接可用。
- **本地AI在本产品中不提供**：用户已选择仅远程API。禁止本地模型自动下载，配置使用远端provider并明确本地选项不受支持；`autoDownload=false`不是endpoint禁用开关，如何真实限制入口须在M4验证。保留核心tokenizer/图像WASM和启动所需模块，不能直接删掉仍被import的transformers依赖。
- **纯离线不等于离线LLM**：ST是UI+代理服务，不自带主聊天大模型。离线管理卡片/聊天可成立；远端生成、翻译、图片生成等仍需要相应服务。
- 若决定减少能力，应由外层明确禁用/显示说明；不要留下可点击但必定500的入口而宣称兼容。现有配置无法细粒度禁用时，先要求方案审批，不能偷偷改上游HTML/JS。

## 8. 三个方案对比

### 8.1 包体与启动数字的口径

下面 Android 数值是**工程估算，不是测试结果/承诺**。比较场景：依赖随包、不含模型/用户数据/系统WebView、未大幅裁剪。现已确定Android14+、单`arm64-v8a`、侧载；具体设备性能及SLA尚未实测确定。

可复核的实际基线：

- 原始 tracked源代码/资源展开约76MiB（clone共113MiB，.git约37MiB）。生产node_modules约336MiB磁盘占用，20,000个文件，实际文件字节约280MiB。
- 独立 `.wasm` 合计91,819,340 B（约87.6MiB），未计JS内嵌WASM。
- `host-payload-unpruned.tar.gz` 为126,124,993 B（约120.3MiB），包括未裁剪ST源码/资源与生产依赖，不含模型；只是payload参考，不是APK。
- 官方旧runtime Android zip：57,287,354 B（含3个ABI和headers）。arm64 libnode原始62,475,584 B、zip压缩18,232,894 B；新Node/full ICU/16KB重建后大小会变。
- Android可能采用不压缩且页对齐的native库，因此不能只把libnode的ZIP压缩大小加到payload上宣称APK体积。ABI split可省去不需要的库，universal APK会明显更大。
- **M1新增实测**：当前Node26 arm64库113,725,528字节（约108.5MiB）；仅运行时探针APK118,102,326字节（约112.6MiB，不含ST）。因此下表A的未裁剪完整包预算由初始150–220MiB上调至约230–280MiB；最终仍须以M2真实payload测量，不能把探针大小当完整App大小。

| 维度 | A：JNI/libnode + Kotlin WebView | B：Capacitor + Node原生插件 | C：Termux + WebView/浏览器伴侣 |
|---|---|---|---|
| 真正运行Node的位置 | 壳的`:server`进程/原生线程 | 仍需自建Node插件，或移植nodejs-mobile Cordova集成 | Termux进程及其私有prefix |
| 独立APK | 可以，**但先解决新版runtime** | 可以，**同样先解决新版runtime** | 不可以；需要Termux及安装包环境 |
| APK估算（单ABI、未裁剪） | 约230–280MiB；已按M1新runtime实测修正，完整payload待M2验证 | 与A同量级，另加约数MiB～十余MiB壳/插件开销 | 伴侣壳约5–20MiB，不含Termux/Node/ST；不能用这个数与A总包体直接比 |
| 安装后占用 | 代码/依赖/解包/缓存约0.5–1GiB预留，另加APK、模型和用户数据；升级双版本还需额外空间 | 类似A | Termux官方README称F-Droid APK+bootstrap安装约180MB；再加Node/git/ST/npm缓存，整体同样可能0.5–1GiB或更大 |
| 进程冷启动、已有payload与编译缓存 | 暂按3–15秒预算范围；WebView首帧另测 | 同量级，多一层bridge初始化，通常不改变主瓶颈 | 环境已安装且直接node启动约同量级；不应每次运行npm install |
| 首次安装后启动 | 解包+无缓存Webpack可能20–90秒或更久，低端机未定 | 类似A | 网络pkg/npm下载可数分钟或更久，不能与A预装payload的首启混比 |
| 上游更新 | 构建期更新固定SHA+lock，跑CI后发APK；目标零ST patch | 同A，还需同步Capacitor/插件/Gradle | git/npm原生可用，更新最接近官方路径；产品化仍建议锁commit，避免漂移 |
| 维护成本 | 初期高；特别是Node26移植与安全更新。壳层Android-only相对可控 | 高到很高：全部A风险+插件生命周期+跨origin/bridge集成 | 服务端适配低，安装/权限/双App支持成本中等，后台/OEM问题仍高 |
| git功能 | 部分不可用，见§4 | 同A | 安装git后大多有原生运行条件 |
| 推荐定位 | **独立APK推荐方案，Node26及后台生成双门控** | 已有Capacitor团队/跨平台需求时再考虑 | **仅技术对照，已因独立APK要求排除交付** |

这里只估算启动到本地UI；不包含远端模型首token、模型下载、本地模型加载。M5必须区分安装首启、杀进程后的冷启、Activity恢复三种情况，输出p50/p95与峰值PSS。

### 8.2 Capacitor 为什么不是 Node 兼容层

- Capacitor的JS在WebView，不具备Node fs/Express/child_process。仍要JNI/libnode或另一进程提供服务端。
- 官方支持部分Cordova插件，但**不执行Cordova hooks/自动配置**；nodejs-mobile-cordova依赖构建hook/资源复制/native模块处理，不能认为 `npm install` + `npx cap sync` 即兼容。
- Capacitor `server.url`/cleartext/allowNavigation 的相关声明标为开发用途、非生产推荐。把Node地址设为server.url只能作为实验起点，正式应用须审核原生加载、导航和插件信任边界。
- 若坚持WebView本地静态origin + Node不同origin，会引入cookie/CORS/CSRF/streaming代理成本；优先保证整个ST页面同源。

### 8.3 Termux 注意事项

- 当前Termux `packages/nodejs/build.sh` 为26.4.0，`nodejs-lts`为24.18.0；包仓库镜像是否已发布该ABI需 `pkg show` / `node -v` 现场确认，不能仅凭配方认定设备已可安装。
- Termux executable链接其prefix下的依赖，涉及 `/data/data/com.termux/files/usr`、Bionic补丁、ICU/OpenSSL等；把node文件搬进另一个App的filesDir不会自然成为可嵌入运行时。
- 伴侣App不能读取Termux私有home；通过经过授权的RUN_COMMAND/受认证HTTP或显式导入导出协作，不申请root、不放宽私有目录权限。RUN_COMMAND需要用户授权及Termux的allow-external-apps设置，不能静默代开。
- Android12+的phantom process/CPU限制、Doze和厂商杀后台仍存在。termux-wake-lock不是永久存活保证。
- ST Android文档的“Play版不维护”提示已落后于Termux README（后者有独立实验Play分支说明）；使用Termux自身最新分发指引，避免混装不同签名源的插件。

## 9. 推荐壳工程结构与边界

以下为**目标结构**。获批后已建立外层git仓库、官方ST submodule、`android/runtime-probe`、`runtime/probe`、构建/验证工具及测试；尚未实现的app/runtime-service等模块仍按后续里程碑推进。实际目录及可执行命令见根`README.md`。

```text
sillytavern-android/
  docs/
    plan.md
    decisions.md                 # 用户确认的Node/API/ABI/能力/渠道/SLA
    compatibility.md             # 每个ST/runtime版本的验证矩阵
  upstream/
    SillyTavern/                 # 官方git submodule，只推进gitlink，不编辑
  upstream.lock.json             # commit、lockfile hash、runtime/toolchain/hash
  android/
    gradlew, settings.gradle.kts
    app/                         # Kotlin UI、WebView、SAF、权限、导航、安全
    runtime-service/             # :server进程、Binder状态、JNI生命周期
    runtime-probe/               # 不加载ST也能执行的运行时门禁APK
    .../src/androidTest/         # security/storage/lifecycle/benchmark仪器测试
  runtime/
    bootstrap.mjs               # 仅外层入口：设置argv/env/目录后import官方server.js
    manifests/                  # runtime来源、hash、ABI、build info；不存API密钥
  config/
    mobile-policy.yaml          # 外层配置策略，不覆盖/修改上游default/config.yaml
  patches/
    README.md                   # patch理由、上游范围、移除条件、审批
    sillytavern/                # 初始为空；只容纳证明必要的patch
    node-runtime/              # 若自建Node，需要独立记录运行时移植补丁
    dependencies/              # 若必须改依赖，同样记录版本/理由，禁止静默改node_modules
  tools/
    ...                         # prepare/build/audit/release工具，后续里程碑实现
  tests/
    host/                       # payload/安全/兼容回归，Node内置test runner
    fixtures/                   # 非敏感卡片、聊天、ONNX小模型及许可
  licenses/                    # AGPL/Node/npm/WASM/模型/扩展NOTICE
  build/                       # 生成的patched staging tree、payload、SBOM，不入库
  research/                    # 本轮试验材料，未来不打入产品、不当submodule
```

已选用submodule：`upstream/SillyTavern`固定在官方`c0a417b240c640037bab69529aa1e0c466c69397`；外层git仓库和`.gitmodules`已建立，`tools/verify_project.py`验证commit、lockfile hash与干净工作树。`research/SillyTavern`仍只是保留的调研clone，不是产品源码来源。

设备布局建议：

```text
Context.filesDir/
  runtime/<payload-id>/         # 展开的官方ST + 生产node_modules + 资源
  state/config.yaml            # 外层生成/上游可迁移的配置
  state/data/                  # DATA_ROOT：用户数据、secrets、cache、cookie secret
  state/runtime-selection      # 当前版本和固定安装origin，不含明文URL凭据
  exports-staging/              # 有界、可清理的导入导出暂存
Context.cacheDir/
  extraction-staging/           # 可以丢弃的临时解包
APK nativeLibraryDir/
  libnode.so, JNI .so, ...      # 不把native可执行文件解压到filesDir后spawn
```

- 解包验证hash/路径穿越/磁盘空间，成功后原子切换；保留上一可用payload但不允许旧payload自动读已不兼容的新数据。
- 首次启动复制config并施加策略，升级补齐时重新验证关键安全值。`--dataRoot`/`--configPath`不需要patch。
- 上游源码不放shell适配代码。bootstrap不得偷偷重写上游函数/绕过安全；如果真的需要改变上游行为，进入patch流程。
- 用户数据迁移前备份；回滚必须将payload与相容的config/data备份作为一组，不能只回滚APK。失败中止必须可恢复。
- 同一运行时保持单实例；Activity旋转/重新进入只连接现有Service。生成期间FGS/持续通知已确认，类型与系统限制需验证；前端流消费/保存链不能遗漏。壳不新增生成总时限。

### 9.1 Patch政策与目前候选

**目前ST patch仍为零。** M1已出现的Node构建补丁独立记录于`patches/README.md`及锁文件，只应用于生成的Node源码，不影响ST。下列ST适配仍是失败后才评估的候选，未实现：

| 候选 | 只有在什么情况下才需要 | 优先不patch的做法 |
|---|---|---|
| 预编译frontend支持 | 无缓存Webpack严重超出SLA或Android worker/os能力失败，且无法复用合法缓存布局 | 先原样运行测量；构建期生成lib，避免携带host绝对路径cache |
| git能力降级/细粒度禁用入口 | 产品要求扩展系统开启但无git操作不能展示500，且外层策略无法处理 | 禁自动更新/明确功能说明；受审计扩展随payload交付。要实现完整git backend是额外项目，不是小patch |
| 嵌入式关闭钩子 | 外层控制服务进程仍无法触发可靠flush且实测导致丢数据 | 单独进程，协作signal/有界等待/备份；不得直接杀UI进程 |
| 后台流恢复接入 | M3.5证明WebView生命周期导致远端abort/未保存，且外层无法接入完整恢复协议 | 先验证外层连接持有/持久暂存/恢复；不直接删除上游abort逻辑，否则会破坏正常取消并造成额外API费用 |
| 依赖WASM loader/worker修复 | 真实设备上复现、同版本宿主基线正常，并已排除遗漏上游fetch patch/资源布局 | 保持原包布局、配置、正确初始化顺序 |

每份patch必须标明问题复现、上游SHA/依赖版本、影响范围、安全理由、验证命令、向上游提交链接及移除条件。构建时只对`build/`中的副本执行`git apply --check`后应用；submodule持续保持clean。升级无法干净应用就fail closed，不模糊匹配、不静默吞冲突。运行时移植patch与ST patch分开维护。

## 10. 分阶段里程碑与本机验收命令

### 使用说明

- M0命令可运行。M1新增的真实命令见下节及根README；后续M2–M6仍为未来接口，不能把计划中的文件/测试名称当作已实现或已通过成绩。
- 在项目根运行；Android命令需设置已确认的`ANDROID_SERIAL`（adb设备ID）和`APP_ID`（debug包名）。当前没有设备，不能验收M1/M3/M4/M5。
- 未来统一环境（本机已有路径）：

```bash
export ANDROID_HOME=/home/opc/android-sdk
export JAVA_HOME=/home/opc/opt/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
adb devices -l
```

- 未来`tools/*`验收工具必须在断言失败/缺设备/必要能力未测试时非0退出，并保留报告；不能只打日志或将skip算通过。测试数据必须使用隔离临时目录，不动真实用户聊天。

### M0：锁定事实与需求（本轮调研及需求收敛已完成）

交付：本文、固定clone、依赖审计、宿主基线及`decisions.md`。Android14+/arm64/侧载/持续通知已确认，不再要求用户指定生成最长时长。A为目标，C已排除交付；Android运行能力留给M1验证，不算本轮已通过。

**现在可运行：**

```bash
cd /home/opc/sillytavern-android
git -C research/SillyTavern rev-parse HEAD
git -C research/SillyTavern status --porcelain
node --version
(cd research/host-baseline && node server.js --help)
python3 -m json.tool research/host-results.json
readelf -lW research/mobile-arm64-libnode.so
```

验收：SHA为本报告值、原clone clean；确认engines差异；JSON展示上述HTTP/tokenizer结果；旧libnode LOAD对齐0x1000。报告只是历史证据，若要重验宿主启动，可在一个终端执行：

```bash
cd /home/opc/sillytavern-android/research/host-baseline
mkdir -p ../manual-state
NODE_ENV=production SILLYTAVERN_EXTENSIONS_MODELS_AUTODOWNLOAD=false \
node server.js --configPath /home/opc/sillytavern-android/research/manual-state/config.yaml \
  --dataRoot /home/opc/sillytavern-android/research/manual-state/data \
  --port 18766 --listen false --browserLaunchEnabled false
```

另一个终端：`curl --fail http://127.0.0.1:18766/csrf-token` 返回JSON；完成后Ctrl-C。此命令仅复现默认loopback基线，不是生产安全配置。M0仅关闭调研与需求记录，不代表批准开始本轮实现或已通过Android验证。

### M1：运行时生死门（最高优先级）

交付：独立runtime-probe APK、锁定runtime/toolchain、capability报告；`minSdk=34`、仅`arm64-v8a`，先不写ST UI。需在Android14及更高系统的arm64环境验证；宿主机x86_64成绩不替代Android ABI验证。

**已实现的本机命令**（成功与否以实际输出和`docs/status.md`为准；没有arm64设备不能验收）：

```bash
python3 tools/verify_project.py
python3 tools/build_node.py --jobs 12
python3 tools/verify_runtime_artifacts.py
(cd android && ./gradlew :runtime-probe:assembleDebug :runtime-probe:assembleDebugAndroidTest)
python3 tools/verify_apk.py android/runtime-probe/build/outputs/apk/debug/runtime-probe-debug.apk
python3 tools/run_device_probe.py --serial "$ANDROID_SERIAL"
python3 tools/verify_runtime_report.py --reports build/reports
```

当前探针为基础Node能力实验，测试类`dev.stshell.probe.RuntimeProbeTest`；不是完整ST/WebView/后台生成验收。v0.2新增手机“开始完整自检（自动两轮）”、系统/API/page size采集、精简构建指纹、复制/保存汇总；UI与instrumentation共享协调器/校验器，手机按钮无需额外安装测试APK。收到汇总后仍按相同验证规则检查，不猜测缺失字段。

报告验证默认要求4KB与16KB设备覆盖；两次进程运行不等于两种页大小覆盖，宿主机报告、空报告或跳过的capability不计通过。Node26.8.2、NDK30以及探针的Temporal/SSL汇编配置差异均已显式记录；API版本号正确不意味着完整M1已过。

验收断言必须包括：

- `process.versions.node`达到确认目标；`process.platform`/arch/ABI记录可核验；不是host Node伪装，不跳过engine。
- ESM/TLA、fs读写/rename、crypto随机数/scrypt、fetch/Response、TLS证书校验、DNS、loopback HTTP、WASM实例化/SIMD检测、Unicode tokenizer行为、worker_threads实际可用；多次Activity attach不重启第二个runtime。
- 下载/自产native库的hash、`readelf -lW`、APK `zipalign -c -P 16 -v 4`检查；实际16KB设备/模拟器`adb shell getconf PAGE_SIZE`为16384时跑同一probe，另有4KB对照。SDK/NDK版本由构建锁固定，不能沿用旧文档r24就宣布通过。
- 停止Node不会杀UI，服务进程能重建；失败可见且有限超时。

**Go/No-go：** 必须实测嵌入的Node主版本为26。若只能用18.20.4、无法取得安全更新来源或不能通过必要ABI/16KB测试，A/B停止并报告阻塞，请你决定运行时移植投入；未经重新授权不降级Node、不退回旧ST、不改用Termux。

### M2：不改上游的可复现payload与宿主回归（宿主准备已实现）

无设备期间只推进可独立验证的构建/宿主部分，不以此关闭M1或放行Android集成。交付：生产依赖ZIP、逐文件manifest、依赖库存、外层config策略/bootstrap及真实服务端回归。

**已实现的本机命令：**

```bash
git submodule update --init --recursive
python3 tools/payload/prepare.py
python3 tools/payload/verify.py
python3 -m unittest discover -s tests/host -p 'test_*.py' -v
node --test tests/host/*.test.mjs
git -C upstream/SillyTavern diff --exit-code
```

确定性复核：复制`build/payload/current.json`后再次执行prepare，用`cmp`比较；本次已验证两次独立安装/打包字节相同。新目录的受限解包用`python3 tools/payload/verify.py --unpack <不存在的目录>`，不覆盖现有安装/用户数据。

实际结果：20,980个文件、667个生产依赖、21个独立WASM，ZIP约129.3MiB；官方源码逐文件一致，native addon检查通过。依赖库存不是完整SBOM，39项许可资料待复核，不能当作分发合规已通过。

宿主回归已运行**实际官方server.js**，覆盖外置state、Basic/CSRF/Host、实际LAN/IPv6拒绝连接、五种tokenizer、四种图像格式、PNG metadata导入导出、settings/chat/secrets落盘、聊天完整性检查、重启与停机快照搬迁、模拟远程SSE转发/取消、错误manifest/代码篡改/状态路径重叠拒绝。上游旧配置alias的迁移先完成再施加策略，不改迁移函数。

边界：Android提取器/集成未完成；snapshot测试是停机复制，不是产品级备份/回滚；强杀残留锁需未来Native层确认进程死亡后恢复；本地provider UI/端点未实现禁用；仍运行上游Webpack，没有预编译优化，也没有新增生成总时限。

### M3：Android外壳、服务生命周期与安全（前台实验代码已构建，未验收）

新增独立`android-app/`构建，避免改变v0.2探针指纹。实现版本化资源提取/复核、`:server`、复用M1 JNI、内核状态锁、Native健康检查、固定origin、WebView和文件选择器。这是为收集Android/ST集成证据的实验载体，不是关闭M1/M3的替代品。

**已实现的构建命令：**

```bash
(cd android-app && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug)
python3 tools/app/verify.py android-app/app/build/outputs/apk/debug/app-debug.apk
```

**设备命令（未执行）：**

```bash
(cd android-app && ./gradlew :app:installDebug :app:connectedDebugAndroidTest)
```

现有测试类`dev.stshell.app.AppSmokeTest`检查Native健康结果及真实WebView的chatInput/publicApi；完整安全/生命周期测试尚不齐。仍须验收：未认证401、合法session无CSRF403/有效204、Host403、真实loopback绑定、WebView认证和Cookie/流式请求、恶意导航/重定向、旋转及进程重建。

未完成项：FGS/wake lock/后台生成、完整Blob/认证下载导出、麦克风/相机、升级回滚。HTTP Basic不是服务端身份的双向TLS证明，端口接管/重定向等对抗测试未通过；不要使用重要数据/长期密钥。详见`docs/foreground-experiment.md`。

可从本机辅查debug socket（不应把adb端口转发当生产功能）：

```bash
adb -s "$ANDROID_SERIAL" logcat -d
adb -s "$ANDROID_SERIAL" shell dumpsys activity services "$APP_ID"
```

### M3.5：后台流式生成生死门（新增，不能推迟到发布前）

交付：在原生FGS生命周期下，真实ST前后端对受控远程API mock的长流生成验证；按§1.4决定是否必须提出外层流恢复适配。

**未来本机命令**（测试实现需启动受控mock并验证其连接/取消日志；当前没有这些实现）：

```bash
(cd android && ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=BackgroundStreamPersistenceTest)
python3 tools/verify-background-generation.py --decisions docs/decisions.md --reports build/reports
```

验收：测试从可见页面发起带已知顺序/数量标记的长SSE，分别切后台、锁屏，按测试定义的多档耐久场景运行并记录持续时间、设备、系统和WebView版本。**测试时长只控制mock负载，不写入产品生成超时。** mock记录请求未被意外取消，输出无遗漏，回前台后聊天可完整恢复并保存，且无重复请求/消息。还要覆盖切Activity、正常取消、notification取消、WebView renderer丢失、上游已有超时与断网；后几类中断必须明确报告/保留可恢复内容，不能偷偷重新生成并重复收费。缺少实际arm64后台/锁屏、socket/落盘断言，不算通过；不以用户提供最长生成时长为前置条件。

**Go/No-go：** 通知常驻或Node线程存活但请求被取消/结果丢失就是失败。失败时先做外层恢复设计；如果需要ST patch，提交复现、最小变更与兼容成本供审批，不自动扩展实现范围。

### M4：真实业务、数据与能力矩阵

交付：卡片/聊天/设置、SAF、流式API、模型/扩展明确支持矩阵。测试用API mock为主，可选真实provider由你提供凭据，不把密钥写仓库。

**未来本机命令：**

```bash
node --test tests/host/*.test.mjs
(cd android && ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=ConversationPersistenceTest,FileImportExportTest,CapabilitiesTest)
python3 tools/verify-capabilities.py --decisions docs/decisions.md --reports build/reports
```

验收：

- 导入有metadata的PNG卡、创建聊天、保存设置、SSE逐段渲染/取消/断网恢复；重启/升级后内容仍一致；SAF导出再导入校验hash/语义。
- 仅远程API：验证实际采用远端provider且不下载本地推理模型；缺远端配置时明确提示，不自动回退本地模型。ONNX模型推理不属于本产品验收；但启动所需transformers import、核心tokenizer/卡片图像WASM仍必须通过。验证本地provider选项的禁用/不支持说明，不假装`autoDownload=false`本身会移除入口。
- 无git环境分别验证builtin clone（如批准开放）和受限的update/branch功能的明确失败说明；禁止“界面显示成功但服务端500”。
- 麦克风/OAuth/音频/剪贴板等按批准需求逐项实测；未支持项目在产品中明示，而非被测试工具忽略。

### M5：更新/回滚、后台与性能门禁

交付：可恢复升级、崩溃/低磁盘处理、后台策略、真实设备性能报告。

**未来本机命令：**

```bash
(cd android && ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=UpgradeRollbackTest,BackgroundGenerationTest)
python3 tools/benchmark-startup.py --serial "$ANDROID_SERIAL" --package "$APP_ID" \
  --runs 20 --output build/reports/startup.json
adb -s "$ANDROID_SERIAL" shell dumpsys meminfo "$APP_ID"
python3 tools/verify-performance.py --decisions docs/decisions.md --reports build/reports
```

验收：20次进程冷启p50/p95、首次解包、WebView可交互、峰值Node/UI/WebView总PSS分别记录；符合你确认的预算，不用Linux1.5秒作Android指标。磁盘不足/解包中断/更新失败不会破坏原数据；新版本迁移后回退用相容数据快照。后台/锁屏继续生成是必测项，复跑M3.5并覆盖多档耐久负载、Android14及更高版本的Doze/OEM矩阵；测试负载时长不是产品限时。用户强制停止/系统强杀作为中断恢复场景，不承诺系统禁止的自动重启。

### M6：AGPL、供应链与正式分发

交付：签名APK/AAB、对应源码归档、NOTICE/SBOM、构建/安装说明、版本来源页、升级策略与渠道检查。

**未来本机命令：**

```bash
node tools/make-source-release.mjs --lock upstream.lock.json
python3 tools/verify-source-release.py build/release/source.tar.gz --lock upstream.lock.json
(cd android && ./gradlew :app:assembleRelease)
"$ANDROID_HOME/build-tools/37.0.0/apksigner" verify --verbose build/release/app.apk
python3 tools/verify-release.py build/release --decisions docs/decisions.md
```

注：实际打包工具须将Gradle产物整理到上述release路径；不能把默认Gradle输出假装已经位于此处。正式build-tools版本届时锁定，37.0.0仅是当前本机已安装路径。

验收：从源码归档在干净目录（另一位接收者无需私有仓库权限）重建未签名等价payload/APK，比较规范化内容/hash；不要求第三方持有你的私有签名key。下载页可取得与APK一一对应的完整源码/patch/脚本/许可证；App内可见版本SHA和源码入口；排除试验数据、API钥匙、用户聊天、私钥与research缓存。核验签名侧载/同签名升级、Android14+的权限/FGS、arm64及16KB运行兼容后发布；Play/F-Droid审核不属于当前验收，侧载不豁免系统级检查。

### MC：Termux技术对照（已排除交付，不在当前实施里程碑中）

以下保留原调研的验证方法，不能替代独立APK的M1或其他验收；本轮也未执行。只有以后明确授权额外对照试验时，才在**手机Termux终端**执行（不是Linux宿主机运行pkg）：

```bash
pkg update
pkg show nodejs
pkg install nodejs npm git
node --version
git clone --branch staging https://github.com/SillyTavern/SillyTavern.git "$HOME/SillyTavern"
cd "$HOME/SillyTavern"
git checkout --detach c0a417b240c640037bab69529aa1e0c466c69397
npm ci --omit=dev --ignore-scripts --no-audit --no-fund
node server.js --listen false --browserLaunchEnabled false
```

验收：设备node版本满足批准目标，本机页面能打开。此处为手工功能基线，未开启产品级Basic配置。需安装适配包的ABI无预期Node版本时不算通过。

未来伴侣工程的本机命令：

```bash
python3 tools/termux-smoke.py --serial "$ANDROID_SERIAL" --decisions docs/decisions.md
(cd android && ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=TermuxBridgeTest)
```

这两个工具须通过经用户授权的RUN_COMMAND通道验证版本、受认证HTTP、重启与导出，不使用`adb run-as com.termux`假设、不读取Termux私有目录。验收失败要区分“未授权”“Termux未安装”“服务不支持”；完整业务、安全、AGPL仍复用M4–M6相应标准。

## 11. AGPL-3.0 分发义务（单列）

这是工程合规计划，不替代针对分发渠道/组合方式的法律审查。**“不fork”“作为submodule”“只在localhost运行”均不豁免二进制分发义务。**

1. **保留许可与署名**：原LICENSE、版权声明、免责声明和应有的法律通知不得删除/隐藏。发布清单中写清ST准确commit和license；任何patch保留显著修改说明/日期。
2. **APK包含ST就是在分发其副本**：按AGPL §4–6提供相应源代码的合规获取方式。推荐APK下载位置同时给与该版本严格对应的完整source archive（等价、无额外收费的源码访问），而不是只贴滚动staging链接。仅给`.gitmodules`但不提供可获取的准确源码、依赖/patch/脚本并不足以保证合规。
3. **Corresponding Source范围**：至少覆盖ST固定源码、所有实际patch、构建和安装/运行所需脚本/接口定义、外层适配，以及所分发依赖/native/WASM涉及的相应源代码和各自许可要求。WASM是目标代码，不把`.wasm`本身说成源码；模型/扩展的再分发许可独立核查，不能假设继承ST许可。
4. **壳的许可不能仅凭“HTTP隔开了”下结论**：紧密组合的APK是否构成衍生/组合作品取决于具体事实。保守推荐把我们自写的壳、JNI、bootstrap、patch、构建脚本以AGPL-3.0兼容方式开放，组合发布按AGPL义务处理；第三方独立组件仍保留自身许可/NOTICE。若想闭源壳，必须先做专门法律评估，不能擅自默认允许。
5. **网络交互义务 §13**：若提供修改版本并允许用户通过网络远程交互，应显著提供该版本Corresponding Source的免费网络获取入口。纯未修改上游与修改版本的§13触发条件要区分；但APK分发的§6义务无论localhost与否仍在。为避免漏项，产品“关于/许可证”原生页提供精确版本源码下载；如日后开放远程使用，入口须让远程用户也能看到，不能仅存在原生页。
6. **不必宣称法律一律要求公开GitHub**：义务关注接收者/适用的网络用户获取源码及其权利。公开可归档发布仓库是推荐的最简履行方式，但不是唯一允许方式。不要只口头承诺“需要时再给”；选用书面offer方式则须严格满足§6对应期限/对象/介质条件。
7. **不得追加相冲突限制**：不得用EULA禁止接收者复制、修改、再分发覆盖作品。审核商店条款、DRM、代码签名/安装限制；适用User Product条款时提供所需Installation Information。发布可自行构建安装的说明，不泄露生产私钥，也不无根据声称必须交出个人签名私钥。
8. **安全材料与用户数据不是源码发布内容**：不上传真实API keys、cookie secrets、签名私钥、聊天、research试验state。只发布模板和可复现的测试fixture。公开源码不代表必须公开使用者数据。
9. **发布验收**：M6生成SBOM/NOTICE/source archive；每个APK有对应manifest和hash，长期保存已发布版本的源码可获取性。若发Termux伴侣但不再分发Termux，其许可责任与“把Termux/其包一起重打包”不同，按实际分发物重新审查。

## 12. 外部资料与可信度说明

本次拉取资料的main分支也会变化，快照保存在`research/`，revision记录在`external-revisions.json`；实施时重新锁定并核实。

- nodejs-mobile官方发布：<https://github.com/nodejs-mobile/nodejs-mobile/releases/tag/v18.20.4>；API latest与最近列表均已查询，未发现更高官方发布。Android发布zip含armv7/arm64/x86_64；本次还检查了arm64 ELF。
- Runtime FAQ：<https://github.com/nodejs-mobile/nodejs-mobile/blob/d9552e0e01ed5bdbe12a31d1ce6c0877a4f39580/doc_mobile/FAQ.md>；构建文档仍提旧分支/NDK r24，只作参考，不能充当现代toolchain结论。
- Node维护周期：<https://github.com/nodejs/Release/blob/main/schedule.json>。
- Termux Node26配方：<https://github.com/termux/termux-packages/blob/299d2c6d225dd5f9da626eba34cedd996ed2404c/packages/nodejs/build.sh>；LTS同仓`packages/nodejs-lts/build.sh`。
- Termux应用/分发/后台注意：<https://github.com/termux/termux-app#readme>；RUN_COMMAND实现：<https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/app/RunCommandService.java>。
- ST Android文档：<https://github.com/SillyTavern/SillyTavern-Docs/blob/70e5e4d3c239253fca4692fe82e3936cb9c4b1b1/Installation/Android.md>；与实际新包有冲突时优先检查安装产物/Termux上游资料。
- Capacitor Cordova兼容与hook限制：<https://github.com/ionic-team/capacitor-docs/blob/main/docs/plugins/cordova.md>；配置声明：<https://github.com/ionic-team/capacitor/blob/5e0f67871994fec94e413cd0993dc6cd495431f7/cli/src/declarations.ts>。网站本次返回403，使用官方仓库文档，不臆造网页内容。
- Android10私有可写目录执行限制：<https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission>。
- Android16KB页：<https://developer.android.com/guide/practices/page-sizes>（含2025-11-01起Play相应要求、NDK/AGP及验证办法）。
- WebView本地内容：<https://developer.android.com/develop/ui/views/layout/webapps/load-local-content>；网络安全：<https://developer.android.com/privacy-and-security/security-config>。
- 前台服务类型/限制：<https://developer.android.com/develop/background-work/services/fgs/service-types>；已确定侧载，仍须验证Android14+用途/权限/targetSdk与较新系统限制；当前不要求Play渠道审批。

**需求已收敛且方案A获批实施：Node26独立APK、Android14+、仅arm64、侧载、仅远程API、后台/锁屏继续生成并接受持续通知。生成语义沿用酒馆，不新增壳级总时限。M1已收到单次Android基础成功回传，完整验收待补齐；M2宿主准备已通过；M3前台实验APK已构建但未在设备验收。未通过完整Android/ST运行、安全与M3.5后台流门禁前，不宣称已可交付完整App。**
