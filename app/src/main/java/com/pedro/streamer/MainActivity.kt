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
package com.pedro.streamer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.widget.AdapterView.OnItemClickListener
import android.widget.GridView
import android.widget.TextView
import android.widget.TableLayout
import android.widget.TableRow
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.pedro.streamer.file.FromFileActivity
import com.pedro.streamer.oldapi.OldApiActivity
import com.pedro.streamer.rotation.RotationActivity
import com.pedro.streamer.screen.ScreenActivity
import com.pedro.streamer.studio.DeepLinkParams
import com.pedro.streamer.utils.ActivityLink
import com.pedro.streamer.utils.ImageAdapter
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import com.pedro.streamer.studio.StudioConstants
import com.pedro.streamer.utils.dataStore
import com.pedro.streamer.utils.fitAppPadding
import com.pedro.streamer.utils.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collectLatest

class MainActivity : AppCompatActivity() {

  private lateinit var list: GridView
  private val activities: MutableList<ActivityLink> = mutableListOf()

  private val permissions = mutableListOf(
    Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA,
  ).apply {
    if (Build.VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) {
      this.add(Manifest.permission.POST_NOTIFICATIONS)
    }
  }.toTypedArray()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    // Parse deep link parameters
    handleDeepLink(intent)

    fitAppPadding()
    transitionAnim(true)
    val tvVersion = findViewById<TextView>(R.id.tv_version)
    tvVersion.text = getString(R.string.version, BuildConfig.VERSION_NAME)
    val table = findViewById<TableLayout>(R.id.table_datastore)
    list = findViewById(R.id.list)
    createList()
    setListAdapter(activities)
    requestPermissions()

