package com.newslive.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "NewsLive.Boot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "收到广播: " + action);
        if (action == null) return;

        // 兼容各厂商的开机/唤醒广播
        if (action.equals(Intent.ACTION_BOOT_COMPLETED)
                || action.equals("android.intent.action.QUICKBOOT_POWERON")
                || action.equals("com.huawei.powergenie.manager.STATE_CHANGE")
                || action.equals("android.intent.action.LOCKED_BOOT_COMPLETED")
                || action.equals("android.intent.action.USER_PRESENT")
                || action.equals("android.intent.action.SCREEN_ON")
                || action.equals("com.android.launcher.action.INSTALL_SHORTCUT")) {
            launchMainActivity(context);
        }
    }

    /**
     * 延迟启动 MainActivity，避免开机早期系统资源紧张被杀
     * 同时作为桌面应用，系统启动后应自动拉起默认桌面
     */
    private void launchMainActivity(final Context context) {
        final Handler handler = new Handler(Looper.getMainLooper());

        // 立即启动一次
        startMainActivity(context);

        // 延迟 3 秒再启动一次（应对系统未就绪导致首次启动失败）
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                startMainActivity(context);
            }
        }, 3000);

        // 延迟 10 秒再启动一次（确保系统完全就绪）
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                startMainActivity(context);
            }
        }, 10000);
    }

    private void startMainActivity(Context context) {
        try {
            Log.i(TAG, "启动 MainActivity, uptime=" + SystemClock.uptimeMillis());
            Intent startIntent = new Intent(context, MainActivity.class);
            startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(startIntent);
        } catch (Exception e) {
            Log.e(TAG, "启动 MainActivity 失败", e);
        }
    }
}
