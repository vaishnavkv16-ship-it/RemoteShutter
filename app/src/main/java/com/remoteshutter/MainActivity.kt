package com.remoteshutter

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * One app, two roles:
 *  - CAMERA phone (on tripod): runs the camera, streams a small preview, takes photos on command.
 *  - REMOTE phone (in hand): shows the live preview, has a shutter button.
 *
 * Messages (byte[0] = type): 0 = JPEG preview frame, 1 = capture command, 2 = status text.
 */
class MainActivity : ComponentActivity() {

    private val serviceId = "com.remoteshutter.app"
    private val strategy = Strategy.P2P_POINT_TO_POINT
    private lateinit var conn: ConnectionsClient
    private lateinit var root: FrameLayout
    private val exec = Executors.newSingleThreadExecutor()

    private var endpointId: String? = null
    private var isCamera = false
    private var imageCapture: ImageCapture? = null
    private var previewImage: ImageView? = null
    private var statusView: TextView? = null
    private var lastSent = 0L

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res.values.all { it }) startRole() else toast("Permissions are required")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        conn = Nearby.getConnectionsClient(this)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        showChooser()
    }

    // ---------- UI ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    private fun setStatus(s: String) = runOnUiThread { statusView?.text = s }

    private fun makeStatus(): TextView = TextView(this).apply {
        setTextColor(Color.WHITE)
        setBackgroundColor(0x88000000.toInt())
        setPadding(dp(12), dp(8), dp(12), dp(8))
        textSize = 14f
        layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = dp(40) }
    }

    private fun showChooser() {
        root.removeAllViews()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), 0)
        }
        val title = TextView(this).apply {
            text = "Remote Shutter"; textSize = 26f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val b1 = Button(this).apply {
            text = "This phone = CAMERA (on tripod)"
            setOnClickListener { isCamera = true; ensurePerms() }
        }
        val b2 = Button(this).apply {
            text = "This phone = REMOTE (in my hand)"
            setOnClickListener { isCamera = false; ensurePerms() }
        }
        col.addView(title); col.addView(b1); col.addView(b2)
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
    }

    private fun ensurePerms() {
        val l = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 31) {
            l += Manifest.permission.BLUETOOTH_SCAN
            l += Manifest.permission.BLUETOOTH_ADVERTISE
            l += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT <= 32) l += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.NEARBY_WIFI_DEVICES
        if (Build.VERSION.SDK_INT <= 28) l += Manifest.permission.WRITE_EXTERNAL_STORAGE
        val missing = l.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startRole() else permLauncher.launch(missing.toTypedArray())
    }

    private fun startRole() {
        root.removeAllViews()
        if (isCamera) startCameraRole() else startRemoteRole()
    }

    // ---------- CAMERA role ----------

    private fun startCameraRole() {
        val pv = PreviewView(this)
        root.addView(pv, FrameLayout.LayoutParams(-1, -1))
        statusView = makeStatus().also { root.addView(it) }
        setStatus("Waiting for remote…")

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(exec) { sendFrame(it) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis)
        }, ContextCompat.getMainExecutor(this))

        conn.startAdvertising(
            Build.MODEL, serviceId, connCb,
            AdvertisingOptions.Builder().setStrategy(strategy).build()
        ).addOnFailureListener { setStatus("Advertising failed: ${it.message}") }
    }

    private fun sendFrame(img: ImageProxy) {
        val id = endpointId
        val now = SystemClock.elapsedRealtime()
        if (id == null || now - lastSent < 100) { img.close(); return } // ~10 fps
        lastSent = now
        try {
            val bmp = img.toBitmap()
            val m = Matrix().apply { postRotate(img.imageInfo.rotationDegrees.toFloat()) }
            val rot = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            val s = 400f / max(rot.width, rot.height)
            val small = Bitmap.createScaledBitmap(rot, (rot.width * s).toInt(), (rot.height * s).toInt(), true)
            for (q in intArrayOf(40, 25, 15)) {
                val out = ByteArrayOutputStream()
                out.write(0)
                small.compress(Bitmap.CompressFormat.JPEG, q, out)
                val bytes = out.toByteArray()
                if (bytes.size <= ConnectionsClient.MAX_BYTES_DATA_SIZE) {
                    conn.sendPayload(id, Payload.fromBytes(bytes)); break
                }
            }
        } finally { img.close() }
    }

    private fun takePhoto() {
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_${System.currentTimeMillis()}")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RemoteShutter")
        }
        val opts = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv).build()
        imageCapture?.takePicture(opts, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    toast("Photo saved"); sendText("Photo saved ✔")
                }
                override fun onError(e: ImageCaptureException) {
                    toast("Capture failed"); sendText("Capture failed: ${e.message}")
                }
            })
    }

    private fun sendText(t: String) {
        endpointId?.let { conn.sendPayload(it, Payload.fromBytes(byteArrayOf(2) + t.toByteArray())) }
    }

    // ---------- REMOTE role ----------

    private fun startRemoteRole() {
        previewImage = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(previewImage, FrameLayout.LayoutParams(-1, -1))
        statusView = makeStatus().also { root.addView(it) }
        setStatus("Searching for camera phone…")

        val shutter = Button(this).apply {
            text = "● CLICK"; textSize = 20f
            setOnClickListener {
                val id = endpointId
                if (id == null) toast("Not connected")
                else conn.sendPayload(id, Payload.fromBytes(byteArrayOf(1)))
            }
            layoutParams = FrameLayout.LayoutParams(dp(200), dp(72), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = dp(32) }
        }
        root.addView(shutter)

        conn.startDiscovery(
            serviceId, discCb,
            DiscoveryOptions.Builder().setStrategy(strategy).build()
        ).addOnFailureListener { setStatus("Discovery failed: ${it.message}") }
    }

    private val discCb = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            setStatus("Found ${info.endpointName}, connecting…")
            conn.requestConnection(Build.MODEL, id, connCb)
        }
        override fun onEndpointLost(id: String) {}
    }

    // ---------- Shared connection callbacks ----------

    private val connCb = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            conn.acceptConnection(id, payloadCb)
        }
        override fun onConnectionResult(id: String, r: ConnectionResolution) {
            if (r.status.isSuccess) {
                endpointId = id
                conn.stopAdvertising(); conn.stopDiscovery()
                setStatus("Connected")
            } else setStatus("Connection failed, restart app")
        }
        override fun onDisconnected(id: String) {
            endpointId = null; setStatus("Disconnected")
        }
    }

    private val payloadCb = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, p: Payload) {
            val b = p.asBytes() ?: return
            if (b.isEmpty()) return
            when (b[0].toInt()) {
                0 -> if (!isCamera) {
                    val bmp = BitmapFactory.decodeByteArray(b, 1, b.size - 1) ?: return
                    runOnUiThread { previewImage?.setImageBitmap(bmp) }
                }
                1 -> if (isCamera) runOnUiThread { takePhoto() }
                2 -> toast(String(b, 1, b.size - 1))
            }
        }
        override fun onPayloadTransferUpdate(id: String, u: PayloadTransferUpdate) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        conn.stopAdvertising(); conn.stopDiscovery(); conn.stopAllEndpoints()
        exec.shutdown()
    }
}
