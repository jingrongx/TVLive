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
    private volatile long startAt = 0;

    /** 运行中且未超时(15 分钟兜底:卡死任务自动视为过期,允许重新启动) */
    public boolean isRunning() {
        return running && (android.os.SystemClock.uptimeMillis() - startAt < 15 * 60 * 1000L);
    }

    /** 中途停止（当前进行中的 HTTP 请求会自然超时后退出） */
    public void stop() { stopped = true; }

    public void scan(final String[] subUrls, final int concurrency, final int minSpeedKB, final Callback cb) {
        if (running) {
            cb.onError("已有优选任务在运行");
            return;
        }
        running = true;
        stopped = false;
        startAt = android.os.SystemClock.uptimeMillis();
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
                            String disp = cleanDisplay(it[0]);
                            if (disp == null) continue;
                            String key = normName(disp);
                            if (key.isEmpty()) continue;
                            Chan c = chans.get(key);
                            if (c == null) {
                                c = new Chan();
                                c.display = disp;
                                c.group = classify(disp);
                                c.urls = new LinkedHashSet<>();
                                chans.put(key, c);
                            }
                            if (!c.urls.contains(it[1])) c.urls.add(it[1]);
                            if (disp.length() < c.display.length()) c.display = disp;
                        }
                    }
                    List<String> uniq = new ArrayList<>();
                    for (Chan c : chans.values()) uniq.addAll(c.urls);
                    cb.onProgress("连通验证与测速", 0, uniq.size(), uniq.size() + " 条线路");

                    // ---- 3. 并发验证 + 分片测速 ----
                    final Map<String, Float> speeds = Collections.synchronizedMap(new LinkedHashMap<String, Float>());
                    pool = Executors.newFixedThreadPool(conc, new java.util.concurrent.ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r);
                            t.setDaemon(true);
                            return t;
                        }
                    });
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
                    final long measureDeadline = android.os.SystemClock.uptimeMillis() + 5 * 60 * 1000L;
                    for (Runnable t : tasks) pool.execute(t);
                    pool.shutdown();
                    try {
                        while (!pool.isTerminated()) {
                            if (stopped) pool.shutdownNow();
                            if (android.os.SystemClock.uptimeMillis() > measureDeadline) {
                                // 整体兜底:个别任务僵死(DNS挂起/半开连接)时强断,未完成的按失败计
                                pool.shutdownNow();
                                cb.onProgress("测速超时兜底", done.get(), uniq.size(),
                                        "部分源响应过慢，按失败处理");
                                long w0 = android.os.SystemClock.uptimeMillis();
                                while (!pool.isTerminated()
                                        && android.os.SystemClock.uptimeMillis() - w0 < 3000) {
                                    Thread.sleep(200);
                                }
                                break;
                            }
                            Thread.sleep(300);
                        }
                    } catch (InterruptedException ignore) { }
                    throwIfStopped();

                    // ---- 4. 每频道优选最快前3条(同名连续多行,第一行最快;卡顿时可在同频道线路内轮换) ----
                    List<Chan> picked = new ArrayList<>();
                    List<Float> allSpeed = new ArrayList<>();
                    for (Chan c : chans.values()) {
                        List<String> ranked = new ArrayList<>(c.urls);
                        Collections.sort(ranked, (a, b) -> Float.compare(
                                speeds.containsKey(b) ? speeds.get(b) : 0,
                                speeds.containsKey(a) ? speeds.get(a) : 0));
                        List<String> good = new ArrayList<>();
                        for (String u : ranked) {
                            float s = speeds.containsKey(u) ? speeds.get(u) : 0;
                            if (s >= minSpeed) {
                                good.add(u);
                                if (good.size() >= 3) break;
                            }
                        }
                        if (good.isEmpty()) continue; // 该频道所有线路低速,淘汰
                        c.top = good;
                        c.bestSpeed = speeds.get(good.get(0));
                        allSpeed.add(c.bestSpeed);
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
                    int outLines = 0;
                    boolean firstSrc = true;
                    for (Chan c : picked) {
                        for (int li = 0; li < c.top.size(); li++) {
                            if (!firstSrc) sb.append(",");
                            firstSrc = false;
                            float s = speeds.containsKey(c.top.get(li)) ? speeds.get(c.top.get(li)) : 0;
                            sb.append("{\"name\":\"").append(esc(c.display))
                              .append("\",\"url\":\"").append(esc(c.top.get(li)))
                              .append("\",\"group\":\"").append(esc(c.group))
                              .append("\",\"speed\":").append(Math.max(1, Math.round(s)))
                              .append("}");
                            outLines++;
                        }
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
        java.util.List<String> top = new ArrayList<>(); // 最快前3条(降序)
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
            long deadline = android.os.SystemClock.uptimeMillis() + timeoutMs; // 总时长窗
            while ((n = in.read(tmp)) != -1) {
                buf.write(tmp, 0, n);
                if (buf.size() > 512 * 1024) break; // 订阅/清单最多读 512KB
                if (android.os.SystemClock.uptimeMillis() > deadline) break; // 慢速滴流兜底
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
            long headDeadline = android.os.SystemClock.uptimeMillis() + 10000; // 总窗:防慢速滴流
            while (head.size() < headLimit && (n = in.read(tmp)) != -1) {
                head.write(tmp, 0, n);
                if (android.os.SystemClock.uptimeMillis() > headDeadline) break;
            }
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
            c2.setReadTimeout(8000);
            c2.setRequestProperty("User-Agent", UA);
            try {
                if (c2.getResponseCode() != 200) return 0;
                InputStream sIn = c2.getInputStream();
                ByteArrayOutputStream segBuf = new ByteArrayOutputStream();
                byte[] b2 = new byte[16384];
                long segDeadline = t0 + 12000; // 分片下载总时长窗:readTimeout 只管单次 read,
                int n2;                        // 慢速滴流源(如 mp4/慢CDN)必须靠总窗兜底,否则任务挂死
                while ((n2 = sIn.read(b2)) != -1) {
                    segBuf.write(b2, 0, n2);
                    if (segBuf.size() > 8 * 1024 * 1024) break;
                    if (android.os.SystemClock.uptimeMillis() > segDeadline) break;
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
            return parseM3uLines(lines);
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

    /** m3u:频道名取 #EXTINF 最后一个逗号之后(属性值如 user-agent 可能含逗号) */
    private List<String[]> parseM3uLines(String[] lines) {
        List<String[]> out = new ArrayList<>();
        String pend = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF")) {
                int c = line.lastIndexOf(',');
                pend = c >= 0 ? line.substring(c + 1).trim() : null;
            } else if (line.startsWith("#")) {
                continue;
            } else if (line.startsWith("http://") || line.startsWith("https://")) {
                if (pend != null) out.add(new String[]{pend, line});
                pend = null;
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

    private static final String[][] ZH_MAP = {
            {"(?i)(?:Nei Monggol|Inner Mongol\\w*)\\s+TV", "内蒙古电视"},
            {"(?i)Anhui\\s+Satellite\\s+TV", "安徽卫视"},
            {"(?i)Beijing\\s+Satellite\\s+TV", "北京卫视"},
            {"(?i)Chongqing\\s+Satellite\\s+TV", "重庆卫视"},
            {"(?i)Dragon\\s+TV", "东方卫视"},
            {"(?i)Fujian\\s+(?:Southeast|Satellite)\\s+TV", "东南卫视"},
            {"(?i)Gansu\\s+Satellite\\s+TV", "甘肃卫视"},
            {"(?i)Guangdong\\s+Satellite\\s+TV", "广东卫视"},
            {"(?i)Guangxi\\s+Satellite\\s+TV", "广西卫视"},
            {"(?i)Guizhou\\s+Satellite\\s+TV", "贵州卫视"},
            {"(?i)Hainan\\s+Satellite\\s+TV", "海南卫视"},
            {"(?i)Hebei\\s+Satellite\\s+TV", "河北卫视"},
            {"(?i)Heilongjiang\\s+Satellite\\s+TV", "黑龙江卫视"},
            {"(?i)Henan\\s+Satellite\\s+TV", "河南卫视"},
            {"(?i)Hubei\\s+Satellite\\s+TV", "湖北卫视"},
            {"(?i)Hunan\\s+(?:Satellite\\s+)?TV", "湖南卫视"},
            {"(?i)Jiangsu\\s+Satellite\\s+TV", "江苏卫视"},
            {"(?i)Jilin\\s+Satellite\\s+TV", "吉林卫视"},
            {"(?i)Liaoning\\s+Satellite\\s+TV", "辽宁卫视"},
            {"(?i)Ningxia\\s+Satellite\\s+TV", "宁夏卫视"},
            {"(?i)Qinghai\\s+Satellite\\s+TV", "青海卫视"},
            {"(?i)Shandong\\s+Satellite\\s+TV", "山东卫视"},
            {"(?i)Shanghai\\s+Satellite\\s+TV", "上海卫视"},
            {"(?i)Shanxi\\s+Satellite\\s+TV", "山西卫视"},
            {"(?i)Shenzhen\\s+Satellite\\s+TV", "深圳卫视"},
            {"(?i)Sichuan\\s+Satellite\\s+TV", "四川卫视"},
            {"(?i)Tianjin\\s+Satellite\\s+TV", "天津卫视"},
            {"(?i)Tibet\\s+Satellite\\s+TV", "西藏卫视"},
            {"(?i)Xinjiang\\s+Satellite\\s+TV", "新疆卫视"},
            {"(?i)Yunnan\\s+Satellite\\s+TV", "云南卫视"},
            {"(?i)Zhejiang\\s+Satellite\\s+TV", "浙江卫视"},
            {"(?i)Shaanxi\\s+(?:Satellite\\s+TV|West\\s+TV)", "陕西卫视"},
            {"(?i)Jiangxi\\s+Satellite\\s+TV", "江西卫视"},
            {"(?i)CGTN\\s+Documentary", "CGTN纪录"},
            {"(?i)CGTN\\s+Spanish", "CGTN西语"},
            {"(?i)CGTN\\s+French", "CGTN法语"},
            {"(?i)CGTN\\s+Arabic", "CGTN阿语"},
            {"(?i)CGTN\\s+Russian", "CGTN俄语"},
    };

    private static final java.util.regex.Pattern P_RES =
            java.util.regex.Pattern.compile("\\s*[（(]\\d{3,4}[piP]?\\s*\\)\\s*$");
    private static final java.util.regex.Pattern P_BRACKET =
            java.util.regex.Pattern.compile("\\s*\\[[^\\]]*\\]\\s*$");
    private static final java.util.regex.Pattern P_DOMAIN =
            java.util.regex.Pattern.compile("^(?:[\\w-]+\\.)+[a-z]{2,}$", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** 清洗展示名:循环剥离分辨率后缀/方括号标记,英文台名映射中文;域名式脏名返回 null */
    static String cleanDisplay(String name) {
        if (name == null) return null;
        String n = name.trim();
        if (P_DOMAIN.matcher(n).matches()) return null;
        while (true) {
            String n2 = P_RES.matcher(n).replaceFirst("");
            n2 = P_BRACKET.matcher(n2).replaceFirst("");
            if (n2.equals(n)) break;
            n = n2;
        }
        n = n.replaceAll("[\\s\\-_]+$", "").replaceAll("^[\\s\\-_]+", "");
        for (String[] m : ZH_MAP) {
            if (n.matches("(?s).*" + m[0] + ".*")) {
                n = m[1];
                break;
            }
        }
        n = n.trim();
        return n.isEmpty() ? null : n;
    }

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
