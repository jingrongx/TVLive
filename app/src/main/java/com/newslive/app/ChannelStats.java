package com.newslive.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

/**
 * 频道实时累积稳定性评级系统。
 * 按流 URL 逐频道累积:速度采样、卡顿次数/时长、错误次数、播放时长,持久化到 SharedPreferences。
 * 评分(0-100):速度40分(2Mbps 满分) + 稳定45分(卡顿<3次/分钟不扣) + 可靠15分(错误少不扣)。
 * 菜单里显示 ▲/△/⚠ + 分数,实时速度显示在频道信息栏。
 */
public final class ChannelStats {

    private static ChannelStats sInstance;
    private final SharedPreferences prefs;
    private final JSONObject data = new JSONObject();

    private String curUrl;
    private long bufStart;
    private long playStart;
    private long bwBytes;
    private long bwTimeMs;
    private long lastSpeedAt;
    private float liveKbps;

    private static final String KEY = "channel_stats_v1";

    private ChannelStats(Context c) {
        prefs = c.getApplicationContext()
                .getSharedPreferences("newslive_stats", Context.MODE_PRIVATE);
        try {
            JSONObject saved = new JSONObject(prefs.getString(KEY, "{}"));
            java.util.Iterator<String> it = saved.keys();
            while (it.hasNext()) {
                String k = it.next();
                data.put(k, saved.getJSONObject(k));
            }
        } catch (Exception e) {
            Log.w("ChannelStats", "load fail", e);
        }
    }

    public static synchronized ChannelStats get(Context c) {
        if (sInstance == null) sInstance = new ChannelStats(c);
        return sInstance;
    }

    private synchronized JSONObject ensure(String url) {
        try {
            if (!data.has(url)) data.put(url, new JSONObject());
            return data.getJSONObject(url);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** 开始播放某频道(自动结束上一个的计时) */
    public synchronized void begin(String url) {
        stop();
        if (url == null || url.isEmpty()) return;
        curUrl = url;
        ensure(url);
        playStart = SystemClock.elapsedRealtime();
        bwBytes = 0;
        bwTimeMs = 0;
        lastSpeedAt = SystemClock.elapsedRealtime();
        liveKbps = 0;
    }

    /** 停止播放(切台/退出),结算播放时长并持久化 */
    public synchronized void stop() {
        if (curUrl == null) return;
        try {
            JSONObject st = ensure(curUrl);
            if (playStart > 0) {
                long sec = (SystemClock.elapsedRealtime() - playStart) / 1000;
                st.put("ps", st.optLong("ps") + Math.min(sec, 3600 * 4));
            }
            if (bufStart > 0) {
                st.put("bc", st.optLong("bc") + 1);
                st.put("bt", st.optLong("bt") + (SystemClock.elapsedRealtime() - bufStart));
                bufStart = 0;
            }
            playStart = 0;
            save();
        } catch (Exception e) {
            Log.w("ChannelStats", "stop fail", e);
        }
        curUrl = null;
    }

    /** 进入缓冲 */
    public synchronized void bufferStart() {
        if (curUrl == null) return;
        if (bufStart == 0) bufStart = SystemClock.elapsedRealtime();
    }

    /** 缓冲结束(恢复播放) */
    public synchronized void bufferEnd() {
        if (curUrl == null || bufStart == 0) return;
        try {
            JSONObject st = ensure(curUrl);
            st.put("bc", st.optLong("bc") + 1);
            st.put("bt", st.optLong("bt") + (SystemClock.elapsedRealtime() - bufStart));
        } catch (Exception ignore) { }
        bufStart = 0;
    }

    /** 播放错误 */
    public synchronized void error() {
        if (curUrl == null) return;
        try {
            JSONObject st = ensure(curUrl);
            st.put("ec", st.optLong("ec") + 1);
        } catch (Exception ignore) { }
    }

    /** 带宽估计回调(AnalyticsListener),累计到 5 秒窗口算一次实时速度 */
    public synchronized void bandwidth(long bytes, long timeMs) {
        if (curUrl == null || bytes <= 0 || timeMs <= 0) return;
        bwBytes += bytes;
        bwTimeMs += timeMs;
        long now = SystemClock.elapsedRealtime();
        if (now - lastSpeedAt >= 5000 && bwTimeMs > 500) {
            liveKbps = (float) (bwBytes * 8L / 1000L) / (bwTimeMs / 1000f);
            try {
                JSONObject st = ensure(curUrl);
                st.put("sp", st.optDouble("sp") + liveKbps);
                st.put("n", st.optLong("n") + 1);
            } catch (Exception ignore) { }
            bwBytes = 0;
            bwTimeMs = 0;
            lastSpeedAt = now;
        }
    }

    /** 当前频道实时速度(Kbps),无数据返回 -1 */
    public synchronized float liveKbps() {
        return liveKbps;
    }

    /** 稳定性评分 0-100;无足够数据返回 -1 */
    public static int score(JSONObject st) {
        if (st == null) return -1;
        long n = st.optLong("n");
        long ps = st.optLong("ps");
        if (n < 3 || ps < 60) return -1; // 样本不足:至少3次速度采样且播放1分钟以上
        double speedKbps = st.optDouble("sp") / n;
        double playMin = Math.max(ps / 60.0, 1.0);
        double bufPerMin = st.optLong("bc") / playMin;
        double errPerMin = st.optLong("ec") / playMin;
        double speedScore = 40.0 * Math.min(speedKbps / 2000.0, 1.0);
        double stabScore = 45.0 * (1.0 - Math.min(bufPerMin / 3.0, 1.0));
        double relScore = 15.0 * (1.0 - Math.min(errPerMin / 0.2, 1.0));
        return (int) Math.max(0, Math.min(100, Math.round(speedScore + stabScore + relScore)));
    }

    /** 查询某 URL 的评分;-1=无数据 */
    public synchronized int scoreOf(String url) {
        try {
            return score(data.has(url) ? data.getJSONObject(url) : null);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 菜单显示用:分数带符号,无数据返回空串 */
    public synchronized String badge(String url) {
        int s = scoreOf(url);
        if (s < 0) return "";
        return s >= 80 ? "  ▲" + s : (s >= 60 ? "  △" + s : "  ⚠" + s);
    }

    /** 配置页/调试用:原始数据 */
    public synchronized String raw() {
        return data.toString();
    }

    private void save() {
        try {
            prefs.edit().putString(KEY, data.toString()).apply();
        } catch (Exception ignore) { }
    }

    /** 强制持久化(切台/退出时调用) */
    public synchronized void flush() {
        save();
    }
}