    // Observe DataStore and show current values
    lifecycleScope.launch(Dispatchers.Main) {
      applicationContext.dataStore.data
        .map { prefs ->
          listOf(
            "Resolution" to (prefs[stringPreferencesKey("video_resolution_key")] ?: "-"),
            "FPS" to (prefs[stringPreferencesKey("video_fps_key")] ?: "-"),
            "SRT IP" to (prefs[stringPreferencesKey("srt_server_ip_key")] ?: "-"),
            "SRT Port" to (prefs[stringPreferencesKey("srt_server_port_key")] ?: "-"),
            "Stream ID" to (prefs[stringPreferencesKey("server_stream_id_key")] ?: "-"),
            "Bitrate" to ((prefs[intPreferencesKey("live_video_bitrate_key")]?.toString()) ?: "-")
          )
        }
        .collectLatest { rows ->
          // Clear previous content except header (index 0)
          while (table.childCount > 1) table.removeViewAt(1)
          rows.forEach { (field, value) ->
            val tr = TableRow(this@MainActivity)
            val tvField = TextView(this@MainActivity).apply {
              text = field
              setTextColor(ContextCompat.getColor(this@MainActivity, R.color.black))
              gravity = android.view.Gravity.CENTER
              background = ContextCompat.getDrawable(this@MainActivity, R.drawable.table_cell_background)
              setPadding(0, 8, 0, 8)
            }
            val tvValue = TextView(this@MainActivity).apply {
              text = value
              setTextColor(ContextCompat.getColor(this@MainActivity, R.color.black))
              gravity = android.view.Gravity.CENTER
              background = ContextCompat.getDrawable(this@MainActivity, R.drawable.table_cell_background)
              setPadding(0, 8, 0, 8)
            }
            // First column wraps, second column expands
            tvField.layoutParams = TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT)
            tvValue.layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
            tr.addView(tvField)
            tr.addView(tvValue)
            table.addView(tr)
          }
        }
    }
  }

  @Suppress("DEPRECATION")
  private fun transitionAnim(isOpen: Boolean) {
    if (Build.VERSION.SDK_INT >= VERSION_CODES.UPSIDE_DOWN_CAKE) {
      val type = if (isOpen) OVERRIDE_TRANSITION_OPEN else OVERRIDE_TRANSITION_CLOSE
      overrideActivityTransition(type, R.anim.slide_in, R.anim.slide_out)
    } else {
      overridePendingTransition(R.anim.slide_in, R.anim.slide_out)
    }
  }

  private fun requestPermissions() {
    if (!hasPermissions(this)) {
      ActivityCompat.requestPermissions(this, permissions, 1)
    }
  }

  @SuppressLint("NewApi")
  private fun createList() {
    activities.add(
      ActivityLink(
        Intent(this, RotationActivity::class.java),
        getString(R.string.rotation_rtmp), VERSION_CODES.LOLLIPOP
      )
    )
  }

  private fun setListAdapter(activities: List<ActivityLink>) {
    list.adapter = ImageAdapter(activities)
    list.onItemClickListener =
      OnItemClickListener { _, _, position, _ ->
        if (hasPermissions(this)) {
          val link = activities[position]
          val minSdk = link.minSdk
          if (Build.VERSION.SDK_INT >= minSdk) {
            // Attach deep link/session params when navigating to RotationActivity
            val params = DeepLinkParams.fromUri(intent.data)
            val matchId = params.matchId ?: intent.getStringExtra(StudioConstants.MATCH_ID_KEY)
            val refreshId = params.refreshId ?: intent.getStringExtra(StudioConstants.REFRESH_ID_KEY)
            val refreshToken = params.refreshToken ?: intent.getStringExtra(StudioConstants.REFRESH_TOKEN_KEY)
            Log.d("MainActivity_TOKENS", "Passing params -> matchId=$matchId, refreshId=$refreshId, refreshToken=${refreshToken?.let { if (it.length > 6) it.take(3)+"***"+it.takeLast(3) else it }}")
            matchId?.let { link.intent.putExtra(StudioConstants.MATCH_ID_KEY, it) }
            refreshId?.let { link.intent.putExtra(StudioConstants.REFRESH_ID_KEY, it) }
            refreshToken?.let { link.intent.putExtra(StudioConstants.REFRESH_TOKEN_KEY, it) }
            startActivity(link.intent)
            transitionAnim(false)
          } else {
            showMinSdkError(minSdk)
          }
        } else {
          showPermissionsErrorAndRequest()
        }
      }
  }

  private fun showMinSdkError(minSdk: Int) {
    val named: String = when (minSdk) {
      VERSION_CODES.JELLY_BEAN_MR2 -> "JELLY_BEAN_MR2"
      VERSION_CODES.LOLLIPOP -> "LOLLIPOP"
      else -> "JELLY_BEAN"
    }
    toast("You need min Android $named (API $minSdk)")
  }

  private fun showPermissionsErrorAndRequest() {
    toast("You need permissions before")
    requestPermissions()
  }

  private fun hasPermissions(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= VERSION_CODES.M) {
      for (permission in permissions) {
        if (ActivityCompat.checkSelfPermission(context, permission)
          != PackageManager.PERMISSION_GRANTED
        ) {
          return false
        }
      }
    }
    return true
  }

  private fun handleDeepLink(intent: Intent?) {
    val params = DeepLinkParams.fromUri(intent?.data)
    lifecycleScope.launch(Dispatchers.IO) {
      applicationContext.dataStore.edit { prefs ->
        params.resolution?.let { prefs[stringPreferencesKey("video_resolution_key")] = it }
        params.fps?.let { prefs[stringPreferencesKey("video_fps_key")] = it }
        params.ip?.let { prefs[stringPreferencesKey("srt_server_ip_key")] = it }
        params.port?.let { prefs[stringPreferencesKey("srt_server_port_key")] = it }
        params.srtStreamId?.let { prefs[stringPreferencesKey("server_stream_id_key")] = it }
        params.bitrate?.let { prefs[intPreferencesKey("live_video_bitrate_key")] = it }
      }
    }
  }
}