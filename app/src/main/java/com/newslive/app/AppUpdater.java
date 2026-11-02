package com.newslive.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 应用自更新内核：检查 GitHub Releases 最新版本 → 后台下载 → 宿主提示安装。
 *
 * 线路策略：国内优先代理镜像（ghfast.top / mirror.ghproxy.com / github.moeyy.xyz），
 * 每条线路独立超时逐个回退，最后 GitHub 直连兜底；区域检测失败按国内处理。
 * 版本号从 releases/latest 的跳转地址/页面内容解析（releases/tag/vX.Y.Z），
 * 不依赖 api.github.com（国内不可达且镜像支持不一）。
 */
public class AppUpdater {

    private static final String TAG = "AppUpdate";
    private static final String REPO = "jingrongx/TVLive";
    private static final String RELEASE_LATEST = "https://github.com/" + REPO + "/releases/latest";

    /** 下载进度回调（下载线程回调） */
    public interface Progress {
        void onProgress(int percent, int totalMb);
    }

    private static final String[] WRAPPERS = {
        "",                                  // GitHub 直连
        "https://ghfast.top/",
        "https://mirror.ghproxy.com/",
        "https://github.moeyy.xyz/",
    };

    // ---- 区域检测缓存（1小时）：国内=true 时镜像优先 ----
    private static volatile long lastRegionCheckAt = 0;
    private static volatile boolean lastRegionChina = true;

