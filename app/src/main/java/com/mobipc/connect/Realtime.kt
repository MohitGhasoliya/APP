package com.mobipc.connect

import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.Timer
import java.util.TimerTask

/** Minimal Supabase Realtime (Phoenix) client for broadcast messages. Same channels as the website. */
class Realtime(
    name: String,
    private val onJoined: () -> Unit,
    private val onEvent: (String, JSONObject) -> Unit
) {
    private val topic = "realtime:$name"
    private var ws: WebSocket? = null
    private var ref = 1000
    private var timer: Timer? = null

    @Synchronized
    private fun nextRef(): String {
        ref += 1
        return ref.toString()
    }

    private fun send(w: WebSocket, t: String, ev: String, payload: JSONObject, r: String) {
        w.send(
            JSONObject().put("topic", t).put("event", ev).put("payload", payload)
                .put("ref", r).put("join_ref", "1").toString()
        )
    }

    fun connect() {
        val url = Store.url.replaceFirst("https://", "wss://") +
            "/realtime/v1/websocket?apikey=" + Store.anon + "&vsn=1.0.0"
        ws = Api.http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val cfg = JSONObject()
                    .put(
                        "config", JSONObject()
                            .put("broadcast", JSONObject().put("self", false).put("ack", false))
                            .put("presence", JSONObject().put("key", ""))
                            .put("postgres_changes", JSONArray())
                            .put("private", false)
                    )
                    .put("access_token", Store.access)
                send(webSocket, topic, "phx_join", cfg, "1")
                val t = Timer()
                t.schedule(object : TimerTask() {
                    override fun run() { send(webSocket, "phoenix", "heartbeat", JSONObject(), nextRef()) }
                }, 25000, 25000)
                timer = t
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val m = JSONObject(text)
                    when (m.optString("event")) {
                        "phx_reply" -> {
                            val ok = m.optJSONObject("payload")?.optString("status") == "ok"
                            if (m.optString("ref") == "1" && ok) onJoined()
                        }
                        "broadcast" -> {
                            val p = m.getJSONObject("payload")
                            onEvent(p.optString("event"), p.optJSONObject("payload") ?: JSONObject())
                        }
                    }
                } catch (e: Exception) {
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { stopTimer() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { stopTimer() }
        })
    }

    fun broadcast(event: String, payload: JSONObject) {
        val w = ws ?: return
        send(
            w, topic, "broadcast",
            JSONObject().put("type", "broadcast").put("event", event).put("payload", payload),
            nextRef()
        )
    }

    private fun stopTimer() {
        timer?.cancel()
        timer = null
    }

    fun close() {
        stopTimer()
        try { ws?.close(1000, "bye") } catch (e: Exception) { }
        ws = null
    }
}
