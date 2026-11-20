package com.jarvis.harness

import android.content.Context
import androidx.annotation.StringRes

/**
 * Resource lookup used by the harness engine for status text and notifications.
 *
 * Mobile Harness resolved these through its own in-app language picker. The module
 * has no opinion about language: it always reads the host application's resources,
 * so the host app's own language/persona settings apply. The name is kept identical
 * to the upstream helper so the ported engine code stays untouched.
 */
object AppStrings {
    fun get(context: Context, @StringRes id: Int, vararg args: Any): String =
        context.getString(id, *args)

    /**
     * Kept for call-site compatibility with upstream, which swapped in a locale-wrapped
     * context. The module applies no locale override, so this is the identity function.
     */
    fun context(context: Context): Context = context
}
