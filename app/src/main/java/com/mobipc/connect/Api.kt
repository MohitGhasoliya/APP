package com.mobipc.connect

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Small Supabase REST client. The login tokens come from the web page (and are refreshed here). */
object Api {
    val http: OkHttpClient = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val JSON = "application/json".toMediaType()

    private fun expiry(token: String): Long = try {
        val payload = token.split(".")[1]
        val text = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        JSONObject(text).getLong("exp")
    } catch (e: Exception) {
        0L
    }

    @Synchronized
    fun ensureToken(): Boolean {
        if (Store.access.isEmpty() || Store.url.isEmpty()) return false
        if (expiry(Store.access) - System.currentTimeMillis() / 1000 > 30) return true
        return refresh()
    }

    private fun refresh(): Boolean {
        if (Store.refresh.isEmpty()) return false
        val body = JSONObject().put("refresh_token", Store.refresh).toString().toRequestBody(JSON)
        val req = Request.Builder()
            .url(Store.url + "/auth/v1/token?grant_type=refresh_token")
            .addHeader("apikey", Store.anon)
            .post(body).build()
        return try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    false
                } else {
                    val j = JSONObject(r.body!!.string())
                    Store.access = j.getString("access_token")
                    Store.refresh = j.getString("refresh_token")
                    true
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    fun rpc(name: String, args: JSONObject = JSONObject()): String? {
        if (!ensureToken()) return null
        val req = Request.Builder()
            .url(Store.url + "/rest/v1/rpc/" + name)
            .addHeader("apikey", Store.anon)
            .addHeader("Authorization", "Bearer " + Store.access)
            .post(args.toString().toRequestBody(JSON)).build()
        return try {
            http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body!!.string() else null }
        } catch (e: Exception) {
            null
        }
    }
}
