# Mario & Luigi — 构建环境与命令参考

> 用途：本机（Windows 11, 上海用户机）从源码重新构建 Windows 版与 Android 版的标准参考。
> 配套：`PLAN.md`（决策与死路记录 §8/§9/§10）。
> 更新：2026-09-06 整理 Android 阶段路径与命令；2026-10-14 增补 §3.3 native 库 ELF 体检。所有路径均经实际构建验证。

## 0. 两条构建链总览

| 产物 | 源码入口 | 编译器 | 运行依赖 | 交付 |
|---|---|---|---|---|
| Windows 版 `mario.exe` | `MARIO.PAS`（program） | FPC 3.2.2 i386（32 位） | `SDL2.dll`（32 位，仓库根已有） | 仓库根 |
| Android 版 APK | `MARIO_ANDROID.PAS`（library+SDL_main） | FPC 3.2.2 交叉 + NDK | SDL2 由 NDK29 编出 `libSDL2.so` | `android/app/build/outputs/apk/debug/app-debug.apk` |

**源码文件角色（重要）**：
- `MARIO.PAS` = Windows 版主程序（`program Mario;` + `{$S+}` + `{$APPTYPE GUI}` + `{$R mario.res}`，存档名取自 `ParamStr(0)`）
- `MARIO_ANDROID.PAS` = Android 专用副本（`library mario_android;` + `{$S-}` + `exports SDL_main;`，存档名固定 `MARIO.CFG`/`MARIO.SAV` 到 filesDir）
- `JOYSTICK.PAS` / 其余单元 = 两版共享，`JOYSTICK.PAS` 内含 `{$IFNDEF ANDROID}` 条件编译
- 两文件须同步维护（目前是独立副本，未做单文件条件合并——`program`/`library` 是语法级关键字，FPC 无法靠 `{$IFDEF}` 切换）

## 1. 工具链与路径清单（本机已装）

