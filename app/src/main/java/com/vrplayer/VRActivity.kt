package com.vrplayer

import android.app.NativeActivity
import android.content.Context
import android.os.Bundle
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

import android.hardware.display.DisplayManager
import android.view.Surface
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.content.Intent
import android.net.Uri

class VRActivity : NativeActivity() {
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var presentation: VRPresentation? = null
    
    private var controlsVirtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var controlsPresentation: VRControlsPresentation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
        } else {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 100)
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        presentation?.loadFiles()
    }

    companion object {
        init {
            System.loadLibrary("vrplayer_native")
        }

        private const val PICK_VIDEO_REQUEST_CODE = 1001
        
        @JvmStatic
        fun openFilePicker(activity: VRActivity) {
            activity.runOnUiThread {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "video/*"
                }
                activity.startActivityForResult(intent, PICK_VIDEO_REQUEST_CODE)
            }
        }

        @JvmStatic
        fun setupVirtualDisplay(activity: VRActivity, surface: Surface, width: Int, height: Int) {
            activity.runOnUiThread {
                val displayManager = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                activity.virtualDisplay = displayManager.createVirtualDisplay(
                    "VR_UI_Display",
                    width, height, 160,
                    surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                )
                
                activity.virtualDisplay?.display?.let { display ->
                    activity.presentation = VRPresentation(activity, display) { filePath ->
                        activity.playFile(filePath)
                    }
                    activity.presentation?.show()
                }
            }
        }

        private var lastDownTime: Long = 0

        @JvmStatic
        fun dispatchVRTouch(activity: VRActivity, x: Float, y: Float, action: Int) {
            activity.runOnUiThread {
                val now = android.os.SystemClock.uptimeMillis()
                if (action == android.view.MotionEvent.ACTION_DOWN) {
                    lastDownTime = now
                }
                
                val downTime = if (lastDownTime == 0L) now else lastDownTime
                val event = android.view.MotionEvent.obtain(
                    downTime,
                    now,
                    action,
                    x * 1024f,
                    y * 1024f,
                    0
                )
                
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                
                if (action == 7) { // ACTION_HOVER_MOVE
                    activity.presentation?.dispatchGenericMotionEvent(event)
                } else {
                    activity.presentation?.dispatchTouchEvent(event)
                }
                
                event.recycle()
                
                if (action == android.view.MotionEvent.ACTION_UP) {
                    lastDownTime = 0L
                }
            }
        }

        @JvmStatic
        fun setupControlsVirtualDisplay(activity: VRActivity, surface: Surface, width: Int, height: Int) {
            activity.runOnUiThread {
                val displayManager = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                activity.controlsVirtualDisplay = displayManager.createVirtualDisplay(
                    "VR_Controls_Display",
                    width, height, 160,
                    surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                )
                
                activity.controlsVirtualDisplay?.display?.let { display ->
                    activity.controlsPresentation = VRControlsPresentation(activity, display) {
                        activity.nativeTogglePlayPause()
                    }
                    activity.controlsPresentation?.show()
                }
            }
        }

        private var lastControlsDownTime: Long = 0

        @JvmStatic
        fun dispatchControlsVRTouch(activity: VRActivity, x: Float, y: Float, action: Int) {
            activity.runOnUiThread {
                val now = android.os.SystemClock.uptimeMillis()
                if (action == android.view.MotionEvent.ACTION_DOWN) {
                    lastControlsDownTime = now
                }
                
                val downTime = if (lastControlsDownTime == 0L) now else lastControlsDownTime
                val event = android.view.MotionEvent.obtain(
                    downTime,
                    now,
                    action,
                    x * 1024f, // width
                    y * 256f,  // height
                    0
                )
                
                if (action == 7) {
                    activity.controlsPresentation?.dispatchGenericMotionEvent(event)
                } else {
                    activity.controlsPresentation?.dispatchTouchEvent(event)
                }
                
                event.recycle()
                
                if (action == android.view.MotionEvent.ACTION_UP) {
                    lastControlsDownTime = 0L
                }
            }
        }

        @JvmStatic
        fun updateMediaProgress(activity: VRActivity, currentSec: Float, totalSec: Float) {
            activity.runOnUiThread {
                activity.controlsPresentation?.updateProgress(currentSec, totalSec)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_VIDEO_REQUEST_CODE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                processVideoUri(uri)
            }
        }
    }

    fun playFile(filePath: String) {
        nativePlayVideo(filePath)
    }

    private fun processVideoUri(uri: Uri) {
        try {
            val pfd = contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                val fd = pfd.detachFd()
                val fdPath = "/proc/self/fd/$fd"
                nativePlayVideo(fdPath)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    external fun nativePlayVideo(path: String)
    external fun nativeTogglePlayPause()
    external fun nativeSeekVideo(positionSeconds: Float)
    external fun nativeSetVolume(volume: Float)
    external fun nativeSetSpeed(speed: Float)
    external fun nativeCycleAudioTrack()
}
