package com.example.brbscan;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
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
        try {
            Image image = imageProxy.getImage();
            if (image == null || image.getFormat() != ImageFormat.YUV_420_888) {
                Log.e(TAG, "Invalid image format or null image");
                return null;
            }

            int width = image.getWidth();
            int height = image.getHeight();

            ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
            ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
            ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

            byte[] yBytes = new byte[yBuffer.remaining()];
            yBuffer.get(yBytes);

            byte[] uBytes = new byte[uBuffer.remaining()];
            uBuffer.get(uBytes);

            byte[] vBytes = new byte[vBuffer.remaining()];
            vBuffer.get(vBytes);

            int uvLength = Math.min(uBytes.length, vBytes.length);
            int nv21Length = width * height + uvLength * 2;
            byte[] nv21 = new byte[nv21Length];

            // Y data
            System.arraycopy(yBytes, 0, nv21, 0, yBytes.length);

            // Interleave VU (not UV) for NV21
            for (int i = 0; i < uvLength && (yBytes.length + (i * 2) + 1) < nv21.length; i++) {
                nv21[yBytes.length + (i * 2)] = vBytes[i];
                nv21[yBytes.length + (i * 2) + 1] = uBytes[i];
            }

            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, width, height), 100, out);
            byte[] jpegBytes = out.toByteArray();

            return android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);

        } catch (Exception e) {
            Log.e(TAG, "Conversion error: " + e.getMessage(), e);
            return null;
        }
    }
}
