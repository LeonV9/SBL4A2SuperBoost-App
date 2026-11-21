package de.sbl4a2.superboost

/**
 * Presets. Die Equalizer-Kurve ist als Funktion der Frequenz definiert, damit sie auf
 * Geräte mit beliebiger Anzahl/Lage der Equalizer-Bänder passt (meist 5 Bänder).
 * Alle Presets bleiben bewusst bei <= 150 % Verstärkung (siehe MainActivity.SAFE_LIMIT).
 */
object Presets {

    class Preset(
        val name: String,
        val gainPercent: Int,
        val bassPercent: Int,
        val treblePercent: Int,
        val curveDb: (hz: Int) -> Float
    )

    val all: List<Preset> = listOf(
        Preset("Normal", 100, 0, 0) { 0f },
        Preset("Bass", 115, 70, -10) { hz ->
            when {
                hz <= 150 -> 4f
                hz <= 400 -> 2f
                hz <= 2000 -> 0f
                else -> -1f
            }
        },
        Preset("Clear Sound", 110, 0, 55) { hz ->
            when {
                hz <= 150 -> -2f
                hz <= 1000 -> -1f
                hz <= 4000 -> 2f
                else -> 3f
            }
        },
        Preset("Extra Boost", 150, 50, 30) { hz ->
            when {
                hz <= 150 -> 3f
                hz <= 1000 -> 1f
                else -> 2f
            }
        }
    )
}
