package com.lagradost.cloudstream4.download

import android.app.Activity
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.core.widget.doOnTextChanged
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.StreamInputBinding
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.ui.player.BasicLink
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.LinkGenerator
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import java.net.URI

/**
 * `DownloadFragment.showStreamInputDialog`, the same dialog and layout. That one is private to a fragment that is staying
 * unchanged as the rollback, and this copy leaves with it: no board draws a new dialog (`Q14`).
 */
internal fun Activity.showPlayFromUrlDialog() {
    val dialog = Dialog(this, R.style.AlertDialogCustom)
    val binding = StreamInputBinding.inflate(dialog.layoutInflater)
    dialog.setContentView(binding.root)
    dialog.show()

    var preventAutoSwitching = false
    fun matchHlsSwitch(text: String?) {
        binding.hlsSwitch.isChecked = safe { URI(text).path?.substringAfterLast(".")?.contains("m3u") } == true
    }
    binding.hlsSwitch.setOnClickListener { preventAutoSwitching = true }
    binding.streamReferer.doOnTextChanged { text, _, _, _ ->
        if (!preventAutoSwitching) matchHlsSwitch(text?.toString())
    }
    (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip?.getItemAt(0)?.text?.toString()
        ?.let { copy ->
            val fixedText = copy.trim()
            binding.streamUrl.setText(fixedText)
            matchHlsSwitch(fixedText)
        }

    binding.applyBtt.setOnClickListener {
        val url = binding.streamUrl.text?.toString()
        if (url.isNullOrEmpty()) {
            showToast(R.string.error_invalid_url, Toast.LENGTH_SHORT)
        } else {
            navigate(
                R.id.global_to_navigation_player,
                GeneratorPlayer.newInstance(
                    LinkGenerator(
                        listOf(BasicLink(url)),
                        extract = true,
                        refererUrl = binding.streamReferer.text?.toString(),
                        id = url.hashCode(),
                    ),
                    0,
                ),
            )
            dialog.dismissSafe(this)
        }
    }
    binding.cancelBtt.setOnClickListener { dialog.dismissSafe(this) }
}
