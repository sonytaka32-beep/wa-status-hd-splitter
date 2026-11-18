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
    private lateinit var rgSplitInterval: RadioGroup
    private lateinit var rb30Sec: RadioButton

    private var selectedUri: Uri? = null
    private var isVideoMode: Boolean = true
    private var videoDurationSec: Int = 0
    private var origDisplayW: Int = 720
    private var origDisplayH: Int = 1280
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

        rgRotateMode = findViewById(R.id.rgRotateMode)
        rbRotateAuto = findViewById(R.id.rbRotateAuto)
        rgSplitInterval = findViewById(R.id.rgSplitInterval)
        rb30Sec = findViewById(R.id.rb30Sec)

        findViewById<Button>(R.id.btnPickVideo).setOnClickListener { pickVideoLauncher.launch("video/*") }
        findViewById<Button>(R.id.btnPickPhoto).setOnClickListener { pickPhotoLauncher.launch("image/*") }

        rgRotateMode.setOnCheckedChangeListener { _, _ -> if (isVideoMode && selectedUri != null) updateVideoSplitPreview() }
        rgSplitInterval.setOnCheckedChangeListener { _, _ -> if (isVideoMode && selectedUri != null) updateVideoSplitPreview() }

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
            tvStatusLog.text = "Status: Engine Bypass Siap."
            btnProcess.isEnabled = true
            btnShareWa.isEnabled = false
        } catch (e: Exception) {
            tvMediaInfo.text = "Gagal membaca video: ${e.message}"
        }
    }

    private fun updateVideoSplitPreview() {
        val interval = if (rb30Sec.isChecked) 30 else 15
        val totalParts = ceil(videoDurationSec.toDouble() / interval.toDouble()).toInt()
        val isHorizontal = origDisplayW > origDisplayH
        
        val rotInfo = if (rbRotateAuto.isChecked && isHorizontal) "Auto Vertikal (Full Layar)" else "Asli"
        
        tvMediaInfo.text = "SONYBAE BYPASS ENGINE (VIDEO)\n" +
                "Input: ${origDisplayW}x${origDisplayH} | ${videoDurationSec}s | ${savedOriginalSizeKb} KB\n" +
                "Target WA: 720p HD @ 2.5 Mbps (Anti-Kompresi)\n" +
                "Rotasi: $rotInfo | Output: $totalParts Bagian (@${interval}s)"
    }

    private fun analyzePhoto(uri: Uri) {
        tvMediaInfo.text = "SONYBAE BYPASS ENGINE (FOTO)\n" +
                "Mode: MP4 Kontainer 5 Detik\n" +
                "Target WA: Full HD (Mencegah foto pecah jadi 150KB)"
        progressBar.progress = 0
        tvStatusLog.text = "Status: Engine Bypass Siap."
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
        tvStatusLog.text = "Menjalankan Protokol Bypass WhatsApp..."

        scope.launch(Dispatchers.IO) {
            val outDir = prepareOutputDir()
            val safeInputPath = FFmpegKitConfig.getSafParameterForRead(this@MainActivity, uri)
            val interval = if (rb30Sec.isChecked) 30 else 15
            val outputPattern = File(outDir, "StatusHD_Part_%03d.mp4").absolutePath

            // STRATEGI BYPASS WA: 
            // 1. Kunci max resolusi di 720p (Batas aman Pass-Through WA)
            // 2. Kunci bitrate max di 2.5 Mbps.
            // Jika kita pakai 1080p atau bitrate 10Mbps, WhatsApp PASTI akan mengkompres ulang file tersebut.
            
            val isHorizontal = origDisplayW > origDisplayH
            val transpose = if (rbRotateAuto.isChecked && isHorizontal) "transpose=1," else ""
            
            // Filter: Rotasi -> Scale ke batas 720p -> Lanczos -> Sedikit Sharpness Natural
            val vfFilter = "${transpose}scale='if(gt(iw,ih),min(1280,iw),-2)':'if(gt(iw,ih),-2,min(1280,ih))':flags=lanczos,unsharp=3:3:0.3:3:3:0.0,format=yuv420p"

            FFmpegKitConfig.enableStatisticsCallback { stats: Statistics ->
                val timeSec = (stats.time / 1000.0).toInt()
                val pct = if (videoDurationSec > 0) ((timeSec * 100) / videoDurationSec).coerceIn(2, 99) else 50
                runOnUiThread {
                    progressBar.progress = pct
                    tvStatusLog.text = "Encoding Bypass 720p: $pct% (${timeSec}s / ${videoDurationSec}s)"
                }
            }

            // Command Bypass: Profile Main, Preset Fast, Bitrate Terkunci 2.5M
            val cmd = "-y -i \"$safeInputPath\" " +
                    "-vf \"$vfFilter\" " +
                    "-r 30 -g ${30 * interval} " +
                    "-c:v libx264 -preset fast -profile:v main -level 3.1 -threads 8 " +
                    "-b:v 2500k -maxrate 2800k -bufsize 5000k " +
                    "-c:a aac -b:a 128k -ar 44100 -ac 2 " +
                    "-f segment -segment_time $interval -reset_timestamps 1 " +
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
                    tvStatusLog.text = "Selesai! File dioptimalkan untuk Bypass Sensor WhatsApp."
                    btnShareWa.isEnabled = outputFiles.isNotEmpty()
                } else {
                    tvStatusLog.text = "Gagal memproses video. Coba lagi."
                }
                btnProcess.isEnabled = true
            }
        }
    }

    private fun processPhotoBypass(uri: Uri) {
        btnProcess.isEnabled = false
        btnShareWa.isEnabled = false
        progressBar.progress = 25
        tvStatusLog.text = "Menjalankan Kontainer Foto Anti-Kompresi..."

        scope.launch(Dispatchers.IO) {
            try {
                val outDir = prepareOutputDir()
                val decodeOpts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                var bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) } ?: throw Exception("Foto error")

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

                // Foto aman dibuat 1080p karena akan dibungkus sebagai video durasi pendek
                val scale = 1920f / maxOf(bitmap.width, bitmap.height).toFloat()
                var tW = (bitmap.width * scale).toInt()
                var tH = (bitmap.height * scale).toInt()
                if (tW % 2 != 0) tW++
                if (tH % 2 != 0) tH++

                val scaled = Bitmap.createScaledBitmap(bitmap, tW, tH, true)
                if (scaled != bitmap) bitmap.recycle()

                val tempPng = File(cacheDir, "hd_frame.png")
                FileOutputStream(tempPng).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
                scaled.recycle()

                runOnUiThread {
                    progressBar.progress = 60
                    tvStatusLog.text = "Membungkus Foto menjadi Video 5 Detik..."
                }

                val outFile = File(outDir, "StatusHD_Photo.mp4")
                val cmd = "-y -loop 1 -framerate 30 -i \"${tempPng.absolutePath}\" " +
                        "-t 5 -c:v libx264 -preset veryfast -tune stillimage " +
                        "-profile:v high -level 4.1 -pix_fmt yuv420p " +
                        "-b:v 4000k -maxrate 5000k -bufsize 10000k " +
                        "\"${outFile.absolutePath}\""

                val session = FFmpegKit.execute(cmd)
                tempPng.delete()

                withContext(Dispatchers.Main) {
                    if (ReturnCode.isSuccess(session.returnCode) && outFile.exists()) {
                        outputFiles.add(outFile)
                        progressBar.progress = 100
                        tvStatusLog.text = "Selesai! Foto Anti-Buram siap dikirim."
                        btnShareWa.isEnabled = true
                    } else {
                        tvStatusLog.text = "Gagal memproses foto."
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
            startActivity(Intent.createChooser(intent, "Kirim ke Status WA"))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}