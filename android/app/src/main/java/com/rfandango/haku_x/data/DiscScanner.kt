package com.rfandango.haku_x.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale
import java.util.zip.CRC32

/**
 * Checks an Xbox disc image (a full dump or an XISO) for what makes games
 * hang, stop loading or not start: its file system (XDVDFS) read entry by
 * entry, every file's place inside the image (a truncated copy cuts the
 * last ones off), default.xbe's headers, and the whole image read once
 * through, for read errors and for long blank stretches inside files (dump
 * tools fill sectors they couldn't read with zeros). The read also gives
 * the image's CRC-32 and SHA-1, to compare with redump.org (a full dump's;
 * an XISO's differ from any dump's), and how fast the storage reads.
 *
 * Plain JVM code over a FileChannel, so tests run it on images they build.
 */
class DiscScanner(private val ch: FileChannel) {

  enum class Severity { ERROR, WARNING, INFO }

  data class Issue(val severity: Severity, val text: String)

  data class Report(
    val issues: List<Issue>,
    val imageBytes: Long,
    val partition: Long?,
    val files: Int,
    val directories: Int,
    val fileBytes: Long,
    val titleId: Long,
    val titleName: String?,
    val crc32: String?,
    val sha1: String?,
    /** The read pass's speed, MB/s (0: no read pass). */
    val readMBps: Double,
    val cancelled: Boolean,
  ) {
    val errors get() = issues.count { it.severity == Severity.ERROR }
    val warnings get() = issues.count { it.severity == Severity.WARNING }
  }

  /** A file of the image: its path and where its bytes are. */
  private class Extent(val path: String, val start: Long, val size: Long)

  private val issues = ArrayList<Issue>()
  private fun error(t: String) = issues.add(Issue(Severity.ERROR, t))
  private fun warn(t: String) = issues.add(Issue(Severity.WARNING, t))
  private fun info(t: String) = issues.add(Issue(Severity.INFO, t))

  /**
   * Runs every check. progress(done, total) reports the read pass in
   * bytes; cancelled is asked between reads. readPass false skips the read
   * pass (structure only, a second at most).
   */
  fun scan(
    readPass: Boolean = true,
    progress: (Long, Long) -> Unit = { _, _ -> },
    cancelled: () -> Boolean = { false },
  ): Report {
    issues.clear()
    val size = runCatching { ch.size() }.getOrElse {
      error("The image's size can't be read: ${it.message}")
      return report(0, null, emptyList(), 0, null, null, 0.0, false)
    }
    val partition = XboxDisc.partitionOf(ch)
    if (partition == null) {
      error(
        if (size < VOLUME + SECTOR) "The file is too small to be an Xbox disc (${mib(size)})."
        else "No Xbox file system was found: this isn't an Xbox disc image, or its first sectors are damaged.",
      )
      return report(size, null, emptyList(), 0, null, null, 0.0, false)
    }
    if (partition != 0L) {
      info("A full dump: the game data starts after the video partition, at ${mib(partition)}. Converting it to an XISO saves ${mib(partition)}.")
    }
    val extents = ArrayList<Extent>()
    var dirs = 0
    val vd = read(partition + VOLUME, SECTOR.toInt())
    if (vd == null) {
      error("The volume descriptor (sector 32) can't be read.")
    } else {
      val tail = ByteArray(MAGIC.length).also { vd.position(0x7EC); vd.get(it) }
      if (String(tail, Charsets.US_ASCII) != MAGIC) {
        warn("The volume descriptor's closing signature is damaged (sector 32): the dump may be bad.")
      }
      val rootSector = vd.getInt(MAGIC.length).toLong() and MASK
      val rootSize = vd.getInt(MAGIC.length + 4).toLong() and MASK
      if (rootSize == 0L || rootSize > MAX_DIR || partition + rootSector * SECTOR + rootSize > size) {
        error("The root directory lies outside the image (sector $rootSector, ${rootSize} bytes): the image is truncated or damaged.")
      } else {
        dirs = walk(partition, size, rootSector, rootSize, extents)
      }
    }
    val xbe = extents.firstOrNull { it.path.equals("default.xbe", ignoreCase = true) }
    var titleId = 0L
    var titleName: String? = null
    if (xbe == null) {
      if (extents.isNotEmpty()) error("There's no default.xbe in the disc's root: it won't start.")
    } else {
      checkXbe(xbe, size)?.let { (id, name) -> titleId = id; titleName = name }
    }
    // Every file inside the image: one past its end was cut off (a truncated copy or download).
    val end = extents.maxOfOrNull { it.start + it.size } ?: 0L
    val outside = extents.filter { it.start + it.size > size }
    if (outside.isNotEmpty()) {
      error(
        "${outside.size} file(s) run past the end of the image: it's truncated, ${mib(end - size)} short. " +
          "First: ${outside.minBy { it.start }.path}. Copy or download it again.",
      )
    }
    checkOverlaps(extents)
    var crc: String? = null
    var sha: String? = null
    var speed = 0.0
    var wasCancelled = false
    if (readPass) {
      val r = readAll(size, extents, progress, cancelled)
      crc = r.crc
      sha = r.sha
      speed = r.mbps
      wasCancelled = r.cancelled
    }
    if (issues.none { it.severity != Severity.INFO } && !wasCancelled) {
      info(if (readPass) "No problems found: every file is in place and the whole image reads." else "The file system is intact (the image wasn't read through).")
    }
    return report(size, partition, extents, dirs, titleId to titleName, crc to sha, speed, wasCancelled)
  }

