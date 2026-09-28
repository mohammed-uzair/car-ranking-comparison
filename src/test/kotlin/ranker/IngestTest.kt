package ranker

import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Unit tests for the PURE parsing/mapping functions in Ingest.kt, against fixed JSON fixtures captured
 * from real Autohero/AutoScout24 responses earlier in this project. Deliberately does NOT hit the network
 * (non-deterministic, slow, and would make CI depend on two third-party sites staying reachable) — the
 * live fetchAutohero()/fetchAutoScout24()/fetchAllListingsLive() functions are exercised manually via
 * `./gradlew runServer` + curl, not in this suite.
 */
private val testJson = Json { ignoreUnknownKeys = true; isLenient = true }
private fun jsonOf(s: String): JsonObject = testJson.parseToJsonElement(s).jsonObject

class AutoheroFuelDecodeTest {
    @Test fun petrol() = assertEquals("petrol", decodeAutoheroFuel(1039, null))
    @Test fun diesel() = assertEquals("diesel", decodeAutoheroFuel(1040, null))
    @Test fun hybrid_code() = assertEquals("hybrid", decodeAutoheroFuel(1041, null))
    @Test fun code_1046_plugin_true_is_phev() = assertEquals("phev", decodeAutoheroFuel(1046, true))
    @Test fun code_1046_plugin_false_is_hybrid() = assertEquals("hybrid", decodeAutoheroFuel(1046, false))
    @Test fun code_1046_plugin_null_defaults_hybrid() = assertEquals("hybrid", decodeAutoheroFuel(1046, null))
    @Test fun unknown_code_is_blank() = assertEquals("", decodeAutoheroFuel(9999, null))
    @Test fun null_code_is_blank() = assertEquals("", decodeAutoheroFuel(null, null))
}

class AutoheroListingParseTest {
    private val fixture = """
    {
      "id": "abc-123", "manufacturer": "BMW", "model": "3er", "subType": "318i", "subTypeExtra": "Advantage",
      "firstRegistrationYear": 2019, "mileage": {"distance": 50525, "unit": "KM"},
      "offerPrice": {"amountMinorUnits": 1899000, "conversionMajor": 100, "currency": "EUR"},
      "fuelType": 1039, "isPluginSystem": false, "gearType": 1139, "kw": 100.0, "ccm": 1998,
      "carPreownerCount": 1, "numberOfAccidents": 0, "numberOfDamages": 0, "hasFilledServiceBook": true,
      "vatType": 1053, "retailAdState": "imported-to-retail",
      "fuelConsumption": {"city": 6.3, "highway": 4.3, "combined": 5.1},
      "esBranch": {"city": "Ketzin"}, "carUrlTitle": "bmw-3-er", "countryCode": "DE"
    }
    """.trimIndent()

    @Test fun maps_all_core_fields() {
        val l = autoheroCarToListing(jsonOf(fixture))!!
        assertEquals("autohero", l.source)
        assertEquals("https://www.autohero.com/de/bmw-3-er/id/abc-123", l.url)
        assertEquals("BMW", l.make); assertEquals("3er", l.model)
        assertEquals("318i", l.subType); assertEquals("Advantage", l.subTypeExtra)
        assertEquals(2019, l.firstRegistrationYear); assertEquals(50525, l.mileageKm)
        assertEquals(18990, l.priceEur)   // 1899000 / 100
        assertEquals("petrol", l.fuel)
        assertEquals(100, l.kw); assertEquals(1, l.owners)
        assertFalse(l.commercial); assertFalse(l.saleInProgress)
        assertEquals(5.1, l.consumptionCombined); assertEquals(6.3, l.consumptionUrban)
        assertEquals("Ketzin", l.city)
    }
    @Test fun commercial_from_vatType_1054() {
        val l = autoheroCarToListing(jsonOf(fixture.replace("\"vatType\": 1053", "\"vatType\": 1054")))!!
        assertTrue(l.commercial)
    }
    @Test fun sale_in_progress_from_reserved_state() {
        val l = autoheroCarToListing(jsonOf(fixture.replace("\"imported-to-retail\"", "\"reserved\"")))!!
        assertTrue(l.saleInProgress)
    }
    @Test fun phev_when_1046_and_plugin_true() {
        val f = fixture.replace("\"fuelType\": 1039", "\"fuelType\": 1046").replace("\"isPluginSystem\": false", "\"isPluginSystem\": true")
        assertEquals("phev", autoheroCarToListing(jsonOf(f))!!.fuel)
    }
    @Test fun missing_required_field_returns_null() {
        val missingId = fixture.replace("\"id\": \"abc-123\",", "")
        assertNull(autoheroCarToListing(jsonOf(missingId)))
    }
}

