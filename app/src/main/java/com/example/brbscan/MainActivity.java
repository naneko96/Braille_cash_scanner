package com.example.brbscan;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Bundle;
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

    private static final int MODEL_INPUT_SIZE = 180;
    private static final String MODEL_PATH = "banknote_model.tflite";
    private final String[] labels = {"خمسة دنانير", "عشرة دنانير", "عشرون دينار", "خمسون دينار", "لا شيء"};

    private long lastAnalysisTime = 0;
    private boolean isAnalyzing = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        if (getSupportActionBar() != null) getSupportActionBar().hide();

        scanIndicator = binding.getRoot().findViewById(R.id.scanIndicator);

        try {
            tflite = new Interpreter(loadModelFile());
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "فشل في تحميل النموذج", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        cameraExecutor = Executors.newSingleThreadExecutor();

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(new Locale("ar"));
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("TTS", "اللغة العربية غير مدعومة");
                    Toast.makeText(this, "TTS العربية غير مدعومة على هذا الجهاز", Toast.LENGTH_LONG).show();
                }
            } else {
                Log.e("TTS", "فشل في تهيئة TTS");
            }
        });

        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (isGranted) {
                        startCamera();
                    } else {
                        Toast.makeText(MainActivity.this, "تم رفض إذن الكاميرا", Toast.LENGTH_SHORT).show();
                    }
                });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private MappedByteBuffer loadModelFile() throws IOException {
        FileInputStream fileInputStream = new FileInputStream(getAssets().openFd(MODEL_PATH).getFileDescriptor());
        FileChannel fileChannel = fileInputStream.getChannel();
        long startOffset = getAssets().openFd(MODEL_PATH).getStartOffset();
        long declaredLength = getAssets().openFd(MODEL_PATH).getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
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
                e.printStackTrace();
                Toast.makeText(this, "فشل في تشغيل الكاميرا", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeImage(@NonNull ImageProxy imageProxy) {
        if (!isAnalyzing) {
            imageProxy.close();
            return;
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastAnalysisTime < 1000) {
            imageProxy.close();
            return;
        }
        lastAnalysisTime = currentTime;

        Bitmap bitmap;
        try {
            bitmap = ImageUtils.imageProxyToBitmap(imageProxy);
        } catch (Exception e) {
            Log.e("MainActivity", "فشل في تحويل الصورة: " + e.getMessage());
            imageProxy.close();
            return;
        }

        if (bitmap == null) {
            Log.e("MainActivity", "Bitmap is null");
            imageProxy.close();
            return;
        }

        bitmap = rotateBitmap(bitmap, imageProxy.getImageInfo().getRotationDegrees());
        Bitmap finalBitmap = bitmap;

        cameraExecutor.execute(() -> {
            try {
                String prediction = runModelInference(finalBitmap);

                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "تم التعرف: " + prediction, Toast.LENGTH_SHORT).show();

                    if (scanIndicator != null) {
                        scanIndicator.setAlpha(1f);
                        scanIndicator.animate().alpha(0f).setDuration(500).start();
                    }

                    // Speak only if prediction is NOT "لا شيء"
                    if (tts != null && !tts.isSpeaking()) {
                        if (!prediction.contains("لم يتم التعرف")) {
                            String spokenText = prediction.split(" \\(")[0]; // remove confidence %
                            tts.speak(spokenText, TextToSpeech.QUEUE_FLUSH, null, "ScanResultID");
                        }
                    }
                });
            } catch (Exception e) {
                Log.e("MainActivity", "فشل الاستدلال: " + e.getMessage());
                e.printStackTrace();
            }
        });

        imageProxy.close();
    }

    private String runModelInference(Bitmap bitmap) {
        Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, true);
        float[][][][] input = new float[1][MODEL_INPUT_SIZE][MODEL_INPUT_SIZE][3];

        for (int y = 0; y < MODEL_INPUT_SIZE; y++) {
            for (int x = 0; x < MODEL_INPUT_SIZE; x++) {
                int pixel = resizedBitmap.getPixel(x, y);
                input[0][y][x][0] = ((pixel >> 16) & 0xFF) / 255.f;
                input[0][y][x][1] = ((pixel >> 8) & 0xFF) / 255.f;
                input[0][y][x][2] = (pixel & 0xFF) / 255.f;
            }
        }

        float[][] output = new float[1][labels.length];
        tflite.run(input, output);

        Log.d("TFLite", "Model output: " + Arrays.toString(output[0]));

        int maxIdx = 0;
        float maxConf = 0f;
        for (int i = 0; i < labels.length; i++) {
            if (output[0][i] > maxConf) {
                maxConf = output[0][i];
                maxIdx = i;
            }
        }

        String resultLabel = labels[maxIdx];
        float confidence = maxConf;

        if (resultLabel.equalsIgnoreCase("لا شيء") || confidence < 0.75f) {
            return "لم يتم التعرف على الورقة نقدية";
        }

        return resultLabel + String.format(" (%.1f%%)", confidence * 100);
    }

    private Bitmap rotateBitmap(Bitmap bitmap, int rotationDegrees) {
        if (rotationDegrees == 0) return bitmap;
        Matrix matrix = new Matrix();
        matrix.postRotate(rotationDegrees);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
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
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tflite != null) tflite.close();
        if (cameraExecutor != null) cameraExecutor.shutdownNow();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }
}
