package com.example.brbscan;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.example.brbscan.databinding.ActivityMainBinding;
import com.google.common.util.concurrent.ListenableFuture;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private Interpreter tflite;
    private ExecutorService cameraExecutor;
    private ActivityResultLauncher<String> requestPermissionLauncher;
    private TextToSpeech tts;
    private View scanIndicator;
    private Vibrator vibrator;

    private static final int MODEL_INPUT_SIZE = 224;
    private static final String MODEL_PATH = "banknote_model.tflite";
    private final String[] labels = {"خمسة دنانير", "عشرة دنانير", "عشرون دينار", "خمسون دينار", "لا شيء"};

    // Modified confidence thresholds
    private static final float GENERAL_THRESHOLD = 0.45f;  // Reduced from 0.65f
    private static final float FIFTY_DINAR_THRESHOLD = 0.65f;  // Reduced from 0.75f
    private static final float FIFTY_DINAR_PENALTY = 0.10f;  // Reduced from 0.15f

    private long lastAnalysisTime = 0;
    private boolean isAnalyzing = true;
    private int noDetectionCount = 0;
    private static final int NO_DETECTION_THRESHOLD = 5;
    private int consecutiveFiftyDetections = 0;
    private static final int MAX_CONSECUTIVE_FIFTY_DETECTIONS = 3;

    private Toast currentToast;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        if (getSupportActionBar() != null) getSupportActionBar().hide();

        scanIndicator = binding.getRoot().findViewById(R.id.scanIndicator);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        try {
            tflite = new Interpreter(loadModelFile());
            Log.d("Model", "Model loaded successfully");
        } catch (IOException e) {
            Log.e("Model", "Failed to load model", e);
            showToast("فشل في تحميل النموذج", Toast.LENGTH_LONG);
            finish();
            return;
        }

        cameraExecutor = Executors.newSingleThreadExecutor();

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(new Locale("ar"));
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("TTS", "اللغة العربية غير مدعومة");
                }
            }
        });

        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (isGranted) {
                        startCamera();
                    } else {
                        showToast("تم رفض إذن الكاميرا");
                    }
                });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private MappedByteBuffer loadModelFile() throws IOException {
        try (FileInputStream fileInputStream = new FileInputStream(getAssets().openFd(MODEL_PATH).getFileDescriptor())) {
            FileChannel fileChannel = fileInputStream.getChannel();
            long startOffset = getAssets().openFd(MODEL_PATH).getStartOffset();
            long declaredLength = getAssets().openFd(MODEL_PATH).getDeclaredLength();
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .build();
                preview.setSurfaceProvider(binding.previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (Exception e) {
                Log.e("Camera", "Failed to start camera", e);
                showToast("فشل في تشغيل الكاميرا");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeImage(@NonNull ImageProxy imageProxy) {
        if (!isAnalyzing) {
            imageProxy.close();
            return;
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastAnalysisTime < 800) {
            imageProxy.close();
            return;
        }
        lastAnalysisTime = currentTime;

        try {
            Bitmap bitmap = ImageUtils.imageProxyToBitmap(imageProxy);
            if (bitmap == null) {
                Log.e("Camera", "Failed to convert ImageProxy to Bitmap");
                imageProxy.close();
                return;
            }

            bitmap = rotateAndEnhanceBitmap(bitmap, imageProxy.getImageInfo().getRotationDegrees());
            processImageForInference(bitmap);
        } catch (Exception e) {
            Log.e("Camera", "Error processing image", e);
        } finally {
            imageProxy.close();
        }
    }

    private void processImageForInference(Bitmap bitmap) {
        cameraExecutor.execute(() -> {
            try {
                PredictionResult result = runModelInference(bitmap);
                handlePredictionResult(result);
            } catch (Exception e) {
                Log.e("Model", "Inference error", e);
            }
        });
    }

    private Bitmap rotateAndEnhanceBitmap(Bitmap bitmap, int rotationDegrees) {
        if (rotationDegrees != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotationDegrees);
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        }
        return enhanceContrast(bitmap);
    }

    private Bitmap enhanceContrast(Bitmap src) {
        Bitmap dst = src.copy(src.getConfig(), true);
        int[] pixels = new int[dst.getWidth() * dst.getHeight()];
        dst.getPixels(pixels, 0, dst.getWidth(), 0, 0, dst.getWidth(), dst.getHeight());

        for (int i = 0; i < pixels.length; i++) {
            int r = Color.red(pixels[i]);
            int g = Color.green(pixels[i]);
            int b = Color.blue(pixels[i]);

            r = (int) (255 * Math.pow(r / 255.0, 0.7));
            g = (int) (255 * Math.pow(g / 255.0, 0.7));
            b = (int) (255 * Math.pow(b / 255.0, 0.7));

            r = Math.min(255, Math.max(0, r));
            g = Math.min(255, Math.max(0, g));
            b = Math.min(255, Math.max(0, b));

            pixels[i] = Color.rgb(r, g, b);
        }

        dst.setPixels(pixels, 0, dst.getWidth(), 0, 0, dst.getWidth(), dst.getHeight());
        return dst;
    }

    private PredictionResult runModelInference(Bitmap bitmap) {
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, true);
        float[][][][] input = new float[1][MODEL_INPUT_SIZE][MODEL_INPUT_SIZE][3];

        for (int y = 0; y < MODEL_INPUT_SIZE; y++) {
            for (int x = 0; x < MODEL_INPUT_SIZE; x++) {
                int pixel = resized.getPixel(x, y);
                float r = (float) Math.pow(((pixel >> 16) & 0xFF) / 255.0, 2.2);
                float g = (float) Math.pow(((pixel >> 8) & 0xFF) / 255.0, 2.2);
                float b = (float) Math.pow((pixel & 0xFF) / 255.0, 2.2);

                input[0][y][x][0] = (r - 0.5f) * 2.0f;
                input[0][y][x][1] = (g - 0.5f) * 2.0f;
                input[0][y][x][2] = (b - 0.5f) * 2.0f;
            }
        }

        float[][] output = new float[1][labels.length];
        tflite.run(input, output);

        float[] probs = softmaxWithTemperature(output[0], 0.8f);

        // Debug logging of raw probabilities
        Log.d("ModelDebug", "Raw probabilities: " + Arrays.toString(probs));

        // Apply penalty to 50 dinar
        int fiftyIndex = Arrays.asList(labels).indexOf("خمسون دينار");
        if (fiftyIndex >= 0) {
            probs[fiftyIndex] *= (1f - FIFTY_DINAR_PENALTY);
            Log.d("ModelDebug", "After penalty - 50 dinar prob: " + probs[fiftyIndex]);
        }

        int bestIdx = 0;
        float maxProb = probs[0];
        for (int i = 1; i < probs.length; i++) {
            if (probs[i] > maxProb) {
                maxProb = probs[i];
                bestIdx = i;
            }
        }

        // Debug logging of best detection
        Log.d("ModelDebug", "Best detection: " + labels[bestIdx] + " with confidence: " + maxProb +
                " (Threshold: " + GENERAL_THRESHOLD + ")");

        if (labels[bestIdx].equals("خمسون دينار")) {
            consecutiveFiftyDetections++;
            if (consecutiveFiftyDetections < MAX_CONSECUTIVE_FIFTY_DETECTIONS || maxProb < FIFTY_DINAR_THRESHOLD) {
                noDetectionCount++;
                Log.d("ModelDebug", "50 dinar detection rejected - consecutive: " + consecutiveFiftyDetections);
                return new PredictionResult("لا شيء", maxProb);
            }
        } else {
            consecutiveFiftyDetections = 0;
        }

        if (maxProb >= GENERAL_THRESHOLD && !labels[bestIdx].equals("لا شيء")) {
            noDetectionCount = 0;
            Log.d("ModelDebug", "Valid detection: " + labels[bestIdx]);
            return new PredictionResult(labels[bestIdx], maxProb);
        }

        noDetectionCount++;
        Log.d("ModelDebug", "No valid detection - best confidence below threshold");
        return new PredictionResult("لا شيء", maxProb);
    }

    private float[] softmaxWithTemperature(float[] logits, float temperature) {
        float sum = 0f;
        float[] exp = new float[logits.length];
        for (int i = 0; i < logits.length; i++) {
            exp[i] = (float) Math.exp(logits[i] / temperature);
            sum += exp[i];
        }
        for (int i = 0; i < exp.length; i++) {
            exp[i] /= sum;
        }
        return exp;
    }

    private synchronized void handlePredictionResult(PredictionResult result) {
        runOnUiThread(() -> {
            // Debug output
            Log.d("Detection", "Handling result - Label: " + result.label + ", Confidence: " + result.confidence);

            if (result.label.equals("لا شيء")) {
                if (noDetectionCount >= NO_DETECTION_THRESHOLD) {
                    showToast("الرجاء توجيه الكاميرا نحو الورقة النقدية");
                }
                return;
            }

            // Check confidence again in case of race conditions
            if (result.confidence < GENERAL_THRESHOLD) {
                Log.d("Detection", "Low confidence result filtered out");
                return;
            }

            String text = result.label + String.format(Locale.getDefault(), " (%.1f%%)", result.confidence * 100);
            showToast("تم التعرف: " + text);

            if (scanIndicator != null) {
                scanIndicator.setAlpha(1f);
                scanIndicator.animate().alpha(0f).setDuration(500).start();
            }

            if (tts != null && !tts.isSpeaking()) {
                tts.speak(result.label, TextToSpeech.QUEUE_FLUSH, null, "BillID");
                vibrateForBill(result.label);
            }
        });
    }

    private void showToast(String message) {
        showToast(message, Toast.LENGTH_SHORT);
    }

    private void showToast(String message, int duration) {
        runOnUiThread(() -> {
            if (currentToast != null) {
                currentToast.cancel();
            }
            currentToast = Toast.makeText(this, message, duration);
            currentToast.show();
        });
    }

    private void vibrateForBill(String label) {
        if (vibrator == null || !vibrator.hasVibrator()) return;

        long[] pattern;
        if (label.contains("خمسة دنانير")) {
            pattern = new long[]{0, 200};
        } else if (label.contains("عشرة دنانير")) {
            pattern = new long[]{0, 200, 300, 200};
        } else if (label.contains("عشرون دينار")) {
            pattern = new long[]{0, 600};
        } else if (label.contains("خمسون دينار")) {
            pattern = new long[]{0, 600, 400, 600};
        } else {
            return;
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } else {
            vibrator.vibrate(pattern, -1);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        isAnalyzing = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        isAnalyzing = true;
        noDetectionCount = 0;
        consecutiveFiftyDetections = 0;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tflite != null) tflite.close();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (currentToast != null) {
            currentToast.cancel();
        }
    }

    private static class PredictionResult {
        final String label;
        final float confidence;

        PredictionResult(String label, float confidence) {
            this.label = label;
            this.confidence = confidence;
        }
    }
}