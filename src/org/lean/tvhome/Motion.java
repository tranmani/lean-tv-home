package org.lean.tvhome;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.view.Choreographer;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * The motion language, modelled on tvOS: focus changes ride a slightly underdamped spring, the
 * page scrolls on a critically damped spring that keeps its speed when the target moves, and a
 * key press that has nowhere to go gives a small bump instead of doing nothing.
 */
final class Motion {
    private Motion() {}

    /** Focus grow and shrink. */
    static final int FOCUS_MS = 420;
    static final int UNFOCUS_MS = 320;
    /** Fades of labels, the shelf and overlays. */
    static final int FADE_MS = 260;

    /** iOS "ease out" curve, for fades and anything that should not overshoot. */
    static final TimeInterpolator EASE_OUT = new PathInterpolator(0.25f, 0.1f, 0.25f, 1f);
    static final TimeInterpolator EASE_IN_OUT = new PathInterpolator(0.42f, 0f, 0.58f, 1f);

    /** Focus spring: a hint of overshoot, settled well inside the duration. */
    static final TimeInterpolator FOCUS = new Spring(0.72f, 2.0f);
    /** Unfocus spring: no visible overshoot. */
    static final TimeInterpolator SETTLE = new Spring(0.92f, 2.4f);

    /**
     * A damped spring from 0 to 1 over the animation. {@code damping} is the damping ratio and
     * {@code cycles} is how many natural periods fit in the duration.
     */
    static final class Spring implements TimeInterpolator {
        private final float zeta, omega, omegaD;

        Spring(float damping, float cycles) {
            zeta = damping;
            omega = (float) (2 * Math.PI * cycles);
            omegaD = omega * (float) Math.sqrt(1 - zeta * zeta);
        }

        @Override
        public float getInterpolation(float t) {
            if (t >= 1f) return 1f;
            double decay = Math.exp(-zeta * omega * t);
            return (float) (1 - decay * (Math.cos(omegaD * t) + zeta * omega / omegaD * Math.sin(omegaD * t)));
        }
    }

    /**
     * A value that follows a moving target on a critically damped spring, one step per frame.
     * Used for scrolling: holding a direction key keeps moving the target, and the motion stays
     * continuous because the speed carries over instead of restarting at every key press.
     */
    static final class Follower implements Choreographer.FrameCallback {
        interface Sink {
            void set(float value);
        }

        private final Sink sink;
        private final float stiffness, damping;
        private float value, velocity, target;
        private long lastNanos;
        private boolean running;

        /** {@code response} is roughly the time in seconds to cover most of the distance. */
        Follower(float response, Sink sink) {
            this.sink = sink;
            float w = (float) (2 * Math.PI / response);
            stiffness = w * w;
            damping = 2 * w;
        }

        float value() {
            return value;
        }

        float target() {
            return target;
        }

        void jump(float v) {
            value = target = v;
            velocity = 0;
            sink.set(v);
        }

        void to(float t) {
            target = t;
            if (!running) {
                running = true;
                lastNanos = 0;
                Choreographer.getInstance().postFrameCallback(this);
            }
        }

        @Override
        public void doFrame(long nanos) {
            float dt = lastNanos == 0 ? 1 / 60f : Math.min((nanos - lastNanos) / 1e9f, 0.05f);
            lastNanos = nanos;
            // Small fixed sub-steps keep the integration stable on a dropped frame.
            for (float left = dt; left > 0; left -= 0.004f) {
                float h = Math.min(left, 0.004f);
                float a = -stiffness * (value - target) - damping * velocity;
                velocity += a * h;
                value += velocity * h;
            }
            if (Math.abs(value - target) < 0.4f && Math.abs(velocity) < 8f) {
                value = target;
                velocity = 0;
                running = false;
            }
            sink.set(value);
            if (running) Choreographer.getInstance().postFrameCallback(this);
        }
    }

    /** tvOS "you can't go further" bump: a short damped nudge toward the pressed direction. */
    static void bump(View v, int dx, int dy) {
        if (Look.motion == 1) return;
        float d = v.getResources().getDisplayMetrics().density * 7;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(380);
        a.addUpdateListener(an -> {
            float t = (float) an.getAnimatedValue();
            float s = (float) (Math.sin(t * Math.PI * 2) * Math.exp(-4.5 * t));
            v.setTranslationX(dx * d * s);
            v.setTranslationY(dy * d * s);
        });
        a.start();
    }

    /** The edit-mode wiggle: a small endless rotation while an icon is being moved. */
    static ValueAnimator jiggle(View v) {
        ValueAnimator a = ValueAnimator.ofFloat(-1f, 1f);
        a.setDuration(150);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setInterpolator(EASE_IN_OUT);
        a.addUpdateListener(an -> v.setRotation(1.6f * (float) an.getAnimatedValue()));
        a.start();
        return a;
    }
}
