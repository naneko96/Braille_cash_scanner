package com.example.brbscan;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.os.Build;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;

public class ImageUtils {
    private static final String TAG = "ImageUtils";

    @OptIn(markerClass = ExperimentalGetImage.class)
    public static Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        Image image = imageProxy.getImage();
        if (image == null) {
            Log.e(TAG, "Image is null");
            return null;
        }

        try {
            return convertYuvToRgbSafe(image);
        } catch (Exception e) {
            Log.e(TAG, "Conversion error", e);
            return null;
        } finally {
            image.close();
        }
    }

    private static Bitmap convertYuvToRgbSafe(Image image) throws Exception {
        if (image.getFormat() != ImageFormat.YUV_420_888) {
            throw new IllegalArgumentException("Unsupported image format");
        }

        int width = image.getWidth();
        int height = image.getHeight();
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[width * height];

        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();
        int uvRowStride = planes[1].getRowStride();
        int uvPixelStride = planes[1].getPixelStride();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int yIndex = y * yRowStride + x * yPixelStride;
                int uvX = x / 2;
                int uvY = y / 2;
                int uvIndex = uvY * uvRowStride + uvX * uvPixelStride;

                int yValue = safeGet(yBuffer, yIndex);
                int uValue = safeGet(uBuffer, uvIndex);
                int vValue = safeGet(vBuffer, uvIndex);

                // Convert YUV to RGB
                int r = yValue + (int)(1.402f * (vValue - 128));
                int g = yValue - (int)(0.344f * (uValue - 128)) - (int)(0.714f * (vValue - 128));
                int b = yValue + (int)(1.772f * (uValue - 128));

                // Clamp
                r = Math.min(255, Math.max(0, r));
                g = Math.min(255, Math.max(0, g));
                b = Math.min(255, Math.max(0, b));

                pixels[y * width + x] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    private static int safeGet(ByteBuffer buffer, int index) {
        if (index < 0 || index >= buffer.limit()) {
            return 128; // Neutral for U/V, fallback for Y
        }
        return buffer.get(index) & 0xff;
    }
}
