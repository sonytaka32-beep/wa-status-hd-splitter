package com.statushd.pro

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
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

        tvMediaInfo.text = "MODE: VIDEO HD+ & AUTO-SPLIT\n" +
                "Resolusi Asli: ${width}x${height} | Total Durasi: ${videoDurationSec} Detik\n" +
                "Otomatis dibagi menjadi: $totalParts Bagian (@${interval} dtk)\n\n" +
                "Rincian Timeline:\n$timelinePreview"
    }

    private fun analyzePhoto(uri: Uri) {
        tvMediaInfo.text = "MODE: FOTO HD+ (Anti-Blur 2048px Sweet-Spot)\n" +
                "Foto siap dioptimasi ke ketajaman maksimal Status WhatsApp."
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
        progressBar.progress = 0
        tvStatusLog.text = "Menyiapkan file input..."

        scope.launch(Dispatchers.IO) {
            val outDir = prepareOutputDir()
            val inputFile = File(cacheDir, "input_temp.mp4")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(inputFile).use { output -> input.copyTo(output) }
            }

            val interval = getSplitInterval()
            val outputPattern = File(outDir, "StatusHD_Part_%03d.mp4").absolutePath

            val cmd = "-y -i \"${inputFile.absolutePath}\" " +
                    "-vf \"scale='min(1080,iw)':-2:flags=lanczos,unsharp=3:3:0.6:3:3:0.0,format=yuv420p\" " +
                    "-c:v libx264 -preset veryfast -profile:v high -level 4.1 " +
                    "-crf 21 -maxrate 4000k -bufsize 8000k " +
                    "-force_key_frames \"expr:gte(t,n_forced*$interval)\" " +
                    "-c:a aac -b:a 128k -ac 2 " +
                    "-f segment -segment_time $interval -reset_timestamps 1 " +
                    "\"$outputPattern\""

            FFmpegKitConfig.enableStatisticsCallback { stats ->
                val timeSec = (stats.time / 1000.0).toInt()
                val pct = if (videoDurationSec > 0) ((timeSec * 100) / videoDurationSec).coerceIn(0, 99) else 50
                runOnUiThread {
                    progressBar.progress = pct
                    tvStatusLog.text = "Memproses Video HD+ & Split: $pct% (${timeSec}s / ${videoDurationSec}s)"
                }
            }

            val session = FFmpegKit.execute(cmd)
            inputFile.delete()

            withContext(Dispatchers.Main) {
                if (ReturnCode.isSuccess(session.returnCode)) {
                    val generated = outDir.listFiles()
                        ?.filter { it.name.startsWith("StatusHD_Part_") && it.length() > 0 }
                        ?.sortedBy { it.name }
                        ?: emptyList()

                    outputFiles.addAll(generated)
                    progressBar.progress = 100
                    tvStatusLog.text = "Selesai! Berhasil membuat ${outputFiles.size} bagian Video HD+ siap upload."
                    btnShareWa.isEnabled = outputFiles.isNotEmpty()
                } else {
                    tvStatusLog.text = "Gagal memproses video. Cek log: ${session.failStackTrace}"
                }
                btnProcess.isEnabled = true
            }
        }
    }

    private fun processPhotoHd(uri: Uri) {
        btnProcess.isEnabled = false
        btnShareWa.isEnabled = false
        progressBar.progress = 30
        tvStatusLog.text = "Merender Foto HD+ Anti-Blur..."

        scope.launch(Dispatchers.IO) {
            try {
                val outDir = prepareOutputDir()
                val original = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                } ?: throw Exception("Bitmap tidak terbaca")

                val maxSide = 2048
                val ratio = minOf(maxSide.toFloat() / original.width, maxSide.toFloat() / original.height, 1.0f)
                val targetW = (original.width * ratio).toInt()
                val targetH = (original.height * ratio).toInt()

                val scaled = if (ratio < 1.0f) {
                    Bitmap.createScaledBitmap(original, targetW, targetH, true)
                } else {
                    original
                }

                val outFile = File(outDir, "StatusHD_Photo.jpg")
                FileOutputStream(outFile).use { fos ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 92, fos)
                }

                outputFiles.add(outFile)

                withContext(Dispatchers.Main) {
                    progressBar.progress = 100
                    val sizeKb = outFile.length() / 1024
                    tvStatusLog.text = "Selesai! Foto HD+ (${targetW}x${targetH}, ${sizeKb} KB) siap dikirim ke Status WA."
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