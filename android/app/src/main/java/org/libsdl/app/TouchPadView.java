package org.libsdl.app;

/* Mario & Luigi 触摸控制层
 * 通过 SDLActivity.onNativeKeyDown/Up 注入键盘事件 (SDL 官方 Android keycode 映射):
 *   DPAD_LEFT/RIGHT   -> SDLK_LEFT/RIGHT   移动
 *   ALT_LEFT          -> SDLK_LALT         跳
 *   CTRL_LEFT         -> SDLK_LCTRL        跑
 *   SPACE             -> SDLK_SPACE        发射
 *   P / Q / ESCAPE    -> 暂停 / 声音 / 退出
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
    private static final int BTN_JUMP  = 2;
    private static final int BTN_RUN   = 3;
    private static final int BTN_FIRE  = 4;
    private static final int BTN_PAUSE = 5;
    private static final int BTN_SOUND = 6;
    private static final int BTN_EXIT  = 7;
    private static final int BTN_COUNT = 8;

    private static final String[] LABELS = {"\u25C0", "\u25B6", "\u25B2", "RUN", "FIRE", "\u2016", "\u266A", "\u2715"};

    private final SparseArray<Integer> pointerToButton = new SparseArray<Integer>();
    private final int[] pressCount = new int[BTN_COUNT];
    private final float[][] btnRect = new float[BTN_COUNT][4];
    private final Paint fillPaint = new Paint();
    private final Paint textPaint = new Paint();

    public TouchPadView(Context context) {
        super(context);
        fillPaint.setColor(0x88222222);
        fillPaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(0xEEFFFFFF);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setAntiAlias(true);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float m = w * 0.015f;
        float big = h * 0.15f;
        float sm = h * 0.075f;

        // 左下: 左右移动
        btnRect[BTN_LEFT]  = new float[]{m, h - big - m, m + big, h - m};
        btnRect[BTN_RIGHT] = new float[]{m + big + m, h - big - m, m + 2 * big + m, h - m};

        // 右下: 跳 / 跑 / 发射 (竖排)
        float rx = w - big - m;
        btnRect[BTN_JUMP] = new float[]{rx, h - 3 * (big + m), rx + big, h - 2 * (big + m)};
        btnRect[BTN_RUN]  = new float[]{rx, h - 2 * (big + m), rx + big, h - big - m};
        btnRect[BTN_FIRE] = new float[]{rx, h - big - m, rx + big, h - m};

        // 顶部: 暂停 / 声音 / 退出
        btnRect[BTN_PAUSE] = new float[]{m, m, m + sm, m + sm};
        btnRect[BTN_SOUND] = new float[]{m + sm + m, m, m + 2 * sm + m, m + sm};
        btnRect[BTN_EXIT]  = new float[]{w - sm - m, m, w - m, m + sm};

        textPaint.setTextSize(big * 0.55f);
        invalidate();
    }

    private int hitTest(float x, float y) {
        for (int i = 0; i < BTN_COUNT; i++) {
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
            case BTN_JUMP:  return KeyEvent.KEYCODE_ALT_LEFT;
            case BTN_RUN:   return KeyEvent.KEYCODE_CTRL_LEFT;
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
                        pointerToButton.put(id, newBtn);
                        pressButton(newBtn);
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
                        if (newBtn >= 0) {
                            pointerToButton.put(id, newBtn);
                            pressButton(newBtn);
                        }
                    }
                    break;
            }
        }
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        // 守卫 1: 视图销毁时释放全部按键, 防止 SDL 侧卡键
        releaseAll();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        for (int i = 0; i < BTN_COUNT; i++) {
            float[] r = btnRect[i];
            if (r[2] == 0 || r[3] == 0) continue;
            float cx = (r[0] + r[2]) / 2f;
            float cy = (r[1] + r[3]) / 2f;
            float rad = (r[2] - r[0]) * 0.45f;
            canvas.drawCircle(cx, cy, rad, fillPaint);
            float fs = (i == BTN_PAUSE || i == BTN_SOUND || i == BTN_EXIT)
                    ? textPaint.getTextSize() * 0.9f : textPaint.getTextSize();
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            canvas.drawText(LABELS[i], cx, cy - (fm.ascent + fm.descent) / 2f + fs * 0.02f, textPaint);
        }
    }
}
