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
import android.view.View
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
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var tvMediaInfo: TextView
    private lateinit var tvStatusLog: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnProcess: Button
    private lateinit var btnShareWa: Button
    private lateinit var rgRotateMode: RadioGroup
    private lateinit var rbRotate90: RadioButton
    private lateinit var spinnerDuration: Spinner

    private var selectedUri: Uri? = null
    private var isVideoMode: Boolean = true
    private var videoDurationSec: Int = 0

    // Raw (pre-rotation) pixel size + container rotation metadata.
    private var rawW: Int = 720
    private var rawH: Int = 1280
    private var metaRotation: Int = 0

    // Display size as seen by the user (rotation metadata already applied).
    private var origDisplayW: Int = 720
    private var origDisplayH: Int = 1280
    private var savedOriginalSizeKb: Long = 0L

    // Batas total durasi hasil kompresi; tiap berkas tetap dibagi maksimal 30 detik
    // agar kompatibel dengan WhatsApp Status versi yang membatasi video 30 detik.
    private var outputDurationLimitSec: Int = 30
    private val splitIntervalSec: Int = 30

    private val outputFiles = ArrayList<File>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    data class VideoPlan(
        val outW: Int,
        val outH: Int,
        /** Rotasi manual dari metadata container; ffmpeg dijalankan dengan -noautorotate. */
        val rotationFilter: String?,
        /** true = sumber landscape diputar 90° agar jadi portrait (Force Portrait). */
        val rotateLandscape: Boolean,
        /** true = kanvas diisi penuh 1080x1920 (crop), false = jaga rasio asli. */
        val fillCanvas: Boolean,
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
        spinnerDuration = findViewById(R.id.spinnerDurationLimit)

        findViewById<Button>(R.id.btnPickVideo).setOnClickListener { pickVideoLauncher.launch("video/*") }
        findViewById<Button>(R.id.btnPickPhoto).setOnClickListener { pickPhotoLauncher.launch("image/*") }

        setupDurationSpinner()

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

    /** Bitrate target: satu bagian 30 detik dijaga sekitar 14 MB agar ada margin di batas WA. */
    private fun bitrateFor(intervalSec: Int): Int {
        val safeInterval = max(1, intervalSec)
        // Reserve ~7% for muxing/bitrate variation so the estimated 14 MiB target
        // stays safely below WhatsApp's practical per-status limit.
        val budgetKbps = WA_PART_BUDGET_KB * 8.0 * 0.93 / safeInterval - AUDIO_KBPS
        // Maksimum 3500 kbps (Bravo HD); dengan audio 128 kbps, part 30 detik
        // diperkirakan sekitar 13.3 MiB, dengan ruang aman di bawah limit Status.
        return budgetKbps.toInt().coerceIn(400, 3500)
    }

    /** Perkiraan ukuran 1 bagian output dalam MB (video + audio). */
    private fun estimatePartMb(intervalSec: Int): Double =
        (bitrateFor(intervalSec) + AUDIO_KBPS) * max(1, intervalSec) / 8.0 / 1024.0

    /** Isi spinner untuk batas durasi total video output: 30-210 detik. */
    private fun setupDurationSpinner() {
        val seconds = resources.getIntArray(R.array.duration_limit_seconds)
            .takeIf { it.isNotEmpty() } ?: intArrayOf(30)

        val labels = seconds.map { sec ->
            String.format(Locale.US, "%d detik", sec)
        }

        val adapter = ArrayAdapter(this, R.layout.status_spinner_item, labels)
        adapter.setDropDownViewResource(R.layout.status_spinner_dropdown_item)
        spinnerDuration.adapter = adapter

        val defaultIndex = seconds.indexOf(30).let { if (it >= 0) it else 0 }
        outputDurationLimitSec = seconds[defaultIndex]
        spinnerDuration.setSelection(defaultIndex, false)

        spinnerDuration.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                outputDurationLimitSec = seconds.getOrElse(position) { 30 }
                if (isVideoMode && selectedUri != null) updateVideoSplitPreview()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun buildVideoPlan(): VideoPlan {
        val forcePortrait = rbRotate90.isChecked

        // Rotasi dari metadata container diterapkan manual (input dijalankan dengan -noautorotate),
        // supaya ffmpeg tidak memutar dua kali. Double-rotation inilah yang membuat output
        // force-portrait jadi gepeng/buram.
        val rotationFilter = when (metaRotation) {
            90 -> "transpose=1"
            180 -> "transpose=2,transpose=2"
            270 -> "transpose=2"
            else -> null
        }

        val baseW: Int
        val baseH: Int
        if (metaRotation == 90 || metaRotation == 270) {
            baseW = if (rawW > 0) rawH else 720
            baseH = if (rawH > 0) rawW else 1280
        } else {
            baseW = if (rawW > 0) rawW else 720
            baseH = if (rawH > 0) rawH else 1280
        }

        // After applying the container rotation, rotate any still-landscape source so
        // Force Portrait works for both ordinary and rotated-metadata videos.
        val rotateLandscape = forcePortrait && baseW > baseH
        // WA Status cuts each part around 30 seconds. Spinner controls total output duration.
        val interval = splitIntervalSec

        val outW: Int
        val outH: Int
        if (forcePortrait) {
            // Kanvas status WhatsApp: portrait penuh 1080x1920 (HD).
            outW = 1080
            outH = 1920
        } else {
            val s = min(
                1f,
                min(1080f / min(baseW, baseH).toFloat(), 1920f / max(baseW, baseH).toFloat())
            )
            outW = ((baseW * s).toInt() / 2) * 2
            outH = ((baseH * s).toInt() / 2) * 2
        }

        val safeW = if (outW < 2) 720 else outW
        val safeH = if (outH < 2) 1280 else outH

        val desc = if (forcePortrait) "Force Portrait 1080x1920 (HD)" else "Original $safeW x $safeH"

        return VideoPlan(
            outW = safeW,
            outH = safeH,
            rotationFilter = rotationFilter,
            rotateLandscape = rotateLandscape,
            fillCanvas = forcePortrait,
            rotateDesc = desc,
            bitrateKbps = bitrateFor(interval),
            splitIntervalSec = interval
        )
    }

    private fun analyzeVideo(uri: Uri) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val metaW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 720
            val metaH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1280
            val metaRot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0

            rawW = if (metaW > 0) metaW else 720
            rawH = if (metaH > 0) metaH else 1280
            metaRotation = if (metaRot == 90 || metaRot == 180 || metaRot == 270) metaRot else 0

            if (metaRotation == 90 || metaRotation == 270) {
                origDisplayW = rawH
                origDisplayH = rawW
            } else {
                origDisplayW = rawW
                origDisplayH = rawH
            }

            savedOriginalSizeKb = getFileSizeKb(uri)
            videoDurationSec = max(1, ceil(durationMs / 1000.0).toInt())
            updateVideoSplitPreview()

            progressBar.progress = 0
            tvStatusLog.text = "System: Video Loaded. Ready for Injection."
            btnProcess.isEnabled = true
            btnShareWa.isEnabled = false
        } catch (e: Exception) {
            tvMediaInfo.text = "Error reading video: ${e.message}"
        } finally {
            retriever.release()
        }
    }

    private fun updateVideoSplitPreview() {
        val plan = buildVideoPlan()
        val selectedDuration = min(videoDurationSec, outputDurationLimitSec)
        val totalParts = max(1, ceil(selectedDuration.toDouble() / plan.splitIntervalSec.toDouble()).toInt())
        val partMb = estimatePartMb(plan.splitIntervalSec)
        val limitMb = WA_PART_BUDGET_KB / 1024.0

        val warnings = buildString {
            if (partMb > limitMb) {
                append(String.format(Locale.US, "\n> ! %.1f MB/part > %.0f MB: WA akan re-encode (buram).", partMb, limitMb))
            }
        }

        tvMediaInfo.text = "> SONYBAE DEFINITIVE ENGINE (VIDEO)\n" +
                "> Source: ${origDisplayW}x${origDisplayH} | ${videoDurationSec}s | ${savedOriginalSizeKb} KB\n" +
                "> Target: ${plan.outW}x${plan.outH} @ ${plan.bitrateKbps} kbps (BRAVO HD ENGINE)\n" +
                "> Config: ${plan.rotateDesc} | Limit ${outputDurationLimitSec}s, $totalParts part @ ${plan.splitIntervalSec}s" +
                warnings
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
        return dir
    }

    private fun processVideoBypass(uri: Uri) {
        // Snapshot UI selections on the main thread before starting background encoding.
        val plan = buildVideoPlan()
        val interval = plan.splitIntervalSec
        val processDurationSec = min(videoDurationSec, outputDurationLimitSec).coerceAtLeast(1)
        outputFiles.clear()

        btnProcess.isEnabled = false
        btnShareWa.isEnabled = false
        progressBar.progress = 2
        tvStatusLog.text = "Menyiapkan encoding video HD... WhatsApp masih dapat mengompres ulang."

        scope.launch(Dispatchers.IO) {
            try {
                val outDir = prepareOutputDir()
                val safeInputPath = FFmpegKitConfig.getSafParameterForRead(this@MainActivity, uri)
                val outputPattern = File(outDir, "StatusHD_Part_%03d.mp4").absolutePath

                val filters = ArrayList<String>()
                plan.rotationFilter?.let { filters.add(it) }
                if (plan.rotateLandscape) filters.add("transpose=1")
                if (plan.fillCanvas) {
                    // Isi penuh kanvas 1080x1920 tanpa distorsi: scale-up lalu crop center.
                    filters.add("scale=${plan.outW}:${plan.outH}:flags=lanczos:force_original_aspect_ratio=increase")
                    filters.add("crop=${plan.outW}:${plan.outH}")
                } else {
                    filters.add("scale=${plan.outW}:${plan.outH}:flags=lanczos")
                }
                filters.add("setsar=1")
                filters.add("format=yuv420p")
                val vfChain = filters.joinToString(",")

                FFmpegKitConfig.enableStatisticsCallback { stats: Statistics ->
                    val timeSec = (stats.time / 1000.0).toInt()
                    val pct = if (processDurationSec > 0) {
                        ((timeSec * 100) / processDurationSec).coerceIn(2, 99)
                    } else {
                        50
                    }
                    runOnUiThread {
                        progressBar.progress = pct
                        tvStatusLog.text = "Memproses Video HD+ (${plan.outW}x${plan.outH}): $pct% (${timeSec}s / ${processDurationSec}s)"
                    }
                }

                val cmd = "-y -noautorotate -i \"$safeInputPath\" " +
                        "-vf \"$vfChain\" " +
                        "-r 30 " +
                        "-g 60 -keyint_min 30 -sc_threshold 0 " +
                        "-force_key_frames \"expr:gte(t,n_forced*$interval)\" " +
                        "-c:v libx264 -preset medium -profile:v high -level 4.2 -threads 8 " +
                        "-b:v ${plan.bitrateKbps}k -maxrate ${plan.bitrateKbps}k -bufsize ${plan.bitrateKbps * 2}k " +
                        "-c:a aac -b:a 128k -ac 2 -ar 44100 " +
                        // Potong di keyframe sesuai durasi, bukan menunggu keyframe alami berikutnya.
                        "-t $processDurationSec " +
                        "-f segment -segment_format mp4 -segment_time $interval -segment_time_delta 0.5 -reset_timestamps 1 " +
                        "\"$outputPattern\""

                val session = FFmpegKit.execute(cmd)
                FFmpegKitConfig.enableStatisticsCallback { }

                withContext(Dispatchers.Main) {
                    if (ReturnCode.isSuccess(session.returnCode)) {
                        val generated = outDir.listFiles()
                            ?.filter { it.name.startsWith("StatusHD_Part_") && it.length() > 0 }
                            ?.sortedBy { it.name } ?: emptyList()

                        outputFiles.addAll(generated)
                        val totalMb = generated.sumOf { it.length() } / 1024.0 / 1024.0
                        progressBar.progress = 100
                        tvStatusLog.text = String.format(
                            Locale.US,
                            "System: Selesai — %d part, %.1f MB, %dx%d @ %d kbps",
                            generated.size, totalMb, plan.outW, plan.outH, plan.bitrateKbps
                        )
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
            } finally {
                // Hentikan callback statistik agar run berikutnya tidak menimpa UI dari thread lama.
                FFmpegKitConfig.enableStatisticsCallback { }
            }
        }
    }

    private fun processPhotoBypass(uri: Uri) {
        outputFiles.clear()
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

    companion object {
        /** Target ukuran per part; bitrate 3500 kbps + audio 128 kbps stays under this. */
        private const val WA_PART_BUDGET_KB = 15000.0
        private const val AUDIO_KBPS = 128.0
    }
}
