package com.clarifi.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

/**
 * The pages a provider name in Settings points at, kept next to each other so the
 * phone and `index.html` send people to the same place.
 */
object ProviderLinks {
    const val GROQ = "https://console.groq.com/keys"
    const val GEMINI = "https://aistudio.google.com/app/apikey"
    const val CLAUDE = "https://console.anthropic.com/settings/keys"
    const val SUPABASE = "https://supabase.com"
}

/**
 * An accent-coloured word that opens a page, matching the desktop's `<a>` styling.
 *
 * Underlined where the desktop is not: a browser gives a link a cursor to hover,
 * and a phone gives it nothing, so colour alone never says "this opens something".
 */
fun AnnotatedString.Builder.link(text: String, url: String, color: Color) {
    withLink(
        LinkAnnotation.Url(
            url = url,
            styles = TextLinkStyles(
                style = SpanStyle(color = color, textDecoration = TextDecoration.Underline),
            ),
        )
    ) {
        append(text)
    }
}
