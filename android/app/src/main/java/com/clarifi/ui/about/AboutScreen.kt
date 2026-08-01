package com.clarifi.ui.about

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clarifi.BuildConfig
import com.clarifi.data.updates.ReleaseChecker
import com.clarifi.ui.components.ClariFiCard
import com.clarifi.ui.components.ClariFiLogo
import com.clarifi.ui.components.SectionHeader
import com.clarifi.ui.components.rememberBarAwareScrollState
import com.clarifi.ui.containerViewModel
import com.clarifi.ui.theme.clarifiPalette

/**
 * The desktop's footer and its Updates panel on one screen: mark, version, what
 * the latest release changed, copyright, repo.
 *
 * There is no in-app updater here. The desktop can swap its own installer; an APK
 * cannot install itself without a permission this app has no business asking for,
 * so the release page is the honest destination.
 */
@Composable
fun AboutScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val viewModel: AboutViewModel = containerViewModel { AboutViewModel(it.releases) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    fun open(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberBarAwareScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        ClariFiLogo(size = 76.dp)
        Text("ClariFi", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "Version ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodyMedium,
            color = clarifiPalette.textMuted,
        )

        Spacer(Modifier.height(18.dp))

        val release = state.release
        when {
            state.loading -> {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(vertical = 20.dp)
                        .size(28.dp),
                    strokeWidth = 3.dp,
                )
            }

            state.error != null -> {
                SectionHeader("Updates", modifier = Modifier.fillMaxWidth())
                ClariFiCard {
                    Text(
                        text = state.error.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        color = clarifiPalette.orange,
                    )
                    Text(
                        text = "Check your connection and try again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = clarifiPalette.textMuted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    TextButton(onClick = viewModel::refresh, modifier = Modifier.padding(top = 6.dp)) {
                        Text("Try again")
                    }
                }
            }

            release != null -> {
                SectionHeader(
                    title = if (release.updateAvailable) "Update available" else "Latest release",
                    modifier = Modifier.fillMaxWidth(),
                )
                ClariFiCard {
                    VersionRow("Installed version", "v${BuildConfig.VERSION_NAME}")
                    VersionRow("Latest version", release.tag.ifEmpty { "-" })

                    if (release.notes.isNotEmpty()) {
                        Text(
                            text = if (release.updateAvailable) {
                                "What's new in ${release.tag}"
                            } else {
                                "What's new in this version"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
                        )
                        release.notes.forEach { bullet ->
                            Row(modifier = Modifier.padding(vertical = 3.dp)) {
                                Text(
                                    text = "•",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = clarifiPalette.textMuted,
                                )
                                Text(
                                    text = bullet,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = clarifiPalette.textMuted,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }

                    Text(
                        text = if (release.updateAvailable) {
                            "Download the new APK →"
                        } else {
                            "View full release notes →"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .clickable { open(release.releaseUrl) },
                    )

                    if (!release.updateAvailable) {
                        Text(
                            text = "✓  You're on the latest version.",
                            style = MaterialTheme.typography.bodySmall,
                            color = clarifiPalette.green,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }

            else -> {
                SectionHeader("Updates", modifier = Modifier.fillMaxWidth())
                ClariFiCard {
                    Text(
                        text = "No releases published yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = clarifiPalette.textMuted,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "© 2026 Federico Roldós",
            style = MaterialTheme.typography.bodySmall,
            color = clarifiPalette.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "View on GitHub",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open(ReleaseChecker.REPO_URL) }
                .padding(vertical = 4.dp),
        )

        Spacer(Modifier.height(32.dp))
    }
}

/** The desktop's two-column version rows, hairline and all. */
@Composable
private fun VersionRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = clarifiPalette.textMuted)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}