  private fun report(
    size: Long, partition: Long?, extents: List<Extent>, dirs: Int,
    title: Pair<Long, String?>?, hashes: Pair<String?, String?>?, speed: Double, cancelled: Boolean,
  ) = Report(
    issues.toList(), size, partition, extents.size, dirs, extents.sumOf { it.size },
    title?.first ?: 0L, title?.second, hashes?.first, hashes?.second, speed, cancelled,
  )

  /**
   * The directory tree from the root: each directory a binary tree of
   * entries (left, right: offsets in 4-byte units; sector, size, attributes,
   * name). Damaged entries are reported, not followed; a directory met
   * twice (a loop) is read once. Returns the directories read.
   */
  private fun walk(partition: Long, size: Long, rootSector: Long, rootSize: Long, out: MutableList<Extent>): Int {
    val seen = HashSet<Long>()
    val todo = ArrayDeque<Triple<String, Long, Long>>()
    todo.add(Triple("", rootSector, rootSize))
    var dirs = 0
    var badEntries = 0
    while (todo.isNotEmpty()) {
      val (prefix, sector, dsize) = todo.removeFirst()
      if (!seen.add(sector)) {
        warn("The directory ${prefix.ifEmpty { "\\" }} is listed twice (a loop in the file system).")
        continue
      }
      if (dsize == 0L) continue    // an empty directory
      if (dsize > MAX_DIR || partition + sector * SECTOR + dsize > size) {
        error("The directory ${prefix.ifEmpty { "\\" }} lies outside the image: truncated or damaged.")
        continue
      }
      val dir = read(partition + sector * SECTOR, dsize.toInt()) ?: run {
        error("The directory ${prefix.ifEmpty { "\\" }} can't be read.")
        continue
      }
      dirs++
      val visited = HashSet<Int>()
      val stack = ArrayDeque<Int>()
      stack.add(0)
      while (stack.isNotEmpty()) {
        val off = stack.removeLast()
        if (!visited.add(off)) continue
        if (off < 0 || off + 14 > dsize) { badEntries++; continue }
        val left = dir.getShort(off).toInt() and 0xFFFF
        val right = dir.getShort(off + 2).toInt() and 0xFFFF
        if (left == 0xFFFF && right == 0xFFFF && (dir.getInt(off + 4) == -1)) continue   // padding
        val fsector = dir.getInt(off + 4).toLong() and MASK
        val fsize = dir.getInt(off + 8).toLong() and MASK
        val attr = dir.get(off + 12).toInt() and 0xFF
        val nlen = dir.get(off + 13).toInt() and 0xFF
        if (nlen == 0 || off + 14 + nlen > dsize) { badEntries++; continue }
        val raw = ByteArray(nlen).also { b -> for (i in 0 until nlen) b[i] = dir.get(off + 14 + i) }
        val name = String(raw, Charsets.ISO_8859_1)
        if (raw.any { it.toInt() and 0xFF < 0x20 }) { badEntries++; continue }
        val path = if (prefix.isEmpty()) name else "$prefix\\$name"
        if (attr and ATTR_DIRECTORY != 0) {
          todo.add(Triple(path, fsector, fsize))
        } else {
          out.add(Extent(path, partition + fsector * SECTOR, fsize))
        }
        if (left != 0 && left != 0xFFFF) stack.add(left * 4)
        if (right != 0 && right != 0xFFFF) stack.add(right * 4)
      }
    }
    if (badEntries > 0) {
      error("$badEntries damaged file system entr${if (badEntries == 1) "y" else "ies"}: some files can't be found. The dump is bad.")
    }
    return dirs
  }

