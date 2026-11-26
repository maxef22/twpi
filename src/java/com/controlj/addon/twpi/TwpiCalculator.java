package com.controlj.addon.twpi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Расчёт TWPI по паре рядов или по группе датчиков.
 *
 * <p>TWPI — это такое постоянное значение PI, которое за тот же период дало бы
 * тот же ущерб, что и фактически менявшиеся условия. Ущерб накапливается
 * со скоростью 1/PI, поэтому усреднять надо скорости, а не сами PI:</p>
 *
 * <pre>
 * TWPI = N / Σ (1 / PI_i)
 * </pre>
 *
 * <p>Если в помещении несколько датчиков, на каждом часе T и RH усредняются
 * по тем датчикам, у которых в этот час есть данные, и PI считается один раз
 * на час. Так помещение с четырьмя точками не «перевешивает» помещение с одной.</p>
 */
public final class TwpiCalculator {

    private TwpiCalculator() {
    }

    /** Результат расчёта: сам TWPI и всё, что нужно показать рядом с ним. */
    public static final class Report {

        /** Итоговый TWPI, лет. {@code NaN}, если считать не из чего. */
        public double twpi = Double.NaN;

        /** PI по последнему общему часу — «мгновенное» значение, лет. */
        public double piLast = Double.NaN;
        public double lastTemperature;
        public double lastHumidity;

        /** Часы, по которым реально считали (есть и T, и RH). */
        public int jointHours;
        /** Сколько часов должно было быть в запрошенном периоде. */
        public int expectedHours;
        /** Часы, выброшенные как неправдоподобные (RH = 0, T = −999 и т. п.). */
        public int rejectedHours;
        /** Часы, где есть только температура или только влажность. */
        public int orphanHours;

        public int temperatureSamples;
        public int humiditySamples;
        public int temperatureHours;
        public int humidityHours;

        public double temperatureAverage;
        public double humidityAverage;
        public double temperatureMin;
        public double temperatureMax;
        public double humidityMin;
        public double humidityMax;

        public long firstHourMillis;
        public long lastHourMillis;

        /** Пояснения для пользователя: чего не хватает, что выброшено. */
        public final List<String> notes = new ArrayList<String>();

        public boolean hasResult() {
            return jointHours > 0 && !Double.isNaN(twpi);
        }

        /** Доля покрытия периода общими часами, 0…1. */
        public double coverage() {
            return expectedHours <= 0 ? 0.0 : Math.min(1.0, jointHours / (double) expectedHours);
        }
    }

    /** Расчёт по одной паре рядов. */
    public static Report compute(HourlySeries temperature, HourlySeries humidity,
                                 long fromMillis, long toMillis) {
        List<HourlySeries> temperatures = new ArrayList<HourlySeries>();
        temperatures.add(temperature);
        List<HourlySeries> humidities = new ArrayList<HourlySeries>();
        humidities.add(humidity);
        return computeMulti(temperatures, humidities, fromMillis, toMillis);
    }