### 1.1 Windows 版
| 项 | 路径/值 |
|---|---|
| FPC 3.2.2（完整安装，含 windres） | `D:\dev\FPC` |
| 32 位编译器 | `D:\dev\FPC\bin\i386-Win32\ppc386.exe` |
| SDL2 绑定单元 | 仓库根 `SDL2-for-Pascal\` |
| SDL2.dll（32 位） | 仓库根（随 mario.exe 同目录交付） |
| 构建脚本 | `build.bat`（上游原版，默认找 `C:\FPC\3.2.2`——**本机需显式指定**，见 §2） |

### 1.2 Android 版
| 项 | 路径/值 |
|---|---|
| FPC 3.2.2 交叉安装器产物（innounp 解包部署） | `D:\dev\FPC-android` |
| 交叉编译器（i386-win32 宿主） | `D:\dev\FPC-android\bin\i386-win32\ppcrossa64.exe` / `ppcrossx64.exe`（另含 386/arm/mipsel） |
| FPC 平台 RTL | `D:\dev\FPC-android\units\{aarch64,x86_64}-android\rtl` |
| NDK r21e（FPC 链接用 GNU 4.9 binutils + android-21 平台库） | `D:\dev\android_sdk\ndk\21.4.7075529` |
| NDK 29（SDL2 C 库编译，脚本默认值，产物 16 KB 页对齐） | `D:\dev\android_sdk\ndk\29.0.14206865` |
| SDL2 源码（gitignored） | `android\3rd\SDL\`（已含 Android.mk） |
| SDK 平台 36 / build-tools 36 | `D:\dev\android_sdk\platforms\android-36` 等 |
| adb | `D:\dev\android_sdk\platform-tools\adb.exe` |
| JDK（Android Studio JBR 17） | `JAVA_HOME=D:\dev\AndroidStudio\jbr` |
| Gradle 9.4.1（wrapper，腾讯镜像） | `android\gradle\wrapper\gradle-wrapper.properties` |
| AGP 9.2.0 | `android\build.gradle.kts` |
| Gradle 用户目录（离线缓存） | `GRADLE_USER_HOME=D:\dev\.gradle` |
| Python（验证截图像素用） | `D:\dev\miniconda3\python.exe`（PIL pillow 已装） |
| 模拟器 | AVD `pixel6`（x86_64 / API34，emulator-5554，可 adb root） |

### 1.3 环境变量注意
- **PATH 已移除 `D:\dev\FPC\bin`**（避免与交叉版混用），FPC 一律显式全路径调用
- Android 构建时脚本临时把 NDK r21e 的 binutils 目录加进 PATH（脚本内完成）
- 构建 Android APK 时确保 `GRADLE_USER_HOME=D:\dev\.gradle`（离线可用，腾讯镜像缓存）
- 工具链固定路径（`D:\dev\...`）可在调用脚本前用环境变量覆盖：`FPC_HOME` / `FPC_ANDROID_HOME` / `ANDROID_NDK_HOME`（r21e）/ `ANDROID_NDK29_HOME`（NDK 29）；脚本默认仍指向 `D:\dev\...`，不设置则行为不变

## 2. Windows 版构建命令

```bat
REM 在仓库根执行（32 位目标，Integer=16bit 语义与 1994 原版一致）
"D:\dev\FPC\bin\i386-Win32\ppc386.exe" -Mtp -Ci- -Cr- -Sg -Si -O2 -FuSDL2-for-Pascal -Fu. -FE. mario.pas
REM 若 mario.res 缺失: "D:\dev\FPC\bin\i386-Win32\windres.exe" --preprocessor=cat -i mario.rc -o mario.res
REM 运行: 同目录需 SDL2.dll（32 位）
```

## 3. Android 版构建命令

```powershell
# 一键脚本（已完成全部路径/参数封装；换 NDK 无需手动清理，obj/.ndk-stamp 守卫会自动全量重编，见 §3.3 第 5 条）：
powershell -ExecutionPolicy Bypass -File .\mario-and-luigi\android\build-android.ps1
# 然后打 APK：
cd mario-and-luigi\android
.\gradlew.bat assembleDebug --offline
# APK 输出：
#   app\build\outputs\apk\debug\app-debug.apk
```

### 3.1 关键编译参数（等价手写命令形态，x86_64 示例）
```text
ppcrossx64 -Tandroid -Mtp -Ci- -Cr- -Sg -Si -O- -dANDROID
  -FD<r21 x86_64-4.9 bin> -Fu<rtl x86_64-android> -Fu<unitDir> -Fu<repo> -Fu<repo>\SDL2-for-Pascal
  -FU<unitDir> -k-L<r21 arch-x86_64\usr\lib64> -k-L<jniLibs x86_64>
  -XP x86_64-linux-android- -o<out> MARIO_ANDROID.PAS
