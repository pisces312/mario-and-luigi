package org.libsdl.app;

/* Mario & Luigi 触摸控制层
 * 通过 SDLActivity.onNativeKeyDown/Up 注入键盘事件 (SDL 官方 Android keycode 映射):
 *   DPAD_LEFT/RIGHT/UP/DOWN -> SDLK_LEFT/RIGHT/UP/DOWN  移动
 *                           上/下同时是进管道键: 下=站管道口按住 (PLAYERS.PAS CheckPipeBelow),
 *                           上=顶入上方管道 (CheckPipeAbove), 菜单里也可上下选择
 *   ALT_LEFT          -> SDLK_LALT         跳 (A)
 *   CTRL_LEFT         -> SDLK_LCTRL        跑 (无独立键: 长按 ◀/▶ 自动注入, 见 updateAutoRun)
 *   SPACE             -> SDLK_SPACE        发射 (B)
 *   P / Q / ESCAPE    -> 暂停 / 声音 / 退出
 * 布局: 7 个可拖动组 (十字方向键算一组), 位置以归一化中心点存 SharedPreferences
 *   默认: 左下十字热区, 右下 B(发射)/A(跳) 两圆 (左 B 右 A, 红白机布局), 顶排 暂停/声音/齿轮/退出
 *   tp_layout = "cx,cy;cx,cy;..." (每组两位, 0..1, 顺序见 GROUP_*), 尺寸仍由屏幕推导;
 *   未设置或校验失败 -> 内置默认布局 (computeDefaults), 详见 docs/BT-INPUT.md §4.5
 * 编辑模式: setEditMode(true) 后不注入任何键值, 拖动组改位置, 松手即落盘, 顶部「完成」退出
 *   (由 ControllerSettingsActivity 以全屏浮层承载, 不注入 P 以免触发 PLAY.PAS 的暂停忙等)
 * 防卡键三守卫:
 *   1. onDetachedFromWindow -> 释放所有按键
 *   2. 指针全部抬起但按键计数残留 -> 强制释放 (状态机安全网)
 *   3. 手指滑出按钮区域 -> 立即释放
 */

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

public class TouchPadView extends View {

    private static final int BTN_LEFT  = 0;
    private static final int BTN_RIGHT = 1;
    private static final int BTN_UP    = 2;
    private static final int BTN_DOWN  = 3;
    private static final int BTN_JUMP  = 4;
    private static final int BTN_FIRE  = 5;
    private static final int BTN_PAUSE = 6;
    private static final int BTN_SOUND = 7;
    private static final int BTN_EXIT  = 8;
    private static final int BTN_SETTINGS = 9; // 齿轮: 不注入键值, 打开手柄设置 (BT-INPUT.md §4.5)
    private static final int BTN_COUNT = 10;

    // BTN_LEFT..BTN_DOWN 是十字方向键, 命中判定用整个热区按方向分区, 不用各自的 rect
    private static final int DPAD_LAST = BTN_DOWN;

    // 可拖动组: 四个方向键共用一个热区, 作为一组整体移动
    private static final int GROUP_DPAD = 0;
    private static final int GROUP_JUMP = 1;
    private static final int GROUP_FIRE = 2;
    private static final int GROUP_PAUSE = 3;
    private static final int GROUP_SOUND = 4;
    private static final int GROUP_EXIT = 5;
    private static final int GROUP_SETTINGS = 6;
    private static final int GROUP_COUNT = 7;

    private static final int[] BUTTON_OF_GROUP = {
            BTN_LEFT, BTN_JUMP, BTN_FIRE, BTN_PAUSE, BTN_SOUND, BTN_EXIT, BTN_SETTINGS
    };

    private static final String[] LABELS = {"\u25C0", "\u25B6", "\u25B2", "\u25BC", "A", "B", "\u2016", "\u266A", "\u2715", "\u2699"};

