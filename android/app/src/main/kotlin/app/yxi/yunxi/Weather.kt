package app.yxi.yunxi

import android.content.Context
import app.yxi.ui.t
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 云曦小管家 · 天气。数据源 **Open-Meteo**（open-meteo.com）：免 key、免注册、CC BY 4.0（卡片角落写一行「数据 Open-Meteo」）。
 *
 * - **手动选城市，不申请定位** —— 一个 SSH 控制台要定位权限显得可疑（PRD §4）。城市用它的地理编码接口搜，中文直接搜得到。
 * - 缓存 30 分钟（[Weather.stale]）；[current] 先给缓存、过期才拉。换城市清缓存。
 * - 已从两台国内机器验证直连可达（2026-09-06：老板 Windows 1.06s、Mac mini 绍兴移动 0.97s）。
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

    /** 地理编码：中文城市名可直接搜（`language=zh`），最多 8 条。失败抛异常。 */
    suspend fun search(name: String): List<City> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(name.trim(), "UTF-8")
        val o = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=8&language=zh&format=json"))
        val a = o.optJSONArray("results") ?: return@withContext emptyList()
        (0 until a.length()).map { i ->
            val r = a.getJSONObject(i)
            City(r.optString("name"), r.optString("admin1"), r.optString("country"), r.getDouble("latitude"), r.getDouble("longitude"), r.optString("timezone", "auto"))
        }
    }

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
        put("lat", c.lat); put("lon", c.lon); put("tz", c.tz)
    }
    private fun cityOf(o: JSONObject) =
        City(o.optString("name"), o.optString("admin"), o.optString("country"), o.getDouble("lat"), o.getDouble("lon"), o.optString("tz", "auto"))

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