```
- **必须在仓库根执行**（`{$I sprites\*.inc}` 相对路径）
- arm64 对应 `ppcrossa64` + `aarch64-linux-android-4.9` + `arch-arm64\usr\lib`
- `-O-` 必须（Android 下 `-O2` 进 LEVEL 1 崩溃，见 PLAN.md §10.2）
- `{$S-}` 必须（Android 下栈检查崩溃，见 PLAN.md §10.1）

### 3.2 各 ABI 产物
| ABI | libmain.so | libSDL2.so |
|---|---|---|
| arm64-v8a | 345,064 B | 1,447,224 B |
| x86_64 | 353,432 B | 1,611,568 B |

### 3.3 打包 `.so` 的 ELF 体检（页对齐与兼容性告警）

当前产物（2026-10-14 清空 `obj/` 后用 NDK 29 重编）：

| ABI / 库 | Magic · Class · Type · Machine | `PT_LOAD p_align` | `.note.android.ident` |
|---|---|---|---|
| arm64-v8a/libSDL2.so | `7f 45 4c 46` · ELF64 · DYN · AArch64 | **0x4000** | r29（构建号 14206865） |
| arm64-v8a/libmain.so | `7f 45 4c 46` · ELF64 · DYN · AArch64 | **0x1000** | r21e（构建号 7075529） |
| x86_64/libSDL2.so | ELF64 · DYN · X86-64 | **0x4000** | r29 |
| x86_64/libmain.so | ELF64 · DYN · X86-64 | **0x1000** | r21e |

1. **ELF 头全部合法**：magic / class / type / machine / `e_shoff` / program header 均正常，四个库 readelf 无报错。
2. **SDL2 侧已 16 KB 对齐，libmain 仍是 4 KB**：FPC 3.2.2 只能用 r21e 的 GNU as/ld 链接（§5 坑 3/4），所以 `libmain.so` 的 `p_align` 与 `.note.android.ident`（r21e、min/target SDK 位置为 0）都没变。**结论：整包仍不满足 16 KB 页设备要求，任何文档与 release 描述不得写"已支持 16 KB 页"。** 方向是单向的——16 KB 对齐的库在 4 KB 设备上照常可用，反之 linker 会以 "misaligned ELF segment" 拒绝。
3. **libmain 若要跟上**（**未实测**）：把 `-z max-page-size=16384` 经 FPC 的 `-k` 逐参透传给 r21e 的 ld，先验证那代 binutils 是否认这个 `-z` 选项。
4. **判据**：`zipalign -c -P 16` 退出码 0 只保证 APK 内 `.so` 条目的数据起始偏移按 16 KB 摆位，**不校验 ELF 的 `p_align`**，单用会假阴性；真判据是 `llvm-readelf -l` 看 `p_align`。
5. **ndk-build 增量陷阱（踩过，现已由脚本守卫）**：`android/obj/` 里留着旧产物时，即使换成 NDK 29 的 `ndk-build.cmd`，它也只打印 `Install : libSDL2.so => …` 把旧 `.so` 原样复制进 `jniLibs`——md5 一字不变，mtime 却会刷新，脚本照样打「完成」。2026-09-06 就是这么错的：`obj29/local/*/libSDL2.so`（r29、`0x4000`）当时已经编出来了，装进 `jniLibs` 的却是 `obj/` 里的 r27d 产物（`0x1000`），本文与 PLAN.md §10.5 因此长期记成"NDK 29 编译、16KB 页对齐"。现 `build-android.ps1` 把 `ndk-build.cmd` 的路径写进 `obj/.ndk-stamp`，比对不一致就先删 `obj` 再编。2026-10-14 实测：同版本跑两次 → 只 `Install`、md5 不变（增量正常）；把 stamp 改成 r27d 路径再跑 → 打印 `clearing obj for full rebuild` 并逐文件 `Compile`，产物 md5 与手动清理后重编完全一致，`p_align` 仍 `0x4000`、note 仍 r29。守卫只在 stamp 存在且不同才清 `obj`，首次运行（无 stamp）不动增量缓存。复核仍只认两条：md5 + `p_align`。

兼容性告警定性（真机 HONOR BKQ-AN80：Android 17 / `ro.build.version.sdk=37` / MagicOS 11 / `getconf PAGE_SIZE`=4096 / abi `arm64-v8a`）：

- 现象：`install -r` 当日 debug 包后启动弹了一次与 `.so` 头部有关的兼容性对话框；游戏运行正常，`force-stop` 重开未复现，logcat 无任何 linker 报错 → 是**警告**而非加载失败。两包差异已核：debug `application-debuggable` + `native-code: arm64-v8a x86_64`，release 仅 `arm64-v8a`，均 versionCode 3 / 1.2。
- 与 Java 侧改动无关：v1.1.0 / v1.2.0 release APK 与当日 debug APK 里 `lib/arm64-v8a/libSDL2.so`（md5 `f326295721de22e1…`）、`libmain.so`（`e460586766a02351…`）三份完全一致，`jniLibs` mtime 停在 2026-09-06 12:14。
- 未定项：弹窗原文尚未拿到，"16 KB 对齐"与"note 版本过旧"谁触发它没有定论；重编后若再弹需截图定性。
- 重编后回归（2026-10-14 真机）：`nativeloader: Load …/lib/arm64/libSDL2.so … ok` 与 `…/libmain.so … ok`，SDLActivity 前台、无 `Fatal signal`，LEVEL 1 画面与触控层正常。

## 4. 验证命令

```powershell
# 导出符号核验（arm64）
& "D:\dev\android_sdk\ndk\29.0.14206865\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-nm.exe" -D --defined-only "D:/.../jniLibs/arm64-v8a/libmain.so" | Select-String SDL_main

# 模拟器安装/启动
& "D:\dev\android_sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
& $adb shell am start -n org.libsdl.mario/org.libsdl.app.SDLActivity

# 截图（必须 pull，PowerShell > 重定向会损坏 PNG）
& $adb shell screencap -p /sdcard/x.png; & $adb pull /sdcard/x.png <本地路径>

# 崩溃日志
& $adb logcat -d | Select-String 'Fatal signal'

# 存档落盘（adb root 后）
& $adb shell ls -la /data/data/org.libsdl.mario/files/

# native 库 ELF 体检（页对齐判据，见 §3.3）
$RE = "D:\dev\android_sdk\ndk\29.0.14206865\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe"
Get-ChildItem app\src\main\jniLibs -Recurse -Filter *.so | ForEach-Object {
    "{0}/{1}  {2} B" -f $_.Directory.Name, $_.Name, $_.Length
    & $RE -l $_.FullName | Select-String "  LOAD "      # 末列 Align：SDL2 应为 0x4000，libmain 仍 0x1000
}
& $RE -x .note.android.ident app\src\main\jniLibs\arm64-v8a\libSDL2.so   # 读 r29 / r21e 标签
# 重编是否真的生效（obj/ 未清时会假成功）：比对 md5
Get-FileHash app\src\main\jniLibs\arm64-v8a\libSDL2.so -Algorithm MD5
# APK 的 zip 层 16KB 对齐（只查 zip 偏移，不校验 ELF p_align）
& "D:\dev\android_sdk\build-tools\37.0.0\zipalign.exe" -c -P 16 app\build\outputs\apk\debug\app-debug.apk
```

## 5. 关键坑速查（详见 PLAN.md §10）
1. `{$S+}` → Android 崩溃/静默退出 → 用 `{$S-}`
2. `-O2` → 进 LEVEL 1 SIGSEGV（SYSTEM.IndexByte）→ 用 `-O-`
3. NDK29 平台库为 LLVM bitcode，GNU ld 无法解析 → FPC 侧用 r21e
4. ld.lld 不解析 FPC 链接脚本 → 同上
5. 官方 FPC 交叉安装器 GUI/静默全拒 → 用 innounp 解包部署到 D:\dev\FPC-android
6. 触摸层防卡键：TouchPadView 三守卫（onDetachedFromWindow 释放/ACTION_UP 兜底/滑出视为释放）
7. 模拟器自动退出流程受窗口焦点抖动干扰，存档落盘建议真机验证
8. `libmain.so` 的 `PT_LOAD p_align` 仍是 0x1000（4 KB），整包不满足 16 KB 页设备要求；`zipalign -P 16` 通过属假阴性，判据只认 `llvm-readelf -l`（详见 §3.3）
9. `android/obj/` 不清就换 NDK → ndk-build 只 `Install` 旧产物、md5 不变却照样报完成，mtime 也会刷新；重编后必须比对 md5 + `p_align`（详见 §3.3 第 5 条）。脚本已用 `obj/.ndk-stamp` 自动检测并清理
10. 含中文的 `.ps1` 必须存成 **UTF-8 with BOM**（`build-android.ps1` 已带）：无 BOM 时 Windows PowerShell 5.1 按 GBK 解码，中文字节会跟后续 ASCII（字母、`}`、`\`）配成双字节字符并吃掉换行，代码被并进注释——`Parser::ParseFile` 零错误，运行时变量却是 `$null`（2026-10-14 实测）。复核：`(Get-Content $f).Count` 与实际行数比对，变少即被吞行

## 6. 常用源码/文件位置
| 文件 | 路径 |
|---|---|
| Android 入口 | 仓库根 `MARIO_ANDROID.PAS` |
| 触摸层 | `android\app\src\main\java\org\libsdl\app\TouchPadView.java` |
| 挂载点 | `SDLActivity.java` onCreate `setContentView(mLayout)` 之后（L419） |
| 构建脚本 | `android\build-android.ps1` |
| 游戏说明（玩家向） | 仓库根 `游戏说明.md` |
