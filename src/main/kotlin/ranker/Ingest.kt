package ranker

import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * Live ingestion, in Kotlin — the single business-logic language for this project. Both fetchers return
 * plain List<Listing>; all scoring (dedup, base-filter gate, per-column scores, ranking) stays in
 * Scoring.kt/BuildSite.kt, unchanged and fully reused. No other language is involved anywhere in this repo.
 */

private val UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
// followRedirects(NORMAL) matters here: Autohero's own listing URLs (carUrlTitle + id) 301-redirect to their
// canonical form, and HttpClient's default policy (NEVER) would otherwise silently return the redirect
// response instead of the page -- fetchAutoheroDetail() depends on this to reach the actual detail page.
private val client: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build()
private val ingestJson = Json { ignoreUnknownKeys = true; isLenient = true }

val ALLOWED_MAKES_LIST = listOf("Audi", "BMW", "Mercedes-Benz", "Porsche", "Honda", "Toyota", "Hyundai")

// ---------- Autohero ----------

private const val AUTOHERO_ENDPOINT = "https://www.autohero.com/v1/retail-customer-gateway/graphql"
private const val AUTOHERO_QUERY =
    "query searchAdV9AdsV2(\$search: EsSearchRequestProjectionInput!, \$tradeInId: UUID) { searchAdV9AdsV2(search: \$search, tradeInId: \$tradeInId) }"

private fun autoheroFilter(): JsonObject = buildJsonObject {
    put("op", "and")
    putJsonArray("value") {
        addJsonObject { put("field", "countryCode"); put("op", "eq"); put("value", "DE") }
        addJsonObject {
            put("op", "or")
            putJsonArray("value") {
                ALLOWED_MAKES_LIST.forEach { mk ->
                    addJsonObject { put("field", "manufacturer"); put("op", "eq"); put("value", mk) }
                }
            }
        }
        addJsonObject { put("field", "firstRegistrationYear"); put("op", "gte"); put("value", 2018) }
        addJsonObject { put("field", "offerPrice.amountMinorUnits"); put("op", "gte"); put("value", 1000000) }
        addJsonObject { put("field", "offerPrice.amountMinorUnits"); put("op", "lte"); put("value", 2000000) }
        addJsonObject { put("field", "numberOfAccidents"); put("op", "eq"); put("value", 0) }
        addJsonObject { put("field", "carPreownerCount"); put("op", "lte"); put("value", 3) }
        addJsonObject { put("field", "doorCount"); put("op", "gte"); put("value", 4) }
        addJsonObject {
            put("op", "or")
            putJsonArray("value") {
                listOf(1039, 1041, 1046).forEach { ft ->
                    addJsonObject { put("field", "fuelType"); put("op", "eq"); put("value", ft) }
                }
            }
        }
    }
}

private fun autoheroBody(offset: Int, limit: Int): String {
    val search = buildJsonObject {
        put("filter", autoheroFilter())
        put("limit", limit)
        put("offset", offset)
        put("sort", "most_popular")
    }
    val variables = buildJsonObject { put("search", search) }
    val payload = buildJsonObject { put("query", AUTOHERO_QUERY); put("variables", variables) }
    return payload.toString()
}

private val fuelTypeMap = mapOf(1039 to "petrol", 1040 to "diesel", 1041 to "hybrid")

/** fuelType 1046 covers both PHEV and plain hybrid on Autohero; isPluginSystem disambiguates. */
fun decodeAutoheroFuel(fuelType: Int?, isPluginSystem: Boolean?): String = when {
    fuelType == 1046 -> if (isPluginSystem == true) "phev" else "hybrid"
    fuelType != null -> fuelTypeMap[fuelType] ?: ""
    else -> ""
}

