package com.example.util

data class CatalogPreset(
    val id: String,
    val label: String,
    val description: String = "",
    val country: String? = null,
    val countries: List<String> = emptyList(),
    val genre: String? = null,
    val language: String? = null
)

object RegionalCategoryConfig {
    /** Title of the Home shelf. Kept for the Home feed; Browse uses [PRESET_INDIA]. */
    const val ROW_TITLE = "Bollywood"

    const val PRESET_ALL = "all"
    const val PRESET_INDIA = "india"
    const val PRESET_US_UK_CA_AU = "us_uk_ca_au"

    /** Genre token the API uses for 18+ titles. Applied as a lens, not a preset. */
    const val ADULT_GENRE = "Adult"

    val PRESETS = listOf(
        CatalogPreset(
            id = PRESET_ALL,
            label = "All",
            description = "Everything in this catalog."
        ),
        CatalogPreset(
            id = PRESET_INDIA,
            label = "India",
            description = "Indian titles, including Hindi, Tamil, Telugu, Malayalam and more.",
            country = "India"
        ),
        CatalogPreset(
            id = PRESET_US_UK_CA_AU,
            label = "US, UK, CA, AU",
            description = "Titles from these countries. This is not a language filter.",
            countries = listOf("United States", "United Kingdom", "Canada", "Australia")
        )
    )

    /** Country/region presets. Adult is a separate lens and can be combined with these. */
    val REGION_PRESETS: List<CatalogPreset> = PRESETS

    fun presetById(id: String?): CatalogPreset =
        PRESETS.firstOrNull { it.id == id } ?: PRESETS.first()

    fun regionPresets(): List<CatalogPreset> = REGION_PRESETS

    /** Exact years offered by the Browse filter sheet, newest first. */
    val YEAR_OPTIONS: List<String> = run {
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        (currentYear downTo 1950).map(Int::toString)
    }
}
