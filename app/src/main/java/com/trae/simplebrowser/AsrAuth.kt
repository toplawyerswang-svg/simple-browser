package com.trae.simplebrowser

import android.app.Activity
import android.content.DialogInterface
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** 音频识别功能令牌门控：无有效令牌时先弹输入框，经 ECS 核验通过方可使用。 */
object AsrAuth {

    fun hasToken(activity: Activity): Boolean = AppPrefs.getAsrToken(activity).isNotEmpty()

    /** 确保已有令牌：已存令牌直接放行（失效时由接口401触发重新输入）；否则弹输入框。 */
    fun ensureVerified(activity: AppCompatActivity, onReady: (String) -> Unit) {
        val saved = AppPrefs.getAsrToken(activity)
        if (saved.isNotEmpty()) {
            onReady(saved)
            return
        }
        promptTokenInput(activity, onReady)
    }

    /** 弹出令牌输入框，核验通过后保存并回调 onReady(token)。 */
    fun promptTokenInput(activity: AppCompatActivity, onReady: (String) -> Unit) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_asr_token, null)
        val input = view.findViewById<EditText>(R.id.asr_token_input)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.asr_token_dialog_title)
            .setMessage(R.string.asr_token_dialog_hint)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(activity.getString(R.string.asr_token_verify), null as DialogInterface.OnClickListener?)
            .show()
        val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        positive.setOnClickListener {
            val token = input.text.toString().trim()
            if (token.length < 40) {
                Toast.makeText(activity, R.string.asr_token_too_short, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            positive.isEnabled = false
            Thread {
                try {
                    val user = AsrClient.verifyToken(activity, token)
                    AppPrefs.setAsrAuth(activity, token, user)
                    activity.runOnUiThread {
                        if (activity.isFinishing) return@runOnUiThread
                        dialog.dismiss()
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.asr_token_welcome, user),
                            Toast.LENGTH_SHORT
                        ).show()
                        onReady(token)
                    }
                } catch (e: AsrAuthException) {
                    activity.runOnUiThread {
                        positive.isEnabled = true
                        Toast.makeText(activity, R.string.asr_token_invalid, Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    activity.runOnUiThread {
                        positive.isEnabled = true
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.asr_failed_prefix) + (e.message ?: e.toString()),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }.start()
        }
    }
}