    private final SparseArray<Integer> pointerToButton = new SparseArray<Integer>();
    private final int[] pressCount = new int[BTN_COUNT];
    private final float[][] btnRect = new float[BTN_COUNT][4];
    private final float[] padRect = new float[4];
    private float padDeadSq;
    private float big, cell, small;
    private int viewW, viewH;
    private MappingStore store;
    private boolean runInjected;
    private int runPendingDir = -1;
    private final Paint fillPaint = new Paint();
    private final Paint padPaint = new Paint();
    private final Paint textPaint = new Paint();

    // 布局数据: 每组中心点归一化坐标 + 组尺寸(像素), 绘制与命中判定共用同一份
    private final float[] groupCx = new float[GROUP_COUNT];
    private final float[] groupCy = new float[GROUP_COUNT];
    private final float[] groupSize = new float[GROUP_COUNT];

    // 编辑模式
    private boolean editMode;
    private int dragGroup = -1;
    private float dragOffX, dragOffY;
    private final float[] doneRect = new float[4];
    private static final int EDIT_BG = 0xFF141414;
    private final Paint hiPaint = new Paint();
    private final Paint donePaint = new Paint();
    private final Paint strokePaint = new Paint();
    private OnEditDone onEditDone;

    public interface OnEditDone {
        void onEditDone();
    }

