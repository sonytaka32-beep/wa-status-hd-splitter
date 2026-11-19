package com.statushd.pro

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.View
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
    private lateinit var rbKeepOriginal: RadioButton
    private lateinit var spinnerDurationLimit: Spinner

    private var selectedUri: Uri? = null
    private var isVideoMode: Boolean = true
    private var videoDurationSec: Int = 0
    private var origDisplayW: Int = 720
    private var origDisplayH: Int = 1280
    private var savedOriginalSizeKb: Long = 0L

    private val limitOptions = arrayOf("Full Video (No Limit)", "30 Seconds (1 Part)", "1 Minute (2 Parts)", "1 Minute 30s (3 Parts)", "2 Minutes (4 Parts)", "2 Minutes 30s (5 Parts)", "3 Minutes (6 Parts)", "5 Minutes (10 Parts)")
    private val limitValuesSec = intArrayOf(0, 30, 60, 90, 120, 150, 180, 300)

    private val outputFiles = ArrayList<File>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    data class VideoPlan(
        val outW: Int,
        val outH: Int,
        val transposeFilter: String,
        val rotateDesc: String,
        val bitrateKbps: Int,
        val processDurationLimitSec: Int
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
        rbKeepOriginal = findViewById(R.id.rbKeepOriginal)
        spinnerDurationLimit = findViewById(R.id.spinnerDurationLimit)

        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, limitOptions)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerDurationLimit.adapter = spinnerAdapter
        
        spinnerDurationLimit.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                (parent.getChildAt(0) as? TextView)?.setTextColor(android.graphics.Color.WHITE)
                if (isVideoMode && selectedUri != null) updateVideoSplitPreview()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

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

    private fun buildVideoPlan(): VideoPlan {
        var transposeStr = ""
        var rotDesc = "Original"
        var rotatedW = origDisplayW
        var rotatedH = origDisplayH

        if (rbRotate90.isChecked) {
            transposeStr = "transpose=1,"
            rotDesc = "Force Portrait (Rotated 90°)"
            val tempW = rotatedW
            rotatedW = rotatedH
            rotatedH = tempW
        }

        // PERMINTAAN USER: BIAKAN NATIVE!
        // Maksimal 1920 (batas aman layar HP), selebihnya biarkan resolusi bawaan asli.
        val maxSide = maxOf(rotatedW, rotatedH)
        val scale = if (maxSide > 1920) 1920f / maxSide.toFloat() else 1.0f

        var targetW = ((rotatedW * scale).toInt() / 2) * 2
        var targetH = ((rotatedH * scale).toInt() / 2) * 2
        if (targetW < 2) targetW = 720
        if (targetH < 2) targetH = 1280

        val limitSelectionSec = limitValuesSec[spinnerDurationLimit.selectedItemPosition]
        val processDurationLimit = if (limitSelectionSec > 0) minOf(videoDurationSec, limitSelectionSec) else videoDurationSec

        // PERMINTAAN USER: NAIKKAN BITRATE SEBESAR MUNGKIN (7000k - 7500k)
        val bitrateKbps = 7500 

        return VideoPlan(targetW, targetH, transposeStr, rotDesc, bitrateKbps, processDurationLimit)
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
        val plan = buildVideoPlan()
        val totalParts = ceil(plan.processDurationLimitSec.toDouble() / 30.0).toInt()
        val durText = if (plan.processDurationLimitSec == videoDurationSec) "${videoDurationSec}s (Full)" else "${plan.processDurationLimitSec}s (Trimmed)"
        
        tvMediaInfo.text = "> SONYBAE DEFINITIVE ENGINE (VIDEO)\n" +
                "> Source: ${origDisplayW}x${origDisplayH} | ${videoDurationSec}s | ${savedOriginalSizeKb} KB\n" +
                "> Target: Native Resolution | Massive 7500k Bitrate\n" +
                "> Config: ${plan.rotateDesc} | Output Parts: $totalParts (@30s)"
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
        tvStatusLog.text = "Injecting Massive Bitrate Engine (Native HD)..."

        scope.launch(Dispatchers.IO) {
            val outDir = prepareOutputDir()
            val safeInputPath = FFmpegKitConfig.getSafParameterForRead(this@MainActivity, uri)
            val outputPattern = File(outDir, "StatusHD_Part_%03d.mp4").absolutePath

            val plan = buildVideoPlan()
            val interval = 30
            val gopFrames = 30 * interval
            
            val vfFilter = "${plan.transposeFilter}scale=${plan.outW}:${plan.outH}:flags=lanczos,format=yuv420p"
            val timeLimitCmd = if (plan.processDurationLimitSec < videoDurationSec) "-t ${plan.processDurationLimitSec}" else ""

            FFmpegKitConfig.enableStatisticsCallback { stats: Statistics ->
                val timeSec = (stats.time / 1000.0).toInt()
                val pct = if (plan.processDurationLimitSec > 0) ((timeSec * 100) / plan.processDurationLimitSec).coerceIn(2, 99) else 50
                runOnUiThread {
                    progressBar.progress = pct
                    tvStatusLog.text = "Encoding Native 7500k: $pct% (${timeSec}s / ${plan.processDurationLimitSec}s)"
                }
            }

            // KODE SAKTI: RESOLUSI NATIVE, MAX BITRATE (7500k)
            val cmd = "-y -i \"$safeInputPath\" " +
                    "$timeLimitCmd " +
                    "-vf \"$vfFilter\" " +
                    "-r 30 -g $gopFrames " +
                    "-c:v libx264 -preset medium -profile:v high -level 4.2 -threads 8 " +
                    "-crf 15 -maxrate ${plan.bitrateKbps}k -bufsize ${plan.bitrateKbps * 2}k " +
                    "-bf 3 -refs 4 -aq-mode 3 " +
                    "-c:a aac -b:a 192k -ar 48000 -ac 2 " +
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
                    tvStatusLog.text = "System: Native HD Payload Successfully Injected!"
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
        tvStatusLog.text = "Building Static MP4 Stream Container..."

        scope.launch(Dispatchers.IO) {
            try {
                val outDir = prepareOutputDir()
                
                val decodeOpts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                var bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) } ?: throw Exception("Image decode error")

                val rot = contentResolver.openInputStream(uri)?.use { input ->
                    when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                        6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
                    }
                } ?: 0f

                if (rot != 0f) {
                    val mat = Matrix().apply { postRotate(rot) }
                    val rotBmp = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, mat, true)
                    if (rotBmp != bitmap) {
                        bitmap.recycle()
                        bitmap = rotBmp
                    }
                }

                val scale = minOf(1920f / maxOf(bitmap.width, bitmap.height).toFloat(), 1080f / minOf(bitmap.width, bitmap.height).toFloat()).coerceAtMost(1.0f)
                var pW = (bitmap.width * scale).toInt()
                var pH = (bitmap.height * scale).toInt()
                if (pW % 2 != 0) pW++
                if (pH % 2 != 0) pH++

                val scaled = Bitmap.createScaledBitmap(bitmap, pW, pH, true)
                if (scaled != bitmap) bitmap.recycle()

                val tempPng = File(cacheDir, "hd_frame.png")
                FileOutputStream(tempPng).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
                scaled.recycle()

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