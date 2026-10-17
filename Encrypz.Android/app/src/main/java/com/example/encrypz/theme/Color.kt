package com.example.encrypz.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Core Encrypz.com Theme Palette
val EncrypzBlack = Color(0xFF0A0A0A)          // --bg-color: #0a0a0a
val EncrypzDeepBlack = Color(0xFF050505)      // terminal/spec box background
val EncrypzNavyGlass = Color(0xFF020D1C)      // theme-color & floating navbar #020d1c
val EncrypzSurface = Color(0xFF111111)        // --bg-color-alt: #111111 (bento cards & panels)
val EncrypzPanel = Color(0xFF181A1F)          // auth card surface #181a1f
val EncrypzPanelAlt = Color(0xFF16181D)       // secondary panel surface #16181d
val EncrypzInput = Color(0xFF111317)          // input field background #111317

// Brand Blues & Accents
val EncrypzBlue = Color(0xFF3B82F6)           // --primary: #3b82f6
val EncrypzBlueBright = Color(0xFF60A5FA)     // gradient end: #60a5fa
val EncrypzBlueDark = Color(0xFF2563EB)       // --primary-hover: #2563eb
val EncrypzBlueSoft = Color(0xFF93C5FD)       // monospace / code highlight #93c5fd
val EncrypzSecondaryBg = Color(0x1A3B82F6)    // --secondary: rgba(59, 130, 246, 0.1)

// Ambient Glows from encrypz.com hero
val EncrypzGlowBlue = Color(0x263B82F6)       // #3b82f626
val EncrypzGlowPurple = Color(0xFFC084FC)     // .glow-1: #c084fc
val EncrypzGlowCyan = Color(0xFF06B6D4)       // .glow-2: #06b6d4

// Text Colors
val EncrypzText = Color(0xFFFFFFFF)           // --text-dark / --text-light: #ffffff
val EncrypzMuted = Color(0xFFA1A1AA)          // --text-muted: #a1a1aa
val EncrypzSubtleText = Color(0xFF717786)     // footer / hint text

// Borders & Dividers
val EncrypzBorder = Color(0xFF222222)         // --border-color: #222222
val EncrypzInputBorder = Color(0xFF2E333E)    // input border #2e333e
val EncrypzBorderGlow = Color(0x803B82F6)     // --border-glow: rgba(59, 130, 246, 0.5)
val EncrypzGlassBorder = Color(0x1AFFFFFF)    // rgba(255, 255, 255, 0.1)

// Status Colors
val EncrypzSuccess = Color(0xFF10B981)        // --success-color: #10b981
val EncrypzEmeraldBright = Color(0xFF34D399)  // .badge-active: #34d399
val EncrypzSuccessBg = Color(0x2610B981)      // #10b98126
val EncrypzSuccessBorder = Color(0x4D10B981)  // #10b9814d

val EncrypzDanger = Color(0xFFEF4444)         // --danger-color: #ef4444
val EncrypzDangerLight = Color(0xFFF87171)    // alert error text
val EncrypzDangerBg = Color(0x1FEF4444)       // rgba(239, 68, 68, 0.12)
val EncrypzDangerBorder = Color(0x59EF4444)   // rgba(239, 68, 68, 0.35)

val EncrypzWarning = Color(0xFFF59E0B)
val EncrypzPurple = Color(0xFFA855F7)         // video category accent

// Reusable Gradients matching encrypz.com
val EncrypzPrimaryGradient = Brush.linearGradient(
    colors = listOf(EncrypzBlue, EncrypzBlueBright)
)

val EncrypzFeaturedCardGradient = Brush.verticalGradient(
    colors = listOf(Color(0x1F3B82F6), EncrypzSurface)
)
