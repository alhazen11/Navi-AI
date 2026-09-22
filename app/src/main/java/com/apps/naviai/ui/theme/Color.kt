package com.apps.naviai.ui.theme

import androidx.compose.ui.graphics.Color

// NAVI AI dark, high-contrast palette. Chosen for accessibility first:
// a near-black background reduces glare/eye strain and maximizes contrast
// against the light accent colors used for text, controls, and the
// detection overlay.
val NaviBackground = Color(0xFF0A0C10)
val NaviSurface = Color(0xFF14171D)
val NaviSurfaceVariant = Color(0xFF1E222B)
val NaviPrimary = Color(0xFF4C8DFF)
val NaviOnPrimary = Color(0xFF00174D)
val NaviOnSurface = Color(0xFFF4F6FA)
val NaviOnSurfaceMuted = Color(0xFFB7C0D1)
val NaviOutline = Color(0xFF3A4150)

// Risk-level colors, chosen for both semantic meaning and contrast against
// NaviBackground/NaviSurface -- also referenced by RiskIndicator.
val RiskSafe = Color(0xFF4CD37A)
val RiskLow = Color(0xFFB6D94C)
val RiskMedium = Color(0xFFF5C518)
val RiskHigh = Color(0xFFFF9A3C)
val RiskCritical = Color(0xFFFF4C4C)
