package com.gekkotron.oikos.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class OikosColors(
    val isDark: Boolean,
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val card: Color,
    val cardBorder: Color,
    val cardOnStart: Color,
    val cardOnEnd: Color,
    val cardOnBorder: Color,
    val content: Color,
    val contentMuted: Color,
    val contentOnCardOn: Color,
    val chip: Color,
    val chipBorder: Color,
    val chipSelected: Color,
    val chipSelectedContent: Color,
    val connected: Color,
    val connectedContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val offline: Color,
    val offlineContainer: Color,
    val solar: Color,
    val water: Color,
    val grid: Color,
    val heat: Color,
    val shadow: Color,
)

private val Ink = Color(0xFFE8ECFF)
private val Night = Color(0xFF0B1020)
private val Amber = Color(0xFFFFC457)
private val Mint = Color(0xFF6FF0BF)
private val Rose = Color(0xFFFF7A9A)

/** Spec §5.1, mockup `visual-style.html` option A. */
val NightGlassColors = OikosColors(
    isDark = true,
    backgroundTop = Color(0xFF1D2B55),
    backgroundBottom = Night,
    card = Color.White.copy(alpha = 0.06f),
    cardBorder = Color.White.copy(alpha = 0.09f),
    cardOnStart = Amber.copy(alpha = 0.28f),
    cardOnEnd = Amber.copy(alpha = 0.06f),
    cardOnBorder = Amber.copy(alpha = 0.35f),
    content = Ink,
    contentMuted = Ink.copy(alpha = 0.65f),
    contentOnCardOn = Ink,
    chip = Color.White.copy(alpha = 0.07f),
    chipBorder = Color.Transparent,
    chipSelected = Ink,
    chipSelectedContent = Night,
    connected = Mint,
    connectedContainer = Color(0xFF50E6AA).copy(alpha = 0.15f),
    warning = Amber,
    warningContainer = Amber.copy(alpha = 0.15f),
    offline = Color(0xFFFF9AB2),
    offlineContainer = Rose.copy(alpha = 0.15f),
    solar = Amber,
    water = Color(0xFF58E1FF),
    grid = Color(0xFF8F7BFF),
    heat = Rose,
    shadow = Color.Transparent,
)

private val Paper = Color(0xFFF5F1EA)
private val Espresso = Color(0xFF1F1B16)

/** Spec §5.1, mockup `visual-style.html` option B. */
val WarmMinimalColors = OikosColors(
    isDark = false,
    backgroundTop = Paper,
    backgroundBottom = Paper,
    card = Color(0xFFFFFDF9),
    cardBorder = Color(0xFFEFE8DC),
    cardOnStart = Espresso,
    cardOnEnd = Espresso,
    cardOnBorder = Espresso,
    content = Espresso,
    contentMuted = Espresso.copy(alpha = 0.65f),
    contentOnCardOn = Paper,
    chip = Color.Transparent,
    chipBorder = Color(0xFFD9D0C2),
    chipSelected = Espresso,
    chipSelectedContent = Paper,
    connected = Color(0xFF3F6B3A),
    connectedContainer = Color(0xFFE6EFE3),
    warning = Color(0xFF8A5A00),
    warningContainer = Color(0xFFF6E7C8),
    offline = Color(0xFFA23B4F),
    offlineContainer = Color(0xFFF6DDE2),
    solar = Color(0xFFD98B3A),
    water = Color(0xFF2B8FB3),
    grid = Color(0xFF6A5ACD),
    heat = Color(0xFFC4526B),
    shadow = Color(0xFF3C280A).copy(alpha = 0.10f),
)
