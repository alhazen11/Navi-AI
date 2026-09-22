package com.apps.naviai.detection.distance

/**
 * How much to trust a single-frame height-based distance estimate for a
 * given class. Real-world object heights vary (e.g. dogs from chihuahua to
 * great dane); this is surfaced to the UI/TTS so estimates are never
 * presented as more precise than they are.
 */
enum class DimensionReliability { HIGH, MEDIUM, LOW }

data class ObjectDimension(
    val label: String,
    val typicalHeightMeters: Float,
    val typicalWidthMeters: Float?,
    val reliability: DimensionReliability,
    val notes: String
)

/**
 * Reference real-world sizes used by [DistanceEstimator]. These are rough,
 * population-average figures, NOT measurements -- distance estimates
 * derived from them are always approximate and must be labeled as such
 * (see DistanceEstimate.method and DistanceEstimate.confidence).
 */
object ObjectDimensions {
    private val database: Map<String, ObjectDimension> = listOf(
        ObjectDimension("person", 1.70f, 0.50f, DimensionReliability.HIGH,
            "Average adult standing height; shorter for children, unreliable if seated, crouched, or occluded."),
        ObjectDimension("bicycle", 1.00f, 1.70f, DimensionReliability.LOW,
            "Height only reliable when viewed roughly front-on; a side view mostly shows length, not height."),
        ObjectDimension("car", 1.50f, 1.80f, DimensionReliability.MEDIUM,
            "Typical sedan; SUVs and vans run taller. Front/rear views are more reliable than oblique angles."),
        ObjectDimension("motorcycle", 1.10f, 2.00f, DimensionReliability.LOW,
            "Highly orientation-dependent; rider posture also changes apparent height."),
        ObjectDimension("bus", 3.20f, 2.50f, DimensionReliability.MEDIUM,
            "City bus average; double-deckers and coaches vary significantly."),
        ObjectDimension("train", 3.50f, 3.00f, DimensionReliability.LOW,
            "Only the visible near-side car is measured; rarely fully framed at close range."),
        ObjectDimension("truck", 2.80f, 2.50f, DimensionReliability.LOW,
            "Extremely variable: pickup trucks vs. box trucks vs. semis."),
        ObjectDimension("boat", 1.50f, 2.00f, DimensionReliability.LOW,
            "Freeboard height varies enormously by vessel type."),
        ObjectDimension("fire hydrant", 0.60f, 0.30f, DimensionReliability.HIGH,
            "Standardized municipal dimensions, low variance."),
        ObjectDimension("stop sign", 0.75f, 0.75f, DimensionReliability.HIGH,
            "Standardized octagonal sign face; bounding box typically excludes the pole."),
        ObjectDimension("parking meter", 1.20f, 0.20f, DimensionReliability.MEDIUM,
            "Post-mounted meters vary by municipality."),
        ObjectDimension("traffic light", 0.90f, 0.35f, DimensionReliability.LOW,
            "Bounding box usually covers only the signal head, not the pole/mast arm."),
        ObjectDimension("bench", 0.45f, 1.50f, DimensionReliability.MEDIUM,
            "Seat height is fairly standard; backrests add variable height."),
        ObjectDimension("bird", 0.20f, 0.30f, DimensionReliability.LOW,
            "Extreme species variation; treat as a rough presence signal only."),
        ObjectDimension("cat", 0.25f, 0.40f, DimensionReliability.MEDIUM,
            "Fairly consistent across breeds when standing."),
        ObjectDimension("dog", 0.50f, 0.60f, DimensionReliability.LOW,
            "Extreme breed variation from small toy breeds to great danes."),
        ObjectDimension("horse", 1.60f, 2.00f, DimensionReliability.MEDIUM,
            "Withers-to-ground height; breed and posture add variance."),
        ObjectDimension("sheep", 0.80f, 1.00f, DimensionReliability.MEDIUM, "Standing height at the shoulder."),
        ObjectDimension("cow", 1.40f, 1.80f, DimensionReliability.MEDIUM, "Standing height at the shoulder."),
        ObjectDimension("backpack", 0.45f, 0.30f, DimensionReliability.LOW,
            "Varies widely by size and how it is worn/carried."),
        ObjectDimension("umbrella", 0.90f, 1.00f, DimensionReliability.LOW, "Open-umbrella dimensions; closed differs greatly."),
        ObjectDimension("handbag", 0.30f, 0.25f, DimensionReliability.LOW, "Wide size range."),
        ObjectDimension("suitcase", 0.55f, 0.35f, DimensionReliability.MEDIUM, "Standard carry-on to mid-size checked bag."),
        ObjectDimension("skateboard", 0.15f, 0.80f, DimensionReliability.LOW, "Deck length dominates apparent size, not height."),
        ObjectDimension("surfboard", 0.30f, 1.80f, DimensionReliability.LOW, "Length varies with board type."),
        ObjectDimension("chair", 0.85f, 0.50f, DimensionReliability.MEDIUM, "Seat-plus-backrest standing height."),
        ObjectDimension("couch", 0.90f, 1.80f, DimensionReliability.MEDIUM, "Two-to-three seat sofa average."),
        ObjectDimension("potted plant", 0.50f, 0.35f, DimensionReliability.LOW, "Extremely variable by plant/pot size."),
        ObjectDimension("bed", 0.60f, 1.50f, DimensionReliability.MEDIUM, "Mattress-top height; frame style varies."),
        ObjectDimension("dining table", 0.75f, 1.20f, DimensionReliability.MEDIUM, "Standard table height is fairly consistent."),
        ObjectDimension("toilet", 0.40f, 0.40f, DimensionReliability.MEDIUM, "Bowl height, fairly standardized."),
        ObjectDimension("tv", 0.50f, 0.90f, DimensionReliability.MEDIUM, "Varies with screen size; 40-55in class assumed."),
        ObjectDimension("laptop", 0.25f, 0.33f, DimensionReliability.MEDIUM, "Open-lid height varies with hinge angle."),
        ObjectDimension("refrigerator", 1.75f, 0.70f, DimensionReliability.HIGH, "Standard full-size fridge."),
        ObjectDimension("oven", 0.90f, 0.60f, DimensionReliability.MEDIUM, "Standard range/oven height."),
        ObjectDimension("sink", 0.30f, 0.50f, DimensionReliability.LOW, "Bounding box height depends heavily on crop."),
        ObjectDimension("book", 0.23f, 0.15f, DimensionReliability.MEDIUM, "Standard hardcover/paperback height."),
        ObjectDimension("vase", 0.30f, 0.15f, DimensionReliability.LOW, "Wide size range."),
        ObjectDimension("teddy bear", 0.30f, 0.25f, DimensionReliability.LOW, "Wide size range."),
        ObjectDimension("wine glass", 0.20f, 0.08f, DimensionReliability.MEDIUM, "Standard stemware height."),
        ObjectDimension("cup", 0.10f, 0.08f, DimensionReliability.MEDIUM, "Standard mug/cup height."),
        ObjectDimension("bottle", 0.25f, 0.08f, DimensionReliability.MEDIUM, "Standard beverage bottle height.")
    ).associateBy { it.label }

    fun forLabel(label: String): ObjectDimension? = database[label.lowercase()]

    val supportedLabels: Set<String> get() = database.keys
}
