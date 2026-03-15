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
import android.system.Os
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter

class TerminalSessionService : Service() {
 companion object {
  const val NOTIFICATION_ID = 2
  var isRunning = false
  private set
  private const val PREFS_NAME = "terminal_session"
  private const val KEY_PTY_PID = "pty_pid"
  private const val PTY_MASTER = "master"

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
 private var ptyProcess: Process? = null
 private var outputReaderThread: Thread? = null
 private var inputWriter: PrintWriter? = null
 private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

 // Event channel for PTY output
 var outputSink: ((String) -> Unit)? = null

 override fun onBind(intent: Intent?): IBinder? = null

 override fun onCreate() {
  super.onCreate()
  createNotificationChannel()
 }

 override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
  startForeground(NOTIFICATION_ID, buildNotification())
  if (isRunning) {
   // Already running, just ensure process is started
   ensurePtyProcessRunning()
   return START_STICKY
  }
  isRunning = true
  acquireWakeLock()
  ensurePtyProcessRunning()
  return START_STICKY
 }

 private fun ensurePtyProcessRunning() {
  if (ptyProcess != null) return

  try {
   val filesDir = filesDir.absolutePath
   val nativeLibDir = applicationInfo.nativeLibraryDir
   val rootfsDir = "$filesDir/rootfs/ubuntu"
   val configDir = "$filesDir/config"
   val procFakes = "$configDir/proc_fakes"
   val sysFakes = "$configDir/sys_fakes"
   val prootPath = "$nativeLibDir/libproot.so"

   // Build proot command for interactive shell
   val machine = "aarch64" // Hardcode for arm64 devices (most common Android)
        val kernelRelease = "\\Linux\\localhost\\6.17.0-PRoot-Distro\\#1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000\\$machine\\localdomain\\-1\\"

   val cmd = mutableListOf(
    prootPath,
    "--change-id=0:0",
    "--sysvipc",
    "--kernel-release=$kernelRelease",
    "--link2symlink",
    "-L",
    "--kill-on-exit",
    "--rootfs=$rootfsDir",
    "--cwd=/root",
    "--bind=/dev",
    "--bind=/dev/urandom:/dev/random",
    "--bind=/proc",
    "--bind=/proc/self/fd:/dev/fd",
    "--bind=/proc/self/fd/0:/dev/stdin",
    "--bind=/proc/self/fd/1:/dev/stdout",
    "--bind=/proc/self/fd/2:/dev/stderr",
    "--bind=/sys",
    "--bind=$procFakes/loadavg:/proc/loadavg",
    "--bind=$procFakes/stat:/proc/stat",
    "--bind=$procFakes/uptime:/proc/uptime",
    "--bind=$procFakes/version:/proc/version",
    "--bind=$procFakes/vmstat:/proc/vmstat",
    "--bind=$procFakes/cap_last_cap:/proc/sys/kernel/cap_last_cap",
    "--bind=$procFakes/max_user_watches:/proc/sys/fs/inotify/max_user_watches",
    "--bind=$procFakes/fips_enabled:/proc/sys/crypto/fips_enabled",
    "--bind=$rootfsDir/tmp:/dev/shm",
    "--bind=$sysFakes/empty:/sys/fs/selinux",
    "--bind=$configDir/resolv.conf:/etc/resolv.conf",
    "--bind=$filesDir/home:/root/home",
    // Shared memory
    "--bind=$rootfsDir/tmp:/dev/shm",
    // Guest environment
    "/usr/bin/env", "-i",
    "HOME=/root",
    "USER=root",
    "LANG=C.UTF-8",
    "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
    "TERM=xterm-256color",
    "TMPDIR=/tmp",
    "NODE_OPTIONS=--require /root/.openclaw/bionic-bypass.js",
    "/bin/bash", "-l"
   )

   // Check for storage permission
   val hasStorage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    android.os.Environment.isExternalStorageManager()
   } else {
    ContextCompat.checkSelfPermission(
     this,
     android.Manifest.permission.READ_EXTERNAL_STORAGE
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
   }

   if (hasStorage) {
    cmd.addAll(listOf(
     "--bind=/storage:/storage",
     "--bind=/storage/emulated/0:/sdcard"
    ))
   }

   val env = mapOf(
    "PROOT_TMP_DIR" to "$filesDir/tmp",
    "PROOT_LOADER" to "$nativeLibDir/libprootloader.so",
    "PROOT_LOADER_32" to "$nativeLibDir/libprootloader32.so",
    "LD_LIBRARY_PATH" to "$filesDir/lib:$nativeLibDir"
   )

   val pb = ProcessBuilder(cmd)
   pb.environment().clear()
   pb.environment().putAll(env)
   pb.redirectErrorStream(false)

   ptyProcess = pb.start()
   val pid = getProcessPid(ptyProcess!!).toInt()
   savePtyPid(this, pid)

   // Start reading output
   startOutputReader()

   // Keep input stream open
   val inputStream = ptyProcess!!.inputStream
   val outputStream = ptyProcess!!.outputStream
   inputWriter = PrintWriter(OutputStreamWriter(outputStream, "UTF-8"), true)

  } catch (e: Exception) {
   outputSink?.invoke("\r\n[Failed to start terminal: ${e.message}]\r\n")
  }
 }

 private fun startOutputReader() {
  outputReaderThread = Thread {
   try {
 Thread.currentThread().name = "PTYOutputReader"
    val reader = BufferedReader(InputStreamReader(ptyProcess!!.inputStream, "UTF-8"))
    var line: String?
    while (reader.readLine().also { line = it } != null) {
     line?.let {
      outputSink?.invoke("$it\r\n")
     }
    }
   } catch (_: Exception) {
    // Process exited or error
   }
 }
 outputReaderThread?.start()

  // Also read stderr
  Thread {
   try {
    val reader = BufferedReader(InputStreamReader(ptyProcess!!.errorStream, "UTF-8"))
    var line: String?
    while (reader.readLine().also { line = it } != null) {
     line?.let {
      outputSink?.invoke("$it\r\n")
     }
    }
   } catch (_: Exception) {}
  }.start()
 }

 fun writeToPty(data: String) {
  inputWriter?.print(data)
  inputWriter?.flush()
 }

 fun resizePty(columns: Int, rows: Int) {
  // PTY resize requires TIOCSWINSZ ioctl, which needs native code
  // For now, we send a signal to update terminal size
  ptyProcess?.let {
   try {
    // Try using stty command in the shell to resize
    val pid = getProcessPid(it)
    Runtime.getRuntime().exec("kill -WINCH $pid")
   } catch (_: Exception) {}
  }
 }

 override fun onDestroy() {
  isRunning = false
  releaseWakeLock()

  // Kill PTY process
  ptyProcess?.destroy()
  ptyProcess = null
  clearPtyPid(this)

  outputReaderThread?.interrupt()
  outputReaderThread = null
  inputWriter?.close()
  inputWriter = null

  super.onDestroy()
 }

 private fun acquireWakeLock() {
  releaseWakeLock()
  val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
  wakeLock = powerManager.newWakeLock(
   PowerManager.PARTIAL_WAKE_LOCK,
   "OpenClaw::TerminalWakeLock"
  )
  wakeLock?.acquire(24 * 60 * 60 * 1000L)
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
    description = "Keeps the OpenClaw terminal session active"
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

    private fun getProcessPid(process: Process): Long {
        return try {
            val pidField = process::class.java.getDeclaredField("pid")
            pidField.isAccessible = true
            pidField.getLong(process)
        } catch (_: Exception) {
            -1L
        }
    }
}
