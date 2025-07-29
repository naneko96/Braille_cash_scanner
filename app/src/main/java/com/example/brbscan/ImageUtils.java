package com.example.brbscan;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageProxy;

import java.io.ByteArrayOutputStream;
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
            // Convert YUV to RGB with better quality
            return convertYuvToRgb(image);
        } catch (Exception e) {
            Log.e(TAG, "Conversion error", e);
            return null;
        } finally {
            image.close();
        }
    }

    private static Bitmap convertYuvToRgb(Image image) throws Exception {
        if (image.getFormat() != ImageFormat.YUV_420_888) {
            throw new IllegalArgumentException("Invalid image format");
        }

        int width = image.getWidth();
        int height = image.getHeight();

        // Get the YUV planes
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        // Create ARGB bitmap
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[width * height];

        // Convert YUV to RGB (simplified conversion)
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int yValue = yBuffer.get(y * planes[0].getRowStride() + x) & 0xff;
                int uValue = uBuffer.get((y/2) * planes[1].getRowStride() + (x/2) * planes[1].getPixelStride()) & 0xff;
                int vValue = vBuffer.get((y/2) * planes[2].getRowStride() + (x/2) * planes[2].getPixelStride()) & 0xff;

                // Convert YUV to RGB
                int r = (int) (yValue + 1.402 * (vValue - 128));
                int g = (int) (yValue - 0.34414 * (uValue - 128) - 0.71414 * (vValue - 128));
                int b = (int) (yValue + 1.772 * (uValue - 128));

                // Clamp values
                r = Math.min(255, Math.max(0, r));
                g = Math.min(255, Math.max(0, g));
                b = Math.min(255, Math.max(0, b));

                pixels[y * width + x] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }
}