  /** default.xbe's headers: the signature, sizes, sections and certificate. The title ID and name, or null. */
  private fun checkXbe(x: Extent, imageSize: Long): Pair<Long, String?>? {
    if (x.start + 0x178 > imageSize) return null      // reported as truncated
    val h0 = read(x.start, 0x178) ?: run { error("default.xbe can't be read."); return null }
    if (h0.getInt(0) != XBE_MAGIC) {
      error("default.xbe isn't an Xbox program (no XBEH signature): the disc won't start.")
      return null
    }
    val base = h0.getInt(0x104).toLong() and MASK
    val headerSize = h0.getInt(0x108).toLong() and MASK
    val imageSz = h0.getInt(0x10C).toLong() and MASK
    if (base != 0x10000L) warn("default.xbe loads at an unusual address (0x%X).".format(base))
    if (headerSize < 0x178 || headerSize > x.size || headerSize > MAX_HEADERS) {
      error("default.xbe's headers are damaged (size $headerSize).")
      return null
    }
    val h = read(x.start, headerSize.toInt()) ?: run { error("default.xbe's headers can't be read."); return null }
    fun at(va: Int, len: Int): Int? {
      val o = (va.toLong() and MASK) - base
      return if (o >= 0 && o + len <= headerSize) o.toInt() else null
    }
    val count = h.getInt(0x11C)
    if (count !in 1..MAX_SECTIONS) {
      error("default.xbe has $count sections: its headers are damaged.")
    } else {
      val table = at(h.getInt(0x120), count * 0x38)
      if (table == null) {
        error("default.xbe's section table lies outside its headers: damaged.")
      } else {
        var bad = 0
        var extra = 0
        for (i in 0 until count) {
          val s = table + i * 0x38
          val va = h.getInt(s + 4).toLong() and MASK
          val vsize = h.getInt(s + 8).toLong() and MASK
          val raw = h.getInt(s + 0x0C).toLong() and MASK
          val rsize = h.getInt(s + 0x10).toLong() and MASK
          if (raw + rsize > x.size) bad++
          if (va + vsize > base + imageSz + SECTOR) extra++
        }
        if (bad > 0) error("$bad of default.xbe's $count sections lie past the end of the file: it's cut off or damaged.")
        if (extra > 0) warn("$extra of default.xbe's sections reach past its stated image size (a patched program?).")
      }
    }
    val c = at(h.getInt(0x118), 0xB0)
    if (c == null) {
      error("default.xbe's certificate lies outside its headers: damaged.")
      return null
    }
    val id = h.getInt(c + 8).toLong() and MASK
    val name = CharArray(40) { h.getChar(c + 0x0C + it * 2) }.let(::String).substringBefore('\u0000').trim().ifEmpty { null }
    if (id == 0L) warn("default.xbe's title ID is 0 (a homebrew or patched program?).")
    // The entry point and kernel thunk are XOR-ed with the retail or the debug key; a debug
    // build's decode to nonsense with the retail key.
    val entry = (h.getInt(0x128).toLong() and MASK) xor RETAIL_ENTRY_KEY
    val debugEntry = (h.getInt(0x128).toLong() and MASK) xor DEBUG_ENTRY_KEY
    val inImage = { e: Long -> e >= base && e < base + imageSz }
    if (!inImage(entry) && inImage(debugEntry)) {
      warn("default.xbe is a debug (development kit) build: it may not run as a retail console's.")
    } else if (!inImage(entry)) {
      warn("default.xbe's entry point isn't inside the program: it's patched or damaged.")
    }
    return id to name
  }

  /** Files sharing sectors: one copy over another in a badly rebuilt XISO. */
  private fun checkOverlaps(extents: List<Extent>) {
    val sorted = extents.filter { it.size > 0 }.sortedBy { it.start }
    var overlaps = 0
    var first: String? = null
    for (i in 1 until sorted.size) {
      val prev = sorted[i - 1]
      if (sorted[i].start < prev.start + prev.size) {
        overlaps++
        if (first == null) first = "${prev.path} and ${sorted[i].path}"
      }
    }
    if (overlaps > 0) {
      warn("$overlaps file(s) overlap others ($first): the image was rebuilt badly. Use the original dump if you have it.")
    }
  }

  private class ReadResult(val crc: String?, val sha: String?, val mbps: Double, val cancelled: Boolean)

