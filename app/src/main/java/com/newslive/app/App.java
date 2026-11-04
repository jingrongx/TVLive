package com.newslive.app;

import android.app.Application;
import android.content.Context;

/**
 * 应用入口：在进程最早阶段安装崩溃捕获，
 * 覆盖 Activity 实例化等发生在 onCreate 之前的崩溃。
 */
public class App extends Application {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        CrashHandler.install(base);
    }
}