    /** 当前版本名（如 1.0.78） */
    public static String currentVersion(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /** 拉取最新版本号（如 1.0.79）；所有线路都失败返回 null */
    public static String fetchLatestVersion() {
        boolean china = isChinaNetwork();
        for (String w : orderedWrappers(china)) {
            HttpURLConnection conn = null;
            try {
                conn = open(w + RELEASE_LATEST, 8000);
                int code = conn.getResponseCode();
                if (code != 200) {
                    LogUtil.w(TAG, "check via " + name(w) + ": HTTP " + code);
                    continue;
                }
                String tag = extractTag(urlOf(conn));
                if (tag == null) tag = extractTag(readBody(conn, 256 * 1024));
                if (tag != null) {
                    LogUtil.i(TAG, "latest version " + tag + " via " + name(w));
                    return tag;
                }
                LogUtil.w(TAG, "check via " + name(w) + ": no tag found");
            } catch (Exception e) {
                LogUtil.w(TAG, "check via " + name(w) + " failed: " + e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return null;
    }

    /** found 是否比 current 新（按数字段比较） */
    public static boolean isNewer(String found, String current) {
        try {
            String[] a = found.split("\\.");
            String[] b = current.split("\\.");
            for (int i = 0; i < Math.max(a.length, b.length); i++) {
                int x = i < a.length ? Integer.parseInt(a[i].trim()) : 0;
                int y = i < b.length ? Integer.parseInt(b[i].trim()) : 0;
                if (x != y) return x > y;
            }
        } catch (Exception ignore) { }
        return false;
    }

    /**
     * 下载指定版本 APK 到应用外部私有目录 update/。
     * 逐条线路尝试；<1MB 或非 ZIP 头视为失败换下一条。全部失败返回 null。
     */
    public static File downloadApk(Context ctx, String version, Progress progress) {
        Context app = ctx.getApplicationContext();
        String asset = "https://github.com/" + REPO
                + "/releases/download/v" + version
                + "/newslive-v" + version + ".apk";
        File dir = new File(app.getExternalFilesDir(null), "update");
        if (!dir.exists()) dir.mkdirs();
        // 每次下载前清空旧安装包
        File[] olds = dir.listFiles();
        if (olds != null) for (File f : olds) f.delete();
        File tmp = new File(dir, "update.tmp");
        File dst = new File(dir, "newslive-v" + version + ".apk");

        boolean china = isChinaNetwork();
        for (String w : orderedWrappers(china)) {
            HttpURLConnection conn = null;
            try {
                LogUtil.i(TAG, "download v" + version + " via " + name(w));
                conn = open(w + asset, 10000);
                int code = conn.getResponseCode();
                if (code != 200) {
                    LogUtil.w(TAG, "download via " + name(w) + ": HTTP " + code);
                    continue;
                }
                long total = conn.getContentLength();
                InputStream in = new BufferedInputStream(conn.getInputStream());
                FileOutputStream fos = new FileOutputStream(tmp);
                byte[] buf = new byte[32 * 1024];
                long done = 0;
                int lastPct = -1;
                int n;
                while ((n = in.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                    done += n;
                    if (total > 0 && progress != null) {
                        int pct = (int) (done * 100 / total);
                        if (pct >= lastPct + 25) {
                            lastPct = pct;
                            progress.onProgress(pct, (int) (total / 1024 / 1024));
                        }
                    }
                }
                fos.close();
                in.close();
                if (done < 1024 * 1024 || !isZip(tmp)) {
                    LogUtil.w(TAG, "download via " + name(w) + ": invalid payload (" + done + "B)");
                    tmp.delete();
                    continue;
                }
                if (dst.exists()) dst.delete();
                if (!tmp.renameTo(dst)) {
                    copyFile(tmp, dst);
                    tmp.delete();
                }
                LogUtil.i(TAG, "downloaded v" + version + " -> " + dst + " (" + done / 1024 / 1024 + "MB)");
                return dst;
            } catch (Exception e) {
                LogUtil.w(TAG, "download via " + name(w) + " failed: " + e);
                tmp.delete();
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return null;
    }

    /** 拉起系统安装器（FileProvider 授权读取；失败弹提示，通常是"未知来源安装"未放开） */
    public static void installApk(Context ctx, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            LogUtil.e(TAG, "install failed", e);
            if (Looper.myLooper() == Looper.getMainLooper()) {
                Toast.makeText(ctx, "拉起安装失败：" + e.getMessage()
                        + "（请检查是否允许安装未知来源应用）", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ---------------------------------------------------------------- 内部

    private static String[] orderedWrappers(boolean china) {
        if (china) {
            return new String[]{WRAPPERS[1], WRAPPERS[2], WRAPPERS[3], WRAPPERS[0]};
        }
        return WRAPPERS;
    }

    private static String name(String wrapper) {
        return wrapper.isEmpty() ? "GitHub直连" : wrapper.replaceAll("https://", "").replaceAll("/$", "");
    }

    private static HttpURLConnection open(String url, int timeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(Math.max(timeoutMs, 30000));
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 NewsLive-Updater");
        return conn;
    }

    private static String urlOf(HttpURLConnection conn) {
        try {
            URL u = conn.getURL();
            return u != null ? u.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String extractTag(String s) {
        if (s == null) return null;
        Matcher m = Pattern.compile("releases/tag/v([0-9]+(?:\\.[0-9]+)*)").matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String readBody(HttpURLConnection conn, int limit) throws IOException {
        InputStream in = conn.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n, total = 0;
        while ((n = in.read(buf)) != -1 && total < limit) {
            bos.write(buf, 0, n);
            total += n;
        }
        in.close();
        return bos.toString("UTF-8");
    }

    private static boolean isZip(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            return in.read() == 'P' && in.read() == 'K';
        } catch (Exception e) {
            return false;
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        InputStream in = new java.io.FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close();
        out.close();
    }

    /**
     * 区域检测：国内服务返回含"中国/省"即判定国内（App 面向国内电视，检测失败按国内处理）。
     * 结果缓存 1 小时。
     */
    private static boolean isChinaNetwork() {
        long now = SystemClock.elapsedRealtime();
        synchronized (AppUpdater.class) {
            if (now - lastRegionCheckAt < 3600_000L) return lastRegionChina;
        }
        boolean china = probeChina();
        synchronized (AppUpdater.class) {
            lastRegionCheckAt = now;
            lastRegionChina = china;
        }
        LogUtil.i(TAG, "region check: china=" + china);
        return china;
    }

    private static boolean probeChina() {
        if (httpContains("http://qifu-api.baidubce.com/ip/local/geo/v1/district", "中国", 3000)) return true;
        if (httpContains("http://whois.pconline.com.cn/ipJson.jsp?json=true", "省", 3000)) return true;
        return true;   // 双检测失败：按国内处理（镜像也可用，仅多几秒回退）
    }

    private static boolean httpContains(String url, String needle, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (conn.getResponseCode() != 200) return false;
            return readBody(conn, 8192).contains(needle);
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
