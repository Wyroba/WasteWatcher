package delta.Peter.Chi.WasteWatcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import java.util.*
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.ParseException
import com.google.android.material.textfield.TextInputEditText
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import delta.Peter.Chi.WasteWatcher.databinding.ActivityMainBinding
import java.io.IOException
import java.text.SimpleDateFormat
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

        // Initialize the activity layout
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize barcodeScanner and cameraExecutor
        barcodeScanner = BarcodeScanning.getClient()
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Check if camera permissions are granted, if not, request them
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Set an OnClickListener for the camera preview to capture images
        binding.viewFinder.setOnClickListener {
            takePhotoAndProcessBarcode()
        }

        // Set up date picker for expiration date input field
        setupDatePicker()

        // Set up voice input button
        setupVoiceInput()

        // Set an OnClickListener for the submit button
        binding.submitButton.setOnClickListener {
            addItem()
        }

        // Set an OnClickListener for expiration date input field to show date picker
        binding.expirationDateInput.setOnClickListener {
            showDatePicker()
        }
    }

    // Function to set up the date picker for expiration date input
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

    // Function to set up voice input
    private fun setupVoiceInput() {
        val voiceInputButton = findViewById<Button>(R.id.voice_input_button)
        voiceInputButton.setOnClickListener {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            }
            startActivityForResult(intent, VOICE_INPUT_REQUEST_CODE)
        }
    }

    // Function to handle the result of voice input recognition
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VOICE_INPUT_REQUEST_CODE && resultCode == RESULT_OK) {
            val matches = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!matches.isNullOrEmpty()) {
                parseAndSetDateFromVoiceInput(matches[0])
            }
        }
    }

    // Function to parse and set date from voice input
    private fun parseAndSetDateFromVoiceInput(voiceInput: String) {
        val parsedDate = parseDateFromVoiceInput(voiceInput)
        parsedDate?.let {
            updateDatePicker(it)
        } ?: run {
            Toast.makeText(this, "Could not recognize the date format", Toast.LENGTH_SHORT).show()
        }
    }

    // Function to parse date from voice input
    private fun parseDateFromVoiceInput(voiceInput: String): Date? {
        // Preprocess the input to remove ordinal suffixes
        val preprocessedInput = voiceInput.replace(Regex("(\\d)(st|nd|rd|th)"), "$1")
        val potentialFormats = listOf("MMMM dd yyyy", "dd/MM/yyyy", "MM/dd/yyyy") // Include "MM/dd/yyyy" format
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

    // Function to update the date picker
    private fun updateDatePicker(date: Date) {
        val calendar = Calendar.getInstance()
        calendar.time = date
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)

        val expirationDateInput = findViewById<TextInputEditText>(R.id.expiration_date_input)
        // Format the date as MM/dd/yyyy
        expirationDateInput.setText(String.format("%02d/%02d/%d", month + 1, day, year))
    }

    // Function to start the camera and initialize image capture
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

    // Function to capture a photo and process it for barcodes
    private fun takePhotoAndProcessBarcode() {
        val imageCapture = imageCapture ?: return

        // Create an output file for the captured photo
        val photoFile = ImageCapture.OutputFileOptions.Builder(createTempFile()).build()

        // Capture the image and process it for barcodes
        imageCapture.takePicture(photoFile, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                @SuppressLint("RestrictedApi")
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

    // Function to process the captured image for barcodes
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

    // Function to check if all required permissions are granted
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        // Check if all required permissions are granted
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    // Function to handle permission request results
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

    // Function to shut down the camera executor when the activity is destroyed
    override fun onDestroy() {
        super.onDestroy()
        // Shutdown the camera executor when the activity is destroyed
        cameraExecutor.shutdown()
    }

    // Function to add a new item
    private fun addItem() {
        if (!validateInput()) {
            // If validation fails, show a Toast and return early
            Toast.makeText(this, "Invalid input. Please check the SKU and date.", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            // Initialize Redis client
            val redisClient = RedisClient.create("redis://default:FJXMsdIeaWspO8n0rbq84opxJE11dQku@redis-12838.c1.us-central1-2.gce.cloud.redislabs.com:12838")
            val connection: StatefulRedisConnection<String, String> = redisClient.connect()
            val syncCommands = connection.sync()

            try {
                // Generate a unique key for the new entry
                val newEntryKey = "product:${UUID.randomUUID()}"

                // Define the format for the date string
                val outputDateFormat = SimpleDateFormat("MM/dd/yyyy", Locale.US)
                var formattedDate = ""

                try {
                    // Parse the date from the input string
                    val inputDate = binding.expirationDateInput.text.toString()
                    val inputDateFormat = SimpleDateFormat("MM/dd/yyyy", Locale.US)
                    val date = inputDateFormat.parse(inputDate)
                    formattedDate = outputDateFormat.format(date)
                } catch (e: ParseException) {
                    Log.e("MainActivity", "Error parsing date", e)
                }

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
                        binding.skuInput.text?.clear()
                        binding.expirationDateInput.text?.clear()
                        binding.batchLotInput.text?.clear()
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

    // Function to validate user input for SKU and date
    private fun validateInput(): Boolean {
        val skuInput = binding.skuInput.text.toString()
        val dateInput = binding.expirationDateInput.text.toString()

        // Validate SKU - adjust the pattern as per your requirements
        if (!skuInput.matches(Regex("^[a-zA-Z0-9]{5,12}$"))) {
            return false
        }

        // Validate Date
        if (dateInput.isEmpty()) {
            return false
        }

        val dateFormats = listOf("yyyy-MM-dd", "M/dd/yyyy")
        return isValidDate(dateInput, dateFormats)
    }

    private fun isValidDate(input: String, formats: List<String>): Boolean {
        for (format in formats) {
            val sdf = SimpleDateFormat(format, Locale.getDefault())
            sdf.isLenient = false
            try {
                val date = sdf.parse(input)
                val currentDate = Calendar.getInstance().time
                if (date != null && !date.before(currentDate)) {
                    return true
                }
            } catch (e: java.text.ParseException) {
                // Continue to the next format if parsing fails
            }
        }
        return false
    }

    // Function to show a date picker dialog for expiration date input
    private fun showDatePicker() {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)

        val datePickerDialog = DatePickerDialog(this,
            { _, selectedYear, selectedMonth, selectedDayOfMonth ->
                // Format the date to include leading zeros for month and day
                val selectedDate = Calendar.getInstance()
                selectedDate.set(selectedYear, selectedMonth, selectedDayOfMonth)
                val dateFormat = SimpleDateFormat("MM/dd/yyyy", Locale.getDefault())
                binding.expirationDateInput.setText(dateFormat.format(selectedDate.time))
            }, year, month, day)

        datePickerDialog.show()
    }
}
