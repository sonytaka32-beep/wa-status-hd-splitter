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
    private lateinit var customDurationLabel: TextView
    private lateinit var customDurationMinutes: EditText

    private var selectedUri: Uri? = null
    private var isVideoMode: Boolean = true
    private var videoDurationSec: Int = 0
    private var videoDurationSecondsExact: Double = 0.0

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
    private var customDurationHasError: Boolean = false
    private val splitIntervalSec: Int = 30

    private val outputFiles = ArrayList<File>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    data class VideoPlan(
        val outW: Int,
        val outH: Int,
        /** true = seluruh frame tajam di tengah, sisa kanvas diisi blur (tanpa crop, tanpa hitam). */
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
        customDurationLabel = findViewById(R.id.tvCustomDurationLabel)
        customDurationMinutes = findViewById(R.id.etCustomDurationMinutes)

        findViewById<Button>(R.id.btnPickVideo).setOnClickListener { pickVideoLauncher.launch("video/*") }
        findViewById<Button>(R.id.btnPickPhoto).setOnClickListener { pickPhotoLauncher.launch("image/*") }

        setupDurationSpinner()

        rgRotateMode.setOnCheckedChangeListener { _, _ ->
            if (isVideoMode && selectedUri != null) updateVideoSplitPreview()
        }

        btnProcess.setOnClickListener {
            val uri = selectedUri ?: return@setOnClickListener
            if (isVideoMode) {
                if (isCustomDurationSelected() && !updateCustomDurationFromInput()) {
                    customDurationMinutes.requestFocus()
                    return@setOnClickListener
                }
                processVideoBypass(uri)
            } else {
                processPhotoBypass(uri)
            }
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

    /** Isi spinner untuk batas total: 30 detik hingga 30 menit, semua video, dan custom. */
    private fun setupDurationSpinner() {
        val seconds = resources.getIntArray(R.array.duration_limit_seconds)
            .takeIf { it.isNotEmpty() } ?: intArrayOf(30)

        val labels = seconds.map { sec ->
            when (sec) {
                ALL_DURATION_SENTINEL -> "Semua durasi video"
                CUSTOM_DURATION_SENTINEL -> "Custom…"
                else -> formatDuration(sec)
            }
        }

        val adapter = ArrayAdapter(this, R.layout.status_spinner_item, labels)
        adapter.setDropDownViewResource(R.layout.status_spinner_dropdown_item)
        spinnerDuration.adapter = adapter

        // Default to all source duration so long videos are not silently truncated.
        // The output is still split into 30-second parts for WhatsApp Status.
        val defaultIndex = seconds.indexOf(ALL_DURATION_SENTINEL).let { if (it >= 0) it else 0 }
        outputDurationLimitSec = if (seconds[defaultIndex] == ALL_DURATION_SENTINEL) Int.MAX_VALUE else seconds[defaultIndex]
        spinnerDuration.setSelection(defaultIndex, false)

        spinnerDuration.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = seconds.getOrElse(position) { 30 }
                val isCustom = selected == CUSTOM_DURATION_SENTINEL
                val isAll = selected == ALL_DURATION_SENTINEL
                customDurationLabel.visibility = if (isCustom) View.VISIBLE else View.GONE
                customDurationMinutes.visibility = if (isCustom) View.VISIBLE else View.GONE

                if (isCustom) {
                    updateCustomDurationFromInput()
                } else {
                    customDurationHasError = false
                    customDurationMinutes.error = null
                    outputDurationLimitSec = if (isAll) Int.MAX_VALUE else selected
                    if (isVideoMode && selectedUri != null) updateVideoSplitPreview()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        customDurationMinutes.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                updateCustomDurationFromInput()
                customDurationMinutes.clearFocus()
                true
            } else {
                false
            }
        }

        customDurationMinutes.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && isCustomDurationSelected()) updateCustomDurationFromInput()
        }
    }

    private fun isCustomDurationSelected(): Boolean =
        spinnerDuration.selectedItemPosition >= 0 &&
                resources.getIntArray(R.array.duration_limit_seconds)
                    .getOrElse(spinnerDuration.selectedItemPosition) { 30 } == CUSTOM_DURATION_SENTINEL

    private fun isAllDurationSelected(): Boolean =
        spinnerDuration.selectedItemPosition >= 0 &&
                resources.getIntArray(R.array.duration_limit_seconds)
                    .getOrElse(spinnerDuration.selectedItemPosition) { 30 } == ALL_DURATION_SENTINEL

    private fun updateCustomDurationFromInput(): Boolean {
        val minutes = customDurationMinutes.text.toString().toIntOrNull()
        if (minutes == null || minutes !in 1..MAX_CUSTOM_DURATION_MINUTES) {
            customDurationMinutes.error = "Masukkan 1–$MAX_CUSTOM_DURATION_MINUTES menit"
            customDurationHasError = true
            if (isVideoMode && selectedUri != null) updateVideoSplitPreview()
            return false
        }

        customDurationMinutes.error = null
        customDurationHasError = false
        outputDurationLimitSec = minutes * SECONDS_PER_MINUTE
        if (isVideoMode && selectedUri != null) updateVideoSplitPreview()
        return true
    }

    private fun formatDuration(seconds: Int): String = when {
        seconds % SECONDS_PER_MINUTE == 0 -> "${seconds / SECONDS_PER_MINUTE} menit"
        seconds >= SECONDS_PER_MINUTE -> "${seconds / SECONDS_PER_MINUTE}m ${seconds % SECONDS_PER_MINUTE}d"
        else -> "$seconds detik"
    }

    private fun buildVideoPlan(): VideoPlan {
        val forcePortrait = rbRotate90.isChecked

        // Rotasi diserahkan ke FFmpeg (autorotate default): FFmpeg membaca display matrix
        // container dan memutar piksel sekali saja. Versi lama mematikan autorotate lalu
        // memutar manual, tapi tag rotasi input ikut tersalin ke output sehingga WhatsApp
        // memutarnya SEKALI LAGI -> video miring + letterbox hitam. Sekarang tag itu
        // dibuang dengan -map_metadata -1 dan tidak ada rotasi manual.

        val baseW: Int
        val baseH: Int
        if (metaRotation == 90 || metaRotation == 270) {
            baseW = if (rawW > 0) rawH else 720
            baseH = if (rawH > 0) rawW else 1280
        } else {
            baseW = if (rawW > 0) rawW else 720
            baseH = if (rawH > 0) rawH else 1280
        }

        // Force Portrait = kanvas status 1080x1920. Video TIDAK diputar dan TIDAK dipotong:
        // frame asli ditampilkan utuh, sisa ruang diisi blur dari frame itu sendiri.
        // WA Status cuts each part around 30 seconds. Spinner controls total output duration.
        val interval = splitIntervalSec

        val outW: Int
        val outH: Int
        if (forcePortrait) {
            // Kanvas status WhatsApp: portrait penuh 1080x1920 (HD).
            outW = 1080
            outH = 1920
        } else {
            // Pertahankan orientasi asli. Batas HD: sisi pendek <= 1080, sisi panjang <= 1920.
            // Tidak pernah upscale, jadi sumber 1612x720 tetap 1612x720 dan tetap tajam.
            val s = min(
                1f,
                min(1080f / min(baseW, baseH).toFloat(), 1920f / max(baseW, baseH).toFloat())
            )
            outW = max(2, ((baseW * s).toInt() / 2) * 2)
            outH = max(2, ((baseH * s).toInt() / 2) * 2)
        }

        val safeW = if (outW < 2) 720 else outW
        val safeH = if (outH < 2) 1280 else outH

        // Sumber yang sudah pas 9:16 tidak perlu padding blur sama sekali.
        val alreadyStatusRatio = baseW.toLong() * 16L == baseH.toLong() * 9L
        val desc = when {
            !forcePortrait -> "Original $safeW x $safeH (utuh, tanpa potong)"
            alreadyStatusRatio -> "Status 1080x1920 (pas, tanpa padding)"
            else -> "Status 1080x1920 + blur bar (frame utuh)"
        }

        return VideoPlan(
            outW = safeW,
            outH = safeH,
            fillCanvas = forcePortrait && !alreadyStatusRatio,
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
            videoDurationSecondsExact = max(0L, durationMs).toDouble() / 1000.0
            videoDurationSec = max(1, ceil(videoDurationSecondsExact).toInt())
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
                "> Config: ${plan.rotateDesc} | Limit ${if (isCustomDurationSelected() && customDurationHasError) "custom belum valid" else if (isAllDurationSelected()) "semua video" else formatDuration(outputDurationLimitSec)}, $totalParts part @ ${plan.splitIntervalSec}s" +
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
        val processDurationSec = min(
            videoDurationSecondsExact.takeIf { it > 0.0 } ?: videoDurationSec.toDouble(),
            outputDurationLimitSec.toDouble()
        ).coerceAtLeast(0.1)
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

                val videoFilterArgs: String
                if (plan.fillCanvas) {
                    // Frame asli tampil utuh (fit, tidak dipotong) di tengah kanvas 9:16.
                    // Sisa ruang diisi salinan frame itu sendiri yang diblur, jadi tidak ada
                    // area hitam dan tidak ada bagian video yang hilang.
                    // Blur dijalankan pada versi kecil (1/8) supaya murah di HP kelas menengah.
                    val source = "[0:v]"
                    val bgChain = "scale=${plan.outW}:${plan.outH}:force_original_aspect_ratio=increase:force_divisible_by=2," +
                            "crop=${plan.outW}:${plan.outH}," +
                            "scale=trunc(iw/8)*2:trunc(ih/8)*2:flags=area," +
                            "boxblur=6:2," +
                            "scale=${plan.outW}:${plan.outH}:flags=bicubic"
                    val filterGraph = source +
                            "split=2[bgsrc][fgsrc];" +
                            "[bgsrc]$bgChain[bg];" +
                            "[fgsrc]scale=${plan.outW}:${plan.outH}:flags=lanczos:force_original_aspect_ratio=decrease:force_divisible_by=2[fg];" +
                            "[bg][fg]overlay=(W-w)/2:(H-h)/2:shortest=1,setsar=1,format=yuv420p[v]"
                    videoFilterArgs = "-filter_complex \"$filterGraph\" -map \"[v]\" -map 0:a? "
                } else {
                    val filters = ArrayList<String>()
                    filters.add("scale=${plan.outW}:${plan.outH}:flags=lanczos")
                    filters.add("setsar=1")
                    filters.add("format=yuv420p")
                    videoFilterArgs = "-vf \"${filters.joinToString(",")}\" "
                }

                FFmpegKitConfig.enableStatisticsCallback { stats: Statistics ->
                    val timeSec = (stats.time / 1000.0).toInt()
                    val pct = if (processDurationSec > 0) {
                        ((timeSec * 100.0) / processDurationSec).toInt().coerceIn(2, 99)
                    } else {
                        50
                    }
                    runOnUiThread {
                        progressBar.progress = pct
                        tvStatusLog.text = "Memproses Video HD+ (${plan.outW}x${plan.outH}): $pct% (${timeSec}s / ${processDurationSec}s)"
                    }
                }

                val cmd = "-y -i \"$safeInputPath\" " +
                        videoFilterArgs +
                        "-map_metadata -1 " +
                        "-r 30 " +
                        "-g 60 -keyint_min 30 -sc_threshold 0 " +
                        "-force_key_frames \"expr:gte(t,n_forced*$interval)\" " +
                        "-c:v libx264 -preset fast -profile:v high -level 4.2 -threads 8 " +
                        "-b:v ${plan.bitrateKbps}k -maxrate ${plan.bitrateKbps}k -bufsize ${plan.bitrateKbps * 2}k " +
                        "-c:a aac -b:a 128k -ac 2 -ar 44100 " +
                        // Potong di keyframe sesuai durasi, bukan menunggu keyframe alami berikutnya.
                        "-t ${String.format(Locale.US, "%.3f", processDurationSec)} " +
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
        private const val ALL_DURATION_SENTINEL = -1
        private const val CUSTOM_DURATION_SENTINEL = 0
        private const val MAX_CUSTOM_DURATION_MINUTES = 99
        private const val SECONDS_PER_MINUTE = 60

        /** Target ukuran per part; bitrate 3500 kbps + audio 128 kbps stays under this. */
        private const val WA_PART_BUDGET_KB = 15000.0
        private const val AUDIO_KBPS = 128.0
    }
}
