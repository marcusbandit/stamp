package com.marcusrosado.stamp.ui

/**
 * Which screen shows where, in every posture. Kept apart from the layouts so the rules can be
 * tested without drawing anything.
 */
object PaneRules {
    /** What sits beside the map. */
    enum class Side { None, Card, Passport }

    /** What covers the map for a tab. The map stays built under each of them. */
    enum class Cover { None, Passport, Settings }

    /**
     * Beside the map: the chosen country's card on a big screen, flat or bent, and the passport
     * on the map's other half when the screen is bent with nothing chosen. A phone shows the
     * card over the map instead, so nothing sits beside it.
     */
    fun side(bent: Boolean, wideFlat: Boolean, selected: Boolean): Side = when {
        selected && (bent || wideFlat) -> Side.Card
        bent -> Side.Passport
        else -> Side.None
    }

    /** Every tab but the map covers it with its own screen, in every posture. */
    fun cover(tab: Tab): Cover = when (tab) {
        Tab.Map -> Cover.None
        Tab.Passport -> Cover.Passport
        Tab.Settings -> Cover.Settings
    }

    /**
     * Where back goes from a tab. A tab reached by a jump from another one, such as "show on
     * map" from Settings, returns there; any other tab returns to the map; only the map with
     * nowhere to return to lets back leave the app. Null means back leaves the app.
     */
    fun back(tab: Tab, cameFrom: Tab?): Tab? = when {
        cameFrom != null && cameFrom != tab -> cameFrom
        tab != Tab.Map -> Tab.Map
        else -> null
    }

    /** The tabs, without the icons and labels that [Destination] carries for drawing. */
    enum class Tab { Map, Passport, Settings }
}

val Destination.tab: PaneRules.Tab get() = PaneRules.Tab.valueOf(name)
fun PaneRules.Tab.destination(): Destination = Destination.valueOf(name)
