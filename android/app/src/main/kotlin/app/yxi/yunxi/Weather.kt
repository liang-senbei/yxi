package app.yxi.yunxi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.util.Log
import app.yxi.ui.t
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * 云曦小管家 · 天气。数据源 **Open-Meteo**（open-meteo.com）：免 key、免注册、CC BY 4.0（卡片角落写一行「数据 Open-Meteo」）。
 *
 * - 城市两条路：**手动搜**（[search]）或**用手机定位**（[locate]，老板 2026-09-06 拍板要的；粗略定位、城市级就够，
 *   只在用户点「用当前位置」时申请权限）。反向地理编码用 BigDataCloud 的免 key 客户端接口（国内直连已验：Mac mini 绍兴移动 200）。
 * - 缓存 30 分钟（[Weather.stale]）；[current] 先给缓存、过期才拉。换城市清缓存。
 * - Open-Meteo 直连已从两台国内机器验证（2026-09-06：老板 Windows 1.06s、Mac mini 0.97s）。
 * - 只发经纬度，不带任何身份。
 */
data class City(
    val name: String,
    /** 省 / 州（`admin1`），同名城市靠它分辨：杭州·浙江 vs 杭州·四川甘孜 */
    val admin: String,
    val country: String,
    val lat: Double,
    val lon: Double,
    val tz: String,
    /** 人口，没有就 0。搜索结果按它倒序 —— 大城市永远在前 */
    val population: Long = 0,
) {
    /** 「杭州 · 浙江 · 中国」；省名 / 国名跟城市名重复的不重复写（「北京 · 北京 · 中国」→「北京 · 中国」） */
    val label get() = listOfNotNull(
        name.takeIf { it.isNotBlank() },
        admin.takeIf { it.isNotBlank() && it != name },
        country.takeIf { it.isNotBlank() && it != name && it != admin },
    ).joinToString(" · ")
}

data class Weather(
    val city: City,
    val fetchedAt: Long,
    val tempC: Double,
    val feelsC: Double,
    val humidity: Int,
    val windKmh: Double,
    /** WMO 天气代码，见 [WeatherApi.describe] */
    val code: Int,
    /** 今天起 3 天 */
    val days: List<Day>,
) {
    data class Day(val date: String, val code: Int, val hiC: Double, val loC: Double, val rainPct: Int)
    /** 已过 [t] 的一句：「多云」「小雨」… */
    val text get() = WeatherApi.describe(code)
    val kind get() = WeatherApi.kind(code)
    val stale get() = System.currentTimeMillis() - fetchedAt > WeatherApi.TTL
}

/** [WeatherApi.current] 失败的两种原因，界面按类型写文案 */
class NoCityException : Exception("no city chosen")
class WeatherNetException(msg: String) : Exception(msg)
/** [WeatherApi.locate] 失败的两种原因 */
class NoLocationPermission : Exception("no location permission")
class LocationUnavailable(msg: String) : Exception(msg)

object WeatherApi {
    const val TTL = 30 * 60_000L

    /** 画图标用的大类 —— 图标按项目规矩自己画路径（LineIcons），不用 emoji */
    enum class Kind { CLEAR, PARTLY, CLOUDY, FOG, RAIN, SNOW, THUNDER }

    private fun p(ctx: Context) = ctx.getSharedPreferences("yxi.yunxi", Context.MODE_PRIVATE)
    private var mem: Weather? = null

    fun city(ctx: Context): City? =
        p(ctx).getString("city", null)?.let { runCatching { cityOf(JSONObject(it)) }.getOrNull() }

    fun setCity(ctx: Context, c: City?) {
        p(ctx).edit().apply {
            if (c == null) remove("city") else putString("city", json(c).toString())
            remove("weather")
        }.apply()
        mem = null
    }

    /** 上次取到的（可能过期）—— 先画它，再去刷新 */
    fun cached(ctx: Context): Weather? =
        mem ?: p(ctx).getString("weather", null)?.let { runCatching { weatherOf(JSONObject(it)) }.getOrNull() }?.also { mem = it }

    // ── 搜城市 ──

