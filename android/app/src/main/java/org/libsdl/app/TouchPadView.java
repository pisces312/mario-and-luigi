package org.libsdl.app;

/* Mario & Luigi 触摸控制层
 * 通过 SDLActivity.onNativeKeyDown/Up 注入键盘事件 (SDL 官方 Android keycode 映射):
 *   DPAD_LEFT/RIGHT/UP/DOWN -> SDLK_LEFT/RIGHT/UP/DOWN  移动
 *                           上/下同时是进管道键: 下=站管道口按住 (PLAYERS.PAS CheckPipeBelow),
 *                           上=顶入上方管道 (CheckPipeAbove), 菜单里也可上下选择
 *   ALT_LEFT          -> SDLK_LALT         跳
 *   CTRL_LEFT         -> SDLK_LCTRL        跑 (无独立键: 长按 ◀/▶ 自动注入, 见 updateAutoRun)
 *   SPACE             -> SDLK_SPACE        发射
 *   P / Q / ESCAPE    -> 暂停 / 声音 / 退出
 * 布局: 左下十字方向键热区, 右下 JUMP|FIRE 一行椭圆, 顶排 暂停/声音/齿轮/退出
 * 防卡键三守卫:
 *   1. onDetachedFromWindow -> 释放所有按键
 *   2. 指针全部抬起但按键计数残留 -> 强制释放 (状态机安全网)
 *   3. 手指滑出按钮区域 -> 立即释放
 */

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

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

    private static final String[] LABELS = {"\u25C0", "\u25B6", "\u25B2", "\u25BC", "JUMP", "FIRE", "\u2016", "\u266A", "\u2715", "\u2699"};

    private final SparseArray<Integer> pointerToButton = new SparseArray<Integer>();
    private final int[] pressCount = new int[BTN_COUNT];
    private final float[][] btnRect = new float[BTN_COUNT][4];
    private final float[] padRect = new float[4];
    private final float[] pillRect = new float[4];
    private float padDeadSq;
    private float big, cell, small, pillH;
    private MappingStore store;
    private boolean runInjected;
    private int runPendingDir = -1;
    private final Paint fillPaint = new Paint();
    private final Paint padPaint = new Paint();
    private final Paint divPaint = new Paint();
    private final Paint textPaint = new Paint();

    // 自动跑: ◀/▶ 连续按住 AUTO_RUN_MS 后补发 CTRL_LEFT 并保持, 方向松开或换到 ↑/↓ 立即收回
    private static final int AUTO_RUN_MS = 220;
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
        divPaint.setColor(0x66FFFFFF);
        textPaint.setColor(0xEEFFFFFF);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setAntiAlias(true);
        try { store = new MappingStore(context); } catch (Throwable ignored) {}
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float m = w * 0.015f;
        big = h * 0.15f;
        small = h * 0.075f;
        cell = big * 0.8f;
        pillH = big * 0.95f;
        float pillW = big * 2.3f;
        divPaint.setStrokeWidth(big * 0.02f);

        // 左下: 十字方向键 (热区 3c 见方, 各自 rect 仅用于画圆)
        float padL = m;
        float padT = h - 3 * cell - m;
        btnRect[BTN_LEFT]  = new float[]{padL, padT + cell, padL + cell, padT + 2 * cell};
        btnRect[BTN_RIGHT] = new float[]{padL + 2 * cell, padT + cell, padL + 3 * cell, padT + 2 * cell};
        btnRect[BTN_UP]    = new float[]{padL + cell, padT, padL + 2 * cell, padT + cell};
        btnRect[BTN_DOWN]  = new float[]{padL + cell, padT + 2 * cell, padL + 2 * cell, padT + 3 * cell};
        padRect[0] = padL;
        padRect[1] = padT;
        padRect[2] = padL + 3 * cell;
        padRect[3] = padT + 3 * cell;
        float dead = cell * 0.34f;
        padDeadSq = dead * dead;

        // 右下: 跳 | 发射 合体成一个横向椭圆 (左半跳, 右半发射); 跑由长按方向键自动注入, 不占键位
        float px2 = w - m, px1 = px2 - pillW, py2 = h - m, py1 = py2 - pillH;
        btnRect[BTN_JUMP] = new float[]{px1, py1, px1 + pillW / 2f, py2};
        btnRect[BTN_FIRE] = new float[]{px1 + pillW / 2f, py1, px2, py2};
        pillRect[0] = px1;
        pillRect[1] = py1;
        pillRect[2] = px2;
        pillRect[3] = py2;

        // 顶部: 暂停 / 声音 / 退出 / 设置(齿轮, 退出左侧)
        btnRect[BTN_PAUSE] = new float[]{m, m, m + small, m + small};
        btnRect[BTN_SOUND] = new float[]{m + small + m, m, m + 2 * small + m, m + small};
        btnRect[BTN_EXIT]  = new float[]{w - small - m, m, w - m, m + small};
        btnRect[BTN_SETTINGS] = new float[]{w - 2 * small - 2 * m, m, w - small - 2 * m, m + small};

        invalidate();
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
        postDelayed(injectRun, AUTO_RUN_MS);
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
        if (padRect[2] > 0) {
            // 淡色底表示整块都是方向键热区 (四角同样可判向, 不只是四个圆)
            float pr = (padRect[2] - padRect[0]) * 0.08f;
            canvas.drawRoundRect(padRect[0], padRect[1], padRect[2], padRect[3], pr, pr, padPaint);
        }
        if (pillRect[2] > 0) {
            canvas.drawOval(pillRect[0], pillRect[1], pillRect[2], pillRect[3], fillPaint);
            float mid = (pillRect[0] + pillRect[2]) / 2f;
            float inset = (pillRect[3] - pillRect[1]) * 0.16f;
            canvas.drawLine(mid, pillRect[1] + inset, mid, pillRect[3] - inset, divPaint);
        }
        for (int i = 0; i < BTN_COUNT; i++) {
            float[] r = btnRect[i];
            if (r[2] == 0 || r[3] == 0) continue;
            if (i != BTN_JUMP && i != BTN_FIRE) {
                canvas.drawCircle((r[0] + r[2]) / 2f, (r[1] + r[3]) / 2f, (r[2] - r[0]) * 0.45f, fillPaint);
            }
            textPaint.setTextSize(textSizeFor(i));
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            canvas.drawText(LABELS[i], (r[0] + r[2]) / 2f,
                    (r[1] + r[3]) / 2f - (fm.ascent + fm.descent) / 2f, textPaint);
        }
    }

    private float textSizeFor(int i) {
        if (i <= DPAD_LAST) return cell * 0.62f;
        if (i == BTN_JUMP || i == BTN_FIRE) return pillH * 0.42f;
        return small * 0.62f;
    }
}
