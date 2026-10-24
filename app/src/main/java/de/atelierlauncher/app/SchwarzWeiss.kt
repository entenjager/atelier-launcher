package de.atelierlauncher.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow

// ============================================================================
//  Schwarz & Weiss: ganz schlicht. Nur Schwarz und Weiss - kein Grau, keine Zwischentoene,
//  keine Wellen, keine Kartenflaechen, kein Schatten, eine nackte Uhr.
// ============================================================================
object SW {
    @Volatile var an = false
}

fun swPalette(dunkel: Boolean) = if (dunkel) Palette(
    dunkel = true,
    text = Color.White, leise = Color.White,
    // "linie" ist auch die Flaeche von Eingabefeldern und Auswahl-Chips -> durchsichtig, sonst Weiss auf Weiss
    karte = Color.Black, linie = Color.Transparent,
    akzent = Color.White, aufAkzent = Color.Black,
    grund = Color.Black, schleier = Color.Black,
    schatten = Shadow(Color.Transparent, Offset.Zero, 0f),
    warnung = Color.White,
) else Palette(
    dunkel = false,
    text = Color.Black, leise = Color.Black,
    karte = Color.White, linie = Color.Transparent,
    akzent = Color.Black, aufAkzent = Color.White,
    grund = Color.White, schleier = Color.White,
    schatten = Shadow(Color.Transparent, Offset.Zero, 0f),
    warnung = Color.Black,
)