  /**
   * The image read start to end in large blocks: read errors and short
   * reads, the hashes, and blank (all zero) stretches inside files of at
   * least BLANK_RUN, which can be sectors the dump couldn't read.
   */
  private fun readAll(size: Long, extents: List<Extent>, progress: (Long, Long) -> Unit, cancelled: () -> Boolean): ReadResult {
    val crc = CRC32()
    val sha = MessageDigest.getInstance("SHA-1")
    val buf = ByteBuffer.allocateDirect(BLOCK)
    val arr = ByteArray(BLOCK)
    val files = extents.filter { it.size >= BLANK_RUN && it.start < size }.sortedBy { it.start }
    var fi = 0
    var zeroRunStart = -1L
    var zeroRunFile = -1
    val blank = LinkedHashMap<String, Long>()   // file -> longest blank stretch
    var readErrors = 0
    var firstError: Long = -1
    var pos = 0L
    val t0 = System.nanoTime()
    var lastReport = 0L
    while (pos < size) {
      if (cancelled()) return ReadResult(null, null, 0.0, true)
      buf.clear()
      val want = minOf(BLOCK.toLong(), size - pos).toInt()
      buf.limit(want)
      var got = 0
      var failed = false
      try {
        while (got < want) {
          val n = ch.read(buf, pos + got)
          if (n <= 0) break
          got += n
        }
      } catch (_: Exception) {
        failed = true
      }
      if (got < want) {
        if (!failed && got == 0) {
          error("The image stops reading at ${mib(pos)} of ${mib(size)}: the file is shorter than it says.")
          break
        }
        if (firstError < 0) firstError = pos + got
        readErrors++
        // the rest of this block can't be read: hashes are no longer the image's; carry on past it
        pos += want
        continue
      }
      buf.flip()
      buf.get(arr, 0, want)
      crc.update(arr, 0, want)
      sha.update(arr, 0, want)
      // blank sectors inside files
      var s = 0
      while (s < want) {
        val abs = pos + s
        while (fi < files.size && files[fi].start + files[fi].size <= abs) fi++
        val sectorLen = minOf(SECTOR.toInt(), want - s)
        val f = files.getOrNull(fi)
        val inFile = f != null && abs >= f.start
        if (inFile && isZero(arr, s, sectorLen)) {
          if (zeroRunStart < 0 || zeroRunFile != fi) { zeroRunStart = abs; zeroRunFile = fi }
          val runLen = abs + sectorLen - zeroRunStart
          if (runLen >= BLANK_RUN) {
            val p = f!!.path
            if ((blank[p] ?: 0L) < runLen) blank[p] = runLen
          }
        } else {
          zeroRunStart = -1L
        }
        s += sectorLen
      }
      pos += want
      val now = System.nanoTime()
      if (now - lastReport > 100_000_000L) {
        progress(pos, size)
        lastReport = now
      }
    }
    progress(size, size)
    val secs = (System.nanoTime() - t0) / 1e9
    val mbps = if (secs > 0) size / 1e6 / secs else 0.0
    if (readErrors > 0) {
      error("$readErrors part(s) of the image can't be read (first at ${mib(firstError)}): the file or the storage is damaged. Copy it again, to other storage if it keeps happening.")
    }
    if (blank.isNotEmpty()) {
      val list = blank.entries.sortedByDescending { it.value }.take(5).joinToString { "${it.key} (${kib(it.value)})" }
      warn(
        "${blank.size} file(s) hold long blank stretches: $list. Dump tools fill sectors they couldn't read with zeros, " +
          "so if the game hangs or loses sound or textures there, the dump is bad; some files are blank on purpose.",
      )
    }
    if (size >= 256L shl 20 && mbps in 0.1..15.0) {
      warn("The storage reads at %.0f MB/s: slow enough that games which stream (Fable, Morrowind, racing games) can stall. Internal storage or a faster SD card helps.".format(Locale.ROOT, mbps))
    }
    val hex = { b: ByteArray -> b.joinToString("") { "%02x".format(it) } }
    return ReadResult(
      if (readErrors == 0) "%08x".format(crc.value) else null,
      if (readErrors == 0) hex(sha.digest()) else null,
      mbps, false,
    )
  }

  private fun isZero(a: ByteArray, from: Int, len: Int): Boolean {
    for (i in from until from + len) if (a[i].toInt() != 0) return false
    return true
  }

  private fun read(pos: Long, n: Int): ByteBuffer? {
    if (n <= 0) return null
    val b = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    var at = pos
    return try {
      while (b.hasRemaining()) {
        val got = ch.read(b, at)
        if (got <= 0) return null
        at += got
      }
      b.flip()
      b
    } catch (_: Exception) {
      null
    }
  }

  companion object {
    private const val SECTOR = 2048L
    private const val VOLUME = 0x10000L
    private const val MAGIC = "MICROSOFT*XBOX*MEDIA"
    private const val XBE_MAGIC = 0x48454258 // "XBEH"
    private const val MASK = 0xFFFFFFFFL
    private const val MAX_DIR = 64L shl 20
    private const val MAX_HEADERS = 4L shl 20
    private const val MAX_SECTIONS = 256
    private const val ATTR_DIRECTORY = 0x10
    private const val BLOCK = 4 shl 20
    private const val BLANK_RUN = 256L * 1024      // 128 sectors
    private const val RETAIL_ENTRY_KEY = 0xA8FC57ABL
    private const val DEBUG_ENTRY_KEY = 0x94859D4BL

    private fun mib(b: Long) = "%.1f MB".format(Locale.ROOT, b / 1048576.0)
    private fun kib(b: Long) = "%d KB".format(Locale.ROOT, b / 1024)
  }
}
