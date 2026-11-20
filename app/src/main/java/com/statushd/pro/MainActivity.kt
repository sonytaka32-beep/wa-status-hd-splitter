package com.statushd.pro

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.antonkarpenko.ffmpegkit.Statistics
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var tvMediaInfo: TextView
    private lateinit var tvStatusLog: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnProcess: Button
    private lateinit var btnShareWa: Button
    private lateinit var rgRotateMode: RadioGroup
    private lateinit var rbRotate90: RadioButton

    private var selectedUri: Uri? = null
    private var isVideoMode: Boolean = true
    private var videoDurationSec: Int = 0
    private var origDisplayW: Int = 720
    private var origDisplayH: Int = 1280
    private var savedOriginalSizeKb: Long = 0L

    private val outputFiles = ArrayList<File>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    data class VideoPlan(
        val outW: Int,
        val outH: Int,
        val transposeFilter: String?,
        val rotateDesc: String,
        val bitrateKbps: Int,
        val splitIntervalSec: Int
    )

    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { selectedUri = it; isVideoMode = true; analyzeVideo(it) }
    }

    private val pickPhotoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { selectedUri = it; isVideoMode = false; analyzePhoto(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvMediaInfo = findViewById(R.id.tvMediaInfo)
        tvStatusLog = findViewById(R.id.tvStatusLog)
        progressBar = findViewById(R.id.progressBar)
        btnProcess = findViewById(R.id.btnProcess)
        btnShareWa = findViewById(R.id.btnShareWa)
        rgRotateMode = findViewById(R.id.rgRotateMode)
        rbRotate90 = findViewById(R.id.rbRotate90)

        findViewById<Button>(R.id.btnPickVideo).setOnClickListener { pickVideoLauncher.launch("video/*") }
        findViewById<Button>(R.id.btnPickPhoto).setOnClickListener { pickPhotoLauncher.launch("image/*") }

        rgRotateMode.setOnCheckedChangeListener { _, _ ->
            if (isVideoMode && selectedUri != null) updateVideoSplitPreview()
        }

        btnProcess.setOnClickListener {
            val uri = selectedUri ?: return@setOnClickListener
            if (isVideoMode) processVideoBypass(uri) else processPhotoBypass(uri)
        }
        btnShareWa.setOnClickListener { shareToWhatsApp() }
    }

    private fun getFileSizeKb(uri: Uri): Long {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst() && sizeIndex != -1) cursor.getLong(sizeIndex) / 1024L else 0L
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun buildVideoPlan(): VideoPlan {
        val isHorizontal = origDisplayW > origDisplayH
        val forcePortrait = rbRotate90.isChecked && isHorizontal

        val transposeFilter: String?
        val rotateDesc: String
        var rotatedW: Int
        var rotatedH: Int

        if (forcePortrait) {
            // Metadata rotation is applied by the encoder, so swap the working dimensions too.
            transposeFilter = "transpose=1"
            rotateDesc = "Auto Portrait"
            rotatedW = origDisplayH
            rotatedH = origDisplayW
        } else {
            transposeFilter = null
            rotateDesc = "Original"
            rotatedW = origDisplayW
            rotatedH = origDisplayH
        }

        if (rotatedW <= 0) rotatedW = 720
        if (rotatedH <= 0) rotatedH = 1280

        val maxSide = maxOf(rotatedW, rotatedH)
        val minSide = minOf(rotatedW, rotatedH)

        val scale = if (maxSide > 1920 || minSide > 1080) {
            minOf(1920f / maxSide.toFloat(), 1080f / minSide.toFloat())
        } else {
            1.0f
        }

        var targetW = ((rotatedW * scale).toInt() / 2) * 2
        var targetH = ((rotatedH * scale).toInt() / 2) * 2
        if (targetW < 2) targetW = 720
        if (targetH < 2) targetH = 1280

        val interval = 30
        val effectiveClipSec = minOf(max(1, videoDurationSec), interval)
        val bitrateKbps = ((9.5 * 8192.0) / effectiveClipSec - 128.0).toInt().coerceIn(3500, 7500)

        return VideoPlan(targetW, targetH, transposeFilter, rotateDesc, bitrateKbps, interval)
    }

    private fun analyzeVideo(uri: Uri) {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 720
            val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1280
            val metaRot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            retriever.release()

            if (metaRot == 90 || metaRot == 270) {
                origDisplayW = rawH
                origDisplayH = rawW
            } else {
                origDisplayW = rawW
                origDisplayH = rawH
            }

            savedOriginalSizeKb = getFileSizeKb(uri)
            videoDurationSec = max(1, (durationMs / 1000).toInt())
            updateVideoSplitPreview()

            progressBar.progress = 0
            tvStatusLog.text = "System: Video Loaded. Ready for Injection."
            btnProcess.isEnabled = true
            btnShareWa.isEnabled = false
        } catch (e: Exception) {
            tvMediaInfo.text = "Error reading video: ${e.message}"
        }
    }

    private fun updateVideoSplitPreview() {
        val plan = buildVideoPlan()
        val totalParts = ceil(videoDurationSec.toDouble() / plan.splitIntervalSec.toDouble()).toInt()

        tvMediaInfo.text = "> SONYBAE DEFINITIVE ENGINE (VIDEO)\n" +
                "> Source: ${origDisplayW}x${origDisplayH} | ${videoDurationSec}s | ${savedOriginalSizeKb} KB\n" +
                "> Target: ${plan.outW}x${plan.outH} (BRAVO HD ENGINE V1)\n" +
                "> Config: ${plan.rotateDesc} | Parts: $totalParts (@${plan.splitIntervalSec}s)"
    }

    private fun analyzePhoto(uri: Uri) {
        try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOpts)
            }
            origDisplayW = boundsOpts.outWidth
            origDisplayH = boundsOpts.outHeight
            savedOriginalSizeKb = getFileSizeKb(uri)

            tvMediaInfo.text = "> SONYBAE DEFINITIVE ENGINE (PHOTO)\n" +
                    "> Source: ${origDisplayW}x${origDisplayH} | ${savedOriginalSizeKb} KB\n" +
                    "> Target: MP4 Stream 5s Container (PlayStore Method)\n" +
                    "> Action: Direct WA Photo Bypass Injector"
            progressBar.progress = 0
            tvStatusLog.text = "System: Photo Loaded. Ready for Build."
            btnProcess.isEnabled = true
            btnShareWa.isEnabled = false
        } catch (e: Exception) {
            tvMediaInfo.text = "Error reading photo: ${e.message}"
        }
    }

    private fun prepareOutputDir(): File {
        val dir = File(externalCacheDir ?: cacheDir, "status_hd_out")
        if (dir.exists()) dir.listFiles()?.forEach { it.delete() } else dir.mkdirs()
        outputFiles.clear()
        return dir
    }

    private fun processVideoBypass(uri: Uri) {
        btnProcess.isEnabled = false
        btnShareWa.isEnabled = false
        progressBar.progress = 2
        tvStatusLog.text = "Injecting BRAVO HD Engine (Anti-Compression)..."

        scope.launch(Dispatchers.IO) {
            try {
                val outDir = prepareOutputDir()
                val safeInputPath = FFmpegKitConfig.getSafParameterForRead(this@MainActivity, uri)
                val outputPattern = File(outDir, "StatusHD_Part_%03d.mp4").absolutePath

                val plan = buildVideoPlan()
                val interval = plan.splitIntervalSec
                val gopFrames = 30 * minOf(max(1, videoDurationSec), interval)

                val filters = ArrayList<String>()
                plan.transposeFilter?.let { filters.add(it) }
                filters.add("scale=${plan.outW}:${plan.outH}:flags=lanczos")
                filters.add("format=yuv420p")
                val vfChain = filters.joinToString(",")

                FFmpegKitConfig.enableStatisticsCallback { stats: Statistics ->
                    val timeSec = (stats.time / 1000.0).toInt()
                    val pct = if (videoDurationSec > 0) {
                        ((timeSec * 100) / videoDurationSec).coerceIn(2, 99)
                    } else {
                        50
                    }
                    runOnUiThread {
                        progressBar.progress = pct
                        tvStatusLog.text = "Memproses Video HD+ (${plan.outW}x${plan.outH}): $pct% (${timeSec}s / ${videoDurationSec}s)"
                    }
                }

                val cmd = "-y -i \"$safeInputPath\" " +
                        "-vf \"$vfChain\" " +
                        "-r 30 -g $gopFrames " +
                        "-c:v libx264 -preset fast -profile:v high -level 4.2 -threads 8 " +
                        "-crf 15 -maxrate ${plan.bitrateKbps}k -bufsize ${plan.bitrateKbps * 2}k " +
                        "-c:a aac -b:a 128k -ac 2 " +
                        "-f segment -segment_time $interval -reset_timestamps 1 " +
                        "\"$outputPattern\""

                val session = FFmpegKit.execute(cmd)

                withContext(Dispatchers.Main) {
                    if (ReturnCode.isSuccess(session.returnCode)) {
                        val generated = outDir.listFiles()
                            ?.filter { it.name.startsWith("StatusHD_Part_") && it.length() > 0 }
                            ?.sortedBy { it.name } ?: emptyList()

                        outputFiles.addAll(generated)
                        progressBar.progress = 100
                        tvStatusLog.text = "System: Video Compression Complete!"
                        btnShareWa.isEnabled = outputFiles.isNotEmpty()
                    } else {
                        tvStatusLog.text = "Error: Video Compression Failed. ${session.failStackTrace}"
                    }
                    btnProcess.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatusLog.text = "Error: ${e.message}"
                    btnProcess.isEnabled = true
                }
            }
        }
    }

    private fun processPhotoBypass(uri: Uri) {
        btnProcess.isEnabled = false
        btnShareWa.isEnabled = false
        progressBar.progress = 25
        tvStatusLog.text = "Building Static MP4 Stream Container..."

        scope.launch(Dispatchers.IO) {
            try {
                val outDir = prepareOutputDir()

                val decodeOpts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                var bmp = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, decodeOpts)
                } ?: throw Exception("Image decode error")

                val rot = contentResolver.openInputStream(uri)?.use { input ->
                    when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                        6 -> 90f
                        3 -> 180f
                        8 -> 270f
                        else -> 0f
                    }
                } ?: 0f

                if (rot != 0f) {
                    val mat = Matrix().apply { postRotate(rot) }
                    val rotBmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mat, true)
                    if (rotBmp != bmp) {
                        bmp.recycle()
                        bmp = rotBmp
                    }
                }

                val scale = minOf(
                    1920f / maxOf(bmp.width, bmp.height).toFloat(),
                    1080f / minOf(bmp.width, bmp.height).toFloat()
                ).coerceAtMost(1.0f)

                var pW = (bmp.width * scale).toInt()
                var pH = (bmp.height * scale).toInt()
                if (pW < 2) pW = 2
                if (pH < 2) pH = 2
                if (pW % 2 != 0) pW++
                if (pH % 2 != 0) pH++

                if (bmp.width != pW || bmp.height != pH) {
                    val resized = Bitmap.createScaledBitmap(bmp, pW, pH, true)
                    if (resized != bmp) {
                        bmp.recycle()
                        bmp = resized
                    }
                }

                val tempPng = File(cacheDir, "hd_frame.png")
                FileOutputStream(tempPng).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()

                runOnUiThread {
                    progressBar.progress = 60
                    tvStatusLog.text = "Encoding Stream MP4 Tanpa Zoompan..."
                }

                val outFile = File(outDir, "StatusHD_Photo.mp4")
                val cmd = "-y -loop 1 -framerate 30 -i \"${tempPng.absolutePath}\" " +
                        "-t 5 " +
                        "-c:v libx264 -preset medium -tune stillimage " +
                        "-profile:v high -level 4.2 -pix_fmt yuv420p " +
                        "-crf 15 -maxrate 6000k -bufsize 12000k " +
                        "-movflags +faststart " +
                        "\"${outFile.absolutePath}\""

                val session = FFmpegKit.execute(cmd)
                tempPng.delete()

                withContext(Dispatchers.Main) {
                    if (ReturnCode.isSuccess(session.returnCode) && outFile.exists() && outFile.length() > 0) {
                        outputFiles.add(outFile)
                        progressBar.progress = 100
                        tvStatusLog.text = "System: Photo Stream Ready. Bye bye blur!"
                        btnShareWa.isEnabled = true
                    } else {
                        tvStatusLog.text = "Error: Failed to encode stream."
                    }
                    btnProcess.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatusLog.text = "Error: ${e.message}"
                    btnProcess.isEnabled = true
                }
            }
        }
    }

    private fun shareToWhatsApp() {
        if (outputFiles.isEmpty()) return
        val uris = outputFiles.map { FileProvider.getUriForFile(this, "${packageName}.provider", it) }

        val mimeType = "video/mp4"

        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }

        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.setPackage("com.whatsapp")

        try {
            startActivity(intent)
        } catch (e: Exception) {
            intent.setPackage(null)
            startActivity(Intent.createChooser(intent, "Send to Status WA"))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
