# Mario & Luigi — 构建环境与命令参考

> 用途：本机（Windows 11, 上海用户机）从源码重新构建 Windows 版与 Android 版的标准参考。
> 配套：`PLAN.md`（决策与死路记录 §8/§9/§10）。
> 更新：2026-09-06（Android 阶段收尾时整理，所有路径均经实际构建验证）。

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
| NDK 29（SDL2 C 库编译，16KB 页对齐） | `D:\dev\android_sdk\ndk\29.0.14206865` |
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

## 2. Windows 版构建命令

```bat
REM 在仓库根执行（32 位目标，Integer=16bit 语义与 1994 原版一致）
"D:\dev\FPC\bin\i386-Win32\ppc386.exe" -Mtp -Ci- -Cr- -Sg -Si -O2 -FuSDL2-for-Pascal -Fu. -FE. mario.pas
REM 若 mario.res 缺失: "D:\dev\FPC\bin\i386-Win32\windres.exe" --preprocessor=cat -i mario.rc -o mario.res
REM 运行: 同目录需 SDL2.dll（32 位）
```

## 3. Android 版构建命令

```powershell
# 一键脚本（已完成全部路径/参数封装）：
powershell -ExecutionPolicy Bypass -File D:\workspace\mario-android-port\mario-and-luigi\android\build-android.ps1
# 然后打 APK：
cd D:\workspace\mario-android-port\mario-and-luigi\android
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
| arm64-v8a | ~345 KB | 1,460,976 B |
| x86_64 | ~353 KB | 1,618,576 B |

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
```

## 5. 关键坑速查（详见 PLAN.md §10）
1. `{$S+}` → Android 崩溃/静默退出 → 用 `{$S-}`
2. `-O2` → 进 LEVEL 1 SIGSEGV（SYSTEM.IndexByte）→ 用 `-O-`
3. NDK29 平台库为 LLVM bitcode，GNU ld 无法解析 → FPC 侧用 r21e
4. ld.lld 不解析 FPC 链接脚本 → 同上
5. 官方 FPC 交叉安装器 GUI/静默全拒 → 用 innounp 解包部署到 D:\dev\FPC-android
6. 触摸层防卡键：TouchPadView 三守卫（onDetachedFromWindow 释放/ACTION_UP 兜底/滑出视为释放）
7. 模拟器自动退出流程受窗口焦点抖动干扰，存档落盘建议真机验证

## 6. 常用源码/文件位置
| 文件 | 路径 |
|---|---|
| Android 入口 | 仓库根 `MARIO_ANDROID.PAS` |
| 触摸层 | `android\app\src\main\java\org\libsdl\app\TouchPadView.java` |
| 挂载点 | `SDLActivity.java` onCreate `setContentView(mLayout)` 之后（L419） |
| 构建脚本 | `android\build-android.ps1` |
| 游戏说明（玩家向） | 仓库根 `游戏说明.md` |
