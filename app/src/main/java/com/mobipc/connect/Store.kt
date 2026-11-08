package com.mobipc.connect

import android.content.Context
import android.content.SharedPreferences

object Store {
    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        if (!::sp.isInitialized) sp = c.applicationContext.getSharedPreferences("mobi", Context.MODE_PRIVATE)
    }

    private fun get(k: String): String = sp.getString(k, "") ?: ""
    private fun put(k: String, v: String) { sp.edit().putString(k, v).apply() }

    var url: String
        get() = get("url")
        set(v) = put("url", v)
    var anon: String
        get() = get("anon")
        set(v) = put("anon", v)
    var access: String
        get() = get("access")
        set(v) = put("access", v)
    var refresh: String
        get() = get("refresh")
        set(v) = put("refresh", v)
    var deviceId: String
        get() = get("deviceId")
        set(v) = put("deviceId", v)
    var control: Boolean
        get() = sp.getBoolean("control", true)
        set(v) { sp.edit().putBoolean("control", v).apply() }
}
