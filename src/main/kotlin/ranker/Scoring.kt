package ranker

import kotlin.math.max
import kotlin.math.min

fun clamp(x: Double, lo: Double, hi: Double) = max(lo, min(hi, x))

val ALLOWED_MAKES = setOf("Audi", "BMW", "Mercedes-Benz", "Porsche", "Honda", "Toyota", "Hyundai")
val COUPE_BODIES = setOf("coupe", "coupé", "cabrio", "cabriolet", "roadster")

/** Per-model price statistics used by the Value column. */
data class ModelStats(val medianPrice: Double, val medianMileage: Double, val medianYear: Double)

// ---------- generation & engine-platform detection ----------

fun generation(make: String, model: String, year: Int): String {
    val m = model.lowercase()
    return when (make) {
        "BMW" -> when {
            "1er" in m -> if (year <= 2019) "F20" else "F40"
            "3er" in m -> if (year <= 2019) "F30" else "G20"
            "2er" in m -> "F4x"
            "5er" in m -> "G30"
            m == "x1" -> "F48"
            m == "x2" -> "F39"
            else -> "?"
        }
        "Audi" -> when {
            "a1" in m -> "GB"
            "a3" in m -> if (year <= 2020) "8V" else "8Y"
            m.startsWith("a4") -> "B9"
            m == "q2" -> "Q2"
            m == "q3" -> "F3"
            else -> "?"
        }
        "Mercedes-Benz" -> when {
            // NOTE: order matters — "a-klasse" is a literal substring of "gla-klasse" AND "cla-klasse",
            // so those two checks MUST come first or GLA/CLA silently misclassify as A-Klasse's generation
            // (which doesn't exist in the reference table for them) and get excluded from the table.
            "gla" in m -> if (year <= 2020) "X156" else "H247"
            "cla" in m -> if (year <= 2019) "C117" else "C118"
            "c-klasse" in m -> "W205"
            "b-klasse" in m -> "W247"
            "a-klasse" in m -> "W177"
            "citan" in m -> "Citan"
            else -> "?"
        }
        "Toyota", "Honda", "Hyundai" -> "*"
        else -> "?"
    }
}

/** Engine platform code (keys into reference.engineGrade / engineThresholdKm). */
fun platform(l: Listing): String {
    val st = (l.subType + " " + l.subTypeExtra).lowercase()
    val m = l.model.lowercase()
    val f = l.fuel.lowercase()
    return when (l.make) {
        "Audi" -> when {
            f == "phev" || "e-tron" in st || "tfsi e" in st -> "AudiPHEV"
            f == "diesel" -> if ("3.0" in st || "50 tdi" in st) "3.0TDI" else "2.0TDI"
            "40 tfsi" in st || "45 tfsi" in st || "2.0 tfsi" in st ||
                (l.model.substringBefore(" ") in setOf("A4", "A5", "Q5") && "35 tfsi" in st) -> "EA888"
            "35 tfsi" in st || "1.5" in st -> "1.5Evo"
            "1.4" in st -> if (l.kw >= 108) "1.4ACT" else "1.4TSI"
            "25 tfsi" in st || "30 tfsi" in st || "1.0" in st -> "1.0TSI"
            else -> "unknown"
        }
        "BMW" -> when {
            f == "phev" || "25e" in st || "xe" in st -> "BMWPHEV"
            f == "diesel" -> if ("116d" in st || "216d" in st) "B37" else "B47"
            listOf("140i", "240i", "340i", "440i", "m140", "m240").any { it in st } -> "B58"
            listOf("320i", "318i", "330i", "20i", "25i", "230i", "120i").any { it in st } -> "B48"
            listOf("116i", "118i", "218i", "216i", "18i").any { it in st } -> "B38"
            else -> "unknown"
        }
        "Mercedes-Benz" -> when {
            f == "diesel" -> if (listOf("180d", "200d").any { it in st }) "OM608" else "OM654"
            listOf("c-klasse", "e-klasse").any { it in m } -> "M274"
            listOf("180", "200").any { it in st } -> "M282"
            listOf("220", "250").any { it in st } -> if (l.firstRegistrationYear >= 2018) "M260" else "M270"
            else -> "M282"
        }
        "Toyota" -> when {
            f == "hybrid" || f == "phev" || "hybrid" in st -> "ToyotaHybrid"
            "turbo" in st || "1.2" in st -> "ToyotaTurbo"
            else -> "ToyotaNA"
        }
        "Honda" -> when {
            f == "hybrid" || f == "phev" || "hybrid" in st || "e:hev" in st -> "HondaHybrid"
            "1.5" in st -> "Honda1.5T"
            "1.0" in st -> "Honda1.0T"
            else -> "HondaNA"
        }
        "Hyundai" -> when {
            f == "hybrid" || f == "phev" || "hybrid" in st || "hev" in st -> "HyundaiHybrid"
            l.model.equals("i10", ignoreCase = true) -> "HyundaiNA"
            else -> "HyundaiTGDI"
        }
        else -> "unknown"
    }
}

