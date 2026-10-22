package com.p9filmshare.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

/**
 * 极简扫码页：Camera2 取流 + zxing core 解码。无第三方扫码库依赖。
 * 仅识别 QR。识别成功后把文本内容通过 setResult 返回。
 */
public class ScanActivity extends Activity {

    private TextureView textureView;
    private TextView tvHint;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread bgThread;
    private Handler bgHandler;

    private final MultiFormatReader reader = new MultiFormatReader();
    private volatile boolean scanning = true;
    private volatile boolean surfaceReady = false;
    private String cameraId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        root.setLayoutParams(fp);

        textureView = new TextureView(this);
        root.addView(textureView);

        tvHint = new TextView(this);
        tvHint.setText("将鸿蒙设备上的二维码放入框内");
        tvHint.setTextSize(16);
        tvHint.setGravity(Gravity.CENTER);
        tvHint.setPadding(0, 0, 0, 0);
        tvHint.setTextColor(0xFFFFFFFF);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        hp.gravity = Gravity.BOTTOM;
        hp.bottomMargin = dp(48);
        tvHint.setLayoutParams(hp);
        root.addView(tvHint);

        Button btnCancel = new Button(this);
        btnCancel.setText("取消");
        btnCancel.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        cp.gravity = Gravity.TOP | Gravity.END;
        cp.topMargin = dp(16);
        cp.rightMargin = dp(16);
        btnCancel.setLayoutParams(cp);
        root.addView(btnCancel);

        setContentView(root);

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        reader.setHints(hints);

        textureView.setSurfaceTextureListener(surfaceListener);

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startBackground();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
        }
    }

    private final TextureView.SurfaceTextureListener surfaceListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int w, int h) {
                    surfaceReady = true;
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        openCamera();
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int w, int h) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    surfaceReady = false;
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                }
            };

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == 1 && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startBackground();
            if (surfaceReady) openCamera();
        } else {
            Toast.makeText(this, "需要相机权限才能扫码", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void startBackground() {
        bgThread = new HandlerThread("scan-bg");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
    }

    private void openCamera() {
        // 卓易通容器里 CAMERA_SERVICE 可能为 null 或不可用：一律兜住，绝不崩溃
        try {
            CameraManager mgr = (CameraManager) getSystemService(CAMERA_SERVICE);
            if (mgr == null) {
                fail("本环境没有可用的相机服务，请改用「自动发现并接收」");
                return;
            }
            cameraId = null;
            for (String id : mgr.getCameraIdList()) {
                Integer facing = mgr.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    break;
                }
            }
            if (cameraId == null) {
                String[] ids = mgr.getCameraIdList();
                if (ids.length > 0) cameraId = ids[0];
            }
            if (cameraId == null) {
                fail("没有找到摄像头，请改用「自动发现并接收」");
                return;
            }
            if (bgHandler == null) startBackground();
            mgr.openCamera(cameraId, stateCallback, bgHandler);
        } catch (Throwable e) {
            fail("相机打开失败：" + e + "\n请改用「自动发现并接收」");
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            createSession();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            closeCamera();
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            closeCamera();
        }
    };

    private void createSession() {
        if (cameraDevice == null || !surfaceReady) return;
        try {
            SurfaceTexture st = textureView.getSurfaceTexture();
            if (st == null) return;
            st.setDefaultBufferSize(640, 480);
            Surface previewSurface = new Surface(st);

            Size analyze = new Size(640, 480);
            imageReader = ImageReader.newInstance(
                    analyze.getWidth(), analyze.getHeight(), ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(readerListener, bgHandler);

            cameraDevice.createCaptureSession(
                    Arrays.asList(previewSurface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                CaptureRequest.Builder b = cameraDevice.createCaptureRequest(
                                        CameraDevice.TEMPLATE_PREVIEW);
                                b.addTarget(previewSurface);
                                b.addTarget(imageReader.getSurface());
                                session.setRepeatingRequest(b.build(), null, bgHandler);
                            } catch (CameraAccessException e) {
                                fail("预览失败：" + e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            fail("相机配置失败");
                        }
                    }, bgHandler);
        } catch (CameraAccessException e) {
            fail("相机配置失败：" + e.getMessage());
        }
    }

    private final ImageReader.OnImageAvailableListener readerListener = readerRef -> {
        if (!scanning) return;
        Image image = readerRef.acquireLatestImage();
        if (image == null) return;
        try {
            decodeFrame(image);
        } catch (Throwable ignored) {
            // 必须 catch Throwable：NoSuchMethodError 等 Error 不属于 Exception，
            // 漏接会直接杀掉扫描线程并导致闪退。
        } finally {
            image.close();
        }
    };

    private void decodeFrame(Image image) {
        Image.Plane yPlane = image.getPlanes()[0];
        ByteBuffer yBuf = yPlane.getBuffer();
        int rowStride = yPlane.getRowStride();
        int width = image.getWidth();
        int height = image.getHeight();
        if (rowStride < width) rowStride = width;
        byte[] yData = new byte[width * height];
        int limit = yBuf.limit();
        for (int y = 0; y < height; y++) {
            int off = y * rowStride;
            if (off + width > limit) break;   // 不同 ROM 的 stride/padding 差异，越界就截断
            yBuf.position(off);
            yBuf.get(yData, y * width, width);
        }
        PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(
                yData, width, height, 0, 0, width, height, false);
        BinaryBitmap bb = new BinaryBitmap(new HybridBinarizer(src));
        try {
            Result res = reader.decodeWithState(bb);
            if (res != null && res.getText() != null && !res.getText().isEmpty()) {
                scanning = false;
                returnResult(res.getText());
            }
        } catch (Throwable ignored) {
            // 暂未识别 / 解码器内部错误，继续下一帧（勿用 catch Exception，接不住 Error）
        }
    }

    private void returnResult(String text) {
        Intent i = new Intent();
        i.putExtra("text", text);
        setResult(RESULT_OK, i);
        runOnUiThread(this::finish);
    }

    private void fail(String msg) {
        runOnUiThread(() -> {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            finish();
        });
    }

    private void closeCamera() {
        try {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
        } catch (Exception ignored) {
        }
        try {
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
        } catch (Exception ignored) {
        }
        try {
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        closeCamera();
        if (bgThread != null) {
            try {
                bgThread.quitSafely();
                bgThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
