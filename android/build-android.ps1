# build-android.ps1 — Mario & Luigi Android 侧一键构建
# 1) ndk-build 交叉编译 SDL2 → libSDL2.so (arm64-v8a)
# 2) ppcrossa64 交叉编译 libmain.so (FPC, 导出 SDL_main)
# 3) 两个 .so 复制到 app/src/main/jniLibs/arm64-v8a/
# 之后用 gradlew.bat assembleDebug 打 APK
#
# 用法: powershell -ExecutionPolicy Bypass -File build-android.ps1

$ErrorActionPreference = 'Stop'

$Root      = $PSScriptRoot
$Repo      = Split-Path $Root -Parent          # mario-and-luigi/
$SDLPath   = "$Root\3rd\SDL"
$JniLibs   = "$Root\app\src\main\jniLibs\arm64-v8a"

# --- 工具链（显式指定，不依赖 PATH） ---
$PPCross   = 'D:\dev\FPC-android\bin\i386-win32\ppcrossa64.exe'
$RTL       = 'D:\dev\FPC-android\units\aarch64-android\rtl'
$NDK       = 'D:\dev\android_sdk\ndk\21.4.7075529'
$NDKBin    = "$NDK\toolchains\aarch64-linux-android-4.9\prebuilt\windows-x86_64\bin"
$NDKLib    = "$NDK\platforms\android-21\arch-arm64\usr\lib"
$NDKBuild  = 'D:\dev\android_sdk\ndk\27.3.13750724\ndk-build.cmd'

# --- 1) SDL2 共享库 ---
Write-Host '=== [1/2] ndk-build libSDL2.so ==='
if (-not (Test-Path "$SDLPath\Android.mk")) { throw "SDL 源码缺失: $SDLPath" }
Push-Location $Root
try {
    cmd /c "`"$NDKBuild`" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=jni\Android.mk NDK_APPLICATION_MK=jni\Application.mk NDK_OUT=obj NDK_LIBS_OUT=app\src\main\jniLibs -j8 2>&1"
    if ($LASTEXITCODE -ne 0) { throw "ndk-build 失败 (exit=$LASTEXITCODE)" }
} finally { Pop-Location }

# --- 2) FPC libmain.so ---
Write-Host '=== [2/2] ppcrossa64 libmain.so ==='
$PascalMain = Join-Path $PSScriptRoot (Join-Path '..\..\spike' 'sdl_hello.pas')
# 编译 sdl2 单元（增量）
New-Item -ItemType Directory -Force -Path "$Root\build\sdl2-unit" | Out-Null
& $PPCross -Tandroid "-FD$NDKBin" "-Fu$RTL" "-Fu$Root\build\sdl2-unit" "-FU$Root\build\sdl2-unit" "$Repo\SDL2-for-Pascal\sdl2.pas"
if ($LASTEXITCODE -ne 0) { throw 'sdl2 单元编译失败' }
# 编译 libmain
$env:PATH = "$NDKBin;$env:PATH"
& $PPCross -Tandroid "-FD$NDKBin" "-Fu$RTL" "-Fu$Root\build\sdl2-unit" "-k-L$NDKLib" "-k-L$JniLibs" -XPaarch64-linux-android- -o"$Root\build\libmain" $PascalMain
if ($LASTEXITCODE -ne 0) { throw 'libmain 编译失败' }

# --- 3) 复制到 jniLibs ---
New-Item -ItemType Directory -Force -Path $JniLibs | Out-Null
Copy-Item "$Root\build\libmain" "$JniLibs\libmain.so" -Force
Write-Host "=== 完成: $JniLibs ==="
Get-ChildItem $JniLibs | ForEach-Object { Write-Host "  $($_.Name) ($($_.Length) B)" }
Write-Host '下一步: cd android && gradlew.bat assembleDebug'
