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
    private lateinit var rgSplitInterval: RadioGroup
    private lateinit var rb30Sec: RadioButton

    private var selectedUri: Uri? = null
    private var isVideoMode: Boolean = true
    private var videoDurationSec: Int = 0
    private var savedWidth: String = "?"
    private var savedHeight: String = "?"
    private var savedOriginalSizeKb: Long = 0L

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

    private fun calculateOptimalVideoParams(durationSec: Int, interval: Int): Triple<Int, Int, String> {
        val effectiveClipSec = minOf(max(1, durationSec), interval)
        // Targetkan ~13.2 MB per klip agar lolos limit 15MB WA dengan kepadatan bitrate maksimal
        val bitrateKbps = ((13.2 * 8192.0) / effectiveClipSec - 128.0).toInt().coerceIn(2200, 6000)
        // Gunakan 1920p Full HD jika bitrate >= 2600 kbps, atau 1440p jika durasi klip penuh 60 detik
        val maxSidePx = if (bitrateKbps >= 2600) 1920 else 1440
        val label = "${maxSidePx}p @ ${String.format("%.2f", bitrateKbps / 1000.0)} Mbps"
        return Triple(bitrateKbps, maxSidePx, label)
    }

    private fun analyzeVideo(uri: Uri) {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            savedWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: "?"
            savedHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: "?"
            retriever.release()

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
        val (_, _, targetLabel) = calculateOptimalVideoParams(videoDurationSec, interval)
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

        tvMediaInfo.text = "MODE: VIDEO HD+ (LOLOS KOMPRESI WA)\n" +
                "Asli: ${savedWidth}x${savedHeight} (${savedOriginalSizeKb} KB) | Durasi: ${videoDurationSec} Detik\n" +
                "Target HD+: $targetLabel | Total: $totalParts Bagian\n\n" +
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
        tvStatusLog.text = "Menjalankan Hardware GPU Turbo..."

        scope.launch(Dispatchers.IO) {
            val outDir = prepareOutputDir()
            val safeInputPath = FFmpegKitConfig.getSafParameterForRead(this@MainActivity, uri)
            val interval = getSplitInterval()
            val outputPattern = File(outDir, "StatusHD_Part_%03d.mp4").absolutePath

            val (videoBitrateKbps, maxSidePx, _) = calculateOptimalVideoParams(videoDurationSec, interval)
            val gopFrames = 30 * minOf(videoDurationSec, interval)

            // Kunci sisi terpanjang tepat di 1920px (batas maksimal Full HD WhatsApp) tanpa CPU unsharp agar GPU ngebut
            val vfFilter = "scale='if(lt(iw,ih),-2,$maxSidePx)':'if(lt(iw,ih),$maxSidePx,-2)':flags=bicubic,format=yuv420p"

            FFmpegKitConfig.enableStatisticsCallback { stats: Statistics ->
                val timeSec = (stats.time / 1000.0).toInt()
                val pct = if (videoDurationSec > 0) ((timeSec * 100) / videoDurationSec).coerceIn(2, 99) else 50
                runOnUiThread {
                    progressBar.progress = pct
                    tvStatusLog.text = "Memproses Video HD+ (${maxSidePx}p): $pct% (${timeSec}s / ${videoDurationSec}s)"
                }
            }

            val hwCmd = "-y -i \"$safeInputPath\" " +
                    "-vf \"$vfFilter\" " +
                    "-r 30 -g $gopFrames " +
                    "-c:v h264_mediacodec -b:v ${videoBitrateKbps}k " +
                    "-c:a aac -b:a 128k -ac 2 " +
                    "-f segment -segment_time $interval -reset_timestamps 1 " +
                    "\"$outputPattern\""

            var session = FFmpegKit.execute(hwCmd)

            if (!ReturnCode.isSuccess(session.returnCode)) {
                runOnUiThread {
                    tvStatusLog.text = "Menjalankan encoder presisi tinggi..."
                }
                outDir.listFiles()?.forEach { it.delete() }

                val swCmd = "-y -i \"$safeInputPath\" " +
                        "-vf \"$vfFilter\" " +
                        "-r 30 -g $gopFrames " +
                        "-c:v libx264 -preset veryfast -profile:v high -level 4.1 -threads 8 " +
                        "-b:v ${videoBitrateKbps}k -maxrate ${videoBitrateKbps + 300}k -bufsize ${videoBitrateKbps * 2}k " +
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
                    val avgSizeMb = if (outputFiles.isNotEmpty()) {
                        String.format("%.1f", outputFiles.sumOf { it.length() } / (1024.0 * 1024.0 * outputFiles.size))
                    } else "0"

                    progressBar.progress = 100
                    tvStatusLog.text = "Selesai! ${outputFiles.size} klip HD+ (~${avgSizeMb} MB/klip) siap dikirim ke WA."
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

                // Perbaiki orientasi EXIF
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

                // Kunci sisi terpanjang tepat ke 1920px dengan dimensi genap (wajib untuk H.264 Full HD)
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

                // Konversi ke MP4 5 detik (6 Mbps, ~3.6 MB) agar WhatsApp tidak menjalankan kompresor foto 150KB
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

        // Video & Foto HD+ sama-sama dikirim melalui jalur video/mp4 Full HD agar anti-kompresi WA
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