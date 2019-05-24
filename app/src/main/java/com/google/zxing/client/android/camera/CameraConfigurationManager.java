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

package com.google.zxing.client.android.camera;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.hardware.Camera;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import com.google.zxing.client.android.PreferencesActivity;
import com.google.zxing.client.android.Utils.CameraConfigurationUtils;
import com.google.zxing.client.android.camera.open.CameraFacing;
import com.google.zxing.client.android.camera.open.OpenCamera;

/**
 * A class which deals with reading, parsing, and setting the camera parameters which are used to
 * configure the camera hardware.
 * <p>
 * 一个处理读取、解析和设置用于配置摄像机硬件的摄像机参数的类
 */
@SuppressWarnings("deprecation") // camera APIs
final class CameraConfigurationManager {

    private static final String TAG = "CameraConfiguration";

    private final Context context;
    private int cwNeededRotation;//从显示到相机顺时针旋转的角度
    private int cwRotationFromDisplayToCamera;//最终展示的角度
    private Point screenResolution;//屏幕分辨率
    private Point cameraResolution;//照相机分辨率
    private Point bestPreviewSize;//最合适的预览尺寸
    private Point previewSizeOnScreen;//屏幕上的预览大小

    CameraConfigurationManager(Context context) {
        this.context = context;
    }

    /**
     * 相机的参数设置,只需一次即可
     * <p>
     * Reads, one time, values from the camera that are needed by the app.
     */
    void initFromCameraParameters(OpenCamera camera) {

//        屏幕的放置方向和预览方向
        Camera.Parameters parameters = camera.getCamera().getParameters();
        WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = manager.getDefaultDisplay();

        int displayRotation = display.getRotation();
        int cwRotationFromNaturalToDisplay; //需要展示的方向
        switch (displayRotation) {
            case Surface.ROTATION_0:
                cwRotationFromNaturalToDisplay = 0;
                break;
            case Surface.ROTATION_90:
                cwRotationFromNaturalToDisplay = 90;
                break;
            case Surface.ROTATION_180:
                cwRotationFromNaturalToDisplay = 180;
                break;
            case Surface.ROTATION_270:
                cwRotationFromNaturalToDisplay = 270;
                break;
            default:
                // Have seen this return incorrect values like -90
                if (displayRotation % 90 == 0) {
                    cwRotationFromNaturalToDisplay = (360 + displayRotation) % 360;
                } else {
                    throw new IllegalArgumentException("Bad rotation: " + displayRotation);
                }
        }
        Log.i(TAG, "Display at: " + cwRotationFromNaturalToDisplay);

        //摄像机(传感器)的方向,测试得到后置是90度,前置是270度.
        int cwRotationFromNaturalToCamera = camera.getOrientation();
        Log.i(TAG, "Camera at: " + cwRotationFromNaturalToCamera);

        // Still not 100% sure about this. But acts like we need to flip this:
        // 对此仍不能百分之百肯定。但好像我们需要翻转这个
        if (camera.getFacing() == CameraFacing.FRONT) {

//            前置摄像头镜像了,90 <----> 270
            cwRotationFromNaturalToCamera = (360 - cwRotationFromNaturalToCamera) % 360;

            Log.i(TAG, "Front camera overriden to: " + cwRotationFromNaturalToCamera);//overriden 翻转
        }


    /*
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
    String overrideRotationString;
    if (camera.getFacing() == CameraFacing.FRONT) {
      overrideRotationString = prefs.getString(PreferencesActivity.KEY_FORCE_CAMERA_ORIENTATION_FRONT, null);
    } else {
      overrideRotationString = prefs.getString(PreferencesActivity.KEY_FORCE_CAMERA_ORIENTATION, null);
    }
    if (overrideRotationString != null && !"-".equals(overrideRotationString)) {
      Log.i(TAG, "Overriding camera manually to " + overrideRotationString);
      cwRotationFromNaturalToCamera = Integer.parseInt(overrideRotationString);
    }
     */

//    计算设置相机的渲染需要旋转的角度,后边需要设置.以后就参照这个办法设置相机的角度问题
//        相机传感器角度 - 屏幕的方向

        cwRotationFromDisplayToCamera =
                (360 + cwRotationFromNaturalToCamera - cwRotationFromNaturalToDisplay) % 360;
        Log.i(TAG, "Final display orientation: " + cwRotationFromDisplayToCamera);

        /*-----------------todo:以上是预览的角度问题------------以下是预览的尺寸问题--------------*/
        if (camera.getFacing() == CameraFacing.FRONT) {
            Log.i(TAG, "Compensating rotation for front camera");
            cwNeededRotation = (360 - cwRotationFromDisplayToCamera) % 360;
        } else {
            cwNeededRotation = cwRotationFromDisplayToCamera;
        }
        Log.i(TAG, "Clockwise rotation from display to camera: " + cwNeededRotation);


// TODO: 2019/5/23  非全屏的的预览情况下需要写上实际的尺寸.
        Point theScreenResolution = new Point();
        display.getSize(theScreenResolution);

        screenResolution = theScreenResolution;
        Log.i(TAG, "Screen resolution in current orientation: " + screenResolution);

        cameraResolution = CameraConfigurationUtils.findBestPreviewSizeValue(parameters, screenResolution);
        Log.i(TAG, "Camera resolution: " + cameraResolution);

        /*-------todo:最佳的预览尺寸,参数zxing-->android core中的算法,拿出来之后看屏幕的方向再设置----------*/
        bestPreviewSize = CameraConfigurationUtils.findBestPreviewSizeValue(parameters, screenResolution);
        Log.i(TAG, "Best available preview size: " + bestPreviewSize);

        boolean isScreenPortrait = screenResolution.x < screenResolution.y;//竖屏
        boolean isPreviewSizePortrait = bestPreviewSize.x < bestPreviewSize.y;

        if (isScreenPortrait == isPreviewSizePortrait) {
            previewSizeOnScreen = bestPreviewSize;
        } else {
            previewSizeOnScreen = new Point(bestPreviewSize.y, bestPreviewSize.x);
        }
        Log.i(TAG, "Preview size on screen: " + previewSizeOnScreen);
    }

