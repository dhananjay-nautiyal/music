package com.music.music.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The accent — the one colour that carries the app's interactive marks: buttons,
 * sliders, progress, the selected segment, the switch when it is on.
 *
 * Each entry holds a value per theme rather than one value used twice. The light
 * theme paints on white, so its accent has to be dark enough for white text on
 * it; the dark theme paints on near-black and wants the accent lifted instead.
 * [onDark] is the foreground the dark variant was drawn against — white where
 * the accent is deep enough to carry it, black where it is pale — and is what a
 * filled control in the dark theme sets its text and icons to.
 *
 * The light variants all sit well under the luminance where white stops working
 * (the darkest is orange's, at about 0.16 against a crossover of 0.18), so the
 * light theme keeps a plain white [onPrimary] rather than carrying a second
 * foreground of its own.
 */
enum class AccentColor(
    /** The primary on the dark theme, and on AMOLED black with it. */
    val dark: Color,
    /** The primary on the light theme. */
    val light: Color,
    /** What sits on top of [dark] — see above. */
    val onDark: Color,
) {
    /** The brand blue the app has shipped with. The default on both platforms. */
    BLUE(Color(0xFF5B9CFF), Color(0xFF0B3D91), Color.White),

    RED(Color(0xFFFF5A70), Color(0xFFC2143A), Color.White),

    ORANGE(Color(0xFFF5822A), Color(0xFFB45309), Color.Black),

    GREEN(Color(0xFF34C759), Color(0xFF1E7B3C), Color.Black),

    TEAL(Color(0xFF2DD4BF), Color(0xFF0F766E), Color.Black),

    PURPLE(Color(0xFFA78BFA), Color(0xFF6D28D9), Color.Black),

    PINK(Color(0xFFFF7AB6), Color(0xFFBE185D), Color.Black),

    INDIGO(Color(0xFF7C7CFF), Color(0xFF4338CA), Color.Black),
    ;

    /** This accent's primary for the theme [darkTheme] is showing. */
    fun primary(darkTheme: Boolean): Color = if (darkTheme) dark else light
}
