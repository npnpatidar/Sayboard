package com.elishaazaria.sayboard

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.ArrayRes
import androidx.annotation.BoolRes
import androidx.annotation.IntegerRes
import androidx.annotation.StringRes

@SuppressLint("StaticFieldLeak")
object AppCtx {
    @JvmStatic
    @SuppressLint("StaticFieldLeak") // App context
    var appCtx: Context? = null
        private set

    @JvmStatic
    fun setAppCtx(context: Context) {
        appCtx = context.applicationContext
    }

    @JvmStatic
    fun getStringRes(@StringRes res: Int): String {
        return checkNotNull(appCtx) { "AppCtx not initialized: call setAppCtx() from Application.onCreate first" }.getString(res)
    }

    @JvmStatic
    fun getIntegerRes(@IntegerRes res: Int): Int {
        return checkNotNull(appCtx) { "AppCtx not initialized: call setAppCtx() from Application.onCreate first" }.resources.getInteger(res)
    }

    @JvmStatic
    fun getBoolRes(@BoolRes res: Int): Boolean {
        return checkNotNull(appCtx) { "AppCtx not initialized: call setAppCtx() from Application.onCreate first" }.resources.getBoolean(res)
    }

    fun getStringArrayRes(@ArrayRes res: Int): Array<String> {
        return checkNotNull(appCtx) { "AppCtx not initialized: call setAppCtx() from Application.onCreate first" }.resources.getStringArray(res)
    }
}