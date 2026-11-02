package com.rfandango.haku_x.ui.controllers

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.rfandango.haku_x.R
import com.rfandango.haku_x.data.Controllers
import com.rfandango.haku_x.data.PlayerSlot
import com.rfandango.haku_x.ui.common.ChoiceDialog
import com.rfandango.haku_x.ui.common.EmptyText
import com.rfandango.haku_x.ui.common.Hint
import com.rfandango.haku_x.ui.common.Section
import com.rfandango.haku_x.ui.common.SubScreen
import com.rfandango.haku_x.ui.common.SwitchItem

/** The connected controllers, kept current while the screen shows. */
@Composable
internal fun rememberControllers(): List<InputDevice> {
  val context = LocalContext.current
  var devices by remember { mutableStateOf(Controllers.connected()) }
  DisposableEffect(Unit) {
    val im = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    val l = object : InputManager.InputDeviceListener {
      override fun onInputDeviceAdded(id: Int) { devices = Controllers.connected() }
      override fun onInputDeviceRemoved(id: Int) { devices = Controllers.connected() }
      override fun onInputDeviceChanged(id: Int) { devices = Controllers.connected() }
    }
    im.registerInputDeviceListener(l, null)
    onDispose { im.unregisterInputDeviceListener(l) }
  }
  return devices
}

/**
 * The physical controllers: which plays as which of the Xbox's four players,
 * and each controller's mapping. Read when a game starts;
 * the game's menu (Controllers) changes the players during a game.
 */
@Composable
fun ControllersScreen(onBack: () -> Unit, onMap: (deviceId: Int) -> Unit) {
  val context = LocalContext.current
  var config by remember { mutableStateOf(Controllers.load(context)) }
  val devices = rememberControllers()
  var picking by remember { mutableStateOf<Int?>(null) }

  fun update(c: Controllers.Config) {
    Controllers.save(context, c)
    config = c
  }

  SubScreen(title = stringResource(R.string.controllers_title), onBack = onBack) { padding ->
    Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
      Hint(stringResource(R.string.controllers_desc))
      val assigned = config.assign(devices)
      val byId = devices.associateBy { it.id }
      Section(stringResource(R.string.controllers_players))
      SwitchItem(
        title = stringResource(R.string.controllers_share_p1),
        summary = stringResource(R.string.controllers_share_p1_desc),
        checked = config.shareP1,
        onChange = { update(config.copy(shareP1 = it)) },
      )
      for (p in 0 until Controllers.PLAYERS) {
        val s = config.slots[p]
        val now = assigned.entries.firstOrNull { it.value == p }?.let { byId[it.key]?.name }
        val what = when (s.mode) {
          PlayerSlot.OFF -> stringResource(R.string.controllers_off)
          PlayerSlot.DEVICE -> now ?: stringResource(R.string.controllers_not_connected, s.name)
          else -> now?.let { stringResource(R.string.controllers_auto_is, it) } ?: stringResource(R.string.controllers_auto)
        }
        ListItem(
          headlineContent = { Text(stringResource(R.string.controllers_player, p + 1)) },
          supportingContent = { Text(what) },
          modifier = Modifier.clickable { picking = p },
        )
      }

      Section(stringResource(R.string.controllers_connected))
      if (devices.isEmpty()) {
        EmptyText(stringResource(R.string.controllers_none))
      }
      for (d in devices) {
        val player = assigned[d.id]
        val mapping = when {
          config.hasUnitProfile(d) -> stringResource(R.string.controllers_map_unit)
          config.profiles.containsKey(Controllers.modelKey(d)) -> stringResource(R.string.controllers_map_model)
          else -> stringResource(R.string.controllers_map_default)
        }
        val playing = player?.let { stringResource(R.string.controllers_player, it + 1) }
          ?: stringResource(R.string.controllers_not_playing)
        ListItem(
          headlineContent = { Text(d.name ?: "?") },
          supportingContent = { Text("$playing · $mapping") },
          modifier = Modifier.clickable { onMap(d.id) },
        )
      }
    }
  }

  picking?.let { p ->
    val options = mutableListOf<Pair<PlayerSlot, String>>(PlayerSlot() to stringResource(R.string.controllers_auto))
    for (d in devices) options += PlayerSlot(PlayerSlot.DEVICE, Controllers.unitKey(d), d.name ?: "") to (d.name ?: "?")
    val cur = config.slots[p]
    if (cur.mode == PlayerSlot.DEVICE && options.none { it.first.key == cur.key }) {
      options += cur to stringResource(R.string.controllers_not_connected, cur.name)
    }
    if (p > 0) options += PlayerSlot(PlayerSlot.OFF) to stringResource(R.string.controllers_off)
    ChoiceDialog(
      title = stringResource(R.string.controllers_player, p + 1),
      options = options,
      current = options.firstOrNull { it.first == cur }?.first ?: options[0].first,
      onPick = { slot ->
        /* a controller chosen for this player leaves any other it played as */
        val slots = config.slots.mapIndexed { i, s ->
          when {
            i == p -> slot
            slot.mode == PlayerSlot.DEVICE && s.mode == PlayerSlot.DEVICE && s.key == slot.key -> PlayerSlot()
            else -> s
          }
        }
        update(config.copy(slots = slots))
        picking = null
      },
      onDismiss = { picking = null },
    )
  }
}
