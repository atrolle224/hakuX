package com.rfandango.haku_x.ui.library

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rfandango.haku_x.R
import com.rfandango.haku_x.data.SaveStateStore
import com.rfandango.haku_x.ui.common.ConfirmDialog
import com.rfandango.haku_x.ui.common.DeleteButton
import com.rfandango.haku_x.ui.common.ThumbnailShape
import com.rfandango.haku_x.ui.common.rememberFocusOnShow
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A game's savestates from the library (its long-press menu's Load state):
 * each slot's frame and when it was saved. Choosing one starts the game
 * right into it; the delete button removes one, once confirmed.
 */
@Composable
fun LoadStatesDialog(
  title: String,
  store: SaveStateStore,
  onLoad: (slot: Int) -> Unit,
  onDismiss: () -> Unit,
) {
  var slots by remember { mutableStateOf(store.saved()) }
  var deleting by remember { mutableStateOf<Int?>(null) }
  val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
  val shown = rememberFocusOnShow()

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.library_load_state_title, title)) },
    text = {
      if (slots.isEmpty()) {
        Text(stringResource(R.string.state_none))
      } else {
        LazyColumn {
          items(slots, key = { it }) { slot ->
            Row(
              Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .then(if (slot == slots.first()) Modifier.focusRequester(shown) else Modifier)
                .clickable { onLoad(slot) }
                .padding(vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Thumbnail(store, slot)
              Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(if (SaveStateStore.isAuto(slot)) stringResource(R.string.state_autosave) else stringResource(R.string.state_slot, slot), style = MaterialTheme.typography.titleSmall)
                Text(
                  format.format(Date(store.state(slot).lastModified())),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              DeleteButton(onClick = { deleting = slot })
            }
          }
        }
      }
    },
    confirmButton = {},
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
  )

  deleting?.let { slot ->
    ConfirmDialog(
      title = stringResource(R.string.state_delete_title),
      text = stringResource(R.string.state_delete_message, slot),
      confirm = stringResource(R.string.common_delete),
      onConfirm = {
        store.delete(slot)
        slots = store.saved()
        deleting = null
      },
      onDismiss = { deleting = null },
    )
  }
}

/** A slot's frame, small (or a blank box without one, also while it decodes off the main thread). */
@Composable
private fun Thumbnail(store: SaveStateStore, slot: Int) {
  val file = store.thumb(slot)
  val bitmap by produceState<ImageBitmap?>(null, slot, file.lastModified()) {
    value = withContext(Dispatchers.IO) {
      file.takeIf { it.isFile }?.let {
        BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap()
      }
    }
  }
  val shown = bitmap
  if (shown != null) {
    Image(
      shown,
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = Modifier.size(96.dp, 72.dp).clip(ThumbnailShape),
    )
  } else {
    Box(Modifier.size(96.dp, 72.dp).clip(ThumbnailShape).background(MaterialTheme.colorScheme.surfaceVariant))
  }
}
