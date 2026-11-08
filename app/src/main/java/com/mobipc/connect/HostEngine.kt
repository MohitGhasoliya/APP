package com.mobipc.connect

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

open class SdpObs : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}

/**
 * Keeps this phone's screen captured while hosting. Trusted devices are connected automatically,
 * with no extra permission popup. New devices are approved once in the app.
 */
class HostEngine(
    private val ctx: Context,
    private val projectionData: Intent,
    private val onStopped: () -> Unit
) {
    companion object { private var webrtcReady = false }

    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val egl: EglBase = EglBase.create()
    private var factory: PeerConnectionFactory? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var helper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var session: Session? = null
    private val handled = HashSet<String>()
    private val notified = HashSet<String>()
    @Volatile private var stopped = false

    @Suppress("DEPRECATION")
    private fun size(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        val big = maxOf(dm.widthPixels, dm.heightPixels)
        val k = if (big > 1280) 1280f / big else 1f
        return Pair((dm.widthPixels * k).toInt() / 2 * 2, (dm.heightPixels * k).toInt() / 2 * 2)
    }

    fun start() {
        exec.execute {
            try {
                if (!webrtcReady) {
                    PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext)
                            .createInitializationOptions()
                    )
                    webrtcReady = true
                }
                val f = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                    .createPeerConnectionFactory()
                factory = f
                val src = f.createVideoSource(true)
                source = src
                val h = SurfaceTextureHelper.create("mobi-capture", egl.eglBaseContext)
                helper = h
                val cap = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
                    override fun onStop() { exec.execute { stopInternal(); onStopped() } }
                })
                capturer = cap
                cap.initialize(h, ctx.applicationContext, src.capturerObserver)
                val (w, hh) = size()
                cap.startCapture(w, hh, 15)
                track = f.createVideoTrack("screen0", src)
                publish()
                exec.scheduleWithFixedDelay(Runnable {
                    try { tick() } catch (e: Exception) { }
                }, 1, 2500, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                AppState.note = "Could not start screen capture: " + (e.message ?: "unknown error")
                stopInternal()
                onStopped()
            }
        }
    }

    fun updateSize() {
        exec.execute {
            val (w, h) = size()
            try { capturer?.changeCaptureFormat(w, h, 15) } catch (e: Exception) { }
        }
    }

    fun controlChanged() { exec.execute { session?.sendState() } }

    fun serveApproved(id: String, peerId: String, name: String) {
        exec.execute {
            handled.add(id)
            serve(id, peerId, name, false)
        }
    }

    fun stop() { exec.execute { stopInternal() } }

    private fun publish() {
        AppState.hosting = !stopped
        AppState.peerId = session?.peerId ?: ""
        AppState.peerName = session?.name ?: ""
        AppState.refresh()
        AppState.note = ""
    }

    private fun tick() {
        if (stopped) return
        val res = Api.rpc("get_incoming_device_requests") ?: return
        val arr = JSONArray(res)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.str("request_id")
            val target = o.str("target_device_id")
            if (target.isNotEmpty() && target != Store.deviceId) continue
            val age = try {
                System.currentTimeMillis() - OffsetDateTime.parse(o.str("created_at")).toInstant().toEpochMilli()
            } catch (e: Exception) { 0L }
            val name = o.str("requester_device_name").ifEmpty { "A device" }
            if (o.optBoolean("trusted") && age < 120000) {
                serve(id, o.str("requester_device_id"), name, true)
                break
            } else if (notified.add(id)) {
                notifyNew(name)
            }
        }
    }

    private fun notifyNew(name: String) {
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, HostService.CH_REQ)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("New connection request")
            .setContentText("$name wants to connect. Open MobiPC to approve.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(2, n)
    }

    private fun serve(id: String, peerId: String, name: String, approve: Boolean) {
        if (stopped) return
        if (approve && !handled.add(id)) return
        session?.close()
        val s = Session(id, peerId, name)
        session = s
        s.start(approve)
        publish()
    }

    private fun endSession(s: Session) {
        if (session === s) {
            session = null
            s.close()
            publish()
        }
    }

    private fun stopInternal() {
        if (stopped) return
        stopped = true
        try { session?.close() } catch (e: Exception) { }
        session = null
        try { capturer?.stopCapture() } catch (e: Exception) { }
        try { capturer?.dispose() } catch (e: Exception) { }
        try { helper?.dispose() } catch (e: Exception) { }
        try { track?.dispose() } catch (e: Exception) { }
        try { source?.dispose() } catch (e: Exception) { }
        try { factory?.dispose() } catch (e: Exception) { }
        try { egl.release() } catch (e: Exception) { }
        AppState.hosting = false
        AppState.peerId = ""
        AppState.peerName = ""
        AppState.refresh()
        exec.shutdown()
    }

    inner class Session(val id: String, val peerId: String, val name: String) {
        private var pc: PeerConnection? = null
        private var rt: Realtime? = null
        private var dc: DataChannel? = null
        @Volatile private var answered = false
        @Volatile private var sent = false
        @Volatile private var closed = false
        @Volatile private var approvedOnce = false

        private fun approveNow() {
            if (approvedOnce) return
            approvedOnce = true
            Api.rpc("respond_device_connection", JSONObject().put("p_request_id", id).put("p_decision", "approve"))
        }

        fun start(approve: Boolean) {
            val me = this
            rt = Realtime("rtc-$id", {
                if (approve) exec.execute { approveNow() }
            }, { ev, payload ->
                if (ev == "offer" && !answered) {
                    answered = true
                    exec.execute { onOffer(payload) }
                } else if (ev == "bye") {
                    exec.execute { endSession(me) }
                }
            })
            rt?.connect()
            if (approve) exec.schedule(Runnable { approveNow() }, 4, TimeUnit.SECONDS)
            exec.schedule(Runnable { if (!answered) endSession(me) }, 90, TimeUnit.SECONDS)
        }

        private fun onOffer(payload: JSONObject) {
            val f = factory ?: return
            val t = track ?: return
            val me = this
            val sdp = payload.getJSONObject("sdp").getString("sdp")
            val cfg = PeerConnection.RTCConfiguration(
                listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
            )
            cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            val p = f.createPeerConnection(cfg, object : PeerConnection.Observer {
                override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(b: Boolean) {}
                override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
                    if (s == PeerConnection.IceGatheringState.COMPLETE) sendAnswer()
                }
                override fun onIceCandidate(c: IceCandidate?) {}
                override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
                override fun onAddStream(s: MediaStream?) {}
                override fun onRemoveStream(s: MediaStream?) {}
                override fun onDataChannel(ch: DataChannel?) { if (ch != null) attach(ch) }
                override fun onRenegotiationNeeded() {}
                override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {
                    if (s == PeerConnection.PeerConnectionState.FAILED || s == PeerConnection.PeerConnectionState.CLOSED) {
                        exec.execute { endSession(me) }
                    }
                }
            }) ?: return
            pc = p
            p.addTrack(t, listOf("mobi"))
            p.setRemoteDescription(object : SdpObs() {
                override fun onSetSuccess() {
                    p.createAnswer(object : SdpObs() {
                        override fun onCreateSuccess(d: SessionDescription?) {
                            if (d != null) p.setLocalDescription(SdpObs(), d)
                        }
                    }, MediaConstraints())
                }
            }, SessionDescription(SessionDescription.Type.OFFER, sdp))
            exec.schedule(Runnable { sendAnswer() }, 4, TimeUnit.SECONDS)
        }

        @Synchronized
        private fun sendAnswer() {
            if (sent || closed) return
            val d = pc?.localDescription ?: return
            sent = true
            rt?.broadcast("answer", JSONObject().put("sdp", JSONObject().put("type", "answer").put("sdp", d.description)))
        }

        private fun attach(ch: DataChannel) {
            dc = ch
            ch.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(n: Long) {}
                override fun onStateChange() { if (ch.state() == DataChannel.State.OPEN) sendState() }
                override fun onMessage(b: DataChannel.Buffer) {
                    if (b.binary) return
                    val bytes = ByteArray(b.data.remaining())
                    b.data.get(bytes)
                    try { Control.handle(JSONObject(String(bytes))) } catch (e: Exception) { }
                }
            })
        }

        fun sendState() {
            val ch = dc ?: return
            val on = Store.control && MobiAccessibilityService.instance != null
            val msg = JSONObject().put("t", "ctlstate").put("on", on).toString()
            try { ch.send(DataChannel.Buffer(ByteBuffer.wrap(msg.toByteArray()), false)) } catch (e: Exception) { }
        }

        fun close() {
            if (closed) return
            closed = true
            try { rt?.broadcast("bye", JSONObject()) } catch (e: Exception) { }
            val r = rt
            exec.schedule(Runnable { r?.close() }, 500, TimeUnit.MILLISECONDS)
            try { dc?.close() } catch (e: Exception) { }
            try { pc?.close() } catch (e: Exception) { }
            try { pc?.dispose() } catch (e: Exception) { }
        }
    }
}
