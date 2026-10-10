package com.example.aiautoagent

import android.media.MediaPlayer
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.io.File

class GLiveWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return VideoEngine()
    }

    inner class VideoEngine : Engine() {
        private var mediaPlayer: MediaPlayer? = null

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            startVideo(holder)
        }

        private fun startVideo(holder: SurfaceHolder) {
            try {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setSurface(holder.surface)
                    val prefs = getSharedPreferences("wallpaper_prefs", MODE_PRIVATE)
                    val videoPath = prefs.getString("current_video_path", null)
                    val isMuted = prefs.getBoolean("is_muted", true)

                    if (videoPath != null && File(videoPath).exists()) {
                        setDataSource(videoPath)
                    } else {
                        return
                    }

                    isLooping = true
                    if (isMuted) setVolume(0f, 0f) else setVolume(1f, 1f)
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                mediaPlayer?.start()
            } else {
                mediaPlayer?.pause()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }
}