class As24DecodeTest {
    @Test fun benzin_is_petrol() = assertEquals("petrol", decodeAs24Fuel("Benzin", ""))
    @Test fun diesel_is_diesel() = assertEquals("diesel", decodeAs24Fuel("Diesel", ""))
    @Test fun elektro_is_electric() = assertEquals("electric", decodeAs24Fuel("Elektro", ""))
    @Test fun elektro_benzin_defaults_hybrid() = assertEquals("hybrid", decodeAs24Fuel("Elektro/Benzin", "Sportback advanced"))
    @Test fun elektro_benzin_with_plugin_hint_is_phev() = assertEquals("phev", decodeAs24Fuel("Elektro/Benzin", "Plug-in Hybrid Trend"))
    @Test fun unknown_raw_is_blank() = assertEquals("", decodeAs24Fuel("Erdgas (CNG)", ""))

    @Test fun coupe_variant_flagged() = assertEquals("coupe", decodeAs24Body("2er Gran Coupe"))
    @Test fun cabrio_variant_flagged() = assertEquals("coupe", decodeAs24Body("4er Cabriolet"))
    @Test fun sportback_not_flagged() = assertNull(decodeAs24Body("A1 Sportback"))
    @Test fun null_variant_not_flagged() = assertNull(decodeAs24Body(null))

    @Test fun schaltgetriebe_is_manual() = assertEquals("manual", decodeAs24Transmission("Schaltgetriebe"))
    @Test fun automatik_is_automatic() = assertEquals("automatic", decodeAs24Transmission("Automatik"))
    @Test fun null_defaults_automatic() = assertEquals("automatic", decodeAs24Transmission(null))
}

class As24ListingParseTest {
    private val fixture = """
    {
      "id": "as24-xyz", "url": "/angebote/audi-a1-sportback-xyz",
      "vehicle": {
        "make": "Audi", "model": "A1", "variant": "A1 Sportback", "motorTypeName": "30 TFSI",
        "modelVersionInput": "Sportback 30 TFSI advanced", "subtitle": "Sportfahrwerk, Sitzheizung",
        "transmission": "Automatik", "fuel": "Benzin", "mileageInKm": "34.543 km",
        "engineDisplacementInCCM": "999 cm³", "isCurrentlyDamaged": false
      },
      "vehicleDetails": [
        {"data": "34.543 km", "iconName": "mileage_odometer"},
        {"data": "Automatik", "iconName": "gearbox"},
        {"data": "04/2020", "iconName": "calendar"},
        {"data": "Benzin", "iconName": "gas_pump"},
        {"data": "85 kW (116 PS)", "iconName": "speedometer"},
        {"data": "4,9 l/100 km (komb.)", "iconName": "water_drop", "name": "fuelConsumptionExtended"}
      ],
      "price": {"priceRaw": 17475},
      "location": {"countryCode": "DE", "city": "Grosskrotzenburg"}
    }
    """.trimIndent()

    @Test fun maps_all_core_fields() {
        val l = as24ListingToListing(jsonOf(fixture))!!
        assertEquals("autoscout24", l.source)
        assertEquals("https://www.autoscout24.de/angebote/audi-a1-sportback-xyz", l.url)
        assertEquals("Audi", l.make); assertEquals("A1", l.model)
        assertEquals("30 TFSI", l.subType); assertEquals("A1 Sportback", l.subTypeExtra)
        assertEquals(2020, l.firstRegistrationYear); assertEquals(34543, l.mileageKm)
        assertEquals(17475, l.priceEur); assertEquals("petrol", l.fuel)
        assertEquals("automatic", l.gearRaw); assertEquals(85, l.kw); assertEquals(999, l.ccm)
        assertEquals(0, l.owners); assertEquals(0, l.accidents)
        assertEquals(4.9, l.consumptionCombined); assertNull(l.consumptionUrban)
        assertNull(l.body)   // "A1 Sportback" has no coupe/cabrio keyword
        assertEquals("Grosskrotzenburg", l.city)
    }
    @Test fun currently_damaged_maps_to_one_accident() {
        val l = as24ListingToListing(jsonOf(fixture.replace("\"isCurrentlyDamaged\": false", "\"isCurrentlyDamaged\": true")))!!
        assertEquals(1, l.accidents)
    }
    @Test fun missing_year_returns_null() {
        val noCalendar = fixture.replace("""{"data": "04/2020", "iconName": "calendar"},""", "")
        assertNull(as24ListingToListing(jsonOf(noCalendar)))
    }
    @Test fun missing_make_returns_null() {
        val noMake = fixture.replace("\"make\": \"Audi\",", "")
        assertNull(as24ListingToListing(jsonOf(noMake)))
    }
}
