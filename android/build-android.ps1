# build-android.ps1 — Mario & Luigi Android 侧一键构建
# 1) ndk-build 交叉编译 SDL2 → libSDL2.so (arm64-v8a + x86_64)
# 2) ppcross* 交叉编译 libmain.so (FPC, 导出 SDL_main)
# 3) .so 复制到 app/src/main/jniLibs/<abi>/
# 之后用 gradlew.bat assembleDebug 打 APK
#
# 用法: powershell -ExecutionPolicy Bypass -File build-android.ps1

$ErrorActionPreference = 'Stop'

$Root      = $PSScriptRoot
$Repo      = Split-Path $Root -Parent          # mario-and-luigi/
$SDLPath   = "$Root\3rd\SDL"
$NDK       = 'D:\dev\android_sdk\ndk\21.4.7075529'          # FPC 链接用 (GNU binutils, FPC 3.2.2 硬依赖)
$NDKBuild  = 'D:\dev\android_sdk\ndk\29.0.14206865\ndk-build.cmd'  # SDL2 C 库编译用 (NDK 29, 16KB 页对齐)

# 说明: FPC 3.2.2 无法使用 NDK 29 平台库 ——
#   * NDK 29 的 libc.so 等为 LLVM bitcode 包装, 仅 ld.lld 可链接
#   * FPC 生成的 GNU ld 链接脚本 (INSERT AFTER) 与 ld.lld 不兼容
#   * GNU as/ld + r21e 平台库 仅用于 FPC 侧, SDL2 与游戏资源均在 NDK 29 编译
$PPBin     = 'D:\dev\FPC-android\bin\i386-win32'
$RTLRoot   = 'D:\dev\FPC-android\units'

# ABI → 工具链配置
#   toolchain: NDK r21e 内 4.9 binutils 目录名
#   pre:      binutils 前缀 (FPC -XP)
#   platlib:  平台库目录 (x86_64 在 lib64!)
$ABIs = @{
  'arm64-v8a' = @{
    ppc      = "$PPBin\ppcrossa64.exe"
    rtl      = "$RTLRoot\aarch64-android\rtl"
    toolchain = "$NDK\toolchains\aarch64-linux-android-4.9\prebuilt\windows-x86_64\bin"
    pre      = 'aarch64-linux-android-'
    platlib  = "$NDK\platforms\android-21\arch-arm64\usr\lib"
  }
  'x86_64' = @{
    ppc      = "$PPBin\ppcrossx64.exe"
    rtl      = "$RTLRoot\x86_64-android\rtl"
    toolchain = "$NDK\toolchains\x86_64-4.9\prebuilt\windows-x86_64\bin"
    pre      = 'x86_64-linux-android-'
    platlib  = "$NDK\platforms\android-21\arch-x86_64\usr\lib64"
  }
}

# --- 1) SDL2 共享库 (双 ABI) ---
Write-Host '=== [1/2] ndk-build libSDL2.so ==='
if (-not (Test-Path "$SDLPath\Android.mk")) { throw "SDL 源码缺失: $SDLPath" }
Push-Location $Root
try {
    foreach ($abi in $ABIs.Keys) {
        Write-Host "  -- ABI: $abi"
        cmd /c "`"$NDKBuild`" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=jni\Android.mk NDK_APPLICATION_MK=jni\Application.mk NDK_OUT=obj NDK_LIBS_OUT=app\src\main\jniLibs APP_ABI=$abi -j8 2>&1"
        if ($LASTEXITCODE -ne 0) { throw "ndk-build 失败 ($abi, exit=$LASTEXITCODE)" }
    }
} finally { Pop-Location }

# --- 2) FPC libmain.so (双 ABI) ---
Write-Host '=== [2/2] ppcross* libmain.so ==='
$PascalMain = 'D:\workspace\mario-android-port\spike\sdl_hello.pas'
foreach ($abi in $ABIs.Keys) {
    $cfg = $ABIs[$abi]
    Write-Host "  -- ABI: $abi"
    $unitDir = "$Root\build\sdl2-unit-$abi"
    $out = "$Root\build\libmain-$abi"
    $jniLibs = "$Root\app\src\main\jniLibs\$abi"
    New-Item -ItemType Directory -Force -Path $unitDir | Out-Null

    # sdl2 绑定单元 (ppu 目标相关, 每 ABI 一份)
    & $cfg.ppc -Tandroid "-FD$($cfg.toolchain)" "-Fu$($cfg.rtl)" "-Fu$unitDir" "-FU$unitDir" "$Repo\SDL2-for-Pascal\sdl2.pas"
    if ($LASTEXITCODE -ne 0) { throw "sdl2 单元编译失败 ($abi)" }

    # libmain
    $env:PATH = "$($cfg.toolchain);$env:PATH"
    & $cfg.ppc -Tandroid "-FD$($cfg.toolchain)" "-Fu$($cfg.rtl)" "-Fu$unitDir" "-k-L$($cfg.platlib)" "-k-L$jniLibs" "-XP$($cfg.pre)" "-o$out" $PascalMain
    if ($LASTEXITCODE -ne 0) { throw "libmain 编译失败 ($abi)" }

    New-Item -ItemType Directory -Force -Path $jniLibs | Out-Null
    Copy-Item $out "$jniLibs\libmain.so" -Force
}

Write-Host '=== 完成 ==='
Get-ChildItem "$Root\app\src\main\jniLibs" -Recurse -Filter '*.so' | ForEach-Object { Write-Host "  $($_.FullName.Replace($Root,'.')) ($($_.Length) B)" }
Write-Host '下一步: cd android && gradlew.bat assembleDebug'
