package app.yxi.agent

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 网络换了就通知重连（老板 2026-09-08：「连接还是很不稳定，左上角又显示断了」）。
 *
 * 手机在 5G / Wi-Fi / 双卡之间切换时 IP 会变，原来那条 TCP 必死；SSH 那边要等心跳超时（常驻连接 15s×2 = 30 秒）
 * 才知道，再按指数退避（最长 15 秒）重连 —— 用户看到的就是「连接断了」挂着几十秒。
 * 这里盯系统的默认网络：**一换网络就把 [generation] 加一**，连接循环看到号变了立刻断掉重连、退避归零，
 * 不再等心跳。丢网（onLost）也加一：这时重连会失败，但退避从 0.5 秒起，网一回来就接上。
 */
object NetWatch {
    val generation = MutableStateFlow(0)
    @Volatile private var started = false
    @Volatile private var lastNet: String? = null
    @Volatile private var lastKind: String? = null

    fun start(ctx: Context) {
        if (started) return
        started = true
        val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val id = network.toString()
                    if (id != lastNet) { lastNet = id; bump("available $id") }
                }
                override fun onLost(network: Network) { lastNet = null; lastKind = null; bump("lost") }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val kind = when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
                        else -> "other"
                    }
                    if (lastKind != null && kind != lastKind) bump("$lastKind→$kind")   // 同一个 Network 对象上 Wi-Fi ↔ 蜂窝也算换网
                    lastKind = kind
                }
            })
        }
    }

    private fun bump(why: String) {
        generation.value = generation.value + 1
        app.yxi.ui.DevMode.log("net", "网络变了（$why），通知重连")
    }
}
