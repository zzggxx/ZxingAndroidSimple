/*
 * Copyright (C) 2008 ZXing authors
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

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.Browser;
import android.util.Log;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.DecodeHintType;
import com.google.zxing.Result;
import com.google.zxing.client.android.camera.CameraManager;

import java.util.Collection;
import java.util.Map;

/**
 * This class handles all the messaging which comprises the state machine for capture.
 * <p>
 * 这个类处理包含用于捕获的状态机的所有消息传递
 *
 * @author dswitkin@google.com (Daniel Switkin)
 */
public final class CaptureActivityHandler extends Handler {

    private static final String TAG = CaptureActivityHandler.class.getSimpleName();

    private final CaptureActivity activity;
    private final DecodeThread decodeThread;
    private State state;
    private final CameraManager cameraManager;

    private enum State {
        PREVIEW,
        SUCCESS,
        DONE
    }

    CaptureActivityHandler(CaptureActivity activity,
                           Collection<BarcodeFormat> decodeFormats,
                           Map<DecodeHintType, ?> baseHints,
                           String characterSet,
                           CameraManager cameraManager) {

        this.activity = activity;

//        处理解码任务的,单起线程去扫描二维码
        decodeThread = new DecodeThread(
                activity, decodeFormats, baseHints, characterSet,
                new ViewfinderResultPointCallback(activity.getViewfinderView()),cameraManager.getCamera());
        decodeThread.start();

        state = State.SUCCESS;

        // Start ourselves capturing previews and decoding.开启拍摄预览和解码
        this.cameraManager = cameraManager;

        //开启相机预览
        // 在startPreview方法执行之后，SurfaceView才真的开始显示照相机内容
        cameraManager.startPreview();

        //重开始预览和解码,重要方法.这里边的逻辑主要是为了拿到回调的帧.和视频的预览是没有关系的
        restartPreviewAndDecode();
    }

    @Override
    public void handleMessage(Message message) {
        switch (message.what) {
            case R.id.restart_preview:
                Log.i(TAG, "handleMessage: restart_preview");
                restartPreviewAndDecode();
                break;
            case R.id.decode_succeeded:
                Log.i(TAG, "handleMessage: decode_succeeded");

                state = State.SUCCESS;

                Bundle bundle = message.getData();
                Bitmap barcode = null;
                float scaleFactor = 1.0f;

                if (bundle != null) {
                    byte[] compressedBitmap = bundle.getByteArray(DecodeThread.BARCODE_BITMAP);
                    if (compressedBitmap != null) {
                        barcode = BitmapFactory.decodeByteArray(compressedBitmap,
                                0, compressedBitmap.length, null);
                        // Mutable copy:拷贝副本
                        barcode = barcode.copy(Bitmap.Config.ARGB_8888, true);
                    }
                    scaleFactor = bundle.getFloat(DecodeThread.BARCODE_SCALED_FACTOR);
                }

                activity.handleDecode((Result) message.obj, barcode, scaleFactor);

                break;
            case R.id.decode_failed:
                Log.i(TAG, "handleMessage: decode_failed--------");
                // We're decoding as fast as possible, so when one decode fails, start another.
                state = State.PREVIEW;
                //这里注意每次只是回调一帧数据,需要重复的设置才行.
                cameraManager.requestPreviewFrame(decodeThread.getHandler(), R.id.decode);
                break;
            case R.id.return_scan_result:
                Log.i(TAG, "handleMessage: return_scan_result");
                activity.setResult(Activity.RESULT_OK, (Intent) message.obj);
                activity.finish();
                break;
            case R.id.launch_product_query:
                Log.i(TAG, "handleMessage: launch_product_query");
                String url = (String) message.obj;

                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.addFlags(Intents.FLAG_NEW_DOC);
                intent.setData(Uri.parse(url));

                ResolveInfo resolveInfo =
                        activity.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
                String browserPackageName = null;
                if (resolveInfo != null && resolveInfo.activityInfo != null) {
                    browserPackageName = resolveInfo.activityInfo.packageName;
                    Log.d(TAG, "Using browser in package " + browserPackageName);
                }

                // Needed for default Android browser / Chrome only apparently
                if (browserPackageName != null) {
                    switch (browserPackageName) {
                        case "com.android.browser":
                        case "com.android.chrome":
                            intent.setPackage(browserPackageName);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            intent.putExtra(Browser.EXTRA_APPLICATION_ID, browserPackageName);
                            break;
                    }
                }

                try {
                    activity.startActivity(intent);
                } catch (ActivityNotFoundException ignored) {
                    Log.w(TAG, "Can't find anything to handle VIEW of URI " + url);
                }
                break;
        }
    }

    public void quitSynchronously() {
        state = State.DONE;
        cameraManager.stopPreview();
        Message quit = Message.obtain(decodeThread.getHandler(), R.id.quit);
        quit.sendToTarget();
        try {
            // Wait at most half a second; should be enough time, and onPause() will timeout quickly
            decodeThread.join(500L);
        } catch (InterruptedException e) {
            // continue
        }

        // Be absolutely sure we don't send any queued up messages
        removeMessages(R.id.decode_succeeded);
        removeMessages(R.id.decode_failed);
    }

    private void restartPreviewAndDecode() {

        if (state == State.SUCCESS) {
            state = State.PREVIEW;// State.SUCCESS是开始的时候认为的设定,此处开始预览了
            cameraManager.requestPreviewFrame(decodeThread.getHandler(), R.id.decode);

            //重新绘制蓝色边缘矩形、扫描线等
            activity.drawViewfinder();
        }
    }

}
