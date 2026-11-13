package com.marcusrosado.stamp.data

/** What the map marks: whole countries, the regions inside them, or cities. The key is what is saved. */
enum class MapView(val key: String, val label: String) {
    Countries("countries", "Countries"),
    Regions("regions", "Regions"),
    Cities("cities", "Cities");

    companion object {
        fun of(key: String?): MapView = entries.firstOrNull { it.key == key } ?: Countries
    }
}
