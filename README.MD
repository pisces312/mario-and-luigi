# Mario & Luigi - SDL2 Port

![Mario & Luigi - Latest Build Screenshot](https://cdn.planetmagpie.net/mario.png)

> **This fork also ships an Android APK port** — see [Android Port](#android-port) below.

A faithful port of **Mario & Luigi** (1994) by Mike Wiering from DOS (Turbo Pascal + VGA Mode 13h) to modern Windows using **SDL2** and **Free Pascal**.

The original game is a side-scrolling platformer for DOS featuring 6 worlds, two-player mode (Mario & Luigi), enemies, power-ups, pipes, and PC speaker sound effects. This port preserves all gameplay, visuals, and audio identically while replacing all DOS/hardware-specific code with SDL2 equivalents.  
  
This port was made based on the original [MARIOSRC.ZIP](https://wieringsoftware.nl/mario/MARIOSRC.ZIP) release, not based on the GitHub repo that was featured on Hacker News recently.  
  
You can play the original on the [Internet Archive's DOSBOX for browsers](https://archive.org/details/msdos_Mario_and_Luigi_2001).  
  
## Building

### Requirements

- [Free Pascal Compiler 3.2.2+](https://www.freepascal.org/) (i386/Win32)
- [SDL2 development library](https://github.com/libsdl-org/SDL/releases) (`SDL2.dll`, included)
- [SDL2-for-Pascal](https://github.com/PascalGameDevelopment/SDL2-for-Pascal) bindings (included in `SDL2-for-Pascal/`)

### Compile

Run the build script:

```bat
build.bat
```

Or compile manually:

```bat
fpc -Mtp -Sg -FuSDL2-for-Pascal MARIO.PAS
```

Place `SDL2.dll` in the same directory as `MARIO.exe` and run.

### Compiler Flags

| Flag   | Purpose                                              |
|--------|------------------------------------------------------|
| `-Mtp` | Turbo Pascal mode (`Integer` = 16-bit, matches original overflow behavior) |
| `-Sg`  | Allow `goto` statements (used in game loop)          |
| `-Fu`  | Unit search path for SDL2-for-Pascal bindings        |

### Android (APK)

The Android port keeps the exact same game units; only the entry point is
swapped to `MARIO_ANDROID.PAS` (`library` + `exports SDL_main`). Full
toolchain requirements, exact compiler/linker flags and every known trap are
documented in **[docs/BUILD.md](docs/BUILD.md)**.

```powershell
# 1) cross-compile SDL2 + libmain.so for arm64-v8a / x86_64
powershell -ExecutionPolicy Bypass -File android/build-android.ps1
# 2) assemble the APK
cd android
.\gradlew.bat assembleDebug --offline
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk` (both ABIs packed).

## Android Port

The Android port runs the unmodified game logic (Turbo Pascal mode,
`-Mtp -O-`) through the SDL2-for-Pascal stack, adapted for Android:

| Layer | Implementation |
|---|---|
| Entry point | `MARIO_ANDROID.PAS` — `library` exporting `SDL_main`, loaded by SDLActivity via `nativeRunMain` (SDL2 ≥ 2.32); compiled with `ppcrossa64`/`ppcrossx64`, linked with NDK r21e GNU binutils |
| SDL2 runtime | C library built with **NDK 29** (16KB page alignment), one `libSDL2.so` per ABI |
| Touch input | `TouchPadView.java` overlay (8 keys: D-pad bottom-left, jump/run/fire bottom-right, pause/sound/quit top) injecting key events through `SDLActivity.onNativeKeyDown/Up`; three stuck-key guards included |
| Saves / config | `SDL_AndroidGetInternalStoragePath` + `chdir(filesDir)`; `MARIO.CFG` / `MARIO.SAV` fixed names |
| Joystick | accelerometer-as-joystick explicitly disabled (`SDL_SetHint`); `JOYSTICK.PAS` defers detection via `{$IFNDEF ANDROID}` |
| ABIs | `arm64-v8a` + `x86_64` |

Android project layout: `android/` — Gradle 9.4.1 (Tencent mirror) / AGP 9.2.0,
Java glue from SDL2's `android-project`, prebuilt native libs in
`app/src/main/jniLibs/<abi>/`, `build-android.ps1` one-shot build script.

**Known traps** (detailed in `docs/BUILD.md` §5 and `docs/PLAN.md` §10):
`{$S-}` is mandatory (FPC stack checking crashes on Android) and `-O-` is
mandatory (`-O2` crashes entering Level 1 via `SYSTEM.IndexByte`).

## What Was Ported

The original game used extensive x86 inline assembly and direct hardware access. Every hardware-dependent component was rewritten in pure Pascal + SDL2:

### Graphics (VGA256.PAS)
- **VGA Mode 13h planar rendering** &rarr; Linear byte framebuffer arrays
- **Hardware page flipping** (CRTC registers) &rarr; Two software framebuffer pages
- **Hardware scrolling** (CRTC start address) &rarr; Viewport offset applied during presentation
- **VGA palette registers** (ports $3C8/$3C9) &rarr; SDL palette arrays, converted to ARGB during rendering
- **PushBackGr/PopBackGr** (VGA latch-copy sprite save/restore) &rarr; Heap-based stack with byte array storage
- **All drawing primitives** (DrawImage, PutImage, Fill, etc.) &rarr; Pure Pascal framebuffer operations
- Renders to a 320x182 software surface, scaled 3x to a 960x546 window via SDL2 renderer

### Input (KEYBOARD.PAS)
- **INT 09h keyboard interrupt handler** &rarr; SDL2 event polling
- **Direct port $60/$61 scancode reading** &rarr; SDL scancode-to-AT-scancode mapping
- **Demo recording/playback** from linked OBJ file &rarr; Typed constant array extracted from DEMOKEYS.OBJ

### Audio (MUSIC.PAS + BUFFERS.PAS)
- **PC speaker via ports $42/$43/$61** &rarr; SDL2 audio callback generating square waves at 44100 Hz
- Music sequence player with note sustain tracking to match PC speaker behavior
- Sound effect priority system ensuring one-shot beeps survive the audio callback timing

### Joystick (JOYSTICK.PAS)
- **Direct port $201 polling** &rarr; SDL2 joystick API

### Timing
- **DOS timer tick** (`$0000:$046C`) &rarr; `SDL_GetTicks div 55`
- **Frame pacing** &rarr; 14ms minimum per frame (~71 fps, matching original DOS speed)

### Sprite Data
- **VGA planar format** (`.$00` files with inline `asm db` blocks) &rarr; Linear byte-per-pixel format (`.000` files converted to typed constants via Python scripts)
- 170+ sprite include files auto-generated from original game assets

### Level Data (WORLDS.PAS)
- **Assembler `db`/`dw` blocks** in procedures &rarr; Typed constant byte arrays extracted via Python script

### Other Replacements
- `uses Crt` (Sound/NoSound/Delay) &rarr; SDL2 audio + SDL_Delay
- `CPU286` unit &rarr; Empty stub
- `{$L DEMOKEYS.OBJ}` &rarr; Extracted to typed constant
- VGA vertical blanking (CRTC SetYStart/SetYEnd) &rarr; Window sized to exact content area
- Executable name checksum protection &rarr; Removed

## Running

All game assets (sprites, levels, fonts, palettes) are compiled directly into the executable as typed constants. To run the game standalone you only need two files:

| File        | Description                          |
|-------------|--------------------------------------|
| `MARIO.exe` | Game executable (all data embedded)  |
| `SDL2.dll`  | SDL2 runtime library                 |

Place both files in the same folder and run `MARIO.exe`.

## Controls

### Menu
| Key             | Action              |
|-----------------|----------------------|
| Up / Down       | Navigate menu        |
| Enter           | Select               |
| Escape          | Back / Quit          |

### Gameplay
| Key             | Action              |
|-----------------|----------------------|
| Left / Right    | Move                 |
| Alt             | Jump                 |
| Ctrl / Space    | Run / Fire (fire mode) |
| Down            | Duck / Enter pipe    |
| Up              | Look up / Enter pipe |
| P               | Pause                |
| S               | Toggle status bar    |
| Q               | Toggle sound on/off  |
| Escape          | Quit                 |

Joystick is also supported.

## Levels

The game features 6 worlds with a second harder "Turbo" loop after completion (12 progression stages total). See **[LEVELS.md](LEVELS.md)** for a complete level map, visual themes, pipe system details, and progression breakdown.

## Project Structure

```
sdl2_port/
  MARIO.PAS          Main program (Windows)
  MARIO_ANDROID.PAS  Android entry point (library, exports SDL_main)
  PLAY.PAS           Game loop
  VGA256.PAS         SDL2 graphics engine (core)
  KEYBOARD.PAS       SDL2 input handling
  MUSIC.PAS          SDL2 audio (square wave synthesis)
  BUFFERS.PAS        Game state, types, shared audio vars
  PALETTES.PAS       VGA palette management
  FIGURES.PAS        Tile/sprite initialization & rendering
  BACKGR.PAS         Background rendering (parallax, bricks, etc.)
  PLAYERS.PAS        Player movement & rendering
  ENEMIES.PAS        Enemy AI & rendering
  TMPOBJ.PAS         Temporary objects (fireballs, particles)
  BLOCKS.PAS         Breakable/bouncing blocks
  GLITTER.PAS        Sparkle/particle effects
  STARS.PAS          Background star field
  STATUS.PAS         HUD status bar
  TXT.PAS            Text rendering (Swiss bitmap font)
  WORLDS.PAS         World/level data management
  JOYSTICK.PAS       SDL2 joystick support
  CPU286.PAS         Stub (originally 286 detection)
  build.bat          Build script (Windows)
  build-win32.ps1    Build script (Windows, explicit toolchain paths)
  android/           Android project (Gradle, SDLActivity, TouchPadView)
  docs/              Build & porting docs (BUILD.md, PLAN.md)
  icon.ico           Application icon
  mario.rc           Windows resource script
  sprites/           170 auto-generated sprite include files
  data/              Level data, fonts, palettes, backgrounds
  SDL2-for-Pascal/   SDL2 Pascal bindings (MPL/zlib licensed)
```

## Documentation

- **[docs/BUILD.md](docs/BUILD.md)** — Toolchain versions, paths, build commands and validation recipes for **both** the Windows and Android targets; sufficient to reproduce any build from scratch.
- **[docs/PLAN.md](docs/PLAN.md)** — The porting plan, all decisions, and a log of every problem encountered with its solution (stack-check crash, optimizer crash, NDK29 linking dead-ends, etc.).
- **[LEVELS.md](LEVELS.md)** — Complete level map and progression.

## Credits

- The original game and its source code were created by Mike Wiering in 1994. All credit for the original code goes to its author. You can find the original source at [wieringsoftware.nl](https://wieringsoftware.nl/mario/).
- **SDL2 Port**: Created with assistance from Claude (Anthropic)
- **SDL2-for-Pascal**: PascalGameDevelopment community
- The characters "Mario", "Luigi", and all related names, images, and concepts are the registered trademarks and copyright of **Nintendo Co., Ltd.** This project is **not affiliated with, authorized, or endorsed by Nintendo** in any way.
- This project is strictly non-commercial. It is provided for free, and no profit is being made from its existence or distribution. Do not attempt to sell or commercialize this software.
