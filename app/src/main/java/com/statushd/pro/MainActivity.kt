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
    private lateinit var rbRotateAuto: RadioButton
    private lateinit var rbRotateRight: RadioButton
    private lateinit var rbRotateLeft: RadioButton

    private lateinit var rgQualityMode: RadioGroup
    private lateinit var rbNativeHd: RadioButton

    private lateinit var rgSplitInterval: RadioGroup
    private lateinit var rb15Sec: RadioButton
    private lateinit var rb30Sec: RadioButton

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
        val bitrateKbps: Int
    )

    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            selectedUri = it
            isVideoMode = true
            analyzeVideo(it)
        }
    }

    private val pickPhotoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            selectedUri = it
            isVideoMode = false
            analyzePhoto(it)
        }
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
        rbRotateAuto = findViewById(R.id.rbRotateAuto)
        rbRotateRight = findViewById(R.id.rbRotateRight)
        rbRotateLeft = findViewById(R.id.rbRotateLeft)

        rgQualityMode = findViewById(R.id.rgQualityMode)
        rbNativeHd = findViewById(R.id.rbNativeHd)

        rgSplitInterval = findViewById(R.id.rgSplitInterval)
        rb15Sec = findViewById(R.id.rb15Sec)
        rb30Sec = findViewById(R.id.rb30Sec)

        findViewById<Button>(R.id.btnPickVideo).setOnClickListener {
            pickVideoLauncher.launch("video/*")
        }

        findViewById<Button>(R.id.btnPickPhoto).setOnClickListener {
            pickPhotoLauncher.launch("image/*")
        }

        rgRotateMode.setOnCheckedChangeListener { _, _ ->
            if (isVideoMode && selectedUri != null) updateVideoSplitPreview()
        }

        rgQualityMode.setOnCheckedChangeListener { _, _ ->
            if (isVideoMode && selectedUri != null) updateVideoSplitPreview()
        }

        rgSplitInterval.setOnCheckedChangeListener { _, _ ->
            if (isVideoMode && selectedUri != null) updateVideoSplitPreview()
        }

        btnProcess.setOnClickListener {
            val uri = selectedUri ?: return@setOnClickListener
            if (isVideoMode) processVideoHdAndSplit(uri) else processPhotoHd(uri)
        }

        btnShareWa.setOnClickListener {
            shareToWhatsApp()
        }
    }

    private fun getSplitInterval(): Int {
        return when {
            rb15Sec.isChecked -> 15
            rb30Sec.isChecked -> 30
            else -> 60
        }
    }

    private fun getFileSizeKb(uri: Uri): Long {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst() && sizeIndex != -1) {
                    cursor.getLong(sizeIndex) / 1024L
                } else 0L
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun buildVideoPlan(): VideoPlan {
        val isHorizontal = origDisplayW > origDisplayH

        val (transposeFilter, rotateDesc, rotatedW, rotatedH) = when {
            rbRotateRight.isChecked -> {
                Quadruple("transpose=1", "Putar 90° Kanan (Vertikal)", origDisplayH, origDisplayW)
            }
            rbRotateLeft.isChecked -> {
                Quadruple("transpose=2", "Putar 90° Kiri (Vertikal)", origDisplayH, origDisplayW)
            }
            rbRotateAuto.isChecked && isHorizontal -> {
                Quadruple("transpose=1", "Auto-Rotate 90° (Horizontal -> Vertikal Full Layar)", origDisplayH, origDisplayW)
            }
            else -> {
                Quadruple(null, "Orientasi Asli (Tanpa Rotasi)", origDisplayW, origDisplayH)
            }
        }

        val maxSide = maxOf(rotatedW, rotatedH)
        val minSide = minOf(rotatedW, rotatedH)

        // Hitung dimensi output tanpa pernah menyusutkan sisi pendek ke 322p
        val scale = if (rbNativeHd.isChecked) {
            // Pertahankan 1:1 piksel asli jika sudah <= 1080x1920 (misal 720x1612 tetap 720x1612)
            if (maxSide > 1920 || minSide > 1080) {
                minOf(1920f / maxSide.toFloat(), 1080f / minSide.toFloat())
            } else {
                1.0f
            }
        } else {
            // Mode Upscale Full HD 1920p (sisi panjang = 1920, sisi pendek <= 1080)
            minOf(1920f / maxSide.toFloat(), 1080f / minSide.toFloat())
        }

        var targetW = ((rotatedW * scale).toInt() / 2) * 2
        var targetH = ((rotatedH * scale).toInt() / 2) * 2
        if (targetW < 2) targetW = 720
        if (targetH < 2) targetH = 1280

        val interval = getSplitInterval()
        val effectiveClipSec = minOf(max(1, videoDurationSec), interval)
        // Target ukuran aman ~8.8 MB per klip agar bitrate tinggi namun tidak memicu limit 15MB WA
        val bitrateKbps = ((8.8 * 8192.0) / effectiveClipSec - 128.0).toInt().coerceIn(2200, 5000)

        return VideoPlan(targetW, targetH, transposeFilter, rotateDesc, bitrateKbps)
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

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
            tvStatusLog.text = "Status: Video siap! Tekan PROSES HD+ / AUTO SPLIT."
            btnProcess.isEnabled = true
            btnShareWa.isEnabled = false
        } catch (e: Exception) {
            tvMediaInfo.text = "Gagal membaca video: ${e.message}"
        }
    }

    private fun updateVideoSplitPreview() {
        val interval = getSplitInterval()
        val totalParts = ceil(videoDurationSec.toDouble() / interval.toDouble()).toInt()
        val plan = buildVideoPlan()
        val timelinePreview = StringBuilder()

        val maxPreview = minOf(totalParts, 8)
        for (i in 0 until maxPreview) {
            val start = i * interval
            val end = minOf((i + 1) * interval, videoDurationSec)
            timelinePreview.append("• Part ${i + 1}: ${start}s - ${end}s\n")
        }
        if (totalParts > 8) {
            timelinePreview.append("• ... s/d Part $totalParts (${videoDurationSec}s)\n")
        }

        val orientAsli = if (origDisplayW > origDisplayH) "Horizontal" else "Vertikal"
        val orientOut = if (plan.outW < plan.outH) "Vertikal Full Layar" else "Horizontal"

        tvMediaInfo.text = "MODE: TRUE NATIVE VIDEO HD+\n" +
                "Asli: ${origDisplayW}x${origDisplayH} ($orientAsli, ${savedOriginalSizeKb} KB) | Durasi: ${videoDurationSec}s\n" +
                "Rotasi: ${plan.rotateDesc}\n" +
                "Output HD+: ${plan.outW}x${plan.outH} ($orientOut) @ ${String.format("%.2f", plan.bitrateKbps / 1000.0)} Mbps\n" +
                "Total Potongan: $totalParts Bagian\n\n" +
                "Rincian Timeline:\n$timelinePreview"
    }

    private fun analyzePhoto(uri: Uri) {
        try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOpts)
            }
            val origW = boundsOpts.outWidth
            val origH = boundsOpts.outHeight
            val origKb = getFileSizeKb(uri)

            tvMediaInfo.text = "MODE: FOTO HD+ ANTI-BLUR (1920p STREAM)\n" +
                    "File Asli: ${origW}x${origH} (${origKb} KB)\n" +
                    "Bypass Kompresor Foto WA: Render 1920p Full HD (Teks & Detail Utuh)"
            progressBar.progress = 0
            tvStatusLog.text = "Status: Foto siap! Tekan PROSES HD+ / AUTO SPLIT."
            btnProcess.isEnabled = true
            btnShareWa.isEnabled = false
        } catch (e: Exception) {
            tvMediaInfo.text = "Gagal membaca info foto: ${e.message}"
        }
    }

    private fun prepareOutputDir(): File {
        val dir = File(externalCacheDir ?: cacheDir, "status_hd_out")
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
        } else {
            dir.mkdirs()
        }
        outputFiles.clear()
        return dir
    }

    private fun processVideoHdAndSplit(uri: Uri) {
        btnProcess.isEnabled = false
        btnShareWa.isEnabled = false
        progressBar.progress = 2
        tvStatusLog.text = "Merender Video True Native HD+..."

        scope.launch(Dispatchers.IO) {
            val outDir = prepareOutputDir()
            val safeInputPath = FFmpegKitConfig.getSafParameterForRead(this@MainActivity, uri)
            val interval = getSplitInterval()
            val outputPattern = File(outDir, "StatusHD_Part_%03d.mp4").absolutePath

            val plan = buildVideoPlan()
            val gopFrames = 30 * minOf(videoDurationSec, interval)

            // Rangkai filter: rotasi (jika aktif) + resolusi presisi tanpa unsharp berlebihan yang memicu halo/blur
            val filters = ArrayList<String>()
            if (plan.transposeFilter != null) {
                filters.add(plan.transposeFilter)
            }
            filters.add("scale=${plan.outW}:${plan.outH}:flags=lanczos")
            if (!rbNativeHd.isChecked) {
                // Micro-contrast sangat halus hanya jika mode Upscale 1920p dipilih
                filters.add("unsharp=3:3:0.25:3:3:0.0")
            }
            filters.add("format=yuv420p")
            val vfChain = filters.joinToString(",")

            FFmpegKitConfig.enableStatisticsCallback { stats: Statistics ->
                val timeSec = (stats.time / 1000.0).toInt()
                val pct = if (videoDurationSec > 0) ((timeSec * 100) / videoDurationSec).coerceIn(2, 99) else 50
                runOnUiThread {
                    progressBar.progress = pct
                    tvStatusLog.text = "Memproses HD+ (${plan.outW}x${plan.outH}): $pct% (${timeSec}s / ${videoDurationSec}s)"
                }
            }

            // Gunakan CRF 19 + deblock -1:-1 agar detail piksel asli tetap tajam alami tanpa artefak over-sharpening
            val cmd = "-y -i \"$safeInputPath\" " +
                    "-vf \"$vfChain\" " +
                    "-r 30 -g $gopFrames " +
                    "-c:v libx264 -preset fast -profile:v high -level 4.1 -threads 8 " +
                    "-crf 19 -maxrate ${plan.bitrateKbps}k -bufsize ${plan.bitrateKbps * 2}k " +
                    "-bf 2 -deblock -1:-1 " +
                    "-c:a aac -b:a 128k -ar 44100 -ac 2 " +
                    "-f segment -segment_time $interval -reset_timestamps 1 " +
                    "\"$outputPattern\""

            val session = FFmpegKit.execute(cmd)

            withContext(Dispatchers.Main) {
                if (ReturnCode.isSuccess(session.returnCode)) {
                    val generated = outDir.listFiles()
                        ?.filter { it.name.startsWith("StatusHD_Part_") && it.length() > 0 }
                        ?.sortedBy { it.name }
                        ?: emptyList()

                    outputFiles.addAll(generated)
                    val avgSizeMb = if (outputFiles.isNotEmpty()) {
                        String.format("%.1f", outputFiles.sumOf { it.length() } / (1024.0 * 1024.0 * outputFiles.size))
                    } else "0"

                    progressBar.progress = 100
                    tvStatusLog.text = "Selesai! ${outputFiles.size} klip (${plan.outW}x${plan.outH}, ~${avgSizeMb} MB/klip) siap dikirim ke WA!"
                    btnShareWa.isEnabled = outputFiles.isNotEmpty()
                } else {
                    tvStatusLog.text = "Gagal memproses video: ${session.failStackTrace}"
                }
                btnProcess.isEnabled = true
            }
        }
    }

    private fun processPhotoHd(uri: Uri) {
        btnProcess.isEnabled = false
        btnShareWa.isEnabled = false
        progressBar.progress = 25
        tvStatusLog.text = "Merender Foto ke Jalur Full HD 1920p Anti-Blur..."

        scope.launch(Dispatchers.IO) {
            try {
                val outDir = prepareOutputDir()

                val decodeOpts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                var bitmap = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, decodeOpts)
                } ?: throw Exception("Foto tidak terbaca")

                val rotationDegrees = contentResolver.openInputStream(uri)?.use { input ->
                    val exif = ExifInterface(input)
                    when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f

                if (rotationDegrees != 0f) {
                    val matrix = Matrix().apply { postRotate(rotationDegrees) }
                    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    if (rotated != bitmap) bitmap.recycle()
                    bitmap = rotated
                }

                val maxSide = maxOf(bitmap.width, bitmap.height)
                val scale = 1920f / maxSide.toFloat()
                var targetW = (bitmap.width * scale).toInt()
                var targetH = (bitmap.height * scale).toInt()
                if (targetW % 2 != 0) targetW += 1
                if (targetH % 2 != 0) targetH += 1

                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                if (scaledBitmap != bitmap) bitmap.recycle()

                val sharpenedBitmap = applyCrispSharpen(scaledBitmap)
                if (sharpenedBitmap != scaledBitmap) scaledBitmap.recycle()

                val tempPng = File(cacheDir, "hd_frame_source.png")
                FileOutputStream(tempPng).use { fos ->
                    sharpenedBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                }
                sharpenedBitmap.recycle()

                runOnUiThread {
                    progressBar.progress = 60
                    tvStatusLog.text = "Membungkus ke Kontainer Full HD Anti-Kompresi WA..."
                }

                val outFile = File(outDir, "StatusHD_Photo.mp4")
                val cmd = "-y -loop 1 -framerate 30 -i \"${tempPng.absolutePath}\" " +
                        "-t 5 -c:v libx264 -preset veryfast -tune stillimage " +
                        "-profile:v high -level 4.1 -pix_fmt yuv420p " +
                        "-b:v 6000k -maxrate 6500k -bufsize 12000k " +
                        "\"${outFile.absolutePath}\""

                val session = FFmpegKit.execute(cmd)
                tempPng.delete()

                withContext(Dispatchers.Main) {
                    if (ReturnCode.isSuccess(session.returnCode) && outFile.exists() && outFile.length() > 0) {
                        outputFiles.add(outFile)
                        progressBar.progress = 100
                        val sizeMb = String.format("%.1f", outFile.length() / (1024.0 * 1024.0))
                        tvStatusLog.text = "Selesai! Foto HD+ (${targetW}x${targetH}, ${sizeMb} MB) siap dikirim ke WA."
                        btnShareWa.isEnabled = true
                    } else {
                        tvStatusLog.text = "Gagal merender Foto HD+: ${session.failStackTrace}"
                    }
                    btnProcess.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatusLog.text = "Gagal memproses foto: ${e.message}"
                    btnProcess.isEnabled = true
                }
            }
        }
    }

    private fun applyCrispSharpen(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)
        val result = pixels.copyOf()

        for (y in 1 until height - 1) {
            val rowOffset = y * width
            for (x in 1 until width - 1) {
                val idx = rowOffset + x
                val c = pixels[idx]
                val top = pixels[idx - width]
                val bottom = pixels[idx + width]
                val left = pixels[idx - 1]
                val right = pixels[idx + 1]

                val r = (((c shr 16) and 0xFF) * 1.6f -
                        (((top shr 16) and 0xFF) + ((bottom shr 16) and 0xFF) +
                         ((left shr 16) and 0xFF) + ((right shr 16) and 0xFF)) * 0.15f).toInt().coerceIn(0, 255)
                val g = (((c shr 8) and 0xFF) * 1.6f -
                        (((top shr 8) and 0xFF) + ((bottom shr 8) and 0xFF) +
                         ((left shr 8) and 0xFF) + ((right shr 8) and 0xFF)) * 0.15f).toInt().coerceIn(0, 255)
                val b = ((c and 0xFF) * 1.6f -
                        ((top and 0xFF) + (bottom and 0xFF) +
                         (left and 0xFF) + (right and 0xFF)) * 0.15f).toInt().coerceIn(0, 255)

                result[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(result, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun shareToWhatsApp() {
        if (outputFiles.isEmpty()) return

        val uris = ArrayList<Uri>()
        for (file in outputFiles) {
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            uris.add(uri)
        }

        val mimeType = "video/mp4"
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }

        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.setPackage("com.whatsapp")

        try {
            startActivity(intent)
        } catch (e: Exception) {
            intent.setPackage(null)
            startActivity(Intent.createChooser(intent, "Kirim ke Status WhatsApp"))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}