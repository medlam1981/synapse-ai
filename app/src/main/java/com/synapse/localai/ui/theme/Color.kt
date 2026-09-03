package com.synapse.localai.ui.theme

import androidx.compose.ui.graphics.Color

// =========================================================================
// 1. Dark Theme Palette (Modern Cyber Obsidian)
// =========================================================================
val ObsidianBackground = Color(0xFF0B0F17) // Deep space obsidian canvas
val ObsidianSurface = Color(0xFF111827) // Elevated slate-navy surface
val ObsidianSurfaceTranslucent = Color(0xFF111827) // Solid elevated slate-navy surface
val ObsidianOnSurface = Color(0xFFF8FAFC) // Crisp white primary labels
val ObsidianOnSurfaceVariant = Color(0xFF94A3B8) // Cool slate secondary telemetry
val ObsidianPrimary = Color(0xFF38BDF8) // Modern vibrant sky azure
val ObsidianSecondary = Color(0xFF818CF8) // Electric soft indigo
val ObsidianTertiary = Color(0xFFA78BFA) // Twilight violet highlight
val ObsidianOutline = Color(0xFF243044) // Crisp 1dp border
val ObsidianOutlineVariant = Color(0xFF334155) // High-contrast cadre border
val ObsidianError = Color(0xFFEF4444)

// =========================================================================
// 2. Light Theme Palette (Modern Porcelain Minimalist)
// =========================================================================
val PorcelainBackground = Color(0xFFF4F6F9) // Clean, airy porcelain canvas
val PorcelainSurface = Color(0xFFFFFFFF) // Pure elevated white surfaces
val PorcelainSurfaceElevated = Color(0xFFFFFFFF)
val PorcelainOnSurface = Color(0xFF0F172A) // High-contrast deep slate
val PorcelainOnSurfaceVariant = Color(0xFF475569) // Muted charcoal telemetry
val PorcelainPrimary = Color(0xFF2563EB) // Modern royal blue
val PorcelainSecondary = Color(0xFF4F46E5) // Electric indigo
val PorcelainTertiary = Color(0xFF7C3AED) // Deep violet
val PorcelainOutline = Color(0xFFCBD5E1) // Crisp 1dp card border
val PorcelainOutlineVariant = Color(0xFFE2E8F0) // Subtle micro-divider
val PorcelainError = Color(0xFFDC2626)

// =========================================================================
// Ambient Outward Elevation Tokens
// =========================================================================
val DarkGlowAzure = Color(0x2E3882F6) // rgba(56, 130, 246, 0.18)
val DarkGlowIndigo = Color(0x146366F1) // rgba(99, 102, 241, 0.08)
val LightAmbientShadow = Color(0x120F172A) // rgba(15, 23, 42, 0.07)

// Backward compatible aliases
val DarkBackground = ObsidianBackground
val DarkSurface = ObsidianSurface
val DarkSurfaceVariant = Color(0xFF1E293B)
val DarkOnSurface = ObsidianOnSurface
val DarkOnSurfaceVariant = ObsidianOnSurfaceVariant
val DarkPrimary = ObsidianPrimary
val DarkSecondary = ObsidianSecondary
val DarkAccent = ObsidianSecondary
val DarkError = ObsidianError
val DarkOutline = ObsidianOutline
val DarkOutlineVariant = ObsidianOutlineVariant

val LightBackground = PorcelainBackground
val LightSurface = PorcelainSurface
val LightSurfaceVariant = Color(0xFFF1F5F9)
val LightOnSurface = PorcelainOnSurface
val LightOnSurfaceVariant = PorcelainOnSurfaceVariant
val LightPrimary = PorcelainPrimary
val LightSecondary = PorcelainSecondary
val LightAccent = PorcelainSecondary
val LightError = PorcelainError
val LightOutline = PorcelainOutline
val LightOutlineVariant = PorcelainOutlineVariant

val NeonCyan = ObsidianPrimary
val NeonPurple = ObsidianSecondary
val NeonMagenta = Color(0xFF7C3AED)
val ElectricCyan = ObsidianPrimary
val DeepIndigo = PorcelainPrimary
val ElectricBlue = ObsidianPrimary

// Syntax Highlighting Colors
val CodeKeyword = Color(0xFFFF7B72)
val CodeVariable = Color(0xFF79C0FF)
val CodeFunction = Color(0xFF79C0FF)
val CodeString = Color(0xFFA5D6FF)
val CodeComment = Color(0xFF8B949E)
val CodeBackgroundDark = Color(0xFF0B0F17)
val CodeBackgroundLight = Color(0xFFF8FAFC)
val CodeBackground = Color(0xFF0B0F17)
