package ranker

import kotlinx.serialization.Serializable

/** A source-agnostic listing. Autohero/AutoScout/manual all map into this shape (see ingest). */
@Serializable
data class Listing(
    val source: String = "manual",          // "autohero" | "autoscout" | "manual" | ...
    val id: String,
    val url: String,
    val make: String,
    val model: String,
    val subType: String = "",
    val subTypeExtra: String = "",
    val firstRegistrationYear: Int,
    val mileageKm: Int,
    val priceEur: Int,
    val fuel: String = "",                   // petrol | diesel | phev | hybrid (already decoded)
    val gearRaw: String = "",                // raw gearType code or text; decoded via reference.gearTypeMap
    val kw: Int = 0,
    val ccm: Int = 0,
    val owners: Int = 0,
    val accidents: Int = 0,
    val numberOfDamages: Int = 0,
    val damageList: List<String> = emptyList(),
    val hasFilledServiceBook: Boolean = false,
    val commercial: Boolean = false,         // prior commercial/fleet use (Gewerbliche Nutzung); Autohero: vatType==1054 (VAT-deductible)
    val saleInProgress: Boolean = false,     // reserved / sale already in progress; Autohero: retailAdState=="reserved" — excluded upstream
    val consumptionCombined: Double? = null, // L/100km combined (null → Consumption cell becomes X)
    val consumptionUrban: Double? = null,    // L/100km urban/city (null → ConsumptionUrban cell becomes X); Autohero: fuelConsumption.city
    val tireSeason: String? = null,          // "all-season" | "summer" | "winter" | null
    val doors: Int? = null,                  // null → derive from body/model
    val body: String? = null,                // "suv"|"sedan"|"hatch"|"estate"|"mpv"|"coupe"|"cabrio" if known
    val country: String = "DE",
    val city: String = "",
    val color: String? = null,               // exterior color, when the source reports it (used for cross-source dedup; Autohero: not exposed)
    val trunkLitres: Int? = null             // boot volume, when the source reports it directly (Autohero: not exposed — falls back to reference.bootLitres via resolveTrunkLitres)
)

/** Score for one scored column. `available=false` renders as `X` and is excluded from Total for this row. */
@Serializable
data class CellScore(val value: Double, val available: Boolean = true, val note: String = "")

/** A fully scored car row ready for display. */
@Serializable
data class ScoredCar(
    val id: String,
    val name: String,
    val make: String,        // exposed separately from `name` so the page can filter by brand/model reliably
    val model: String,
    val url: String,
    val source: String,
    val color: String? = null,  // for future cross-source dedup; null until a source reports it
    val firstRegistration: Int,
    val fuel: String,
    val priceEur: Int,
    val mileageKm: Int,
    val owners: Int,
    val city: String,
    val platform: String,
    val generation: String,
    val scores: Map<String, CellScore>,     // column name -> score
    var total: Double = 0.0,
    var index: Int = 0
)

/**
 * All twelve scored columns (ROI + eleven total-columns), in display order. ROI is independent (0-10) and NOT summed into Total.
 * Owners, TireSeason, MinorDamage, Commercial are placed last and are OFF by default (see DEFAULT_ACTIVE_COLUMNS) —
 * they can skew comparisons across data sources that don't all report them, or are situational rather than
 * core to the ranking; the user can tick them back on in the page.
 */
val SCORED_COLUMNS = listOf(
    "ROI", "Engine", "Mileage", "Value", "Transmission", "Consumption", "ConsumptionUrban", "TrunkSize",
    "Owners", "TireSeason", "MinorDamage", "Commercial"
)

/** The eleven columns that feed the Total (everything except the independent ROI). */
val TOTAL_COLUMNS = SCORED_COLUMNS.filter { it != "ROI" }

/** Which total-columns are active by default (i.e. summed into Total on first load). */
val DEFAULT_ACTIVE_COLUMNS = listOf("Engine", "Mileage", "Value", "Transmission", "Consumption", "ConsumptionUrban", "TrunkSize")
