/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.systemui.statusbar;

import android.animation.Animator;
import android.view.View;
import android.view.ViewPropertyAnimator;

import androidx.annotation.Nullable;

import com.android.app.animation.Interpolators;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;

/**
 * A helper to fade views in and out.
 */
public class CrossFadeHelper {
    public static final long ANIMATION_DURATION_LENGTH = 210;

    public static void fadeOut(final View view) {
        fadeOut(view, (Runnable) null);
    }

    public static void fadeOut(final View view, final Runnable endRunnable) {
        fadeOut(view, ANIMATION_DURATION_LENGTH, 0, endRunnable);
    }

    public static void fadeOut(final View view, final Animator.AnimatorListener listener) {
        fadeOut(view, ANIMATION_DURATION_LENGTH, 0, listener);
    }

    public static void fadeOut(final View view, long duration, int delay) {
        fadeOut(view, duration, delay, (Runnable) null);
    }

    /**
     * Perform a fade-out animation, invoking {@code endRunnable} when the animation ends. It will
     * not be invoked if the animation is cancelled.
     *
     * @deprecated Use {@link #fadeOut(View, long, int, Animator.AnimatorListener)} instead.
     */
    @Deprecated
    public static void fadeOut(final View view, long duration, int delay,
            @Nullable final Runnable endRunnable) {
        view.animate().cancel();
        view.animate()
                .alpha(0f)
                .setDuration(duration)
                .setInterpolator(Interpolators.ALPHA_OUT)
                .setStartDelay(delay)
                .withEndAction(() -> {
                    if (endRunnable != null) {
                        endRunnable.run();
                    }
                    if (view.getVisibility() != View.GONE) {
                        view.setVisibility(View.INVISIBLE);
                    }
                });
        if (view.hasOverlappingRendering()) {
            view.animate().withLayer();
        }
    }

    public static void fadeOut(final View view, long duration, int delay,
            @Nullable final Animator.AnimatorListener listener) {
        view.animate().cancel();
        ViewPropertyAnimator animator = view.animate()
                .alpha(0f)
                .setDuration(duration)
                .setInterpolator(Interpolators.ALPHA_OUT)
                .setStartDelay(delay)
                .withEndAction(() -> {
                    if (view.getVisibility() != View.GONE) {
                        view.setVisibility(View.INVISIBLE);
                    }
                });
        if (listener != null) {
            animator.setListener(listener);
        }
        if (view.hasOverlappingRendering()) {
            view.animate().withLayer();
        }
    }

    public static void fadeOut(View view, float fadeOutAmount) {
        fadeOut(view, fadeOutAmount, true /* remap */);
    }

    /**
     * Fade out a view by a given progress amount
     * @param view the view to fade out
     * @param fadeOutAmount how much the view is faded out. 0 means not at all and 1 means fully
     *                      faded out
     * @param remap whether the fade amount should be remapped to the shorter duration
     * {@link #ANIMATION_DURATION_LENGTH} from the normal fade duration
     * {@link StackStateAnimator#ANIMATION_DURATION_STANDARD} in order to have a faster fading.
     *
     * @see #fadeIn(View, float, boolean)
     */
    public static void fadeOut(View view, float fadeOutAmount, boolean remap) {
        view.animate().cancel();
        if (fadeOutAmount == 1.0f && view.getVisibility() != View.GONE) {
            view.setVisibility(View.INVISIBLE);
        } else if (view.getVisibility() == View.INVISIBLE) {
            view.setVisibility(View.VISIBLE);
        }
        if (remap) {
            fadeOutAmount = mapToFadeDuration(fadeOutAmount);
        }
        float alpha = Interpolators.ALPHA_OUT.getInterpolation(1.0f - fadeOutAmount);
        view.setAlpha(alpha);
        updateLayerType(view, alpha);
    }

    private static float mapToFadeDuration(float fadeOutAmount) {
        // Assuming a linear interpolator, we can easily map it to our new duration
        float endPoint = (float) ANIMATION_DURATION_LENGTH
                / (float) StackStateAnimator.ANIMATION_DURATION_STANDARD;
        return Math.min(fadeOutAmount / endPoint, 1.0f);
    }

