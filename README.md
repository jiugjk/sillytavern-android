# SillyTavern Android

**简体中文** | [English](README.en.md)

[![Android](https://img.shields.io/badge/Android-14%2B%20(API%2034%2B)-brightgreen.svg)](https://developer.android.com)
[![Architecture](https://img.shields.io/badge/Arch-arm64--v8a-blue.svg)](https://developer.android.com/ndk)
[![Node.js](https://img.shields.io/badge/Node.js-26-green.svg)](https://nodejs.org)
[![No Termux](https://img.shields.io/badge/Termux-Not%20Required-orange.svg)](#)
[![License](https://img.shields.io/badge/License-AGPL%203.0-blue.svg)](LICENSE)

专为 Android 平台打造的 **SillyTavern（酒馆）** 原生独立运行环境与启动器。

内置原生编译的现代 Node.js 26 运行时，无需安装 Termux 或配置复杂的 Linux 终端环境；针对移动端优化全屏沉浸式体验与贴边交互手柄，并提供专门的后台保活与唤醒服务，支持在手机切后台、锁屏状态下持续接收大模型流式回复并完整保存。

---

## 🌟 核心特性

- 🚀 **开箱即用，免 Termux 依赖**
  - 内置基于官方源码交叉编译的 Node.js 26 共享库（`libnode.so`），兼容现代 Android 16KB 内存页与 Bionic 环境。
  - 通过 JNI 直接在后台服务进程中托管，无需配置 Linux proot、Termux 终端或外部运行时。
- 📦 **轻量安装包，首启自动安装**
  - APK 底包轻巧，仅集成 Node 核心与端内安装器。
  - 首次启动时自动拉取 SillyTavern 官方 `staging` 最新源码，并在端内自动完成生产依赖安装。
  - 后续启动完全离线运行、极速秒开。
- ⚡ **后台与锁屏持续生成**
  - 针对手机端切后台易杀进程、WebView 锁屏断流导致模型回复丢失的问题，提供专属后台会话保护服务（前台服务 + 唤醒锁）。
  - 在生成期间保持连接与 CPU 活跃，流式响应完毕后自动释放唤醒并保存聊天，告别锁屏断聊。
- 🔎 **可视化的安装进度与更新检查**
  - 首次安装与每次启动的下载、解压、依赖安装与完整性校验都带阶段名、进度条与百分比（下载显示 MB，校验显示文件数）。
  - 控制面板内置“检查更新”：联网比对 SillyTavern Staging、三个内置插件与启动器 APK 的最新版本，确认有更新后再一键下载更新。
- 📱 **移动端全屏交互设计**
  - 沉浸式全屏 WebView，适配系统深色状态栏、刘海屏与手势导航。
  - 贴边微型悬浮手柄，轻轻向内滑动即可展开控制面板；在软键盘弹起时智能避让隐藏，不挤压网页布局。
- 🧩 **预置精选全局扩展**
  - 首次安装默认集成三大高频实用扩展：
    - **[ST-Prompt-Template](https://github.com/zonde306/ST-Prompt-Template)**：提示词模板增强
    - **[JS-Slash-Runner](https://github.com/n0vi028/JS-Slash-Runner)**：斜杠命令与脚本执行增强
    - **[st-theater](https://github.com/koichole213-ui/st-theater)**：剧场模式与可视化展示
  - 同时完整支持在酒馆内安装和管理用户自定义扩展。
- 🛡 **数据独立持久化**
  - 角色卡、聊天记录、用户设置、API 密钥与自定义扩展独立存储在私有数据区（`st-state/`）。
  - 控制面板支持一键“重新下载本体与内置插件”，更新酒馆本体绝不丢失任何用户数据。

---

## 📱 系统要求

| 项目 | 要求 | 说明 |
| :--- | :--- | :--- |
| **系统版本** | Android 14+ (API 34+) | 兼容 Android 14、15 及更高版本 |
| **CPU 架构** | arm64-v8a (64位) | 适配 4KB 与 16KB 页大小的现代 arm64 设备 |
| **可用空间** | 建议预留 ≥ 1.5 GB | 包含 Node 运行时、SillyTavern 源码、npm 依赖缓存及用户数据 |
| **网络环境** | 良好网络连接 | 仅首次启动需要访问 GitHub 及 npm registry 下载初始化资源 |

> **提示**：本项目专注于作为移动端独立客户端，用于连接远端 API、中转服务或本地局域网模型服务端（如 KoboldCPP、Ollama、vLLM、OpenAI 兼容端点等），不内置且不支持在手机端进行本地大模型推理。

---

## 🚀 快速上手

### 1. 下载与安装
前往 GitHub [Releases](../../releases) 页面下载最新版本的 `app-release.apk`（或 Actions 编译构建产物），在手机上允许未知来源侧载安装。

### 2. 首次启动与初始化
1. 打开应用，授予网络与通知权限。
2. 首次进入将自动启动安装器，下载 SillyTavern 最新 Staging 分支源码及内置扩展，并安装依赖。
3. 安装过程通常需要 1~3 分钟（取决于网络与设备性能）。安装完成后将自动启动服务并加载酒馆界面。

### 3. 日常操作与控制面板
- **呼出面板**：屏幕边缘可见半透明贴边小竖条手柄，向屏幕内侧滑动（或点击）即可展开控制面板。
- **调整手柄**：按住手柄上下拖动即可调整停靠位置；输入文字弹出软键盘时，手柄会自动隐藏。
- **控制面板功能**（分区呈现：服务控制 / 更新与维护 / 系统与后台 / 诊断与关于）：
  - 状态卡片：当前阶段、详情、启动步骤（x/9）、进度条与百分比，以及后台保护、活动观察器状态；
  - 服务控制：启动、重启、停止，页面异常时额外提供“重新打开页面”（不重启 Node）；
  - 更新与维护：检查更新、重新下载本体与内置插件；
  - 诊断与关于：复制诊断信息、查看 Node 启动日志、说明与开源许可证，并可一键跳转项目仓库。

### 4. 开启后台/锁屏生成保护
1. 从贴边手柄呼出控制面板，点击 **“后台保护”** 并确保已允许系统通知。
2. 开启后，通知栏将常驻保护状态。
3. 发起长文本对话后，你可以放心切换到其他应用或锁屏。保护服务会在生成期间维持连接与 CPU 锁，生成结束自动保存并休眠。

> ⚠️ **部分系统电池优化提醒**：若使用的是部分深度定制系统（如 HyperOS / MIUI、OriginOS、ColorOS 等），请在系统的应用设置中将本应用的“省电策略 / 电池管理”设置为 **“无限制”** 或 **“允许后台高耗电”**，防止系统强杀后台服务。

---

## 📦 数据与更新

### 检查更新
控制面板 → **“检查更新”** 会向 GitHub 查询：
- SillyTavern 本体（`staging` 分支最新提交）与三个内置插件的最新提交，与本机已安装的提交逐项比对；
- 启动器自身的最新 Release 版本号，与当前 APK 版本比较。

结果按组件列出“已是最新 / 有更新 / 无法获取”，并显示当前与最新的提交短哈希。仅在确实存在更新时才提供 **“下载并更新本体”** 按钮；启动器 APK 需手动下载安装，可直接点击 **“前往下载 APK”** 打开 Release 页面。检查过程只读取版本信息，不会下载或改动任何已安装内容。

### 更新酒馆本体与内置插件
当 SillyTavern 或内置插件发布更新时：
1. 呼出控制面板，点击 **“检查更新”** 确认有更新后点击“下载并更新本体”；或直接点击 **“重新下载本体与内置插件”**。
2. 确认后，应用将重新从官方仓库拉取最新源码并完成初始化，全过程显示下载/依赖/校验进度。
3. **数据安全承诺**：你的角色卡、聊天历史、API 密钥与自定义配置均保存在独立的数据目录中，重装本体**不会受到任何影响**。

### 用户数据导出与备份
- 建议定期通过酒馆前端界面的导出功能备份你的重要角色卡与对话记录。
- 启动器控制面板提供诊断与日志复制功能，方便排查各类异常问题。

---

## ❓ 常见问题 (FAQ)

<details>
<summary><b>Q: 首次启动下载一直卡住或失败？</b></summary>

首次初始化需要直接连接 GitHub 与 npm registry。如果所在网络无法稳定访问 GitHub，可能会导致下载超时。
- 建议连接网络代理（科学上网环境）后重试。
- 退出应用重新打开，安装器会自动重试断点继续下载。
</details>

<details>
<summary><b>Q: 为什么酒馆插件菜单里不能删除内置的三个扩展？</b></summary>

为确保移动端运行稳定性，`ST-Prompt-Template`、`JS-Slash-Runner` 和 `st-theater` 是作为系统级插件部署的，处于只读保护状态。如需更新这三个扩展，使用控制面板的“重新下载本体与内置插件”即可一键升级至最新版。用户自行在扩展菜单中安装的其他第三方扩展不受此限制。
</details>

<details>
<summary><b>Q: 锁屏后为什么模型有时还是停止生成了？</b></summary>

1. 确认是否已在控制面板中点击开启了 **“后台保护”**；
2. 检查手机系统设置，确保应用已被加入系统电池白名单（允许后台活动，避免系统激进冻结）；
3. 某些 API 供应商对单次连接有超时限制，若生成内容耗时极长可能被远端服务端断开。
</details>

<details>
<summary><b>Q: 能否运行手机本地的大模型（如 GGUF / Ollama）？</b></summary>

不能。移动端芯片与内存资源有限，本地跑模型发热量与耗电极大。本项目定位为专业的移动端前端客户端，推荐连接云端 API 服务，或在电脑运行服务端（如 KoboldCPP / LM Studio / Ollama）并在手机上连接局域网 IP。
</details>

---

## 🛠 编译与开发

如果你希望自行构建 APK 或修改外层壳工程，可以参考以下开发说明：

### 本机环境要求
- Linux (x86_64) 或具有交叉编译工具链的环境
- JDK 21 (`JAVA_HOME`)
- Android SDK (platform: `android-36`, build-tools: `35.0.0`)
- Android NDK (`30.0.16248370`) 与 CMake (`3.22.1`)
- Python 3.12+ (用于构建与检查工具)
- Node.js 26 (宿主测试与回归)

### 快速构建 APK
```bash
# 1. 准备安装器与前端资源
python3 tools/app/prepare_installer.py

# 2. 编译并校验 Debug APK
cd android-app
./gradlew :app:assembleDebug
cd ..
python3 tools/app/verify.py --variant debug android-app/app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Actions 自动构建
仓库内置了 GitHub Actions 工作流（`.github/workflows/android-apk.yml`），支持手动触发编译 `release` 或 `debug` APK，并自动发布到 GitHub Releases。

### 详细技术文档
关于底层实现细节与验证，请查阅 `docs/` 目录：
- [首次联网安装架构与安全边界](docs/first-run-install.md)
- [后台生成与保活机制技术说明](docs/background-experiment.md)
- [全屏 WebView 与边缘手势实现](docs/p1-fullscreen.md)
- [CI 构建、自动化测试与发布指南](docs/ci-release.md)
- [Node.js 交叉编译与 Patch 细节](patches/README.md)

---

## ⚖️ 许可与致谢

- 本启动器外壳、原生适配代码及工程自动化工具采用 **[AGPL-3.0](LICENSE)** 协议开源。
- SillyTavern 归其原作者所有，采用 AGPL-3.0 协议。
- Node.js 归 OpenJS Foundation 及贡献者所有，采用 MIT 协议。
- 第三方组件与扩展分别遵循其各自的开源许可证（详见 `licenses/` 目录与各扩展仓库）。

> **声明**：本项目为独立开源适配工程，非 SillyTavern 官方项目。请合理合法使用相关 AI 服务。
