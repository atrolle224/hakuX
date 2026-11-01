package com.rfandango.haku_x

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.rfandango.haku_x.data.SaveStateStore

/** A busy dialog's content: a spinner and a line (Save state's, a savestate load's). */
class BusyView(context: Context, line: String) {
  val text = TextView(context).apply {
    text = line
    setPadding(dp(context, 20), 0, 0, 0)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
  }

  val view = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    val pad = dp(context, 20)
    setPadding(pad, pad, pad, pad)
    addView(ProgressBar(context))
    addView(text)
  }

  private fun dp(context: Context, v: Int) =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()
}

/**
 * A savestate's load: Save state's busy dialog, "Loading slot n…", over the
 * frame held on screen (RelaunchActivity, then NativeCoreActivity) until the
 * game shows its own. Not a menu dialog: the game runs under it, and keeps
 * the focus (its bars hidden). NativeCoreActivity puts the shader build's
 * progress in its line meanwhile.
 */
class LoadingDialog(activity: Activity, state: String) {
  /** "Loading slot n…" */
  val line: String = SaveStateStore.slotOf(state)?.takeIf { !SaveStateStore.isAuto(it) }?.let { activity.getString(R.string.state_loading, it) }
    ?: activity.getString(R.string.state_loading_any)

  private val busy = BusyView(activity, line)

  private val dialog: AlertDialog = AlertDialog.Builder(activity)
    .setView(busy.view)
    .setCancelable(false)
    .create()
    .apply {
      window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
      window?.setWindowAnimations(0)    /* one dialog from RelaunchActivity's to the core's */
    }

  fun show() = dialog.show()

  fun dismiss() = dialog.dismiss()

  /** The line: the shader build's progress, or (null) "Loading slot n…" again. */
  fun say(s: String?) {
    busy.text.text = s ?: line
  }
}
