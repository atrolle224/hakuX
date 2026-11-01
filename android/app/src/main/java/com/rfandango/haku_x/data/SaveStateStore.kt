package com.rfandango.haku_x.data

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * A game's savestates (the native core's, native/state): ten slots, each a
 * state file and the frame on screen when it was saved. Kept by the game's
 * key (GameOverrides.gameKey: its title ID and version), so the library and
 * the running game find the same ones however the game was started.
 *
 * discUri: the disc's URI as the game was started with it; the first states
 * were kept by its hash, and those move here the first time this store is
 * made for the game.
 */
class SaveStateStore(context: Context, gameKey: String, discUri: String? = null) {
  private val root = File(context.filesDir, "native/states")
  private val dir = File(root, folder(gameKey))

  init {
    if (discUri != null) adoptLegacy(File(root, hash(discUri)))
  }

  fun state(slot: Int) = File(dir, "slot$slot.hxs")
  fun thumb(slot: Int) = File(dir, "slot$slot.jpg")

  /** The slots holding a state: the autosaves (newest first), then the ten. */
  fun saved(): List<Int> =
    AUTO_SLOTS.filter { state(it).isFile }.sortedByDescending { state(it).lastModified() } +
      (1..SLOTS).filter { state(it).isFile }

  /** The newest state of any slot, or null. */
  fun newest(): Int? = saved().maxByOrNull { state(it).lastModified() }

  /** The autosave slot to write next: an empty one, else the older. */
  fun nextAutoSlot(): Int = AUTO_SLOTS.firstOrNull { !state(it).isFile } ?: AUTO_SLOTS.minBy { state(it).lastModified() }

  /** Where a slot's files go (made on first use). */
  fun prepare() {
    dir.mkdirs()
  }

  /**
   * A slot's state and frame go. The game's cache copies (blobs/: what its
   * states' cache drives held, one copy per content) go with its last state;
   * with states left, the next save drops the copies none of them uses.
   */
  fun delete(slot: Int) {
    state(slot).delete()
    thumb(slot).delete()
    if (saved().isEmpty()) File(dir, "blobs").deleteRecursively()
  }

  /** States kept by the disc URI's hash: their slots move here (newer wins). */
  private fun adoptLegacy(old: File) {
    if (old == dir || !old.isDirectory) return
    dir.mkdirs()
    for (slot in 1..SLOTS) {
      val s = File(old, "slot$slot.hxs")
      if (!s.isFile) continue
      if (!state(slot).isFile || s.lastModified() > state(slot).lastModified()) {
        s.renameTo(state(slot))
        File(old, "slot$slot.jpg").let { if (it.isFile) it.renameTo(thumb(slot)) }
        Log.i(TAG, "savestate slot $slot moved from ${old.name} to ${dir.name}")
      }
    }
    old.listFiles()?.forEach { it.delete() }
    old.delete()
  }

  companion object {
    const val SLOTS = 10
    /** Automatic savestates (NativeCoreActivity), two in turn so a bad one leaves the other. */
    val AUTO_SLOTS = listOf(11, 12)

    fun isAuto(slot: Int) = slot in AUTO_SLOTS
    private const val TAG = "hakuX-library"

    /** The slot a state file is (state's slot<N>.hxs), or null. */
    fun slotOf(path: String): Int? = Regex("""slot(\d+)\.hxs$""").find(path)?.groupValues?.get(1)?.toIntOrNull()

    /** A folder name for a key: as it is when it can be ("4D53004B.1"), else hashed. */
    private fun folder(key: String): String =
      if (key.matches(Regex("[A-Za-z0-9._-]{1,64}"))) key else hash(key)

    private fun hash(s: String): String {
      val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
      return d.take(8).joinToString("") { "%02x".format(it) }
    }
  }
}
