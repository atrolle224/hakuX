package com.rfandango.haku_x

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.rfandango.haku_x.data.SaveStateStore
import java.text.DateFormat
import java.util.Date

/**
 * The running game's savestates (push-buffer mode, native/state): the
 * menu's Save state and Load state over the game's ten slots
 * (SaveStateStore). Saving goes through the core (nativeSaveState, off the
 * UI thread: about a frame and the write); loading starts the core again
 * into the state (RelaunchActivity with the state's path), its frame held
 * meanwhile. Load state's list deletes states too.
 */
class SaveStates(
  private val activity: NativeCoreActivity,
  gameKey: String,
  /** The disc's URI as the game was started (the first states were kept by it). */
  discUri: String?,
  /** Dialogs the game stays stopped under (NativeCoreActivity.showMenuDialog). */
  private val showDialog: (AlertDialog.Builder) -> AlertDialog,
  /** The frame on screen (null: none could be copied). */
  private val captureFrame: ((Bitmap?) -> Unit) -> Unit,
) {
  private val store = SaveStateStore(activity, gameKey, discUri)

  fun state(slot: Int) = store.state(slot)

  /** The slots for Save state (all of them) or Load state (the saved ones). */
  fun showSlots(save: Boolean) {
    val slots = if (save) (1..SaveStateStore.SLOTS).toMutableList() else store.saved().toMutableList()
    if (slots.isEmpty()) {
      Toast.makeText(activity, R.string.state_none, Toast.LENGTH_SHORT).show()
      return
    }
    val adapter = SlotAdapter(slots, deletable = !save)
    val dialog = showDialog(
      AlertDialog.Builder(activity)
        .setTitle(if (save) R.string.overlay_menu_save_state else R.string.overlay_menu_load_state)
        .setAdapter(adapter) { _, i -> if (save) save(slots[i]) else load(slots[i]) }
        .setNegativeButton(android.R.string.cancel, null)
    )
    adapter.dialog = dialog
    /* a controller's X (or Delete) deletes the slot it's on: the row's delete button takes no focus */
    if (!save) {
      dialog.listView.setOnKeyListener { list, code, e ->
        val i = (list as AdapterView<*>).selectedItemPosition
        if (code !in DELETE_KEYS || i == AdapterView.INVALID_POSITION) return@setOnKeyListener false
        if (e.action == KeyEvent.ACTION_UP) adapter.delete(adapter.getItem(i))
        true
      }
    }
  }

  fun save(slot: Int) {
    store.prepare()
    val progress = showDialog(
      AlertDialog.Builder(activity)
        .setView(BusyView(activity, activity.getString(R.string.state_saving, slot)).view)
        .setCancelable(false)
    )
    Thread {
      val err = NativeCoreActivity.nativeSaveState(store.state(slot).absolutePath)
      activity.runOnUiThread {
        if (err != null) {
          progress.dismiss()
          showDialog(
            AlertDialog.Builder(activity)
              .setTitle(R.string.state_save_failed_title)
              .setMessage(err)
              .setPositiveButton(android.R.string.ok, null)
          )
          return@runOnUiThread
        }
        /* the frame the state shows: the game stopped on it again */
        captureFrame { frame ->
          Thread {
            if (frame != null) {
              runCatching { store.thumb(slot).outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
            }
            activity.runOnUiThread {
              progress.dismiss()
              Toast.makeText(activity, activity.getString(R.string.state_saved, slot), Toast.LENGTH_SHORT).show()
            }
          }.start()
        }
      }
    }.start()
  }

  /** The newest state of any slot (the hang prompt's), or null. */
  fun newest(): Int? = store.newest()

  /**
   * An automatic savestate into the next autosave slot (the older of the
   * two), without dialogs: the game stands still while it's written, as for
   * Save state. done(true) once saved and its frame kept, false if it failed.
   */
  fun autosave(done: (Boolean) -> Unit) {
    store.prepare()
    val slot = store.nextAutoSlot()
    val file = store.state(slot)
    Thread {
      /* the core writes beside the file and renames it over (native/state): a failed save leaves the old one */
      val err = NativeCoreActivity.nativeSaveState(file.absolutePath)
      val ok = err == null && file.isFile
      activity.runOnUiThread {
        if (!ok) {
          android.util.Log.w("hakuX-autosave", "autosave failed: ${err ?: "not written"}")
          done(false)
          return@runOnUiThread
        }
        captureFrame { frame ->
          Thread {
            if (frame != null) {
              runCatching { store.thumb(slot).outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
            }
            activity.runOnUiThread { done(true) }
          }.start()
        }
      }
    }.start()
  }

  fun load(slot: Int) {
    val file = store.state(slot)
    Thread {
      val err = NativeCoreActivity.nativeStateCheck(file.absolutePath)
      activity.runOnUiThread {
        if (err != null) {
          showDialog(
            AlertDialog.Builder(activity)
              .setTitle(R.string.state_load_failed_title)
              .setMessage(err)
              .setPositiveButton(android.R.string.ok, null)
          )
          return@runOnUiThread
        }
        /* the state's frame on screen while the core starts again */
        val held = RelaunchActivity.heldFrame(activity)
        runCatching { store.thumb(slot).copyTo(held, overwrite = true) }.onFailure { held.delete() }
        activity.restartInto(file.absolutePath)
      }
    }.start()
  }

  /** Deletes a slot's state once confirmed; then onDeleted. */
  private fun confirmDelete(slot: Int, onDeleted: () -> Unit) {
    showDialog(
      AlertDialog.Builder(activity)
        .setTitle(R.string.state_delete_title)
        .setMessage(activity.getString(R.string.state_delete_message, slot))
        .setPositiveButton(R.string.state_delete) { _, _ ->
          store.delete(slot)
          onDeleted()
        }
        .setNegativeButton(android.R.string.cancel, null)
    )
  }

  private fun dp(v: Int) =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), activity.resources.displayMetrics).toInt()

  /** A slot: its frame, its number and when it was saved (or empty); Load state's with a delete button. */
  private inner class SlotAdapter(private val slots: MutableList<Int>, private val deletable: Boolean) : BaseAdapter() {
    private val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    var dialog: AlertDialog? = null

    override fun getCount() = slots.size
    override fun getItem(i: Int) = slots[i]
    override fun getItemId(i: Int) = slots[i].toLong()

    override fun getView(i: Int, convert: View?, parent: ViewGroup?): View {
      val slot = slots[i]
      val pad = dp(8)
      val row = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), pad, dp(8), pad)
      }
      val image = ImageView(activity).apply {
        layoutParams = LinearLayout.LayoutParams(dp(96), dp(72))
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(Color.DKGRAY)
        store.thumb(slot).takeIf { it.exists() }?.let { f ->
          BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 4 })?.let(::setImageBitmap)
        }
      }
      val file = store.state(slot)
      val text = TextView(activity).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        setPadding(dp(16), 0, 0, 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        text = (if (SaveStateStore.isAuto(slot)) activity.getString(R.string.state_autosave) else activity.getString(R.string.state_slot, slot)) + "\n" +
          if (file.exists()) format.format(Date(file.lastModified())) else activity.getString(R.string.state_empty)
      }
      row.addView(image)
      row.addView(text)
      if (deletable) {
        row.addView(ImageView(activity).apply {
          setImageResource(android.R.drawable.ic_menu_delete)
          contentDescription = activity.getString(R.string.state_delete)
          setPadding(pad, pad, pad, pad)
          isFocusable = false
          setOnClickListener { delete(slot) }
        })
      }
      return row
    }

    fun delete(slot: Int) {
      confirmDelete(slot) {
        slots.remove(slot)
        if (slots.isEmpty()) dialog?.dismiss() else notifyDataSetChanged()
      }
    }
  }

  private companion object {
    val DELETE_KEYS = setOf(KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL)
  }
}
