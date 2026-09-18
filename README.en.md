# SillyTavern Android

[简体中文](README.md) | **English**

[![Android](https://img.shields.io/badge/Android-14%2B%20(API%2034%2B)-brightgreen.svg)](https://developer.android.com)
[![Architecture](https://img.shields.io/badge/Arch-arm64--v8a-blue.svg)](https://developer.android.com/ndk)
[![Node.js](https://img.shields.io/badge/Node.js-26-green.svg)](https://nodejs.org)
[![No Termux](https://img.shields.io/badge/Termux-Not%20Required-orange.svg)](#)
[![License](https://img.shields.io/badge/License-AGPL%203.0-blue.svg)](LICENSE)

A native, self-contained runtime and launcher for **SillyTavern** on Android.

It ships a natively compiled, modern Node.js 26 runtime, so no Termux install or hand-built Linux terminal environment is needed. The UI is tuned for mobile with an immersive full-screen experience and an edge-docked control handle, and a dedicated background/wake service keeps streaming model replies alive — and fully saved — even when the app is backgrounded or the screen is locked.

---

## 🌟 Key Features

- 🚀 **Works out of the box, no Termux required**
  - Bundles a Node.js 26 shared library (`libnode.so`) cross-compiled from official sources, compatible with modern Android 16 KB memory pages and the Bionic environment.
  - Hosted directly inside a background service process through JNI — no Linux proot, Termux shell, or external runtime to configure.
- 📦 **Small APK, self-installing on first launch**
  - The base APK stays light, containing only the Node core and the in-app installer.
  - On first launch it fetches the latest SillyTavern `staging` sources and installs production dependencies entirely on-device.
  - Every later launch runs fully offline and starts in seconds.
- ⚡ **Keeps generating in the background and on a locked screen**
  - Addresses the usual mobile problems — background processes being killed, WebView streams dropping on lock — with a dedicated session protection service (foreground service + wake lock).
  - The connection and CPU stay awake while a response is generating, then the wake lock is released and the chat is saved automatically once streaming finishes.
- 🔎 **Visible install progress and update checks**
  - First-time install and every launch show stage names, a progress bar and a percentage for downloading, extracting, installing dependencies and verifying integrity (MB for downloads, file counts for verification).
  - The control panel has a built-in **Check for updates**: it compares SillyTavern staging, the three bundled extensions and the launcher APK against the latest published versions, and only then offers a one-tap update.
- 📱 **Mobile-first full-screen interaction**
  - Immersive full-screen WebView that adapts to dark system bars, notches and gesture navigation.
  - A tiny edge-docked handle expands the control panel with a short inward swipe, and hides itself when the soft keyboard appears instead of squeezing the page layout.
- 🧩 **Curated global extensions preinstalled**
  - Three widely used extensions are installed by default on first run:
    - **[ST-Prompt-Template](https://github.com/zonde306/ST-Prompt-Template)** — prompt template enhancements
    - **[JS-Slash-Runner](https://github.com/n0vi028/JS-Slash-Runner)** — slash commands and script execution
    - **[st-theater](https://github.com/koichole213-ui/st-theater)** — theater mode and visual presentation
  - Installing and managing your own extensions from inside SillyTavern remains fully supported.
- 🛡 **Independent, persistent data**
  - Character cards, chat history, user settings, API keys and custom extensions live in a private data area (`st-state/`).
  - The control panel can re-download SillyTavern and the bundled extensions at any time — updating the app itself never touches your data.

---

## 📱 Requirements

| Item | Requirement | Notes |
| :--- | :--- | :--- |
| **OS version** | Android 14+ (API 34+) | Works on Android 14, 15 and newer |
| **CPU architecture** | arm64-v8a (64-bit) | Supports modern arm64 devices with both 4 KB and 16 KB page sizes |
| **Free space** | ≥ 1.5 GB recommended | Node runtime, SillyTavern sources, npm cache and user data |
| **Network** | A reliable connection | Only the first launch needs GitHub and the npm registry |

> **Note**: this project is a standalone mobile client for connecting to remote APIs, relay services or local/LAN model servers (KoboldCPP, Ollama, vLLM, OpenAI-compatible endpoints, …). It does not bundle or support running local LLM inference on the phone.

---

## 🚀 Getting Started

### 1. Download and install
Grab the latest `app-release.apk` from the GitHub [Releases](../../releases) page (or from an Actions build artifact) and sideload it, allowing installs from unknown sources.

### 2. First launch and initialization
1. Open the app and grant network and notification permissions.
2. The installer starts automatically, downloading the latest SillyTavern staging sources plus the bundled extensions, then installing dependencies.
3. This usually takes 1–3 minutes depending on network and device. When it finishes, the service starts and the SillyTavern UI loads.

### 3. Daily use and the control panel
- **Open the panel**: a translucent vertical handle sits at the screen edge — swipe it inward (or tap it) to expand the control panel.
- **Move the handle**: press and drag it up or down to change its docking position; it hides itself while the soft keyboard is open.
- **Panel sections** (service control / updates & maintenance / system & background / diagnostics & about):
  - Status card: current stage, details, startup step (x/9), progress bar and percentage, plus background protection and activity-watcher state;
  - Service control: start, restart, stop, and — when the page misbehaves — **Reopen page** without restarting Node;
  - Updates & maintenance: check for updates, re-download SillyTavern and the bundled extensions;
  - Diagnostics & about: copy diagnostics, view Node startup logs, read the notes and open-source licenses, and jump to the project repository.

### 4. Enable background / locked-screen generation
1. Open the control panel from the edge handle, tap **Background protection**, and make sure notifications are allowed.
2. Once enabled, a persistent notification shows the protection status.
3. After starting a long response you can safely switch apps or lock the screen: the service keeps the connection and CPU alive while generating, then saves and sleeps automatically.

> ⚠️ **Aggressive battery optimization**: on heavily customized systems (HyperOS / MIUI, OriginOS, ColorOS, …), set this app's battery policy to **Unrestricted** / allow high background power usage, otherwise the system may kill the background service.

---

## 📦 Data and Updates

### Checking for updates
Control panel → **Check for updates** queries GitHub for:
- the latest commits of SillyTavern (`staging`) and the three bundled extensions, compared item by item against what is installed locally;
- the launcher's latest release version, compared against the installed APK.

Results are listed per component as up to date / update available / unavailable, with short commit hashes for both current and latest. **Download and update** appears only when there really is an update; the launcher APK must be installed manually, so **Go to APK download** simply opens the release page. Checking is read-only and never downloads or modifies anything.

### Updating SillyTavern and bundled extensions
When SillyTavern or a bundled extension has an update:
1. Open the control panel, tap **Check for updates** and then **Download and update**, or use **Re-download app and bundled extensions** directly.
2. After confirmation the app re-fetches the latest sources from the official repositories and reinitializes, showing download / dependency / verification progress throughout.
3. **Data safety**: character cards, chat history, API keys and custom configuration live in a separate data directory and are **never affected** by a reinstall.

### Exporting and backing up your data
- Periodically export important character cards and conversations through SillyTavern's own export features.
- The launcher's control panel can copy diagnostics and logs, which makes troubleshooting much easier.

---

## ❓ FAQ

<details>
<summary><b>Q: The first-launch download stalls or fails.</b></summary>

Initialization connects directly to GitHub and the npm registry. If your network cannot reach GitHub reliably, downloads may time out.
- Retry through a proxy/VPN.
- Close and reopen the app — the installer retries and resumes where it stopped.
</details>

<details>
<summary><b>Q: Why can't I delete the three bundled extensions from SillyTavern's extension menu?</b></summary>

For mobile stability, `ST-Prompt-Template`, `JS-Slash-Runner` and `st-theater` are deployed as system-level extensions and are read-only. To update them, use **Re-download app and bundled extensions** in the control panel. Third-party extensions you install yourself are not restricted.
</details>

<details>
<summary><b>Q: Generation still stops sometimes after locking the screen.</b></summary>

1. Confirm **Background protection** is actually enabled in the control panel;
2. Check system settings and make sure the app is whitelisted for battery/background activity so it is not frozen aggressively;
3. Some API providers enforce a per-connection timeout, so extremely long generations may be cut off by the remote server.
</details>

<details>
<summary><b>Q: Can it run local models (GGUF / Ollama) on the phone?</b></summary>

No. Mobile chips and memory are limited, and local inference means heavy heat and battery drain. This project is a dedicated mobile front-end client: connect to a cloud API, or run a server on a PC (KoboldCPP / LM Studio / Ollama) and connect to its LAN address from the phone.
</details>

---

## 🛠 Building and Development

To build the APK yourself or modify the launcher shell:

### Local environment
- Linux (x86_64) or an environment with the cross-compilation toolchain
- JDK 21 (`JAVA_HOME`)
- Android SDK (platform `android-36`, build-tools `35.0.0`)
- Android NDK (`30.0.16248370`) and CMake (`3.22.1`)
- Python 3.12+ (build and verification tooling)
- Node.js 26 (host-side tests and regressions)

### Quick APK build
```bash
# 1. Prepare the installer and front-end assets
python3 tools/app/prepare_installer.py

# 2. Build and verify the debug APK
cd android-app
./gradlew :app:assembleDebug
cd ..
python3 tools/app/verify.py --variant debug android-app/app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Actions builds
The repository ships a GitHub Actions workflow (`.github/workflows/android-apk.yml`) that can be triggered manually to build a `release` or `debug` APK and publish it to GitHub Releases.

### Detailed technical docs
See the `docs/` directory for implementation details and verification notes:
- [First-run online install architecture and security boundaries](docs/first-run-install.md)
- [Background generation and keep-alive mechanism](docs/background-experiment.md)
- [Full-screen WebView and edge gestures](docs/p1-fullscreen.md)
- [CI builds, automated tests and release guide](docs/ci-release.md)
- [Node.js cross-compilation and patch details](patches/README.md)

---

## ⚖️ License and Credits

- The launcher shell, native integration code and project automation are released under **[AGPL-3.0](LICENSE)**.
- SillyTavern belongs to its original authors and is licensed under AGPL-3.0.
- Node.js belongs to the OpenJS Foundation and its contributors, licensed under MIT.
- Third-party components and extensions keep their own licenses (see the `licenses/` directory and each extension's repository).

> **Disclaimer**: this is an independent open-source porting project and is not an official SillyTavern product. Please use the related AI services lawfully and responsibly.
