package com.newslive.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 直播 HLS 本地中继（长时缓冲内核）。
 *
 * 它解决的其实不是"多缓存几秒"这个伪命题，而是三件真实会发生的事：
 *  1) 缓冲不再被丢弃：分片逐段落盘，内存里只放索引，既不会被盒子 OOM 杀掉，也不会因重连清空；
 *  2) 对外输出 #EXT-X-PLAYLIST-TYPE:EVENT 的本地播放列表，ExoPlayer 对它只追加、不裁剪时间线；
 *  3) 源端播放列表是滑动窗口，滚出窗口的分片远端已经拿不到，本地还在——只要播放位置还在本地池子里，
 *     网络抖动、短暂断流都不会掉进重缓冲。
 *
 * 天花板由源决定：本地可得的最长内容 = 源播放列表窗口（本项目源池实测中位 29s、最大 70s）。
 * 想要分钟级，必须换带时移(DVR)窗口的源；本类在那种源上能一直攒到 retainMs 上限。
 */
public class HlsRelay {

    public interface Listener {
        /** 第一段分片已落盘，可以开始播放本地列表 */
        void onReady(HlsRelay relay);

        /** 起播前连续失败，建议回退直连源 */
        void onFailed(HlsRelay relay, String reason);
    }

    /** auth_key 过期时可由宿主改成最新签名地址（复用 App 现有的同频道候选逻辑） */
    public interface UrlFixer {
        String fix(String url);
    }

    private static final String TAG = "HlsRelay";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 10000;
    private static final int MAX_VARIANT_BPS = 1_500_000;
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String REFERER = "https://tv.cctv.com/";

    private static final Pattern RE_URI_ATTR = Pattern.compile("URI=\"([^\"]+)\"");
    private static final Pattern RE_BANDWIDTH = Pattern.compile("BANDWIDTH=(\\d+)");

    private static class Seg {
        long seq;
        String remoteUri;
        double durSec;
        String fileName;
        boolean ready;
        boolean discontinuity;
        String keyTag;      // 已绝对化的 EXT-X-KEY 原始行
        String mapTag;      // 已绝对化的 EXT-X-MAP 原始行
        String initName;    // 本地 init 分片文件名（fMP4）
        String pdt;         // 程序时间
        long bytes;
    }

    private final Context appCtx;
    private final String sourceUrl;
    private final String token;
    private final File dir;
    private final String baseUrl;      // http://127.0.0.1:port/relay/<token>/
    private final long retainMs;
    private final UrlFixer fixer;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();

    private final TreeMap<Long, Seg> segs = new TreeMap<>();
    private volatile Listener listener;
    private volatile boolean running = true;
    private volatile Thread worker;
    private volatile String playlistUrl;
    private volatile String playlistText = "";
    private volatile String lastError = "";
    private volatile boolean readyFired = false;
    private volatile boolean failedFired = false;
    private volatile int targetDurationSec = 6;
    private volatile int sourceWindowSec = 0;
    private volatile int readyCount = 0;
    private volatile long readyMs = 0;
    private volatile long diskBytes = 0;
    private volatile long evictedMs = 0;
    private volatile long playerPosMs = 0;
    private int initSeq = 0;

    public HlsRelay(Context ctx, String sourceUrl, String token, File dir,
                    String baseUrl, long retainMs, UrlFixer fixer, Listener listener) {
        this.appCtx = ctx.getApplicationContext();
        this.sourceUrl = sourceUrl;
        this.token = token;
        this.dir = dir;
        this.baseUrl = baseUrl;
        this.retainMs = Math.max(60_000L, retainMs);
        this.fixer = fixer;
        this.listener = listener;
        this.playlistUrl = sourceUrl;
        if (!dir.exists()) dir.mkdirs();
    }

    // ---------------------------------------------------------------- 对外接口

    public String getToken() { return token; }

    public boolean isForSource(String url) {
        return url != null && url.equals(sourceUrl);
    }

    public String getLocalPlaylistUrl() { return baseUrl + "index.m3u8"; }

    public String getSourceUrl() { return sourceUrl; }

    public boolean hasFirstSegment() { return readyFired; }

    public String getPlaylistText() { return playlistText; }

