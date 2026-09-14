package com.gflix.app.ui

import android.content.Context
import android.content.pm.PackageManager
import android.view.View

/**
 * Leanback focus polish (ports sozo-tv TvFocus + Kodi 10-foot focus rules).
 * Applied centrally in [com.gflix.app.adapters.AppAdapter.onCreateViewHolder]
 * for every TV item type: scale-up + lift on focus, reset on blur.
 * No-op on non-Leanback devices so mobile behavior is untouched.
 */
object TvFocus {

    const val SCALE_CARD = 1.06f
    const val SCALE_ROW = 1.03f
    private const val DURATION_MS = 120L

    fun isTv(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    /** Attach once at holder creation (never in bind — recycled views keep it). */
    fun applyScale(view: View, scale: Float = SCALE_CARD) {
        if (!isTv(view.context)) return
        view.setOnFocusChangeListener { v, focused ->
            v.animate().cancel()
            val target = if (focused) scale else 1f
            v.animate()
                .scaleX(target)
                .scaleY(target)
                .translationZ(if (focused) 8f else 0f)
                .setDuration(DURATION_MS)
                .start()
        }
    }

    /** Give initial focus to the first focusable child, ignoring stale focus. */
    fun requestInitialFocus(root: View): Boolean {
        if (!isTv(root.context)) return false
        if (root.hasFocus()) return true
        return root.focusSearch(View.FOCUS_DOWN)?.requestFocus() ?: root.requestFocus()
    }
}
