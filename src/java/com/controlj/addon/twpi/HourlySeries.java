package com.controlj.addon.twpi;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ряд значений, свёрнутый в часовые интервалы.
 *
 * <p>Тренды WebCTRL могут писаться с разным шагом (COV, 5/15/60 минут), а у пары
 * «температура + влажность» моменты измерений почти никогда не совпадают.
 * Поэтому оба ряда приводятся к одному шагу — часу: все сэмплы внутри часа
 * усредняются, а часы, где есть обе величины, идут в расчёт TWPI. Один час —
 * один интервал, вес у всех одинаковый.</p>
 */
public final class HourlySeries {

    public static final long HOUR_MS = 3600_000L;

    /** Адрес, который ввёл пользователь. */
    public final String address;

    /** Час (номер часа от эпохи) → {сумма значений, количество сэмплов}. */
    public final TreeMap<Long, double[]> buckets = new TreeMap<Long, double[]>();

    /** Человеческое имя точки и путь — для отчёта. */
    public String displayName;
    public String displayPath;
    public String units;

    /** Сколько сырых сэмплов прочитано (только TrendType.DATA). */
    public int sampleCount;

    /** Если точка не прочиталась — здесь причина, buckets пустой. */
    public String error;

    /** Пояснение к тому, что данные взяты не за запрошенный период. */
    public String note;

    public HourlySeries(String address) {
        this.address = address;
    }

    public boolean isOk() {
        return error == null;
    }

    /** Сбрасывает накопленные данные (нужно для повторного чтения). */
    public void clear() {
        buckets.clear();
        sampleCount = 0;
        error = null;
    }

    /** Добавляет измерение; момент времени — миллисекунды эпохи. */
    public void add(long timeMillis, double value) {
        long hour = Math.floorDiv(timeMillis, HOUR_MS);
        double[] accumulator = buckets.get(hour);
        if (accumulator == null) {
            accumulator = new double[]{0.0, 0.0};
            buckets.put(hour, accumulator);
        }
        accumulator[0] += value;
        accumulator[1] += 1.0;
        sampleCount++;
    }

    /** Среднее значение за час или {@code NaN}, если часа нет. */
    public double valueAt(long hour) {
        double[] accumulator = buckets.get(hour);
        return accumulator == null ? Double.NaN : accumulator[0] / accumulator[1];
    }

    public static String formatTime(long millis) {
        return new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date(millis));
    }

    /** Часы, в которых есть данные, по возрастанию. */
    public Iterable<Map.Entry<Long, double[]>> entries() {
        return buckets.entrySet();
    }
}
