package dev.bmcreations.scrcast

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.multi.CompositeMultiplePermissionsListener
import com.karumi.dexter.listener.multi.DialogOnAnyDeniedMultiplePermissionsListener
import com.karumi.dexter.listener.multi.MultiplePermissionsListener
import dev.bmcreations.dispatcher.ActivityResult
import dev.bmcreations.scrcast.config.Options
import dev.bmcreations.scrcast.config.OptionsBuilder
import dev.bmcreations.scrcast.extensions.supportsPauseResume
import dev.bmcreations.scrcast.recorder.*
import dev.bmcreations.scrcast.recorder.RecordingState.*
import dev.bmcreations.scrcast.recorder.RecordingStateChangeCallback
import dev.bmcreations.scrcast.recorder.notification.NotificationProvider
import dev.bmcreations.scrcast.recorder.notification.RecorderNotificationProvider
import dev.bmcreations.scrcast.recorder.service.RecorderService
import dev.bmcreations.scrcast.request.MediaProjectionRequest
import dev.bmcreations.scrcast.request.MediaProjectionResult
import java.io.File

/**
 * Main Interface for accessing [scrcast] Library
 */
class ScrCast private constructor(private val activity: Activity) {

    var state: RecordingState = Idle
        set(value) {
            val was = field
            field = value
            onStateChange?.invoke(value)
            if (was == Recording && value == Idle) {
                try {
                    broadcaster.unregisterReceiver(recordingStateHandler)
                } catch (swallow: Exception) { }
            }
        }

    val isRecording: Boolean get() = state == Recording
    val isIdle: Boolean get() = state == Idle
    val isInStartDelay: Boolean get() = state is Delay

    private val defaultNotificationProvider by lazy {
        RecorderNotificationProvider(activity, options)
    }
    private var notificationProvider: NotificationProvider? = null

    private var onStateChange: RecordingStateChangeCallback? = null

    private val metrics by lazy {
        DisplayMetrics().apply { activity.windowManager.defaultDisplay.getMetrics(this) }
    }

    private val dpi by lazy { metrics.density }

    private var options = Options()

    private var serviceBinder: RecorderService? = null

    private val broadcaster = LocalBroadcastManager.getInstance(activity)

    private val recordingStateHandler = object : BroadcastReceiver() {
        override fun onReceive(p0: Context?, p1: Intent?) {
            p1?.action?.let { action ->
                when(action) {
                    STATE_RECORDING -> state = Recording
                    STATE_IDLE -> state = Idle
                    STATE_DELAY -> {
                        state = Delay(p1.extras?.getInt(EXTRA_DELAY_REMAINING) ?: 0)
                    }
                    STATE_PAUSED -> state = Paused
                    ACTION_STOP -> {
                        activity.unbindService(connection)
                        scanForOutputFile()
                    }
                }
            }
        }
    }

    private var _outputFile: File? = null

    val outputDirectory: File?
        get() {
            val mediaStorageDir = options.storage.mediaStorageLocation
            mediaStorageDir.apply {
                if (!exists()) {
                    if (!mkdirs()) {
                        Log.d("scrcast", "failed to create designated output directory")
                        return null
                    }
                }
            }

            return mediaStorageDir
        }

    private val outputFile: File?
        get() {
            if (_outputFile == null) {
                outputDirectory?.let { dir ->
                    _outputFile = File("${dir.path}${File.separator}${options.storage.fileNameFormatter}.mp4")
                } ?: return null
            }
            return _outputFile
        }


    private val projectionManager: MediaProjectionManager by lazy {
        activity.getSystemService(MediaProjectionManager::class.java)
    }

    private val permissionListener = object : MultiplePermissionsListener {
        override fun onPermissionsChecked(p0: MultiplePermissionsReport?) {
            startRecording()
        }

        override fun onPermissionRationaleShouldBeShown(
            p0: MutableList<PermissionRequest>?,
            p1: PermissionToken?
        ) {
            p1?.continuePermissionRequest()
        }
    }

    private fun startRecording() {
        MediaProjectionRequest(
            activity,
            projectionManager
        ).start(object : MediaProjectionResult {
            override fun onCancel() = Unit
            override fun onFailure(error: Throwable) = Unit

            override fun onSuccess(result: ActivityResult?) {
                if (result != null) {
                    if (options.moveTaskToBack) activity.moveTaskToBack(true)
                    val output = outputFile
                    if (output != null) {
                        startService(result, output)
                    }
                }
            }
        })
    }

