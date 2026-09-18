package com.trae.simplebrowser

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.exifinterface.media.ExifInterface
import com.trae.simplebrowser.core.Nv21Frame
import com.trae.simplebrowser.core.QrCodeDecoder
import com.trae.simplebrowser.core.YuvNv21
import com.trae.simplebrowser.databinding.ActivityScanBinding
import android.util.Size
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * 扫码页：CameraX 实时取景 + ZXing 解码 + 相册识码。
 *
 * 结果处理约定（与产品确认一致）：
 * - 网址 → 直接交给 MainActivity 打开；
 * - 纯文本 → 弹窗提供「复制 / 搜索 / 取消」，取消后可继续扫码。
 *
 * 入口有两处：地址栏扫码图标（MainActivity 内启动）与桌面长按图标快捷方式（独立任务根）。
 * 两种入口下结果都统一经 [openInBrowser] 回到 MainActivity，页面自身用完即 finish。
 */
class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private val logTag = "ScanActivity"

    private var camera: Camera? = null
    private var torchOn = false

    /** 一次识别只处理一帧；取消文本弹窗后复位以继续扫码 */
    @Volatile
    private var handled = false

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Log.w(logTag, "camera permission denied -> finish")
                Toast.makeText(this, R.string.scan_camera_permission_denied, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) decodeFromGallery(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(logTag, "onCreate action=${intent?.action}")
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnTorch.setOnClickListener { toggleTorch() }
        binding.btnAlbum.setOnClickListener { pickImageLauncher.launch("image/*") }

        applySystemBarInsets()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            Log.d(logTag, "request camera permission")
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.topBar) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top, view.paddingRight, view.paddingBottom)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.hintText) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (view.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { lp ->
                lp.bottomMargin = bars.bottom + (24 * resources.displayMetrics.density).toInt()
                view.layoutParams = lp
            }
            insets
        }
    }

    private fun startCamera() {
        Log.i(logTag, "startCamera")
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching {
                val provider = future.get()
                provider.unbindAll()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1280, 720),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor, ::analyzeFrame)

                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
                Log.i(logTag, "camera bound (back, 1280x720 analysis)")
            }.onFailure {
                Log.e(logTag, "camera bind failed", it)
                Toast.makeText(this, R.string.scan_no_camera, Toast.LENGTH_SHORT).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** 分析线程：NV21 提取 → 按屏幕方向旋转 → ZXing 解码；识别成功回调 UI 线程 */
    private fun analyzeFrame(proxy: ImageProxy) {
        if (handled || isFinishing) {
            proxy.close()
            return
        }
        val text = runCatching {
            val planes = proxy.planes
            val nv21 = YuvNv21.yuv420888ToNv21(
                planes[0].buffer, planes[1].buffer, planes[2].buffer,
                proxy.width, proxy.height,
                planes[0].rowStride, planes[1].rowStride, planes[1].pixelStride
            )
            val rotated = YuvNv21.rotate(
                Nv21Frame(nv21, proxy.width, proxy.height),
                proxy.imageInfo.rotationDegrees
            )
            QrCodeDecoder.decodeNv21(rotated.data, rotated.width, rotated.height)
        }.onFailure { Log.w(logTag, "frame decode failed: ${it.message}") }.getOrNull()
        proxy.close()

        if (text != null) runOnUiThread { onDecoded(text) }
    }

    private fun onDecoded(raw: String) {
        if (handled || isFinishing) return
        val text = raw.trim()
        if (text.isEmpty()) return
        handled = true
        Log.i(logTag, "decoded: len=${text.length} head=${text.take(48)}")
        vibrate()
        if (QrCodeDecoder.isProbablyUrl(text)) {
            openInBrowser(QrCodeDecoder.normalizeUrl(text))
        } else {
            showTextResultDialog(text)
        }
    }

    private fun showTextResultDialog(text: String) {
        Log.d(logTag, "show text result dialog")
        AlertDialog.Builder(this)
            .setTitle(R.string.scan_result_title)
            .setMessage(text)
            .setPositiveButton(R.string.scan_action_copy) { _, _ ->
                copyText(text)
                finish()
            }
            .setNegativeButton(R.string.scan_action_search) { _, _ ->
                val searchUrl =
                    "https://www.google.com/search?q=" + URLEncoder.encode(text, "UTF-8")
                openInBrowser(searchUrl)
            }
            .setNeutralButton(android.R.string.cancel, null)
            .setOnDismissListener {
                // 用户取消：复位识别锁，继续扫码
                if (!isFinishing) {
                    handled = false
                    Log.d(logTag, "dialog dismissed -> resume scanning")
                }
            }
            .show()
    }

    private fun openInBrowser(url: String) {
        Log.i(logTag, "open in browser: $url")
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(url)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish()
    }

    private fun decodeFromGallery(uri: Uri) {
        if (handled) return
        Log.i(logTag, "gallery decode start: $uri")
        Thread {
            val text = runCatching {
                val bitmap = loadOrientedBitmap(uri)
                if (bitmap == null) {
                    Log.w(logTag, "gallery bitmap null")
                    null
                } else {
                    Log.d(logTag, "gallery bitmap ${bitmap.width}x${bitmap.height}")
                    val width = bitmap.width
                    val height = bitmap.height
                    val pixels = IntArray(width * height)
                    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                    bitmap.recycle()
                    QrCodeDecoder.decodeArgbPixels(pixels, width, height)
                }
            }.onFailure { Log.e(logTag, "gallery decode failed", it) }.getOrNull()
            runOnUiThread {
                if (text != null) {
                    onDecoded(text)
                } else {
                    Toast.makeText(this, R.string.scan_decode_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** 按容量降采样读取相册图，并根据 EXIF 方向摆正 */
    private fun loadOrientedBitmap(uri: Uri): Bitmap? {
        val resolver = contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_DIMENSION) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: return null

        val orientation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = android.graphics.Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> Unit
        }
        return if (matrix.isIdentity) bitmap
        else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        binding.btnTorch.alpha = if (torchOn) 1.0f else 0.6f
        Log.d(logTag, "torch=$torchOn")
    }

    private fun vibrate() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun copyText(text: String) {
        runCatching {
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                ClipData.newPlainText("scan", text)
            )
            Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        analysisExecutor.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        /** 相册大图长边降采样阈值，超出则逐步 2 倍降采样，避免 OOM 与解码过慢 */
        const val MAX_IMAGE_DIMENSION = 1600
    }
}
