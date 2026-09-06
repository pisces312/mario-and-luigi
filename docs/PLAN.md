# Mario & Luigi（DOS 1994）Windows SDL2 版 + Android 移植 —— 方案总记录

> **构建参考**：工具链版本/路径/命令见 [`docs/BUILD.md`](BUILD.md)；问题与解决方案见 §8 风险登记、§9 决策记录、§10 移植记录。

> 记录日期：2026-09-06
> 状态：方案已定（路线 A），Windows 构建进行中
> 本文档是该项目唯一权威方案记录，后续所有决策与执行步骤在此追加。

---

## 1. 项目背景与目标

- 游戏：*Mario & Luigi*，Mike Wiering 1994 年用 Turbo Pascal 编写的 DOS 版超级玛丽克隆（原版 57KB 单文件 `MARIO.EXE`，6 大关 + Turbo 二周目）。
- 痛点：**官网下载的原版 `MARIO.EXE` 在 Windows 11 上已无法运行**（16 位 DOS 程序，Win11 不兼容）。
- 目标：
  1. 用 SDL2 移植版构建出**可在本机 Windows 运行的版本**；
  2. 后续将该移植**编译到 Android**（路线 A：Free Pascal 交叉编译），产出可安装 APK。
- 约束：优先使用本机已有开发工具与环境；缺失工具先与用户确认再安装。

## 2. 源码仓库调研结论（GitHub API，2026-09-06 核验）

| 仓库 | 说明 | 最后推送 | 活跃度 | 备注 |
|---|---|---|---|---|
| **pdromnt/mario-and-luigi** | SDL2 移植版（Free Pascal + SDL2-for-Pascal），全部 DOS 硬件代码已 SDL2 化 | 2026-04-06 | **活跃（唯一）** | **选定为基础项目**；资源（精灵/关卡/字体/调色板）编译内嵌为类型常量 |
| mosheDO/MARIO-LUIGI-SOURCE-CODE-MIRROR | 原 TP 源码镜像 | 2024-09-01 | 停更 | 仅作源码对照 |
| jazzyjester/Mario-And-Luigi | 原 TP 源码镜像 | 2022-02-26 | 停更 | 仅作源码对照 |
| leonhad/mario-luigi | 原 TP 源码镜像 | 2019-07-11 | **已归档** | 仅作源码对照 |

> 权威源码：作者官网 `wieringsoftware.nl` 提供 `MARIOSRC.ZIP`（TP 6/7 完整源码，223KB）与 `MARSRC55.ZIP`（TP 5.5，236KB）。

## 3. 下载源记录

| 产物 | 来源 | 说明 |
|---|---|---|
| `MARIO.EXE`（57KB 完整版） | `http://www.wieringsoftware.nl/mario/download.html` | 官方正式版，官网附第 4 关画面用于鉴别"完整版 vs 被盗 beta 版（4 关）" |
| `MARIOSRC.ZIP` / `MARSRC55.ZIP` | 同上站点 source 页 | 原版源码，编译命令 `TPC /M /L MARIO` |
| SDL2 移植版源码 | `https://github.com/pdromnt/mario-and-luigi.git` | 含 `SDL2.dll`、SDL2-for-Pascal 绑定、170+ 精灵 |
| SDL2 官方源码（Android 阶段） | `https://libsdl.org/release/SDL2-<ver>.tar.gz` | 仅 Android 工程需要（Windows 版用仓库自带 dll） |

## 4. 移植路线记录

### 路线 A —— 选定方案：FPC 交叉编译（Pascal 直出 Android）

- 思路：`pdromnt/mario-and-luigi` 已是纯 Free Pascal + SDL2，资源全部内嵌；
  用 FPC `aarch64-android` 交叉编译器把游戏编成 `libmain.so`，套 SDL 官方 Android Java 胶水层出 APK。
- 优点：无重写成本；复用官方 SDL 工程的 Java 层、触摸注入、assets 提取等成熟做法。
- 关键风险（需 spike 验证）：
  1. **SDL2-for-Pascal 绑定在 Android 的可用性**（该库主战场是桌面）；
  2. **FPC 生成的 .so 能否导出 `SDL_main` 符号**被 SDL 的 Java 层 `dlsym` 到；
  3. FPC Android 生态边缘，工具链需要 fpcupdeluxe 或官方 cross 包搭好。
- 工程注意：`Android.mk` 不能照搬 skill 的 `wildcard *.c`（游戏是 .pas），
  需在 ndk-build/Gradle 中挂 FPC 交叉编译自定义步骤。