fun autoheroCarToListing(c: JsonObject): Listing? {
    val id = c["id"]?.jsonPrimitive?.contentOrNull ?: return null
    val make = c["manufacturer"]?.jsonPrimitive?.contentOrNull ?: return null
    val model = c["model"]?.jsonPrimitive?.contentOrNull ?: return null
    val year = c["firstRegistrationYear"]?.jsonPrimitive?.intOrNull ?: return null
    val price = c["offerPrice"]?.jsonObject
    val amount = price?.get("amountMinorUnits")?.jsonPrimitive?.longOrNull ?: return null
    val conv = price["conversionMajor"]?.jsonPrimitive?.longOrNull ?: 100L
    val mileage = c["mileage"]?.jsonObject?.get("distance")?.jsonPrimitive?.intOrNull ?: 0
    val carUrlTitle = c["carUrlTitle"]?.jsonPrimitive?.contentOrNull ?: ""
    val cons = c["fuelConsumption"]?.jsonObject
    val branch = c["esBranch"]?.jsonObject
    return Listing(
        source = "autohero", id = id,
        url = "https://www.autohero.com/de/$carUrlTitle/id/$id",
        make = make, model = model,
        subType = c["subType"]?.jsonPrimitive?.contentOrNull ?: "",
        subTypeExtra = c["subTypeExtra"]?.jsonPrimitive?.contentOrNull ?: "",
        firstRegistrationYear = year, mileageKm = mileage, priceEur = (amount / conv).toInt(),
        fuel = decodeAutoheroFuel(c["fuelType"]?.jsonPrimitive?.intOrNull, c["isPluginSystem"]?.jsonPrimitive?.booleanOrNull),
        gearRaw = c["gearType"]?.jsonPrimitive?.contentOrNull ?: c["gearType"]?.jsonPrimitive?.intOrNull?.toString() ?: "",
        kw = c["kw"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: 0,
        ccm = c["ccm"]?.jsonPrimitive?.intOrNull ?: 0,
        owners = c["carPreownerCount"]?.jsonPrimitive?.intOrNull ?: 0,
        accidents = c["numberOfAccidents"]?.jsonPrimitive?.intOrNull ?: 0,
        numberOfDamages = c["numberOfDamages"]?.jsonPrimitive?.intOrNull ?: 0,
        damageList = emptyList(),
        hasFilledServiceBook = c["hasFilledServiceBook"]?.jsonPrimitive?.booleanOrNull ?: false,
        commercial = c["vatType"]?.jsonPrimitive?.intOrNull == 1054,
        saleInProgress = c["retailAdState"]?.jsonPrimitive?.contentOrNull == "reserved",
        consumptionCombined = cons?.get("combined")?.jsonPrimitive?.doubleOrNull,
        consumptionUrban = cons?.get("city")?.jsonPrimitive?.doubleOrNull,
        tireSeason = null, doors = null, body = null,
        country = c["countryCode"]?.jsonPrimitive?.contentOrNull ?: "DE",
        city = branch?.get("city")?.jsonPrimitive?.contentOrNull ?: "",
        color = null, trunkLitres = null,
    )
}

fun fetchAutohero(): List<Listing> {
    val out = mutableListOf<Listing>()
    var offset = 0
    val limit = 50
    var total = Int.MAX_VALUE
    while (offset < total) {
        val req = HttpRequest.newBuilder(URI(AUTOHERO_ENDPOINT))
            .header("Content-Type", "application/json").header("Accept", "application/json")
            .header("User-Agent", UA).header("x-country", "DE").header("x-locale", "de-DE")
            .POST(HttpRequest.BodyPublishers.ofString(autoheroBody(offset, limit)))
            .timeout(Duration.ofSeconds(20)).build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) break
        val root = ingestJson.parseToJsonElement(resp.body()).jsonObject
        val result = root["data"]?.jsonObject?.get("searchAdV9AdsV2")?.jsonObject ?: break
        total = result["total"]?.jsonPrimitive?.intOrNull ?: 0
        val data = result["data"]?.jsonArray ?: break
        data.forEach { el -> autoheroCarToListing(el.jsonObject)?.let { out.add(it) } }
        offset += limit
        if (data.isEmpty()) break
    }
    return out
}

/**
 * Autohero's bulk search API (fetchAutohero above) never reports body style or dimensions -- model/subType/
 * subTypeExtra are only engine size + trim badge, e.g. "Corolla" / "2.0 Hybrid" / "Team D", identical for the
 * hatchback and the Touring Sports estate. The individual listing's detail PAGE does carry it, embedded as a
 * JS-string-escaped JSON blob (`window.__APOLLO_STATE__ = "...";`) among the page's Apollo GraphQL cache
 * entries. This does one extra HTTP GET per listing, so callers should only use it on an already-narrowed
 * candidate set (see /api/lengths), never the full live pool.
 */
data class AutoheroDetail(val bodyType: String?, val lengthMm: Int?)

private val BODY_TYPE_RE = Regex("\"bodyType\":\"([A-Za-z]+)\"")
private val DIMENSIONS_RE = Regex(
    "\"__typename\":\"CarDetailsDimensionsProjection\"[^}]*\"length\":(\\d+)"
)