    private static void updateLayerType(View view, float alpha) {
        if (view.hasOverlappingRendering() && alpha > 0.0f && alpha < 1.0f) {
            if (view.getLayerType() != View.LAYER_TYPE_HARDWARE) {
                view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                view.setTag(R.id.cross_fade_layer_type_changed_tag, true);
            }
        } else if (view.getLayerType() == View.LAYER_TYPE_HARDWARE
                && view.getTag(R.id.cross_fade_layer_type_changed_tag) != null) {
            view.setLayerType(View.LAYER_TYPE_NONE, null);
        }
    }

    public static void fadeIn(final View view) {
        fadeIn(view, ANIMATION_DURATION_LENGTH, 0);
    }

    public static void fadeIn(final View view, Runnable endRunnable) {
        fadeIn(view, ANIMATION_DURATION_LENGTH, /* delay= */ 0, endRunnable);
    }

    public static void fadeIn(final View view, Animator.AnimatorListener listener) {
        fadeIn(view, ANIMATION_DURATION_LENGTH, /* delay= */ 0, listener);
    }

    public static void fadeIn(final View view, long duration, int delay) {
        fadeIn(view, duration, delay, /* endRunnable= */ (Runnable) null);
    }

    /**
     * Perform a fade-in animation, invoking {@code endRunnable} when the animation ends. It will
     * not be invoked if the animation is cancelled.
     *
     * @deprecated Use {@link #fadeIn(View, long, int, Animator.AnimatorListener)} instead.
     */
    @Deprecated
    public static void fadeIn(final View view, long duration, int delay,
            @Nullable Runnable endRunnable) {
        view.animate().cancel();
        if (view.getVisibility() == View.INVISIBLE) {
            view.setAlpha(0.0f);
            view.setVisibility(View.VISIBLE);
        }
        view.animate()
                .alpha(1f)
                .setDuration(duration)
                .setStartDelay(delay)
                .setInterpolator(Interpolators.ALPHA_IN)
                .withEndAction(endRunnable);
        if (view.hasOverlappingRendering() && view.getLayerType() != View.LAYER_TYPE_HARDWARE) {
            view.animate().withLayer();
        }
    }

    public static void fadeIn(final View view, long duration, int delay,
            @Nullable Animator.AnimatorListener listener) {
        view.animate().cancel();
        if (view.getVisibility() == View.INVISIBLE) {
            view.setAlpha(0.0f);
            view.setVisibility(View.VISIBLE);
        }
        ViewPropertyAnimator animator = view.animate()
                .alpha(1f)
                .setDuration(duration)
                .setStartDelay(delay)
                .setInterpolator(Interpolators.ALPHA_IN);
        if (listener != null) {
            animator.setListener(listener);
        }
        if (view.hasOverlappingRendering() && view.getLayerType() != View.LAYER_TYPE_HARDWARE) {
            view.animate().withLayer();
        }
    }

    public static void fadeIn(View view, float fadeInAmount) {
        fadeIn(view, fadeInAmount, false /* remap */);
    }

    /**
     * Fade in a view by a given progress amount
     * @param view the view to fade in
     * @param fadeInAmount how much the view is faded in. 0 means not at all and 1 means fully
     *                     faded in.
     * @param remap whether the fade amount should be remapped to the shorter duration
     * {@link #ANIMATION_DURATION_LENGTH} from the normal fade duration
     * {@link StackStateAnimator#ANIMATION_DURATION_STANDARD} in order to have a faster fading.
     *
     * @see #fadeOut(View, float, boolean)
     */
    public static void fadeIn(View view, float fadeInAmount, boolean remap) {
        view.animate().cancel();
        if (view.getVisibility() == View.INVISIBLE) {
            view.setVisibility(View.VISIBLE);
        }
        if (remap) {
            fadeInAmount = mapToFadeDuration(fadeInAmount);
        }
        float alpha = Interpolators.ALPHA_IN.getInterpolation(fadeInAmount);
        view.setAlpha(alpha);
        updateLayerType(view, alpha);
    }
}