    /**
     * 地理编码：中文城市名直接搜（`language=zh`），最多 8 条，**按人口倒序**。失败抛异常。
     *
     * ⚠️ Open-Meteo 的规则：输入只有 **2 个字符时只做精确匹配**。GeoNames 里不少城市的中文名带「市」
     *   （「长沙市」「石家庄市」），两个字「长沙」精确匹配不到湖南长沙，只匹配到各省真叫「长沙」的镇和村，
     *   还按它自己的顺序排 —— 重庆那个 4.7 万人的镇排第一（老板 1.1.13 上的截图，2026-09-06）。
     *   所以：不带行政后缀的短中文名**再补搜一次「X市」**，两路合并、按坐标去重、按人口排，大城市永远在前。
     *   （北京 / 上海 / 武汉这类中文名本来就不带「市」的，第一路就命中，补搜只是多几条同名小地方，排在后面。）
     */
    suspend fun search(name: String): List<City> = withContext(Dispatchers.IO) {
        val q = name.trim()
        if (q.length < 2) return@withContext emptyList()
        val variants = linkedSetOf(q)
        if (q.length <= 3 && q.all { it in '一'..'鿿' } && q.last() !in "市县区镇乡") variants += q + "市"
        val all = variants.flatMap { v ->
            // 主查询失败要报错；补搜失败（少见）就当没有，别把主结果一起丢了
            runCatching { query(v) }.getOrElse { if (v == q) throw it else emptyList() }
        }
        all.distinctBy { String.format(java.util.Locale.US, "%.2f,%.2f", it.lat, it.lon) }
            .sortedByDescending { it.population }
            .take(8)
    }

