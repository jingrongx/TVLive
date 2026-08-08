package com.newslive.app;

import android.util.Log;

/**
 * 农历日期转换 + 24节气计算工具类
 *
 * 24位数据表格式（被CalendarView/Android-PickerView等知名库使用）：
 *   bits 0-4:  正月初一的公历日期 (1-31)
 *   bits 5-6:  正月初一的月份 (1=January, 2=February)
 *   bits 7-19: 13个月大小月分布 (bit19=正月, bit7=闰月, 1=大月30天, 0=小月29天)
 *   bits 20-23: 闰月月份 (0=无闰月)
 */
public class LunarCalendar {

    private static final int[] LUNAR_INFO = {
        0x84B6BF,/*1900*/
        0x04AE53,0x0A5748,0x5526BD,0x0D2650,0x0D9544,0x46AAB9,0x056A4D,0x09AD42,0x24AEB6,0x04AE4A,/*1901-1910*/
        0x6A4DBE,0x0A4D52,0x0D2546,0x5D52BA,0x0B544E,0x0D6A43,0x296D37,0x095B4B,0x749BC1,0x049754,/*1911-1920*/
        0x0A4B48,0x5B25BC,0x06A550,0x06D445,0x4ADAB8,0x02B64D,0x095742,0x2497B7,0x04974A,0x664B3E,/*1921-1930*/
        0x0D4A51,0x0EA546,0x56D4BA,0x05AD4E,0x02B644,0x393738,0x092E4B,0x7C96BF,0x0C9553,0x0D4A48,/*1931-1940*/
        0x6DA53B,0x0B554F,0x056A45,0x4AADB9,0x025D4D,0x092D42,0x2C95B6,0x0A954A,0x7B4ABD,0x06CA51,/*1941-1950*/
        0x0B5546,0x555ABB,0x04DA4E,0x0A5B43,0x352BB8,0x052B4C,0x8A953F,0x0E9552,0x06AA48,0x6AD53C,/*1951-1960*/
        0x0AB54F,0x04B645,0x4A5739,0x0A574D,0x052642,0x3E9335,0x0D9549,0x75AABE,0x056A51,0x096D46,/*1961-1970*/
        0x54AEBB,0x04AD4F,0x0A4D43,0x4D26B7,0x0D254B,0x8D52BF,0x0B5452,0x0B6A47,0x696D3C,0x095B50,/*1971-1980*/
        0x049B45,0x4A4BB9,0x0A4B4D,0xAB25C2,0x06A554,0x06D449,0x6ADA3D,0x0AB651,0x095746,0x5497BB,/*1981-1990*/
        0x04974F,0x064B44,0x36A537,0x0EA54A,0x86B2BF,0x05AC53,0x0AB647,0x5936BC,0x092E50,0x0C9645,/*1991-2000*/
        0x4D4AB8,0x0D4A4C,0x0DA541,0x25AAB6,0x056A49,0x7AADBD,0x025D52,0x092D47,0x5C95BA,0x0A954E,/*2001-2010*/
        0x0B4A43,0x4B5537,0x0AD54A,0x955ABF,0x04BA53,0x0A5B48,0x652BBC,0x052B50,0x0A9345,0x474AB9,/*2011-2020*/
        0x06AA4C,0x0AD541,0x24DAB6,0x04B64A,0x6a573D,0x0A4E51,0x0D2646,0x5E933A,0x0D534D,0x05AA43,/*2021-2030*/
        0x36B537,0x096D4B,0xB4AEBF,0x04AD53,0x0A4D48,0x6D25BC,0x0D254F,0x0D5244,0x5DAA38,0x0B5A4C,/*2031-2040*/
        0x056D41,0x24ADB6,0x049B4A,0x7A4BBE,0x0A4B51,0x0AA546,0x5B52BA,0x06D24E,0x0ADA42,0x355B37,/*2041-2050*/
        0x09374B,0x8497C1,0x049753,0x064B48,0x66A53C,0x0EA54F,0x06AA44,0x4AB638,0x0AAE4C,0x092E42,/*2051-2060*/
        0x3C9735,0x0C9649,0x7D4ABD,0x0D4A51,0x0DA545,0x55AABA,0x056A4E,0x0A6D43,0x452EB7,0x052D4B,/*2061-2070*/
        0x8A95BF,0x0A9553,0x0B4A47,0x6B553B,0x0AD54F,0x055A45,0x4A5D38,0x0A5B4C,0x052B42,0x3A93B6,/*2071-2080*/
        0x069349,0x7729BD,0x06AA51,0x0AD546,0x54DABA,0x04B64E,0x0A5743,0x452738,0x0D264A,0x8E933E,/*2081-2090*/
        0x0D5252,0x0DAA47,0x66B53B,0x056D4F,0x04AE45,0x4A4EB9,0x0A4D4C,0x0D1541,0x2D92B5/*2091-2099*/
    };

