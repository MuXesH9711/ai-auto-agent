package com.example.aiautoagent

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {

    private val PICK_VIDEO_REQ = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnPickGallery).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "video/*"
            }
            startActivityForResult(intent, PICK_VIDEO_REQ)
        }

        findViewById<Button>(R.id.btnApplyWallpaper).setOnClickListener {
            launchWallpaperChooser()
        }

        findViewById<Button>(R.id.btnAnimePreset).setOnClickListener {
            Toast.makeText(this, "Anime category selected", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnCarPreset).setOnClickListener {
            Toast.makeText(this, "Supercar category selected", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnCyberPreset).setOnClickListener {
            Toast.makeText(this, "Cyberpunk category selected", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnGenerateAi).setOnClickListener {
            Toast.makeText(this, "Connecting to AI Generator...", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_VIDEO_REQ && resultCode == RESULT_OK && data?.data != null) {
            saveVideoLocally(data.data!!)
        }
    }

    private fun saveVideoLocally(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val file = File(filesDir, "custom_live_wallpaper.mp4")
            val outputStream = FileOutputStream(file)
            inputStream?.copyTo(outputStream)
            inputStream?.close()
            outputStream.close()

            getSharedPreferences("wallpaper_prefs", MODE_PRIVATE)
                .edit()
                .putString("current_video_path", file.absolutePath)
                .putBoolean("is_muted", true)
                .apply()

            Toast.makeText(this, "Video saved! Now tap 'Apply Live Wallpaper'", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error loading video", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchWallpaperChooser() {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this@MainActivity, GLiveWallpaperService::class.java)
            )
        }
        startActivity(intent)
    }
}