    public String getLastError() { return lastError; }

    /** 源播放列表窗口长度（秒）——它才是"最多能缓存多久"的真上限 */
    public int getSourceWindowSec() { return sourceWindowSec; }

    public int getReadyCount() { return readyCount; }

    public long getReadyMs() { return readyMs; }

    public long getDiskBytes() { return diskBytes; }

    public long getRetainMs() { return retainMs; }

    public long getEvictedMs() { return evictedMs; }

    /** 实测源码率（KB/s）：已落盘字节 ÷ 已缓存内容时长。与网速对比即可判断能不能持续 */
    public double getSourceBitrateKBps() {
        long ms = readyMs;
        if (ms < 1000) return 0;
        return diskBytes / (ms / 1000.0) / 1024.0;
    }

    public String getStatsLine() {
        return "窗口 " + sourceWindowSec + "s / 本地 " + (readyMs / 1000) + "s(" + readyCount
                + "片) / 磁盘 " + (diskBytes / 1024 / 1024) + "MB";
    }

    /** 播放器当前播放位置（相对本地列表起点，ms），用于安全淘汰 */
    public void setPlayerPositionMs(long pos) {
        this.playerPosMs = Math.max(0, pos);
    }

    public void setListener(Listener l) { this.listener = l; }

    public File getFile(String name) {
        if (name == null || name.isEmpty()) return null;
        if (name.contains("/") || name.contains("\\") || name.contains("..")) return null;
        File f = new File(dir, name);
        return f.exists() ? f : null;
    }