    private static final String[] SOLAR_TERMS = {
        "小寒","大寒","立春","雨水","惊蛰","春分","清明","谷雨",
        "立夏","小满","芒种","夏至","小暑","大暑","立秋","处暑",
        "白露","秋分","寒露","霜降","立冬","小雪","大雪","冬至"
    };

    // 21世纪(2000-2099)节气C值
    private static final double[] SOLAR_TERM_C_21 = {
        5.4055, 20.12, 3.87, 18.73, 5.63, 20.646, 4.81, 20.1,
        5.52, 21.04, 5.678, 21.37, 7.108, 22.83, 7.5, 23.13,
        7.646, 23.042, 8.318, 23.438, 7.438, 22.36, 7.18, 22.6
    };
    // 20世纪(1900-1999)节气C值
    private static final double[] SOLAR_TERM_C_20 = {
        6.11, 20.84, 4.6295, 19.4599, 6.3826, 21.4155, 5.59, 20.888,
        6.318, 21.86, 6.5, 22.2, 7.928, 23.65, 8.35, 23.95,
        8.44, 23.822, 9.098, 24.218, 8.218, 23.08, 7.9, 22.6
    };

    private static final String[] TIAN_GAN = {
        "甲","乙","丙","丁","戊","己","庚","辛","壬","癸"
    };
    private static final String[] DI_ZHI = {
        "子","丑","寅","卯","辰","巳","午","未","申","酉","戌","亥"
    };
    private static final String[] MONTH_NAMES = {
        "正月","二月","三月","四月","五月","六月",
        "七月","八月","九月","十月","冬月","腊月"
    };
    private static final String[] DAY_NAMES = {
        "初一","初二","初三","初四","初五","初六","初七","初八","初九","初十",
        "十一","十二","十三","十四","十五","十六","十七","十八","十九","二十",
        "廿一","廿二","廿三","廿四","廿五","廿六","廿七","廿八","廿九","三十"
    };

    // ==================== 24位数据表解析 ====================

    /**
     * 获取农历年的正月初一对应的公历日期
     * bits 0-4: 日期(1-31), bits 5-6: 月份(1=1月, 2=2月)
     */
    private static int[] getSpringFestival(int year) {
        int info = LUNAR_INFO[year - 1900];
        int day = info & 0x1F;
        int month = (info >> 5) & 0x3;
        if (month == 0) month = 1; // safety
        return new int[]{month, day};
    }

    /**
     * 闰月月份 (0=无闰月)
     * bits 20-23
     */
    private static int leapMonth(int y) {
        return (LUNAR_INFO[y - 1900] >> 20) & 0xF;
    }

    /**
     * 闰月天数 (29或30)
     */
    private static int leapDays(int y) {
        if (leapMonth(y) != 0) {
            // bit 7 对应第13个月(闰月)的大小
            return (LUNAR_INFO[y - 1900] & 0x80) != 0 ? 30 : 29;
        }
        return 0;
    }

    /**
     * 农历某月天数 (m: 1-12, 不含闰月)
     * bits 7-19: bit19=正月, bit18=二月, ..., bit8=十二月, bit7=闰月
     */
    private static int monthDays(int y, int m) {
        return (LUNAR_INFO[y - 1900] & (0x80000 >> (m - 1))) != 0 ? 30 : 29;
    }

    /**
     * 农历年总天数
     */
    private static int lYearDays(int y) {
        int sum = 0;
        for (int i = 1; i <= 12; i++) {
            sum += monthDays(y, i);
        }
        sum += leapDays(y);
        return sum;
    }

    /**
     * 公历日期转Julian Day Number（用于天数差计算）
     */
    private static int toJulianDay(int year, int month, int day) {
        int a = (14 - month) / 12;
        int y = year + 4800 - a;
        int m = month + 12 * a - 3;
        return day + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045;
    }

    // ==================== 农历转换 ====================

