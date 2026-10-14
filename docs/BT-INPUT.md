# Android 版蓝牙手柄 / 蓝牙键盘支持方案

> 记录日期：2026-10-12
> 状态：**已实施**（路线 A：Java 输入映射层），2026-10-14 构建通过，待真机验证
> 配套：`PLAN.md`（移植总记录）、`BUILD.md`（构建命令）

---

## 1. 目标与范围

为 Android 移植版增加外接输入设备支持：

1. **蓝牙手柄**（Xbox / PS / 8BitDo 等标准 Android 手柄）：开箱即玩的默认映射 + 自定义改键；
2. **蓝牙键盘**：即插即用（现有链路已支持）+ 自定义改键；
3. **设置界面**：设备列表与选择、按键映射编辑（按下捕获）、恢复默认、连接状态显示；
4. 手柄连接时自动隐藏触控层，断开后恢复；
5. **不改动 Pascal 游戏源码**（`MARIO_ANDROID.PAS` 及各单元零改动，避免重蹈 §10 崩溃坑）。

非目标：手柄震动、模拟量精确控制（本游戏为纯数字输入）、手柄热键组合宏。

---

## 2. 现状分析（输入链路全图）

### 2.1 Pascal 侧（游戏内，不改动）

| 层 | 文件 | 机制 |
|---|---|---|
| 键盘 | `KEYBOARD.PAS` | `SDL_KEYDOWN/UP` → `KeyMap[1..9]`（左/右/上/下/Alt/Ctrl/ShiftL/ShiftR/Space）+ `Key` 变量（PC 扫描码，菜单用：Enter=#28 确认、Esc=#1 返回、字母如 'J' 校准手柄） |
| 摇杆 | `JOYSTICK.PAS` | `SDL_JoystickOpen(0)`，轴 0/1（死区 8000）+ 按钮 0（跳）/1（跑·发）；**不读 hat**；`jsEnabled = jsDetected AND Settings.UseJS` |
| 合并 | `PLAYERS.PAS` L920–931 | 每帧 `keyLeft := kbLeft or jsLeft; …`（键盘与手柄或运算合并） |
| 菜单 | `MARIO_ANDROID.PAS` | **纯键盘扫描码驱动**，摇杆仅在游戏内生效 |

关键事实：
- `Settings.UseJS` 默认 **FALSE**，开启入口是菜单里按 **'J'** 键校准——Android 触控层没有 'J' 按钮，**当前手柄通路实际处于关闭状态**；
- SDL2 Android 把手柄十字键（DPAD 键值）转成 joystick **hat**，`JOYSTICK.PAS` 只读轴不读 hat——即使启用，十字键也无效，只有左摇杆（轴）能用。

### 2.2 Android Java 侧（SDL 官方胶水层，已随工程交付）

| 组件 | 职责 |
|---|---|
| `SDLActivity` | 事件总线；`dispatchKeyEvent`（L665）→ `handleKeyEvent`（L1321）→ `onNativeKeyDown/Up` |
| `SDLControllerManager` | 枚举 SOURCE_GAMEPAD/JOYSTICK/DPAD 设备注册为 SDL joystick；手柄按键 → `onNativePadDown/Up`（→ SDL joystick 按钮/hat）；摇杆 → `onNativeJoy` |
| `HIDDeviceManager` | USB/BLE HID 特俗设备（Steam Controller），标准 BT HID 手柄**不走这里**，走系统 InputDevice |
| `TouchPadView` | 触控层 9 键 → `onNativeKeyDown/Up` 注入（已验证，含防卡键三守卫）；左下为十字方向键热区，上下兼作进管道键，跑键已取消（长按 ◀/▶ 自动注入 CTRL_LEFT）；7 组按钮位置可由用户在设置页拖拽自定义（§4.5） |
| `MARIO_ANDROID.PAS` | 已设 `SDL_HINT_ACCELEROMETER_AS_JOYSTICK=0`（加速度计不抢输入） |

### 2.3 蓝牙输入到达路径与现状结论

