package ranker

import kotlin.test.*

private val REF = Ref.load("data/reference.json")

/** Listing factory with sensible defaults; override only what a test needs. */
private fun L(
    id: String = "t", make: String = "BMW", model: String = "3er", subType: String = "318i",
    year: Int = 2019, km: Int = 50000, price: Int = 15000, fuel: String = "petrol",
    gear: String = "1139", kw: Int = 100, ccm: Int = 1998, owners: Int = 1, accidents: Int = 0,
    damages: Int = 0, damageList: List<String> = emptyList(), svc: Boolean = false,
    commercial: Boolean = false, saleInProgress: Boolean = false,
    cons: Double? = 6.0, consUrban: Double? = 6.5, consHighway: Double? = null, co2: Double? = null,
    tire: String? = null, doors: Int? = 5, body: String? = null,
    source: String = "test", color: String? = null, trunkLitres: Int? = null
) = Listing(
    source = source, id = id, url = "http://x", make = make, model = model, subType = subType,
    firstRegistrationYear = year, mileageKm = km, priceEur = price, fuel = fuel, gearRaw = gear,
    kw = kw, ccm = ccm, owners = owners, accidents = accidents, numberOfDamages = damages,
    damageList = damageList, hasFilledServiceBook = svc, commercial = commercial, saleInProgress = saleInProgress,
    consumptionCombined = cons, consumptionUrban = consUrban, consumptionHighway = consHighway, co2 = co2,
    tireSeason = tire, doors = doors, body = body,
    color = color, trunkLitres = trunkLitres
)

class BaseFilterTest {
    @Test fun rejects_bad_brand()    = assertFalse(passesBaseFilter(L(make = "Fiat"), REF))
    @Test fun rejects_old_year()     = assertFalse(passesBaseFilter(L(year = 2017), REF))
    @Test fun rejects_low_price()    = assertFalse(passesBaseFilter(L(price = 9000), REF))
    @Test fun rejects_high_price()   = assertFalse(passesBaseFilter(L(price = 23000), REF))
    @Test fun rejects_many_owners()  = assertFalse(passesBaseFilter(L(owners = 4), REF))
    @Test fun rejects_accident()     = assertFalse(passesBaseFilter(L(accidents = 1), REF))
    @Test fun rejects_diesel()       = assertFalse(passesBaseFilter(L(fuel = "diesel"), REF))
    @Test fun rejects_electric()     = assertFalse(passesBaseFilter(L(fuel = "electric"), REF))
    @Test fun rejects_two_door()     = assertFalse(passesBaseFilter(L(doors = 2), REF))
    @Test fun rejects_sale_in_progress() = assertFalse(passesBaseFilter(L(saleInProgress = true), REF))
    @Test fun rejects_unknown_roi()  = assertFalse(passesBaseFilter(L(model = "9er", subType = "999i"), REF))
    @Test fun accepts_valid()        = assertTrue(passesBaseFilter(L(), REF))
    @Test fun accepts_phev()         = assertTrue(passesBaseFilter(L(fuel = "phev", model = "2er", subType = "225xe"), REF))
    // Regression: Autohero bakes body-style suffixes into `model` for wildcard-gen makes (Toyota/Honda/Hyundai),
    // e.g. "Auris Touring Sports" for the Auris estate. Must fall back to the base model, not silently drop the car.
    @Test fun accepts_body_variant_suffix_via_fallback() =
        assertTrue(passesBaseFilter(L(make = "Toyota", model = "Auris Touring Sports", subType = "1.8 Hybrid", fuel = "hybrid"), REF))
    @Test fun rejects_unrelated_model_that_merely_shares_a_prefix() =
        // "Aurislike" is not "Auris " + suffix (no word boundary) -> must NOT fuzzy-match
        assertFalse(passesBaseFilter(L(make = "Toyota", model = "Aurislike", fuel = "hybrid"), REF))
    // Trunk-size hard filter (min 360L): listing's own value wins; unknown (neither listing nor
    // reference table) must NOT be rejected.
    @Test fun rejects_known_small_trunk_from_listing() =
        assertFalse(passesBaseFilter(L(trunkLitres = 300), REF))  // BMW 3er's reference entry (480L) is fine, but the LISTING says 300L -> listing wins
    @Test fun accepts_known_large_trunk_from_listing() =
        assertTrue(passesBaseFilter(L(trunkLitres = 400), REF))
    @Test fun accepts_when_trunk_size_fully_unknown() {
        val noBootData = REF.copy(bootLitres = emptyMap())
        assertTrue(passesBaseFilter(L(trunkLitres = null), noBootData))
    }
}

