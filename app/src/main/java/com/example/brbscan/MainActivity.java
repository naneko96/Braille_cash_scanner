package com.example.brbscan;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.net.Uri;
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
import androidx.camera.core.Camera;
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
    private Camera camera;

    private static final int MODEL_INPUT_SIZE = 224;
    private static final String MODEL_PATH = "banknote_model.tflite";
    private final String[] labels = {"خمسة دنانير", "عشرة دنانير", "عشرون دينار", "خمسون دينار", "لا شيء"};

    private static final float GENERAL_THRESHOLD = 0.45f;
    private static final float FIFTY_DINAR_THRESHOLD = 0.65f;
    private static final float FIFTY_DINAR_PENALTY = 0.10f;
    private static final float TWENTY_DINAR_BOOST = 0.10f;

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

        // Set up clickable footer "Powered by naneko96"
        binding.bottomLeftText.setOnClickListener(v -> openGithub());

        try {
            tflite = new Interpreter(loadModelFile());
        } catch (IOException e) {
            showToast("فشل في تحميل النموذج", Toast.LENGTH_LONG);
            finish();
            return;
        }

        cameraExecutor = Executors.newSingleThreadExecutor();

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("ar"));
            }
        });

        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (isGranted) {
                        startCamera();
                    } else {
                        showToast("تم رفض إذن الكاميرا", Toast.LENGTH_LONG);
                    }
                });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void openGithub() {
        String url = "https://github.com/naneko96";
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse(url));
        startActivity(intent);
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
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

                if (camera.getCameraInfo().hasFlashUnit()) {
                    camera.getCameraControl().enableTorch(true);
                }

            } catch (Exception e) {
                showToast("فشل في تشغيل الكاميرا", Toast.LENGTH_LONG);
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
            if (bitmap != null) {
                bitmap = rotateAndEnhanceBitmap(bitmap, imageProxy.getImageInfo().getRotationDegrees());
                processImageForInference(bitmap);
            }
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
        return bitmap;
    }

    private PredictionResult runModelInference(Bitmap bitmap) {
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, true);
        float[][][][] input = new float[1][MODEL_INPUT_SIZE][MODEL_INPUT_SIZE][3];

        for (int y = 0; y < MODEL_INPUT_SIZE; y++) {
            for (int x = 0; x < MODEL_INPUT_SIZE; x++) {
                int pixel = resized.getPixel(x, y);
                input[0][y][x][0] = ((pixel >> 16 & 0xFF) / 127.5f) - 1;
                input[0][y][x][1] = ((pixel >> 8 & 0xFF) / 127.5f) - 1;
                input[0][y][x][2] = ((pixel & 0xFF) / 127.5f) - 1;
            }
        }

        float[][] output = new float[1][labels.length];
        tflite.run(input, output);

        float[] probs = softmaxWithTemperature(output[0], 0.8f);

        int fiftyIdx = Arrays.asList(labels).indexOf("خمسون دينار");
        if (fiftyIdx >= 0) probs[fiftyIdx] *= (1f - FIFTY_DINAR_PENALTY);

        int twentyIdx = Arrays.asList(labels).indexOf("عشرون دينار");
        if (twentyIdx >= 0) probs[twentyIdx] *= (1f + TWENTY_DINAR_BOOST);

        int bestIdx = 0;
        float maxProb = probs[0];
        for (int i = 1; i < probs.length; i++) {
            if (probs[i] > maxProb) {
                maxProb = probs[i];
                bestIdx = i;
            }
        }

        if (labels[bestIdx].equals("خمسون دينار")) {
            consecutiveFiftyDetections++;
            if (consecutiveFiftyDetections < MAX_CONSECUTIVE_FIFTY_DETECTIONS || maxProb < FIFTY_DINAR_THRESHOLD) {
                return new PredictionResult("لا شيء", maxProb);
            }
        } else {
            consecutiveFiftyDetections = 0;
        }

        if (maxProb >= GENERAL_THRESHOLD && !labels[bestIdx].equals("لا شيء")) {
            return new PredictionResult(labels[bestIdx], maxProb);
        }

        return (maxProb >= 0.3f) ? new PredictionResult(labels[bestIdx], maxProb)
                : new PredictionResult("لا شيء", maxProb);
    }

    private float[] softmaxWithTemperature(float[] logits, float temperature) {
        float sum = 0f;
        float[] exp = new float[logits.length];
        for (int i = 0; i < logits.length; i++) {
            exp[i] = (float) Math.exp(logits[i] / temperature);
            sum += exp[i];
        }
        for (int i = 0; i < logits.length; i++) {
            exp[i] /= sum;
        }
        return exp;
    }

    private void handlePredictionResult(PredictionResult result) {
        runOnUiThread(() -> {
            if (result.label.equals("لا شيء")) {
                if (++noDetectionCount >= NO_DETECTION_THRESHOLD) {
                    showToast("الرجاء توجيه الكاميرا نحو الورقة النقدية", Toast.LENGTH_LONG);
                }
                return;
            }

            noDetectionCount = 0;
            showToast("تم التعرف: " + result.label, Toast.LENGTH_LONG);

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

    private void showToast(String message, int duration) {
        runOnUiThread(() -> {
            if (currentToast != null) currentToast.cancel();
            currentToast = Toast.makeText(this, message, duration);
            currentToast.show();
        });
    }

    private void vibrateForBill(String label) {
        if (vibrator == null || !vibrator.hasVibrator()) return;

        long[] pattern;
        switch (label) {
            case "خمسة دنانير": pattern = new long[]{0, 200}; break;
            case "عشرة دنانير": pattern = new long[]{0, 200, 300, 200}; break;
            case "عشرون دينار": pattern = new long[]{0, 600}; break;
            case "خمسون دينار": pattern = new long[]{0, 600, 400, 600}; break;
            default: return;
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
        if (currentToast != null) currentToast.cancel();
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
