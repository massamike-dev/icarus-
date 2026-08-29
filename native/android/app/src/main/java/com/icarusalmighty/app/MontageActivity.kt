package com.icarusalmighty.app

import android.content.ContentValues
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File

class MontageActivity : AppCompatActivity() {
    private val selected = mutableListOf<Uri>()
    private lateinit var status: TextView
    private lateinit var export: Button
    private var transformer: Transformer? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        selected.clear()
        selected.addAll(uris)
        uris.forEach { uri ->
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        status.text = if (uris.isEmpty()) "No videos selected." else "${uris.size} videos ready. Review your selection, then create the montage."
        export.isEnabled = uris.isNotEmpty()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "ICARUS Montage"
        val query = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        status = TextView(this).apply {
            textSize = 18f
            text = if (query.isBlank()) {
                "Choose the videos ICARUS should combine."
            } else {
                "Requested subject: “$query”\n\nChoose the matching videos. ICARUS will never upload or edit other files without this review."
            }
        }
        val choose = Button(this).apply {
            text = "Choose videos"
            setOnClickListener { picker.launch(arrayOf("video/*")) }
        }
        export = Button(this).apply {
            text = "Create montage"
            isEnabled = false
            setOnClickListener { createMontage() }
        }
        val close = Button(this).apply {
            text = "Cancel"
            setOnClickListener { finish() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 64, 48, 48)
            addView(status, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(choose, LinearLayout.LayoutParams(-1, -2))
            addView(export, LinearLayout.LayoutParams(-1, -2))
            addView(close, LinearLayout.LayoutParams(-1, -2))
        })
    }

    private fun createMontage() {
        if (selected.isEmpty()) return
        export.isEnabled = false
        status.text = "Creating montage… Keep ICARUS open until export finishes."
        val items = selected.map { EditedMediaItem.Builder(MediaItem.fromUri(it)).build() }
        val sequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
            .addItems(items)
            .build()
        val composition = Composition.Builder(sequence).build()
        val temp = File(externalCacheDir ?: cacheDir, "icarus-montage-${System.currentTimeMillis()}.mp4")
        transformer = Transformer.Builder(this)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    saveToGallery(temp)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    status.text = "The montage could not be created. Your original videos were not changed."
                    export.isEnabled = true
                }
            })
            .build()
        transformer?.start(composition, temp.absolutePath)
    }

    private fun saveToGallery(temp: File) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "ICARUS_Montage_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ICARUS")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            status.text = "Montage created, but Android could not save it to Movies."
            export.isEnabled = true
            return
        }
        runCatching {
            contentResolver.openOutputStream(uri)?.use { output -> temp.inputStream().use { it.copyTo(output) } }
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            temp.delete()
        }.onSuccess {
            status.text = "Montage saved to Movies/ICARUS."
            Toast.makeText(this, "ICARUS montage saved", Toast.LENGTH_LONG).show()
        }.onFailure {
            contentResolver.delete(uri, null, null)
            status.text = "The montage was rendered, but Android could not save it."
            export.isEnabled = true
        }
    }

    override fun onDestroy() {
        transformer?.cancel()
        super.onDestroy()
    }

    companion object { const val EXTRA_QUERY = "query" }
}