### 路线 B —— 备选但放弃：Pascal → C 重写后走 skill 原生路径

- 思路：把游戏逻辑翻译成 C，走本地 `sdl2-android-port` skill 的 ndk-build 原路径。
- 放弃原因：数千行 Pascal + 170+ 精灵文件重写，工作量大、易引入行为偏差。

### 路线 C —— 兜底对照：Android 上跑 DOSBox 模拟原版

- 思路：在 Android 用 DOSBox 直接运行官方 `MARIO.EXE`。
- 定位：零移植成本、立即可玩；**不是 SDL2 方案**，仅作对照与功能基准。

## 5. 本机环境清单（2026-09-06 探查）

| 工具 | 状态 | 位置/版本 |
|---|---|---|
| Git | ✅ 已有 | `D:\dev\git\cmd\git.exe` |
| Gradle | ✅ 已有 | 9.4.1（`D:\dev\gradle-home`） |
| Android SDK / NDK | ✅ 已有 | `D:\dev\android_sdk`；NDK 25.1/27.0/**27.3.13750724**/28.2/29.0；platforms 33~37；build-tools 33~37 |
| JDK | ✅ 已有 | `D:\dev\AndroidStudio\jbr`（JAVA_HOME） |
| winget | ✅ 已有 | 可用于安装 FPC |
| **Free Pascal（FPC 3.2.2）** | ✅ 完整可用 | **官方 i386-win32 完整安装已迁移至 `D:\dev\FPC`**（含 ppc386/fpcres/全套 binutils/units；用户已清空原精简交叉包） |
| SDL2.dll | ✅ 仓库自带（32 位） | 构建曾验证官方 2.32.4 64 位替代路线（`downloads\SDL2-2.32.4-win32-x64.zip`），最终保持仓库原 32 位版 |
| 参考项目 `D:\3rd-party-projects\SDLPoP / sdlpal` | ❌ 本机不存在 | skill 中的参考实现，仅作思路参考，不可复用为本地依据 |

> 网络备忘：本机访问 GitHub releases 直连超时（exit 28），下载统一走 `libsdl.org` / `downloads.freepascal.org` 官方直链。
> Android 阶段待装：`aarch64-android` 交叉编译器（方案见第 7 节）。

## 6. Windows 版构建（✅ 已完成，2026-09-06）

### 6.1 执行记录

1. ✅ 克隆 `pdromnt/mario-and-luigi` → `mario-and-luigi/`（完整：MARIO.PAS 全部单元、SDL2.dll、SDL2-for-Pascal、sprites/）
2. ✅ 备份官方 `MARIO.EXE`（57,480B）/ `MARIOSRC.ZIP`（227,568B）→ `downloads/`
3. ✅ FPC 工具链就位：官方 i386-win32 **完整安装**（ppc386/fpcres/全套 binutils）——用户清空原精简包后，完整安装已迁移至 `D:\dev\FPC`（2026-09-06）
4. ✅ 编译：`ppc386.exe -Mtp -Ci- -Cr- -Sg -Si -O2 -FuSDL2-for-Pascal -Fu. -FE. mario.pas`
   - 结果：36,099 行编译，0.9 秒；3 warning / 29 note（均未使用变量，无害）
5. ✅ 产物：`mario-android-port\mario-and-luigi\mario.exe`（**32 位 i386**，212,480 字节，含内嵌资源/图标）
6. ✅ 启动验证：进程存活、窗口标题 "Mario & Luigi"（SDL2 初始化、全部资源加载成功）
7. ✅ 迁移后回归构建通过（build-win32.ps1 已指向 `D:\dev\FPC\bin\i386-win32\ppc386.exe`，产物一致）
8. ✅ **用户实机确认：Windows 上可正常游玩**
9. ✅ 游戏说明文档：`mario-android-port\游戏说明.md`（运行/操作/关卡/构建/FAQ）

### 6.2 复现构建

```powershell
# 一键脚本：build-win32.ps1（项目根）
& 'D:\workspace\mario-android-port\fpc-full-tmp\bin\i386-win32\ppc386.exe' `
  -Mtp -Ci- -Cr- -Sg -Si -O2 -FuSDL2-for-Pascal -Fu. -FE. mario.pas
