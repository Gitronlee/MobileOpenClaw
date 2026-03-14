package com.junwan666.openclawzh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.BufferedReader
import java.io.File

class TerminalSessionService : Service() {
 companion object {
  const val NOTIFICATION_ID = 2
  var isRunning = false
  private set
  private const val PREFS_NAME = "terminal_session"
  private const val KEY_PTY_PID = "pty_pid"

  fun start(context: Context) {
   val intent = Intent(context, TerminalSessionService::class.java)
   if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    context.startForegroundService(intent)
   } else {
    context.startService(intent)
   }
  }

  fun stop(context: Context) {
   val intent = Intent(context, TerminalSessionService::class.java)
   context.stopService(intent)
  }

  fun savePtyPid(context: Context, pid: Int) {
   val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
   prefs.edit().putInt(KEY_PTY_PID, pid).apply()
  }

  fun getPtyPid(context: Context): Int {
   val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
   return prefs.getInt(KEY_PTY_PID, -1)
  }

  fun isPtyProcessAlive(context: Context): Boolean {
   val pid = getPtyPid(context)
   if (pid <= 0) return false
   return isProcessAlive(pid)
  }

  fun clearPtyPid(context: Context) {
   val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
   prefs.edit().remove(KEY_PTY_PID).apply()
  }

  private fun isProcessAlive(pid: Int): Boolean {
   return try {
    val statusFile = File("/proc/$pid/status")
    if (statusFile.exists()) {
     BufferedReader(statusFile.reader()).use { reader ->
      val stateLine = reader.lineSequence().find { it.startsWith("State:") }
      stateLine?.let {
       val state = it.substringAfter("State:").trim().firstOrNull()
       // 状态为 R(running), S(sleeping), D(device sleep) 表示存活
       state in listOf('R', 'S', 'D')
      } ?: false
     }
    } else {
     false
    }
   } catch (_: Exception) {
    false
   }
  }
 }

 private var wakeLock: PowerManager.WakeLock? = null

 override fun onBind(intent: Intent?): IBinder? = null

 override fun onCreate() {
  super.onCreate()
  createNotificationChannel()
 }

 override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
  startForeground(NOTIFICATION_ID, buildNotification())
  if (isRunning) {
   return START_STICKY
  }
  isRunning = true
  acquireWakeLock()
  return START_STICKY
 }

 override fun onDestroy() {
  isRunning = false
  releaseWakeLock()
  super.onDestroy()
 }

 private fun acquireWakeLock() {
  releaseWakeLock()
  val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
  wakeLock = powerManager.newWakeLock(
   PowerManager.PARTIAL_WAKE_LOCK,
   "OpenClaw::TerminalWakeLock"
  )
  wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 hours max
 }

 private fun releaseWakeLock() {
  wakeLock?.let {
   if (it.isHeld) it.release()
  }
  wakeLock = null
 }

 private fun createNotificationChannel() {
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
   val channel = NotificationChannel(
    GatewayService.CHANNEL_ID,
    "OpenClaw Gateway",
    NotificationManager.IMPORTANCE_LOW
   ).apply {
    description = "Keeps the OpenClaw gateway running in the background"
   }
   val manager = getSystemService(NotificationManager::class.java)
   manager.createNotificationChannel(channel)
  }
 }

 private fun buildNotification(): Notification {
  val intent = Intent(this, MainActivity::class.java)
  val pendingIntent = PendingIntent.getActivity(
   this, 0, intent,
   PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
  )

  return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
   Notification.Builder(this, GatewayService.CHANNEL_ID)
    .setContentTitle("OpenClaw Terminal")
    .setContentText("Terminal session active")
    .setSmallIcon(android.R.drawable.ic_menu_manage)
    .setContentIntent(pendingIntent)
    .setOngoing(true)
    .build()
  } else {
   @Suppress("DEPRECATION")
   Notification.Builder(this)
    .setContentTitle("OpenClaw Terminal")
    .setContentText("Terminal session active")
    .setSmallIcon(android.R.drawable.ic_menu_manage)
    .setContentIntent(pendingIntent)
    .setOngoing(true)
    .build()
  }
 }
}