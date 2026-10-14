# AGENTS.md

本仓库是 [pdromnt/mario-and-luigi](https://github.com/pdromnt/mario-and-luigi)（1994 年 DOS《Mario & Luigi》的 Free Pascal + SDL2 复刻）的 fork，额外维护 **Windows** 与 **Android** 两条构建线。`origin` = `pisces312/mario-and-luigi`（推送目标），`upstream` 只取不改。

文档入口：`README.MD`（概览+构建）、`docs/BUILD.md`（工具链版本/路径/命令）、`docs/PLAN.md`（移植总记录）、`docs/BT-INPUT.md`（Android 外接输入方案）。

## 协作约定

- 先出方案再动手；commit / push / 打 tag / 建 release 需逐次确认（用户一次性说"提交并推送"即视为该次已授权）。
- 只 `git add` 本次真正改动的路径，禁止 `git add -A` / `git add .`。改动跨类别时按语义拆 commit：`Android:` / `feat:` / `build:` / `docs:` / `release:`（正文中文）。
- 验证分工：AI 职责止于「构建 + `adb install -r`」，游戏手感与真机手柄回归由用户完成；产物留在 `android/app/build/outputs/`，不拷进仓库。
- 未真机实测过的功能，在文档与 release 描述里显式标注"待真机验证"，不写成已验证。

## Android 发版流程（按顺序）

### 1. 递增版本（两处必须同步）

- `android/app/build.gradle.kts` → `versionCode` / `versionName`
- `android/app/src/main/AndroidManifest.xml` → `android:versionCode` / `android:versionName`

规则：新增功能 → `versionName` 进 minor（`1.0` → `1.1`）；仅修 bug → patch。`versionCode` 每次发版 +1。Git tag 用 `v<versionName>`（上游用 `20260219-2` 这类日期式 tag，不要混用）。

### 2. 构建正式版（签名走环境变量）

```powershell
# 前置：KEY_STORE / KEY_STORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD 四个环境变量已设置
cd android
./gradlew.bat assembleRelease --offline
# 产物：android/app/build/outputs/apk/release/app-release.apk
```

- 签名配置只从环境变量读取，缺任一项 Gradle 直接抛异常；**禁止硬编码密码或回退默认值**。
- `--offline` 可用（`GRADLE_USER_HOME=D:\dev\.gradle` 有腾讯镜像缓存）；release 变体固定**仅 arm64-v8a、不混淆**。
- Git Bash 里 `cmd.exe /c "gradlew.bat …"` 会静默不执行，必须写 `./gradlew.bat`。
- 仅改 Java/Gradle/Manifest 时不需要重跑 native；改了 `*.PAS` 或 SDL2 才先跑 `powershell -ExecutionPolicy Bypass -File ./build-android.ps1`（工具链路径可用 `FPC_ANDROID_HOME` / `ANDROID_NDK_HOME` / `ANDROID_NDK29_HOME` 覆盖）。

### 3. 产物校验（判据，不要凭"BUILD SUCCESSFUL"收尾）

```bash
BT=/d/dev/android_sdk/build-tools/37.0.0
$BT/aapt2.exe dump badging app-release.apk | grep -E 'package:|native-code'   # versionCode/versionName/ABI
$BT/apksigner.bat verify --verbose --min-sdk-version 23 app-release.apk        # v1+v2 true（AGP 默认不签 v3，正常）
```

`apksigner verify` 不带 `--min-sdk-version 23` 时 v1/v2 会恒显示 `false`（按 minSdk 区间判定），别误判成未签名。

### 4. 推送并打 tag

```bash
git push origin main
git tag v1.1.0 && git push origin v1.1.0
```

### 5. 创建 GitHub release（中英双语）

资产重命名后放仓库外（如 `D:/Temp/`），文件名含版本与 ABI：`mario-android-v1.1.0-arm64.apk`。

```bash
gh release create v1.1.0 "D:/Temp/mario-android-v1.1.0-arm64.apk" \
  --repo pisces312/mario-and-luigi \
  --title "v1.1 — 蓝牙手柄与按键自定义 / Gamepad & key remapping" \
  --notes-file D:/Temp/release-v1.1.md
```

`--repo` 必写：本仓是 fork，`gh` 会把 `upstream` 远端（pdromnt/mario-and-luigi）当默认仓库，报 "tag has not been pushed to pdromnt/…"。建完用 `gh release view v1.1.0 --repo pisces312/mario-and-luigi --json assets,name` 复核标题与资产。

描述结构：中文段（新增 / 变更 / 已知限制 / 安装）+ English 段镜像；安装段固定提醒 **仅 arm64-v8a、Android 5.0+（minSdk 21）、设备已装 debug 版需先卸载**（release 与 debug 签名互斥，`adb install -r` 会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。

本机对 `github.com` 直连 HTTPS 超时，`gh` / `api.github.com` 正常；下载 release 资产走 `gh api`。

## 已知坑（实测过）

- **Java 源码里的图标字符必须写单反斜杠 `"\u25C0"`**。写成 `"\\u25C0"` 能编译通过，但运行时得到 6 字符字面量 `\u25C0`，触控层按钮会显示转义文本而不是符号（2026-10-14 javac 实测 `len=6`）。校验产物：读 APK 内 `classes.dex` 统计 `E2 97 80`（◀ 的 UTF-8 字节）是否出现。
- 触控层/映射层新增按键时，`setVisibility(GONE)` 与 `onDetachedFromWindow` 前必须 `releaseAll()`，否则 SDL 侧卡键（角色一直往一个方向跑）。
- `SDLActivity.mSurface.setOnGenericMotionListener` 已被 SDL 占用，摇杆拦截只能在 Activity 的 `dispatchGenericMotionEvent` 做。
