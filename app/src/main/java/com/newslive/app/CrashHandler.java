package com.newslive.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.os.Process;
import android.view.ContextThemeWrapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/**
 * 全局崩溃捕获器：任何未捕获异常都会
 * 1. 落盘保存到 Android/data/com.newslive.app/files/crash/crash-时间.txt
 * 2. 拉起独立的崩溃报告页 CrashReportActivity 全屏显示（应用上下文的对话框
 *    在部分车机系统上无法显示，所以用 Activity 承载）
 * 3. App 启动时调用 showPreviousCrashIfAny，自动弹出上次未查看的崩溃报告，
 *    保证"崩溃时没看到"的报告下次一定能看到。
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

    /** 尽早安装：Application.attachBaseContext 或 Activity onCreate 第一行 */
    public static void install(Context context) {
        if (installed) return;
        Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        if (def instanceof CrashHandler) return;
        Context app = null;
        try {
            app = context.getApplicationContext();
        } catch (Throwable ignored) {
        }
        if (app == null) app = context;
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(app, def));
        installed = true;
    }

    /** 崩溃文件目录 */
    private static File crashDir(Context c) {
        File dir = c.getExternalFilesDir("crash");
        if (dir == null) dir = new File(c.getFilesDir(), "crash");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** 最新的未查看崩溃报告文件（crash-*.txt，已读的会改名 .seen） */
    private static File newestUnseenCrash(Context c) {
        try {
            File dir = crashDir(c);
            File[] files = dir.listFiles((d, name) -> name.startsWith("crash-") && name.endsWith(".txt"));
            if (files == null || files.length == 0) return null;
            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.compare(b.lastModified(), a.lastModified());
                }
            });
            return files[0];
        } catch (Throwable t) {
            return null;
        }
    }

    /** App 启动时调用：存在上次未查看的崩溃报告就立即弹出报告页 */
    public static void showPreviousCrashIfAny(Context context) {
        try {
            if (!(context instanceof android.app.Activity)) return; // 必须从页面环境拉起
            File f = newestUnseenCrash(context);
            if (f == null) return;
            launchReport(context, f);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        if (handling) {
            // 已经在处理一次崩溃，再崩就直接退出，避免死循环
            exitApp();
            return;
        }
        handling = true;
        File saved = null;
        try {
            saved = saveToFile(buildTrace(thread, throwable));
        } catch (Throwable ignored) {
        }
        try {
            if (saved != null) {
                launchReport(appContext, saved);
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    // 主线程崩溃：手动恢复消息循环，让报告页能正常显示
                    Looper.loop();
                }
                return;
            }
        } catch (Throwable t) {
            // 报告页拉不起来，退回对话框兜底
            try {
                showFallbackDialog(buildTrace(thread, throwable));
                if (Looper.myLooper() == Looper.getMainLooper()) Looper.loop();
                return;
            } catch (Throwable ignored) {
            }
        }
        // 一切都失败时交给系统默认处理
        if (defaultHandler != null) {
            defaultHandler.uncaughtException(thread, throwable);
        } else {
            exitApp();
        }
    }

    private static void launchReport(Context context, File file) {
        Intent i = new Intent(context, CrashReportActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        i.putExtra("path", file.getAbsolutePath());
        context.startActivity(i);
    }

    private void showFallbackDialog(String trace) {
        Context themed = new ContextThemeWrapper(appContext, android.R.style.Theme_Material_Dialog_Alert);
        android.widget.ScrollView scroll = new android.widget.ScrollView(themed);
        android.widget.TextView tv = new android.widget.TextView(themed);
        tv.setText("请截图反馈给开发者\n\n" + trace);
        tv.setTextIsSelectable(true);
        tv.setTextSize(11f);
        tv.setPadding(48, 32, 48, 32);
        scroll.addView(tv);
        new AlertDialog.Builder(themed)
                .setTitle("应用崩溃了")
                .setView(scroll)
                .setPositiveButton("退出", (d, w) -> exitApp())
                .setCancelable(false)
                .show();
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

    private File saveToFile(String trace) {
        File dir = crashDir(appContext);
        String name = "crash-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
        File f = new File(dir, name);
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
            w.write(trace);
            w.flush();
            w.close();
            return f;
        } catch (Throwable t) {
            return null;
        }
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
