package com.controlj.addon.twpi;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Разбивка периода на интервалы для диаграммы.
 *
 * <p>Шаг подбирается под длину периода: до 31 дня — по дням, до 120 дней —
 * по неделям, дальше — по календарным месяцам. Так на карточке помещения
 * всегда 7–13 столбиков, а не 365.</p>
 */
public final class Buckets {

    public static final long DAY_MS = 24L * 3600L * 1000L;

    /** Интервалы диаграммы и её шаг: {@code day}, {@code week} или {@code month}. */
    public static final class Plan {
        public final String granularity;
        public final List<long[]> ranges;

        Plan(String granularity, List<long[]> ranges) {
            this.granularity = granularity;
            this.ranges = ranges;
        }
    }

    private Buckets() {
    }

    public static Plan plan(long toMillis, int days) {
        if (days <= 31) {
            return new Plan("day", dayRanges(toMillis, days));
        }
        if (days <= 120) {
            return new Plan("week", weekRanges(toMillis, days));
        }
        int months = Math.min(12, Math.max(1, Math.round(days / 30f)));
        return new Plan("month", monthRanges(toMillis, months));
    }

    static long startOfDay(long millis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    /** По дню на столбик; последний день обрезан текущим моментом. */
    static List<long[]> dayRanges(long toMillis, int days) {
        List<long[]> ranges = new ArrayList<long[]>();
        long windowStart = toMillis - days * DAY_MS;
        long todayStart = startOfDay(toMillis);
        for (int i = days - 1; i >= 0; i--) {
            long start = todayStart - i * DAY_MS;
            long end = Math.min(start + DAY_MS, toMillis);
            if (end <= start) {
                continue;
            }
            ranges.add(new long[]{Math.max(start, windowStart), end});
        }
        return ranges;
    }

    /** По неделе на столбик, отсчёт назад от конца сегодняшнего дня. */
    static List<long[]> weekRanges(long toMillis, int days) {
        List<long[]> ranges = new ArrayList<long[]>();
        long windowStart = toMillis - days * DAY_MS;
        long todayEnd = Math.min(startOfDay(toMillis) + DAY_MS, toMillis);
        int count = Math.max(1, (int) Math.ceil(days / 7.0));
        for (int i = count - 1; i >= 0; i--) {
            long end = todayEnd - (long) (count - 1 - i) * 7L * DAY_MS;
            long start = Math.max(end - 7L * DAY_MS, windowStart);
            if (end > start) {
                ranges.add(new long[]{start, end});
            }
        }
        return ranges;
    }

    /** По календарному месяцу на столбик. */
    static List<long[]> monthRanges(long nowMillis, int count) {
        List<long[]> ranges = new ArrayList<long[]>();
        Calendar start = Calendar.getInstance();
        start.setTimeInMillis(nowMillis);
        start.set(Calendar.DAY_OF_MONTH, 1);
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);

        Calendar end = (Calendar) start.clone();
        end.add(Calendar.MONTH, 1);
        ranges.add(0, new long[]{start.getTimeInMillis(), Math.min(end.getTimeInMillis(), nowMillis)});

        for (int i = 1; i < count; i++) {
            end = (Calendar) start.clone();
            start = (Calendar) start.clone();
            start.add(Calendar.MONTH, -1);
            ranges.add(0, new long[]{start.getTimeInMillis(), end.getTimeInMillis()});
        }
        return ranges;
    }
}
