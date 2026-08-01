package com.clarifi.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * ClariFi's icon set, transcribed from the inline SVGs in templates/index.html.
 *
 * Material's own icons are a different family - heavier, filled, different corner
 * treatment - so using them would quietly change the app's character. These are
 * the same 24×24, 2px-stroke, round-capped outlines the desktop draws.
 *
 * Colour is left black on purpose: `Icon(...)` tints the whole vector, so the
 * declared colour never reaches the screen.
 */
object ClariFiIcons {

    // ── navigation ────────────────────────────────────────────────────────────

    val Dashboard: ImageVector by lazy {
        strokeIcon(
            "Dashboard",
            roundedRect(3f, 3f, 7f, 9f, 1.5f),
            roundedRect(14f, 3f, 7f, 5f, 1.5f),
            roundedRect(14f, 12f, 7f, 9f, 1.5f),
            roundedRect(3f, 16f, 7f, 5f, 1.5f),
        )
    }

    val Transactions: ImageVector by lazy {
        strokeIcon("Transactions", "M7 4v16M3 8l4-4 4 4", "M17 20V4M21 16l-4 4-4-4")
    }

    val Scan: ImageVector by lazy {
        strokeIcon(
            "Scan",
            "M3 9V7a2 2 0 0 1 2-2h2M17 5h2a2 2 0 0 1 2 2v2M21 15v2a2 2 0 0 1-2 2h-2M7 19H5a2 2 0 0 1-2-2v-2",
            "M3 12h18",
        )
    }

    val Statement: ImageVector by lazy {
        strokeIcon(
            "Statement",
            "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z",
            "M14 2v6h6",
            "M9 13h6M9 17h6",
        )
    }

    val Fixed: ImageVector by lazy {
        strokeIcon("Fixed", "M21 12a9 9 0 1 1-3-6.7", "M21 4v5h-5")
    }

    val Accounts: ImageVector by lazy {
        strokeIcon(
            "Accounts",
            roundedRect(2f, 6f, 20f, 13f, 3f),
            "M16 13.5h2.5",
            "M2 10h20",
        )
    }

    val Settings: ImageVector by lazy {
        strokeIcon(
            "Settings",
            circle(12f, 12f, 3f),
            "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 " +
                "1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 " +
                "2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 " +
                "1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 " +
                "1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 " +
                "1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z",
        )
    }

    val Cloud: ImageVector by lazy {
        strokeIcon("Cloud", "M18 10h-1.26A8 8 0 1 0 9 20h9a5 5 0 0 0 0-10z")
    }

    val Info: ImageVector by lazy {
        strokeIcon("Info", circle(12f, 12f, 10f), "M12 16v-4", "M12 8h.01")
    }

    // ── actions ───────────────────────────────────────────────────────────────

    val Menu: ImageVector by lazy { strokeIcon("Menu", "M3 12h18M3 6h18M3 18h18") }

    /** The overflow affordance: three filled dots, not rings. */
    val More: ImageVector by lazy {
        filledIcon("More", circle(12f, 5f, 1.7f), circle(12f, 12f, 1.7f), circle(12f, 19f, 1.7f))
    }

    val Plus: ImageVector by lazy { strokeIcon("Plus", "M12 5v14M5 12h14") }
    val Close: ImageVector by lazy { strokeIcon("Close", "M18 6 6 18M6 6l12 12") }
    val Check: ImageVector by lazy { strokeIcon("Check", "M20 6 9 17l-5-5") }
    val ChevronRight: ImageVector by lazy { strokeIcon("ChevronRight", "M9 18l6-6-6-6") }
    val ChevronDown: ImageVector by lazy { strokeIcon("ChevronDown", "M6 9l6 6 6-6") }
    val Back: ImageVector by lazy { strokeIcon("Back", "M19 12H5M12 19l-7-7 7-7") }

    val Edit: ImageVector by lazy {
        strokeIcon("Edit", "M12 20h9", "M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4 12.5-12.5z")
    }

