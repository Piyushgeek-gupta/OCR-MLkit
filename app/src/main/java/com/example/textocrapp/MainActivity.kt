package com.example.textocrapp

import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var gestureDetector: GestureDetector

    // UI Elements
    private lateinit var previewView: PreviewView
    private lateinit var statusTextView: TextView

    // State
    @Volatile
    private var currentlyDetectedText: String = ""
    private var isListening = false

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        private const val TAG = "SmartOCR"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Setup UI Programmatically (No XML needed)
        setupUserInterface()

        // 2. Initialize Helper Classes
        cameraExecutor = Executors.newSingleThreadExecutor()
        textToSpeech = TextToSpeech(this, this)
        setupGestureDetector()
        setupVoiceRecognizer()

        // 3. Check Permissions and Start
        if (allPermissionsGranted()) {
            startCamera()
            startListeningForVoiceCommands()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun setupUserInterface() {
        val rootLayout = FrameLayout(this)
        rootLayout.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        // Camera Preview
        previewView = PreviewView(this)
        previewView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        rootLayout.addView(previewView)

        // Status Text Overlay (to show detected text snippets)
        statusTextView = TextView(this)
        statusTextView.setTextColor(Color.WHITE)
        statusTextView.setBackgroundColor(Color.parseColor("#80000000")) // Semi-transparent black
        statusTextView.setPadding(32, 32, 32, 32)
        statusTextView.textSize = 16f
        statusTextView.text = "Point camera at text. Double tap to read."

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.gravity = android.view.Gravity.BOTTOM
        statusTextView.layoutParams = params
        rootLayout.addView(statusTextView)

        setContentView(rootLayout)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview Use Case
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            // Image Analysis Use Case (The OCR Engine)
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, TextReaderAnalyzer { text ->
                        currentlyDetectedText = text
                        // Update UI on main thread
                        runOnUiThread {
                            if (text.isNotEmpty()) {
                                statusTextView.text = "Detected: ${text.take(50)}..."
                            }
                        }
                    })
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // --- ML Kit Analyzer ---

    private class TextReaderAnalyzer(private val listener: (String) -> Unit) : ImageAnalysis.Analyzer {

        // We use the Devanagari options to support Hindi, Marathi, and English
        private val recognizer = TextRecognition.getClient(
            DevanagariTextRecognizerOptions.Builder().build()
        )

        @OptIn(ExperimentalGetImage::class)
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        listener(visionText.text)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Text recognition failed", e)
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            } else {
                imageProxy.close()
            }
        }
    }

    // --- Text To Speech ---

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Try to set to Hindi, fall back to English if not available
            val result = textToSpeech.setLanguage(Locale("hi", "IN"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                textToSpeech.setLanguage(Locale.ENGLISH)
            }
        }
    }

    private fun readOutLoud() {
        if (currentlyDetectedText.isNotBlank()) {
            Toast.makeText(this, "Reading...", Toast.LENGTH_SHORT).show()
            // Stop current speech if any
            textToSpeech.stop()
            textToSpeech.speak(currentlyDetectedText, TextToSpeech.QUEUE_FLUSH, null, "ocr_read")
        } else {
            Toast.makeText(this, "No text detected yet", Toast.LENGTH_SHORT).show()
        }
    }

    // --- Voice Command Recognition ---

    private fun setupVoiceRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    // Restart listening after a short pause if desired,
                    // or wait for button press. For this demo, we auto-restart
                    // to simulate "always listening" (Note: Battery intensive in real apps)
                    if (isListening) startListeningForVoiceCommands()
                }

                override fun onError(error: Int) {
                    // Restart on error to keep listening loop alive
                    if (isListening) startListeningForVoiceCommands()
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    matches?.let {
                        for (phrase in it) {
                            processVoiceCommand(phrase.lowercase())
                        }
                    }
                    // Continue listening
                    startListeningForVoiceCommands()
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startListeningForVoiceCommands() {
        if (!allPermissionsGranted()) return

        isListening = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Listen for both English and Hindi logic roughly
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        // Run on UI thread to ensure thread safety for SpeechRecognizer
        runOnUiThread {
            try {
                speechRecognizer.startListening(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun processVoiceCommand(phrase: String) {
        // Check for English command or Hindi/Hinglish command
        if (phrase.contains("read what") ||
            phrase.contains("front of me") ||
            phrase.contains("mere saamne") ||
            phrase.contains("samne kya likha")) {

            readOutLoud()
        }
    }

    // --- Gestures (Double Tap) ---

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                readOutLoud()
                return true
            }

            override fun onDown(e: MotionEvent): Boolean = true
        })
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        return if (event != null) {
            gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
        } else {
            super.onTouchEvent(event)
        }
    }

    // --- Permissions Boilerplate ---

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
                startListeningForVoiceCommands()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        textToSpeech.stop()
        textToSpeech.shutdown()
        speechRecognizer.destroy()
    }
}