    /**
     * 设置摄像头参数
     *
     * @param camera
     * @param safeMode
     */
    void setDesiredCameraParameters(OpenCamera camera, boolean safeMode) {

        Camera theCamera = camera.getCamera();
        Camera.Parameters parameters = theCamera.getParameters();

        if (parameters == null) {
            Log.w(TAG, "Device error: no camera parameters are available. Proceeding without configuration.");
            return;
        }

        Log.i(TAG, "Initial camera parameters: " + parameters.flatten());

        if (safeMode) {
            Log.w(TAG, "In camera config safe mode -- most settings will not be honored");
        }

//        拿到apk的包名的sp文件
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

//        设置亮度和曝光度
        initializeTorch(parameters, prefs, safeMode);

//        设置是否自动对焦
        CameraConfigurationUtils.setFocus(
                parameters,
                prefs.getBoolean(PreferencesActivity.KEY_AUTO_FOCUS, true),
                prefs.getBoolean(PreferencesActivity.KEY_DISABLE_CONTINUOUS_FOCUS, true),
                safeMode);

//        非安全模式下设置的
        if (!safeMode) {
            if (prefs.getBoolean(PreferencesActivity.KEY_INVERT_SCAN, false)) {
                CameraConfigurationUtils.setInvertColor(parameters);//反色
            }

            if (!prefs.getBoolean(PreferencesActivity.KEY_DISABLE_BARCODE_SCENE_MODE, true)) {
                CameraConfigurationUtils.setBarcodeSceneMode(parameters);//旋转
            }

            if (!prefs.getBoolean(PreferencesActivity.KEY_DISABLE_METERING, true)) {
                CameraConfigurationUtils.setVideoStabilization(parameters);//多个扫描
                CameraConfigurationUtils.setFocusArea(parameters);
                CameraConfigurationUtils.setMetering(parameters);
            }

            //SetRecordingHint to true also a workaround for low framerate on Nexus 4
            //https://stackoverflow.com/questions/14131900/extreme-camera-lag-on-nexus-4
            parameters.setRecordingHint(true);

        }

        // TODO: 2019/5/22 方向和合理的预览尺寸是重点需要关注的东西
        //设置预览的最佳尺寸,应该是在预览之前,若是已经开始预览则需要停止预览再改变尺寸.
        parameters.setPreviewSize(bestPreviewSize.x, bestPreviewSize.y);

        //上边其实都是改变了parameters,这里重新设置.
        theCamera.setParameters(parameters);

        //设置相机的预览角度.
        theCamera.setDisplayOrientation(cwRotationFromDisplayToCamera);



        /*----------------------------------修正后的参数-------------------------------------*/
        Camera.Parameters afterParameters = theCamera.getParameters();
        Camera.Size afterSize = afterParameters.getPreviewSize();

        if (afterSize != null && (bestPreviewSize.x != afterSize.width || bestPreviewSize.y != afterSize.height)) {
            Log.w(TAG, "Camera said it supported preview size " + bestPreviewSize.x + 'x' + bestPreviewSize.y +
                    ", but after setting it, preview size is " + afterSize.width + 'x' + afterSize.height);
            bestPreviewSize.x = afterSize.width;
            bestPreviewSize.y = afterSize.height;
        }
    }

    Point getBestPreviewSize() {
        return bestPreviewSize;
    }

    Point getPreviewSizeOnScreen() {
        return previewSizeOnScreen;
    }

    Point getCameraResolution() {
        return cameraResolution;
    }

    Point getScreenResolution() {
        return screenResolution;
    }

    int getCWNeededRotation() {
        return cwNeededRotation;
    }

    boolean getTorchState(Camera camera) {
        if (camera != null) {
            Camera.Parameters parameters = camera.getParameters();
            if (parameters != null) {
                String flashMode = parameters.getFlashMode();
                return
                        Camera.Parameters.FLASH_MODE_ON.equals(flashMode) ||
                                Camera.Parameters.FLASH_MODE_TORCH.equals(flashMode);
            }
        }
        return false;
    }

    void setTorch(Camera camera, boolean newSetting) {
        Camera.Parameters parameters = camera.getParameters();
        doSetTorch(parameters, newSetting, false);
        camera.setParameters(parameters);
    }

    private void initializeTorch(Camera.Parameters parameters, SharedPreferences prefs, boolean safeMode) {

        boolean currentSetting = FrontLightMode.readPref(prefs) == FrontLightMode.ON;
        doSetTorch(parameters, currentSetting, safeMode);

    }

    private void doSetTorch(Camera.Parameters parameters, boolean newSetting, boolean safeMode) {

        CameraConfigurationUtils.setTorch(parameters, newSetting);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        if (!safeMode && !prefs.getBoolean(PreferencesActivity.KEY_DISABLE_EXPOSURE, true)) {
            CameraConfigurationUtils.setBestExposure(parameters, newSetting);
        }
    }

}
