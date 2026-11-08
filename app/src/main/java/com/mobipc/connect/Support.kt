package com.mobipc.connect

import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)

/** State shared between the service, the accessibility service and the web page. */
object AppState {
    @Volatile var hosting = false
    @Volatile var peerId = ""
    @Volatile var peerName = ""
    @Volatile var note = ""
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun addListener(f: (String) -> Unit) { listeners.add(f) }
    fun removeListener(f: (String) -> Unit) { listeners.remove(f) }

    fun json(): String = JSONObject()
        .put("hosting", hosting)
        .put("control", Store.control)
        .put("a11y", MobiAccessibilityService.instance != null)
        .put("peerId", peerId)
        .put("peerName", peerName)
        .put("note", note)
        .toString()

    fun refresh() {
        val j = json()
        for (l in listeners) l(j)
    }
}

object Control {
    fun handle(m: JSONObject) {
        if (Store.control) MobiAccessibilityService.instance?.handle(m)
    }
}
