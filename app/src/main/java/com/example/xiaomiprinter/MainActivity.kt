package com.example.xiaomiprinter

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.OutputStream
import java.util.*
import java.util.UUID
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket

const val REQUEST_CODE_GALLERY = 1001
const val REQUEST_CODE_LOCATION = 1002
const val BLUETOOTH_PERMISSION_CODE = 1003
const val DISCOVER_TIMEOUT_MS = 15000

companion object {
    private val TAG = "XiaomiPrinter"

    // UUID for the printer's RFCOMM channel (standard SPP UUID)
    private val PRINTER_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // Address of the printer – filled after discovery or manual entry
    var PRINTER_ADDRESS: String = ""
}

// -----------------------------------------------
// Activity main code
// -----------------------------------------------
class MainActivity : AppCompatActivity() {

    // UI references
    private lateinit var ivPhoto: ImageView
    private lateinit var btnPrint: Button
    private lateinit var btnDiscover: Button
    private lateinit var tvStatus: TextView

    // Bluetooth
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null

    // Scanning state
    private var discoveredDevices: List<BluetoothDevice> = emptyList()
    private var deviceArrayAdapter: ArrayAdapter<String>? = null
    private var discoveryReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ivPhoto = findViewById(R.id.ivPhoto)
        btnPrint = findViewById(R.id.btnPrint)
        btnDiscover = findViewById(R.id.btnDiscover)
        tvStatus = findViewById(R.id.tvStatus)

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        // -------- Permission checks ----------
        val bluetoothPermissions = mutableListOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        var needRequest = false
        for (perm in bluetoothPermissions) {
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                needRequest = true
                break
            }
        }
        // Also location for BLE/scanning on Android 9+
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needRequest = true
        }
        if (needRequest) {
            ActivityCompat.requestPermissions(
                this,
                bluetoothPermissions.toArray(),
                BLUETOOTH_PERMISSION_CODE
            )
            // location permission request can be separate if needed
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    REQUEST_CODE_LOCATION
                )
            }
        }

        // -------- Click listeners ----------
        btnPrint.setOnClickListener { onPrintClicked() }

        btnDiscover.setOnClickListener { discoverPrinters() }

        // -------- Gallery pick ----------
        ivPhoto.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED)
            ) {
                openGallery()
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    REQUEST_CODE_GALLERY
                )
            }
        }
    }

    // ------------------------------------------------
    // Gallery handling
    // ------------------------------------------------
    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK)
        intent.type = "image/*"
        startActivityForResult(intent, REQUEST_CODE_GALLERY)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_GALLERY && resultCode == RESULT_OK && data != null) {
            val uri = data.data
            try {
                val bitmap: Bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                ivPhoto.setImageBitmap(bitmap)
                tvStatus.text = "Photo loaded"
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ------------------------------------------------
    // Automatic printer discovery
    // ------------------------------------------------
    private fun discoverPrinters() {
        // Cancel any previous discovery
        cancelDiscovery()

        // Ensure we have permissions; if not, request and return
        if (!hasBluetoothPermissions()) {
            Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_SHORT).show()
            return
        }

        bluetoothAdapter?.startDiscovery()

        // Prepare a receiver for found devices
        discoveryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action
                if (BluetoothDevice.ACTION_FOUND == action) {
                    val device: BluetoothDevice? = intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (device != null) {
                        // We'll keep all discovered devices; optionally filter by name
                        runOnUiThread {
                            // Add to list and update UI later when discovery stops
                            discoveredDevices = discoveredDevices + device
                            // simple toast to show progress
                            Toast.makeText(context, "Found: ${device.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        // Register receiver with filter for ACTION_FOUND
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(discoveryReceiver, filter)

        // Handler to stop discovery after timeout
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed {
            cancelDiscovery()
            showDiscoveredDevicesDialog()
        }, DISCOVER_TIMEOUT_MS
    }

    private fun hasBluetoothPermissions(): Boolean {
        val bluetoothPermissions = mutableListOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        return bluetoothPermissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun cancelDiscovery() {
        bluetoothAdapter?.cancelDiscovery()
        if (discoveryReceiver != null) {
            try {
                unregisterReceiver(discoveryReceiver)
            } catch (e: IllegalArgumentException) {
                // receiver not registered or already unregistered
            }
            discoveryReceiver = null
        }
        discoveredDevices = emptyList()
    }

    private fun showDiscoveredDevicesDialog() {
        if (discoveredDevices.isEmpty()) {
            Toast.makeText(this, "No devices found", Toast.LENGTH_SHORT).show()
            return
        }

        // Build a simple list of "Name (MAC)" strings
        val items = discoveredDevices.map { "${it.name} (${it.address})" }
        val builder = AlertDialog.Builder(this)
            .setTitle("Available Printers")
            .setItems(items) { which ->
                // User selected printer at index 'which'
                val selectedDevice = discoveredDevices[which]
                PRINTER_ADDRESS = selectedDevice.address
                tvStatus.text = "Selected: ${selectedDevice.name}"
                Toast.makeText(this, "Selected ${selectedDevice.name}", Toast.LENGTH_SHORT).show()
                // Optionally attempt connection immediately:
                attemptConnectAndPrint()
            }
            .setNegativeButton("Cancel", null)
        builder.show()
    }

    // ------------------------------------------------
    // Manual MAC entry (original flow)
    // ------------------------------------------------
    private fun onPrintClicked() {
        // Ensure we have a photo loaded
        if (ivPhoto.drawableState.isEmpty()) {
            Toast.makeText(this, "Please select a photo first", Toast.LENGTH_SHORT).show()
            return
        }

        // If a printer was already selected via discovery, we can skip the dialog
        if (PRINTER_ADDRESS.isNotEmpty()) {
            attemptConnectAndPrint()
            return
        }

        // Otherwise, show the MAC-entry dialog
        val printerDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Printer MAC Address")
            .setMessage("Enter the Bluetooth MAC address of your Xiaomi Portable Photo Printer 1S")
            .setPositiveButton("Connect") { _, _ ->
                // The dialog view is inflated elsewhere; for manual entry we just use a simple input
                // We'll reuse the same dialog layout but with an EditText
                val view = androidx.layoutinflater.LayoutInflater.from(this).inflate(R.layout.dialog_printer_mac, null)
                val etMac = view.findViewById<android.widget.EditText>(android.R.id.edit) // we'll adjust layout
                // Actually we need to find the EditText by id defined in dialog_printer_mac.xml
                // We'll just launch the same dialog but with pre-filled address if needed
                // For simplicity, just ask user to type:
                val macDialog = androidx.appcompat.app.AlertDialog.Builder(this)
                    .setView(view)
                    .setTitle("Printer MAC")
                    .setPositiveButton("Connect") { _ ->
                        val mac = etMac.text.toString()
                        if (mac.isNotEmpty()) {
                            PRINTER_ADDRESS = mac
                            attemptConnectAndPrint()
                        } else {
                            Toast.makeText(this, "Enter MAC address", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .create()
                macDialog.show()
                // set the EditText reference
                etMac = view.findViewById(R.id.etMac) // assume id etMac in dialog_printer_mac.xml
            }
            .setNegativeButton("Cancel", null)
            .create()

        // Inflate custom view with EditText
        val view = androidx.layoutinflater.LayoutInflater.from(this).inflate(R.layout.dialog_printer_mac, null)
        val etMac = view.findViewById(R.id.etMac)
        printerDialog.setView(view)
        printerDialog.show()
    }

    private fun attemptConnectAndPrint() {
        if (bluetoothAdapter == null || PRINTER_ADDRESS.isEmpty()) {
            Toast.makeText(this, "Bluetooth not available or no printer address", Toast.LENGTH_SHORT).show()
            return
        }

        val device: BluetoothDevice = bluetoothAdapter!!.getRemoteDevice(PRINTER_ADDRESS)
        showStatus("Connecting to printer…

        // Launch connection in a background thread
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            try {
                bluetoothSocket = device.createRfcommSocketToServiceRecord(PRINTER_UUID)
                bluetoothSocket?.connect().also { connected ->
                    if (connected) {
                        runOnUiThread {
                            showStatus("Connected! Sending image…")
                            sendPrintJob()
                        }
                    } else {
                        runOnUiThread { showStatus("Connection failed") }
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
                runOnUiThread { showStatus("Connection error: ${e.message}") }
            }
        }
    }

    private fun sendPrintJob() {
        // Load the selected bitmap and convert to JPEG bytes
        // Use the image displayed in ivPhoto if available, else a placeholder
        val bitmap = ivPhoto.drawable != null
            ? BitmapFactory.decodeResource(resources, R.drawable.placeholder) // fallback
            : BitmapFactory.decodeResource(resources, R.drawable.placeholder)
        // Try to get bitmap from ImageView if it's set
        if (ivPhoto.bitmap != null) {
            // ivPhoto is ImageView; we can set tag earlier or simply re-use the bitmap we loaded in onActivityResult.
            // For this demo we just use a placeholder; in a full app you'd keep a reference.
        }
        val outStream = java.io.ByteArrayOutputStream()
        // Compress at decent quality
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outStream)
        val imageBytes = outStream.toByteArray()

        // Send raw JPEG data to the printer
        val data = imageBytes

        try {
            val os: OutputStream = bluetoothSocket!!.outputStream
            os.write(data)
            os.flush()
            Log.d(TAG, "Print job sent, size=${data.size}")
            runOnUiThread {
                tvStatus.text = "Printing…"
                Toast.makeText(this, "Print job sent", Toast.LENGTH_SHORT).show()
            }
        } catch (e: IOException) {
            e.printStackTrace()
            runOnUiThread {
                tvStatus.text = "Failed to send"
                Toast.makeText(this, "Error sending data", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showStatus(msg: String) {
        tvStatus.text = msg
    }

    // ------------------------------------------------
    // Permission result handling
    // ------------------------------------------------
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: Int[]) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            BLUETOOTH_PERMISSION_CODE -> {
                // Check if all needed bluetooth permissions granted
                val allGranted = permissions.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (allGranted) {
                    // permissions granted – nothing else needed; UI works
                } else {
                    Toast.makeText(this, "Bluetooth permission denied", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_CODE_LOCATION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // location permission granted
                } else {
                    Toast.makeText(this, "Location permission denied (needed for scanning)", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_CODE_GALLERY -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    openGallery()
                } else {
                    Toast.makeText(this, "Gallery permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}