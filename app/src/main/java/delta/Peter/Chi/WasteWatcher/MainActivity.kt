package delta.Peter.Chi.WasteWatcher

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.app.DatePickerDialog
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Button
import android.widget.Toast
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
import com.google.android.material.textfield.TextInputEditText
import androidx.core.text.set
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import delta.Peter.Chi.WasteWatcher.databinding.ActivityMainBinding
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var barcodeScanner: com.google.mlkit.vision.barcode.BarcodeScanner
    private var imageCapture: ImageCapture? = null

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val VOICE_INPUT_REQUEST_CODE = 100
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
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        binding.viewFinder.setOnClickListener {
            takePhotoAndProcessBarcode()
        }

        setupDatePicker()
        setupVoiceInput()
    }

    private fun setupDatePicker() {
        val expirationDateInput = findViewById<TextInputEditText>(R.id.expiration_date_input)
        expirationDateInput.setOnClickListener {
            val calendar = Calendar.getInstance()
            val year = calendar.get(Calendar.YEAR)
            val month = calendar.get(Calendar.MONTH)
            val day = calendar.get(Calendar.DAY_OF_MONTH)

            val datePickerDialog = DatePickerDialog(this, { _, selectedYear, selectedMonth, selectedDayOfMonth ->
                expirationDateInput.setText(String.format("%d-%02d-%02d", selectedYear, selectedMonth + 1, selectedDayOfMonth))
            }, year, month, day)
            datePickerDialog.show()
        }
    }

    private fun setupVoiceInput() {
        val voiceInputButton = findViewById<Button>(R.id.voice_input_button)
        voiceInputButton.setOnClickListener {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            }
            startActivityForResult(intent, VOICE_INPUT_REQUEST_CODE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VOICE_INPUT_REQUEST_CODE && resultCode == RESULT_OK) {
            val matches = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!matches.isNullOrEmpty()) {
                parseAndSetDateFromVoiceInput(matches[0])
            }
        }
    }

    private fun parseAndSetDateFromVoiceInput(voiceInput: String) {
        val parsedDate = parseDateFromVoiceInput(voiceInput)
        parsedDate?.let {
            updateDatePicker(it)
        } ?: run {
            Toast.makeText(this, "Could not recognize the date format", Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseDateFromVoiceInput(voiceInput: String): Date? {
        // Preprocess the input to remove ordinal suffixes
        val preprocessedInput = voiceInput.replace(Regex("(\\d)(st|nd|rd|th)"), "$1")
        val potentialFormats = listOf("MMMM dd yyyy", "dd/MM/yyyy", "MM-dd-yyyy")
        var parsedDate: Date? = null

        for (format in potentialFormats) {
            try {
                val sdf = SimpleDateFormat(format, Locale.getDefault())
                sdf.isLenient = false
                parsedDate = sdf.parse(preprocessedInput)
                if (parsedDate != null) break
            } catch (e: Exception) {
                // Parsing failed for this format, try the next one
            }
        }

        return parsedDate
    }

    private fun updateDatePicker(date: Date) {
        val calendar = Calendar.getInstance()
        calendar.time = date
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)

        val expirationDateInput = findViewById<TextInputEditText>(R.id.expiration_date_input)
        expirationDateInput.setText(String.format("%d-%02d-%02d", year, month + 1, day))
    }

    private fun startCamera() {
        // Create a camera provider future
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Create a preview and bind it to the camera lifecycle
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            // Create an image capture instance
            imageCapture = ImageCapture.Builder().build()

            try {
                // Unbind any existing use of the camera and bind to the camera selector, preview, and image capture
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (exc: Exception) {
                // Handle exceptions
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhotoAndProcessBarcode() {
        val imageCapture = imageCapture ?: return

        // Create an output file for the captured photo
        val photoFile = ImageCapture.OutputFileOptions.Builder(createTempFile()).build()

        // Capture the image and process it for barcodes
        imageCapture.takePicture(photoFile, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = Uri.fromFile(photoFile.file)
                    processImageForBarcode(savedUri)
                }

                override fun onError(exception: ImageCaptureException) {
                    // Handle errors during image capture
                    Toast.makeText(baseContext, "Photo capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun processImageForBarcode(uri: Uri) {
        try {
            // Create an InputImage from the captured photo's URI
            val image = InputImage.fromFilePath(this, uri)

            // Process the image for barcodes using the barcodeScanner
            barcodeScanner.process(image)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        val rawValue = barcode.rawValue ?: continue
                        // Set the barcode value in an input field (assuming the first barcode found is used)
                        binding.skuInput.setText(rawValue)
                        break
                    }
                }
                .addOnFailureListener {
                    // Handle failures in barcode processing
                    Toast.makeText(this, "Barcode processing failed", Toast.LENGTH_SHORT).show()
                }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        // Check if all required permissions are granted
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                // If permissions are granted, start the camera
                startCamera()
            } else {
                // If permissions are not granted, display a message and finish the activity
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Shutdown the camera executor when the activity is destroyed
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