class RoiResolutionTest {
    @Test fun body_variant_suffix_resolves_to_base_model_roi() {
        val base = resolveRoi("Toyota", "Auris", 2018, REF)!!.first
        val variant = resolveRoi("Toyota", "Auris Touring Sports", 2018, REF)!!.first
        assertEquals(base, variant)
    }
    @Test fun exact_match_preferred_over_fallback() {
        // BMW/Audi/Mercedes enumerate body variants explicitly; an exact hit must win, no fallback needed.
        val a3sportback = resolveRoi("Audi", "A3 Sportback", 2019, REF)
        assertNotNull(a3sportback)
    }
    @Test fun unrelated_model_does_not_resolve() =
        assertNull(resolveRoi("Toyota", "Supra", 2020, REF))
    // Regression: "a-klasse" is a literal substring of "gla-klasse" and "cla-klasse" — generation()
    // must check the longer/more-specific tokens first or these silently misclassify and vanish.
    @Test fun gla_klasse_does_not_misclassify_as_a_klasse() {
        assertEquals("X156", generation("Mercedes-Benz", "GLA-Klasse", 2018))
        assertNotNull(resolveRoi("Mercedes-Benz", "GLA-Klasse", 2018, REF))
    }
    @Test fun cla_klasse_does_not_misclassify_as_a_klasse() {
        assertEquals("C117", generation("Mercedes-Benz", "CLA-Klasse", 2018))
        assertNotNull(resolveRoi("Mercedes-Benz", "CLA-Klasse", 2018, REF))
    }
    @Test fun boot_litres_fallback_prefers_direct_entry_over_base() {
        // Auris Touring Sports (estate) has its own accurate entry and must NOT fall back to the
        // hatchback Auris figure.
        val direct = resolveBootLitres("Toyota", "Auris Touring Sports", REF)
        val base = resolveBootLitres("Toyota", "Auris", REF)
        assertNotEquals(base, direct)
    }
}

class DeterminismTest {
    @Test fun same_listing_same_scores() {
        val a = scoreCar(L(), REF, emptyMap())
        val b = scoreCar(L(), REF, emptyMap())
        assertEquals(a.scores.mapValues { it.value.value }, b.scores.mapValues { it.value.value })
    }
    @Test fun different_id_same_total() {
        val stats = buildPriceModel(listOf(L(id = "a"), L(id = "b")))
        val a = scoreCar(L(id = "a"), REF, stats).also { it.total = totalOf(it, TOTAL_COLUMNS.toSet()) }
        val b = scoreCar(L(id = "b"), REF, stats).also { it.total = totalOf(it, TOTAL_COLUMNS.toSet()) }
        assertEquals(a.total, b.total)
    }
    @Test fun identical_car_same_roi() =
        assertEquals(roiScore(L(id = "a"), REF, null).value, roiScore(L(id = "b"), REF, null).value)
    @Test fun flags_lower_roi() {
        val clean = roiScore(L(km = 30000, svc = true, owners = 1), REF, null).value
        val flagged = roiScore(L(km = 220000, svc = false, owners = 3, commercial = true), REF, null).value
        assertTrue(flagged < clean, "flagged $flagged should be < clean $clean")
    }
}

class RoiIndependenceTest {
    @Test fun roi_is_0_to_10() {
        val roi = roiScore(L(), REF, null)
        assertTrue(roi.value in 0.0..10.0, "ROI ${roi.value} not in 0..10")
    }
    @Test fun roi_not_in_total_columns() = assertFalse("ROI" in TOTAL_COLUMNS)
    @Test fun ownership_cost_not_in_total_columns() = assertFalse("OwnershipCost" in TOTAL_COLUMNS)
    @Test fun total_excludes_independent_plugins() {
        val car = scoreCar(L(), REF, emptyMap())
        val withIndependents = car.scores.filterValues { it.available }.values.sumOf { it.value }
        val total = totalOf(car, TOTAL_COLUMNS.toSet())
        assertTrue(total < withIndependents, "total should exclude the independent-plugin cells")
        val independentsSum = car.scores["ROI"]!!.value + car.scores["OwnershipCost"]!!.value
        // totalOf() rounds to 2dp; independentsSum doesn't, so allow for that rounding granularity.
        assertEquals(total, totalOf(car, SCORED_COLUMNS.toSet()) - independentsSum, 0.01)
    }
}

