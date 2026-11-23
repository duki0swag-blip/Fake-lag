package com.netlatch.app

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val FILE = "netlatch"
    const val KEY_PROFILE = "profile"
    const val KEY_AUTO = "auto"
    const val KEY_BIDI = "bidi"
    const val KEY_BUBBLE = "bubble"

    fun get(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 0 suave, 1 medio, 2 agresivo. Techo duro de 800 ms. */
    fun range(ctx: Context): IntRange = when (get(ctx).getInt(KEY_PROFILE, 1)) {
        0 -> 250..400
        2 -> 600..800
        else -> 400..600
    }
}
