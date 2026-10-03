package com.newslive.app;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 直播源自动优选引擎（App 内置）。
 * 流程：拉取订阅（TVBox txt / m3u）→ 频道名归一化合并 → 并发连通验证 + 分片级测速
 *      → 每频道保留最快 1 条线路 → 按分组顺序排序 → 输出 NewsLive 配置 JSON。
 *
 * 单次扫描单例使用：isRunning() 为 true 时拒绝重复启动；stop() 可中途终止。
 */
public class SourceScanner {

    public interface Callback {
        /** 进度：phase 阶段名，cur/total 计数，msg 附加信息 */
        void onProgress(String phase, int cur, int total, String msg);

        /** 完成：configJson 为可直接交给 updateConfig 的配置，medianKB 中位速度 */
        void onDone(String configJson, int channels, int medianKB);

        void onError(String msg);
    }

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private static final String[] GROUP_ORDER = {
            "央视频道", "卫视频道", "电影频道", "体育频道", "少儿频道", "纪录频道",
            "音乐频道", "春晚回顾", "港澳台及国际", "地方频道", "其他频道", "海外频道"};

    private static final Pattern P_CCTV_PREFIX = Pattern.compile("^CCTV\\d");
    private static final Pattern P_CJK = Pattern.compile("[\\u4e00-\\u9fff]");

    private volatile boolean running = false;
    private volatile boolean stopped = false;

    public boolean isRunning() { return running; }

    /** 中途停止（当前进行中的 HTTP 请求会自然超时后退出） */
    public void stop() { stopped = true; }