| 输入 | Android 路径 | 现状 |
|---|---|---|
| BT 键盘按键 | KeyEvent（SOURCE_KEYBOARD）→ `handleKeyEvent` → SDL 键事件 → 游戏 | ✅ **开箱可用**（方向/Alt/Ctrl/Space/Enter/Esc/字母全部有映射）；仅个别系统组合键被拦截（见 §7） |
| BT 手柄按钮（A/B/X/Y/肩键） | KeyEvent（SOURCE_GAMEPAD）→ SDLControllerManager → SDL joystick 按钮 | ⚠️ 到达 SDL joystick 层，但 `jsEnabled=false` + 只读按钮 0/1 → **游戏无响应** |
| BT 手柄十字键 | KeyEvent（DPAD_*）→ SDL joystick **hat** | ❌ `JOYSTICK.PAS` 不读 hat → 无效 |
| BT 手柄左摇杆 | MotionEvent（AXIS_X/Y）→ `onNativeJoy` → SDL joystick 轴 | ⚠️ 原生可用但被 `jsEnabled=false` 挡住 |

**核心结论**：手柄事件已经到达 Java 层，缺的不是"接收"，而是"翻译成游戏听得懂的键盘事件"。触控层已证明 `onNativeKeyDown/Up` 注入链路可靠——**在 Java 层做映射是最小侵入路径**。

---

## 3. 总体设计：方案 A —— Java 输入映射层（推荐）

### 3.1 架构

```
蓝牙手柄/键盘
     │  KeyEvent / MotionEvent
     ▼
SDLActivity.dispatchKeyEvent / dispatchGenericMotionEvent   ← 挂载点①（最前端拦截）
     │
     ▼
GamepadInputMapper（新增）
     │  命中映射（且来自选定设备）→ 翻译 → consume
     │  未命中 → return false，走原 SDL 流程（键盘默认直通即靠此）
     ▼
SDLActivity.onNativeKeyDown / onNativeKeyUp  ──→ SDL → KEYBOARD.PAS → 游戏
     ▲
TouchPadView（触控层，同一注入通道，共存）
```

- **统一出口**：所有外设输入最终都变成"键盘注入"，游戏侧只认键盘——菜单（Enter/Esc/字母）与游戏（方向/Alt/Ctrl/Space）**同时覆盖**，这是本方案相对原生手柄路线的决定性优势（原生路线菜单无法用手柄操作）；
- **一键多绑**：一个物理键可注入多个虚拟键。例：手柄 A → `{ALT_LEFT, ENTER}`——菜单里按 A 即确认（Enter），游戏里按 A 即跳（Alt），互不干扰（菜单忽略 Alt、游戏忽略 Enter）；
- **零 Pascal 改动**：彻底避开 `{$S+}`/`-O2` 等已验证死路（PLAN.md §10）。

### 3.2 三个新文件 + 三处挂载点

| 文件 | 职责 |
|---|---|
| `org.libsdl.app.MappingStore` | SharedPreferences 读写：选定设备、映射表、开关项；提供默认映射 |
| `org.libsdl.app.GamepadInputMapper` | 运行时：键/轴事件 → 注入；轴数字化状态机；热插拔监听；释放全部按键 |
| `org.libsdl.app.ControllerSettingsActivity` | 设置界面：设备列表/选择、映射编辑（捕获）、测试、恢复默认 |

挂载修改（均为小改）：
1. `SDLActivity.dispatchKeyEvent` 最前端加 mapper 调用；新增 `dispatchGenericMotionEvent` 覆写；
2. `SDLActivity.onCreate/onResume/onDestroy`：创建/重载/释放 mapper；
3. `TouchPadView`：加第 9 个"设置"按钮（齿轮，进入设置页）+ 提供显隐接口；
4. `AndroidManifest.xml`：声明设置 Activity。

---

## 4. 模块设计

### 4.1 MappingStore（存储层）

SharedPreferences 文件名 `controller_prefs`：