// ---------- body / doors (base filter) ----------

fun isMultiDoor(l: Listing): Boolean {
    l.doors?.let { return it >= 4 }
    val b = l.body?.lowercase()
    if (b != null) return b !in COUPE_BODIES
    // infer from model/subType tokens
    val s = (l.model + " " + l.subType).lowercase()
    if (COUPE_BODIES.any { it in s }) return false
    if ("gran coupe" in s || "gran coupé" in s) return true // 4-door "coupé"
    return true // sedans/hatch/SUV/estate/MPV by default
}

/**
 * Resolve (make, model, year) to its ROI reference entry. Handles two cases:
 *  - exact "MAKE|MODEL|GEN" or "MAKE|MODEL|*" match — used by BMW/Audi/Mercedes, which track real
 *    generations and enumerate body-variant model names explicitly (e.g. "A3 Sportback" vs "A3 Limousine").
 *  - a base-model PREFIX match for wildcard-generation makes (Toyota/Honda/Hyundai): Autohero sometimes
 *    bakes a body-style suffix straight into the `model` field (e.g. "Auris Touring Sports" for the Auris
 *    estate) instead of putting it in subType. We fall back to the longest known base model name that is
 *    a whole-word prefix of the listing's model — "Auris Touring Sports" -> "Auris" — so it isn't silently
 *    dropped. Returns null if nothing matches (car cannot be scored / is excluded upstream).
 */
fun resolveRoi(make: String, model: String, year: Int, ref: Reference): Pair<Double, String>? {
    val gen = generation(make, model, year)
    ref.roi["$make|$model|$gen"]?.let { return it to "$model ($gen)" }
    ref.roi["$make|$model|*"]?.let { return it to model }
    if (gen == "*") {
        val ml = model.lowercase()
        val base = ref.roi.keys
            .filter { it.startsWith("$make|") && it.endsWith("|*") }
            .map { it.removePrefix("$make|").removeSuffix("|*") }
            .filter { ml == it.lowercase() || ml.startsWith(it.lowercase() + " ") }
            .maxByOrNull { it.length }
        if (base != null) return ref.roi["$make|$base|*"]!! to "$base (matched from \"$model\")"
    }
    return null
}

fun passesBaseFilter(l: Listing, ref: Reference): Boolean {
    if (l.make !in ALLOWED_MAKES) return false
    if (l.firstRegistrationYear < 2018) return false
    if (l.priceEur < 10000 || l.priceEur > 20000) return false
    if (l.country != "DE") return false
    if (l.owners > 3) return false
    if (l.accidents != 0) return false
    if (l.saleInProgress) return false      // exclude reserved / sale-already-in-progress listings
    if (l.fuel.lowercase() !in setOf("petrol", "hybrid", "phev")) return false // exclude diesel & pure-electric
    if (!isMultiDoor(l)) return false
    // must have a known ROI (reliability) or it can't be scored
    return resolveRoi(l.make, l.model, l.firstRegistrationYear, ref) != null
}

// ---------- price model ----------

fun buildPriceModel(listings: List<Listing>): Map<String, ModelStats> {
    fun median(xs: List<Double>) = xs.sorted().let {
        if (it.isEmpty()) 0.0 else if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2
    }
    return listings.groupBy { "${it.make}|${it.model}" }.mapValues { (_, g) ->
        ModelStats(
            median(g.map { it.priceEur.toDouble() }),
            median(g.map { it.mileageKm.toDouble() }),
            median(g.map { it.firstRegistrationYear.toDouble() })
        )
    }
}

// ---------- the ten column scorers ----------

