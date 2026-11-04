package com.newslive.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.ContextThemeWrapper;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局崩溃捕获器：任何未捕获异常都会
 * 1. 落盘保存到 Android/data/com.newslive.app/files/crash/
 * 2. 直接在屏幕上弹出完整堆栈（车机等无法连电脑的场景，截图即可反馈）
 * 用户选择"重启应用"或"退出"后才结束进程。
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static volatile boolean installed = false;

    private final Context appContext;
    private final Thread.UncaughtExceptionHandler defaultHandler;
    private boolean handling = false;

    private CrashHandler(Context appContext, Thread.UncaughtExceptionHandler defaultHandler) {
        this.appContext = appContext;
        this.defaultHandler = defaultHandler;
    }

    /** 在应用入口（Activity onCreate 第一行）调用，越早安装越能抓到启动期崩溃 */
    public static void install(Context context) {
        if (installed) return;
        Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        if (def instanceof CrashHandler) return;
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(context.getApplicationContext(), def));
        installed = true;
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        if (handling) {
            // 已经在处理一次崩溃，再崩就直接退出，避免死循环
            exitApp();
            return;
        }
        handling = true;
        final String trace = buildTrace(thread, throwable);
        saveToFile(trace);
        try {
            showOnScreen(trace);
        } catch (Throwable t) {
            // 连弹窗都失败了，退回系统默认处理
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            } else {
                exitApp();
            }
        }
    }

    private String buildTrace(Thread thread, Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("机型: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        pw.println("系统: Android " + android.os.Build.VERSION.RELEASE
                + " (API " + android.os.Build.VERSION.SDK_INT + ")");
        pw.println("版本: " + BuildConfig.VERSION_NAME + " (build " + BuildConfig.VERSION_CODE + ")");
        pw.println("线程: " + thread.getName());
        pw.println("时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        pw.println();
        throwable.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    private void saveToFile(String trace) {
        try {
            File dir = appContext.getExternalFilesDir("crash");
            if (dir == null) dir = new File(appContext.getFilesDir(), "crash");
            if (!dir.exists()) dir.mkdirs();
            String name = "crash-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
            Writer w = new OutputStreamWriter(new FileOutputStream(new File(dir, name)), StandardCharsets.UTF_8);
            w.write(trace);
            w.flush();
            w.close();
        } catch (Throwable ignored) {
        }
    }

    private void showOnScreen(final String trace) {
        final Runnable show = () -> {
            try {
                Context themed = new ContextThemeWrapper(appContext, android.R.style.Theme_Material_Dialog_Alert);
                ScrollView scroll = new ScrollView(themed);
                TextView tv = new TextView(themed);
                tv.setText("请截图反馈给开发者\n\n" + trace);
                tv.setTextIsSelectable(true);
                tv.setTextSize(11f);
                tv.setPadding(48, 32, 48, 32);
                scroll.addView(tv);
                new AlertDialog.Builder(themed)
                        .setTitle("应用崩溃了")
                        .setView(scroll)
                        .setPositiveButton("重启应用", (d, w) -> restartApp())
                        .setNegativeButton("退出", (d, w) -> exitApp())
                        .setCancelable(false)
                        .show();
            } catch (Throwable t) {
                exitApp();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // 主线程崩溃：先显示弹窗，再手动恢复消息循环让弹窗保持活着
            show.run();
            Looper.loop();
        } else {
            // 后台线程崩溃：丢到主线程显示，本线程自然结束即可
            new Handler(Looper.getMainLooper()).post(show);
        }
    }

    private void restartApp() {
        try {
            Intent i = appContext.getPackageManager().getLaunchIntentForPackage(appContext.getPackageName());
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                appContext.startActivity(i);
            }
        } catch (Throwable ignored) {
        }
        // 略等启动 Intent 派发后退出，避免把刚拉起的页面一起带走
        new Handler(Looper.getMainLooper()).postDelayed(this::exitApp, 800);
    }

    private void exitApp() {
        try {
            LogUtil.flush();
        } catch (Throwable ignored) {
        }
        Process.killProcess(Process.myPid());
        System.exit(10);
    }
}