    @JvmSynthetic
    fun options(opts: OptionsBuilder.() -> Unit) {
        options = handleDynamicVideoSize(OptionsBuilder().apply(opts).build())
    }

    fun updateOptions(options: Options) {
        this.options = handleDynamicVideoSize(options)
    }

    private fun handleDynamicVideoSize(options: Options): Options {
        var reconfig: Options = options
        if (options.video.width == -1) {
            reconfig = reconfig.copy(video = reconfig.video.copy(width = metrics.widthPixels))
        }
        if (options.video.height == -1) {
            reconfig = reconfig.copy(video = reconfig.video.copy(height = metrics.heightPixels))
        }
        return reconfig
    }

    fun setOnStateChangeListener(listener : OnRecordingStateChange) {
        onStateChange = { listener.onStateChange(it) }
    }

    @JvmSynthetic
    fun setOnStateChangeListener(callback: (RecordingState) -> Unit) {
        onStateChange = callback
    }

    /**
     * Convenience method for clients to easily check if the required permissions are enabled for storage
     * Even though we internally will bubble up the permission request and handle the allow/deny,
     * some clients may want to onboard users via an OOBE or some UX state involving previously recorded files.
     */
    fun hasStoragePermissions(): Boolean {
        val perms = listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)
        return perms.all { ActivityCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED }
    }

    fun setNotificationProvider(provider: NotificationProvider) {
        notificationProvider = provider
    }

    /**
     *
     */
    fun record() {
        when (state) {
            Idle -> {
                Dexter.withContext(activity)
                    .withPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)
                    .withListener(CompositeMultiplePermissionsListener(permissionListener, dialogPermissionListener))
                    .check()
            }
            Paused -> resume()
            Recording -> stopRecording()
            is Delay -> { /* Prevent erroneous calls to record while in start delay */}
        }
    }

    fun stopRecording() {
        broadcaster.sendBroadcast(Intent(Action.Stop.name))
    }

    fun pause() {
        if (supportsPauseResume) {
            if (state.isRecording) {
                broadcaster.sendBroadcast(Intent(Action.Pause.name))
            }
        }
    }

    fun resume() {
        if (supportsPauseResume) {
            if (state.isPaused) {
                broadcaster.sendBroadcast(Intent(Action.Resume.name))
            } else {
                record()
            }
        }
    }

    private fun scanForOutputFile() {
        MediaScannerConnection.scanFile(activity, arrayOf(outputFile.toString()), null) { path, uri ->
            Log.i("scrcast", "scanned: $path")
            Log.i("scrcast", "-> uri=$uri")
            _outputFile = null
        }
    }

    private fun startService(result: ActivityResult, file : File) {
        val service = Intent(activity, RecorderService::class.java).apply {
            putExtra("code", result.resultCode)
            putExtra("data", result.data)
            putExtra("options", options)
            putExtra("outputFile", file.absolutePath)
            putExtra("dpi", dpi)
            putExtra("rotation", activity.windowManager.defaultDisplay.rotation)
        }

        broadcaster.registerReceiver(
            recordingStateHandler,
            IntentFilter().apply {
                addAction(STATE_IDLE)
                addAction(STATE_RECORDING)
                addAction(STATE_PAUSED)
                addAction(STATE_DELAY)
                addAction(ACTION_STOP)
            }
        )

        activity.bindService(service, connection, Context.BIND_AUTO_CREATE)
        activity.startService(service)

    }

    /** Defines callbacks for service binding, passed to bindService()  */
    private val connection = object : ServiceConnection {

        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            val binder = service as RecorderService.LocalBinder
            serviceBinder = binder.service
            serviceBinder?.setNotificationProvider(notificationProvider ?: defaultNotificationProvider)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            serviceBinder = null
        }
    }

    private val dialogPermissionListener: DialogOnAnyDeniedMultiplePermissionsListener = DialogOnAnyDeniedMultiplePermissionsListener.Builder
        .withContext(activity)
        .withTitle("Storage permissions")
        .withMessage("Storage permissions are needed to store the screen recording")
        .withButtonText(android.R.string.ok)
        .withIcon(R.drawable.ic_storage_permission_dialog)
        .build()

    companion object {
        @JvmStatic
        fun use(activity: Activity): ScrCast {
            return ScrCast(activity)
        }
    }
}