    public void start() {
        if (worker != null) return;
        worker = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "HlsRelay-" + token);
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() {
        running = false;
        Thread t = worker;
        if (t != null) t.interrupt();
        worker = null;
        try {
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) f.delete();
            dir.delete();
        } catch (Exception ignore) { }
    }

    // ---------------------------------------------------------------- 主循环

    private void loop() {
        int failures = 0;
        long lastStats = 0;
        while (running) {
            try {
                String text = fetchText(playlistUrl);
                boolean master = mergePlaylist(text);
                if (master) continue;               // master 已解析出变体地址，立刻重取

                downloadMissing();

                failures = 0;
                long now = System.currentTimeMillis();
                if (now - lastStats > 15000) {
                    lastStats = now;
                    LogUtil.i(TAG, "relay " + token + " " + getStatsLine());
                }
                Thread.sleep(Math.max(700L, Math.min(2500L, targetDurationSec * 350L)));
            } catch (InterruptedException ie) {
                return;
            } catch (Exception e) {
                lastError = e.toString();
                failures++;
                LogUtil.w(TAG, "relay loop error(" + failures + "): " + e);
                if (!readyFired && failures >= 4 && !failedFired) {
                    failedFired = true;
                    fireFailed(lastError);
                }
                try {
                    Thread.sleep(Math.min(6000L, 800L * failures));
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private void fireReady() {
        final Listener l = listener;
        if (l == null) return;
        main.post(new Runnable() {
            @Override public void run() { l.onReady(HlsRelay.this); }
        });
    }

    private void fireFailed(final String reason) {
        final Listener l = listener;
        if (l == null) return;
        main.post(new Runnable() {
            @Override public void run() { l.onFailed(HlsRelay.this, reason); }
        });
    }

    // ---------------------------------------------------------------- 拉取与解析

    private String fetchText(String url) throws IOException {
        IOException last = null;
        String target = url;
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(target).openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", UA);
                conn.setRequestProperty("Referer", REFERER);
                conn.setRequestProperty("Accept", "*/*");
                int code = conn.getResponseCode();
                if (code == 403 && attempt == 0 && fixer != null) {
                    String fixed = fixer.fix(target);
                    if (fixed != null && !fixed.isEmpty() && !fixed.equals(target)) {
                        LogUtil.i(TAG, "playlist 403, rewrite to fresh key");
                        target = fixed;
                        closeQuietly(conn);
                        continue;
                    }
                }
                if (code != 200) throw new IOException("HTTP " + code);
                InputStream in = conn.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                in.close();
                // 重定向后相对分片要基于最终地址解析
                URL finalUrl = conn.getURL();
                if (finalUrl != null && !finalUrl.toString().equals(playlistUrl)) {
                    playlistUrl = finalUrl.toString();
                }
                return bos.toString("UTF-8");
            } catch (IOException e) {
                last = e;
            } finally {
                closeQuietly(conn);
            }
        }
        throw last != null ? last : new IOException("fetch failed");
    }

    private static void closeQuietly(HttpURLConnection c) {
        try { if (c != null) c.disconnect(); } catch (Exception ignore) { }
    }

    /** @return true 表示拿到的是 master 列表，已选出变体地址，需要立刻重新拉取 */
    private boolean mergePlaylist(String text) throws IOException {
        if (text == null || !text.startsWith("#EXTM3U")) {
            throw new IOException("not a playlist");
        }
        if (text.contains("#EXT-X-STREAM-INF")) {
            String variant = pickVariant(text);
            if (variant == null) throw new IOException("no variant");
            playlistUrl = variant;
            LogUtil.i(TAG, "master playlist -> " + variant);
            return true;
        }
        if (text.contains("#EXT-X-BYTERANGE")) {
            // 按字节区间切分片无法整体落盘，直接回退直连，避免播放异常
            throw new IOException("byterange playlist unsupported");
        }

        String base = playlistUrl;
        String[] lines = text.split("\n");
        long seq = -1;
        double pendingDur = -1;
        boolean pendingDisc = false;
        String curKey = null, curMap = null, curPdt = null;
        int td = 0;
        List<Seg> parsed = new ArrayList<>();

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                seq = parseLong(line.substring(line.indexOf(':') + 1).trim(), -1);
            } else if (line.startsWith("#EXT-X-TARGETDURATION:")) {
                td = (int) parseLong(line.substring(line.indexOf(':') + 1).trim(), 0);
            } else if (line.startsWith("#EXT-X-KEY:")) {
                curKey = absolutizeTag(line, base);
            } else if (line.startsWith("#EXT-X-MAP:")) {
                curMap = absolutizeTag(line, base);
            } else if (line.startsWith("#EXT-X-PROGRAM-DATE-TIME:")) {
                curPdt = line.substring(line.indexOf(':') + 1).trim();
            } else if (line.startsWith("#EXT-X-DISCONTINUITY")) {
                pendingDisc = true;
            } else if (line.startsWith("#EXTINF:")) {
                String v = line.substring(8);
                int comma = v.indexOf(',');
                if (comma >= 0) v = v.substring(0, comma);
                try { pendingDur = Double.parseDouble(v.trim()); } catch (Exception e) { pendingDur = td > 0 ? td : 4; }
            } else if (line.startsWith("#")) {
                continue;
            } else {
                if (seq < 0) seq = 0;
                Seg s = new Seg();
                s.seq = seq++;
                s.remoteUri = resolve(base, line);
                s.durSec = pendingDur > 0 ? pendingDur : (td > 0 ? td : 4);
                s.discontinuity = pendingDisc;
                s.keyTag = curKey;
                s.mapTag = curMap;
                s.pdt = curPdt;
                parsed.add(s);
                pendingDur = -1;
                pendingDisc = false;
                curPdt = null;
            }
        }
        if (td > 0) targetDurationSec = td;

        double windowSec = 0;
        for (Seg s : parsed) windowSec += s.durSec;
        sourceWindowSec = (int) Math.round(windowSec);

        long newestEndMs = 0;
        for (Seg s : parsed) newestEndMs += (long) (s.durSec * 1000);
        long oldestKeepMs = newestEndMs - retainMs;

        synchronized (lock) {
            long cursor = 0;
            for (Seg s : parsed) {
                cursor += (long) (s.durSec * 1000);
                if (cursor <= oldestKeepMs && parsed.size() > 1) continue; // 超出保留上限的旧分片
                if (!segs.containsKey(s.seq)) {
                    if (s.mapTag != null) {
                        s.initName = "init" + (initSeq++) + ".mp4";
                        s.initName = downloadInit(s.mapTag, s.initName);
                    }
                    segs.put(s.seq, s);
                }
            }
            evictIfNeededLocked();
            rebuildPlaylistLocked();
        }
        return false;
    }

    private String pickVariant(String text) {
        String[] lines = text.split("\n");
        int best = -1, lowest = Integer.MAX_VALUE;
        String bestUrl = null, lowUrl = null;
        boolean pendingInf = false;
        int pendingBw = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                pendingInf = true;
                Matcher m = RE_BANDWIDTH.matcher(line);
                pendingBw = m.find() ? (int) parseLong(m.group(1), 0) : 0;
            } else if (!line.isEmpty() && !line.startsWith("#") && pendingInf) {
                String u = resolve(playlistUrl, line);
                if (pendingBw <= MAX_VARIANT_BPS && pendingBw >= best) { best = pendingBw; bestUrl = u; }
                if (pendingBw < lowest) { lowest = pendingBw; lowUrl = u; }
                pendingInf = false;
            }
        }
        return bestUrl != null ? bestUrl : lowUrl;
    }

    private String downloadInit(String mapTag, String fileName) {
        try {
            Matcher m = RE_URI_ATTR.matcher(mapTag);
            if (!m.find()) return null;
            String uri = m.group(1);
            File out = new File(dir, fileName);
            if (!out.exists()) downloadToFile(uri, out);
            return out.exists() ? fileName : null;
        } catch (Exception e) {
            LogUtil.w(TAG, "init segment failed: " + e);
            return null;
        }
    }

    // ---------------------------------------------------------------- 分片下载

    private void downloadMissing() {
        List<Seg> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(segs.values());
        }
        for (Seg s : snapshot) {
            if (!running) return;
            if (s.ready) continue;
            boolean ok = false;
            for (int attempt = 0; attempt < 3 && running && !ok; attempt++) {
                try {
                    File tmp = new File(dir, "tmp_" + s.seq);
                    long n = downloadToFile(s.remoteUri, tmp);
                    s.fileName = "s" + s.seq + extOf(s.remoteUri);
                    File dst = new File(dir, s.fileName);
                    if (!tmp.renameTo(dst)) {
                        // 个别设备 rename 失败：退化为复制
                        copyFile(tmp, dst);
                        tmp.delete();
                    }
                    s.bytes = n;
                    s.ready = true;
                    ok = true;
                } catch (Exception e) {
                    lastError = e.toString();
                    if (attempt < 2) {
                        try { Thread.sleep(400L * (attempt + 1)); } catch (InterruptedException ie) { return; }
                    }
                }
            }
            if (!ok) {
                // 保持顺序：这一段没下来，后面的先不拉，下一轮继续重试它
                return;
            }
            synchronized (lock) {
                diskBytes += s.bytes;
                rebuildPlaylistLocked();
            }
            if (!readyFired) {
                readyFired = true;
                LogUtil.i(TAG, "relay ready: " + getStatsLine());
                fireReady();
            }
        }
    }

    private long downloadToFile(String uri, File out) throws IOException {
        HttpURLConnection conn = null;
        FileOutputStream fos = null;
        try {
            conn = (HttpURLConnection) new URL(uri).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", REFERER);
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " " + uri);
            InputStream in = conn.getInputStream();
            fos = new FileOutputStream(out);
            byte[] buf = new byte[32 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                fos.write(buf, 0, n);
                total += n;
            }
            in.close();
            fos.flush();
            return total;
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception ignore) { }
            closeQuietly(conn);
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

    // ---------------------------------------------------------------- 本地播放列表

    private void rebuildPlaylistLocked() {
        List<Seg> ready = new ArrayList<>();
        long sum = 0;
        for (Seg s : segs.values()) {
            if (!s.ready) break;      // 只输出"从起点开始连续就绪"的前缀
            ready.add(s);
            sum += (long) (s.durSec * 1000);
        }
        readyMs = sum;
        readyCount = ready.size();
        if (ready.isEmpty()) {
            playlistText = "";
            return;
        }
        StringBuilder sb = new StringBuilder(1024);
        sb.append("#EXTM3U\n");
        sb.append("#EXT-X-VERSION:3\n");
        sb.append("#EXT-X-TARGETDURATION:").append(Math.max(1, targetDurationSec)).append('\n');
        sb.append("#EXT-X-MEDIA-SEQUENCE:").append(ready.get(0).seq).append('\n');
        // EVENT：ExoPlayer 只追加、不裁剪，这是"连续不断一直缓存"能被播放器承认的开关
        sb.append("#EXT-X-PLAYLIST-TYPE:EVENT\n");
        if (ready.get(0).pdt != null) {
            sb.append("#EXT-X-PROGRAM-DATE-TIME:").append(ready.get(0).pdt).append('\n');
        }
        String lastKey = null;
        String lastMap = null;
        for (int i = 0; i < ready.size(); i++) {
            Seg s = ready.get(i);
            if (s.discontinuity && i > 0) sb.append("#EXT-X-DISCONTINUITY\n");
            if (s.keyTag != null && !s.keyTag.equals(lastKey)) {
                sb.append(s.keyTag).append('\n');
                lastKey = s.keyTag;
            }
            if (s.initName != null && !s.initName.equals(lastMap)) {
                sb.append("#EXT-X-MAP:URI=\"").append(baseUrl).append(s.initName).append("\"\n");
                lastMap = s.initName;
            }
            sb.append("#EXTINF:").append(String.format(Locale.US, "%.3f", s.durSec)).append(",\n");
            sb.append(baseUrl).append(s.fileName).append('\n');
        }
        playlistText = sb.toString();
    }

    /**
     * 滚动淘汰（行车记录仪式）：录满就覆盖最旧的。
     * - 未起播（预缓存阶段）：纯粹环形覆盖，池子永远只留最近 retainMs，不会把磁盘吃满；
     * - 已起播：绝不删播放位置前 90s 内的分片，否则会让画面跳变。
     */
    private void evictIfNeededLocked() {
        long total = 0;
        for (Seg s : segs.values()) total += (long) (s.durSec * 1000);
        long hardLimit = retainMs + 120_000L;
        if (total <= hardLimit) return;

        boolean playing = playerPosMs > 0;
        long playerAbs = evictedMs + playerPosMs;
        // 硬上限：用户长时间回看时，播放保护会让淘汰卡住，磁盘会无限长。
        // 超过保留时长 25% 就强制覆盖，宁可跳一下也不要把盒子写满。
        long hardCap = retainMs + Math.max(300_000L, retainMs / 4);
        boolean overCap = total > hardCap;
        Iterator<Map.Entry<Long, Seg>> it = segs.entrySet().iterator();
        while (it.hasNext() && total > retainMs) {
            Seg s = it.next().getValue();
            long segEnd = evictedMs + (long) (s.durSec * 1000);
            if (playing && !overCap && segEnd > playerAbs - 90_000L) break;
            if (!s.ready) break;
            File f = s.fileName != null ? new File(dir, s.fileName) : null;
            if (f != null) f.delete();
            diskBytes -= s.bytes;
            if (diskBytes < 0) diskBytes = 0;
            evictedMs += (long) (s.durSec * 1000);
            total -= (long) (s.durSec * 1000);
            it.remove();
        }
    }

    // ---------------------------------------------------------------- 工具

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
    }

    private static String resolve(String base, String uri) {
        try {
            if (uri.startsWith("http://") || uri.startsWith("https://")) return uri;
            return new URL(new URL(base), uri).toString();
        } catch (Exception e) {
            return uri;
        }
    }

    private static String absolutizeTag(String tag, String base) {
        try {
            Matcher m = RE_URI_ATTR.matcher(tag);
            if (!m.find()) return tag;
            String abs = resolve(base, m.group(1));
            return tag.substring(0, m.start(1)) + abs + tag.substring(m.end(1));
        } catch (Exception e) {
            return tag;
        }
    }

    private static String extOf(String uri) {
        String path = uri;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int dot = path.lastIndexOf('.');
        if (dot < 0 || path.length() - dot > 6) return ".ts";
        String ext = path.substring(dot).toLowerCase(Locale.US);
        if (ext.startsWith(".ts") || ext.startsWith(".m4s") || ext.startsWith(".mp4")
                || ext.startsWith(".aac") || ext.startsWith(".mp3")) {
            return ext.substring(0, Math.min(ext.length(), 4));
        }
        return ".ts";
    }
}
