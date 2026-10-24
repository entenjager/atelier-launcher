package de.atelierlauncher.app

// ============================================================================
//  Einstellungen, die fuer die ganze App gelten
// ============================================================================
object Variante {
    /** eigener Lautstaerke-Regler + Randgesten (Bedienungshilfe): erst als geladene Erweiterung */
    val lautRegler get() = Erweiterung.randgesten
    /** Name, wo die App sich selbst nennen muss - kommt aus dem App-Namen (beim Start gesetzt), steht nicht im Code */
    @Volatile var appName: String = "Launcher"
}
