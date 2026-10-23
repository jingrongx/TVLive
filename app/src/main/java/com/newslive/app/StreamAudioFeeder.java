package com.newslive.app;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 低版本（Android 9 及以下）的音频旁路：
 * 系统没给应用内录音接口，但这类 DRM 流通常只加密视频、音频是明文，
 * 于是按播放列表逐段把音频轨抽出来，直接喂给同一个 MP4 封装器。
 *
 * 只做音频：下载的分片用 MediaExtractor 取 audio track，写完立即删文件，
 * 磁盘占用只有 AAC 码率（约 128kbps ≈ 58MB/小时）。
 */
public class StreamAudioFeeder {

    public interface Sink {
        void onAudioFormat(MediaFormat format);
        void onAudioSample(ByteBuffer data, long ptsUs);
    }

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String REFERER = "https://tv.cctv.com/";
    private static final Pattern RE_EXTINF = Pattern.compile("#EXTINF:\\s*([0-9.]+)");

    private final String playlistUrl;
    private final File tmpDir;
    private final Sink sink;
    private volatile boolean running = true;
    private Thread thread;
    private long basePtsUs = 0;
    private boolean formatSent = false;

    public StreamAudioFeeder(String playlistUrl, File tmpDir, Sink sink) {
        this.playlistUrl = playlistUrl;
        this.tmpDir = tmpDir;
        this.sink = sink;
    }

    public void start() {
        running = true;
        thread = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "audio-feed");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        try { if (thread != null) thread.join(1500); } catch (Exception ignore) { }
    }

    public boolean isFormatSent() { return formatSent; }

    private void loop() {
        List<String> seen = new ArrayList<>();
        String url = playlistUrl;
        int fail = 0;
        while (running) {
            try {
                String text = fetchText(url);
                if (text.contains("#EXT-X-STREAM-INF")) {
                    String variant = firstVariant(text, url);
                    if (variant != null) { url = variant; continue; }
                }
                List<String> uris = new ArrayList<>();
                List<Double> durs = new ArrayList<>();
                parse(text, url, uris, durs);

                for (int i = 0; i < uris.size() && running; i++) {
                    String segUrl = uris.get(i);
                    if (seen.contains(segUrl)) continue;
                    seen.add(segUrl);
                    if (seen.size() > 400) seen.subList(0, 200).clear();

                    File tmp = new File(tmpDir, "a_" + System.nanoTime() + ".ts");
                    try {
                        download(segUrl, tmp);
                        extract(tmp, (long) (durs.get(i) * 1_000_000));
                    } catch (Exception e) {
                        LogUtil.w("StreamAudio", "segment audio failed: " + e);
                    } finally {
                        tmp.delete();
                    }
                }
                fail = 0;
                Thread.sleep(1200);
            } catch (InterruptedException ie) {
                return;
            } catch (Exception e) {
                fail++;
                LogUtil.w("StreamAudio", "loop error(" + fail + "): " + e);
                try { Thread.sleep(Math.min(5000, 800L * fail)); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private void extract(File ts, long segDurUs) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(ts.getAbsolutePath());
            int audioTrack = -1;
            MediaFormat af = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    af = f;
                    break;
                }
            }
            if (audioTrack < 0 || af == null) return;
            ex.selectTrack(audioTrack);
            if (!formatSent) {
                formatSent = true;
                sink.onAudioFormat(af);
            }
            ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long segBase = basePtsUs;
            while (running) {
                buf.clear();
                int size = ex.readSampleData(buf, 0);
                if (size < 0) break;
                long t = ex.getSampleTime();
                info.set(0, size, segBase + Math.max(0, t), 0);
                buf.position(0);
                buf.limit(size);
                sink.onAudioSample(buf, info.presentationTimeUs);
                ex.advance();
            }
            basePtsUs = segBase + segDurUs;
        } catch (Exception e) {
            LogUtil.w("StreamAudio", "extract: " + e);
        } finally {
            try { ex.release(); } catch (Exception ignore) { }
        }
    }

    private void parse(String text, String base, List<String> uris, List<Double> durs) {
        String[] lines = text.split("\n");
        double pendingDur = 4;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("#EXTINF:")) {
                Matcher m = RE_EXTINF.matcher(line);
                if (m.find()) {
                    try { pendingDur = Double.parseDouble(m.group(1)); } catch (Exception ignore) { }
                }
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                uris.add(resolve(base, line));
                durs.add(pendingDur);
            }
        }
    }

    private static String firstVariant(String text, String base) {
        String[] lines = text.split("\n");
        boolean next = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-STREAM-INF")) { next = true; continue; }
            if (next && !line.isEmpty() && !line.startsWith("#")) return resolve(base, line);
        }
        return null;
    }

    private static String resolve(String base, String uri) {
        try {
            if (uri.startsWith("http")) return uri;
            return new URL(new URL(base), uri).toString();
        } catch (Exception e) {
            return uri;
        }
    }

    private String fetchText(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Referer", REFERER);
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) != -1) bos.write(b, 0, n);
            in.close();
            return bos.toString("UTF-8");
        } finally {
            try { c.disconnect(); } catch (Exception ignore) { }
        }
    }

    private void download(String url, File out) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        FileOutputStream fos = null;
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Referer", REFERER);
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            fos = new FileOutputStream(out);
            byte[] b = new byte[32 * 1024];
            int n;
            while ((n = in.read(b)) != -1) fos.write(b, 0, n);
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception ignore) { }
            try { c.disconnect(); } catch (Exception ignore) { }
        }
    }
}