    private fun query(name: String): List<City> {
        val q = URLEncoder.encode(name, "UTF-8")
        val o = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=10&language=zh&format=json"))
        val a = o.optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).map { i ->
            val r = a.getJSONObject(i)
            City(
                r.optString("name"), r.optString("admin1"), r.optString("country"),
                r.getDouble("latitude"), r.getDouble("longitude"), r.optString("timezone", "auto"),
                r.optLong("population", 0),
            )
        }
    }

    // ── 用手机定位 ──

    /** 有没有定位权限（粗略的就够）。没有 → 页面方申请 `ACCESS_COARSE_LOCATION`，拿到再调 [locate] */
    fun hasLocationPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * 用手机定位找城市：粗略定位（城市级就够）→ 反向地理编码成 [City]。**不落盘**，页面方拿到后自己 [setCity]。
     * 失败：[NoLocationPermission]（还没授权）/ [LocationUnavailable]（定位关着 / 10 秒没定到 / 提供者一个都没有）。
     * 反查失败**不算失败**：Open-Meteo 查天气只要经纬度，城市名只是显示用 —— 反查不到就叫「当前位置」。
     */
    suspend fun locate(ctx: Context): Result<City> {
        if (!hasLocationPermission(ctx)) return Result.failure(NoLocationPermission())
        return try {
            val loc = lastOrCurrentLocation(ctx) ?: return Result.failure(LocationUnavailable("no fix"))
            Result.success(reverse(loc.latitude, loc.longitude))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(LocationUnavailable(e.message ?: e.javaClass.simpleName))
        }
    }

    private const val TAG = "Yunxi"

    /**
     * 定位策略（城市级够用，宁可旧一点也别失败）：
     * ① 所有提供者的 lastKnown（含 passive），10 分钟内的直接用；
     * ② 没有就挨个提供者现定一次，总预算 10 秒；
     * ③ 还没有就退回最近 24 小时内的 lastKnown。
     * ⚠️ 12 以下拿粗略权限碰 GPS 会 SecurityException；12 起系统把 GPS 定位模糊化后照样给粗略权限的 App ——
     *    模拟器上只有 GPS（`adb emu geo fix`），漏了它就永远定不到（Entertainment 的 E2E 撞到的）。
     * 每步打 `Yunxi` 日志，E2E 时 `adb logcat -s Yunxi` 就能看卡在哪。
     */
    @SuppressLint("MissingPermission")
    private suspend fun lastOrCurrentLocation(ctx: Context): Location? {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val fine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val s12 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val providers = listOfNotNull(
            if (s12) LocationManager.FUSED_PROVIDER else null,
            LocationManager.NETWORK_PROVIDER,
            if (fine || s12) LocationManager.GPS_PROVIDER else null,
        ).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val now = System.currentTimeMillis()
        val known = (providers + LocationManager.PASSIVE_PROVIDER).distinct()
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        Log.d(TAG, "locate: providers=$providers known=" + known.map { "${it.provider}@${(now - it.time) / 1000}s" })
        known.filter { now - it.time < 10 * 60_000 }.maxByOrNull { it.time }?.let { return it }
        // ⚠️ 每个提供者单独限时：fused 要是一直不回，会把总预算吃光，排在后面的 GPS 永远轮不到 ——
        //    模拟器上就只有 GPS（Entertainment 第二次 E2E 还是 LocationUnavailable 的原因）。
        withTimeoutOrNull(12_000) {
            for (pv in providers) {
                val fix = withTimeoutOrNull(4_000) {
                    runCatching { requestOnce(ctx, lm, pv) }.onFailure { Log.w(TAG, "locate: requestOnce($pv) threw", it) }.getOrNull()
                }
                Log.d(TAG, "locate: requestOnce($pv) → ${fix != null}")
                if (fix != null) return@withTimeoutOrNull fix
            }
            null
        }?.let { return it }
        return known.filter { now - it.time < 24 * 3600_000 }.maxByOrNull { it.time }
            .also { Log.d(TAG, "locate: fallback stale=${it?.provider}") }
    }

    /** 现定一次。30+ 走 getCurrentLocation（回调在主线程 executor，不要求调用线程有 Looper）；老系统 requestSingleUpdate。 */
    @SuppressLint("MissingPermission")
    private suspend fun requestOnce(ctx: Context, lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val cs = CancellationSignal()
                cont.invokeOnCancellation { cs.cancel() }
                lm.getCurrentLocation(provider, cs, ctx.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc) }
            } else {
                val l = object : LocationListener {
                    override fun onLocationChanged(location: Location) { if (cont.isActive) cont.resume(location) }
                    @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) { if (cont.isActive) cont.resume(null) }
                }
                cont.invokeOnCancellation { lm.removeUpdates(l) }   // 先挂再注册：removeUpdates 对没注册的监听是安全的空操作
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, l, Looper.getMainLooper())
            }
        }

    /**
     * 经纬度 → 城市。BigDataCloud 的 `reverse-geocode-client`：免 key、专给客户端直连用、支持中文。
     * 国名不填 —— 人就在这儿，写「中华人民共和国」纯占地方；省名去掉「省」字跟 Open-Meteo 的写法对齐。
     */
    private suspend fun reverse(lat: Double, lon: Double): City = withContext(Dispatchers.IO) {
        val fallback = City(t("当前位置"), "", "", lat, lon, "auto")
        runCatching {
            val o = JSONObject(get("https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=$lat&longitude=$lon&localityLanguage=zh"))
            val name = o.optString("city").ifBlank { o.optString("locality") }.ifBlank { return@runCatching fallback }
            val admin = o.optString("principalSubdivision").removeSuffix("省").removeSuffix("市")
            City(name, if (admin == name) "" else admin, "", lat, lon, "auto")
        }.getOrDefault(fallback)
    }

    // ── 取天气 ──

    /**
     * 当前天气。30 分钟内直接给缓存；没选城市 → [NoCityException]；取不到 → [WeatherNetException]。
     * ⚠️ 取消不算失败（CancellationException 原样抛回去）。
     */
    suspend fun current(ctx: Context, force: Boolean = false): Result<Weather> {
        val c = city(ctx) ?: return Result.failure(NoCityException())
        cached(ctx)?.let { hit ->
            if (!force && !hit.stale && hit.city.lat == c.lat && hit.city.lon == c.lon) return Result.success(hit)
        }
        return try {
            val w = fetch(c)
            p(ctx).edit().putString("weather", json(w).toString()).apply(); mem = w
            Result.success(w)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(WeatherNetException(e.message ?: e.javaClass.simpleName))
        }
    }

    suspend fun fetch(c: City): Weather = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${c.lat}&longitude=${c.lon}" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=3"
        val o = JSONObject(get(url))
        val cur = o.getJSONObject("current")
        val d = o.getJSONObject("daily")
        val dates = d.getJSONArray("time")
        val codes = d.getJSONArray("weather_code"); val hi = d.getJSONArray("temperature_2m_max")
        val lo = d.getJSONArray("temperature_2m_min"); val rain = d.optJSONArray("precipitation_probability_max")
        Weather(
            c, System.currentTimeMillis(),
            cur.getDouble("temperature_2m"), cur.getDouble("apparent_temperature"),
            cur.getInt("relative_humidity_2m"), cur.getDouble("wind_speed_10m"), cur.getInt("weather_code"),
            // ⚠️ 温度 / 代码用 get 不用 opt：opt 遇到 JSON null 给 NaN / 0，界面会画出「NaN°」或把 null 当成「晴」。
            //    宁可整次失败（外面兜成 WeatherNetException），下次再取。降水概率可以是 null，那个才用 opt。
            (0 until dates.length()).map { i ->
                Weather.Day(dates.getString(i), codes.getInt(i), hi.getDouble(i), lo.getDouble(i), rain?.optInt(i) ?: 0)
            },
        )
    }

    /** WMO 代码 → 一句（已过 [t]） */
    fun describe(code: Int): String = t(
        when (code) {
            0 -> "晴"; 1 -> "晴间多云"; 2 -> "多云"; 3 -> "阴"
            45, 48 -> "雾"
            51, 53, 55 -> "毛毛雨"; 56, 57 -> "冻毛毛雨"
            61 -> "小雨"; 63 -> "中雨"; 65 -> "大雨"; 66, 67 -> "冻雨"
            71 -> "小雪"; 73 -> "中雪"; 75 -> "大雪"; 77 -> "雪粒"
            80 -> "小阵雨"; 81 -> "阵雨"; 82 -> "强阵雨"; 85 -> "阵雪"; 86 -> "强阵雪"
            95 -> "雷雨"; 96, 99 -> "雷雨伴冰雹"
            else -> "天气未知"
        },
    )

    fun kind(code: Int): Kind = when (code) {
        0 -> Kind.CLEAR; 1, 2 -> Kind.PARTLY; 3 -> Kind.CLOUDY
        45, 48 -> Kind.FOG
        in 51..67, in 80..82 -> Kind.RAIN
        in 71..77, 85, 86 -> Kind.SNOW
        in 95..99 -> Kind.THUNDER
        else -> Kind.CLOUDY
    }

    // ── HTTP / JSON ──

    private fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 10_000; requestMethod = "GET"
            setRequestProperty("User-Agent", "Yxi-Android")
        }
        return try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun json(c: City) = JSONObject().apply {
        put("name", c.name); put("admin", c.admin); put("country", c.country)
        put("lat", c.lat); put("lon", c.lon); put("tz", c.tz); put("population", c.population)
    }
    private fun cityOf(o: JSONObject) =
        City(o.optString("name"), o.optString("admin"), o.optString("country"), o.getDouble("lat"), o.getDouble("lon"), o.optString("tz", "auto"), o.optLong("population", 0))

    private fun json(w: Weather) = JSONObject().apply {
        put("city", json(w.city)); put("fetchedAt", w.fetchedAt)
        put("tempC", w.tempC); put("feelsC", w.feelsC); put("humidity", w.humidity); put("windKmh", w.windKmh); put("code", w.code)
        put("days", JSONArray().apply {
            w.days.forEach { d ->
                put(JSONObject().apply { put("date", d.date); put("code", d.code); put("hiC", d.hiC); put("loC", d.loC); put("rainPct", d.rainPct) })
            }
        })
    }
    private fun weatherOf(o: JSONObject): Weather {
        val a = o.optJSONArray("days") ?: JSONArray()
        return Weather(
            cityOf(o.getJSONObject("city")), o.getLong("fetchedAt"),
            o.getDouble("tempC"), o.getDouble("feelsC"), o.getInt("humidity"), o.getDouble("windKmh"), o.getInt("code"),
            (0 until a.length()).map { i ->
                val d = a.getJSONObject(i)
                Weather.Day(d.optString("date"), d.optInt("code"), d.optDouble("hiC"), d.optDouble("loC"), d.optInt("rainPct"))
            },
        )
    }
}
