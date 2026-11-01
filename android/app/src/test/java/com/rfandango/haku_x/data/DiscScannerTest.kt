package com.rfandango.haku_x.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DiscScanner over small XISOs built here: a good one, then each kind of damage. */
class DiscScannerTest {

  private val sector = 2048

  /**
   * An XISO: the volume descriptor at sector 32, the root directory at
   * sector 33 (default.xbe, data.bin, a subdirectory with one file), files
   * after it. blank: data.bin all zeros; truncateBy: bytes cut off the end.
   */
  private fun build(blank: Boolean = false, truncateBy: Int = 0, badXbe: Boolean = false, badMagic: Boolean = false): File {
    val xbeSector = 40L
    val xbe = xbe(badXbe)
    val dataSector = 48L
    val dataSize = 512 * 1024
    val subSector = 34L
    val innerSector = dataSector + dataSize / sector
    val innerSize = 4096
    val total = (innerSector * sector + innerSize).toInt()
    val img = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
    // volume descriptor
    val magic = (if (badMagic) "MICROSOFT*XBOX*MEDIX" else "MICROSOFT*XBOX*MEDIA").toByteArray(Charsets.US_ASCII)
    img.position(32 * sector); img.put(magic)
    img.putInt(33)           // root sector
    img.putInt(sector)       // root size
    img.position(32 * sector + 0x7EC); img.put(magic)
    // root: a tree of 3 entries, default.xbe at 0 (left -> data.bin, right -> sub)
    val root = 33 * sector
    fill(img, root, sector)
    val e2 = entry(img, root, 0, left = 0, right = 0, sectorOf = xbeSector, size = xbe.size, attr = 0x20, name = "default.xbe", leftOff = 32, rightOff = 64)
    entry(img, root, 32, 0, 0, dataSector, dataSize, 0x20, "data.bin")
    entry(img, root, 64, 0, 0, subSector, sector, 0x10, "sub")
    check(e2)
    // sub: one file
    fill(img, subSector.toInt() * sector, sector)
    entry(img, subSector.toInt() * sector, 0, 0, 0, innerSector, innerSize, 0x20, "inner.dat")
    img.position(xbeSector.toInt() * sector); img.put(xbe)
    if (!blank) {
      for (i in 0 until dataSize) img.put(dataSector.toInt() * sector + i, ((i * 31 + 7) and 0xFF).toByte().let { if (it.toInt() == 0) 1 else it })
    }
    for (i in 0 until innerSize) img.put(innerSector.toInt() * sector + i, 0x5A)
    val f = File.createTempFile("disc", ".iso")
    f.deleteOnExit()
    f.writeBytes(img.array().copyOf(total - truncateBy))
    return f
  }

  private fun fill(img: ByteBuffer, at: Int, len: Int) { for (i in 0 until len) img.put(at + i, 0xFF.toByte()) }

  private fun entry(
    img: ByteBuffer, dir: Int, off: Int, left: Int, right: Int, sectorOf: Long, size: Int, attr: Int, name: String,
    leftOff: Int = 0, rightOff: Int = 0,
  ): Boolean {
    val at = dir + off
    img.putShort(at, (if (leftOff != 0) leftOff / 4 else left).toShort())
    img.putShort(at + 2, (if (rightOff != 0) rightOff / 4 else right).toShort())
    img.putInt(at + 4, sectorOf.toInt())
    img.putInt(at + 8, size)
    img.put(at + 12, attr.toByte())
    img.put(at + 13, name.length.toByte())
    name.toByteArray(Charsets.US_ASCII).forEachIndexed { i, b -> img.put(at + 14 + i, b) }
    return true
  }

  /** A minimal retail XBE: headers 0x1000 bytes at base 0x10000, a certificate, one section. */
  private fun xbe(bad: Boolean): ByteArray {
    val size = 0x2000
    val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    b.putInt(0, if (bad) 0x12345678 else 0x48454258)
    b.putInt(0x104, 0x10000)            // base
    b.putInt(0x108, 0x1000)             // headers size
    b.putInt(0x10C, 0x2000)             // image size
    b.putInt(0x118, 0x10000 + 0x200)    // certificate
    b.putInt(0x11C, 1)                  // sections
    b.putInt(0x120, 0x10000 + 0x400)    // section table
    b.putInt(0x128, (0x11000L xor 0xA8FC57ABL).toInt())   // entry point, retail key
    b.putInt(0x200 + 8, 0x4D530064)     // title ID
    "Test Game".forEachIndexed { i, c -> b.putChar(0x200 + 0x0C + i * 2, c) }
    val s = 0x400
    b.putInt(s + 4, 0x11000); b.putInt(s + 8, 0x1000); b.putInt(s + 0x0C, 0x1000); b.putInt(s + 0x10, 0x1000)
    return b.array()
  }

  private fun scan(f: File, read: Boolean = true) =
    RandomAccessFile(f, "r").use { DiscScanner(it.channel).scan(readPass = read) }

  @Test
  fun goodImageHasNoProblems() {
    val r = scan(build())
    assertEquals(r.issues.toString(), 0, r.errors)
    assertEquals(r.issues.toString(), 0, r.warnings)
    assertEquals(3, r.files)
    assertEquals(2, r.directories)
    assertEquals(0x4D530064L, r.titleId)
    assertEquals("Test Game", r.titleName)
    assertNotNull(r.sha1)
    assertEquals(0L, r.partition)
  }

  @Test
  fun truncatedImageIsAnError() {
    val r = scan(build(truncateBy = 3000))
    assertTrue(r.issues.toString(), r.issues.any { it.severity == DiscScanner.Severity.ERROR && "past the end" in it.text })
  }

  @Test
  fun blankStretchIsAWarning() {
    val r = scan(build(blank = true))
    assertEquals(r.issues.toString(), 0, r.errors)
    assertTrue(r.issues.toString(), r.issues.any { it.severity == DiscScanner.Severity.WARNING && "data.bin" in it.text })
  }

  @Test
  fun damagedXbeIsAnError() {
    val r = scan(build(badXbe = true), read = false)
    assertTrue(r.issues.toString(), r.issues.any { it.severity == DiscScanner.Severity.ERROR && "XBEH" in it.text })
  }

  @Test
  fun notAnXboxDisc() {
    val r = scan(build(badMagic = true), read = false)
    assertEquals(r.issues.toString(), 1, r.errors)
    assertEquals(0, r.files)
  }
}
