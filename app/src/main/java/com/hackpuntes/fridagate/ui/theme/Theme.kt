package com.hackpuntes.fridagate.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
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

@Composable
fun FridagateTheme(content: @Composable () -> Unit) {
    // We always use the dark hacker theme — dynamic color and light mode are disabled
    // Dynamic color (Android 12+) would override our custom palette, so we skip it
    MaterialTheme(
        colorScheme = HackerColorScheme,
        typography = Typography,
        content = content
    )
}