| 键 | 类型 | 说明 |
|---|---|---|
| `selected_device` | String | 选定手柄的 `InputDevice.getDescriptor()`；空串 = "任意手柄设备"（默认，插上即玩） |
| `mapping_json` | String | 映射表 JSON（见 §6）；缺省 = 默认映射 |
| `auto_hide_touchpad` | boolean | 手柄在线时自动隐藏触控层，默认 true |
| `touch_auto_run` | boolean | 长按 ◀/▶ 自动注入跑（CTRL_LEFT），**触控层与手柄/键盘映射层共用**，默认 true |
| `tp_layout` | String | 触控层 7 组按钮中心点 `"cx,cy;…"`（归一化 0..1，顺序见 §4.5）；缺省 = 内置默认布局 |

- 设备**不**按 id 记忆（id 重连会变），按 **descriptor**（API 16+，minSdk 21 满足）；
- 提供 `resetToDefault()`；读取失败一律回退默认，不抛异常进游戏。

### 4.2 GamepadInputMapper（运行时核心）

**按键通路** `handleKeyEvent(KeyEvent): boolean`：

1. 设备过滤：`selected_device` 非空时，仅处理 descriptor 匹配的设备；键盘来源（SOURCE_KEYBOARD）单独按映射表过滤（未绑定键直通 SDL）；
2. 查表：`keycode → Set<虚拟键>`，命中则对集合逐个 `onNativeKeyDown/Up`（与 TouchPadView 同通道），返回 true（consume，SDLControllerManager 不再收到 → 无双重输入）；
3. 未命中返回 false → 原 SDL 流程。

**摇杆通路** `handleMotionEvent(MotionEvent): boolean`：

1. 仅处理选定设备的 SOURCE_JOYSTICK 事件；
2. 轴数字化状态机：每条已绑轴（如 AXIS_X→左/右）维护当前符号状态，**进入阈值 0.5、释放回差 0.3**，跨越时发送对应方向键 down/up；
3. AXIS_HAT_X/Y（部分十字键走 hat）同样按方向键处理；
4. 已绑轴 consume，未绑轴放行。

**长按自动跑**：方向来源连续按住 `AUTO_RUN_MS` 后补发 RUN，规则与触控层逐条对齐，见 §4.8。

**生命周期**：

- `releaseAll()`：跟踪所有已注入按键，设备断开（InputDeviceListener）、`onPause`、映射重载、设置页返回时全部补发 key up——**延续触控层防卡键三守卫思想**；
- 热插拔：`InputManager.registerInputDeviceListener`，设备上下线时更新内部状态并回调 SDLActivity 刷触控层显隐；
- 配置重载：`onResume` 时对比 prefs 版本号，变了先 `releaseAll()` 再重载。

### 4.3 ControllerSettingsActivity（设置界面）

纯 `android.app.Activity` + 原生 View（项目无 androidx 依赖，保持一致），代码构建布局（LinearLayout + ScrollView），横屏。

四个区块：

1. **设备状态**：实时列出全部输入设备（名称 / 来源图标：蓝牙·USB·内置 / 是否手柄类），选中设备高亮；底部"手柄选择"单选列表（任意 / 各手柄 descriptor）；
2. **按键映射**：动作列表（10 项，见 §5），每行显示当前绑定文本（如 `A按钮 + ENTER`）；点击行进入**捕获对话框**："请按下手柄按键或推动摇杆…（返回键取消）"——对话框内 `onKeyDown` 记录 keycode、`onGenericMotionEvent` 记录轴+方向（|value|>0.7 判定），支持"追加绑定 / 清空本项"；
3. **操作**：恢复默认映射、**测试模式**（大字实时显示"物理键 → 动作"，验证链路）、自动隐藏触控开关、自动跑开关、**编辑触控按钮位置** / **恢复默认布局**（§4.5）；
4. **说明**：简短帮助（蓝牙配对请在系统设置完成；Ctrl+Space 为系统输入法快捷键等已知限制）。

入口：
- 触控层顶部新增齿轮按钮（小尺寸，与暂停/声音/退出并列）；
- 同时声明独立 LAUNCHER intent-filter（桌面出现"手柄设置"图标，游戏外也能进）。

### 4.4 SDLActivity 挂载点（精确位置）