    /**
     * 公历转农历
     * 使用数据表中内嵌的正月初一日期，逐年比较，不依赖累计天数
     * @return int[]{农历年, 农历月(1-12), 农历日(1-30), 是否闰月(0/1)}
     */
    public static int[] solarToLunar(int year, int month, int day) {
        Log.i("LunarCalendar", "solarToLunar: " + year + "-" + month + "-" + day);

        // 确定目标公历日期落在哪个农历年
        // 农历年Y的范围：Y年正月初一 ~ Y+1年正月初一-1
        int lunarYear = year;
        int[] sf = getSpringFestival(lunarYear);
        int sfJD = toJulianDay(lunarYear, sf[0], sf[1]);
        int targetJD = toJulianDay(year, month, day);

        if (targetJD < sfJD) {
            // 目标日期在当年正月初一之前，属于上一个农历年
            lunarYear--;
        }

        // 计算从正月初一开始的偏移天数
        sf = getSpringFestival(lunarYear);
        sfJD = toJulianDay(lunarYear, sf[0], sf[1]);
        int offset = targetJD - sfJD;
        Log.i("LunarCalendar", "lunarYear=" + lunarYear + " springFestival=" + lunarYear + "-" + sf[0] + "-" + sf[1] + " offset=" + offset);

        if (offset < 0) {
            Log.e("LunarCalendar", "offset < 0, this shouldn't happen");
            return new int[]{0, 0, 0, 0};
        }

        int leap = leapMonth(lunarYear);
        int lunarMonth = 1;
        int lunarDay = 1;
        boolean isLeap = false;

        // 逐月减去月天数
        for (int m = 1; m <= 12; m++) {
            int days = monthDays(lunarYear, m);
            if (offset < days) {
                lunarMonth = m;
                lunarDay = offset + 1;
                isLeap = false;
                break;
            }
            offset -= days;

            // 如果当前月后面有闰月，检查是否落在闰月内
            if (leap == m) {
                int lDays = leapDays(lunarYear);
                if (offset < lDays) {
                    lunarMonth = m;
                    lunarDay = offset + 1;
                    isLeap = true;
                    break;
                }
                offset -= lDays;
            }

            if (m == 12) {
                // 理论上不会到这里，但作为安全兜底
                lunarMonth = 12;
                lunarDay = offset + 1;
                isLeap = false;
            }
        }

        Log.i("LunarCalendar", "result: year=" + lunarYear + " month=" + lunarMonth + " day=" + lunarDay + " isLeap=" + isLeap + " leapMonth=" + leap);
        return new int[]{lunarYear, lunarMonth, lunarDay, isLeap ? 1 : 0};
    }

    /**
     * 格式化农历日期字符串，如 "丙午年 六月廿五"
     */
    public static String formatLunar(int lunarYear, int lunarMonth, int lunarDay, boolean isLeap) {
        String ganZhi = TIAN_GAN[(lunarYear - 4) % 10] + DI_ZHI[(lunarYear - 4) % 12];
        String monthName = (isLeap ? "闰" : "") + MONTH_NAMES[lunarMonth - 1];
        String dayName = DAY_NAMES[lunarDay - 1];
        return ganZhi + "年 " + monthName + dayName;
    }

    // ==================== 24节气计算 ====================

    /**
     * 计算某年第n个节气的日期(几号)
     * n: 0=小寒, 1=大寒, 2=立春, ..., 23=冬至
     * 公式: D = [Y*0.2422 + C] - [Y/4], Y为年份后两位
     */
    private static int getSolarTermDay(int year, int n) {
        int y = year % 100;
        double c = (year >= 2000) ? SOLAR_TERM_C_21[n] : SOLAR_TERM_C_20[n];
        return (int) Math.floor(y * 0.2422 + c) - (int) Math.floor(y / 4.0);
    }

    /**
     * 获取节气信息
     * 当天是节气: 返回 "今日立秋"
     * 非节气: 返回 "距处暑 15天"
     */
    public static String getJieqiInfo(int year, int month, int day) {
        int todayJD = toJulianDay(year, month, day);

        // 先检查今天是否是某个节气
        for (int n = 0; n < 24; n++) {
            int termMonth = n / 2 + 1;
            if (termMonth != month) continue;
            int termDay = getSolarTermDay(year, n);
            if (termDay == day) {
                return "今日" + SOLAR_TERMS[n];
            }
        }

        // 找下一个节气
        for (int n = 0; n < 24; n++) {
            int termMonth = n / 2 + 1;
            int termDay = getSolarTermDay(year, n);
            int termJD = toJulianDay(year, termMonth, termDay);

            if (termJD > todayJD) {
                int diff = termJD - todayJD;
                return "距" + SOLAR_TERMS[n] + " " + diff + "天";
            }
        }

        // 今年所有节气已过，找明年的第一个节气(小寒)
        int nextYear = year + 1;
        int termDay = getSolarTermDay(nextYear, 0);
        int termJD = toJulianDay(nextYear, 1, termDay);
        int diff = termJD - todayJD;
        return "距" + SOLAR_TERMS[0] + " " + diff + "天";
    }
}
