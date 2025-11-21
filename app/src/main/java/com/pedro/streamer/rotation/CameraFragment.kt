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
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.sources.video.Camera1Source
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.extrasources.CameraXSource
import com.pedro.library.base.recording.RecordController
import com.pedro.library.generic.GenericStream
import com.pedro.library.util.BitrateAdapter
import com.pedro.streamer.R
import com.pedro.streamer.studio.StudioConstants
import com.pedro.streamer.utils.PathUtils
import com.pedro.streamer.utils.toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.max
import androidx.core.view.isVisible
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.library.rtmp.RtmpCamera1

/**
 * Example code to stream using StreamBase. This is the recommend way to use the library.
 * Necessary API 21+
 * This mode allow you stream using custom Video/Audio sources, attach a preview or not dynamically, support device rotation, etc.
 *
 * Check Menu to use filters, video and audio sources, and orientation
 *
 * Orientation horizontal (by default) means that you want stream with vertical resolution
 * (with = 640, height = 480 and rotation = 0) The stream/record result will be 640x480 resolution
 *
 * Orientation vertical means that you want stream with vertical resolution
 * (with = 640, height = 480 and rotation = 90) The stream/record result will be 480x640 resolution
 *
 * More documentation see:
 * [com.pedro.library.base.StreamBase]
 * Support RTMP, RTSP and SRT with commons features
 * [com.pedro.library.generic.GenericStream]
 * Support RTSP with all RTSP features
 * [com.pedro.library.rtsp.RtspStream]
 * Support RTMP with all RTMP features
 * [com.pedro.library.rtmp.RtmpStream]
 * Support SRT with all SRT features
 * [com.pedro.library.srt.SrtStream]
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class CameraFragment: Fragment(), ConnectChecker {

  companion object {
    fun getInstance(): CameraFragment = CameraFragment()
  }

  val genericStream: GenericStream by lazy {
    GenericStream(requireContext(), this).apply {
      getGlInterface().autoHandleOrientation = true
      getStreamClient().setBitrateExponentialFactor(0.5f)
    }
  }
  private lateinit var surfaceView: SurfaceView
  private lateinit var bStartStop: ImageView
  private lateinit var txtBitrate: TextView
  private lateinit var txtPacketLoss: TextView
  private lateinit var txtResolution: TextView
  private lateinit var txtFps: TextView
  private lateinit var txtMinBitrate: TextView
  private lateinit var txtMaxBitrate: TextView
  private lateinit var txtCodec: TextView
  private lateinit var txtBitrateMode: TextView
  private lateinit var etUrl: EditText
  var width = 1280
  var height = 720
  var vBitrate = 1000 * 1000
  private var rotation = 0
  private val sampleRate = 32000
  private val isStereo = true
  private val aBitrate = 128 * 1000
  private var recordPath = ""
  private var maxBitrate = 0 * 1000
  private var currentCodec: VideoCodec = VideoCodec.H264
  private var preferCbr: Boolean = false
  //Bitrate adapter used to change the bitrate on fly depend of the bandwidth.
  private val bitrateAdapter = BitrateAdapter {
    genericStream.setVideoBitrateOnFly(it)
  }.apply {
    setMaxBitrate(maxBitrate)
  }

  private var isOverlayVisible = true
  private var isScoringVisible = true
  private lateinit var toggleScoringButton: Button
  private lateinit var toggleOverlayButton: Button

  // Packet loss sampling state (for short-term loss rate)
  private var lastPacketsLost: Int = 0
  private var lastLossSampleTimeMs: Long = 0L
  private var isLossSampling: Boolean = false
  private val lossHandler = Handler(Looper.getMainLooper())
  private val lossSampler = object: Runnable {
    override fun run() {
      if (!isAdded) return
      if (!genericStream.isStreaming) {
        stopLossSampling()
        return
      }
      val packetsLost = genericStream.getStreamClient().getPacketsLost()
      val now = System.currentTimeMillis()
      val recentDelta = if (lastLossSampleTimeMs != 0L) (packetsLost - lastPacketsLost).coerceAtLeast(0) else 0
      lastLossSampleTimeMs = now
      lastPacketsLost = packetsLost
      // Show last-500ms loss count; color red if any loss in the window, otherwise white
      txtPacketLoss.text = "Pkt Loss: $recentDelta"
      if (recentDelta > 0) {
        txtPacketLoss.setTextColor(Color.RED)
      } else {
        txtPacketLoss.setTextColor(Color.WHITE)
      }
      lossHandler.postDelayed(this, 500L)
    }
  }

  private fun startLossSampling() {
    if (isLossSampling) return
    isLossSampling = true
    lastLossSampleTimeMs = 0L
    lossHandler.postDelayed(lossSampler, 500L)
  }

  private fun stopLossSampling() {
    if (!isLossSampling) return
    isLossSampling = false
    lossHandler.removeCallbacks(lossSampler)
  }

  @SuppressLint("ClickableViewAccessibility")
  override fun onCreateView(
    inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
  ): View? {
    val view = inflater.inflate(R.layout.fragment_camera, container, false)
    bStartStop = view.findViewById(R.id.b_start_stop)
    val bRecord = view.findViewById<ImageView>(R.id.b_record)
    val bSwitchCamera = view.findViewById<ImageView>(R.id.switch_camera)
    etUrl = view.findViewById(R.id.et_rtp_url)

    txtBitrate = view.findViewById(R.id.txt_bitrate)
    txtResolution = view.findViewById(R.id.txt_resolution)
    txtFps = view.findViewById(R.id.txt_fps)
    txtMinBitrate = view.findViewById(R.id.txt_min_bitrate)
    txtMaxBitrate = view.findViewById(R.id.txt_max_bitrate)
    txtCodec = view.findViewById(R.id.txt_codec)
    txtBitrateMode = view.findViewById(R.id.txt_bitrate_mode)
    txtPacketLoss = view.findViewById(R.id.txt_packet_loss)
    surfaceView = view.findViewById(R.id.surfaceView)
    (activity as? RotationActivity)?.let {
      surfaceView.setOnTouchListener(it)
    }
    surfaceView.holder.addCallback(object: SurfaceHolder.Callback {
      override fun surfaceCreated(holder: SurfaceHolder) {
        if (!genericStream.isOnPreview) genericStream.startPreview(surfaceView)
      }

      override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        genericStream.getGlInterface().setPreviewResolution(width, height)
      }

      override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (genericStream.isOnPreview) genericStream.stopPreview()
      }

    })

    bStartStop.setOnClickListener {
      if (!genericStream.isStreaming) {
        genericStream.startStream(etUrl.text.toString())
        bStartStop.setImageResource(R.drawable.stream_stop_icon)
        (activity as? RotationActivity)?.hideAppBar()
        etUrl.visibility = View.GONE
        startLossSampling()
      } else {
        genericStream.stopStream()
        bStartStop.setImageResource(R.drawable.stream_icon)
        (activity as? RotationActivity)?.showAppBar()
        etUrl.visibility = View.VISIBLE
        stopLossSampling()
      }
    }
    bRecord.setOnClickListener {
      if (!genericStream.isRecording) {
        val folder = PathUtils.getRecordPath()
        if (!folder.exists()) folder.mkdir()
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        recordPath = "${folder.absolutePath}/${sdf.format(Date())}.mp4"
        genericStream.startRecord(recordPath) { status ->
          if (status == RecordController.Status.RECORDING) {
            bRecord.setImageResource(R.drawable.stop_icon)
          }
        }
        bRecord.setImageResource(R.drawable.pause_icon)
      } else {
        genericStream.stopRecord()
        bRecord.setImageResource(R.drawable.record_icon)
        PathUtils.updateGallery(requireContext(), recordPath)
      }
    }
    bSwitchCamera.setOnClickListener {
      when (val source = genericStream.videoSource) {
        is Camera1Source -> source.switchCamera()
        is Camera2Source -> source.switchCamera()
        is CameraXSource -> source.switchCamera()
      }
    }
    updateResolutionDisplay()
    // Fixed FPS display
    txtFps.text = "30 fps"
    updateBitrateLabels()
    updateCodecLabel()
    updateBitrateModeLabel()
    return view
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    val matchId = arguments?.getString(StudioConstants.MATCH_ID_KEY)
    val refreshId = arguments?.getString(StudioConstants.REFRESH_ID_KEY)
    val refreshToken = arguments?.getString(StudioConstants.REFRESH_TOKEN_KEY)

    fun setupWebView(webView: WebView, url: String) {
      webView.visibility = View.VISIBLE
      webView.setBackgroundColor(Color.TRANSPARENT)
      webView.webViewClient = WebViewClient()
      webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        useWideViewPort = true
        loadWithOverviewMode = true
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
      }
      android.webkit.WebView.setWebContentsDebuggingEnabled(true)
      webView.loadUrl(url)
    }

    // Show scoring WebView on fragment open
    val scoringQuery = listOfNotNull(
      refreshId?.takeIf { it.isNotBlank() && it != "null" && it != "undefined" }?.let { "refreshId=$it" },
      refreshToken?.takeIf { it.isNotBlank() && it != "null" && it != "undefined" }?.let { "refreshToken=$it" }
    ).joinToString("&")

    Log.d("SCORING URL LOG", refreshId ?: "")

    val scoringUrl = "${StudioConstants.SCORING_OVERLAY_URL}/$matchId" + if (scoringQuery.isNotEmpty()) "?$scoringQuery" else ""
    val scoringWebView = view.findViewById<WebView>(R.id.scoringWebView)
    setupWebView(scoringWebView, scoringUrl)
    isScoringVisible = true

    // Show overlay WebView on fragment open
    val overlayUrl = "${StudioConstants.OVERLAY_URL}/preview/$matchId"
    val overlayWebView = view.findViewById<WebView>(R.id.overlayWebView)
    setupWebView(overlayWebView, overlayUrl)
    isOverlayVisible = true

    toggleScoringButton = view.findViewById<Button>(R.id.toggleScoringButton)
    toggleScoringButton.setOnClickListener {
      if (scoringWebView.isVisible) {
        scoringWebView.visibility = View.GONE
        scoringWebView.loadUrl("about:blank")
        isScoringVisible = false
      } else {
        setupWebView(scoringWebView, scoringUrl)
        isScoringVisible = true
      }
      updateScoringButtonHighlight()
    }

    toggleOverlayButton = view.findViewById<Button>(R.id.toggleOverlayButton)
    toggleOverlayButton.setOnClickListener {
      if (overlayWebView.isVisible) {
        overlayWebView.visibility = View.GONE
        overlayWebView.loadUrl("about:blank")
        isOverlayVisible = false
      } else {
        setupWebView(overlayWebView, overlayUrl)
        isOverlayVisible = true
      }
      updateOverlayButtonHighlight()
    }

    updateOverlayButtonHighlight()
    updateScoringButtonHighlight()
    handleZoomControls(view)
    setupTapToFocus()

    val micView = view.findViewById<ImageView>(R.id.b_mic)
    micView.setOnClickListener { handleAudio(it) }

    val autoFocusButton = view.findViewById<ImageView>(R.id.b_auto_focus)
    autoFocusButton.setOnClickListener {
      handleAutoFocus(it)
    }
  }

  private fun showFocusIndicator(x: Float, y: Float) {
    val indicator = view?.findViewById<ImageView>(R.id.focusIndicator) ?: return

    indicator.translationX = x - indicator.width / 2
    indicator.translationY = y - indicator.height / 2
    indicator.visibility = View.VISIBLE
    indicator.alpha = 1f

    indicator.animate()
      .alpha(0f)
      .setDuration(800)
      .withEndAction { indicator.visibility = View.GONE }
      .start()
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun setupTapToFocus() {
    val surfaceView = view?.findViewById<SurfaceView>(R.id.surfaceView) ?: return

    surfaceView.setOnTouchListener { v, event ->
      if (event.action == MotionEvent.ACTION_UP) {
        val cameraSource = genericStream.videoSource
        val focused = when (cameraSource) {
          is Camera1Source -> cameraSource.tapToFocus(v, event)
          is Camera2Source -> cameraSource.tapToFocus(v, event)
          is CameraXSource -> cameraSource.tapToFocus(v, event)
          else -> false
        }

        if (focused) {
          showFocusIndicator(event.x, event.y)
        }
      }
      true
    }
  }

  private fun handleAutoFocus(view: View) {
    val cameraSource = genericStream.videoSource
    val autoFocusButton = view as ImageView

    when (cameraSource) {
      is Camera1Source -> {
        val enabled = cameraSource.isAutoFocusEnabled()
        if (enabled) cameraSource.disableAutoFocus() else cameraSource.enableAutoFocus()
        autoFocusButton.alpha = if (enabled) 0.5f else 1f
      }
      is Camera2Source -> {
        val enabled = cameraSource.isAutoFocusEnabled()
        if (enabled) cameraSource.disableAutoFocus() else cameraSource.enableAutoFocus()
        autoFocusButton.alpha = if (enabled) 0.5f else 1f
      }
      is CameraXSource -> {
        val enabled = cameraSource.isAutoFocusEnabled()
        if (enabled) cameraSource.disableAutoFocus() else cameraSource.enableAutoFocus()
        autoFocusButton.alpha = if (enabled) 0.5f else 1f
      }
    }
  }


  private fun handleAudio(view: View) {
    val source = genericStream.audioSource as MicrophoneSource
    val isMuted = source.isMuted()

    val micView = view as ImageView

    if (isMuted) {
      source.unMute()
      micView.setImageResource(R.drawable.ic_baseline_mic_24)
      micView.alpha = 1.0f
    } else {
      source.mute()
      micView.setImageResource(R.drawable.ic_baseline_mic_off_24)
      micView.alpha = 0.5f
    }
  }

  private fun handleZoomControls(view: View) {
    val zoomSeek = view.findViewById<SeekBar>(R.id.zoom_seekbar)

    zoomSeek.visibility = View.GONE
    zoomSeek.alpha = 0f

    zoomSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
      override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        if (fromUser) {
          val source = genericStream.videoSource
          when (source) {
            is Camera1Source -> {
              val zoomRange = source.getZoomRange()
              val newZoom = zoomRange.lower + (progress * (zoomRange.upper - zoomRange.lower) / seekBar!!.max)
              source.setZoom(newZoom)
              Log.d("CameraFragment", "Zoom In: $newZoom/${zoomRange.upper}")
            }
            is Camera2Source -> {
              val zoomRange = source.getZoomRange()
              val newZoom = zoomRange.lower + (progress * (zoomRange.upper - zoomRange.lower) / seekBar!!.max)
              source.setZoom(newZoom)
              Log.d("CameraFragment", "Zoom In: $newZoom/${zoomRange.upper}")
            }
            is CameraXSource -> {
              val zoomRange = source.getZoomRange()
              val newZoom = zoomRange.lower + (progress * (zoomRange.upper - zoomRange.lower) / seekBar!!.max)
              source.setZoom(newZoom)
              Log.d("CameraFragment", "Zoom In: $newZoom/${zoomRange.upper}")
            }
          }
        }
      }
      override fun onStartTrackingTouch(seekBar: SeekBar?) {}
      override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    })

    val bZoom = view.findViewById<ImageView>(R.id.b_zoom)
    bZoom.alpha = 0.5f
    bZoom.setOnClickListener {
      if (zoomSeek.isVisible) {
        zoomSeek.animate().alpha(0f).setDuration(200).withEndAction { zoomSeek.visibility = View.GONE }
        bZoom.alpha = 0.5f
      } else {
        zoomSeek.alpha = 0f
        zoomSeek.visibility = View.VISIBLE
        zoomSeek.animate().alpha(1f).setDuration(200)
        bZoom.alpha = 1.0f
      }
    }
  }

  private fun updateOverlayButtonHighlight() {
    if (isOverlayVisible) {
      toggleOverlayButton.setBackgroundResource(R.drawable.button_highlight_background)
      toggleOverlayButton.alpha = 1.0f
    } else {
      toggleOverlayButton.setBackgroundResource(R.drawable.white_rounded_border)
      toggleOverlayButton.alpha = 0.75f
    }
  }

  private fun updateScoringButtonHighlight() {
    if (isScoringVisible) {
      toggleScoringButton.setBackgroundResource(R.drawable.button_highlight_background)
      toggleScoringButton.alpha = 1.0f
    } else {
      toggleScoringButton.setBackgroundResource(R.drawable.white_rounded_border)
      toggleScoringButton.alpha = 0.75f
    }
  }

  fun setStreamUrl(url: String) {
    if (this::etUrl.isInitialized) {
      etUrl.setText(url)
    }
  }

  fun getStreamUrl(): String {
    return if (this::etUrl.isInitialized) etUrl.text.toString() else ""
  }

  fun setOrientationMode(isVertical: Boolean) {
    val wasOnPreview = genericStream.isOnPreview
    genericStream.release()
    rotation = if (isVertical) 90 else 0
    prepare()
    if (wasOnPreview) genericStream.startPreview(surfaceView)
  }

  fun setResolution(newWidth: Int, newHeight: Int) {
    val wasOnPreview = genericStream.isOnPreview
    genericStream.release()
    width = newWidth
    height = newHeight
    updateResolutionDisplay()
    prepare()
    if (wasOnPreview) genericStream.startPreview(surfaceView)
  }

  private fun updateResolutionDisplay() {
    val verticalLines = min(width, height)
    txtResolution.text = "${verticalLines}p"
  }

  private fun updateBitrateLabels() {
    txtMinBitrate.text = "Bitrate: ${vBitrate / 1_000_000.0} Mbps"
    txtMaxBitrate.text = "max: ${maxBitrate / 1_000_000} Mbps"
  }

  private fun updateCodecLabel() {
    txtCodec.text = currentCodec.name
  }

  private fun updateBitrateModeLabel() {
    txtBitrateMode.text = if (preferCbr) "CBR" else "VBR"
  }

  fun setVideoCodec(codec: VideoCodec) {
    currentCodec = codec
    genericStream.setVideoCodec(codec)
    updateCodecLabel()
  }

  fun setBitrateMode(preferCbr: Boolean) {
    genericStream.setPreferCbr(preferCbr)
    this.preferCbr = preferCbr
    updateBitrateModeLabel()
    if (genericStream.isStreaming) {
      toast("Bitrate mode will apply on next start")
      return
    }
    val wasOnPreview = genericStream.isOnPreview
    genericStream.release()
    prepare()
    if (wasOnPreview) genericStream.startPreview(surfaceView)
  }

  fun safeSetVideoCodec(codec: VideoCodec): Boolean {
    val wasOnPreview = genericStream.isOnPreview
    val previousCodec = currentCodec
    try {
      genericStream.release()
      currentCodec = codec
      genericStream.setVideoCodec(codec)
      prepare()
      if (wasOnPreview) genericStream.startPreview(surfaceView)
      updateCodecLabel()
      return true
    } catch (_: Exception) {
      // revert
      try {
        genericStream.release()
        currentCodec = previousCodec
        genericStream.setVideoCodec(previousCodec)
        prepare()
        if (wasOnPreview) genericStream.startPreview(surfaceView)
      } catch (_: Exception) {}
      updateCodecLabel()
      return false
    }
  }

  fun setMinBitrateMbps(mbps: Double) {
    vBitrate = (mbps * 1_000_000).toInt()
    if (genericStream.isStreaming) {
      genericStream.setVideoBitrateOnFly(vBitrate)
      updateBitrateLabels()
      return
    }
    val wasOnPreview = genericStream.isOnPreview
    genericStream.release()
    prepare()
    if (wasOnPreview) genericStream.startPreview(surfaceView)
    updateBitrateLabels()
  }

  fun setMaxBitrateMbps(mbps: Int) {
    maxBitrate = mbps * 1_000_000
    bitrateAdapter.setMaxBitrate(maxBitrate)
    updateBitrateLabels()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    prepare()
    genericStream.getStreamClient().setReTries(10)
  }

  private fun prepare() {
    val prepared = try {
      genericStream.prepareVideo(width, height, vBitrate, rotation = rotation)
          && genericStream.prepareAudio(sampleRate, isStereo, aBitrate)
    } catch (_: IllegalArgumentException) {
      false
    }
    if (!prepared) {
      toast("Audio or Video configuration failed")
      activity?.finish()
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    genericStream.release()
    stopLossSampling()
  }

  override fun onConnectionStarted(url: String) {
  }

  override fun onConnectionSuccess() {
    toast("Connected")
  }

  override fun onConnectionFailed(reason: String) {
    if (genericStream.getStreamClient().reTry(5000, reason, null)) {
      toast("Retry: $reason")
    } else {
      genericStream.stopStream()
      bStartStop.setImageResource(R.drawable.stream_icon)
      // Show app bar when streaming fails
      (activity as? RotationActivity)?.showAppBar()
      toast("Failed: $reason")
    }
  }

  override fun onNewBitrate(bitrate: Long) {
    bitrateAdapter.adaptBitrate(bitrate, genericStream.getStreamClient().hasCongestion())
    txtBitrate.text = String.format(Locale.getDefault(), "%.1f mb/s", bitrate / 1000_000f)
  }

  override fun onDisconnect() {
    txtBitrate.text = String()
    // Show app bar when disconnected
    (activity as? RotationActivity)?.showAppBar()
    toast("Disconnected")
    stopLossSampling()
  }

  override fun onAuthError() {
    genericStream.stopStream()
    bStartStop.setImageResource(R.drawable.stream_icon)
    // Show app bar when auth error occurs
    (activity as? RotationActivity)?.showAppBar()
    toast("Auth error")
  }

  override fun onAuthSuccess() {
    toast("Auth success")
  }

}