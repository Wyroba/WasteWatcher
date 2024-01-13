package delta.Peter.Chi.WasteWatcher

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import delta.Peter.Chi.WasteWatcher.databinding.ActivityMainBinding
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var barcodeScanner: com.google.mlkit.vision.barcode.BarcodeScanner
    private var imageCapture: ImageCapture? = null

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inflate the layout and set it as the content view
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize the barcode scanner and camera executor
        barcodeScanner = BarcodeScanning.getClient()
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Check if camera permissions are granted, and start the camera if granted, else request permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Set a click listener on the view finder to take a photo and process a barcode
        binding.viewFinder.setOnClickListener {
            takePhotoAndProcessBarcode()
        }
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
}
