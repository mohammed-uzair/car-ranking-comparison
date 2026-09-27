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
    val rows: List<ScoredCar>
)

/** Registered independent plugins. Add a row here (+ a plugins/<name>.md) to surface a new advisory column. */
val INDEPENDENT_PLUGINS = listOf(
    IndepCol("ROI", "ROI (Independent score)", 10, "plugins/roi.md")
)

/**
 * Usage: reads data/listings.json (array of Listing) + data/reference.json, writes site/data.json.
 * The GitHub Pages table (site/index.html) renders and sorts that JSON — it does no fetching or math.
 */
fun main(args: Array<String>) {
    val listingsPath = args.getOrNull(0) ?: "data/listings.json"
    val refPath = args.getOrNull(1) ?: "data/reference.json"
    val outPath = args.getOrNull(2) ?: "site/data.json"

    val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true; encodeDefaults = true }
    val ref = Ref.load(refPath)
    val listings: List<Listing> =
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Listing.serializer()),
            File(listingsPath).readText())

    val active = DEFAULT_ACTIVE_COLUMNS.toSet()
    // Ship the FULL eligible pool, not just the top 50: the page owns the top-50 cut so it can re-derive it
    // after a brand/model/source filter change (the drawer's "Update" button) without a real re-fetch.
    val table = buildTable(listings, ref, active, maxRows = listings.size)

    val out = SiteData(
        generatedAt = Instant.now().toString(),
        independentColumns = INDEPENDENT_PLUGINS,
        totalColumns = TOTAL_COLUMNS,
        scoredColumns = SCORED_COLUMNS,
        activeColumns = DEFAULT_ACTIVE_COLUMNS,
        rowCount = table.size,
        rows = table
    )
    File(outPath).parentFile?.mkdirs()
    File(outPath).writeText(json.encodeToString(SiteData.serializer(), out))
    println("Wrote ${table.size} rows to $outPath (from ${listings.size} listings, ${listings.count { passesBaseFilter(it, ref) }} eligible).")
}
