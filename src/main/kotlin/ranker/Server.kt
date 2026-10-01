package ranker

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

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
 *                        (Scoring.kt/BuildSite.kt) used for the offline snapshot -> fresh SiteData JSON.
 *                        ?bodyTypes=<comma-separated codes> narrows the live Autohero query itself (a real
 *                        server-side filter -- see Ingest.kt's AUTOHERO_BODY_TYPE_CODES).
 *
 * Run: ./gradlew runServer   (defaults to port 8081; override with the PORT env var)
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8081
    val ref = Ref.load("data/reference.json")
    val server = HttpServer.create(InetSocketAddress(port), 0)

    server.createContext("/api/pool") { exchange ->
        try {
            val bodyTypesParam = (exchange.requestURI.rawQuery ?: "").split("&")
                .map { it.split("=", limit = 2) }
                .firstOrNull { it[0] == "bodyTypes" }?.getOrNull(1) ?: ""
            val bodyTypes = bodyTypesParam.split(",").mapNotNull { it.trim().toIntOrNull() }
            println("Live fetch starting (Autohero + AutoScout24)${if (bodyTypes.isNotEmpty()) ", bodyTypes=$bodyTypes" else ""}...")
            val started = System.currentTimeMillis()
            val fetched = fetchAllListingsLive(bodyTypes)
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
    println("                    ?bodyTypes=1023,1035,... narrows the live Autohero query itself")
}

private fun jsonString(s: String): String {
    val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
    return "\"$escaped\""
}

private fun respondJson(exchange: HttpExchange, status: Int, body: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}