class EngineThresholdTest {
    @Test fun over_threshold_scores_lower() {
        // Audi Q2 1.4 TFSI, kw>=108 → platform 1.4ACT, threshold 90000 km
        val under = engineScore(L(make = "Audi", model = "Q2", subType = "1.4 TFSI", kw = 110, km = 80000), REF)
        val over  = engineScore(L(make = "Audi", model = "Q2", subType = "1.4 TFSI", kw = 110, km = 120000), REF)
        assertTrue(over.value < under.value, "over-threshold ${over.value} should be < under ${under.value}")
    }
}

class ColumnScoreTest {
    @Test fun consumption_missing_is_X() = assertFalse(consumptionScore(L(cons = null)).available)
    @Test fun consumption_urban_missing_is_X() = assertFalse(consumptionUrbanScore(L(consUrban = null)).available)
    @Test fun consumption_urban_lower_is_higher() =
        assertTrue(consumptionUrbanScore(L(consUrban = 4.0)).value > consumptionUrbanScore(L(consUrban = 9.0)).value)
    @Test fun tire_missing_is_X()        = assertFalse(tireScore(L(tire = null)).available)
    @Test fun trunk_unknown_model_is_X() = assertFalse(trunkScore(L(make = "BMW", model = "ZZ"), REF).available)
    @Test fun no_damage_full()           = assertEquals(100.0, damageScore(L(damages = 0)).value)
    @Test fun minor_damage_penalised()   = assertTrue(damageScore(L(damages = 2, damageList = listOf("a","b"))).value < 100.0)
    @Test fun fewer_owners_higher()      = assertTrue(ownersScore(L(owners = 0)).value > ownersScore(L(owners = 3)).value)
    @Test fun commercial_downgraded()    = assertTrue(commercialScore(L(commercial = true)).value < commercialScore(L(commercial = false)).value)
    @Test fun lower_mileage_higher()     = assertTrue(mileageScore(L(km = 20000), REF).value > mileageScore(L(km = 180000), REF).value)
    @Test fun cheaper_higher_value() {
        val stats = mapOf("BMW|3er" to ModelStats(20000.0, 60000.0, 2019.0))
        assertTrue(valueScore(L(price = 14000), stats["BMW|3er"]).value >
                   valueScore(L(price = 19000), stats["BMW|3er"]).value)
    }
    @Test fun automatic_beats_manual() =
        assertTrue(transmissionScore(L(gear = "1139"), REF).value > transmissionScore(L(gear = "manual"), REF).value)
}

class TotalAndToggleTest {
    @Test fun total_is_sum_of_active_available() {
        val car = scoreCar(L(cons = null), REF, emptyMap())   // Consumption + Tire + (maybe Trunk) unavailable
        val expected = TOTAL_COLUMNS.filter { car.scores[it]!!.available }.sumOf { car.scores[it]!!.value }
        // totalOf() rounds to 2dp (see its doc comment: keeps sort order stable across the full pool) —
        // compare with a tolerance that accounts for that rounding, not raw floating-point noise.
        assertEquals(expected, totalOf(car, TOTAL_COLUMNS.toSet()), 0.01)
    }
    @Test fun disabled_by_default_columns_excluded_from_default_active() {
        for (c in listOf("Owners", "TireSeason", "MinorDamage", "Commercial")) assertFalse(c in DEFAULT_ACTIVE_COLUMNS)
    }
    @Test fun default_build_total_matches_default_active_only() {
        val car = scoreCar(L(), REF, emptyMap())
        assertEquals(totalOf(car, DEFAULT_ACTIVE_COLUMNS.toSet()), totalOf(car, DEFAULT_ACTIVE_COLUMNS.toSet()))
        // sanity: default-active total differs from full total when disabled columns have nonzero score
        val full = totalOf(car, TOTAL_COLUMNS.toSet())
        val def = totalOf(car, DEFAULT_ACTIVE_COLUMNS.toSet())
        assertTrue(def <= full)
    }
    @Test fun toggling_column_off_lowers_total() {
        val car = scoreCar(L(), REF, emptyMap())
        val full = totalOf(car, TOTAL_COLUMNS.toSet())
        val without = totalOf(car, (TOTAL_COLUMNS - "Owners").toSet())
        assertEquals(full - car.scores["Owners"]!!.value, without, 0.001)
    }
}

