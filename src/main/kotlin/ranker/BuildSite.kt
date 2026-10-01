package ranker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/** An independent score plugin (advisory column, never summed into Total). */
@Serializable
data class IndepCol(val name: String, val label: String, val scale: Int, val plugin: String)

@Serializable
data class SiteData(
    val generatedAt: String,
    val independentColumns: List<IndepCol>,
    val totalColumns: List<String>,
    val scoredColumns: List<String>,
    val activeColumns: List<String>,
    val rowCount: Int,
    val rows: List<ScoredCar>,
    /** Non-null when a LIVE fetch (Server.kt's /api/pool) didn't get the complete result -- see
     * FetchResult/fetchAllListingsLive() in Ingest.kt. Always null for the offline batch snapshot, which
     * reads from a static fixture file, not a live paginated fetch. The page must surface this to the user
     * rather than silently presenting a partial pool as complete. */
    val fetchWarning: String? = null
)

/** Registered independent plugins. Add a row here (+ a plugins/<name>.md) to surface a new advisory column. */
val INDEPENDENT_PLUGINS = listOf(
    IndepCol("ROI", "ROI (Independent score)", 10, "plugins/roi.md")
)

/** Shared JSON codec for site data — used by both the offline batch job (this file) and the live server (Server.kt). */
val siteJson = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true; encodeDefaults = true }

/**
 * Score `listings` and build the SiteData payload, shipping the FULL eligible pool (not just top 50) —
 * the page owns the top-50 cut so it can re-derive it after a brand/model/source filter change without
 * a re-fetch. Shared by the offline batch job (main(), below) and the live server's /api/pool endpoint.
 */
fun buildSiteData(listings: List<Listing>, ref: Reference, fetchWarning: String? = null): SiteData {
    val table = buildTable(listings, ref, DEFAULT_ACTIVE_COLUMNS.toSet(), maxRows = listings.size)
    return SiteData(
        generatedAt = Instant.now().toString(),
        independentColumns = INDEPENDENT_PLUGINS,
        totalColumns = TOTAL_COLUMNS,
        scoredColumns = SCORED_COLUMNS,
        activeColumns = DEFAULT_ACTIVE_COLUMNS,
        rowCount = table.size,
        rows = table,
        fetchWarning = fetchWarning
    )
}

/**
 * Usage: reads data/listings.json (array of Listing) + data/reference.json, writes site/data.json.
 * This is the offline snapshot generator. The GitHub Pages table (site/index.html) renders/sorts that
 * JSON on load — no fetching or math in the page itself. For a LIVE, on-demand re-fetch+rescore (the
 * page's "Update" button), see Server.kt / `./gradlew runServer`.
 */
fun main(args: Array<String>) {
    val listingsPath = args.getOrNull(0) ?: "data/listings.json"
    val refPath = args.getOrNull(1) ?: "data/reference.json"
    val outPath = args.getOrNull(2) ?: "site/data.json"

    val ref = Ref.load(refPath)
    val listings: List<Listing> =
        siteJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Listing.serializer()),
            File(listingsPath).readText())

    val out = buildSiteData(listings, ref)
    File(outPath).parentFile?.mkdirs()
    File(outPath).writeText(siteJson.encodeToString(SiteData.serializer(), out))
    println("Wrote ${out.rows.size} rows to $outPath (from ${listings.size} listings, ${listings.count { passesBaseFilter(it, ref) }} eligible).")
}