/** Holistic car-buying ROI (0-10, independent). Base = reliability; then hidden-flag penalties. See plugins/roi.md. */
fun roiScore(l: Listing, ref: Reference, stats: ModelStats?): CellScore {
    val (base, matchedAs) = resolveRoi(l.make, l.model, l.firstRegistrationYear, ref)
        ?: return CellScore(0.0, available = false, note = "no ROI data")
    var adj = 0.0
    val flags = mutableListOf<String>()
    val age = max(1, ref.currentYear - l.firstRegistrationYear)
    val kmYr = l.mileageKm.toDouble() / age
    if (l.make == "Toyota" && l.fuel.lowercase() !in setOf("hybrid", "phev")) { adj -= 1.0; flags += "Toyota not hybrid" }
    val boot = resolveBootLitres(l.make, l.model, ref)
    if (boot != null && boot < 360) { adj -= 0.7; flags += "boot ${boot}L<360" }
    if (kmYr > 18000) { adj -= min(1.5, (kmYr - 18000) / 8000); flags += "${kmYr.toInt()} km/yr" }
    val expected = expectedPrice(l, stats)
    if (expected != null && l.priceEur > 1.05 * expected) { adj -= min(1.0, (l.priceEur - expected) / expected * 3); flags += "overpriced" }
    if (l.damageList.isNotEmpty()) { adj -= 0.6; flags += "listed damage" }  // minor cosmetic count is NOT penalised (accidents already filtered)
    if (l.owners == 2) adj -= 0.2 else if (l.owners >= 3) { adj -= 0.6; flags += "${l.owners} owners" }
    if (l.commercial) { adj -= 0.5; flags += "commercial/fleet use" }
    if (!l.hasFilledServiceBook) { adj -= 0.3; flags += "no full service history" }
    val roi = clamp(base + adj, 0.0, 10.0)
    val verdict = if (roi >= 7.0) "Yes" else "No"
    val matchNote = if (matchedAs != l.model) " [ROI matched as: $matchedAs]" else ""
    val note = "$verdict. " + (if (flags.isEmpty()) "no red flags" else "Flags: " + flags.joinToString("; ")) + matchNote
    return CellScore(roi, note = note)
}

fun engineScore(l: Listing, ref: Reference): CellScore {
    val plat = platform(l)
    val base = (ref.engineGrade[plat] ?: 7.0) * 10
    val thr = ref.engineThresholdKm[plat]
    val penalty = when {
        thr == null -> 0.0
        l.mileageKm >= thr -> clamp((l.mileageKm - thr).toDouble() / thr * 40, 0.0, 40.0)
        l.mileageKm >= 0.9 * thr -> 8.0
        else -> 0.0
    }
    val powerAdj = if (l.kw < 70) -10.0 else if (l.kw >= 110) 5.0 else 0.0
    val age = max(1, ref.currentYear - l.firstRegistrationYear)
    val kmPerYear = l.mileageKm.toDouble() / age
    var fuelRisk = 0.0
    if (l.fuel.lowercase() == "phev") fuelRisk += 10
    if (l.fuel.lowercase() == "diesel" && kmPerYear < 8000) fuelRisk += 8
    return CellScore(clamp(base - penalty + powerAdj - fuelRisk, 0.0, 100.0),
        note = "$plat" + if (thr != null && l.mileageKm >= thr) " · past ${thr}km" else "")
}

fun mileageScore(l: Listing, ref: Reference): CellScore {
    val age = max(1, ref.currentYear - l.firstRegistrationYear)
    val kmPerYear = l.mileageKm.toDouble() / age
    val base = clamp(100 * (25000 - kmPerYear) / 20000, 0.0, 100.0)
    val absPen = if (l.mileageKm > 200000) clamp((l.mileageKm - 200000).toDouble() / 50000 * 20, 0.0, 20.0) else 0.0
    val svc = if (l.hasFilledServiceBook) 5.0 else 0.0
    return CellScore(clamp(base - absPen + svc, 0.0, 100.0), note = "${kmPerYear.toInt()} km/yr")
}

fun expectedPrice(l: Listing, stats: ModelStats?): Double? {
    if (stats == null || stats.medianPrice <= 0) return null
    val e = stats.medianPrice + (stats.medianMileage - l.mileageKm) * 0.05 + (l.firstRegistrationYear - stats.medianYear) * 800
    return if (e > 0) e else null
}

fun valueScore(l: Listing, stats: ModelStats?): CellScore {
    val expected = expectedPrice(l, stats) ?: return CellScore(50.0, note = "no baseline")
    val score = clamp(50 + (expected - l.priceEur) / expected * 100, 0.0, 100.0)
    return CellScore(score, note = "exp €${expected.toInt()}")
}

fun transmissionScore(l: Listing, ref: Reference): CellScore {
    val type = ref.gearTypeMap[l.gearRaw] ?: if ("manual" in l.gearRaw.lowercase()) "manual"
        else if (l.gearRaw.isBlank()) "unknown" else "automatic"
    var score = when (type) { "automatic" -> 85.0; "manual" -> 70.0; else -> 60.0 }
    val modelKey = "${l.make}|${l.model}"
    if (type == "automatic" && modelKey in ref.dctRiskModels) score = 60.0
    return CellScore(score, note = type)
}

fun ownersScore(l: Listing): CellScore =
    CellScore(when { l.owners <= 1 -> 100.0; l.owners == 2 -> 80.0; l.owners == 3 -> 60.0; else -> 40.0 },
        note = "${l.owners} owner(s)")

fun commercialScore(l: Listing): CellScore =
    if (l.commercial) CellScore(50.0, note = "commercial/fleet (VAT-deductible)")
    else CellScore(100.0, note = "private (margin scheme)")

