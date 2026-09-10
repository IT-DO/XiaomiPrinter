package com.example.xiaomiprinter

import android.Manifest
import android.content.Intent
.content
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.OutputStream
import java.util.UUID
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

const val REQUEST_CODE_GALLERY = 1001
const val REQUEST_CODE_LOCATION = 1002
const val BLUETOOTH_PERMISSION_CODE = 1003

companion object {
    private val TAG = "XiaomiPrinter"

    // UUID for the printer's RFCOMM channel (may need to be adjusted for the specific model)
    private val PRINTER_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // SPP UUID

    // Address of the printer – user must replace with the actual device MAC address
    // Example: "20:16:0D:12:34:56"
    var PRINTER_ADDRESS: String = ""
}

class MainActivity : AppCompatActivity() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var printThread: Thread? = null

    private lateinit var ivPhoto: ImageView
    private lateinit var btnPrint: Button
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ivPhoto = findViewById(R.id.ivPhoto)
        btnPrint = findViewById(R.id.btnPrint)
        tvStatus = findViewById(R.id.tvStatus)

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        // Request Bluetooth permissions if needed
        if (checkSelfPermission(Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN),
                BLUETOOTH_PERMISSION_CODE
            )
        }

        btnPrint.setOnClickListener {
            onPrintClicked()
        }

        // Open gallery to pick a photo
        ivPhoto.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED)) {
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

    private fun onPrintClicked() {
        // Ensure we have a photo loaded
        if (ivPhoto.drawableState.isEmpty()) {
            Toast.makeText(this, "Please select a photo first", Toast.LENGTH_SHORT).show()
            return
        }

        // Ask user for printer MAC address (hardcoded placeholder)
        val printerDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Printer MAC Address")
            .setMessage("Enter the Bluetooth MAC address of your Xiaomi Portable Photo Printer 1S")
            .setPositiveButton("Connect") { _, _ ->
                val mac = etMac?.text?.toString() ?: ""
                if (mac.isNotEmpty()) {
                    PRINTER_ADDRESS = mac
                    attemptConnectAndPrint()
                } else {
                    Toast.makeText(this, "Enter MAC address", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        // Inflate a custom view with an EditText for MAC
        val view = androidx.layoutinflater.LayoutInflater.from(this).inflate(R.layout.dialog_printer_mac, null)
        etMac = view.findViewById(R.id.etMac)
        printerDialog.setView(view)
        printerDialog.show()
    }

    private lateinit var etMac: android.widget.EditText

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
                            showStatus("Connected! Sending image…
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
        val bitmap = BitmapFactory.decodeResource(resources, R.drawable.placeholder) // TODO: replace with ivPhoto bitmap
        val outStream = java.io.ByteArrayOutputStream()
        // Compress at decent quality
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outStream)
        val imageBytes = outStream.toByteArray()

        // Optionally prepend a simple header if the printer expects it;
        // for now we just send the raw JPEG data.
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

    // Handle permission results
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: Int[]) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            BLUETOOTH_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permissions granted, continue
                } else {
                    Toast.makeText(this, "Bluetooth permission denied", Toast.LENGTH_SHORT).show()
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