| 位置 | 改动 |
|---|---|
| `onCreate` L419 触控层挂载处 | 之后创建 `GamepadInputMapper`（读 prefs、注册 InputDeviceListener），持有 TouchPadView 引用用于显隐联动 |
| `dispatchKeyEvent` L665 | 音量键白名单之后、`super` 之前：`if (mInputMapper != null && mInputMapper.handleKeyEvent(event)) return true;` |
| 新增 `dispatchGenericMotionEvent` 覆写 | `if (mInputMapper != null && mInputMapper.handleMotionEvent(ev)) return true; return super…` |
| `onResume` | `mInputMapper.reloadIfChanged()`；`mTouchPad.reloadLayout()`（设置页可能改过按钮位置） |
| `onPause` / `onDestroy` | `mInputMapper.releaseAll()` |
| `onCreate` L440 与 `setWindowStyle()` L1015 | 强制沉浸式全屏（隐藏状态栏/导航栏），见 §4.7 |

注意：`mSurface.setOnGenericMotionListener` 已被 SDL 占用，**不可再 set**（会顶掉 SDL 的 listener）——摇杆拦截必须在 Activity dispatch 层完成。

### 4.5 TouchPadView 联动与按钮位置自定义

**联动**

- 新增第 9 键 `BTN_SETTINGS`（齿轮 ⚙，顶部小键区）：点击不注入键值，回调 SDLActivity `startActivity(ControllerSettingsActivity)`；
- 显隐：mapper 检测到选定手柄在线且开关开 → `setVisibility(GONE)`；离线/开关关 → `VISIBLE`；`GONE` 前必须 `releaseAll()`（视图已有此守卫）。

**位置自定义（7 组可拖动）**

| 组 | 含按钮 | 说明 |
|---|---|---|
| `GROUP_DPAD` | ◀ ▶ ▲ ▼ | 十字热区（含中心死区）作为**一个整体**移动 |
| `GROUP_JUMP` / `GROUP_FIRE` | A(跳) / B(发弹) | 各自独立 |
| `GROUP_PAUSE` / `GROUP_SOUND` / `GROUP_EXIT` / `GROUP_SETTINGS` | ‖ ♪  ⚙ | 顶排四个小键各自独立 |

- **只存中心点**：`tp_layout = "cx,cy;cx,cy;…"`（7 组、按上表顺序、归一化 0..1、4 位小数）。尺寸仍由屏幕推导（`big=0.15h`、`cell=0.8big`、`small=0.075h`），因此**只调位置不调大小**，且横竖屏/分辨率变化后自适应。
- 布局管线：`relayout() = computeDefaults()` → `loadCustomLayout()` → `applyLayout()`；`onSizeChanged` 与 `reloadLayout()` 共用它。任一坏值 / 组数不符 → **整体**回退默认，不接受半套。
- `applyLayout()` 里对中心点做 clamp（`half .. dim-half`），组永远完整在屏内；绘制与 `hitTest` 读同一份 rect，不存在两套坐标。
- **编辑模式**：`setEditMode(true)` 后 `onTouchEvent` 短路到 `handleEditTouch`，**不注入任何键值**；按下记录"手指到组中心"的偏移，拖动跟手，`ACTION_UP` 落盘（`setTouchLayout`）。齿轮 ⚙ 在编辑模式只是被拖动的对象，退出用顶部固定的「完成」按钮（不参与持久化）。
- 承载方式：设置页 `addContentView()` 叠一层全屏 `TouchPadView`（不注入 `P`——`PLAY.PAS` 的暂停是 `While Key = kbP do ;` 忙等，SDL 侧按键抬起不会送达，会卡死游戏）；返回键与其余按键在 `dispatchKeyEvent` 里被拦截，只关浮层。
- 生效时机：游戏 `onResume` → `reloadLayout()`，即"设置页调完 → 返回游戏"立即生效，无需重启。


### 4.6 Manifest 变更

