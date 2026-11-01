package com.rfandango.haku_x.ui.library

import android.text.format.Formatter
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.rfandango.haku_x.R
import com.rfandango.haku_x.data.DiscScanner
import com.rfandango.haku_x.data.Game
import com.rfandango.haku_x.ui.common.ProgressBlock
import com.rfandango.haku_x.ui.common.fractionOf
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The library menu's Check disc: DiscScanner over the game's image (its file
 * system, every file's place, default.xbe, then the whole image read
 * through), its progress, then what it found, worst first, with the image's
 * hashes to compare with redump.org.
 */
@Composable
fun DiscCheckDialog(game: Game, onDismiss: () -> Unit) {
  val context = LocalContext.current
  var done by remember { mutableLongStateOf(0L) }
  var total by remember { mutableLongStateOf(game.sizeBytes) }
  var report by remember { mutableStateOf<DiscScanner.Report?>(null) }
  var failure by remember { mutableStateOf<String?>(null) }
  val cancel = remember { AtomicBoolean(false) }
  // the screen stays on through the read: a long one on a big image
  val view: View = LocalView.current
  DisposableEffect(Unit) {
    view.keepScreenOn = true
    onDispose {
      view.keepScreenOn = false
      cancel.set(true)
    }
  }
  LaunchedEffect(game.uri) {
    val r = withContext(Dispatchers.IO) {
      runCatching {
        context.contentResolver.openFileDescriptor(game.uri, "r")!!.use { pfd ->
          FileInputStream(pfd.fileDescriptor).use { fis ->
            DiscScanner(fis.channel).scan(
              progress = { d, t -> done = d; total = t },
              cancelled = { cancel.get() },
            )
          }
        }
      }
    }
    r.onSuccess { report = it }.onFailure { failure = it.message ?: it.javaClass.simpleName }
  }

  val r = report
  when {
    failure != null -> AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(R.string.disc_check_title)) },
      text = { Text(stringResource(R.string.disc_check_open_failed, failure.orEmpty())) },
      confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
    )
    r == null -> AlertDialog(
      onDismissRequest = {},
      properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
      title = { Text(stringResource(R.string.disc_check_running)) },
      text = {
        Column {
          Text(game.title, style = MaterialTheme.typography.bodyMedium)
          Spacer(Modifier.height(12.dp))
          ProgressBlock(
            fractionOf(done, total),
            stringResource(
              R.string.disc_check_progress,
              Formatter.formatShortFileSize(context, done),
              Formatter.formatShortFileSize(context, total),
            ),
          )
        }
      },
      confirmButton = {},
      dismissButton = {
        TextButton(onClick = { cancel.set(true); onDismiss() }) { Text(stringResource(android.R.string.cancel)) }
      },
    )
    else -> AlertDialog(
      onDismissRequest = onDismiss,
      title = {
        Text(
          when {
            r.errors > 0 -> stringResource(R.string.disc_check_bad)
            r.warnings > 0 -> stringResource(R.string.disc_check_warnings)
            else -> stringResource(R.string.disc_check_good)
          },
        )
      },
      text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
          Text(game.title, style = MaterialTheme.typography.titleSmall)
          Spacer(Modifier.height(8.dp))
          for (i in r.issues.sortedBy { it.severity.ordinal }) {
            Row(Modifier.padding(vertical = 4.dp)) {
              Text(
                when (i.severity) {
                  DiscScanner.Severity.ERROR -> "✖ "
                  DiscScanner.Severity.WARNING -> "⚠ "
                  DiscScanner.Severity.INFO -> "• "
                },
                color = when (i.severity) {
                  DiscScanner.Severity.ERROR -> MaterialTheme.colorScheme.error
                  DiscScanner.Severity.WARNING -> MaterialTheme.colorScheme.tertiary
                  DiscScanner.Severity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                },
              )
              Text(i.text, style = MaterialTheme.typography.bodyMedium)
            }
          }
          Spacer(Modifier.height(8.dp))
          val details = buildString {
            append(context.getString(R.string.disc_check_summary, r.files, r.directories, Formatter.formatShortFileSize(context, r.imageBytes)))
            if (r.titleId != 0L) append("\n").append(context.getString(R.string.disc_check_title_id, "%08X".format(r.titleId), r.titleName ?: "?"))
            if (r.readMBps > 0) append("\n").append(context.getString(R.string.disc_check_speed, "%.0f".format(r.readMBps)))
            if (r.crc32 != null) append("\nCRC-32 ").append(r.crc32)
            if (r.sha1 != null) append("\nSHA-1 ").append(r.sha1)
          }
          SelectionContainer {
            Text(details, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
          }
          if (r.sha1 != null && (r.partition ?: 0L) != 0L) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.disc_check_redump), style = MaterialTheme.typography.bodySmall)
          }
        }
      },
      confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
    )
  }
}
