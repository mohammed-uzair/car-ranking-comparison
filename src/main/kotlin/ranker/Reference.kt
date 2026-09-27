package ranker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Reference(
    val currentYear: Int = 2026,
    val roi: Map<String, Double> = emptyMap(),
    val roiPending: List<String> = emptyList(),
    val engineGrade: Map<String, Double> = emptyMap(),
    val engineThresholdKm: Map<String, Int> = emptyMap(),
    val gearTypeMap: Map<String, String> = emptyMap(),
    val fuelTypeMap: Map<String, String> = emptyMap(),
    val dctRiskModels: List<String> = emptyList(),
    val bootLitres: Map<String, Int> = emptyMap()
)

object Ref {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun load(path: String = "data/reference.json"): Reference =
        json.decodeFromString(Reference.serializer(), File(path).readText())
}