```xml
<activity android:name="org.libsdl.app.ControllerSettingsActivity"
    android:label="手柄设置" android:screenOrientation="landscape"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

**权限：零新增。** 设备枚举走 `InputManager/InputDevice` API，不需要蓝牙权限。`BLUETOOTH_CONNECT`（API 31+ 运行时权限）仅当未来要显示"系统已配对蓝牙设备列表"时才需申请——本期不做，文档备案。

### 4.7 沉浸式全屏（隐藏状态栏与导航栏）

主题 `AppTheme` 本就继承 `android:Theme.NoTitleBar.Fullscreen`，状态栏之所以还在，是 SDL 把它关掉了两次：

1. `SDLActivity.onCreate` 的 `setWindowStyle(false)`；
2. `SDL_CreateWindow` 链路：`SDL_video.c:1854` `SDL_UpdateFullscreenMode(window, FULLSCREEN_VISIBLE(window))`，游戏窗口只带 `SDL_WINDOW_SHOWN`（`VGA256.PAS:195`）→ 传 `SDL_FALSE` → `SDL_androidwindow.c:121` `Android_JNI_SetWindowStyle(false)` → 同一个 handler 的 else 分支（`FLAG_FORCE_NOT_FULLSCREEN` + `SYSTEM_UI_FLAG_VISIBLE`），并把 `mFullscreenModeActive` 置 false，连带让 `onSystemUiVisibilityChange` 的"自动收回"逻辑失效。

**改法（仅 Java，不需重编 native）**：`onCreate` 改 `setWindowStyle(true)`；`setWindowStyle()` 内把命令参数恒置 `1`。安全性依据：游戏只有一个窗口，`*.PAS` 里除 `VGA256.PAS:195` 的 `SDL_CreateWindow` 外没有任何 `SDL_SetWindowFullscreen` / `SDL_GetWindowFlags` / `ShowWindow` / `SetWindowSize` 调用，运行期不存在"合法请求窗口化"的场景。

- 效果取自 SDL 现成的 true 分支：immersive-sticky 同时隐藏状态栏与导航栏、刘海屏 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`，并置 `mFullscreenModeActive=true` → 边缘下拉临时露出系统栏后 2s 自动收回，且露出期间画面不缩放。
- 画面由 `SDL_RenderSetLogicalSize`（`VGA256.PAS:215`）按新窗口尺寸重新 letterbox，不拉伸。
- 触控层可用高度随之变大（导航栏区域让出），归一化布局自动适配；此前存过 `tp_layout` 的用户可能要重新微调位置。
- 兼容边界：真机 Android 13、模拟器 API 34 上 legacy `SYSTEM_UI_FLAG_*` 有效（2026-10-14 实测）。本项目 `targetSdk=36`，Android 15+ 机型若隐藏失效需补 `WindowInsetsController` 分支——**未在 Android 15+ 实测**。

### 4.8 长按方向键自动跑（触控层与手柄/键盘共用）

跑键（RUN = `CTRL_LEFT`）在触控层已取消，改由长按 ◀/▶ 补发。手柄在线时触控层被置 `GONE`（§4.5），而映射层原本只有"按下即注入、抬起即收回"、没有任何时间维度，所以手柄与蓝牙键盘上长按方向键不会起跑——原先手柄的跑只有 B/Y/L1/R2/右扳机（§5）。

**改法（仅 Java，不需重编 native）**：在 `GamepadInputMapper` 里补一套与触控层同规则的状态机。