fun consumptionScore(l: Listing): CellScore {
    val c = l.consumptionCombined ?: return CellScore(0.0, available = false, note = "n/a")
    return CellScore(clamp(100 * (9 - c) / (9 - 4), 0.0, 100.0), note = "$c L/100km combined")
}

/** Urban/city consumption — weighted higher in practice for a Berlin-city use case than the combined figure. */
fun consumptionUrbanScore(l: Listing): CellScore {
    val c = l.consumptionUrban ?: return CellScore(0.0, available = false, note = "n/a")
    return CellScore(clamp(100 * (10 - c) / (10 - 3), 0.0, 100.0), note = "$c L/100km urban")
}

/** Same body-style-suffix fallback as resolveRoi (e.g. "Auris Touring Sports" falls back to "Auris" only if no direct entry exists). */
fun resolveBootLitres(make: String, model: String, ref: Reference): Int? {
    ref.bootLitres["$make|$model"]?.let { return it }
    val ml = model.lowercase()
    val base = ref.bootLitres.keys
        .filter { it.startsWith("$make|") }
        .map { it.removePrefix("$make|") }
        .filter { ml.startsWith(it.lowercase() + " ") }
        .maxByOrNull { it.length }
    return base?.let { ref.bootLitres["$make|$it"] }
}

fun trunkScore(l: Listing, ref: Reference): CellScore {
    val litres = resolveBootLitres(l.make, l.model, ref)
        ?: return CellScore(0.0, available = false, note = "n/a")
    return CellScore(clamp(100.0 * (litres - 300) / (500 - 300), 0.0, 100.0), note = "$litres L")
}

fun tireScore(l: Listing): CellScore = when (l.tireSeason?.lowercase()) {
    null -> CellScore(0.0, available = false, note = "n/a")
    "all-season", "both", "allseason" -> CellScore(100.0, note = l.tireSeason!!)
    else -> CellScore(70.0, note = l.tireSeason!!)
}

fun damageScore(l: Listing): CellScore {
    if (l.numberOfDamages == 0 || l.damageList.isEmpty()) return CellScore(100.0, note = "none")
    return CellScore(clamp(100.0 - 8 * l.numberOfDamages, 60.0, 100.0),
        note = l.damageList.joinToString("; ").take(60))
}

// ---------- assemble ----------

fun scoreCar(l: Listing, ref: Reference, stats: Map<String, ModelStats>): ScoredCar {
    val scores = linkedMapOf(
        "ROI" to roiScore(l, ref, stats["${l.make}|${l.model}"]),
        "Engine" to engineScore(l, ref),
        "Mileage" to mileageScore(l, ref),
        "Value" to valueScore(l, stats["${l.make}|${l.model}"]),
        "Transmission" to transmissionScore(l, ref),
        "Owners" to ownersScore(l),
        "Consumption" to consumptionScore(l),
        "ConsumptionUrban" to consumptionUrbanScore(l),
        "TrunkSize" to trunkScore(l, ref),
        "TireSeason" to tireScore(l),
        "MinorDamage" to damageScore(l),
        "Commercial" to commercialScore(l)
    )
    val name = listOf(l.make, l.model, l.subType, l.subTypeExtra).filter { it.isNotBlank() }.joinToString(" ")
    return ScoredCar(
        id = l.id, name = name, make = l.make, model = l.model, url = l.url, source = l.source,
        firstRegistration = l.firstRegistrationYear, fuel = l.fuel, priceEur = l.priceEur,
        mileageKm = l.mileageKm, owners = l.owners, city = l.city,
        platform = platform(l), generation = generation(l.make, l.model, l.firstRegistrationYear),
        scores = scores
    )
}

/** Total = sum of scores that are available AND whose column is globally active. */
fun totalOf(car: ScoredCar, activeColumns: Set<String>): Double =
    car.scores.entries.filter { it.key in activeColumns && it.value.available }.sumOf { it.value.value }

/** Score, gate, sum, sort desc, cap at 50, assign index. */
fun buildTable(listings: List<Listing>, ref: Reference,
               activeColumns: Set<String> = DEFAULT_ACTIVE_COLUMNS.toSet(), maxRows: Int = 50): List<ScoredCar> {
    val eligible = listings.filter { passesBaseFilter(it, ref) }
    val stats = buildPriceModel(eligible)
    val scored = eligible.map { l ->
        val car = scoreCar(l, ref, stats)
        car.total = totalOf(car, activeColumns)
        car
    }.sortedWith(compareByDescending<ScoredCar> { it.total }
        .thenBy { it.mileageKm }.thenBy { it.priceEur }.thenBy { it.id })
        .take(maxRows)
    scored.forEachIndexed { i, c -> c.index = i + 1 }
    return scored
}
