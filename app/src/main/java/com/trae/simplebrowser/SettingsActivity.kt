package com.trae.simplebrowser

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.trae.simplebrowser.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val current = AppPrefs.getHideControls(this)
        binding.hideControlsSwitch.isChecked = current
        binding.hideControlsSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppPrefs.setHideControls(this, isChecked)
        }

        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrDefault("Unknown")
        binding.versionTextView.text = getString(R.string.label_version, versionName)
    }

    // Two-finger swipe down gesture variables
    private var startY1 = 0f
    private var startY2 = 0f
    private var isTwoFingerGesture = false
    private val swipeThresholdPx by lazy { 100 * resources.displayMetrics.density }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        val action = ev.actionMasked
        when (action) {
            android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount == 2) {
                    startY1 = ev.getY(0)
                    startY2 = ev.getY(1)
                    isTwoFingerGesture = true
                }
            }
            android.view.MotionEvent.ACTION_POINTER_UP -> {
                 if (ev.pointerCount <= 2) {
                     isTwoFingerGesture = false
                 }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                isTwoFingerGesture = false
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (isTwoFingerGesture && ev.pointerCount >= 2) {
                    val y1 = ev.getY(0)
                    val y2 = ev.getY(1)
                    
                    val dy1 = y1 - startY1
                    val dy2 = y2 - startY2
                    
                    // Check if both fingers moved down significantly (> 100dp)
                    if (dy1 > swipeThresholdPx && dy2 > swipeThresholdPx) {
                        isTwoFingerGesture = false // Reset
                        finish() // Close the settings activity
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}