| 要点 | 实现 |
|---|---|
| 阈值共用 | `MappingStore.AUTO_RUN_MS = 220`，`TouchPadView` 不再自持常量，两条路径同一份 |
| 方向来源计数 | `dirHold: 动作 → 活跃来源数`。十字键（token `k:21`）、左摇杆（`a:16:-1:LEFT`）、HAT 可能对同一个 LEFT 同时上报，必须计数，否则"任一来源松手就退出"会提前收跑 |
| 触发/退出规则 | 与 `TouchPadView.updateAutoRun` / `runningDirection` 逐条对齐：只有 LEFT/RIGHT 参与；UP/DOWN 一旦激活立即取消计时并收回；同方向被别的来源重复激活不重新计时（下管道要求 `XVel=0`，所以 ↑/↓ 不参与） |
| 注入走引用计数 | 到点后 `pressToken("auto:run", {RUN})`，与实体 B/Y/L1/R2 共用 `virtualRef` 计数——按住 B 跑时长按方向键结束，不会提前把 Ctrl 松开 |
| 收回与防卡键 | `releaseAll()` 开头 `cancelRunSchedule()` + `releaseRun()`，并清 `dirHold`；覆盖设备断开、`onPause`、映射重载、`destroy()` |
| 定时通道 | 主线程 `Handler`（mapper 在 `onCreate` 主线程构造），`postDelayed` / `removeCallbacks` 成对。未采用系统 key repeat 计数或 `KeyEvent.isLongPress()`：前者重复率随设备变、部分 pad 的十字键根本不报重复，后者阈值固定在 ~500ms 且需 API 34 |

覆盖输入：手柄十字键、左摇杆、HAT、蓝牙键盘方向键（同一条按键通路）。游戏侧只读 `SDL_KEYDOWN`（`KEYBOARD.PAS:182` 把 `SDLK_LCTRL` 记成 `keyCtrl`），不碰 joystick，因此改动全在注入层。

**已知限制（方案 B 未做）**：触控层的自动跑仍是裸 `onNativeKeyDown/Up`（`TouchPadView.java:108` / `:403`），与映射层的引用计数各持一份状态。只有"手柄在线 + 关闭自动隐藏 + 手指同时按住屏幕 ◀/▶"这种叠加用法才会互相覆盖，表现为先松开的来源把跑停下——方向上只会提前 UP、不会漏 UP，所以不产生卡键。

---

## 5. 动作表与默认映射

游戏动作（10 项）= 虚拟键（Android keycode，与触控层同一套 SDL 映射）：

| 动作 | 虚拟键 | 游戏内作用 | 菜单作用 |
|---|---|---|---|
| LEFT / RIGHT / UP / DOWN | DPAD_LEFT/RIGHT/UP/DOWN | 移动 / 上看·进管 / 蹲·进管 | 菜单选择 ↑↓ |
| JUMP | ALT_LEFT | 跳跃 | — |
| RUN | CTRL_LEFT | 加速跑 | — |
| FIRE | SPACE | 发射子弹 | — |
| PAUSE | P | 暂停 | — |
| SOUND | Q | 音效开关 | — |
| EXIT | ESCAPE | 退出 | 菜单返回 |
| CONFIRM | ENTER | — | 菜单确认 |
| BACK | ESCAPE | （与 EXIT 同键值，语义行） | 菜单返回 |

默认映射（Xbox 布局为基准，蓝牙键盘无需映射、直通）：

| 物理输入 | 注入虚拟键 | 说明 |
|---|---|---|
| 十字键 DPAD 四向 | 方向四键 | |
| 左摇杆 X/Y（含 HAT） | 方向四键 | 阈值 0.5 / 回差 0.3 |
| A（BUTTON_A） | ALT_LEFT **+ ENTER** | 一键多绑：游戏=跳，菜单=确认 |
| X（BUTTON_X） | SPACE **+ ENTER** | 发弹；菜单也可确认 |
| B（BUTTON_B） | CTRL_LEFT | 跑 |
| Y / L1 | CTRL_LEFT | 跑的备用键 |
| R2（AXIS_RTRIGGER>0.5 或 BUTTON_R2） | CTRL_LEFT | 扳机跑（常见习惯） |
| START（BUTTON_START） | P + ENTER | 暂停；菜单确认 |
| SELECT（BUTTON_SELECT） | ESCAPE | 菜单返回 / 游戏退出 |

> B 不绑 ESCAPE（游戏里按 B 会误触退出）；菜单返回统一用 SELECT。
> 长按 ◀/▶（十字键或左摇杆）220ms 会自动补发 `CTRL_LEFT`（§4.8），不想用就把设置页的自动跑开关关掉，实体跑键不受影响。

---

## 6. 映射 JSON 格式（mapping_json）

