package com.hackpuntes.fridagate.ui.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hackpuntes.fridagate.BuildConfig
import com.hackpuntes.fridagate.R
import com.hackpuntes.fridagate.utils.Diagnostics

/**
 * AboutScreen - Information about the app, author and links, plus a diagnostics card.
 *
 * Everything but the diagnostics is static content. The diagnostics need root,
 * so AboutViewModel only runs them when the user asks.
 */
@Composable
fun AboutScreen(viewModel: AboutViewModel = viewModel()) {
    // Context is needed to launch an Intent (open a URL in the browser) and copy the report
    val context = LocalContext.current
    val diagnostics by viewModel.diagnostics.collectAsState()
    val isRunningDiagnostics by viewModel.isRunning.collectAsState()

    /**
     * Helper lambda that opens a URL in the device's default browser.
     * Intent.ACTION_VIEW with a Uri is the standard Android way to open a URL.
     */
    val openUrl: (String) -> Unit = { url ->
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        Spacer(modifier = Modifier.height(8.dp))

        // ── App icon ──────────────────────────────────────────────────────────
        // On Android 26+ launcher icons are adaptive icons (XML), not bitmaps.
        // BitmapFactory can't decode them — it returns null and crashes.
        // Solution: use ContextCompat.getDrawable() which handles adaptive icons,
        // then draw it manually onto a Bitmap via Canvas.
        val iconBitmap = remember {
            val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
            val bmp = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable?.setBounds(0, 0, canvas.width, canvas.height)
            drawable?.draw(canvas)
            bmp
        }
        Image(
            painter = BitmapPainter(iconBitmap.asImageBitmap()),
            contentDescription = "Fridagate icon",
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
        )

        // ── App name + version ────────────────────────────────────────────────
        Text(
            text = "🪝 Fridagate",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            // Comes from appVersion in gradle.properties, nothing to edit here
            text = "Version ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()

        // ── Description ───────────────────────────────────────────────────────
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "📖 About",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Fridagate is an open source Android pentesting toolkit that combines " +
                            "Frida server management and proxy setup for Burp Suite, Caido and " +
                            "mitmproxy into a single app. Designed to simplify mobile security " +
                            "research workflows.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start
                )
            }
        }

        // ── Author ────────────────────────────────────────────────────────────
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "👤 Author",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text(
                            text = "Javier Olmedo",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Security Researcher",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Blog link
                LinkButton(
                    label = "hackpuntes.com",
                    url = "https://hackpuntes.com",
                    onClick = { openUrl("https://hackpuntes.com") }
                )
            }
        }

        // ── Links ─────────────────────────────────────────────────────────────
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "🔗 Links",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                LinkButton(
                    label = "GitHub Repository",
                    url = "https://github.com/JavierOlmedo/Fridagate",
                    onClick = { openUrl("https://github.com/JavierOlmedo/Fridagate") }
                )

                LinkButton(
                    label = "Report an Issue",
                    url = "https://github.com/JavierOlmedo/Fridagate/issues",
                    onClick = { openUrl("https://github.com/JavierOlmedo/Fridagate/issues") }
                )

                LinkButton(
                    label = "Frida Official Site",
                    url = "https://frida.re",
                    onClick = { openUrl("https://frida.re") }
                )
            }
        }

        // ── Diagnostics ───────────────────────────────────────────────────────
        DiagnosticsCard(
            sections = diagnostics,
            isRunning = isRunningDiagnostics,
            onRun = viewModel::runDiagnostics,
            onCopy = { copyToClipboard(context, Diagnostics.format(diagnostics)) }
        )

        // ── License ───────────────────────────────────────────────────────────
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "📄 License",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "MIT License\n\n" +
                            "Permission is hereby granted, free of charge, to any person obtaining " +
                            "a copy of this software to use, copy, modify, merge, publish, distribute, " +
                            "sublicense, and/or sell copies of the software.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── Disclaimer ────────────────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "⚠️ Disclaimer",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "For authorized security testing only. Only use this tool on devices " +
                            "and applications you own or have explicit permission to test.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        // ── Footer ────────────────────────────────────────────────────────────
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Made with ❤️ in Spain",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/**
 * Reusable composable for a tappable link row.
 *
 * @param label The text shown on the button
 * @param url   The URL (shown as subtitle)
 * @param onClick Called when the user taps the button
 */
@Composable
private fun LinkButton(
    label: String,
    url: String,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, fontWeight = FontWeight.Medium)
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = "Open link",
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * Runs the diagnostics and shows them grouped by section.
 * "Copy report" puts the plain text version on the clipboard, ready for an issue.
 */
@Composable
private fun DiagnosticsCard(
    sections: List<Diagnostics.Section>,
    isRunning: Boolean,
    onRun: () -> Unit,
    onCopy: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "🩺 Diagnostics",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Checks root, SELinux, iptables, the CA store, frida-server and the proxy. " +
                        "The report leaves out the proxy address and the target app, so it can " +
                        "go in a public issue.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            sections.forEach { section ->
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
                section.items.forEach { item ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(0.4f)
                        )
                        Text(
                            text = item.value,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(0.6f)
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onRun,
                    enabled = !isRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(if (sections.isEmpty()) "Run" else "Run again")
                    }
                }
                OutlinedButton(
                    onClick = onCopy,
                    enabled = sections.isNotEmpty() && !isRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy report")
                }
            }
        }
    }
}

/** Puts [text] on the clipboard. Android 13+ confirms it on its own, older versions get a toast. */
private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Fridagate diagnostics", text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "Report copied", Toast.LENGTH_SHORT).show()
    }
}
