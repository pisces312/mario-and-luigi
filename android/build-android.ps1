# build-android.ps1 - one-shot Android native build for Mario & Luigi
# 1) ndk-build cross-compiles SDL2 -> libSDL2.so (arm64-v8a + x86_64)
# 2) ppcross* cross-compiles libmain.so (FPC, exports SDL_main; source MARIO_ANDROID.PAS)
# 3) copies the .so files into app/src/main/jniLibs/<abi>/
# Then build the APK with gradlew.bat assembleDebug / assembleRelease.
#
# Usage: powershell -ExecutionPolicy Bypass -File build-android.ps1
# Keep this file ASCII-only: Windows PowerShell 5.1 decodes BOM-less UTF-8 as GBK and
# silently eats newlines and following ASCII bytes, so code ends up inside comments.

$ErrorActionPreference = 'Stop'

$Root      = $PSScriptRoot
$Repo      = Split-Path $Root -Parent          # mario-and-luigi/
$SDLPath   = "$Root\3rd\SDL"
# --- toolchain (override via env vars; defaults are the fixed D:\dev paths on this machine) ---
$FPCAndroidHome = if ($env:FPC_ANDROID_HOME) { $env:FPC_ANDROID_HOME } else { 'D:\dev\FPC-android' }
$NDK            = if ($env:ANDROID_NDK_HOME) { $env:ANDROID_NDK_HOME } else { 'D:\dev\android_sdk\ndk\21.4.7075529' }  # FPC link step (GNU binutils, hard requirement of FPC 3.2.2)
$NDKBuild  = if ($env:ANDROID_NDK29_HOME) { Join-Path $env:ANDROID_NDK29_HOME 'ndk-build.cmd' } else { 'D:\dev\android_sdk\ndk\29.0.14206865\ndk-build.cmd' }  # SDL2 C library (NDK 28+ defaults to 16KB page alignment); switching this NDK needs no manual obj cleanup, the stamp guard below forces a full rebuild

# Why the split: FPC 3.2.2 cannot use the NDK 29 platform libraries --
#   * NDK 29 libc.so etc. are LLVM bitcode wrappers, linkable only by ld.lld
#   * the GNU ld linker scripts FPC emits (INSERT AFTER) are not understood by ld.lld
#   * GNU as/ld + r21e platform libs are used for the FPC side only; SDL2 builds on NDK 29
$PPBin     = Join-Path $FPCAndroidHome 'bin\i386-win32'
$RTLRoot   = Join-Path $FPCAndroidHome 'units'

# game Pascal entry point (Android specific: library + SDL_main)
$PascalMain = "$Repo\MARIO_ANDROID.PAS"

# FPC flags -- same as the Windows build plus the Android target
#   -Mtp: Turbo Pascal mode (Integer = 16 bit, keeps the 1994 original semantics)
#   -dANDROID: conditional compilation in JOYSTICK.PAS (probe gamepads after SDL_Init)
$FpcOpts = @('-Mtp', '-Ci-', '-Cr-', '-Sg', '-Si', '-O-', '-dANDROID')

# ABI -> toolchain settings
#   toolchain: name of the 4.9 binutils dir inside NDK r21e
#   pre:       binutils prefix (FPC -XP)
#   platlib:   platform library dir (x86_64 lives in lib64!)
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

# --- 1) SDL2 shared library (both ABIs) ---
Write-Host '=== [1/2] ndk-build libSDL2.so ==='
if (-not (Test-Path "$SDLPath\Android.mk")) { throw "SDL sources missing: $SDLPath" }
# ndk-build decides what to rebuild from source mtimes only and ignores the toolchain
# version: with old artifacts left in obj it just copies them into jniLibs (md5 unchanged).
# obj/.ndk-stamp remembers which ndk-build.cmd built obj; on a mismatch, wipe obj first.
$ObjDir = "$Root\obj"
$Stamp  = "$ObjDir\.ndk-stamp"
if ((Test-Path $Stamp) -and ((Get-Content $Stamp -Raw).Trim() -ne $NDKBuild)) {
    Write-Host '  ndk-build changed -> clearing obj for full rebuild'
    Remove-Item -Recurse -Force $ObjDir
}
New-Item -ItemType Directory -Force -Path $ObjDir | Out-Null
Set-Content -Path $Stamp -Value $NDKBuild -NoNewline
Push-Location $Root
try {
    foreach ($abi in $ABIs.Keys) {
        Write-Host "  -- ABI: $abi"
        cmd /c "`"$NDKBuild`" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=jni\Android.mk NDK_APPLICATION_MK=jni\Application.mk NDK_OUT=obj NDK_LIBS_OUT=app\src\main\jniLibs APP_ABI=$abi -j8 2>&1"
        if ($LASTEXITCODE -ne 0) { throw "ndk-build failed ($abi, exit=$LASTEXITCODE)" }
    }
} finally { Pop-Location }

# --- 2) FPC libmain.so (both ABIs, compiles all game units) ---
Write-Host '=== [2/2] ppcross* libmain.so ==='
foreach ($abi in $ABIs.Keys) {
    $cfg = $ABIs[$abi]
    Write-Host "  -- ABI: $abi"
    $unitDir = "$Root\build\units-$abi"
    $out = "$Root\build\libmain-$abi"
    $jniLibs = "$Root\app\src\main\jniLibs\$abi"
    New-Item -ItemType Directory -Force -Path $unitDir | Out-Null

    # compile from the repo root: MARIO_ANDROID.PAS relies on {$I sprites\*.inc} relative paths
    $env:PATH = "$($cfg.toolchain);$env:PATH"
    Push-Location $Repo
    try {
        & $cfg.ppc -Tandroid $FpcOpts "-FD$($cfg.toolchain)" "-Fu$($cfg.rtl)" "-Fu$unitDir" "-Fu$Repo" "-Fu$Repo\SDL2-for-Pascal" "-FU$unitDir" "-k-L$($cfg.platlib)" "-k-L$jniLibs" "-XP$($cfg.pre)" "-o$out" $PascalMain
        if ($LASTEXITCODE -ne 0) { throw "libmain build failed ($abi)" }
    } finally { Pop-Location }

    New-Item -ItemType Directory -Force -Path $jniLibs | Out-Null
    Copy-Item $out "$jniLibs\libmain.so" -Force
}

Write-Host '=== done ==='
Get-ChildItem "$Root\app\src\main\jniLibs" -Recurse -Filter '*.so' | ForEach-Object { Write-Host "  $($_.FullName.Replace($Root,'.')) ($($_.Length) B)" }
Write-Host 'next: cd android && gradlew.bat assembleDebug'