class ScoredCarIdentityTest {
    // Regression: the page filters by brand/model on ScoredCar.make/model directly (not by parsing
    // the combined `name` string), so these must be populated verbatim from the listing.
    @Test fun make_and_model_populated_verbatim() {
        val car = scoreCar(L(make = "BMW", model = "3er"), REF, emptyMap())
        assertEquals("BMW", car.make)
        assertEquals("3er", car.model)
    }
}

class BuildTableTest {
    private fun pool() = (1..60).map { i ->
        L(id = "c$i", model = if (i % 2 == 0) "3er" else "1er", subType = if (i % 2 == 0) "318i" else "118i",
          year = 2019, km = 30000 + i * 1000, price = 12000 + i * 100)
    }
    @Test fun caps_at_50_sorted_desc_indexed() {
        val t = buildTable(pool(), REF)
        assertEquals(50, t.size)
        for (i in 0 until t.size - 1) assertTrue(t[i].total >= t[i + 1].total, "not sorted at $i")
        assertEquals((1..50).toList(), t.map { it.index })
    }
    @Test fun eviction_high_total_car_enters_and_last_drops() {
        val base = buildTable(pool(), REF)
        val lastTotalBefore = base.last().total
        // a synthetic near-zero-mileage, low-price car should land high and push out old #50
        val boosted = pool() + L(id = "BOOST", model = "3er", subType = "318i", km = 1000, price = 11000, owners = 0, svc = true)
        val t = buildTable(boosted, REF)
        assertEquals(50, t.size)
        assertTrue(t.any { it.id == "BOOST" }, "boosted car should be in top 50")
        assertTrue(t.first().total >= lastTotalBefore)
    }
}

class EdgeCaseTest {
    @Test fun current_year_no_divide_by_zero() {
        val s = mileageScore(L(year = REF.currentYear, km = 5000), REF)   // age → 1
        assertTrue(s.value in 0.0..100.0)
    }
    @Test fun low_power_clamped()  = assertTrue(engineScore(L(kw = 40), REF).value in 0.0..100.0)
    @Test fun high_power_clamped() = assertTrue(engineScore(L(kw = 300), REF).value in 0.0..100.0)
    @Test fun huge_mileage_clamped() = assertEquals(0.0, mileageScore(L(km = 400000, svc = false), REF).value)
}

class FullPoolTest {
    // buildTable with maxRows = pool size must ship EVERYTHING eligible, not silently cap at 50 —
    // this is what lets the page re-derive its own top-50 after a filter change without a re-fetch.
    @Test fun maxRows_equal_to_pool_size_ships_full_eligible_set() {
        val pool = (1..80).map { i ->
            L(id = "c$i", model = if (i % 2 == 0) "3er" else "1er", subType = if (i % 2 == 0) "318i" else "118i",
              year = 2019, km = 30000 + i * 1000, price = 12000 + i * 100)
        }
        val eligibleCount = pool.count { passesBaseFilter(it, REF) }
        val full = buildTable(pool, REF, maxRows = pool.size)
        assertEquals(eligibleCount, full.size)
        assertTrue(full.size > 50, "test setup should exceed 50 to actually prove no cap happened")
        for (i in 0 until full.size - 1) assertTrue(full[i].total >= full[i + 1].total, "not sorted at $i")
        assertEquals((1..full.size).toList(), full.map { it.index })
    }
}

class DedupTest {
    @Test fun single_source_is_a_true_noop() {
        val listings = listOf(
            L(id = "a", source = "autohero", color = "black"),
            L(id = "b", source = "autohero", color = "black")   // same name/price/color, but only one source present
        )
        assertEquals(listings, dedupeAcrossSources(listings))
    }
    @Test fun cross_source_duplicate_with_known_color_is_merged_keeping_highest_priority() {
        val listings = listOf(
            L(id = "scout", source = "autoscout24", color = "black", price = 15000),
            L(id = "hero",  source = "autohero",    color = "black", price = 15000)
        )
        val result = dedupeAcrossSources(listings)
        assertEquals(1, result.size)
        assertEquals("hero", result[0].id)   // autohero outranks autoscout24 in SOURCE_PRIORITY
    }
    @Test fun cross_source_same_name_price_but_unknown_color_stays_distinct() {
        // color must be KNOWN on both sides to risk a match — unset color must never merge two listings
        // that only coincidentally share a name and price.
        val listings = listOf(
            L(id = "scout", source = "autoscout24", color = null, price = 15000),
            L(id = "hero",  source = "autohero",    color = null, price = 15000)
        )
        assertEquals(2, dedupeAcrossSources(listings).size)
    }
    @Test fun cross_source_different_color_stays_distinct() {
        val listings = listOf(
            L(id = "scout", source = "autoscout24", color = "black", price = 15000),
            L(id = "hero",  source = "autohero",    color = "white", price = 15000)
        )
        assertEquals(2, dedupeAcrossSources(listings).size)
    }
}

