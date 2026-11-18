package com.statushd.pro

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
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
    private lateinit var rgSplitInterval: RadioGroup
    private lateinit var rb30Sec: RadioButton

    private var selectedUri: Uri? = null
    private var isVideoMode: Boolean = true
    private var videoDurationSec: Int = 0
    private val outputFiles = ArrayList<File>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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
        rgSplitInterval = findViewById(R.id.rgSplitInterval)
        rb30Sec = findViewById(R.id.rb30Sec)

        findViewById<Button>(R.id.btnPickVideo).setOnClickListener {
            pickVideoLauncher.launch("video/*")
        }

        findViewById<Button>(R.id.btnPickPhoto).setOnClickListener {
            pickPhotoLauncher.launch("image/*")
        }

        rgSplitInterval.setOnCheckedChangeListener { _, _ ->
            if (isVideoMode && selectedUri != null) {
                updateVideoSplitPreview()
            }
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
        return if (rb30Sec.isChecked) 30 else 60
    }

    private fun analyzeVideo(uri: Uri) {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: "?"
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: "?"
            retriever.release()

            videoDurationSec = max(1, (durationMs / 1000).toInt())
            updateVideoSplitPreview(width, height)
            progressBar.progress = 0
            tvStatusLog.text = "Status: Video siap! Tekan tombol PROSES HD+ / AUTO SPLIT."
            btnProcess.isEnabled = true
            btnShareWa.isEnabled = false
        } catch (e: Exception) {
            tvMediaInfo.text = "Gagal membaca video: ${e.message}"
        }
    }

    private fun updateVideoSplitPreview(width: String = "Auto", height: String = "Auto") {
        val interval = getSplitInterval()
        val totalParts = ceil(videoDurationSec.toDouble() / interval.toDouble()).toInt()
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

        tvMediaInfo.text = "MODE: VIDEO HD+ & AUTO-SPLIT (GPU TURBO)\n" +
                "Resolusi Asli: ${width}x${height} | Total Durasi: ${videoDurationSec} Detik\n" +
                "Otomatis dibagi menjadi: $totalParts Bagian (@${interval} dtk)\n\n" +
                "Rincian Timeline:\n$timelinePreview"
    }

    private fun analyzePhoto(uri: Uri) {
        tvMediaInfo.text = "MODE: FOTO HD+ (Anti-Blur 2048px Sweet-Spot)\n" +
                "Foto siap dioptimasi ke ketajaman maksimal Status WhatsApp."
        progressBar.progress = 0
        tvStatusLog.text = "Status: Foto siap! Tekan tombol PROSES HD+ / AUTO SPLIT."
        btnProcess.isEnabled = true
        btnShareWa.isEnabled = false
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
        tvStatusLog.text = "Memulai Hardware GPU Encoding..."

        scope.launch(Dispatchers.IO) {
            val outDir = prepareOutputDir()
            // Zero-copy: baca langsung dari SAF URI tanpa menyalin file raksasa ke cache
            val safeInputPath = FFmpegKitConfig.getSafParameterForRead(this@MainActivity, uri)
            val interval = getSplitInterval()
            val outputPattern = File(outDir, "StatusHD_Part_%03d.mp4").absolutePath

            // Pertahankan resolusi asli jika sisi terpendek <= 1080p; turunkan ke 1080p hanya jika 2K/4K
            val scaleFilter = "scale='if(gt(min(iw,ih),1080),if(lt(iw,ih),1080,-2),iw)':'if(gt(min(iw,ih),1080),if(lt(iw,ih),-2,1080),ih)':flags=bicubic,format=yuv420p"

            FFmpegKitConfig.enableStatisticsCallback { stats: Statistics ->
                val timeSec = (stats.time / 1000.0).toInt()
                val pct = if (videoDurationSec > 0) ((timeSec * 100) / videoDurationSec).coerceIn(2, 99) else 50
                runOnUiThread {
                    progressBar.progress = pct
                    tvStatusLog.text = "Memproses Turbo HD+ & Split: $pct% (${timeSec}s / ${videoDurationSec}s)"
                }
            }

            // 1. Prioritas Utama: Hardware MediaCodec GPU (sangat cepat)
            val hwCmd = "-y -i \"$safeInputPath\" " +
                    "-vf \"$scaleFilter\" " +
                    "-c:v h264_mediacodec -b:v 4000k -maxrate 4500k -bufsize 8000k " +
                    "-force_key_frames \"expr:gte(t,n_forced*$interval)\" " +
                    "-c:a aac -b:a 128k -ac 2 " +
                    "-f segment -segment_time $interval -reset_timestamps 1 " +
                    "\"$outputPattern\""

            var session = FFmpegKit.execute(hwCmd)

            // 2. Fallback otomatis ke CPU ultrafast multi-thread jika device menolak MediaCodec
            if (!ReturnCode.isSuccess(session.returnCode)) {
                runOnUiThread {
                    tvStatusLog.text = "Beralih ke mode kompatibilitas cepat..."
                }
                outDir.listFiles()?.forEach { it.delete() }

                val swCmd = "-y -i \"$safeInputPath\" " +
                        "-vf \"$scaleFilter\" " +
                        "-c:v libx264 -preset ultrafast -threads 8 -crf 22 -maxrate 4000k -bufsize 8000k " +
                        "-force_key_frames \"expr:gte(t,n_forced*$interval)\" " +
                        "-c:a aac -b:a 128k -ac 2 " +
                        "-f segment -segment_time $interval -reset_timestamps 1 " +
                        "\"$outputPattern\""

                session = FFmpegKit.execute(swCmd)
            }

            withContext(Dispatchers.Main) {
                if (ReturnCode.isSuccess(session.returnCode)) {
                    val generated = outDir.listFiles()
                        ?.filter { it.name.startsWith("StatusHD_Part_") && it.length() > 0 }
                        ?.sortedBy { it.name }
                        ?: emptyList()

                    outputFiles.addAll(generated)
                    progressBar.progress = 100
                    tvStatusLog.text = "Selesai! ${outputFiles.size} bagian Video HD+ siap dikirim ke Status WA."
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
        progressBar.progress = 40
        tvStatusLog.text = "Mengoptimasi Foto HD+..."

        scope.launch(Dispatchers.IO) {
            try {
                val outDir = prepareOutputDir()
                val maxSide = 2048

                // 1. Cek dimensi asli tanpa memuat seluruh piksel ke RAM
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, boundsOpts)
                }

                // 2. Hitung subsampling cepat agar dekode instan
                var sampleSize = 1
                while ((boundsOpts.outWidth / sampleSize) > maxSide * 2 ||
                    (boundsOpts.outHeight / sampleSize) > maxSide * 2
                ) {
                    sampleSize *= 2
                }

                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                var bitmap = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, decodeOpts)
                } ?: throw Exception("Foto tidak dapat dibaca")

                // 3. Perbaiki orientasi EXIF kamera agar foto potret tidak miring
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

                // 4. Scale presisi ke 2048px sweet-spot WhatsApp
                val ratio = minOf(maxSide.toFloat() / bitmap.width, maxSide.toFloat() / bitmap.height, 1.0f)
                val targetW = (bitmap.width * ratio).toInt()
                val targetH = (bitmap.height * ratio).toInt()

                val finalBitmap = if (ratio < 1.0f) {
                    val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                    if (scaled != bitmap) bitmap.recycle()
                    scaled
                } else {
                    bitmap
                }

                val outFile = File(outDir, "StatusHD_Photo.jpg")
                FileOutputStream(outFile).use { fos ->
                    finalBitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos)
                }
                finalBitmap.recycle()

                outputFiles.add(outFile)

                withContext(Dispatchers.Main) {
                    progressBar.progress = 100
                    val sizeKb = outFile.length() / 1024
                    tvStatusLog.text = "Selesai! Foto HD+ (${targetW}x${targetH}, ${sizeKb} KB) siap dikirim."
                    btnShareWa.isEnabled = true
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

    private fun shareToWhatsApp() {
        if (outputFiles.isEmpty()) return

        val uris = ArrayList<Uri>()
        for (file in outputFiles) {
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            uris.add(uri)
        }

        val mimeType = if (isVideoMode) "video/mp4" else "image/jpeg"
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