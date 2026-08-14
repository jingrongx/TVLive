package com.newslive.app;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 日志工具：在输出 logcat 的同时自动落盘保存，并自动轮转清理。
 *
 * 日志目录：Android/data/com.newslive.app/files/logs/（无需存储权限）
 * 文件命名：newslive-yyyyMMdd.log，单文件超过 2MB 自动切换新文件（追加序号）
 * 自动清理：初始化时仅保留最近 8 个日志文件，更早的自动删除
 */
public class LogUtil {

    private static final long MAX_FILE_SIZE = 2 * 1024 * 1024; // 单文件上限 2MB
    private static final int MAX_FILE_COUNT = 8;               // 最多保留 8 个日志文件

    private static final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(2000);
    private static final SimpleDateFormat dayFmt = new SimpleDateFormat("yyyyMMdd", Locale.US);
    private static final SimpleDateFormat timeFmt = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static volatile Writer writer;
    private static volatile File currentFile;
    private static volatile long currentSize;
    private static volatile File logDir;
    private static volatile boolean initialized = false;

    /** 初始化日志落盘（在 Activity onCreate 中调用一次即可） */
    public static synchronized void init(Context context) {
        if (initialized) return;
        initialized = true;
        try {
            File dir = context.getExternalFilesDir("logs");
            if (dir == null) dir = new File(context.getFilesDir(), "logs");
            logDir = dir;
            if (!logDir.exists()) logDir.mkdirs();
            cleanOldLogs();
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    writerLoop();
                }
            }, "NewsLive-LogWriter");
            t.setDaemon(true);
            t.start();
            i("NewsLive", "LogUtil init, dir=" + logDir.getAbsolutePath());
        } catch (Exception e) {
            Log.e("NewsLive", "LogUtil init failed", e);
        }
    }

    public static void i(String tag, String msg) {
        Log.i(tag, msg);
        enqueue('I', tag, msg);
    }

    public static void d(String tag, String msg) {
        Log.d(tag, msg);
        enqueue('D', tag, msg);
    }

    public static void w(String tag, String msg) {
        Log.w(tag, msg);
        enqueue('W', tag, msg);
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
        enqueue('E', tag, msg);
    }

    public static void e(String tag, String msg, Throwable tr) {
        Log.e(tag, msg, tr);
        enqueue('E', tag, msg + " | " + Log.getStackTraceString(tr));
    }

    /** 立即刷盘（退出前调用，避免丢失最后几条日志） */
    public static void flush() {
        try {
            Writer w = writer;
            if (w != null) w.flush();
        } catch (Exception ignored) {
        }
    }

    /** 日志目录（供调试查看） */
    public static String getLogDir() {
        return logDir != null ? logDir.getAbsolutePath() : "";
    }

    private static void enqueue(char level, String tag, String msg) {
        if (!initialized || logDir == null) return;
        String line;
        synchronized (timeFmt) {
            line = timeFmt.format(new Date()) + " " + level + "/" + tag + ": " + msg;
        }
        queue.offer(line); // 队列满时丢弃，绝不阻塞主线程
    }

    private static void writerLoop() {
        while (true) {
            try {
                String line = queue.take();
                ensureWriter();
                if (writer != null) {
                    writer.write(line);
                    writer.write('\n');
                    currentSize += line.length() + 1;
                    // 批量写出，减少 IO 次数
                    List<String> batch = new ArrayList<>();
                    queue.drainTo(batch, 100);
                    for (String l : batch) {
                        writer.write(l);
                        writer.write('\n');
                        currentSize += l.length() + 1;
                    }
                    writer.flush();
                    if (currentSize > MAX_FILE_SIZE) {
                        rotate();
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                Log.e("NewsLive", "LogUtil write failed", e);
            }
        }
    }

    /** 确保有可用的写入文件（跨天或超限时轮转） */
    private static void ensureWriter() {
        try {
            String day;
            synchronized (dayFmt) {
                day = dayFmt.format(new Date());
            }
            if (writer != null && currentFile != null
                && currentFile.getName().contains(day) && currentSize <= MAX_FILE_SIZE) {
                return;
            }
            rotate();
        } catch (Exception e) {
            Log.e("NewsLive", "LogUtil ensureWriter failed", e);
        }
    }

    /** 关闭当前文件并打开新的写入文件 */
    private static void rotate() {
        closeWriter();
        if (logDir == null) return;
        try {
            String day;
            synchronized (dayFmt) {
                day = dayFmt.format(new Date());
            }
            File f = new File(logDir, "newslive-" + day + ".log");
            int seq = 0;
            while (f.exists() && f.length() >= MAX_FILE_SIZE) {
                seq++;
                f = new File(logDir, "newslive-" + day + "-" + seq + ".log");
            }
            currentFile = f;
            currentSize = f.length();
            writer = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e("NewsLive", "LogUtil rotate failed", e);
        }
    }

    private static void closeWriter() {
        try {
            if (writer != null) {
                writer.flush();
                writer.close();
            }
        } catch (Exception ignored) {
        }
        writer = null;
    }

    /** 自动清理：只保留最近 MAX_FILE_COUNT 个日志文件 */
    private static void cleanOldLogs() {
        try {
            File[] files = logDir.listFiles((dir, name) ->
                name.startsWith("newslive-") && name.endsWith(".log"));
            if (files == null || files.length <= MAX_FILE_COUNT) return;
            Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i < files.length - MAX_FILE_COUNT; i++) {
                boolean deleted = files[i].delete();
                Log.i("NewsLive", "LogUtil clean old log: " + files[i].getName() + " deleted=" + deleted);
            }
        } catch (Exception e) {
            Log.e("NewsLive", "LogUtil clean failed", e);
        }
    }
}
