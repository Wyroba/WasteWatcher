package delta.Peter.Chi.WasteWatcher

import android.Manifest
import android.app.DatePickerDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import java.util.*
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.text.set
import com.google.mlkit.vision.barcode.BarcodeScanning
import delta.Peter.Chi.WasteWatcher.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var barcodeScanner: com.google.mlkit.vision.barcode.BarcodeScanner

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        barcodeScanner = BarcodeScanning.getClient()
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        binding.submitButton.setOnClickListener{
            addItem()
        }

        binding.expirationDateInput.setOnClickListener {
            showDatePicker()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            } catch (exc: Exception) {
                // Handle exceptions
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    // Function to capture image and process for barcode
    private fun processImageForBarcode() {
        // Here, implement logic to capture an image from the PreviewView
        // and process it using ML Kit
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun addItem() {
        Thread {
            // Initialize Redis client
            val redisClient = RedisClient.create("redis://default:FJXMsdIeaWspO8n0rbq84opxJE11dQku@redis-12838.c1.us-central1-2.gce.cloud.redislabs.com:12838")
            val connection: StatefulRedisConnection<String, String> = redisClient.connect()
            val syncCommands = connection.sync()

            try {
                // Generate a unique key for the new entry
                val newEntryKey = "product:${UUID.randomUUID()}"

                // Define the new entry data using Gson
                val newEntryObject = JsonObject()
                newEntryObject.addProperty("UPC", binding.skuInput.text.toString())
                newEntryObject.addProperty("Date", binding.expirationDateInput.text.toString())
                newEntryObject.addProperty("Lot Number", binding.batchLotInput.text.toString())
                val newEntryJson = Gson().toJson(newEntryObject)

                // Try to add the new entry to the database as JSON
                val setResult = syncCommands.set(newEntryKey, newEntryJson)

                // Check if the new entry was added successfully
                if ("OK" == setResult) {
                    // Log the new entry action
                    Log.d("RedisTest", "New entry added with key $newEntryKey: $newEntryJson")

                    // Prepare a user-friendly string
                    val displayText = "Last entry added:\nUPC: ${binding.skuInput.text}\nDate: ${binding.expirationDateInput.text}\nLot Number: ${binding.batchLotInput.text}"

                    // Update the TextView with the user-friendly string
                    runOnUiThread {
                        binding.textView.text = displayText
                        binding.skuInput.text.clear()
                        binding.expirationDateInput.text.clear()
                        binding.batchLotInput.text.clear()
                    }
                } else {
                    // Handle the failure case
                    Log.e("RedisTest", "Failed to add new entry: $newEntryJson")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Failed to add item", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception)
            {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error adding item", Toast.LENGTH_SHORT).show()
                }
            } finally {
                connection.close()
                redisClient.shutdown()
            }
        }.start()
    }

    private fun showDatePicker() {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)

        val datePickerDialog = DatePickerDialog(this,
            { _, selectedYear, selectedMonth, selectedDayOfMonth ->
                binding.expirationDateInput.setText("${selectedMonth + 1}/$selectedDayOfMonth/$selectedYear")
            }, year, month, day)

        datePickerDialog.show()
    }
}
