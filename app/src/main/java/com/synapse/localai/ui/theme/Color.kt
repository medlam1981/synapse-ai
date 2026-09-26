package com.synapse.localai.ui.theme

import androidx.compose.ui.graphics.Color

// =========================================================================
// ATLAS-AI Dynamic Dual Theme Palette
// Night: Deep Space Black  |  Day: Crystal White
// =========================================================================

// ── Shared Accent Colors (both themes) ─────────────────────────────────
val AtlasGreen     = Color(0xFF00E676)  // Neon Emerald — primary action
val AtlasRed       = Color(0xFFFF2A4D)  // Crimson — error / AI badge
val AtlasBlue      = Color(0xFF007BFF)  // Electric Blue — borders / links
val AtlasBlueGlow  = Color(0xFF00D2FF)  // Neon Cyan — highlights
val AtlasCyan      = Color(0xFF00E5FF)  // Electric Cyan — dark primary
val AtlasCardBorder = Color(0x33007BFF) // Soft blue border

// ── NIGHT THEME: "Deep Space" ─────────────────────────────────────────────
// Background: #0A0A12 — near-black with cosmic tint
// Primary: Electric Cyan #00E5FF
val NightBackground         = Color(0xFF0A0A12) // Deep Space Black
val NightSurface            = Color(0xFF12121E) // Elevated card
val NightSurfaceVariant     = Color(0xFF1C1C2E) // Container
val NightSurfaceContainer   = Color(0xFF16162A) // Mid-level container
val NightOnBackground       = Color(0xFFE8EAF6) // Almost-white text
val NightOnSurface          = Color(0xFFE8EAF6)
val NightOnSurfaceVariant   = Color(0xFFB0B8D0) // Muted secondary text
val NightPrimary            = Color(0xFF00E5FF) // Electric Cyan
val NightOnPrimary          = Color(0xFF000000)
val NightPrimaryContainer   = Color(0xFF00374A)
val NightOnPrimaryContainer = Color(0xFFB3F0FF)
val NightSecondary          = Color(0xFF7C4DFF) // Electric Purple
val NightOnSecondary        = Color(0xFFFFFFFF)
val NightTertiary           = Color(0xFFFF2A4D) // Crimson accent
val NightOutline            = Color(0x4400E5FF) // Cyan border glow
val NightOutlineVariant     = Color(0x221C1C2E)
val NightError              = Color(0xFFFF4444)
val NightGlow               = Color(0x1A00E5FF) // Subtle glow layer

// ── DAY THEME: "Crystal White" ─────────────────────────────────────────
// Background: #FFFFFF — Pure white canvas
// Primary: Indigo Blue #2563EB
val DayBackground         = Color(0xFFFFFFFF) // Pure White
val DaySurface            = Color(0xFFF8FAFF) // Soft blue-tinted white
val DaySurfaceVariant     = Color(0xFFEEF2FF) // Light indigo tint container
val DaySurfaceContainer   = Color(0xFFE8EDFF) // Slightly deeper
val DayOnBackground       = Color(0xFF0A0A1A) // Near-black text
val DayOnSurface          = Color(0xFF0A0A1A)
val DayOnSurfaceVariant   = Color(0xFF4B5263) // Muted secondary text
val DayPrimary            = Color(0xFF2563EB) // Modern Indigo Blue
val DayOnPrimary          = Color(0xFFFFFFFF)
val DayPrimaryContainer   = Color(0xFFDBEAFE) // Light blue container
val DayOnPrimaryContainer = Color(0xFF1E3A8A)
val DaySecondary          = Color(0xFF7C3AED) // Electric Violet
val DayOnSecondary        = Color(0xFFFFFFFF)
val DayTertiary           = Color(0xFFE53935) // Rich Red accent
val DayOutline            = Color(0xFFCBD5E1) // Soft slate border
val DayOutlineVariant     = Color(0xFFE2E8F0)
val DayError              = Color(0xFFDC2626)

// ── Backward-compatible aliases (used across the app) ──────────────────
// Legacy obsidian tokens → now point to Night palette
val AtlasMaroonBackground       = NightBackground
val AtlasOliveBackground        = NightBackground
val AtlasSurfaceDark            = NightSurface
val AtlasSurfaceElevated        = NightSurfaceVariant
val ObsidianBackground          = NightBackground
val ObsidianSurface             = NightSurface
val ObsidianSurfaceTranslucent  = NightSurface
val ObsidianOnSurface           = NightOnSurface
val ObsidianOnSurfaceVariant    = NightOnSurfaceVariant
val ObsidianPrimary             = NightPrimary
val ObsidianSecondary           = NightSecondary
val ObsidianTertiary            = NightTertiary
val ObsidianOutline             = NightOutline
val ObsidianOutlineVariant      = NightOutlineVariant
val ObsidianError               = NightError

// Legacy porcelain tokens → now point to Day palette
val PorcelainBackground         = DayBackground
val PorcelainSurface            = DaySurface
val PorcelainSurfaceElevated    = DaySurface
val PorcelainOnSurface          = DayOnSurface
val PorcelainOnSurfaceVariant   = DayOnSurfaceVariant
val PorcelainPrimary            = DayPrimary
val PorcelainSecondary          = DaySecondary
val PorcelainTertiary           = DayTertiary
val PorcelainOutline            = DayOutline
val PorcelainOutlineVariant     = DayOutlineVariant
val PorcelainError              = DayError

// Dark/Light aliases
val DarkBackground       = NightBackground
val DarkSurface          = NightSurface
val DarkSurfaceVariant   = NightSurfaceVariant
val DarkOnSurface        = NightOnSurface
val DarkOnSurfaceVariant = NightOnSurfaceVariant
val DarkPrimary          = NightPrimary
val DarkSecondary        = NightSecondary
val DarkAccent           = NightSecondary
val DarkError            = NightError
val DarkOutline          = NightOutline
val DarkOutlineVariant   = NightOutlineVariant

val LightBackground       = DayBackground
val LightSurface          = DaySurface
val LightSurfaceVariant   = DaySurfaceVariant
val LightOnSurface        = DayOnSurface
val LightOnSurfaceVariant = DayOnSurfaceVariant
val LightPrimary          = DayPrimary
val LightSecondary        = DaySecondary
val LightAccent           = DaySecondary
val LightError            = DayError
val LightOutline          = DayOutline
val LightOutlineVariant   = DayOutlineVariant

// Glow / ambient elevation tokens
val DarkGlowAzure      = Color(0x2200E5FF)
val DarkGlowIndigo     = Color(0x147C4DFF)
val LightAmbientShadow = Color(0x120A0A1A)

// Neon aliases
val NeonCyan    = NightPrimary
val NeonPurple  = NightSecondary
val NeonMagenta = Color(0xFF7C3AED)
val ElectricCyan  = NightPrimary
val DeepIndigo    = DayPrimary
val ElectricBlue  = AtlasBlue

// Syntax Highlighting (unchanged)
val CodeKeyword         = Color(0xFFFF7B72)
val CodeVariable        = Color(0xFF79C0FF)
val CodeFunction        = Color(0xFF79C0FF)
val CodeString          = Color(0xFFA5D6FF)
val CodeComment         = Color(0xFF8B949E)
val CodeBackgroundDark  = Color(0xFF0B0F17)
val CodeBackgroundLight = Color(0xFFF8FAFC)
val CodeBackground      = Color(0xFF0B0F17)