    val Delete: ImageVector by lazy {
        strokeIcon(
            "Delete",
            "M3 6h18",
            "M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2",
            "M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6",
            "M10 11v6M14 11v6",
        )
    }

    val Filter: ImageVector by lazy { strokeIcon("Filter", "M22 3H2l8 9.46V19l4 2v-8.54L22 3z") }

    val Search: ImageVector by lazy {
        strokeIcon("Search", circle(11f, 11f, 8f), "M21 21l-4.35-4.35")
    }

    val Calendar: ImageVector by lazy {
        strokeIcon("Calendar", roundedRect(3f, 4f, 18f, 18f, 2f), "M16 2v4M8 2v4M3 10h18")
    }

    val Transfer: ImageVector by lazy {
        strokeIcon("Transfer", "M17 1l4 4-4 4", "M3 11V9a4 4 0 0 1 4-4h14", "M7 23l-4-4 4-4", "M21 13v2a4 4 0 0 1-4 4H3")
    }

    val Camera: ImageVector by lazy {
        strokeIcon(
            "Camera",
            "M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z",
            circle(12f, 13f, 4f),
        )
    }

    val Gallery: ImageVector by lazy {
        strokeIcon("Gallery", roundedRect(3f, 3f, 18f, 18f, 2f), circle(8.5f, 8.5f, 1.5f), "M21 15l-5-5L5 21")
    }

    val Archive: ImageVector by lazy {
        strokeIcon("Archive", "M21 8v13H3V8", roundedRect(1f, 3f, 22f, 5f, 1f), "M10 12h4")
    }

    val Restore: ImageVector by lazy {
        strokeIcon("Restore", "M1 4v6h6", "M3.51 15a9 9 0 1 0 2.13-9.36L1 10")
    }

    val Sun: ImageVector by lazy {
        strokeIcon(
            "Sun",
            circle(12f, 12f, 4f),
            "M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41",
        )
    }

    val Moon: ImageVector by lazy {
        strokeIcon("Moon", "M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z")
    }

    val Upload: ImageVector by lazy {
        strokeIcon("Upload", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "M17 8l-5-5-5 5", "M12 3v12")
    }

    val Download: ImageVector by lazy {
        strokeIcon("Download", "M12 3v13M6 11l6 6 6-6", "M5 21h14")
    }

    val Wallet: ImageVector by lazy {
        strokeIcon("Wallet", roundedRect(2f, 6f, 20f, 13f, 3f), "M16 13.5h2.5")
    }

    // ── builders ──────────────────────────────────────────────────────────────

    /**
     * SVG arcs would need eight parameters each; for the circles in this set a
     * two-arc path is exact and far easier to read at the call site.
     */
    private fun circle(cx: Float, cy: Float, r: Float): String =
        "M$cx,${cy - r} a$r,$r 0 1,0 0,${2 * r} a$r,$r 0 1,0 0,${-2 * r}z"

    /** The SVGs use `<rect rx>`; this emits the equivalent path. */
    private fun roundedRect(x: Float, y: Float, w: Float, h: Float, r: Float): String =
        "M${x + r},$y H${x + w - r} A$r,$r 0 0 1 ${x + w},${y + r} " +
            "V${y + h - r} A$r,$r 0 0 1 ${x + w - r},${y + h} " +
            "H${x + r} A$r,$r 0 0 1 $x,${y + h - r} " +
            "V${y + r} A$r,$r 0 0 1 ${x + r},$y z"

    private fun strokeIcon(name: String, vararg pathData: String): ImageVector =
        build(name, pathData) { data ->
            addPath(
                pathData = PathParser().parsePathString(data).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }

    private fun filledIcon(name: String, vararg pathData: String): ImageVector =
        build(name, pathData) { data ->
            addPath(
                pathData = PathParser().parsePathString(data).toNodes(),
                fill = SolidColor(Color.Black),
            )
        }

    private fun build(
        name: String,
        pathData: Array<out String>,
        addEach: ImageVector.Builder.(String) -> Unit,
    ): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply { pathData.forEach { addEach(it) } }.build()
}
