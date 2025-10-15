/*
 * Copyright (C) 2024 pedroSG94.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pedro.streamer.rotation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import android.util.Log
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.BitmapSource
import com.pedro.encoder.input.sources.video.BufferVideoSource
import com.pedro.encoder.input.sources.video.Camera1Source
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.extrasources.CameraUvcSource
import com.pedro.extrasources.CameraXSource
import com.pedro.streamer.R
import com.pedro.streamer.utils.FilterMenu
import com.pedro.streamer.utils.dataStore
import com.pedro.streamer.utils.fitAppPadding
import com.pedro.streamer.utils.toast
import com.pedro.streamer.utils.updateMenuColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import com.pedro.streamer.studio.StudioConstants


/**
 * Created by pedro on 22/3/22.
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class RotationActivity : AppCompatActivity(), OnTouchListener {

  private val cameraFragment = CameraFragment.getInstance()
  private val filterMenu: FilterMenu by lazy { FilterMenu(this) }
  private var currentVideoSource: MenuItem? = null
  private var currentAudioSource: MenuItem? = null
  private var currentOrientation: MenuItem? = null
  private var currentFilter: MenuItem? = null
  private var currentResolution: MenuItem? = null
  private var currentMinBitrate: MenuItem? = null
  private var currentMaxBitrate: MenuItem? = null
  private var currentCodec: MenuItem? = null
  private var currentBitrateMode: MenuItem? = null
  private var menu: Menu? = null

  private val PERMISSIONS_REQUEST = 1001
  private val REQUIRED_PERMISSIONS = arrayOf(
    android.Manifest.permission.CAMERA,
    android.Manifest.permission.RECORD_AUDIO
  )

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.rotation_activity)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    
    // Hide status bar for fullscreen camera experience
    hideStatusBar()
    // Remove fitAppPadding() to allow camera to extend into notch/cutout area
    // Log incoming session params from Intent extras
    val matchId = intent.getStringExtra(com.pedro.streamer.studio.StudioConstants.MATCH_ID_KEY)
    val refreshId = intent.getStringExtra(com.pedro.streamer.studio.StudioConstants.REFRESH_ID_KEY)
    val refreshToken = intent.getStringExtra(com.pedro.streamer.studio.StudioConstants.REFRESH_TOKEN_KEY)

    val args = Bundle()
    args.putString(StudioConstants.MATCH_ID_KEY, matchId)
    args.putString(StudioConstants.REFRESH_ID_KEY, refreshId)
    args.putString(StudioConstants.REFRESH_TOKEN_KEY, refreshToken)
    cameraFragment.arguments = args

    cameraFragment.viewLifecycleOwnerLiveData.observe(this) { owner ->
      if (owner != null) {
        lifecycleScope.launch(Dispatchers.Main) {
          val url = getSrtUrl(applicationContext)
          val bitrateMbps = getBitrate(applicationContext)
          val resolutionString = getResolution(applicationContext)
          
          if (url.isNotBlank()) cameraFragment.setStreamUrl(url)
          if (bitrateMbps > 0) {
            cameraFragment.setMinBitrateMbps(bitrateMbps)
            updateMinBitrateMenuColor(bitrateMbps)
          }
          if (!resolutionString.isNullOrBlank()) {
            val parts = resolutionString.split("x")
            if (parts.size == 2) {
              val width = parts[0].toIntOrNull()
              val height = parts[1].toIntOrNull()
              if (width != null && height != null) {
                cameraFragment.setResolution(width, height)
                updateResolutionMenuColor(resolutionString)
              }
            }
          }
        }
      }
    }
    if (hasAllPermissions()) {
      supportFragmentManager.beginTransaction().add(R.id.container, cameraFragment).commit()
    } else {
      ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSIONS_REQUEST)
    }
  }

  suspend fun getBitrate(context: Context): Double {
    return withContext(Dispatchers.IO) {
      val prefs = context.dataStore.data.first()
      val intVal = prefs[intPreferencesKey("live_video_bitrate_key")]
      val mbps: Double =  intVal?.let { it / 1_000.0 }  ?: 0.0
      mbps
    }
  }

  suspend fun getSrtUrl(context: Context): String {
    return withContext(Dispatchers.IO) {
      val prefs = context.dataStore.data.first()

      val ip = prefs[stringPreferencesKey("srt_server_ip_key")]
      val port = prefs[stringPreferencesKey("srt_server_port_key")]
      val streamId = prefs[stringPreferencesKey("server_stream_id_key")]

      if (ip.isNullOrBlank() || port.isNullOrBlank() || streamId.isNullOrBlank()) {
        Log.e("SRT_URL", "Missing required parameters: ip=$ip, port=$port, streamId=$streamId")
        return@withContext ""
      }

      val url = "srt://$ip:$port?mode=caller&streamid=$streamId"
      Log.d("SRT_URL", url)
      url
    }
  }

  suspend fun getResolution(context: Context): String? {
    return withContext(Dispatchers.IO) {
      val prefs = context.dataStore.data.first()
      prefs[stringPreferencesKey("video_resolution_key")]
    }
  }

  private fun updateMinBitrateMenuColor(bitrateMbps: Double) {
    menu?.let { menu ->
      val bitrateValues = arrayOf(0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 4.5, 5.0)
      val menuIds = arrayOf(
        R.id.min_bitrate_1, R.id.min_bitrate_2, R.id.min_bitrate_3, R.id.min_bitrate_4, R.id.min_bitrate_5,
        R.id.min_bitrate_6, R.id.min_bitrate_7, R.id.min_bitrate_8, R.id.min_bitrate_9, R.id.min_bitrate_10
      )
      
      val index = bitrateValues.indexOf(bitrateMbps)
      if (index >= 0 && index < menuIds.size) {
        val menuItem = menu.findItem(menuIds[index])
        currentMinBitrate = menuItem.updateMenuColor(this, currentMinBitrate)
      }
    }
  }

  private fun updateResolutionMenuColor(resolutionString: String) {
    menu?.let { menu ->
      val parts = resolutionString.split("x")
      if (parts.size == 2) {
        val width = parts[0].toIntOrNull()
        val height = parts[1].toIntOrNull()
        
        if (width != null && height != null) {
          val menuItem = when {
            width == 854 && height == 480 -> menu.findItem(R.id.resolution_480p)
            width == 1280 && height == 720 -> menu.findItem(R.id.resolution_720p)
            width == 1920 && height == 1080 -> menu.findItem(R.id.resolution_1080p)
            else -> null
          }
          
          menuItem?.let {
            currentResolution = it.updateMenuColor(this, currentResolution)
          }
        }
      }
    }
  }


  private fun hasAllPermissions(): Boolean {
    return REQUIRED_PERMISSIONS.all { perm ->
      ContextCompat.checkSelfPermission(this, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == PERMISSIONS_REQUEST && hasAllPermissions()) {
      supportFragmentManager.beginTransaction().replace(R.id.container, cameraFragment).commit()
    } else if (requestCode == PERMISSIONS_REQUEST) {
      toast("Camera and Microphone permissions are required")
      finish()
    }
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    this.menu = menu
    menuInflater.inflate(R.menu.rotation_menu, menu)
    val defaultVideoSource = menu.findItem(R.id.video_source_camera2)
    val defaultAudioSource = menu.findItem(R.id.audio_source_microphone)
    val defaultOrientation = menu.findItem(R.id.orientation_horizontal)
    val defaultFilter = menu.findItem(R.id.no_filter)
    val defaultResolution = menu.findItem(R.id.resolution_720p)
    val defaultMinBitrate = menu.findItem(R.id.min_bitrate_1)
    val defaultMaxBitrate = menu.findItem(R.id.max_bitrate_auto)
    val defaultCodec = menu.findItem(R.id.codec_h265)
    val defaultBitrateMode = menu.findItem(R.id.bitrate_mode_vbr)
    currentVideoSource = defaultVideoSource.updateMenuColor(this, currentVideoSource)
    currentAudioSource = defaultAudioSource.updateMenuColor(this, currentAudioSource)
    currentOrientation = defaultOrientation.updateMenuColor(this, currentOrientation)
    currentFilter = defaultFilter.updateMenuColor(this, currentFilter)
    currentResolution = defaultResolution.updateMenuColor(this, currentResolution)
    currentMinBitrate = defaultMinBitrate.updateMenuColor(this, currentMinBitrate)
    currentMaxBitrate = defaultMaxBitrate.updateMenuColor(this, currentMaxBitrate)
    currentCodec = defaultCodec.updateMenuColor(this, currentCodec)
    currentBitrateMode = defaultBitrateMode.updateMenuColor(this, currentBitrateMode)
    cameraFragment.setMinBitrateMbps(1.0)
    cameraFragment.setMaxBitrateMbps(0)
    cameraFragment.setVideoCodec(com.pedro.common.VideoCodec.H265)
    cameraFragment.setResolution(1280, 720)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    try {
      when (item.itemId) {
        R.id.video_source_camera1 -> {
          currentVideoSource = item.updateMenuColor(this, currentVideoSource)
          cameraFragment.genericStream.changeVideoSource(Camera1Source(applicationContext))
        }
        R.id.video_source_camera2 -> {
          currentVideoSource = item.updateMenuColor(this, currentVideoSource)
          cameraFragment.genericStream.changeVideoSource(Camera2Source(applicationContext))
        }
        R.id.video_source_camerax -> {
          currentVideoSource = item.updateMenuColor(this, currentVideoSource)
          cameraFragment.genericStream.changeVideoSource(CameraXSource(applicationContext))
        }
        R.id.video_source_camera_uvc -> {
          currentVideoSource = item.updateMenuColor(this, currentVideoSource)
          cameraFragment.genericStream.changeVideoSource(CameraUvcSource())
        }
        R.id.video_source_bitmap -> {
          currentVideoSource = item.updateMenuColor(this, currentVideoSource)
          val bitmap = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
          cameraFragment.genericStream.changeVideoSource(BitmapSource(bitmap))
        }
        R.id.video_source_buffer -> {
          currentVideoSource = item.updateMenuColor(this, currentVideoSource)
          val bitmap = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
          val data = bitmapToRgba(bitmap.scale(cameraFragment.width, cameraFragment.height))
          val source = BufferVideoSource(BufferVideoSource.Format.ARGB, cameraFragment.vBitrate)
          cameraFragment.genericStream.changeVideoSource(source)
          CoroutineScope(Dispatchers.IO).launch {
            while (cameraFragment.genericStream.videoSource is BufferVideoSource) {
              source.setBuffer(data.clone())
              delay(1000 / 30)
            }
          }
        }
        R.id.audio_source_microphone -> {
          currentAudioSource = item.updateMenuColor(this, currentAudioSource)
          cameraFragment.genericStream.changeAudioSource(MicrophoneSource())
        }
        R.id.orientation_horizontal -> {
          currentOrientation = item.updateMenuColor(this, currentOrientation)
          cameraFragment.setOrientationMode(false)
        }
        R.id.orientation_vertical -> {
          currentOrientation = item.updateMenuColor(this, currentOrientation)
          cameraFragment.setOrientationMode(true)
        }
        R.id.resolution_480p -> {
          currentResolution = item.updateMenuColor(this, currentResolution)
          cameraFragment.setResolution(854, 480)
        }
        R.id.resolution_720p -> {
          currentResolution = item.updateMenuColor(this, currentResolution)
          cameraFragment.setResolution(1280, 720)
        }
        R.id.resolution_1080p -> {
          currentResolution = item.updateMenuColor(this, currentResolution)
          cameraFragment.setResolution(1920, 1080)
        }
        R.id.codec_h264 -> {
          val prev = currentCodec
          if (cameraFragment.safeSetVideoCodec(com.pedro.common.VideoCodec.H264)) {
            currentCodec = item.updateMenuColor(this, currentCodec)
          } else {
            toast("H264 not supported")
            prev?.let { currentCodec = it.updateMenuColor(this, currentCodec) }
          }
        }
        R.id.codec_h265 -> {
          val prev = currentCodec
          if (cameraFragment.safeSetVideoCodec(com.pedro.common.VideoCodec.H265)) {
          currentCodec = item.updateMenuColor(this, currentCodec)
          } else {
            toast("H265 not supported on this device/protocol")
            prev?.let { currentCodec = it.updateMenuColor(this, currentCodec) }
          }
        }
        R.id.bitrate_mode_vbr -> {
          currentBitrateMode = item.updateMenuColor(this, currentBitrateMode)
          cameraFragment.setBitrateMode(false)
        }
        R.id.bitrate_mode_cbr -> {
          currentBitrateMode = item.updateMenuColor(this, currentBitrateMode)
          cameraFragment.setBitrateMode(true)
        }
        R.id.min_bitrate_1 -> { currentMinBitrate = item.updateMenuColor(this, currentMinBitrate); cameraFragment.setMinBitrateMbps(0.5) }
        R.id.min_bitrate_2 -> { currentMinBitrate = item.updateMenuColor(this, currentMinBitrate); cameraFragment.setMinBitrateMbps(1.0) }
        R.id.min_bitrate_3 -> { currentMinBitrate = item.updateMenuColor(this, currentMinBitrate); cameraFragment.setMinBitrateMbps(1.5) }
        R.id.min_bitrate_4 -> { currentMinBitrate = item.updateMenuColor(this, currentMinBitrate); cameraFragment.setMinBitrateMbps(2.0) }
        R.id.min_bitrate_5 -> { currentMinBitrate = item.updateMenuColor(this, currentMinBitrate); cameraFragment.setMinBitrateMbps(2.5) }
        R.id.min_bitrate_6 -> { currentMinBitrate = item.updateMenuColor(this, currentMinBitrate); cameraFragment.setMinBitrateMbps(3.0) }
        R.id.min_bitrate_7 -> { currentMinBitrate = item.updateMenuColor(this, currentMinBitrate); cameraFragment.setMinBitrateMbps(3.5) }
        R.id.min_bitrate_8 -> { currentMinBitrate = item.updateMenuColor(this, currentMinBitrate); cameraFragment.setMinBitrateMbps(4.0) }
        R.id.min_bitrate_9 -> { currentMinBitrate = item.updateMenuColor(this, currentMinBitrate); cameraFragment.setMinBitrateMbps(4.5) }
        R.id.min_bitrate_10 -> { currentMinBitrate = item.updateMenuColor(this, currentMinBitrate); cameraFragment.setMinBitrateMbps(5.0) }
        R.id.max_bitrate_1 -> { currentMaxBitrate = item.updateMenuColor(this, currentMaxBitrate); cameraFragment.setMaxBitrateMbps(1) }
        R.id.max_bitrate_2 -> { currentMaxBitrate = item.updateMenuColor(this, currentMaxBitrate); cameraFragment.setMaxBitrateMbps(2) }
        R.id.max_bitrate_3 -> { currentMaxBitrate = item.updateMenuColor(this, currentMaxBitrate); cameraFragment.setMaxBitrateMbps(3) }
        R.id.max_bitrate_4 -> { currentMaxBitrate = item.updateMenuColor(this, currentMaxBitrate); cameraFragment.setMaxBitrateMbps(4) }
        R.id.max_bitrate_5 -> { currentMaxBitrate = item.updateMenuColor(this, currentMaxBitrate); cameraFragment.setMaxBitrateMbps(5) }
        R.id.max_bitrate_6 -> { currentMaxBitrate = item.updateMenuColor(this, currentMaxBitrate); cameraFragment.setMaxBitrateMbps(6) }
        R.id.max_bitrate_7 -> { currentMaxBitrate = item.updateMenuColor(this, currentMaxBitrate); cameraFragment.setMaxBitrateMbps(7) }
        R.id.max_bitrate_8 -> { currentMaxBitrate = item.updateMenuColor(this, currentMaxBitrate); cameraFragment.setMaxBitrateMbps(8) }
        R.id.max_bitrate_9 -> { currentMaxBitrate = item.updateMenuColor(this, currentMaxBitrate); cameraFragment.setMaxBitrateMbps(9) }
        R.id.max_bitrate_10 -> { currentMaxBitrate = item.updateMenuColor(this, currentMaxBitrate); cameraFragment.setMaxBitrateMbps(10) }
        R.id.max_bitrate_auto -> { currentMaxBitrate = item.updateMenuColor(this, currentMaxBitrate); cameraFragment.setMaxBitrateMbps(0) }
        else -> {
          val result = filterMenu.onOptionsItemSelected(item, cameraFragment.genericStream.getGlInterface())
          if (result) currentFilter = item.updateMenuColor(this, currentFilter)
          return result
        }
      }
    } catch (e: IllegalArgumentException) {
      toast("Change source error: ${e.message}")
    }
    return super.onOptionsItemSelected(item)
  }

  private fun bitmapToRgba(bitmap: Bitmap): IntArray {
    require(bitmap.config == Bitmap.Config.ARGB_8888) { "Bitmap must be in ARGB_8888 format" }
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return pixels
  }

  @SuppressLint("ClickableViewAccessibility")
  override fun onTouch(view: View, motionEvent: MotionEvent): Boolean {
    if (filterMenu.spriteGestureController.spriteTouched(view, motionEvent)) {
      filterMenu.spriteGestureController.moveSprite(view, motionEvent)
      filterMenu.spriteGestureController.scaleSprite(motionEvent)
      return true
    }
    return false
  }

  fun hideAppBar() {
    supportActionBar?.hide()
    // Keep status bar hidden during streaming for fullscreen experience
  }

  fun showAppBar() {
    supportActionBar?.show()
    // showStatusBar() // Show status bar when accessing controls
  }

  private fun hideStatusBar() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      window.insetsController?.let { controller ->
        controller.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
        controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      }
    } else {
      @Suppress("DEPRECATION")
      window.decorView.systemUiVisibility = (
        View.SYSTEM_UI_FLAG_FULLSCREEN or
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
      )
    }
    
    // Ensure content extends into cutout/notch area
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
  }

  private fun showStatusBar() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      window.insetsController?.let { controller ->
        controller.show(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
      }
    } else {
      @Suppress("DEPRECATION")
      window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }
    
    // Restore normal cutout handling when showing status bar
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
    }
  }

  override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
    super.onConfigurationChanged(newConfig)
    // Re-apply fullscreen mode after rotation to ensure notch coverage
    hideStatusBar()
  }
}