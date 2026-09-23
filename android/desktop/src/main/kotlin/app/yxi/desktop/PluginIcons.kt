package app.yxi.desktop

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/** Only catalog publisher assets and icons declared by the publisher website are used. */
internal class PluginIconLoader(private val cache: File, private val now: () -> Long = System::currentTimeMillis,
    private val fetch: (String) -> ByteArray = ::download) {
    private val slots = Semaphore(4)
    private val failedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    suspend fun load(plugin: NativePlugin, dark: Boolean): ByteArray? = slots.withPermit { withContext(Dispatchers.IO) {
        val urls = listOfNotNull(if (dark) plugin.iconUrlDark else null, plugin.iconUrl, plugin.composerIconUrl).distinct()
        val key = MessageDigest.getInstance("SHA-256").digest(("viewport-v2:" + urls.joinToString() + plugin.name + plugin.websiteUrl + dark).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val saved = File(cache, "$key.png")
        if ((failedUntil[key] ?: 0L) > now()) return@withContext null
        fun <T> attempt(block: () -> T): T? = try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        if (saved.isFile && saved.length() <= 512 * 1024 && System.currentTimeMillis() - saved.lastModified() < 7L * 86400000) {
            runCatching { render(saved.readBytes()) }.getOrNull()?.let { return@withContext it }
        }
        fun store(bytes: ByteArray): ByteArray {
            runCatching {
                cache.mkdirs(); saved.writeBytes(bytes)
                var size = 0L
                cache.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}\\.png")) }.sortedByDescending { it.lastModified() }.forEach {
                    size += it.length(); if (size > 32 * 1024 * 1024) it.delete()
                }
            }
            return bytes
        }
        val bundled = when {
            plugin.name == "canva" && hostMatches(plugin.websiteUrl, "canva.com") -> "canva.ico"
            plugin.name == "gmail" && hostMatches(plugin.websiteUrl, "google.com") -> "gmail.ico"
            plugin.name == "github" && hostMatches(plugin.websiteUrl, "github.com") -> "github-fluidicon.png"
            plugin.name == "slack" && hostMatches(plugin.websiteUrl, "slack.com") -> "slack-nav-logo.svg"
            plugin.name == "dropbox" && hostMatches(plugin.websiteUrl, "dropbox.com") -> "dropbox-logo-nav.svg"
            plugin.name in setOf("google-drive", "google_drive", "googledrive") && hostMatches(plugin.websiteUrl, "google.com") -> "drive-productlogos-192.svg"
            plugin.name == "notion" && (hostMatches(plugin.websiteUrl, "notion.so") || hostMatches(plugin.websiteUrl, "notion.com")) -> "notion-logo-ios.png"
            plugin.name == "linear" && hostMatches(plugin.websiteUrl, "linear.app") -> "linear-apple-touch-icon.png"
            plugin.name == "figma" && hostMatches(plugin.websiteUrl, "figma.com") -> "figma-icon-192.png"
            plugin.name == "stripe" && hostMatches(plugin.websiteUrl, "stripe.com") -> "stripe-favicon-180.png"
            plugin.name == "vercel" && hostMatches(plugin.websiteUrl, "vercel.com") -> "vercel-apple-touch-icon-180.png"
            plugin.name == "supabase" && hostMatches(plugin.websiteUrl, "supabase.com") -> "supabase-favicon-196.png"
            plugin.name in setOf("google-calendar", "google_calendar", "googlecalendar") && hostMatches(plugin.websiteUrl, "google.com") -> "gcalendar-play-icon.png"
            plugin.name in setOf("outlook", "outlook-email", "outlook-calendar", "outlook_email", "outlook_calendar") &&
                listOf("microsoft.com", "outlook.com", "office.com", "cloud.microsoft").any { hostMatches(plugin.websiteUrl, it) } -> "outlook-m365cloud.png"
            else -> null
        }
        if (bundled != null) javaClass.getResourceAsStream("/app/yxi/desktop/plugin-icons/$bundled")?.use { source ->
            runCatching { render(source.readBytes()) }.getOrNull()?.let { return@withContext store(it) }
        }
        for (url in urls) {
            currentCoroutineContext().ensureActive()
            attempt { render(fetch(checkedUrl(url).toString())) }?.let { return@withContext store(it) }
        }
        val website = runCatching { checkedUrl(plugin.websiteUrl ?: return@withContext null) }.getOrNull() ?: return@withContext null
        val declared = attempt { websiteIcons(website, fetch(website.toString()).toString(Charsets.UTF_8)) }.orEmpty()
        for (url in (declared + website.resolve("/favicon.ico").toString()).distinct().take(5)) {
            currentCoroutineContext().ensureActive()
            attempt { render(fetch(checkedUrl(url).toString())) }?.let { return@withContext store(it) }
        }
        currentCoroutineContext().ensureActive()
        if (failedUntil.size >= 4096) failedUntil.keys.firstOrNull()?.let { failedUntil.remove(it) }
        failedUntil[key] = now() + 60_000L
        null
    } }
    companion object {
        internal fun hostMatches(url: String?, domain: String): Boolean {
            val host = runCatching { checkedUrl(url ?: return false).host.lowercase() }.getOrNull() ?: return false
            return host == domain || host.endsWith(".$domain")
        }
        internal fun checkedUrl(value: String): URI = URI(value).also {
            require(it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null)
            require(it.host.lowercase() !in setOf("localhost", "127.0.0.1", "[::1]"))
        }
        internal fun websiteIcons(base: URI, html: String): List<String> {
            fun attribute(tag: String, key: String) = Regex("\\b$key\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE)
                .find(tag)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
            return Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).mapNotNull { match ->
                val rel = attribute(match.value, "rel").orEmpty().lowercase().split(Regex("\\s+"))
                if (rel.none { it == "icon" || it.startsWith("apple-touch-icon") }) return@mapNotNull null
                val href = attribute(match.value, "href")?.replace("&amp;", "&") ?: return@mapNotNull null
                runCatching { checkedUrl(base.resolve(href).toString()).toString() }.getOrNull()?.let { url ->
                    (if (rel.any { it.startsWith("apple-touch-icon") }) 0 else 1) to url
                }
            }.sortedBy { it.first }.map { it.second }.distinct().take(4).toList()
        }
        private fun download(url: String): ByteArray {
            var uri = checkedUrl(url)
            repeat(4) {
                val connection = uri.toURL().openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false; connection.connectTimeout = 4000; connection.readTimeout = 4000
                connection.setRequestProperty("User-Agent", "Yxi/1.0 (plugin icon loader)")
                try {
                    val status = connection.responseCode
                    if (status in setOf(301, 302, 303, 307, 308)) {
                        uri = checkedUrl(uri.resolve(connection.getHeaderField("Location") ?: error("Missing redirect")).toString())
                    } else {
                        check(status == 200) { "Icon HTTP $status" }
                        val bytes = connection.inputStream.use { stream -> stream.readNBytes(512 * 1024 + 1) }
                        require(bytes.size <= 512 * 1024) { "Icon too large" }
                        return bytes
                    }
                } finally { connection.disconnect() }
            }
            error("Too many icon redirects")
        }
        internal fun render(bytes: ByteArray): ByteArray {
            require(bytes.size <= 512 * 1024)
            val prefix = bytes.take(1024).toByteArray().toString(Charsets.UTF_8)
            return Surface.makeRasterN32Premul(64, 64).use { surface ->
                surface.canvas.clear(0)
                if (prefix.contains("<svg", ignoreCase = true)) {
                    val svg = bytes.toString(Charsets.UTF_8)
                    require(!svg.contains("<!DOCTYPE", true) && !svg.contains("<!ENTITY", true))
                    require(Regex("(?:\\b|:)href\\s*=\\s*[\"']([^\"']+)", RegexOption.IGNORE_CASE).findAll(svg)
                        .all { it.groupValues[1].startsWith('#') || it.groupValues[1].startsWith("data:image/") })
                    Data.makeFromBytes(bytes).use { data -> SVGDOM(data).use { dom ->
                        require(dom.root != null)
                        val tag = Regex("<svg\\b[^>]*>", RegexOption.IGNORE_CASE).find(svg)?.value ?: error("Missing SVG root")
                        fun attribute(name: String) = Regex("\\s$name\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
                        fun length(name: String) = attribute(name)?.trim()?.removeSuffix("px")?.toFloatOrNull()
                        val viewBox = attribute("viewBox")?.trim()?.split(Regex("[\\s,]+"))?.map { it.toFloatOrNull() }
                        val width = length("width") ?: viewBox?.takeIf { it.size == 4 }?.get(2) ?: 64f
                        val height = length("height") ?: viewBox?.takeIf { it.size == 4 }?.get(3) ?: 64f
                        require(width.isFinite() && height.isFinite() && width in 1f..2048f && height in 1f..2048f)
                        val scale = minOf(64f / width, 64f / height)
                        surface.canvas.translate((64 - width * scale) / 2, (64 - height * scale) / 2)
                        surface.canvas.scale(scale, scale)
                        dom.setContainerSize(width, height); dom.render(surface.canvas)
                    } }
                } else Image.makeFromEncoded(bytes).use { image ->
                    require(image.width in 1..2048 && image.height in 1..2048)
                    val scale = minOf(64f / image.width, 64f / image.height)
                    surface.canvas.translate((64 - image.width * scale) / 2, (64 - image.height * scale) / 2)
                    surface.canvas.scale(scale, scale); surface.canvas.drawImage(image, 0f, 0f)
                }
                surface.makeImageSnapshot().use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
            }
        }
    }
}

internal object PluginIcons { val loader by lazy { PluginIconLoader(File(Store.dir, "plugin-icons")) } }
