package com.newslive.app;

import java.util.Calendar;

/**
 * 时辰计算工具类。
 *
 * 传统时辰制：一天12个时辰，每个时辰2小时。
 * 每个时辰分为「初」「正」两段，各1小时；每段4刻，每刻15分钟。
 *
 * 示例：14:30 → 未时 → 未正 → 第3刻（0-3）→ 二刻（1-based: 一二三四）
 * 故显示「未正二刻」。
 */
public class ShichenUtil {

    // 时辰地支名，索引 = 时辰序号(0-11)，对应 23:00 起算
    private static final String[] ZHI = {
            "子", "丑", "寅", "卯", "辰", "巳",
            "午", "未", "申", "酉", "戌", "亥"
    };

    /**
     * 根据当前时间计算时辰名称（含初正与刻数）。
     *
     * @param hour   24小时制小时 (0-23)
     * @param minute 分钟 (0-59)
     * @return 如 "未正二刻"
     */
    public static String getShichen(int hour, int minute) {
        // 23:00-00:59 属于子时（时辰序号0）
        // 时辰序号 = ((hour + 1) / 2) % 12
        int shichenIndex = ((hour + 1) / 2) % 12;
        String zhi = ZHI[shichenIndex];

        // 时辰内偏移分钟：把 23:00 作为第0分钟起点
        int offsetMin;
        if (hour == 23) {
            offsetMin = minute;                     // 23:00 起
        } else if (hour == 0) {
            offsetMin = 60 + minute;               // 23:00 的下一小时
        } else {
            // 一般情况：该时辰起始小时
            int startHour = (shichenIndex == 0) ? 23 : (shichenIndex * 2 - 1);
            offsetMin = (hour - startHour) * 60 + minute;
            if (offsetMin < 0) offsetMin += 1440;  // 跨日修正
        }

        // 0-59 分钟 = 初；60-119 分钟 = 正
        boolean isZheng = offsetMin >= 60;
        int minInHalf = isZheng ? (offsetMin - 60) : offsetMin;   // 半时辰内分钟数 0-59

        // 刻数：每刻15分钟，0-3 → 一二三四
        int keIndex = minInHalf / 15;   // 0-3
        String keName;
        switch (keIndex) {
            case 0:  keName = "一刻"; break;
            case 1:  keName = "二刻"; break;
            case 2:  keName = "三刻"; break;
            default: keName = "四刻"; break;
        }

        return zhi + (isZheng ? "正" : "初") + keName;
    }

    /**
     * 便捷重载。
     */
    public static String getShichen(Calendar cal) {
        return getShichen(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
    }
}
