package ranker

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.stream.Collectors

/**
 * The local, on-your-own-machine Kotlin server. GitHub only stores the code; this is what actually
 * "runs" when the page's Update button is clicked. Built on the JDK's own com.sun.net.httpserver.HttpServer
 * -- no new dependency (no Ktor, no framework) -- since a browser button click can only ever trigger local
 * program execution via a network call to something already listening; this is the smallest thing that can
 * listen.
 *
 * Routes:
 *   GET /            -> serves site/index.html (same origin as the API below, so no CORS question ever
 *                        arises for the page<->API leg)
 *   GET /api/pool    -> live fetch (Autohero + AutoScout24, via Ingest.kt) -> the SAME scoring pipeline
 *                        (Scoring.kt/BuildSite.kt) used for the offline snapshot -> fresh SiteData JSON
 *   GET /api/lengths -> ?urls=<comma-separated listing URLs>, Autohero only. One detail-page fetch per URL
 *                        (body style + car length aren't in the bulk search API -- see Ingest.kt), so the
 *                        page only calls this for an already-narrowed candidate set, never the full pool.
 *
 * Run: ./gradlew runServer   (defaults to port 8081; override with the PORT env var)
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8081
    val ref = Ref.load("data/reference.json")
    val server = HttpServer.create(InetSocketAddress(port), 0)

    server.createContext("/api/pool") { exchange ->
        try {
            println("Live fetch starting (Autohero + AutoScout24)...")
            val started = System.currentTimeMillis()
            val fetched = fetchAllListingsLive()
            val out = buildSiteData(fetched.listings, ref, fetchWarning = fetched.note)
            val elapsed = (System.currentTimeMillis() - started) / 1000.0
            val completeness = if (fetched.complete) "complete" else "INCOMPLETE: ${fetched.note}"
            println("Live fetch done in ${elapsed}s: ${fetched.listings.size} listings ($completeness) -> ${out.rows.size} eligible")
            respondJson(exchange, 200, siteJson.encodeToString(SiteData.serializer(), out))
        } catch (e: Exception) {
            System.err.println("Live fetch failed: ${e}")
            respondJson(exchange, 500, "{\"error\": ${jsonString(e.message ?: e.toString())}}")
        }
    }

    server.createContext("/api/lengths") { exchange ->
        try {
            val query = exchange.requestURI.rawQuery ?: ""
            val urlsParam = query.split("&")
                .map { it.split("=", limit = 2) }
                .firstOrNull { it[0] == "urls" }?.getOrNull(1) ?: ""
            val urls = URLDecoder.decode(urlsParam, "UTF-8").split(",").map { it.trim() }.filter { it.isNotBlank() }
            // Only Autohero exposes body/length on its detail page (see Ingest.kt); a non-Autohero URL just
            // comes back absent from the result map, which the page renders as N/A. One HTTP request per URL,
            // so this runs them concurrently (common ForkJoinPool) rather than sequentially -- serially, a
            // realistic-sized candidate set (e.g. 30+ Toyota/Autohero rows) would take tens of seconds.
            val entries = urls.parallelStream().map(::fetchDetailOrNull).filter { it != null }
                .map { it!! }.collect(Collectors.toList())
            respondJson(exchange, 200, buildJsonObjectString(entries))
        } catch (e: Exception) {
            System.err.println("Length fetch failed: ${e}")
            respondJson(exchange, 500, "{\"error\": ${jsonString(e.message ?: e.toString())}}")
        }
    }

    server.createContext("/") { exchange ->
        val path = if (exchange.requestURI.path == "/") "/index.html" else exchange.requestURI.path
        val file = File("site" + path)
        if (file.exists() && file.isFile) {
            val bytes = file.readBytes()
            val contentType = when {
                path.endsWith(".html") -> "text/html; charset=utf-8"
                path.endsWith(".json") -> "application/json; charset=utf-8"
                path.endsWith(".js") -> "application/javascript; charset=utf-8"
                path.endsWith(".css") -> "text/css; charset=utf-8"
                else -> "application/octet-stream"
            }
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } else {
            val msg = "404 Not Found".toByteArray()
            exchange.sendResponseHeaders(404, msg.size.toLong())
            exchange.responseBody.use { it.write(msg) }
        }
    }

    server.executor = null   // default (single-threaded) executor -- a personal local tool, one user at a time
    server.start()
    println("Local server running: http://localhost:$port  (Ctrl+C to stop)")
    println("  GET /          -> the page")
    println("  GET /api/pool  -> live re-fetch + re-score (what the Update button calls)")
    println("  GET /api/lengths?urls=... -> Autohero body/length for a narrowed candidate set")
}

private fun fetchDetailOrNull(url: String): Pair<String, AutoheroDetail>? {
    if (!url.contains("autohero.com")) return null
    val d = fetchAutoheroDetail(url) ?: return null
    return url to d
}

private fun jsonString(s: String): String {
    val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
    return "\"$escaped\""
}

private fun buildJsonObjectString(entries: List<Pair<String, AutoheroDetail>>): String =
    entries.joinToString(",", prefix = "{", postfix = "}") { (url, d) ->
        val bodyType = d.bodyType?.let { jsonString(it) } ?: "null"
        val lengthMm = d.lengthMm?.toString() ?: "null"
        "${jsonString(url)}: {\"bodyType\": $bodyType, \"lengthMm\": $lengthMm}"
    }

private fun respondJson(exchange: HttpExchange, status: Int, body: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}
