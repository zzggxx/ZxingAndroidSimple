/*
 * Copyright (C) 2010 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.zxing.client.android;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.client.android.camera.open.OpenCamera;
import com.google.zxing.common.HybridBinarizer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;

final class DecodeHandler extends Handler {

    private static final String TAG = DecodeHandler.class.getSimpleName();

    private final CaptureActivity activity;
    private final MultiFormatReader multiFormatReader;
    private boolean running = true;
    private OpenCamera mCamera;

    DecodeHandler(CaptureActivity activity, Map<DecodeHintType, Object> hints, OpenCamera camera) {
        mCamera = camera;
        multiFormatReader = new MultiFormatReader();
        multiFormatReader.setHints(hints);
        this.activity = activity;
    }

    @Override
    public void handleMessage(Message message) {
        if (message == null || !running) {
            return;
        }
        Log.i(TAG, "handleMessage: 发送消息处");//由 onPreviewFrame 回调回来的.
        switch (message.what) {
            case R.id.decode:
                decode((byte[]) message.obj, message.arg1, message.arg2);//后边参数是照片的宽高,实测是屏幕的宽高
                break;
            case R.id.quit:
                running = false;
                Looper.myLooper().quit();
                break;
        }
    }

    /**
     * Decode the data within the viewfinder rectangle, and time how long it took. For efficiency,
     * reuse the same reader objects from one decode to the next.
     * <p>
     * 解码取景器矩形内的数据，以及所用时间。为了提高效率，将相同的读取器对象从一个解码器重用到另一个读取器。
     *
     * @param data   The YUV preview frame.
     * @param width  The width of the preview frame.
     * @param height The height of the preview frame.
     */
    ByteArrayOutputStream baos;

    private void decode(byte[] data, int width, int height) {

        /*--------------------------------------------------------------------*/
//        拿到每一帧的图片进行保存的逻辑
        byte[] rawImage;
        Camera.Size previewSize = mCamera.getCamera().getParameters().getPreviewSize();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        YuvImage yuvImage = new YuvImage(data, ImageFormat.NV21, previewSize.width, previewSize.height, null);
        baos = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, previewSize.width, previewSize.height), 100, baos);
        rawImage = baos.toByteArray();
        Bitmap bitmap1 = arrayByteConvertoBitmap(rawImage, null);
        if (bitmap1 != null) {
//            保存扫描得来的图片.
//            saveBitmap(bitmap1);
        } else {
            Log.i(TAG, "decode: bitmap is null");
        }
        /*--------------------------------------------------------------------*/


        /*-----------------------猜测:底层算法验证是否包含了二维码的rawResult-----------------------------------*/
        long start = System.currentTimeMillis();
        Result rawResult = null;
        PlanarYUVLuminanceSource source = activity.getCameraManager().buildLuminanceSource(data, width, height);
        if (source != null) {
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            try {
                rawResult = multiFormatReader.decodeWithState(bitmap);
            } catch (ReaderException re) {
                // continue
            } finally {
                multiFormatReader.reset();
            }
        }

        /*--------------直接消息发送-------------------*/
        Handler handler = activity.getHandler();//又发送到CaptureActivity的CaptureActivityHandler中
        if (rawResult != null) {
            // Don't log the barcode contents for security.
            long end = System.currentTimeMillis();
            Log.d(TAG, "Found barcode in " + (end - start) + " ms");
            if (handler != null) {

                Message message = Message.obtain(handler, R.id.decode_succeeded, rawResult);
                Bundle bundle = new Bundle();
                bundleThumbnail(source, bundle);
                message.setData(bundle);
                message.sendToTarget();
            }
        } else {
            if (handler != null) {
                Message message = Message.obtain(handler, R.id.decode_failed);   //没有得到想要的图片继续进行获取预览图
                message.sendToTarget();
            }
        }
    }

    /*--------------在bundle中设置图片缩率图的二维码信息------------------------*/
    private static void bundleThumbnail(PlanarYUVLuminanceSource source, Bundle bundle) {

        int[] pixels = source.renderThumbnail();

        int width = source.getThumbnailWidth();
        int height = source.getThumbnailHeight();

        Bitmap bitmap = Bitmap.createBitmap(pixels, 0, width, width, height, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 50, out);

        bundle.putByteArray(DecodeThread.BARCODE_BITMAP, out.toByteArray());
        bundle.putFloat(DecodeThread.BARCODE_SCALED_FACTOR, (float) width / source.getWidth());
    }

    public Bitmap arrayByteConvertoBitmap(byte[] bytes, BitmapFactory.Options opts) {

        if (bytes != null) {
            if (opts != null) {
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            } else {
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            }
        }

        return null;
    }

    public void saveBitmap(Bitmap bm) {

        String bitmapName = "BITMAP_" + System.currentTimeMillis() + ".jpeg";
        File f = new File(activity.getExternalCacheDir(), bitmapName);
        if (f.exists()) {
            f.delete();
        }
        try {
            FileOutputStream out = new FileOutputStream(f);
            bm.compress(Bitmap.CompressFormat.JPEG, 90, out);
            out.flush();
            out.close();
            Log.i(TAG, "bitmap save success__" + bitmapName);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            Log.i(TAG, "bitmap save failed__" + bitmapName);
        } catch (IOException e) {
            e.printStackTrace();
            Log.i(TAG, "bitmap save failed__" + bitmapName);
        }
    }

}