```json
{
  "version": 1,
  "actions": {
    "JUMP":  [ {"type":"key","keycode":96}, {"type":"key","keycode":97,"note":"示例：B也跳"} ],
    "LEFT":  [ {"type":"key","keycode":19}, {"type":"axis","axis":0,"dir":-1}, {"type":"axis","axis":15,"dir":-1} ],
    "RUN":   [ {"type":"key","keycode":113}, {"type":"key","keycode":102}, {"type":"axis","axis":17,"dir":1} ]
  }
}
```

- 顶层按**动作**索引，值为绑定数组（追加式，一键可绑多动作、一动作可绑多键）；
- `axis` 为 Android `MotionEvent.AXIS_*` 常量；`dir` = -1/+1；
- 反向索引（keycode→动作集合）在 mapper 加载时构建；
- 手写损坏时整体回退默认映射。

---

## 7. 边界与已知限制

| 项 | 对策 / 说明 |
|---|---|
| 卡键 | mapper 维护注入按键集合；设备断开/onPause/配置重载/视图销毁一律 releaseAll（沿用触控层三守卫经验） |
| 系统保留键 | HOME、电源、音量、截图组合被系统消费，永远到不了应用——捕获界面提示"该键不可用" |
| Ctrl+Space | Android 输入法切换系统快捷键，可能被拦截——帮助文案说明；默认映射不受影响（发弹是 Space 单键） |
| 双重输入 | 命中映射的事件 consume 后不再进 SDLControllerManager；未命中事件原样放行 |
| 键盘 Alt 直通 | BT 键盘 ALT_LEFT 未绑定时不 consume，SDL 原生映射即 SDLK_LALT=跳——默认零配置 |
| 设备失配 | descriptor 匹配失败（罕见）时回退"任意手柄"并 Toast 提示重选 |
| 模拟器调试 | "任意手柄"模式下 `adb shell input keyevent` 注入的键也会过映射管线，可无蓝牙验证大部分逻辑 |
| 自动跑双份状态 | 触控层用裸注入、映射层用引用计数，只在"手柄在线 + 关自动隐藏 + 手指同时按住屏幕 ◀/▶"的叠加用法下互相覆盖（提前停下、不卡键），见 §4.8 |
| 延迟 | 注入与触控层同通道，实测 50fps 无感；不引入额外线程/轮询 |

---

## 8. 备选方案 B：原生 SDL 手柄路线（已评估，不推荐）

思路：改 `JOYSTICK.PAS` —— 加 hat 读取（十字键）、多按钮、`jsEnabled` 自动启用、经 JNI 传设备索引与映射。

放弃原因：
1. **菜单无法用手柄**（菜单纯键盘扫描码驱动，要改就得动 `MARIO_ANDROID.PAS` 菜单循环，侵入大）；
2. 改 Pascal 需重新走交叉编译 + 回归 §10 的崩溃雷区；
3. 配置仍得做 Java 界面 + JNI 通道，工作量不降反升。

保留为远期选项（若将来要模拟量/震动再议）。

---

## 9. 实施步骤

1. [x] `MappingStore.java`：prefs 读写 + 默认映射表 + JSON 解析/回退
2. [x] `GamepadInputMapper.java`：按键映射、轴状态机、releaseAll、InputDeviceListener、reloadIfChanged
3. [x] `SDLActivity.java`：四处挂载点（§4.4）
4. [x] `TouchPadView.java`：齿轮按钮 + 显隐接口（setVisibility 守卫：GONE 前 releaseAll）
5. [x] `ControllerSettingsActivity.java`：设备列表/选择、捕获对话框、测试模式、恢复默认
6. [x] `AndroidManifest.xml`：声明设置 Activity
7. [x] `gradlew assembleDebug --offline` 构建（2026-10-14 BUILD SUCCESSFUL）
8. [x] 模拟器冒烟（pixel6）：APK 安装成功、设置页启动渲染正常（设备列表/选择/映射区均显示）；**完整按键注入链路验证留待真机**
9. [ ] 真机验证（BT 手柄/键盘，见 §10 清单）
10. [x] 更新 `游戏说明.md`（§八 Android 版与外接输入）与本文件状态

