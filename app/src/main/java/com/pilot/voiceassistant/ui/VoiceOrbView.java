package com.pilot.voiceassistant.ui;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import com.pilot.voiceassistant.R;
import com.pilot.voiceassistant.core.AssistantState;

/**
 * The animated circle that shows the assistant's state at a glance, by colour and by movement:
 *
 * <pre>
 * STANDBY    grey, breathes slowly
 * LISTENING  green, rings move outwards
 * THINKING   amber, an arc turns around it
 * SPEAKING   blue, beats like a voice
 * </pre>
 */
public final class VoiceOrbView extends View {

    /** One loop of the animation; every movement repeats a whole number of times in it. */
    private static final long LOOP_MS = 2400;
    /** How fast the colour follows a change of state, per frame. */
    private static final float COLOR_STEP = 0.12f;
    private static final int RING_COUNT = 3;
    private static final double TWO_PI = 2 * Math.PI;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArgbEvaluator colors = new ArgbEvaluator();
    private final Drawable micIcon;
    private final ValueAnimator loop = ValueAnimator.ofFloat(0f, 1f);

    private AssistantState state = AssistantState.STANDBY;
    private int color;
    private float phase;

    public VoiceOrbView(Context context, AttributeSet attrs) {
        super(context, attrs);
        color = colorOf(state);
        micIcon = context.getDrawable(R.drawable.ic_mic);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        loop.setDuration(LOOP_MS);
        loop.setRepeatCount(ValueAnimator.INFINITE);
        loop.setInterpolator(new LinearInterpolator());
        loop.addUpdateListener(animation -> {
            phase = (float) animation.getAnimatedValue();
            invalidate();
        });
    }

    public void setState(AssistantState newState) {
        state = newState;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        loop.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        loop.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        color = (int) colors.evaluate(COLOR_STEP, color, colorOf(state));
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        // The rings reach twice the radius of the circle, and must fit in the view.
        float radius = Math.min(getWidth(), getHeight()) / 4.4f;

        float scale;
        switch (state) {
            case LISTENING:
                drawRings(canvas, cx, cy, radius, 1);
                scale = 1f + 0.03f * wave(2);
                break;
            case THINKING:
                drawArc(canvas, cx, cy, radius * 1.3f);
                scale = 1f;
                break;
            case SPEAKING:
                drawRings(canvas, cx, cy, radius, 2);
                scale = 1f + 0.07f * wave(3) + 0.04f * wave(7);
                break;
            default:
                scale = 1f + 0.02f * wave(1);
                break;
        }

        // A soft halo, then the circle itself.
        fill.setColor(color);
        fill.setAlpha(40);
        canvas.drawCircle(cx, cy, radius * scale * 1.18f, fill);
        fill.setAlpha(255);
        canvas.drawCircle(cx, cy, radius * scale, fill);

        int icon = (int) (radius * 0.45f);
        micIcon.setBounds((int) cx - icon, (int) cy - icon, (int) cx + icon, (int) cy + icon);
        micIcon.setAlpha(state == AssistantState.STANDBY ? 150 : 255);
        micIcon.draw(canvas);
    }

    /** Rings that grow from the circle and fade, {@code speed} times per loop. */
    private void drawRings(Canvas canvas, float cx, float cy, float radius, int speed) {
        stroke.setColor(color);
        stroke.setStrokeWidth(radius * 0.035f);
        for (int i = 0; i < RING_COUNT; i++) {
            float progress = (phase * speed + (float) i / RING_COUNT) % 1f;
            stroke.setAlpha((int) (110 * (1f - progress)));
            canvas.drawCircle(cx, cy, radius * (1f + 0.9f * progress), stroke);
        }
    }

    private void drawArc(Canvas canvas, float cx, float cy, float radius) {
        stroke.setColor(color);
        stroke.setAlpha(255);
        stroke.setStrokeWidth(radius * 0.06f);
        canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius,
                360f * phase * 2, 100f, false, stroke);
    }

    /** A sine that goes around {@code timesPerLoop} times in one loop, from -1 to 1. */
    private float wave(int timesPerLoop) {
        return (float) Math.sin(TWO_PI * phase * timesPerLoop);
    }

    private int colorOf(AssistantState s) {
        switch (s) {
            case LISTENING:
                return getContext().getColor(R.color.state_listening);
            case THINKING:
                return getContext().getColor(R.color.state_thinking);
            case SPEAKING:
                return getContext().getColor(R.color.state_speaking);
            default:
                return getContext().getColor(R.color.state_standby);
        }
    }
}
