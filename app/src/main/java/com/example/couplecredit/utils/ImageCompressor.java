package com.example.couplecredit.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class ImageCompressor {

    public static byte[] compress(Context context, Uri imageUri, int maxDimension, int quality) {
        try (InputStream is = context.getContentResolver().openInputStream(imageUri)) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is, null, options);

            int inSampleSize = 1;
            if (options.outHeight > maxDimension || options.outWidth > maxDimension) {
                int halfHeight = options.outHeight / 2;
                int halfWidth = options.outWidth / 2;
                while ((halfHeight / inSampleSize) >= maxDimension
                        && (halfWidth / inSampleSize) >= maxDimension) {
                    inSampleSize *= 2;
                }
            }

            try (InputStream is2 = context.getContentResolver().openInputStream(imageUri)) {
                options.inJustDecodeBounds = false;
                options.inSampleSize = inSampleSize;
                Bitmap bitmap = BitmapFactory.decodeStream(is2, null, options);
                if (bitmap == null) return null;

                float widthScale = (float) maxDimension / bitmap.getWidth();
                float heightScale = (float) maxDimension / bitmap.getHeight();
                float scale = Math.min(widthScale, heightScale);
                if (scale < 1f) {
                    int newW = Math.round(bitmap.getWidth() * scale);
                    int newH = Math.round(bitmap.getHeight() * scale);
                    Bitmap scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true);
                    bitmap.recycle();
                    bitmap = scaled;
                }

                ByteArrayOutputStream baos = new ByteArrayOutputStream(bitmap.getWidth() * bitmap.getHeight() / 2);
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos);
                bitmap.recycle();
                return baos.toByteArray();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