工作量估计：新增 Java 约 900–1200 行；修改现有文件 4 处，每处 < 30 行；Pascal 零改动。

---

## 10. 验证计划

### 10.1 模拟器（pixel6 x86_64，无蓝牙，验证管线）

- [x] 设置页可打开、默认映射展示正确、恢复默认生效（2026-10-14 冒烟：启动+渲染正常）
- [x] `adb shell input keyevent 19/20/21/22`（DPAD）→ 菜单选择移动（2026-10-14：`keyevent 20` 使 START→OPTIONS 高亮下移）
- [ ] 捕获对话框：`input keyevent` 能被记录为绑定
- [ ] 自定义映射（如把 JUMP 改到 BUTTON_A=96）后注入生效
- [ ] 触控层显隐开关切换正常，无卡键
- [x] 按钮位置自定义（§4.5，2026-10-14 pixel6 2400x1080 实测）：设置页 →「编辑触控按钮位置」→ 浮层显示 7 组 + 顶部「完成」；`input swipe` 拖动 A 键与整个十字热区均跟手，松手后 `shared_prefs/controller_prefs.xml` 出现 `tp_layout`（7 组 `cx,cy`）；「完成」返回设置页，进游戏后触控层按新位置渲染，点新位置的 ▼ 菜单照常下移（命中判定与绘制同源）；「恢复默认布局」后 `tp_layout` 被移除，返回游戏布局回到默认
- [x] debug 包 `install -r` 覆盖安装保留 `shared_prefs`（同证书生效；2026-10-14 升级后 `tp_layout` 仍在）
- [x] 沉浸式全屏（§4.7，2026-10-14 pixel6 2400x1080 实测）：进游戏后状态栏与导航栏均隐藏、顶排小键贴到屏幕最上沿；画面按整屏高度重新 letterbox，无拉伸、像素仍最近邻；边缘下拉临时露出系统栏时画面不缩放，随后自动收回

### 10.2 真机（用户 BT 手柄 + BT 键盘，待用户执行）

- [ ] 手柄配对（系统设置）→ 进游戏：十字键/左摇杆移动、A 跳、B 跑、X 发弹、START 暂停、SELECT 返回，**全程菜单可纯手柄操作**（A 确认）
- [ ] 改键：跳改到 R1，保存后生效，重启游戏仍在
- [ ] 手柄连接后触控层自动隐藏；游戏中拔电/断连 → 触控恢复且**无卡键**（角色不跑偏）
- [x] 手柄长按 ◀/▶ 自动跑（§4.8，2026-10-14 用户真机 BT 手柄实测：长按方向键约 220ms 后进入跑速，功能生效）
- [ ] BT 键盘：方向/Alt/Ctrl/Space/Enter/Esc 直接可玩；改键（跳→Z）生效
- [ ] 双设备：键盘与手柄同时在线互不干扰
- [ ] 触控层按钮位置：真机手指拖拽手感、误触与其他 App 切换后布局是否仍生效（模拟器已验链路，**待真机验证**）
- [ ] 沉浸式全屏：S20 挖孔与侧滑手势区是否挡住顶排/底部按钮，切后台再回来仍保持隐藏（模拟器已验，**待真机验证**）
- [ ] 长时间游戏（>10 分钟）稳定、无输入漂移

---

## 11. 风险登记

| 风险 | 等级 | 缓解 |
|---|---|---|
| 个别手柄 descriptor 不稳定导致选择失效 | 低 | 默认"任意手柄"即玩；失配回退 + 提示 |
| 厂商定制手柄按键不上报（被系统吃掉） | 中 | 捕获界面提示；文档列为已知限制 |
| 设置页与 SDL 单实例任务栈交互异常 | 低 | 设置页独立 taskAffinity 不设，随应用栈；验证返回后 mapper 重载 |
| 用户误清空全部映射无法游戏 | 低 | "恢复默认"按钮；空映射时启动自动回退默认 |
| Android 14+ 输入子系统行为差异 | 低 | minSdk 21 / targetSdk 36 区间内 API 均稳定；真机回归兜底 |