# 运行：cd mario-and-luigi && .\mario.exe（依赖同目录 SDL2.dll）
```

### 6.3 构建期环境适配备忘

- 仓库自带 SDL2.dll 为 32 位 → 编译目标必须是 i386（ppc386）
- 曾验证 64 位替代路线：官方 SDL2 **2.32.4** win64（`downloads\SDL2-2.32.4-win32-x64.zip`，libsdl.org）+ `ppcrossx64` 亦可编译，但最终选择符合作者原意的 32 位路线
- 构建脚本：`build-win32.ps1`（FPC 指向 `D:\dev\FPC\bin\i386-win32\ppc386.exe`）
- 本机 GitHub releases 直连超时 → 统一走 `libsdl.org` / `downloads.freepascal.org` 官方直链

### 6.4 Windows 阶段收尾

- [x] 用户实机试玩确认：Windows 正常游玩
- [x] FPC 完整安装迁移至 `D:\dev\FPC`（用户清空原精简交叉包）
- [x] 游戏说明文档：`游戏说明.md`

## 7. Android 移植步骤（路线 A，✅ 已启动 2026-09-06）

### 7.0 本阶段依赖

- [x] Windows 版验证完成（资源完整、工具链稳定）
- [x] **`aarch64-android` 交叉编译器已部署**（`D:\dev\FPC-android`，手动解包绕过官方安装器，见 7.3）
- [ ] 准备 SDL2 Android 工程骨架（SDL 官方 android-project Java 层，skill §9）

### 7.1 步骤清单

1. [x] **交叉编译器就位**：`D:\dev\FPC-android\bin\i386-win32\ppcrossa64.exe`（3.2.2）+ `units\aarch64-android\*` + NDK r21e（`D:\dev\android_sdk\ndk\21.4.7075529`）
2. [x] **Spike 1（交叉编译链路）已通过**：ppcrossa64 + NDK r21e 编译 hello.pas → ELF64/AArch64 DYN 产物（69,936B）。**结论：FPC 交叉编译产物与 NDK 链接器完全兼容**
3. [x] **Spike 2（最小 SDL2 APK）已通过**：libSDL2.so（NDK 27.3 交叉编译）+ libmain.so（FPC，导出 SDL_main）+ Gradle 9.4.1/AGP 9.2.0 工程 → **app-debug.apk 构建成功**，含双 ABI（arm64-v8a + x86_64）
4. [x] **Spike 2 模拟器验证已通过（pixel6 / x86_64）**：安装→启动→`SDL_main from library libmain.so` 日志→渲染循环 50fps→截图确认深色背景+橙色方块。**FPC SDL_main 完整链路在 Android 真环境跑通**
   - 教训：manifest activity 需全限定名 `org.libsdl.app.SDLActivity`（namespace 与 Java 包不同）
   - 教训：x86_64 的 NDK 平台库在 `arch-x86_64/usr/lib64`（非 lib）；toolchain 目录名 `x86_64-4.9`
   - 教训：PowerShell `>` 重定向会损坏 screencap 二进制，须用 `adb shell screencap` + `adb pull`
   - 图标：原版 `icon.ico`（32×32）→ System.Drawing 高质量放大 → 5 个 mipmap 尺寸
5. [ ] **移植游戏本体**：mario 全部单元交叉编译 → 替换 sdl_hello（入口改造：library + SDL_main 包装，游戏逻辑放入 SDL_main）
6. [ ] 处理存档路径（游戏有 GAME SELECT 存档菜单，GetSaveName 基于 ParamStr(0) → Android 为 "app_process"）：JNI 层 `chdir(filesDir)`（skill §5 方案，不改游戏源码）
7. [ ] 触摸控制注入（skill §7 方案：Java 覆盖层调用 `onNativeKeyDown`，注意防卡键三守卫；游戏为键盘操作 ←→/Alt/Ctrl/Space）
8. [ ] 禁用加速度计作为摇杆：`SDL_SetHint(SDL_HINT_ACCELEROMETER_AS_JOYSTICK, "0")`（skill §8）
9. [ ] 验证：`gradle assembleDebug` → 检查 `lib/` 与资源 → 真机实玩（输入劫持与卡键只能在真机发现）
   - 注：用户真机（arm64）曾闪退，当时 APK 仅有 arm64-v8a；模拟器验证通过后需用户重试真机，若仍闪退抓 logcat 分析

### 7.2 Spike 设计（先行验证）

- 最小目标：FPC 交叉编译 hello + SDL2 窗口，打包 APK，在模拟器/真机显示画面
- 关键问题：
  1. [x] FPC aarch64 交叉编译产物与 NDK 链接器（ld.bfd）兼容 —— **已验证通过**
  2. [x] `SDL_LibName` 绑定：Android 目标命中 `{$IFDEF UNIX}` → `libSDL2.so` + `{$LINKLIB libSDL2}`，**无需改绑定**（sdl2.pas 在 -Tandroid 下编译通过，链接自动 -lSDL2）—— **已验证通过**
  3. [x] FPC .so 导出 `SDL_main`：library + `exports SDL_main` → `llvm-readelf --dyn-syms` 确认 `SDL_main FUNC GLOBAL DEFAULT` —— **已验证通过**
- 结论分支：
  - **通过 → 路线 A 全部步骤继续（当前状态）**

### 7.3 交叉编译器部署备忘（绕过官方安装器）

- 官方 `fpc-3.2.2.i386-win32.cross.android.exe`（Inno 5.3.11）**无法静默/GUI 安装**：
  - 注册表被清空（用户卸载时），Inno 脚本默认目录 `{sd}\FPC\3.2.2` 解析失败 → 命令行 `/DIR` 一律被拒（"must enter a full path"）
  - 主安装器重装也不写任何注册表（FPC 走便携式安装）→ 无法喂给交叉安装器
  - NDK 校验页同样失败（r22/r29 都报 "does not point to an Android NDK"）
- **解决方案**：`innounp 0.50`（D:\workspace\mario-android-port\innounp.exe，用 bsdtar 解其 RAR 发行包获得）解包安装器 → 766MB 内容部署到 `D:\dev\FPC-android`。**完全绕开安装器脚本**。
- **交叉编译命令要点**（Spike 1 验证）：
  - 库路径用 `-k"-L<NDK platform lib dir>"`（**`-FL` 参数不生效**，必须 -k 直传链接器）
  - binutils 目录需在 PATH 中（或 -FD + PATH），前缀 `-XPaarch64-linux-android-`
  - NDK 配套：**r21e**（21.4.7075529）的 `aarch64-linux-android-4.9` GCC binutils（FPC 3.2.2 时代标准；r22+ 的 4.9 binutils 也可用）
- **NDK 29 升级（2026-09-06）**：
  - [x] SDL2 改用 **NDK 29**（29.0.14206865）ndk-build 编译 → **16KB 页对齐确认**（LOAD 对齐 0x4000，兼容 Android 15+/16KB 设备）；模拟器回归通过
  - [ ] **FPC 3.2.2 无法用 NDK 29 平台库**（已探明，硬限制）：NDK 29 的 `libc.so` 等为 **LLVM bitcode 包装**，GNU ld 无法链接；而 FPC 生成的 GNU ld 链接脚本（`INSERT AFTER`）**ld.lld 不支持**。结论：FPC 侧保持 GNU as/ld + r21e 平台库；SDL2 侧 NDK 29。运行时 ABI 兼容。尝试记录：`-Aclang`（FPC 3.2.2 仅 Darwin 目标支持）、ld.lld wrapper（`INSERT` 不支持 + `.res` 需 `-T` 显式脚本）、NDK29 平台库 + GNU ld（bitcode 解析失败）
- 本机新增：NDK r21e（21.4.7075529）+ r22（22.1.7171670）经 sdkmanager 安装；7-Zip 未装成（winget 装后找不到，不影响）

## 8. 风险登记

| 风险 | 等级 | 缓解 |
|---|---|---|
| SDL2-for-Pascal 在 Android 不可用 | 高 | 先做 spike 再铺开；若失败则评估仅用 SDL 的 C API 手写薄绑定层 |
| FPC .so 无法导出 SDL_main | 高 | spike 验证；FPC 可用 `-XD`/exports 机制导出符号 |
| 官网下载文件完整性（http 明文） | 低 | 下载后杀毒扫描；用官网 Level 4 画面鉴别完整版 |
| 32 位目标差异（Integer 16 位溢出行为） | 中 | 严格按仓库 build.bat 的 fpc 参数与目标平台编译，不做"顺手"改动 |
| Android 真机输入/卡键问题 | 中 | 只能真机验证；套用 skill 三守卫方案 |

## 9. 决策记录

| 日期 | 决策 | 依据 |
|---|---|---|
| 2026-09-06 | 基础项目选 `pdromnt/mario-and-luigi` | 唯一活跃维护；已完成 SDL2 化，资源内嵌 |
| 2026-09-06 | Android 移植选路线 A（FPC 交叉编译） | 无重写成本；skill 工程骨架知识可复用 |
| 2026-09-06 | 先构建 Windows 版，再进入 Android 阶段 | 先验证资源完整与工具链，降低 Android 阶段风险 |
| 2026-09-06 | 缺失 FPC → 向用户确认安装方式 | 用户约束：缺失工具先确认 |
| 2026-09-06 | FPC 实为精简交叉包（仅 ppcrossx64）→ 用官方 i386-win32 完整安装（fpc-full-tmp）中的 ppc386 构建 | 仓库 SDL2.dll 为 32 位，需 i386 目标；完整安装含 fpcres/全套 binutils |
| 2026-09-06 | 保持 32 位路线（恢复原 SDL2.dll），64 位替代路线仅作记录 | 符合作者交付形态与上游意图 |
| 2026-09-06 | Windows 版构建完成并启动验证通过 → 等待用户实机试玩确认 → 进入 Android 阶段（路线 A） | 先验证资源完整与工具链，降低 Android 阶段风险 |
| 2026-09-06 | 用户确认 Windows 正常游玩；FPC 完整安装迁移至 `D:\dev\FPC`（用户清空原精简包） | 统一工具链位置 |
| 2026-09-06 | 游戏说明文档交付（`游戏说明.md`）；Android 阶段启动，先做 Spike（最小 SDL2 APK） | 先验证两条关键风险再铺开 |

## 10. 游戏本体 Android 移植（已完结）

> 时间：2026-09-06（Android 阶段收尾）。全部决策与死路记录，供后续维护参考。

### 10.1 崩溃根因一：{$S+} 栈检查（已修复）

- **现象**：完整版 APK 装模拟器即崩（Fatal signal SIGABRT，SDLThread/RenderThread），或静默退出。
- **二分定位**：模板编译指令行 `{$A+} {$B-} {$G+} {$I-} {$R-} {$S+} {$V-} {$X+}`——任意含 `{$S+}` 的变体（含空单元）死；`{$S-}` 后存活。
- **修复**：`MARIO_ANDROID.PAS` 头部改 `{$S-}`。**勿恢复 `{$S+}`**。

### 10.2 崩溃根因二：-O2 优化（已修复，勿重试）

- **现象**：{$S-} 后过菜单进入 LEVEL 1 时 SDLThread SIGSEGV（tombstone：libmain 偏移 0x36c7 = `SYSTEM_$$_INDEXBYTE`，null 解引用）；SDL_PollEvent 主循环在跑但输入无效。
- **修复**：`-O2` → `-O-` 重编后完整游戏正常（标题→菜单→LEVEL 1→移动→触摸跳跃全通）。
- **结论**：`-O2` 为已验证死路。

### 10.3 触摸控制（TouchPadView）

- `TouchPadView.java` 8 键：←→ 左下、跳/跑/发 右下竖排、顶部 暂停/声音/退出。
- 经 `SDLActivity.onNativeKeyDown/Up` 注入原生键事件；防卡键三守卫（onDetachedFromWindow 释放、ACTION_UP 兜底释放、滑出按钮视为释放）。
- 挂载点：SDLActivity.onCreate `setContentView(mLayout)` 之后（L419）。
- 验证：触摸 JUMP 按住 600ms 马里奥起跳（像素 y:834→487）；触摸 EXIT 回主菜单有效。

### 10.4 图标

- 原版标题画面（spike/g2.png 完整 "MARIO & LUIGI" 区域）裁剪压缩为 5 个 mipmap ic_launcher.png。

### 10.5 双 ABI 与工具链（已验证）

- FPC 3.2.2 交叉（`D:\dev\FPC-android`）：ppcrossa64/ppcrossx64，-Tandroid -Mtp -O-，GNU 4.9 binutils（NDK r21e）链接。
- SDL2 C 库：NDK 29 编译（16KB 页对齐，libSDL2.so arm64 1,460,976B / x86_64 1,618,576B）。
- 产物：libmain.so arm64 345,064B / x86_64 353,432B；APK 双 ABI 打包，arm64 导出 SDL_main 符号已验证（llvm-nm）。
- **死路**：NDK29 平台库（LLVM bitcode，GNU ld 无法解析）；ld.lld（不解析 FPC 链接脚本）。

### 10.6 验收记录（pixel6 模拟器 x86_64）

- 启动无崩溃、菜单全链路（Intro→存档→主→人数→游戏）、LEVEL 1 游玩、右移、触摸跳跃、触摸 EXIT 回菜单、长时间运行（>5 分钟）稳定。
- 存档（MARIO.CFG/MARIO.SAV 写 filesDir）代码路径确认（WriteConfig 于 SDL_main 尾部、GetConfigName/GetSaveName 固定文件名、SDL_AndroidGetInternalStoragePath chdir）；模拟器自动化退出流程受窗口焦点抖动干扰，落盘实机验收项留待真机。
- 真机 arm64 待用户装机验收。

### 10.7 git

- 提交 e6da2fe 已推送 origin（pisces312/mario-and-luigi）。
