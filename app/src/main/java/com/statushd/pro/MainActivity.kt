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
        val totalParts = ceil(videoDurationSec.toDouble() / 30.0).toInt()
        val rotInfo = if (rbRotateAuto.isChecked && origDisplayW > origDisplayH) "Auto-Rotate to Portrait" else "Keep Original"
        
        tvMediaInfo.text = "> SONYBAE DEFINITIVE ENGINE (VIDEO)\n" +
                "> Source: ${origDisplayW}x${origDisplayH} | ${videoDurationSec}s | ${savedOriginalSizeKb} KB\n" +
                "> Target: Strict 720p Bounding Box (Anti-Downscale)\n" +
                "> Config: $rotInfo | Parts: $totalParts (@30s)"
    }

    private fun analyzePhoto(uri: Uri) {
        tvMediaInfo.text = "> SONYBAE DEFINITIVE ENGINE (PHOTO)\n" +
                "> Target: Static MP4 720p HD Payload\n" +
                "> Action: Bypassing WA 150KB Image Compressor"
        progressBar.progress = 0
        tvStatusLog.text = "System: Photo Loaded. Ready for Build."
        btnProcess.isEnabled = true
        btnShareWa.isEnabled = false
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
        tvStatusLog.text = "Injecting Anti-Compression Algorithm..."

        scope.launch(Dispatchers.IO) {
            val outDir = prepareOutputDir()
            val safeInputPath = FFmpegKitConfig.getSafParameterForRead(this@MainActivity, uri)
            val outputPattern = File(outDir, "StatusHD_Part_%03d.mp4").absolutePath

            val isHorizontal = origDisplayW > origDisplayH
            val transpose = if (rbRotateAuto.isChecked && isHorizontal) "transpose=1," else ""
            
            // Aturan Emas: Sisi paling panjang tidak boleh melebihi 1280px agar lolos sensor WA.
            val vfFilter = "${transpose}scale='if(gt(iw,ih),min(1280,iw),-2)':'if(gt(iw,ih),-2,min(1280,ih))':flags=lanczos,unsharp=3:3:0.3:3:3:0.0,format=yuv420p"

            FFmpegKitConfig.enableStatisticsCallback { stats: Statistics ->
                val timeSec = (stats.time / 1000.0).toInt()
                val pct = if (videoDurationSec > 0) ((timeSec * 100) / videoDurationSec).coerceIn(2, 99) else 50
                runOnUiThread {
                    progressBar.progress = pct
                    tvStatusLog.text = "Bypassing Server: $pct% (${timeSec}s / ${videoDurationSec}s)"
                }
            }

            val cmd = "-y -i \"$safeInputPath\" " +
                    "-vf \"$vfFilter\" " +
                    "-r 30 -g 90 " +
                    "-c:v libx264 -preset fast -profile:v baseline -level 3.0 -threads 8 " +
                    "-crf 17 -maxrate 2600k -bufsize 5000k " +
                    "-c:a aac -b:a 128k -ar 44100 -ac 2 " +
                    "-f segment -segment_time 30 -reset_timestamps 1 " +
                    "-segment_format_options movflags=+faststart " +
                    "\"$outputPattern\""

            val session = FFmpegKit.execute(cmd)

            withContext(Dispatchers.Main) {
                if (ReturnCode.isSuccess(session.returnCode)) {
                    val generated = outDir.listFiles()
                        ?.filter { it.name.startsWith("StatusHD_Part_") && it.length() > 0 }
                        ?.sortedBy { it.name } ?: emptyList()

                    outputFiles.addAll(generated)
                    progressBar.progress = 100
                    tvStatusLog.text = "System: Payload Successfully Injected!"
                    btnShareWa.isEnabled = outputFiles.isNotEmpty()
                } else {
                    tvStatusLog.text = "Error: Injection Failed. " + session.failStackTrace
                }
                btnProcess.isEnabled = true
            }
        }
    }

    private fun processPhotoBypass(uri: Uri) {
        btnProcess.isEnabled = false
        btnShareWa.isEnabled = false
        progressBar.progress = 25
        tvStatusLog.text = "Building Static HD Container..."

        scope.launch(Dispatchers.IO) {
            try {
                val outDir = prepareOutputDir()
                val decodeOpts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                var bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) } ?: throw Exception("Image error")

                val rot = contentResolver.openInputStream(uri)?.use { input ->
                    when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                        6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
                    }
                } ?: 0f

                if (rot != 0f) {
                    val mat = Matrix().apply { postRotate(rot) }
                    val rotBmp = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, mat, true)
                    if (rotBmp != bitmap) bitmap.recycle(); bitmap = rotBmp
                }

                // Pra-Scale agar FFmpeg tidak kehabisan memori jika foto asli beresolusi 50MP
                val scale = 1920f / maxOf(bitmap.width, bitmap.height).toFloat()
                if (scale < 1.0f) {
                    val tW = (bitmap.width * scale).toInt()
                    val tH = (bitmap.height * scale).toInt()
                    val scaled = Bitmap.createScaledBitmap(bitmap, tW, tH, true)
                    if (scaled != bitmap) { bitmap.recycle(); bitmap = scaled }
                }

                val tempPng = File(cacheDir, "hd_frame.png")
                FileOutputStream(tempPng).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()

                runOnUiThread {
                    progressBar.progress = 60
                    tvStatusLog.text = "Encoding Static Frame to Anti-Blur MP4..."
                }

                val outFile = File(outDir, "StatusHD_Photo.mp4")
                
                // INJEKSI KETAJAMAN: Filter scale memastikan sisi terpanjang maksimal 1280 (Aman dari kompresi WA).
                // Filter unsharp=5:5:0.8 berfungsi menegaskan warna merah/putih di latar gelap agar tidak kabur (counter chroma subsampling).
                // Dibuat statis murni (-tune stillimage) tanpa zoompan agar piksel teks tidak bergetar/berbayang.
                val vfFilter = "scale='if(gt(iw,ih),min(1280,iw),-2)':'if(gt(iw,ih),-2,min(1280,ih))':flags=lanczos,unsharp=5:5:0.8:3:3:0.0,format=yuv420p"
                
                val cmd = "-y -loop 1 -framerate 30 -i \"${tempPng.absolutePath}\" " +
                        "-t 5 -vf \"$vfFilter\" " +
                        "-c:v libx264 -preset fast -tune stillimage " +
                        "-profile:v baseline -level 3.0 -threads 8 " +
                        "-crf 15 -maxrate 3000k -bufsize 6000k " +
                        "-movflags +faststart " +
                        "\"${outFile.absolutePath}\""

                val session = FFmpegKit.execute(cmd)
                tempPng.delete()

                withContext(Dispatchers.Main) {
                    if (ReturnCode.isSuccess(session.returnCode) && outFile.exists()) {
                        outputFiles.add(outFile)
                        progressBar.progress = 100
                        tvStatusLog.text = "System: Photo Payload Ready."
                        btnShareWa.isEnabled = true
                    } else {
                        tvStatusLog.text = "Error: Build failed. " + session.failStackTrace
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
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uris[0]) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "video/mp4"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)) }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.setPackage("com.whatsapp")
        try { startActivity(intent) } catch (e: Exception) {
            intent.setPackage(null)
            startActivity(Intent.createChooser(intent, "Send to Status WA"))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}