fun fetchAutoheroDetail(url: String): AutoheroDetail? {
    val req = HttpRequest.newBuilder(URI(url)).header("User-Agent", UA).timeout(Duration.ofSeconds(20)).GET().build()
    val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
    if (resp.statusCode() !in 200..299) return null
    val html = resp.body()

    val marker = "window.__APOLLO_STATE__ = \""
    val start = html.indexOf(marker).takeIf { it >= 0 }?.plus(marker.length) ?: return null
    var end = start
    while (true) {
        end = html.indexOf('"', end)
        if (end < 0) return null
        if (html[end - 1] != '\\') break
        end++
    }
    // The blob is a JS double-quoted string containing escaped JSON; unescape it via the JSON string grammar,
    // which is the same escaping JS string literals use here (\", \\, \n, \uXXXX, ...).
    val innerJson = ingestJson.parseToJsonElement("\"${html.substring(start, end)}\"").jsonPrimitive.content

    val bodyType = BODY_TYPE_RE.find(innerJson)?.groupValues?.get(1)
    val lengthMm = DIMENSIONS_RE.find(innerJson)?.groupValues?.get(1)?.toIntOrNull()
    return AutoheroDetail(bodyType, lengthMm)
}

// ---------- AutoScout24 ----------

private val AS24_MAKE_SLUGS = mapOf(
    "Audi" to "audi", "BMW" to "bmw", "Mercedes-Benz" to "mercedes-benz", "Porsche" to "porsche",
    "Honda" to "honda", "Toyota" to "toyota", "Hyundai" to "hyundai",
)
private val COUPE_WORDS = setOf("coupe", "coupé", "cabrio", "cabriolet", "roadster")
private val NEXT_DATA_RE = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
private val DIGITS_RE = Regex("""\d+""")
private val YEAR_RE = Regex("""(\d{4})""")
private val KW_RE = Regex("""(\d+)\s*kW""")
private val CONS_RE = Regex("""([\d,.]+)\s*l/100""")

/** How many AS24 result pages (20 listings each) to pull per brand for a LIVE, user-waiting fetch.
 * Deliberately smaller than an offline batch sample (was 5) to keep the Update click responsive. */
var AS24_PAGES_PER_BRAND_LIVE = 2

private fun parseKmDigits(s: String?): Int = if (s.isNullOrBlank()) 0 else (DIGITS_RE.findAll(s).joinToString("") { it.value }.toIntOrNull() ?: 0)

private fun as24Year(vehicleDetails: JsonArray?): Int? =
    vehicleDetails?.firstOrNull { it.jsonObject["iconName"]?.jsonPrimitive?.contentOrNull == "calendar" }
        ?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull
        ?.let { YEAR_RE.find(it)?.groupValues?.get(1)?.toIntOrNull() }

private fun as24Kw(vehicleDetails: JsonArray?): Int =
    vehicleDetails?.firstOrNull { it.jsonObject["iconName"]?.jsonPrimitive?.contentOrNull == "speedometer" }
        ?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull
        ?.let { KW_RE.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0

private fun as24Consumption(vehicleDetails: JsonArray?): Double? =
    vehicleDetails?.firstOrNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull == "fuelConsumptionExtended" }
        ?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull
        ?.let { CONS_RE.find(it)?.groupValues?.get(1)?.replace(",", ".")?.toDoubleOrNull() }

fun decodeAs24Fuel(rawFuel: String, subtitleBlob: String): String {
    if (rawFuel == "Benzin") return "petrol"
    if (rawFuel == "Diesel") return "diesel"
    if (rawFuel == "Elektro") return "electric"
    if ("Elektro/Benzin" in rawFuel || "Hybrid" in rawFuel) {
        val low = subtitleBlob.lowercase()
        return if ("plug-in" in low || "plugin" in low) "phev" else "hybrid"
    }
    return ""
}

fun decodeAs24Body(variant: String?): String? {
    val low = (variant ?: "").lowercase()
    return if (COUPE_WORDS.any { it in low }) "coupe" else null
}

fun decodeAs24Transmission(transmission: String?): String =
    if ((transmission ?: "").lowercase().contains("schalt")) "manual" else "automatic"