    /**
     * Расчёт по группе датчиков: T и RH усредняются по датчикам внутри часа.
     * Пустые списки или списки с ошибками чтения просто не дают часов.
     */
    public static Report computeMulti(List<HourlySeries> temperatures, List<HourlySeries> humidities,
                                      long fromMillis, long toMillis) {
        Report report = new Report();
        report.expectedHours = (int) Math.max(0, (toMillis - fromMillis) / HourlySeries.HOUR_MS);

        Map<Long, double[]> temperatureAcc = merge(temperatures);
        Map<Long, double[]> humidityAcc = merge(humidities);

        for (HourlySeries series : temperatures) {
            report.temperatureSamples += series.sampleCount;
            report.temperatureHours += series.buckets.size();
        }
        for (HourlySeries series : humidities) {
            report.humiditySamples += series.sampleCount;
            report.humidityHours += series.buckets.size();
        }

        double rateSum = 0.0;
        double tSum = 0.0;
        double rhSum = 0.0;
        double tMin = Double.POSITIVE_INFINITY;
        double tMax = Double.NEGATIVE_INFINITY;
        double rhMin = Double.POSITIVE_INFINITY;
        double rhMax = Double.NEGATIVE_INFINITY;
        long firstHour = Long.MAX_VALUE;
        long lastHour = Long.MIN_VALUE;
        double lastT = Double.NaN;
        double lastRh = Double.NaN;

        // Некоторые точки отдают влажность долями (0…1), а не процентами.
        // В помещении 1 % RH не бывает, поэтому такой ряд однозначно пересчитываем.
        double rhScale = 1.0;
        double rawMax = Double.NEGATIVE_INFINITY;
        for (double[] bucket : humidityAcc.values()) {
            if (bucket[1] > 0) {
                rawMax = Math.max(rawMax, bucket[0] / bucket[1]);
            }
        }
        if (rawMax > 0.0 && rawMax <= 1.5) {
            rhScale = 100.0;
            report.notes.add("Влажность задана долями (0…1) — пересчитана в проценты.");
        }

        for (Map.Entry<Long, double[]> entry : temperatureAcc.entrySet()) {
            long hour = entry.getKey();
            // Ряды могут быть прочитаны шире периода (например, на год для месячной
            // истории) — считаем только часы внутри запрошенного окна.
            long hourMillis = hour * HourlySeries.HOUR_MS;
            if (hourMillis < fromMillis || hourMillis >= toMillis) {
                continue;
            }
            double[] humidityBucket = humidityAcc.get(hour);
            if (humidityBucket == null) {
                report.orphanHours++;
                continue;
            }
            double t = entry.getValue()[0] / entry.getValue()[1];
            double rh = humidityBucket[0] / humidityBucket[1] * rhScale;
            if (!Preservation.isPlausible(t, rh)) {
                report.rejectedHours++;
                continue;
            }
            double pi = Preservation.pi(t, rh);
            if (Double.isNaN(pi) || Double.isInfinite(pi) || pi <= 0.0) {
                report.rejectedHours++;
                continue;
            }
            rateSum += 1.0 / pi;
            tSum += t;
            rhSum += rh;
            tMin = Math.min(tMin, t);
            tMax = Math.max(tMax, t);
            rhMin = Math.min(rhMin, rh);
            rhMax = Math.max(rhMax, rh);
            firstHour = Math.min(firstHour, hour);
            if (hour >= lastHour) {
                lastHour = hour;
                lastT = t;
                lastRh = rh;
            }
            report.jointHours++;
        }

        if (report.jointHours > 0) {
            report.twpi = report.jointHours / rateSum;
            report.temperatureAverage = tSum / report.jointHours;
            report.humidityAverage = rhSum / report.jointHours;
            report.temperatureMin = tMin;
            report.temperatureMax = tMax;
            report.humidityMin = rhMin;
            report.humidityMax = rhMax;
            report.lastTemperature = lastT;
            report.lastHumidity = lastRh;
            report.piLast = Double.isNaN(lastT) || Double.isNaN(lastRh)
                    ? Double.NaN
                    : Preservation.pi(lastT, lastRh);
            report.firstHourMillis = firstHour * HourlySeries.HOUR_MS;
            report.lastHourMillis = lastHour * HourlySeries.HOUR_MS;

            if (report.rejectedHours > 0) {
                report.notes.add("Часов с неправдоподобными значениями пропущено: " + report.rejectedHours
                        + " (RH вне 0…100 % или T вне −60…90 °C).");
            }
            if (report.coverage() < 0.9) {
                report.notes.add("Данные покрывают лишь " + Math.round(report.coverage() * 100)
                        + " % периода — результат может быть смещён.");
            }
        } else {
            report.notes.add("Нет ни одного часа, где есть и температура, и влажность одновременно.");
        }
        return report;
    }

    /** Складывает часовые корзины нескольких рядов: час → {сумма, количество}. */
    private static Map<Long, double[]> merge(List<HourlySeries> series) {
        Map<Long, double[]> merged = new HashMap<Long, double[]>();
        for (HourlySeries one : series) {
            if (one == null) {
                continue;
            }
            for (Map.Entry<Long, double[]> entry : one.buckets.entrySet()) {
                double[] accumulator = merged.get(entry.getKey());
                if (accumulator == null) {
                    accumulator = new double[]{0.0, 0.0};
                    merged.put(entry.getKey(), accumulator);
                }
                accumulator[0] += entry.getValue()[0];
                accumulator[1] += entry.getValue()[1];
            }
        }
        return merged;
    }
}