    public void scan(final String[] subUrls, final int concurrency, final int minSpeedKB, final Callback cb) {
        if (running) {
            cb.onError("已有优选任务在运行");
            return;
        }
        running = true;
        stopped = false;
        final int conc = Math.max(4, Math.min(concurrency, 60));
        final int minSpeed = Math.max(10, minSpeedKB);
        new Thread(new Runnable() {
            @Override
            public void run() {
                ExecutorService pool = null;
                try {
                    // ---- 1. 下载订阅 ----
                    List<List<String[]>> subItems = new ArrayList<>();
                    for (int i = 0; i < subUrls.length; i++) {
                        throwIfStopped();
                        cb.onProgress("下载订阅", i, subUrls.length, shortUrl(subUrls[i]));
                        String text = httpGet(subUrls[i], 20000, null);
                        if (text == null) continue;
                        List<String[]> items = parseSub(text);
                        if (!items.isEmpty()) subItems.add(items);
                    }
                    if (subItems.isEmpty()) {
                        running = false;
                        cb.onError("所有订阅均拉取失败（检查网络或地址）");
                        return;
                    }

                    // ---- 2. 归一化合并 ----
                    LinkedHashMap<String, Chan> chans = new LinkedHashMap<>();
                    for (List<String[]> items : subItems) {
                        for (String[] it : items) {
                            throwIfStopped();
                            String key = normName(it[0]);
                            if (key.isEmpty()) continue;
                            Chan c = chans.get(key);
                            if (c == null) {
                                c = new Chan();
                                c.display = it[0];
                                c.group = classify(it[0]);
                                c.urls = new LinkedHashSet<>();
                                chans.put(key, c);
                            }
                            if (!c.urls.contains(it[1])) c.urls.add(it[1]);
                            if (it[0].length() < c.display.length()) c.display = it[0];
                        }
                    }
                    List<String> uniq = new ArrayList<>();
                    for (Chan c : chans.values()) uniq.addAll(c.urls);
                    cb.onProgress("连通验证与测速", 0, uniq.size(), uniq.size() + " 条线路");

                    // ---- 3. 并发验证 + 分片测速 ----
                    final Map<String, Float> speeds = Collections.synchronizedMap(new LinkedHashMap<String, Float>());
                    pool = Executors.newFixedThreadPool(conc);
                    final AtomicInteger done = new AtomicInteger(0);
                    List<Runnable> tasks = new ArrayList<>();
                    for (final String u : uniq) {
                        tasks.add(new Runnable() {
                            @Override
                            public void run() {
                                if (stopped) return;
                                float s = measureOne(u);
                                speeds.put(u, s);
                                int d = done.incrementAndGet();
                                if (d % 20 == 0 || d == uniq.size()) {
                                    cb.onProgress("连通验证与测速", d, uniq.size(), "有速度 " + countPositive(speeds) + " 条");
                                }
                            }
                        });
                    }
                    for (Runnable t : tasks) pool.execute(t);
                    pool.shutdown();
                    try {
                        while (!pool.isTerminated()) {
                            if (stopped) pool.shutdownNow();
                            Thread.sleep(300);
                        }
                    } catch (InterruptedException ignore) { }
                    throwIfStopped();

                    // ---- 4. 每频道优选最快 1 条 ----
                    List<Chan> picked = new ArrayList<>();
                    List<Float> allSpeed = new ArrayList<>();
                    for (Chan c : chans.values()) {
                        String best = null;
                        float bestS = 0;
                        for (String u : c.urls) {
                            float s = speeds.containsKey(u) ? speeds.get(u) : 0;
                            if (s > bestS) { bestS = s; best = u; }
                        }
                        if (best == null) continue;
                        if (bestS < minSpeed) continue; // 淘汰低速
                        c.best = best;
                        c.bestSpeed = bestS;
                        allSpeed.add(bestS);
                        picked.add(c);
                    }
                    if (picked.isEmpty()) {
                        running = false;
                        cb.onError("没有线路达到淘汰线（" + minSpeed + " KB/s），请降低淘汰线或更换订阅");
                        return;
                    }
                    final Map<String, Integer> order = new LinkedHashMap<>();
                    for (int i = 0; i < GROUP_ORDER.length; i++) order.put(GROUP_ORDER[i], i);
                    Collections.sort(picked, new java.util.Comparator<Chan>() {
                        @Override
                        public int compare(Chan a, Chan b) {
                            int ia = order.containsKey(a.group) ? order.get(a.group) : 99;
                            int ib = order.containsKey(b.group) ? order.get(b.group) : 99;
                            if (ia != ib) return ia - ib;
                            return a.display.compareToIgnoreCase(b.display);
                        }
                    });
                    Collections.sort(allSpeed);

                    // ---- 5. 生成配置 JSON ----
                    StringBuilder sb = new StringBuilder("{\"playerModeEnabled\":true,\"sources\":[");
                    for (int i = 0; i < picked.size(); i++) {
                        if (i > 0) sb.append(",");
                        Chan c = picked.get(i);
                        sb.append("{\"name\":\"").append(esc(c.display))
                          .append("\",\"url\":\"").append(esc(c.best)).append("\"}");
                    }
                    sb.append("]}");
                    float median = allSpeed.get(allSpeed.size() / 2);
                    running = false;
                    cb.onDone(sb.toString(), picked.size(), Math.round(median));
                } catch (StopException se) {
                    running = false;
                    cb.onError("已停止");
                } catch (Exception e) {
                    running = false;
                    cb.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
                } finally {
                    running = false;
                    if (pool != null) pool.shutdownNow();
                }
            }
        }, "SourceScanner").start();
    }

    // ---------- 数据结构 ----------

    private static class Chan {
        String display;
        String group;
        LinkedHashSet<String> urls;
        String best;
        float bestSpeed;
    }

    private static class StopException extends RuntimeException { }

    private void throwIfStopped() { if (stopped) throw new StopException(); }

    // ---------- HTTP ----------

