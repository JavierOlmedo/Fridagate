package com.hackpuntes.fridagate.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Fridagate always uses the dark hacker theme — no light mode.
 *
 * Material3 darkColorScheme maps our custom colors to semantic roles:
 *
 *  primary          → main interactive color (buttons, active tabs, icons)
 *  onPrimary        → text/icon color ON TOP of primary colored elements
 *  primaryContainer → background of highlighted containers (e.g., selected chip)
 *  onPrimaryContainer → text on primaryContainer
 *
 *  secondary        → secondary actions and accents
 *  onSecondary      → text on secondary elements
 *
 *  background       → the screen background
 *  onBackground     → text drawn directly on background
 *
 *  surface          → card / sheet / dialog backgrounds
 *  onSurface        → text on surface elements
 *  surfaceVariant   → slightly different surface (e.g., input field background)
 *  onSurfaceVariant → muted text on surface (subtitles, placeholders)
 *
 *  error            → error states, destructive actions
 *  onError          → text on error elements
 *  errorContainer   → background of error banners/cards
 *  onErrorContainer → text on error containers
 */
private val HackerColorScheme = darkColorScheme(
    // ── Primary — electric cyan ──────────────────────────────────────────────
    primary              = CyanPrimary,       // #00D4FF — buttons, active indicators
    onPrimary            = BackgroundDark,    // Dark text on cyan buttons (readable)
    primaryContainer     = SurfaceVariant,    // Container highlighted with primary
    onPrimaryContainer   = CyanPrimary,       // Cyan text inside primary containers

    // ── Secondary — blue ─────────────────────────────────────────────────────
    secondary            = BluePrimary,       // #0070FF — secondary buttons, links
    onSecondary          = TextPrimary,       // White text on blue
    secondaryContainer   = SurfaceVariant,
    onSecondaryContainer = BluePrimary,

    // ── Tertiary — green (status/success) ────────────────────────────────────
    tertiary             = GreenSuccess,      // #00FF88 — success indicators
    onTertiary           = BackgroundDark,
    tertiaryContainer    = SurfaceVariant,
    onTertiaryContainer  = GreenSuccess,

    // ── Backgrounds ──────────────────────────────────────────────────────────
    background           = BackgroundDark,    // #080D18 — deepest navy black
    onBackground         = TextPrimary,       // White text on background

    // ── Surfaces (cards, sheets, dialogs) ────────────────────────────────────
    surface              = SurfaceDark,       // #0F1623 — card backgrounds
    onSurface            = TextPrimary,       // White text on cards
    surfaceVariant       = SurfaceVariant,    // #141C2E — input fields, chips
    onSurfaceVariant     = TextSecondary,     // #8A9BC0 — muted/subtitle text

    // ── Errors ───────────────────────────────────────────────────────────────
    error                = RedError,          // #FF3B5C — error text and icons
    onError              = TextPrimary,
    errorContainer       = Color(0xFF2A0A12), // Very dark red — error card bg
    onErrorContainer     = RedError,

    // ── Outlines & dividers ──────────────────────────────────────────────────
    outline              = BorderColor,       // #1E2D45 — card borders, dividers
    outlineVariant       = SurfaceVariant
)

private val LightColorScheme = lightColorScheme(
    primary              = CyanPrimaryDark,
    onPrimary            = Color.White,
    primaryContainer     = Color(0xFFE0F7FF),
    onPrimaryContainer   = CyanPrimaryDark,

    secondary            = BluePrimaryDark,
    onSecondary          = Color.White,
    secondaryContainer   = Color(0xFFDDE8FF),
    onSecondaryContainer = BluePrimaryDark,

    tertiary             = Color(0xFF007A42),
    onTertiary           = Color.White,

    background           = Color(0xFFF4F6FA),
    onBackground         = Color(0xFF0D1117),

    surface              = Color.White,
    onSurface            = Color(0xFF0D1117),
    surfaceVariant       = Color(0xFFE8EDF5),
    onSurfaceVariant     = Color(0xFF4A5568),

    error                = RedError,
    onError              = Color.White,
    errorContainer       = Color(0xFFFFE8EC),
    onErrorContainer     = Color(0xFF9B1C34),

    outline              = Color(0xFFCDD5E0),
    outlineVariant       = Color(0xFFE8EDF5)
)

@Composable
fun FridagateTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) HackerColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}
