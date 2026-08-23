package com.anzhi.core;

import android.anzhi.IAnzhiCoreService;
import android.app.Service;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.hardware.input.InputManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

public class AnzhiCoreService extends Service {

    private static final String TAG = "AnzhiCore";

    private final IBinder mBinder = new IAnzhiCoreService.Stub() {

        @Override
        public ParcelFileDescriptor captureScreen(int displayId) {
            try {
                IBinder displayToken;
                if (displayId == 0) {
                    displayToken = SurfaceControl.getInternalDisplayToken();
                } else {
                    displayToken = SurfaceControl.getPhysicalDisplayToken(displayId);
                }
                if (displayToken == null) {
                    Log.e(TAG, "captureScreen: displayToken null for display " + displayId);
                    return null;
                }
                int width = getDisplayWidth();
                int height = getDisplayHeight();
                SurfaceControl.DisplayCaptureArgs captureArgs =
                        new SurfaceControl.DisplayCaptureArgs.Builder(displayToken)
                                .setSize(width, height)
                                .build();
                SurfaceControl.ScreenshotHardwareBuffer buffer =
                        SurfaceControl.captureDisplay(captureArgs);
                if (buffer == null) {
                    Log.e(TAG, "captureScreen: buffer is null");
                    return null;
                }
                Bitmap bitmap = buffer.asBitmap();
                if (bitmap == null) {
                    Log.e(TAG, "captureScreen: bitmap is null");
                    return null;
                }
                File tmpFile = new File(getCacheDir(),
                        "screenshot_" + System.nanoTime() + ".png");
                try (FileOutputStream out = new FileOutputStream(tmpFile)) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                return ParcelFileDescriptor.open(tmpFile,
                        ParcelFileDescriptor.MODE_READ_ONLY);
            } catch (Exception e) {
                Log.e(TAG, "captureScreen failed", e);
                return null;
            }
        }

        @Override
        public boolean injectInputEvent(int action, int x, int y, int displayId) {
            try {
                long downTime = SystemClock.uptimeMillis();
                MotionEvent event = MotionEvent.obtain(
                        downTime, downTime, action, x, y, 0);
                event.setDisplayId(displayId);
                return InputManager.getInstance().injectInputEvent(event,
                        InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            } catch (Exception e) {
                Log.e(TAG, "injectInputEvent failed", e);
                return false;
            }
        }

        @Override
        public boolean injectKeyEvent(int keyCode, boolean down) {
            try {
                long downTime = SystemClock.uptimeMillis();
                KeyEvent event = new KeyEvent(downTime, downTime,
                        down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
                        keyCode, 0);
                return InputManager.getInstance().injectInputEvent(event,
                        InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            } catch (Exception e) {
                Log.e(TAG, "injectKeyEvent failed", e);
                return false;
            }
        }

        @Override
        public boolean injectSwipe(int x1, int y1, int x2, int y2, int durationMs) {
            try {
                long downTime = SystemClock.uptimeMillis();
                int steps = Math.max(1, durationMs / 10);

                MotionEvent.PointerProperties[] props = {
                        new MotionEvent.PointerProperties()
                };
                props[0].id = 0;
                props[0].toolType = MotionEvent.TOOL_TYPE_FINGER;

                MotionEvent.PointerCoords[] coords = {
                        new MotionEvent.PointerCoords()
                };
                coords[0].x = x1;
                coords[0].y = y1;

                // ACTION_DOWN
                MotionEvent down = MotionEvent.obtain(
                        downTime, downTime,
                        MotionEvent.ACTION_DOWN, 1, props, coords,
                        0, 0, 1, 1, 0, 0, 0, 0);
                InputManager.getInstance().injectInputEvent(down,
                        InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);

                // ACTION_MOVE interpolated
                for (int i = 1; i <= steps; i++) {
                    float t = (float) i / steps;
                    coords[0].x = x1 + (x2 - x1) * t;
                    coords[0].y = y1 + (y2 - y1) * t;
                    long now = downTime + (long) (durationMs * t);
                    MotionEvent move = MotionEvent.obtain(
                            downTime, now,
                            MotionEvent.ACTION_MOVE, 1, props, coords,
                            0, 0, 1, 1, 0, 0, 0, 0);
                    InputManager.getInstance().injectInputEvent(move,
                            InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        break;
                    }
                }

                // ACTION_UP
                coords[0].x = x2;
                coords[0].y = y2;
                MotionEvent up = MotionEvent.obtain(
                        downTime, downTime + durationMs,
                        MotionEvent.ACTION_UP, 1, props, coords,
                        0, 0, 1, 1, 0, 0, 0, 0);
                return InputManager.getInstance().injectInputEvent(up,
                        InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            } catch (Exception e) {
                Log.e(TAG, "injectSwipe failed", e);
                return false;
            }
        }

        @Override
        public int getDisplayWidth() {
            try {
                WindowManager wm = getSystemService(WindowManager.class);
                Display display = wm.getDefaultDisplay();
                Point size = new Point();
                display.getRealSize(size);
                return size.x;
            } catch (Exception e) {
                Log.e(TAG, "getDisplayWidth failed", e);
                return 0;
            }
        }

        @Override
        public int getDisplayHeight() {
            try {
                WindowManager wm = getSystemService(WindowManager.class);
                Display display = wm.getDefaultDisplay();
                Point size = new Point();
                display.getRealSize(size);
                return size.y;
            } catch (Exception e) {
                Log.e(TAG, "getDisplayHeight failed", e);
                return 0;
            }
        }

        @Override
        public int getRotation() {
            try {
                WindowManager wm = getSystemService(WindowManager.class);
                return wm.getDefaultDisplay().getRotation();
            } catch (Exception e) {
                Log.e(TAG, "getRotation failed", e);
                return 0;
            }
        }

        @Override
        public boolean moveTaskToDisplay(int taskId, int displayId) {
            try {
                ActivityTaskManager.getService()
                        .moveTaskToDisplay(taskId, displayId);
                return true;
            } catch (RemoteException e) {
                Log.e(TAG, "moveTaskToDisplay failed", e);
                return false;
            }
        }

        @Override
        public String getSystemProperty(String key, String defaultValue) {
            try {
                return SystemProperties.get(key, defaultValue);
            } catch (Exception e) {
                Log.e(TAG, "getSystemProperty failed", e);
                return defaultValue;
            }
        }

        @Override
        public int[] getRunningTasks(int maxCount, int displayId) {
            try {
                var tasks = ActivityTaskManager.getService().getTasks(maxCount, 0);
                int[] taskIds = new int[tasks.size()];
                for (int i = 0; i < tasks.size(); i++) {
                    taskIds[i] = tasks.get(i).taskId;
                }
                return taskIds;
            } catch (RemoteException e) {
                Log.e(TAG, "getRunningTasks failed", e);
                return new int[0];
            }
        }

        @Override
        public boolean moveTaskToFront(int taskId) {
            try {
                ActivityTaskManager.getService()
                        .moveTaskToFront(taskId, 0, null);
                return true;
            } catch (RemoteException e) {
                Log.e(TAG, "moveTaskToFront failed", e);
                return false;
            } catch (SecurityException e) {
                Log.e(TAG, "moveTaskToFront permission denied", e);
                return false;
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        ServiceManager.addService("anzhi_core", mBinder);
        Log.i(TAG, "AnzhiCoreService registered as 'anzhi_core'");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }
}
