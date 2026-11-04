package com.newslive.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * 崩溃报告页：全屏显示崩溃堆栈，独立 Activity 展示（比对话框可靠，
 * 应用上下文的对话框在部分车机系统上会因窗口令牌问题无法显示）。
 * 进入本页即把对应崩溃文件标记为已读，避免下次启动重复弹出。
 */
public class CrashReportActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String path = getIntent() != null ? getIntent().getStringExtra("path") : null;
        String text = readTrace(path);
        markSeen(path);

        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 24, 32, 24);

        TextView title = new TextView(this);
        title.setText("应用崩溃报告");
        title.setTextColor(Color.RED);
        title.setTextSize(22f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("请把本页完整拍照发给开发者（可上下滚动）");
        hint.setTextColor(Color.YELLOW);
        hint.setTextSize(15f);
        hint.setPadding(0, 12, 0, 12);
        root.addView(hint);

        TextView body = new TextView(this);
        body.setText(text);
        body.setTextColor(Color.WHITE);
        body.setTextSize(11f);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, bodyLp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.setMargins(16, 16, 16, 16);

        Button restart = new Button(this);
        restart.setText("重启应用");
        restart.setOnClickListener(v -> restartApp());
        buttons.addView(restart, btnLp);

        Button exit = new Button(this);
        exit.setText("退出");
        exit.setOnClickListener(v -> exitApp());
        buttons.addView(exit, btnLp);

        Button back = new Button(this);
        back.setText("继续使用");
        back.setOnClickListener(v -> finish());
        buttons.addView(back, btnLp);

        root.addView(buttons);
        setContentView(root);
    }

    private String readTrace(String path) {
        try {
            if (path == null) return "未找到崩溃详情文件";
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new FileReader(new File(path)));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            r.close();
            if (sb.length() > 0) return sb.toString();
            return "崩溃详情文件为空";
        } catch (Throwable t) {
            return "读取崩溃详情失败: " + t;
        }
    }

    /** 把报告标记为已读：重命名加后缀，下次启动不再自动弹出 */
    private void markSeen(String path) {
        try {
            if (path == null) return;
            File f = new File(path);
            f.renameTo(new File(f.getParentFile(), f.getName() + ".seen"));
        } catch (Throwable ignored) {
        }
    }

    private void restartApp() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
            }
        } catch (Throwable ignored) {
        }
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
