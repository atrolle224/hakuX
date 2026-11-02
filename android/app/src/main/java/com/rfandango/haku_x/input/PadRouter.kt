package com.rfandango.haku_x.input

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.CombinedVibration
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.rfandango.haku_x.data.Binding
import com.rfandango.haku_x.data.Controllers
import com.rfandango.haku_x.data.PadOut
import com.rfandango.haku_x.data.PadProfile
import com.rfandango.haku_x.data.PadState

/**
 * The physical controllers in a game: each controller's events through its
 * mapping to the player it plays as (Controllers.Config.assign), and the
 * title's rumble back to that controller. The activity hands it every
 * gamepad event before SDL could see it; the core takes each player's state
 * (send) and gives back its rumble (rumbleOf: left << 16 | right).
 */
class PadRouter(
  private val context: Context,
  private val send: (player: Int, out: PadOut) -> Unit,
  private val rumbleOf: (player: Int) -> Int,
) {
  private var config = Controllers.load(context)
  private val states = HashMap<Int, PadState>()
  private val profiles = HashMap<Int, PadProfile>()
  /** device id -> player */
  var assignment: Map<Int, Int> = emptyMap()
    private set
  private val out = PadOut()
  private val other = PadOut()

  /** The configuration again (the settings or the menu changed it). */
  fun reload() {
    config = Controllers.load(context)
    reassign()
  }

  /** Controllers connected or gone: who plays as whom, and nothing from a player left without one. */
  fun reassign() {
    val devices = Controllers.connected()
    assignment = config.assign(devices)
    profiles.clear()
    motors.clear()
    val ids = devices.map { it.id }.toSet()
    states.keys.retainAll(ids)
    for (p in 0 until Controllers.PLAYERS) {
      if (p !in assignment.values) send(p, PadOut())
    }
    for (p in assignment.values.toSet()) sendPlayer(p)
  }

  /**
   * A player's state: the one controller's, or with several playing as it
   * (Config.shareP1) all of theirs combined: any button held, the furthest
   * pressed trigger or face button, and each stick from the controller
   * pushing it furthest.
   */
  private fun sendPlayer(player: Int) {
    var first = true
    for ((id, p) in assignment) {
      if (p != player) continue
      val st = states[id] ?: continue
      val d = InputDevice.getDevice(id) ?: continue
      if (first) {
        out.lx = 0; out.ly = 0; out.rx = 0; out.ry = 0
        st.compute(profile(d), out)
        first = false
        continue
      }
      other.lx = 0; other.ly = 0; other.rx = 0; other.ry = 0
      val o = st.compute(profile(d), other)
      out.buttons = out.buttons or o.buttons
      for (i in out.analog.indices) out.analog[i] = maxOf(out.analog[i], o.analog[i])
      if (o.lx.toLong() * o.lx + o.ly.toLong() * o.ly > out.lx.toLong() * out.lx + out.ly.toLong() * out.ly) {
        out.lx = o.lx; out.ly = o.ly
      }
      if (o.rx.toLong() * o.rx + o.ry.toLong() * o.ry > out.rx.toLong() * out.rx + out.ry.toLong() * out.ry) {
        out.rx = o.rx; out.ry = o.ry
      }
    }
    if (first) {
      out.buttons = 0; out.analog.fill(0); out.lx = 0; out.ly = 0; out.rx = 0; out.ry = 0
    }
    send(player, out)
  }

  private fun profile(d: InputDevice) = profiles.getOrPut(d.id) { config.profileFor(d) }

  /** The key is a controller's that its mapping binds to an Xbox button. */
  fun bindsKey(e: KeyEvent): Boolean {
    val d = e.device ?: return false
    if (!Controllers.isGameController(d)) return false
    return profile(d).bind.values.any { l -> l.any { it is Binding.Key && it.code == e.keyCode } }
  }

  /** A controller's key: true when it is the controller's (the activity passes it on otherwise). */
  fun onKey(e: KeyEvent): Boolean {
    val d = e.device ?: return false
    if (!Controllers.isGameController(d)) return false
    val p = profile(d)
    val bound = p.bind.values.any { l -> l.any { it is Binding.Key && it.code == e.keyCode } }
    if (!bound && !KeyEvent.isGamepadButton(e.keyCode) && e.keyCode !in DPAD_KEYS) return false
    if (!bound && e.keyCode == KeyEvent.KEYCODE_BUTTON_MODE) return false     /* the menu's (the activity) */
    val st = states.getOrPut(d.id) { PadState() }
    if (st.onKey(e)) {
      assignment[d.id]?.let { sendPlayer(it) }
    }
    return true
  }

  /** A controller's sticks, triggers and hat. */
  fun onMotion(e: MotionEvent): Boolean {
    val d = e.device ?: return false
    if ((e.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) return false
    if (!Controllers.isGameController(d) || e.actionMasked != MotionEvent.ACTION_MOVE) return false
    val st = states.getOrPut(d.id) { PadState() }
    st.onMotion(e)
    assignment[d.id]?.let { sendPlayer(it) }
    return true
  }

  /** device id -> the levels last sent (left << 8 | right) and when */
  private val rumble = HashMap<Int, Int>()
  private val rumbleAt = HashMap<Int, Long>()
  /** device id -> whether it has motors of its own (asked once) */
  private val motors = HashMap<Int, Boolean>()

  /**
   * The titles' rumble to the controllers playing (polled): each change of
   * level, and every 900 ms while it lasts (a vibration is asked for a second
   * at a time, as SDL's rumble was). A controller without motors (a
   * handheld's own controls, which Android reports as external USB, or a
   * clip-on) rumbles the device instead: the first player's such controller,
   * so that several don't share it.
   */
  fun pollRumble() {
    val now = SystemClock.uptimeMillis()
    var body = -1
    var bodyPlayer = Controllers.PLAYERS
    for ((id, p) in assignment) {
      if (p < bodyPlayer && InputDevice.getDevice(id)?.let { hasMotors(it) } == false) {
        body = id
        bodyPlayer = p
      }
    }
    for ((id, p) in assignment) {
      val raw = rumbleOf(p)
      val r = level(raw ushr 16) shl 8 or level(raw and 0xFFFF)
      val last = rumble[id] ?: 0
      if (r == last && (r == 0 || now - (rumbleAt[id] ?: 0) < 900)) continue
      rumble[id] = r
      rumbleAt[id] = now
      InputDevice.getDevice(id)?.let { vibrate(it, r ushr 8, r and 0xFF, id == body) }
    }
  }

  /** Every controller still, when the game goes away. */
  fun stopRumble() {
    for (id in rumble.keys) InputDevice.getDevice(id)?.let { vibrate(it, 0, 0, true) }
    rumble.clear()
  }

  private fun hasMotors(d: InputDevice): Boolean = motors.getOrPut(d.id) {
    try {
      if (Build.VERSION.SDK_INT >= 31) {
        d.vibratorManager.vibratorIds.isNotEmpty()
      } else {
        @Suppress("DEPRECATION")
        d.vibrator.hasVibrator()
      }
    } catch (_: Exception) {
      false
    }
  }

  /** A strength (0..65535) as a motor's amplitude, 0..255 (0 is off, as SDL's). */
  private fun level(v: Int) = (v * 255 + 32767) / 65535

  /*
   * A motor without amplitude control is on or off: on from a quarter of the
   * strength (else a title's faint engine rumble would be the motor's all).
   */
  private fun amplitude(v: Int, control: Boolean): Int = when {
    control -> v
    v >= ONOFF_MIN -> VibrationEffect.DEFAULT_AMPLITUDE
    else -> 0
  }

  /** One motor: the stronger of the two. */
  private fun vibrateOne(vib: Vibrator, left: Int, right: Int) {
    val a = amplitude(maxOf(left, right), vib.hasAmplitudeControl())
    if (a == 0) {
      vib.cancel()
      return
    }
    val e = VibrationEffect.createOneShot(1000, a)
    if (Build.VERSION.SDK_INT >= 33) {
      vib.vibrate(e, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
    } else {
      @Suppress("DEPRECATION")
      vib.vibrate(e, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
    }
  }

  /** The device's own motor. */
  private fun bodyVibrator(): Vibrator? {
    val v = if (Build.VERSION.SDK_INT >= 31) {
      context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
      context.getSystemService(Vibrator::class.java)
    }
    return v?.takeIf { it.hasVibrator() }
  }

  /** A controller's rumble (levels): on its motors, or (body) on the device's when it has none. */
  private fun vibrate(d: InputDevice, left: Int, right: Int, body: Boolean) {
    try {
      if (!hasMotors(d)) {
        if (body) bodyVibrator()?.let { vibrateOne(it, left, right) }
        return
      }
      if (Build.VERSION.SDK_INT < 31) {
        @Suppress("DEPRECATION")
        vibrateOne(d.vibrator, left, right)
        return
      }
      val vm = d.vibratorManager
      val ids = vm.vibratorIds
      val c = CombinedVibration.startParallel()
      var any = false
      /* two motors: the left (heavy) and the right (light) as the Duke's */
      for ((i, vid) in ids.withIndex()) {
        val v = if (ids.size >= 2) (if (i == 0) left else right) else maxOf(left, right)
        val a = amplitude(v, vm.getVibrator(vid).hasAmplitudeControl())
        if (a == 0) continue
        c.addVibrator(vid, VibrationEffect.createOneShot(1000, a))
        any = true
      }
      when {
        !any -> vm.cancel()
        Build.VERSION.SDK_INT >= 33 ->
          vm.vibrate(c.combine(), VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
        else -> vm.vibrate(c.combine())
      }
    } catch (_: Exception) {
      /* a controller gone meanwhile, or one that refuses: no rumble */
    }
  }

  companion object {
    private val DPAD_KEYS = setOf(
      KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
      KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK,
    )
    /** The level (of 255) from which an on/off motor is on. */
    private const val ONOFF_MIN = 64
  }
}
