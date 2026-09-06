package io.iaw.lanshare

import android.animation.ValueAnimator
import android.app.Activity
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator

object UiMotion {
    fun begin(container: ViewGroup, durationMillis: Long = 180L) {
        if (!enabled() || !container.isLaidOut) {
            return
        }
        TransitionManager.beginDelayedTransition(
            container,
            AutoTransition().apply {
                duration = durationMillis
                interpolator = DecelerateInterpolator()
            },
        )
    }

    fun enterFromBottom(view: View, distancePx: Float, durationMillis: Long = 220L) {
        if (!enabled()) {
            view.alpha = 1f
            view.translationY = 0f
            return
        }
        view.alpha = 0f
        view.translationY = distancePx
        view.post {
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(durationMillis)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    fun exitToBottom(
        view: View,
        distancePx: Float,
        durationMillis: Long = 160L,
        onComplete: () -> Unit,
    ) {
        if (!enabled()) {
            onComplete()
            return
        }
        view.animate()
            .alpha(0f)
            .translationY(distancePx)
            .setDuration(durationMillis)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction(onComplete)
            .start()
    }

    @Suppress("DEPRECATION")
    fun suppressPendingTransition(activity: Activity) {
        activity.overridePendingTransition(0, 0)
    }

    fun enabled(): Boolean {
        return ValueAnimator.areAnimatorsEnabled()
    }
}