    private static String httpGet(String urlStr, int timeoutMs, StringBuilder captureHead) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("User-Agent", UA);
            int code = conn.getResponseCode();
            if (code != 200) return null;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) != -1) {
                buf.write(tmp, 0, n);
                if (buf.size() > 512 * 1024) break; // 订阅/清单最多读 512KB
            }
            in.close();
            return buf.toString("UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Exception ignore) { }
        }
    }

    /**
     * 连通验证 + 分片级测速：HLS 下载一个完整 ts 分片按「大小÷耗时」计速；
     * FLV/TS 匀速流用 6 秒窗口。返回 KB/s，0 表示不可用。
     */
    private float measureOne(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("User-Agent", UA);
            int code = conn.getResponseCode();
            if (code != 200) return 0;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n, headLimit = 128 * 1024;
            while (head.size() < headLimit && (n = in.read(tmp)) != -1) head.write(tmp, 0, n);
            byte[] headBytes = head.toByteArray();
            if (headBytes.length >= 3 && headBytes[0] == 'F' && headBytes[1] == 'L' && headBytes[2] == 'V') {
                return windowSpeed(in, headBytes.length, 6000);
            }
            if (headBytes.length >= 1 && headBytes[0] == 0x47) {
                return windowSpeed(in, headBytes.length, 6000);
            }
            String text = new String(headBytes, 0, Math.min(headBytes.length, 16384), "UTF-8");
            if (!text.contains("#EXTM3U")) return 0;
            String manifestUrl = urlStr;
            if (text.contains("#EXT-X-STREAM-INF")) {
                String sub = firstUriAfterStreamInf(text);
                if (sub == null) return 0;
                String subUrl = resolve(manifestUrl, sub);
                String subText = httpGet(subUrl, 12000, null);
                if (subText == null) return 0;
                text = subText;
                manifestUrl = subUrl;
            }
            String seg = firstSegment(text);
            if (seg == null) return 0;
            String segUrl = resolve(manifestUrl, seg);
            long t0 = android.os.SystemClock.uptimeMillis();
            HttpURLConnection c2 = (HttpURLConnection) new URL(segUrl).openConnection();
            c2.setConnectTimeout(6000);
            c2.setReadTimeout(12000);
            c2.setRequestProperty("User-Agent", UA);
            try {
                if (c2.getResponseCode() != 200) return 0;
                InputStream sIn = c2.getInputStream();
                ByteArrayOutputStream segBuf = new ByteArrayOutputStream();
                byte[] b2 = new byte[16384];
                while ((n = sIn.read(b2)) != -1) {
                    segBuf.write(b2, 0, n);
                    if (segBuf.size() > 8 * 1024 * 1024) break;
                }
                sIn.close();
                long dt = android.os.SystemClock.uptimeMillis() - t0;
                if (segBuf.size() < 10 * 1024 || dt < 30) return 0; // 过小视为无效
                return segBuf.size() / 1024f / Math.max(dt, 1) * 1000f;
            } finally {
                try { c2.disconnect(); } catch (Exception ignore) { }
            }
        } catch (Exception e) {
            return 0;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Exception ignore) { }
        }
    }

    private float windowSpeed(InputStream in, int already, long windowMs) {
        long t0 = android.os.SystemClock.uptimeMillis();
        long bytes = already;
        try {
            byte[] tmp = new byte[16384];
            int n;
            while (android.os.SystemClock.uptimeMillis() - t0 < windowMs) {
                n = in.read(tmp);
                if (n == -1) break;
                bytes += n;
            }
            in.close();
        } catch (Exception ignore) { }
        float sec = Math.max((android.os.SystemClock.uptimeMillis() - t0) / 1000f, 0.1f);
        return bytes / 1024f / sec;
    }

    // ---------- 解析 ----------

    private List<String[]> parseSub(String text) {
        List<String[]> out = new ArrayList<>();
        String[] lines = text.split("\r?\n");
        if (text.contains("#EXTM3U")) {
            String pend = null;
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#EXTINF")) {
                    int c = line.indexOf(',');
                    pend = c >= 0 ? line.substring(c + 1).trim() : null;
                } else if (line.startsWith("#")) {
                    continue;
                } else if (line.startsWith("http://") || line.startsWith("https://")) {
                    if (pend != null) out.add(new String[]{pend, line});
                    pend = null;
                }
            }
        } else {
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty() || line.contains("#genre#")) continue;
                int c = line.indexOf(',');
                if (c <= 0) continue;
                String name = line.substring(0, c).trim();
                String[] urls = line.substring(c + 1).split("#");
                for (String u : urls) {
                    u = u.trim();
                    if (u.startsWith("http://") || u.startsWith("https://")) out.add(new String[]{name, u});
                }
            }
        }
        return out;
    }

    private static String firstSegment(String m3u) {
        String[] lines = m3u.split("\r?\n");
        for (String l : lines) {
            l = l.trim();
            if (!l.isEmpty() && !l.startsWith("#")) return l;
        }
        return null;
    }

    private static String firstUriAfterStreamInf(String m3u) {
        String[] lines = m3u.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().startsWith("#EXT-X-STREAM-INF") && i + 1 < lines.length) {
                return lines[i + 1].trim();
            }
        }
        return null;
    }

    private static String resolve(String base, String ref) {
        try {
            return new java.net.URL(new java.net.URL(base), ref).toString();
        } catch (Exception e) {
            return ref;
        }
    }

    // ---------- 归一化与分类（与桌面工具一致） ----------

    static String normName(String name) {
        if (name == null) return "";
        String n = name.replaceAll("[\\s\\-\\_·()\\[\\]（）【】]", "").toUpperCase();
        n = n.replaceAll("CCTV(\\d)综合", "CCTV$1");
        n = n.replace("CCTV5+", "CCTV5PLUS");
        n = n.replaceAll("超清|高清|蓝光|标清|HD$", "");
        return n;
    }

    static String classify(String name) {
        String n = normName(name);
        if (n.startsWith("CCTV") || n.startsWith("CGTN") || n.startsWith("CETV") || name.contains("央视")) {
            return "央视频道";
        }
        if (name.contains("卫视")) return "卫视频道";
        if (name.matches(".*(体育|足球|篮球|NBA|中超|英超).*")) return "体育频道";
        if (name.matches(".*(少儿|卡通|动画|动漫|金鹰|亲子|宝贝).*")) return "少儿频道";
        if (name.contains("纪录") || name.contains("地理")) return "纪录频道";
        if (n.contains("CHC") || name.matches(".*(电影|影院|剧场).*")) return "电影频道";
        if (name.matches(".*(音乐|MTV|MV).*")) return "音乐频道";
        if (name.contains("春晚")) return "春晚回顾";
        if (name.matches("(?i).*(TVBS|东森|中天|三立|TVB|翡翠|凤凰|澳门|民视|八大|纬来|年代|华视|中视|台视|好消息|点睛|天映|寰宇|明珠|美亚|Astro|Arirang|KBS|MBC|SBS|NHK|Fuji|CNN|BBC|HBO|Fox|Discovery|Cartoon|Disney|Nick|History|EBC).*")) {
            return "港澳台及国际";
        }
        if (!name.contains("卫视") && name.matches(".*(北京|上海|广东|深圳|浙江|江苏|湖南|湖北|山东|河南|河北|四川|重庆|天津|福建|江西|安徽|辽宁|吉林|黑龙江|陕西|甘肃|青海|宁夏|新疆|广西|云南|贵州|山西|内蒙古|海南|西藏|城市|都市|新闻综合|影视|生活|法治|教育|纪实).*")) {
            return "地方频道";
        }
        Matcher m = P_CJK.matcher(name);
        if (m.find()) return "其他频道";
        return "海外频道";
    }

    private static int countPositive(Map<String, Float> m) {
        int c = 0;
        for (Float v : m.values()) if (v != null && v > 0) c++;
        return c;
    }

    private static String shortUrl(String u) {
        if (u == null) return "";
        return u.length() <= 60 ? u : u.substring(0, 60) + "…";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