fun as24ListingToListing(l: JsonObject): Listing? {
    val v = l["vehicle"]?.jsonObject ?: return null
    val make = v["make"]?.jsonPrimitive?.contentOrNull ?: return null
    val model = v["model"]?.jsonPrimitive?.contentOrNull ?: return null
    val vehicleDetails = l["vehicleDetails"]?.jsonArray
    val year = as24Year(vehicleDetails) ?: return null
    val price = l["price"]?.jsonObject?.get("priceRaw")?.jsonPrimitive?.longOrNull ?: return null
    val id = l["id"]?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString()
    val subtitleBlob = (v["modelVersionInput"]?.jsonPrimitive?.contentOrNull ?: "") + " " + (v["subtitle"]?.jsonPrimitive?.contentOrNull ?: "")
    val location = l["location"]?.jsonObject
    return Listing(
        source = "autoscout24", id = id,
        url = "https://www.autoscout24.de" + (l["url"]?.jsonPrimitive?.contentOrNull ?: ""),
        make = make, model = model,
        subType = v["motorTypeName"]?.jsonPrimitive?.contentOrNull ?: "",
        subTypeExtra = v["variant"]?.jsonPrimitive?.contentOrNull ?: "",
        firstRegistrationYear = year, mileageKm = parseKmDigits(v["mileageInKm"]?.jsonPrimitive?.contentOrNull),
        priceEur = price.toInt(),
        fuel = decodeAs24Fuel(v["fuel"]?.jsonPrimitive?.contentOrNull ?: "", subtitleBlob),
        gearRaw = decodeAs24Transmission(v["transmission"]?.jsonPrimitive?.contentOrNull),
        kw = as24Kw(vehicleDetails), ccm = parseKmDigits(v["engineDisplacementInCCM"]?.jsonPrimitive?.contentOrNull),
        owners = 0, accidents = if (v["isCurrentlyDamaged"]?.jsonPrimitive?.booleanOrNull == true) 1 else 0,
        numberOfDamages = 0, damageList = emptyList(), hasFilledServiceBook = false,
        commercial = false, saleInProgress = false,
        consumptionCombined = as24Consumption(vehicleDetails), consumptionUrban = null,
        tireSeason = null, doors = null, body = decodeAs24Body(v["variant"]?.jsonPrimitive?.contentOrNull),
        country = location?.get("countryCode")?.jsonPrimitive?.contentOrNull ?: "DE",
        city = location?.get("city")?.jsonPrimitive?.contentOrNull ?: "",
        color = null, trunkLitres = null,
    )
}

private fun as24Url(slug: String, page: Int) =
    "https://www.autoscout24.de/lst/$slug?atype=C&cy=D&damaged_listing=exclude&fregfrom=2018" +
        "&pricefrom=10000&priceto=20000&fuel=B,2&ustate=N,U&page=$page"

fun fetchAutoScout24Brand(make: String, pagesPerBrand: Int = AS24_PAGES_PER_BRAND_LIVE): List<Listing> {
    val slug = AS24_MAKE_SLUGS[make] ?: return emptyList()
    val out = mutableListOf<Listing>()
    for (page in 1..pagesPerBrand) {
        val req = HttpRequest.newBuilder(URI(as24Url(slug, page)))
            .header("User-Agent", UA).header("Accept-Language", "de-DE,de;q=0.9")
            .timeout(Duration.ofSeconds(20)).GET().build()
        val resp = try { client.send(req, HttpResponse.BodyHandlers.ofString()) } catch (e: Exception) { break }
        if (resp.statusCode() != 200) break
        val m = NEXT_DATA_RE.find(resp.body()) ?: break
        val data = try { ingestJson.parseToJsonElement(m.groupValues[1]).jsonObject } catch (e: Exception) { break }
        val listings = data["props"]?.jsonObject?.get("pageProps")?.jsonObject?.get("listings")?.jsonArray ?: break
        if (listings.isEmpty()) break
        listings.forEach { el -> as24ListingToListing(el.jsonObject)?.let { out.add(it) } }
        val totalPages = data["props"]?.jsonObject?.get("pageProps")?.jsonObject?.get("numberOfPages")?.jsonPrimitive?.intOrNull ?: 1
        if (page >= totalPages) break
        Thread.sleep(400)   // be a polite client
    }
    return out
}

fun fetchAutoScout24(): List<Listing> {
    val out = mutableListOf<Listing>()
    for (make in AS24_MAKE_SLUGS.keys) {
        out += fetchAutoScout24Brand(make)
    }
    return out
}

/** Both sources, live. Used by the server's /api/pool endpoint and available for an offline re-fetch too. */
fun fetchAllListingsLive(): List<Listing> = fetchAutohero() + fetchAutoScout24()
