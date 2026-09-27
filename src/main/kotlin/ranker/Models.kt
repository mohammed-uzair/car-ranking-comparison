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
    val consumptionCombined: Double? = null, // L/100km (null → Tire/Consumption cell becomes X)
    val tireSeason: String? = null,          // "all-season" | "summer" | "winter" | null
    val doors: Int? = null,                  // null → derive from body/model
    val body: String? = null,                // "suv"|"sedan"|"hatch"|"estate"|"mpv"|"coupe"|"cabrio" if known
    val country: String = "DE",
    val city: String = ""
)

/** Score for one scored column. `available=false` renders as `X` and is excluded from Total for this row. */
@Serializable
data class CellScore(val value: Double, val available: Boolean = true, val note: String = "")

/** A fully scored car row ready for display. */
@Serializable
data class ScoredCar(
    val id: String,
    val name: String,
    val url: String,
    val source: String,
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

/** All eleven scored columns, in display order. ROI is independent (0-10) and NOT summed into Total. */
val SCORED_COLUMNS = listOf(
    "ROI", "Engine", "Mileage", "Value", "Transmission",
    "Owners", "Consumption", "TrunkSize", "TireSeason", "MinorDamage", "Commercial"
)

/** The ten columns that feed the Total (everything except the independent ROI). */
val TOTAL_COLUMNS = SCORED_COLUMNS.filter { it != "ROI" }
