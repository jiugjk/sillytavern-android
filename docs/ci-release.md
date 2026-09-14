# GitHub Actions 构建与发布

工作流：[`.github/workflows/android-build.yml`](../.github/workflows/android-build.yml)，只能手动触发
（Actions → **Android APK** → Run workflow）。它复刻 README 里的本机流程，不改变任何既有校验门禁。

## 1. 手动触发参数

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `build_type` | `both` | 构建 `release`、`debug` 或两者 |
| `version_bump` | `patch` | 推进 `android-app/version.properties`；`none` 表示不动版本号 |
| `version_name` | 空 | 指定确切 `versionName`（形如 `1.0.0`），填了就覆盖 `version_bump` |
| `publish_release` | `true` | 把 APK 发布到 GitHub Releases |
| `prerelease` | `true` | Release 标记为预发布 |
| `run_checks` | `true` | 打包前跑 `:app:testDebugUnitTest` 与 `:app:lintDebug` |
| `runtime_runner` | `ubuntu-latest` | 跑 Node 交叉编译的 runner 标签 |

## 2. 签名密钥（Actions secrets）

`release` 构建从这四个仓库 secrets 取签名材料；缺任意一个时 release APK 会保持未签名，
此时若还要求发布 Release，工作流会主动失败而不是发出装不上的包。

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | keystore 文件的 base64（单行，无换行） |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 口令 |
| `ANDROID_KEY_ALIAS` | 密钥别名 |
| `ANDROID_KEY_PASSWORD` | 密钥口令（未单独设置时与 keystore 口令相同） |

在**本机**生成并上传（脚本不会把任何密钥写进仓库）：

```bash
bash tools/ci/make_signing_key.sh
gh secret set ANDROID_KEYSTORE_BASE64 < sillytavern-android-release.jks.base64
gh secret set ANDROID_KEYSTORE_PASSWORD
gh secret set ANDROID_KEY_ALIAS
gh secret set ANDROID_KEY_PASSWORD
```

上传后离线备份 `*.jks` 与口令，并删除 `*.jks.base64`。注意：

- 签名密钥是私钥材料，不要提交、不要贴进 issue/PR/聊天；`.gitignore` 已忽略 `*.jks`/`*.keystore`。
- 换密钥重签的包无法覆盖安装，用户必须先卸载；丢失密钥等于再也无法发布可升级的版本。
- `debug` 包仍用 Android 默认 debug 签名，带 `debuggable` 标记，只用于排查。

## 3. 版本号

`android-app/version.properties` 是唯一版本来源：`app/build.gradle.kts` 读它，
`tools/app/app_inputs.py` 把它计入 APK 内的 app contract，因此改版本号会改变 `appIdentity`。

- `tools/ci/bump_version.py --bump patch|minor|major|none`（也支持 `--version-name`、`--dry-run`）。
- 版本变化时工作流把 `version.properties` 提交回触发时所在分支，提交者是 `github-actions[bot]`。
- Release tag 为 `v<versionName>`；已存在则退化为 `v<versionName>-build<versionCode>`。

## 4. 缓存

| 缓存 | key 依据 | 说明 |
| --- | --- | --- |
| `build/runtime` | `upstream.lock.json`、`patches/node-runtime/*`、`tools/build_node.py`、`tools/runtime_common.py` | 交叉编译出的 `libnode.so` 等产物 |
| `build/payload` | `upstream.lock.json`、`config/*-policy.json`、`tools/payload/*.py`、`runtime/mobile/*.mjs` | ST payload.zip 与 manifest |
| `~/.npm` | `upstream.lock.json` | payload 的 npm 下载 |
| Gradle | `gradle/actions/setup-gradle` 托管 | 依赖与 build cache（`--build-cache`） |

首次运行（或改动 Node 锁/补丁）要完整交叉编译 Node 26，通常 2–4 小时，
runtime job 因此设了 350 分钟上限（GitHub 托管 runner 硬上限 6 小时）并预先清理 runner 磁盘；
标准 4 核 runner 有超时风险，必要时用 `runtime_runner` 指向更大的或自托管 runner。
命中缓存后整条流水线通常十几到几十分钟。
Node 源码树（`build/node-source`，数十 GB）不入缓存，只缓存 strip 后的产物。

## 5. 校验范围

- `debug` APK 走既有 `python3 tools/app/verify.py`（它按设计要求 `debuggable`，故不适用于 release）。
- `release` APK 只做 `apksigner verify` 与 `zipalign -c -P 16 -v 4`；打包内容的完整性由构建期
  `tools/app/prepare.py`（含 `verify_project.py`、runtime 与 payload 校验）保证。
- 工作流**不**做设备验收：没有 instrumentation、没有真机运行结论。发布产物仍是非官方实验包。
