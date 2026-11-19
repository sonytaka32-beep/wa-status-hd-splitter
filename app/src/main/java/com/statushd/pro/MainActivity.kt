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

    private var selectedUri: Uri? = null
    private var isVideoMode: Boolean = true
    private var videoDurationSec: Int = 0
    private var origDisplayW: Int = 720
    private var origDisplayH: Int = 1280
    private var savedOriginalSizeKb: Long = 0L

    private val outputFiles = ArrayList<File>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Struktur Data Kalkulasi Video
    data class VideoPlan(
        val outW: Int,
        val outH: Int,
        val transposeFilter: String,
        val rotateDesc: String,
        val bitrateKbps: Int
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
        rbRotateAuto = findViewById(R.id.rbRotateAuto)

        findViewById<Button>(R.id.btnPickVideo).setOnClickListener { pickVideoLauncher.launch("video/*") }
        findViewById<Button>(R.id.btnPickPhoto).setOnClickListener { pickPhotoLauncher.launch("image/*") }

        rgRotateMode.setOnCheckedChangeListener { _, _ -> if (isVideoMode && selectedUri != null) updateVideoSplitPreview() }

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
        } catch (e: Exception) { 0L }
    }

    // Engine Kalkulator Bravo HD yang Telah Direstorasi
    private fun buildVideoPlan(): VideoPlan {
        val isHorizontal = origDisplayW > origDisplayH

        var transposeStr = ""
        var rotDesc = "Original"
        var rotatedW = origDisplayW
        var rotatedH = origDisplayH

        // Logika Rotasi Diperbaiki: Jika Auto Portrait aktif dan video horizontal (gameplay)
        if (rbRotateAuto.isChecked && isHorizontal) {
            transposeStr = "transpose=1," // Putar 90 derajat searah jarum jam
            rotDesc = "Auto Portrait (Rotated 90°)"
            rotatedW = origDisplayH
            rotatedH = origDisplayW
        }

        val maxSide = maxOf(rotatedW, rotatedH)
        val minSide = minOf(rotatedW, rotatedH)

        // Native 1:1 Pixel Mapping (Sama persis dengan Versi 5 yang sangat tajam)
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
        
        // RESTORASI BATAS BAWAH BITRATE (3500k) AGAR CRF-15 TIDAK MATI KUTU!
        val bitrateKbps = ((9.5 * 8192.0) / effectiveClipSec - 128.0).toInt().coerceIn(3500, 7500)

        return VideoPlan(targetW, targetH, transposeStr, rotDesc, bitrateKbps)
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
                origDisplayW = rawH; origDisplayH = rawW
            } else {
                origDisplayW = rawW; origDisplayH = rawH
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
        val interval = getSplitInterval()
        val totalParts = ceil(videoDurationSec.toDouble() / interval.toDouble()).toInt()
        val plan = buildVideoPlan()
        
        tvMediaInfo.text = "> SONYBAE DEFINITIVE ENGINE (VIDEO)\n" +
                "> Source: ${origDisplayW}x${origDisplayH} | ${videoDurationSec}s | ${savedOriginalSizeKb} KB\n" +
                "> Target: ${plan.outW}x${plan.outH} (BRAVO HD ENGINE V1)\n" +
                "> Rotasi: ${plan.rotateDesc} | Parts: $totalParts (@${interval}s)"
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
            filters.add("format=yuv420p")
            val vfChain = filters.joinToString(",")

            FFmpegKitConfig.enableStatisticsCallback { stats: Statistics ->
                val timeSec = (stats.time / 1000.0).toInt()
                val pct = if (videoDurationSec > 0) ((timeSec * 100) / videoDurationSec).coerceIn(2, 99) else 50
                runOnUiThread {
                    progressBar.progress = pct
                    tvStatusLog.text = "Memproses Video HD+ (${plan.outW}x${plan.outH}): $pct% (${timeSec}s / ${videoDurationSec}s)"
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
                        ?.sortedBy { it.name } ?: emptyList()

                    outputFiles.addAll(generated)
                    progressBar.progress = 100
                    tvStatusLog.text = "Selesai! ${outputFiles.size} klip (${plan.outW}x${plan.outH}, ~${avgSizeMb(outputFiles)} MB/klip) siap dikirim ke WA!"
                    btnShareWa.isEnabled = outputFiles.isNotEmpty()
                } else {
                    tvStatusLog.text = "Gagal memproses video: ${session.failStackTrace}"
                }
                btnProcess.isEnabled = true
            }
        }
    }

    private fun processPhotoBypass(uri: Uri) {
        btnProcess.isEnabled = false
        btnShareWa.isEnabled = false
        progressBar.progress = 25
        tvStatusLog.text = "Merender Foto ke Jalur Full HD 1920p Anti-Blur..."

        scope.launch(Dispatchers.IO) {
            try {
                val outDir = prepareOutputDir()
                
                val decodeOpts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                var bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) } ?: throw Exception("Foto tidak terbaca")

                val rotationDegrees = contentResolver.openInputStream(uri)?.use { input ->
                    val exif = ExifInterface(input)
                    when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f

                if (rot != 0f) {
                    val mat = Matrix().apply { postRotate(rot) }
                    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, mat, true)
                    if (rotated != bitmap) bitmap.recycle()
                    bitmap = rotated
                }

                val maxSide = maxOf(bitmap.width, bitmap.height)
                val scale = 1920f / maxSide.toFloat()
                var targetW = (bitmap.width * scale).toInt()
                var targetH = (bitmap.height * scale).toInt()
                if (targetW % 2 != 0) targetW++
                if (targetH % 2 != 0) targetH++

                val tempPng = File(cacheDir, "hd_frame.png")
                FileOutputStream(tempPng).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()

                runOnUiThread {
                    progressBar.progress = 60
                    tvStatusLog.text = "Membungkus ke Kontainer Full HD Anti-Kompresi WA..."
                }

                val outFile = File(outDir, "StatusHD_Photo.mp4")
                val cmd = "-y -loop 1 -framerate 30 -i \"${tempPng.absolutePath}\" " +
                        "-t 5 -c:v libx264 -preset medium -tune stillimage " +
                        "-profile:v high -level 4.2 -pix_fmt yuv420p " +
                        "-crf 15 -maxrate 6000k -bufsize 12000k " +
                        "\"${outFile.absolutePath}\""

                val session = FFmpegKit.execute(cmd)
                tempPng.delete()

                withContext(Dispatchers.Main) {
                    if (ReturnCode.isSuccess(session.returnCode) && outFile.exists() && outFile.length() > 0) {
                        outputFiles.add(outFile)
                        progressBar.progress = 100
                        tvStatusLog.text = "Selesai! Foto HD+ (${pW}x${pH}) siap dikirim ke WA."
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