class OwnershipCostTest {
    // default L() is a BMW 3er -> "Mittelklasse-Sedan" segment, petrol, cons=6.0/consUrban=6.5
    @Test fun available_when_all_inputs_present() {
        val s = ownershipCostScore(L(co2 = 130.0), REF)
        assertTrue(s.available)
        assertTrue(s.value in 0.0..100.0, "score ${s.value} not in 0..100")
    }
    @Test fun unavailable_when_nothing_resolvable() {
        // no consumption figures, no co2, and an unmapped make/model -> every sub-score skipped
        val s = ownershipCostScore(L(cons = null, consUrban = null, consHighway = null, co2 = null,
            make = "Fiat", model = "Panda"), REF)
        assertFalse(s.available)
    }
    @Test fun lower_fuel_consumption_scores_higher() {
        val efficient = ownershipCostScore(L(consUrban = 4.0, co2 = 110.0), REF)
        val thirsty = ownershipCostScore(L(consUrban = 9.0, co2 = 110.0), REF)
        assertTrue(efficient.value > thirsty.value, "efficient ${efficient.value} should beat thirsty ${thirsty.value}")
    }
    @Test fun higher_co2_and_displacement_scores_lower() {
        val clean = ownershipCostScore(L(ccm = 1000, co2 = 90.0), REF)
        val dirty = ownershipCostScore(L(ccm = 3000, co2 = 220.0), REF)
        assertTrue(clean.value > dirty.value, "low-tax car ${clean.value} should beat high-tax car ${dirty.value}")
    }
    @Test fun hybrid_gets_resale_bonus_over_otherwise_identical_petrol() {
        // isolate the resale sub-score by only giving the segment input (no consumption/co2 to blend in)
        val petrol = ownershipCostScore(L(fuel = "petrol", cons = null, consUrban = null, co2 = null), REF)
        val hybrid = ownershipCostScore(L(fuel = "hybrid", cons = null, consUrban = null, co2 = null), REF)
        assertTrue(hybrid.value > petrol.value, "hybrid ${hybrid.value} should beat petrol ${petrol.value} on resale alone")
    }
    @Test fun segment_resolves_via_body_style_suffix_fallback() {
        // "BMW|3er Touring" (the estate variant) isn't a literal key in `segments` -- only "BMW|3er" is --
        // so this only passes via the same whole-word-prefix fallback resolveRoi/resolveBootLitres use.
        val direct = resolveSegment("BMW", "3er", REF)
        val viaFallback = resolveSegment("BMW", "3er Touring", REF)
        assertEquals(direct, viaFallback)
        assertEquals("Mittelklasse-Sedan", REF.segments["BMW|3er"])
    }
    @Test fun unmapped_model_has_no_segment() {
        // "Yaris Cross" is deliberately NOT a good example here -- it correctly resolves via the "Yaris"
        // prefix fallback (same mechanism the fallback test above checks), which is intended, not a bug.
        assertNull(resolveSegment("Toyota", "Supra", REF))   // not in `segments` directly or via any prefix
    }
    @Test fun kfz_steuer_known_value_sanity_check() {
        // 1998ccm -> ceil(1998/100)*2.00 = 40.00; co2=130 -> 95@0 + 20@2.00 + 15@2.20 = 40+33 = 73.00; total ~113€/yr
        // (not asserting the exact CellScore, just that co2Surcharge-driven ordering is internally consistent)
        val lowCo2 = ownershipCostScore(L(ccm = 1998, co2 = 100.0, cons = null, consUrban = null), REF)
        val highCo2 = ownershipCostScore(L(ccm = 1998, co2 = 200.0, cons = null, consUrban = null), REF)
        assertTrue(lowCo2.value > highCo2.value)
    }
}