    // 自动跑: ◀/▶ 连续按住阈值后补发 CTRL_LEFT 并保持, 方向松开或换到 ↑/↓ 立即收回
    // 阈值与手柄/键盘映射层共用 (GamepadInputMapper), 改一处两边同步
    private final Runnable injectRun = new Runnable() {
        @Override
        public void run() {
            runPendingDir = -1;
            runInjected = true;
            try { SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_CTRL_LEFT); } catch (Throwable ignored) {}
        }
    };

    public TouchPadView(Context context) {
        super(context);
        fillPaint.setColor(0x88222222);
        fillPaint.setStyle(Paint.Style.FILL);
        padPaint.setColor(0x33222222);
        padPaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(0xEEFFFFFF);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setAntiAlias(true);
        hiPaint.setColor(0x66FFEB3B);
        hiPaint.setStyle(Paint.Style.FILL);
        donePaint.setColor(0xEE2E6DA4);
        donePaint.setStyle(Paint.Style.FILL);
        strokePaint.setColor(0x66FFFFFF);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(2f);
        strokePaint.setAntiAlias(true);
        try { store = new MappingStore(context); } catch (Throwable ignored) {}
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        viewW = w;
        viewH = h;
        relayout();
    }

    /** 默认位置 -> 自定义位置 -> 换算成 rect; 尺寸始终由屏幕推导 (只调位置) */
    private void relayout() {
        if (viewW <= 0 || viewH <= 0) return;
        computeDefaults();
        loadCustomLayout();
        applyLayout();
        invalidate();
    }

    /** 游戏侧从设置页返回后调用, 让新布局立即生效 */
    public void reloadLayout() {
        relayout();
    }

    private void computeDefaults() {
        float m = viewW * 0.015f;
        big = viewH * 0.15f;
        small = viewH * 0.075f;
        cell = big * 0.8f;
        float padSide = 3 * cell;

        groupSize[GROUP_DPAD] = padSide;
        groupCx[GROUP_DPAD] = (m + padSide / 2f) / viewW;
        groupCy[GROUP_DPAD] = (viewH - padSide - m + padSide / 2f) / viewH;

        groupSize[GROUP_JUMP] = big;
        groupSize[GROUP_FIRE] = big;
        groupSize[GROUP_PAUSE] = small;
        groupSize[GROUP_SOUND] = small;
        groupSize[GROUP_EXIT] = small;
        groupSize[GROUP_SETTINGS] = small;

        // 右下 B(发弹)/A(跳) 两圆: 左 B 右 A 沿用红白机布局, 整排离底边上抬 lift (拇指自然落点偏上)
        float lift = big * 0.45f;
        float abcCy = (viewH - m - lift - big / 2f) / viewH;
        groupCy[GROUP_FIRE] = abcCy;
        groupCy[GROUP_JUMP] = abcCy;
        groupCx[GROUP_JUMP] = (viewW - m - big / 2f) / viewW;
        groupCx[GROUP_FIRE] = (viewW - 2 * m - big - big / 2f) / viewW;

        float topCy = (m + small / 2f) / viewH;
        groupCy[GROUP_PAUSE] = topCy;
        groupCy[GROUP_SOUND] = topCy;
        groupCy[GROUP_EXIT] = topCy;
        groupCy[GROUP_SETTINGS] = topCy;
        groupCx[GROUP_PAUSE] = (m + small / 2f) / viewW;
        groupCx[GROUP_SOUND] = (2 * m + 1.5f * small) / viewW;
        groupCx[GROUP_EXIT] = (viewW - m - small / 2f) / viewW;
        groupCx[GROUP_SETTINGS] = (viewW - 2 * m - 1.5f * small) / viewW;
    }

    /** 任一坏值 / 组数不符 -> 整体回退默认, 不做半套接受 */
    private void loadCustomLayout() {
        if (store == null) return;
        String s = store.getTouchLayout();
        if (s == null) return;
        String[] parts = s.split(";");
        if (parts.length != GROUP_COUNT) return;
        float[] cx = new float[GROUP_COUNT];
        float[] cy = new float[GROUP_COUNT];
        try {
            for (int i = 0; i < GROUP_COUNT; i++) {
                String[] xy = parts[i].split(",");
                if (xy.length != 2) return;
                float x = Float.parseFloat(xy[0]);
                float y = Float.parseFloat(xy[1]);
                if (Float.isNaN(x) || Float.isInfinite(x) || Float.isNaN(y) || Float.isInfinite(y)) return;
                cx[i] = clamp(x, 0f, 1f);
                cy[i] = clamp(y, 0f, 1f);
            }
        } catch (Throwable ignored) {
            return;
        }
        for (int i = 0; i < GROUP_COUNT; i++) {
            groupCx[i] = cx[i];
            groupCy[i] = cy[i];
        }
    }

    private void applyLayout() {
        for (int g = 0; g < GROUP_COUNT; g++) {
            float half = groupSize[g] / 2f;
            float ccx = clamp(groupCx[g] * viewW, half, viewW - half);
            float ccy = clamp(groupCy[g] * viewH, half, viewH - half);
            groupCx[g] = ccx / viewW;
            groupCy[g] = ccy / viewH;

            if (g == GROUP_DPAD) {
                float padL = ccx - half, padT = ccy - half;
                padRect[0] = padL;
                padRect[1] = padT;
                padRect[2] = padL + 3 * cell;
                padRect[3] = padT + 3 * cell;
                float dead = cell * 0.34f;
                padDeadSq = dead * dead;
                btnRect[BTN_LEFT] = new float[]{padL, padT + cell, padL + cell, padT + 2 * cell};
                btnRect[BTN_RIGHT] = new float[]{padL + 2 * cell, padT + cell, padL + 3 * cell, padT + 2 * cell};
                btnRect[BTN_UP] = new float[]{padL + cell, padT, padL + 2 * cell, padT + cell};
                btnRect[BTN_DOWN] = new float[]{padL + cell, padT + 2 * cell, padL + 2 * cell, padT + 3 * cell};
            } else {
                int b = BUTTON_OF_GROUP[g];
                btnRect[b] = new float[]{ccx - half, ccy - half, ccx + half, ccy + half};
            }
        }
        float dw = small * 1.6f, dh = small * 0.9f, m = viewW * 0.015f;
        doneRect[0] = viewW / 2f - dw / 2f;
        doneRect[1] = m;
        doneRect[2] = viewW / 2f + dw / 2f;
        doneRect[3] = m + dh;
    }

    private static float clamp(float v, float lo, float hi) {
        if (hi < lo) return (lo + hi) / 2f;
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private int hitTest(float x, float y) {
        // 方向键: 落在十字热区内按主轴判向, 中心死区防误触发; 区内滑动即可换向
        if (padRect[2] > 0 && x >= padRect[0] && x <= padRect[2] && y >= padRect[1] && y <= padRect[3]) {
            float dx = x - (padRect[0] + padRect[2]) / 2f;
            float dy = y - (padRect[1] + padRect[3]) / 2f;
            if (dx * dx + dy * dy < padDeadSq) return -1;
            if (dx * dx > dy * dy) return dx > 0 ? BTN_RIGHT : BTN_LEFT;
            return dy > 0 ? BTN_DOWN : BTN_UP;
        }
        for (int i = DPAD_LAST + 1; i < BTN_COUNT; i++) {
            float[] r = btnRect[i];
            if (x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3]) return i;
        }
        return -1;
    }

    // ---- 编辑模式 ----

    public void setEditMode(boolean b) {
        if (editMode == b) return;
        releaseAll();
        editMode = b;
        dragGroup = -1;
        invalidate();
    }

    public void setOnEditDone(OnEditDone cb) {
        onEditDone = cb;
    }

    /** 命中优先级: 小按钮先于方向键大热区, 拖动后重叠时仍能选中上层 */
    private int groupHitTest(float x, float y) {
        for (int g = GROUP_COUNT - 1; g > GROUP_DPAD; g--) {
            float[] r = btnRect[BUTTON_OF_GROUP[g]];
            if (r[2] > 0 && x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3]) return g;
        }
        if (padRect[2] > 0 && x >= padRect[0] && x <= padRect[2] && y >= padRect[1] && y <= padRect[3]) {
            return GROUP_DPAD;
        }
        return -1;
    }

    private void handleEditTouch(MotionEvent ev) {
        int action = ev.getActionMasked();
        float x = ev.getX(0);
        float y = ev.getY(0);

        if (action == MotionEvent.ACTION_DOWN) {
            if (x >= doneRect[0] && x <= doneRect[2] && y >= doneRect[1] && y <= doneRect[3]) {
                dragGroup = -1;
                setEditMode(false);
                if (onEditDone != null) onEditDone.onEditDone();
                return;
            }
            int g = groupHitTest(x, y);
            if (g >= 0) {
                dragGroup = g;
                dragOffX = x - groupCx[g] * viewW;
                dragOffY = y - groupCy[g] * viewH;
                invalidate();
            }
            return;
        }
        if (action == MotionEvent.ACTION_MOVE && dragGroup >= 0) {
            float half = groupSize[dragGroup] / 2f;
            groupCx[dragGroup] = clamp(x - dragOffX, half, viewW - half) / viewW;
            groupCy[dragGroup] = clamp(y - dragOffY, half, viewH - half) / viewH;
            applyLayout();
            invalidate();
            return;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (dragGroup >= 0) {
                dragGroup = -1;
                saveLayout();
                invalidate();
            }
        }
    }

    private void saveLayout() {
        if (store == null) return;
        StringBuilder sb = new StringBuilder(GROUP_COUNT * 12);
        for (int i = 0; i < GROUP_COUNT; i++) {
            if (i > 0) sb.append(';');
            sb.append(String.format(Locale.US, "%.4f,%.4f", groupCx[i], groupCy[i]));
        }
        try { store.setTouchLayout(sb.toString()); } catch (Throwable ignored) {}
    }

    private void pressButton(int btn) {
        if (pressCount[btn] == 0) sendDown(btn);
        pressCount[btn]++;
    }

    private void releaseButton(int btn) {
        if (pressCount[btn] > 0) pressCount[btn]--;
        if (pressCount[btn] == 0) sendUp(btn);
    }

    private void forceRelease(int btn) {
        if (pressCount[btn] > 0) {
            pressCount[btn] = 0;
            sendUp(btn);
        }
    }

    private void releaseAll() {
        for (int i = 0; i < BTN_COUNT; i++) forceRelease(i);
        pointerToButton.clear();
        cancelRunSchedule();
        releaseRun();
    }

    /** 每次触摸事件处理后同步自动跑状态 */
    private void updateAutoRun() {
        int dir = runningDirection();
        if (dir < 0) {
            cancelRunSchedule();
            releaseRun();
            return;
        }
        if (runInjected || dir == runPendingDir) return; // 已注入 / 同方向继续计时
        cancelRunSchedule();
        if (store != null && !store.getAutoRun()) return;
        runPendingDir = dir;
        postDelayed(injectRun, MappingStore.AUTO_RUN_MS);
    }

    /** 横向且未按住 ↑/↓ 才计跑: 下管道要求 XVel=0, 上下键不参与自动跑 */
    private int runningDirection() {
        int dir = -1;
        for (int i = 0; i < pointerToButton.size(); i++) {
            int b = pointerToButton.valueAt(i);
            if (b == BTN_UP || b == BTN_DOWN) return -1;
            if (b == BTN_LEFT || b == BTN_RIGHT) dir = b;
        }
        return dir;
    }

    private void cancelRunSchedule() {
        removeCallbacks(injectRun);
        runPendingDir = -1;
    }

    private void releaseRun() {
        if (!runInjected) return;
        runInjected = false;
        try { SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_CTRL_LEFT); } catch (Throwable ignored) {}
    }

    private void sendDown(int btn) {
        int kc = keycodeFor(btn);
        if (kc != 0) {
            try { SDLActivity.onNativeKeyDown(kc); } catch (Throwable ignored) {}
        }
    }

    private void sendUp(int btn) {
        int kc = keycodeFor(btn);
        if (kc != 0) {
            try { SDLActivity.onNativeKeyUp(kc); } catch (Throwable ignored) {}
        }
    }

    private static int keycodeFor(int btn) {
        switch (btn) {
            case BTN_LEFT:  return KeyEvent.KEYCODE_DPAD_LEFT;
            case BTN_RIGHT: return KeyEvent.KEYCODE_DPAD_RIGHT;
            case BTN_UP:    return KeyEvent.KEYCODE_DPAD_UP;
            case BTN_DOWN:  return KeyEvent.KEYCODE_DPAD_DOWN;
            case BTN_JUMP:  return KeyEvent.KEYCODE_ALT_LEFT;
            case BTN_FIRE:  return KeyEvent.KEYCODE_SPACE;
            case BTN_PAUSE: return KeyEvent.KEYCODE_P;
            case BTN_SOUND: return KeyEvent.KEYCODE_Q;
            case BTN_EXIT:  return KeyEvent.KEYCODE_ESCAPE;
        }
        return 0;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (editMode) {
            handleEditTouch(ev);
            return true;
        }
        int action = ev.getActionMasked();

        // 守卫 2: 状态机安全网 —— 指针全离但按键计数残留时强制释放
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            boolean anyDown = false;
            for (int i = 0; i < BTN_COUNT; i++) {
                if (pressCount[i] > 0) { anyDown = true; break; }
            }
            if (!anyDown && pointerToButton.size() > 0) {
                releaseAll();
                return true;
            }
        }

        int count = ev.getPointerCount();
        int actionIndex = ev.getActionIndex();
        int actionId = ev.getPointerId(actionIndex);

        for (int p = 0; p < count; p++) {
            int id = ev.getPointerId(p);
            float x = ev.getX(p);
            float y = ev.getY(p);
            int oldBtn = pointerToButton.get(id, -1);
            int newBtn = hitTest(x, y);
            boolean thisPointer = (id == actionId);

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (thisPointer && oldBtn < 0 && newBtn >= 0) {
                        if (newBtn == BTN_SETTINGS) {
                            openSettings(); // 齿轮不注入键值
                        } else {
                            pointerToButton.put(id, newBtn);
                            pressButton(newBtn);
                        }
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                    if (thisPointer && oldBtn >= 0) {
                        releaseButton(oldBtn);
                        pointerToButton.remove(id);
                    }
                    break;

                case MotionEvent.ACTION_MOVE:
                    // 守卫 3: 滑出按钮区域视为释放; 滑入新按钮则按下
                    if (oldBtn != newBtn) {
                        if (oldBtn >= 0) {
                            releaseButton(oldBtn);
                            pointerToButton.remove(id);
                        }
                        if (newBtn >= 0 && newBtn != BTN_SETTINGS) {
                            pointerToButton.put(id, newBtn);
                            pressButton(newBtn);
                        }
                    }
                    break;
            }
        }
        updateAutoRun();
        return true;
    }

    private void openSettings() {
        try {
            android.content.Intent i = new android.content.Intent(getContext(), ControllerSettingsActivity.class);
            getContext().startActivity(i);
        } catch (Throwable ignored) {}
    }

    @Override
    public void setVisibility(int visibility) {
        // 防卡键: 隐藏前释放全部按键 (BT-INPUT.md §4.5)
        if (visibility != VISIBLE) releaseAll();
        super.setVisibility(visibility);
    }

    @Override
    protected void onDetachedFromWindow() {
        // 守卫 1: 视图销毁时释放全部按键, 防止 SDL 侧卡键
        releaseAll();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (editMode) canvas.drawColor(EDIT_BG);
        if (padRect[2] > 0) {
            // 淡色底表示整块都是方向键热区 (四角同样可判向, 不只是四个圆)
            float pr = (padRect[2] - padRect[0]) * 0.08f;
            if (dragGroup == GROUP_DPAD) {
                canvas.drawRoundRect(padRect[0], padRect[1], padRect[2], padRect[3], pr, pr, hiPaint);
            }
            canvas.drawRoundRect(padRect[0], padRect[1], padRect[2], padRect[3], pr, pr, padPaint);
            if (editMode) {
                canvas.drawRoundRect(padRect[0], padRect[1], padRect[2], padRect[3], pr, pr, strokePaint);
            }
        }
        for (int i = 0; i < BTN_COUNT; i++) {
            float[] r = btnRect[i];
            if (r[2] == 0 || r[3] == 0) continue;
            if (editMode && dragGroup == groupOfButton(i)) {
                canvas.drawRect(r[0], r[1], r[2], r[3], hiPaint);
            }
            canvas.drawCircle((r[0] + r[2]) / 2f, (r[1] + r[3]) / 2f, (r[2] - r[0]) * 0.45f, fillPaint);
            textPaint.setTextSize(textSizeFor(i));
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            canvas.drawText(LABELS[i], (r[0] + r[2]) / 2f,
                    (r[1] + r[3]) / 2f - (fm.ascent + fm.descent) / 2f, textPaint);
        }
        if (editMode) {
            // 文案写成 u 转义 (单反斜杠): 不依赖源文件编码, 见 AGENTS.md 已知坑
            float pr = (doneRect[3] - doneRect[1]) * 0.35f;
            canvas.drawRoundRect(doneRect[0], doneRect[1], doneRect[2], doneRect[3], pr, pr, donePaint);
            textPaint.setTextSize(small * 0.5f);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            canvas.drawText("\u5B8C\u6210", (doneRect[0] + doneRect[2]) / 2f,
                    (doneRect[1] + doneRect[3]) / 2f - (fm.ascent + fm.descent) / 2f, textPaint);
            canvas.drawText("\u62D6\u52A8\u6309\u94AE\u8C03\u6574\u4F4D\u7F6E\uFF0C\u677E\u624B\u5373\u4FDD\u5B58",
                    viewW / 2f, doneRect[3] + small * 0.75f, textPaint);
        }
    }

    /** 按钮 -> 所属组, 用于拖动高亮 */
    private static int groupOfButton(int btn) {
        if (btn <= DPAD_LAST) return GROUP_DPAD;
        for (int g = GROUP_DPAD + 1; g < GROUP_COUNT; g++) {
            if (BUTTON_OF_GROUP[g] == btn) return g;
        }
        return -1;
    }

    private float textSizeFor(int i) {
        if (i <= DPAD_LAST) return cell * 0.62f;
        if (i == BTN_JUMP || i == BTN_FIRE) return big * 0.46f;
        return small * 0.62f;
    }
}
