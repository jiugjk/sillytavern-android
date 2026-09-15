# 首次联网安装：ST Staging + 三个内置全局扩展

本轮采用首次联网安装方案，取代 Android APK 内打包固定 ST 本体的方式。历史的 `tools/payload/*` 保留为固定源码的宿主回归工具，不再作为 APK 的 ST 本体输入。

## 行为

- APK 只包含锁定的 Node26 运行时、壳程序、安全策略与 npm 安装工具（不是 ST 源码及插件）。
- 首次启动时，从 `SillyTavern/SillyTavern` 的 `staging` 分支解析最新提交，再按该提交下载完整源码。
- 同次安装解析三个仓库的默认分支 HEAD，并下载到 `server/public/scripts/extensions/third-party/<name>`：
  - <https://github.com/zonde306/ST-Prompt-Template>
  - <https://github.com/n0vi028/JS-Slash-Runner>
  - <https://github.com/koichole213-ui/st-theater>
- “最新”是解析时各仓库的最新提交，不是一个统一的跨仓库快照。安装记录包含实际 commit、版本和下载归档 SHA256。
- 生产依赖使用下载的 ST `package-lock.json`，通过 APK 固定版本 npm 的 Arborist 在**同一个 Node 进程内**安装。对齐 npm ci 的依赖树校验，不调用系统 npm/git/第二个 Node，也不执行生命周期脚本。不需要 Termux。
- 普通启动复用已安装版本，不查询更新；仍逐文件哈希校验，不能凭只读权限或时间戳跳过。
- 控制面板的“重新下载本体与内置插件”需要确认，会停服并重新联网安装。聊天、角色、配置、密钥和用户级扩展继续使用独立的 `st-state`，不会被删除。
- 下载中断或失败不切换当前指针，下一次启动可重试；废弃的精确 UUID 暂存目录在持有服务互斥时清理。正常版本保留当前及上一份，其他已识别版本受限回收；损坏版本另行隔离供诊断。
- 源码及内置扩展安装后设为只读。**内置全局插件不能在酒馆扩展菜单中直接更新/删除**，请使用启动器的重新下载入口。用户级扩展保存在可写数据目录，保持可安装。

## 安全与信任边界

1. Android 在执行 APK 安装器前，验证 APK 固定的归档和成员哈希。
2. GitHub 下载仅接受指定 HTTPS 主机，移动 ref 先解析成 commit，再下载；解包拒绝穿越、重复成员、符号链接、硬链接、特殊文件与超限资源。
3. npm 依赖限 HTTPS npm registry，并校验锁文件的完整性值；拒绝不支持的平台选择依赖和常见原生扩展。若未来 Staging 引入不兼容依赖，安装将失败，不伪装成功。
4. 下载内容的身份与哈希存于私有安装记录。它们用于重启时检测损坏，**不是开发者签名，也不是对远端最新源码安全性的背书**。本方案明确把 ST/插件仓库及 npm 供应链纳入信任范围。
5. 只读权限阻止常规写入，但同 UID 代码仍可能改权限，因此不是恶意插件隔离沙箱。哈希不证明代码无恶意。
6. 不修改 ST 上游源码或路由。全局写入尝试目前由文件权限拒绝，上游返回 HTTP 500；这是已知 UX 局限，不代表用户级扩展也不可安装。
7. `JS-Slash-Runner` 的仓库许可证是 **AFPL**（不是 MIT/AGPL）；另两个仓库分别带 AGPLv3、MIT 文本。下载保留源码及许可证。公开再分发或商业用途需要另行审查，不能宣称已完成许可证合规审核。

## 构建与验收

已有匹配 `upstream.lock.json` 的 `build/runtime` 后：

```bash
python3 tools/app/prepare_installer.py
(cd android-app && ./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease)
python3 tools/app/verify.py --variant debug --require-signature android-app/app/build/outputs/apk/debug/app-debug.apk
python3 tools/app/verify.py --variant release android-app/app/build/outputs/apk/release/app-release-unsigned.apk
```

Release 内容校验接受优化后的资源名称，通过 aapt2 资源表定位同一逻辑资源；未签名只可做静态检查，不能安装或发布。发布必须增加 `--require-signature`。

```bash
# 不联网的安装边界测试（需先构建 installer）
node --test tests/host/first-install.test.mjs
# 真实 GitHub + npm 下载、ST 启动、全局/用户级扩展、离线复用与损坏修复
node --test tests/host/online-install.live.mjs
# 原固定 ST 宿主回归，仍与 Android 安装内容分开
python3 tools/payload/prepare.py
node --test tests/host/*.test.mjs
python3 -m unittest discover -s tests/host -p 'test_*.py' -v
```

真实安装报告：`build/reports/online-install.json`；诊断日志：`build/reports/online-server.log`。报告带 `scope=live-github-npm-linux-host`，**不是 Android 真机结果**。

需要在 Android 14+ arm64 实机继续验收：首次网络安装、网络断开/停止后重试、WebView 中三个插件实际加载与交互、独立页面恢复、后台/锁屏生成及数据保存。宿主 HTTP 能提供插件资源，不等于已经验证所有插件功能或外部 CDN 依赖。

请先备份用户数据；Debug 签名可能不同于已装正式版，不要为安装测试包而直接卸载现有应用。
