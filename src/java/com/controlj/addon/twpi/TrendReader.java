package com.controlj.addon.twpi;

import com.controlj.green.addonsupport.access.Location;
import com.controlj.green.addonsupport.access.NoSuchAspectException;
import com.controlj.green.addonsupport.access.Tree;
import com.controlj.green.addonsupport.access.UnresolvableException;
import com.controlj.green.addonsupport.access.aspect.AnalogTrendSource;
import com.controlj.green.addonsupport.access.aspect.TrendSource;
import com.controlj.green.addonsupport.access.trend.TrendAnalogSample;
import com.controlj.green.addonsupport.access.trend.TrendData;
import com.controlj.green.addonsupport.access.trend.TrendRangeFactory;
import com.controlj.green.addonsupport.access.trend.TrendType;

import java.util.Date;
import java.util.Iterator;

/**
 * Чтение одного тренда WebCTRL и свёртка его в {@link HourlySeries}.
 *
 * <p>Важные правила, без которых результат «врёт»:</p>
 * <ul>
 *   <li>в расчёт идут только сэмплы {@link TrendType#DATA}: метки «связи нет»,
 *       «неизвестное значение», «отказ» — это не измерения;</li>
 *   <li>проверяется тип тренда (аналоговый) и то, что сбор вообще включён;</li>
 *   <li>любая проблема точки превращается в {@link HourlySeries#error}, а не в
 *       исключение — иначе один кривой адрес роняет всю страницу.</li>
 * </ul>
 */
public final class TrendReader {

    private TrendReader() {
    }

    /**
     * Читает тренд по адресу вида {@code #building/floor/room/point}.
     *
     * @param tree       дерево GEO, полученное в {@code SystemAccess}
     * @param address    адрес точки, введённый пользователем
     * @param fromMillis начало периода
     * @param toMillis   конец периода
     * @return ряд; при ошибке — с заполненным {@link HourlySeries#error}
     */
    public static HourlySeries read(Tree tree, String address, long fromMillis, long toMillis) {
        HourlySeries series = new HourlySeries(address);
        try {
            Location location = resolve(tree, address);
            series.displayName = location.getDisplayName();
            series.displayPath = safeLookupId(location);

            if (!location.hasAspect(TrendSource.class)) {
                series.error = "у точки нет тренда: " + address;
                return series;
            }
            TrendSource source = location.getAspect(TrendSource.class);
            if (source == null) {
                series.error = "у точки нет тренда: " + address;
                return series;
            }
            if (source.getType() != TrendSource.Type.Analog) {
                series.error = "тренд не аналоговый (" + source.getType() + "): " + address;
                return series;
            }
            if (!source.isEnabled()) {
                series.error = "сбор тренда отключён: " + address;
                return series;
            }

            TrendData<TrendAnalogSample> data = ((AnalogTrendSource) source)
                    .getTrendData(TrendRangeFactory.byDateRange(new Date(fromMillis), new Date(toMillis)));
            if (data == null) {
                series.error = "WebCTRL не вернул данные тренда: " + address;
                return series;
            }
            try {
                series.units = ((AnalogTrendSource) source).getUnits();
            } catch (Throwable ignored) {
                // getUnits() есть не во всех версиях API — единицы не критичны.
            }
            try {
                // В новых API по умолчанию включён кэш полей; на старых серверах
                // он может быть пуст и давать «нет данных».
                data.useFieldCache(false);
            } catch (Throwable ignored) {
            }

            Stats stats = fill(series, data, fromMillis, toMillis);

            if (series.sampleCount == 0) {
                // Вторая попытка: часы сервера и данные тренда могли разойтись.
                Date latest = latestSample(source);
                if (latest != null) {
                    long end = latest.getTime() + HourlySeries.HOUR_MS;
                    long start = end - Math.max(HourlySeries.HOUR_MS, toMillis - fromMillis);
                    series.clear();
                    TrendData<TrendAnalogSample> retry = ((AnalogTrendSource) source)
                            .getTrendData(TrendRangeFactory.byDateRange(new Date(start), new Date(end)));
                    if (retry != null) {
                        stats = fill(series, retry, start, end);
                    }
                }
            }

            if (series.sampleCount == 0) {
                // Третья попытка: вся доступная история (не больше ~400 суток).
                Date oldest = oldestSample(source);
                Date latest = latestSample(source);
                if (oldest != null && latest != null && latest.after(oldest)) {
                    long end = latest.getTime() + HourlySeries.HOUR_MS;
                    long start = Math.max(oldest.getTime(), end - 400L * 24L * HourlySeries.HOUR_MS);
                    series.clear();
                    TrendData<TrendAnalogSample> everything = ((AnalogTrendSource) source)
                            .getTrendData(TrendRangeFactory.byDateRange(new Date(start), new Date(end)));
                    if (everything != null) {
                        stats = fill(series, everything, start, end);
                        if (series.sampleCount > 0) {
                            series.note = "Данные взяты за фактический период тренда: "
                                    + HourlySeries.formatTime(start) + " — " + HourlySeries.formatTime(end)
                                    + " (запрошенный период пуст).";
                        }
                    }
                }
            }

            if (series.sampleCount == 0) {
                series.error = "в тренде нет данных за выбранный период: " + address + ". "
                        + diagnostics(source) + ". Отбор: " + stats.describe();
            }
        } catch (UnresolvableException e) {
            series.error = "точка не найдена в дереве: " + address;
        } catch (NoSuchAspectException e) {
            series.error = "у точки нет тренда: " + address;
        } catch (Throwable e) {
            series.error = "не удалось прочитать тренд " + address + ": " + describe(e);
        }
        return series;
    }

    /**
     * Постоянная ссылка точки. В API 6.5 метод может отсутствовать — тогда
     * показываем хотя бы адрес, который ввёл пользователь.
     */
    private static String safeLookupId(Location location) {
        try {
            return location.getPersistentLookupString(true);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Счётчики отбора: по ним видно, на каком шаге потерялись данные. */
    private static final class Stats {
        int total;
        int data;
        int inRange;
                int badValue;
        long minTime = Long.MAX_VALUE;
        long maxTime = Long.MIN_VALUE;

        void note(long time) {
            if (time < minTime) {
                minTime = time;
            }
            if (time > maxTime) {
                maxTime = time;
            }
        }

        String describe() {
            return "всего=" + total + ", DATA=" + data + ", в диапазоне=" + inRange
                    + ", без значения=" + badValue
                    + ", время " + time(minTime) + " — " + time(maxTime);
        }

        private static String time(long value) {
            if (value == Long.MAX_VALUE || value == Long.MIN_VALUE) {
                return "нет";
            }
            return HourlySeries.formatTime(value);
        }
    }

    /** Перекладывает сэмплы тренда в часовой ряд. */
    private static Stats fill(HourlySeries series, TrendData<TrendAnalogSample> data,
                              long fromMillis, long toMillis) throws Exception {
        Stats stats = new Stats();
        Iterator<TrendAnalogSample> samples = data.getSamples();
        while (samples.hasNext()) {
            TrendAnalogSample sample = samples.next();
            if (sample == null) {
                continue;
            }
            stats.total++;
            if (sample.getType() != TrendType.DATA) {
                continue;
            }
            stats.data++;
            long time = sampleTime(sample);
            stats.note(time);
            if (time < fromMillis || time >= toMillis) {
                continue;
            }
            stats.inRange++;
            double value;
            try {
                value = sample.doubleValue();
            } catch (RuntimeException e) {
                stats.badValue++;
                continue;
            }
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                stats.badValue++;
                continue;
            }
            series.add(time, value);
        }
        return stats;
    }

    /**
     * Метка времени сэмпла. В старых сборках {@code getTimeInMillis()} может
     * вернуть не миллисекунды — тогда берём {@link TrendAnalogSample#getTime()}.
     */
    private static long sampleTime(TrendAnalogSample sample) {
        long time = Long.MIN_VALUE;
        try {
            time = sample.getTimeInMillis();
        } catch (Throwable ignored) {
        }
        if (time < 1_000_000_000_000L || time > 4_000_000_000_000L) {
            try {
                Date date = sample.getTime();
                if (date != null) {
                    time = date.getTime();
                }
            } catch (Throwable ignored) {
            }
        }
        return time;
    }

    private static Date latestSample(TrendSource source) {
        try {
            return source.getLatestSampleTimeInDatabase();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Date oldestSample(TrendSource source) {
        try {
            return source.getOldestSampleTimeInDatabase();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Что вообще есть в тренде — чтобы «нет данных» не было тупиком. */
    private static String diagnostics(TrendSource source) {
        StringBuilder text = new StringBuilder("источник: ");
        try {
            text.append("включён=").append(source.isEnabled());
        } catch (Throwable ignored) {
        }
        try {
            text.append(", историк=").append(source.isHistorianEnabled());
        } catch (Throwable ignored) {
        }
        try {
            text.append(", шаг=").append(source.isInterval() ? source.getSampleInterval() + " мс" : "COV");
        } catch (Throwable ignored) {
        }
        try {
            text.append(", сэмплов=").append(source.getNumberOfSamplesInDatabase());
        } catch (Throwable ignored) {
        }
        try {
            text.append(", данные за ").append(source.getOldestSampleTimeInDatabase())
                    .append(" — ").append(source.getLatestSampleTimeInDatabase());
        } catch (Throwable ignored) {
        }
        return text.toString();
    }

    /**
     * Разрешает адрес точки. Понимает три формы:
     * <ul>
     *   <li>{@code #Site/Building/Floor/Point} — путь по <b>reference name</b>
     *       (это же значение WebCTRL показывает в колонке Lookup ID и в
     *       {@code Location.getPersistentLookupString});</li>
     *   <li>{@code ABSPATH:1:#Site/...} — та же постоянная ссылка целиком,
     *       её удобно копировать из отчётов и логов;</li>
     *   <li>путь по отображаемым именам ({@code Здание/1 этаж/101/Температура}) —
     *       на случай, когда адрес скопирован из дерева или из интерфейса, где
     *       видно Display Name.</li>
     * </ul>
     */
    private static Location resolve(Tree tree, String address) throws UnresolvableException {
        String value = address == null ? "" : address.trim();
        if (value.isEmpty()) {
            throw new UnresolvableException("пустой адрес тренда");
        }
        try {
            return tree.resolve(value);
        } catch (UnresolvableException first) {
            int marker = value.indexOf(":#");
            if (marker > 0 && value.regionMatches(true, 0, "ABSPATH", 0, 7)) {
                return tree.resolve(value.substring(marker + 1));
            }
            Location byName = resolveByNames(tree, value);
            if (byName != null) {
                return byName;
            }
            throw first;
        }
    }

    /**
     * Ищет точку, сравнивая каждый сегмент пути с reference name и display name
     * детей (без учёта регистра). Нужен только как запасной вариант, когда
     * пользователь вставил путь с отображаемыми именами.
     */
    private static Location resolveByNames(Tree tree, String path) {
        String value = path.trim();
        while (value.startsWith("#")) {
            value = value.substring(1).trim();
        }
        Location current = tree.getRoot();
        if (value.isEmpty()) {
            return current;
        }
        for (String rawSegment : value.split("/")) {
            String segment = rawSegment.trim();
            if (segment.isEmpty()) {
                continue;
            }
            Location match = null;
            for (Location child : current.getChildren()) {
                if (matchesName(child, segment)) {
                    match = child;
                    break;
                }
            }
            if (match == null) {
                return null;
            }
            current = match;
        }
        return current;
    }

    private static boolean matchesName(Location location, String segment) {
        String needle = stripHash(segment);
        return needle.equalsIgnoreCase(stripHash(nullSafe(location.getReferenceName())))
                || needle.equalsIgnoreCase(stripHash(nullSafe(location.getDisplayName())));
    }

    /** В части систем reference-имена сами начинаются с '#': сравниваем без него. */
    private static String stripHash(String value) {
        String text = value == null ? "" : value.trim();
        while (text.startsWith("#")) {
            text = text.substring(1).trim();
        }
        return text;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /** Короткое описание исключения: у многих из них {@code getMessage()} пустой. */
    public static String describe(Throwable e) {
        if (e == null) {
            return "неизвестная ошибка";
        }
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = e.getClass().getSimpleName();
        }
        Throwable cause = e.getCause();
        if (cause != null && cause != e) {
            String causeMessage = cause.getMessage();
            message += " (" + (causeMessage == null || causeMessage.trim().isEmpty()
                    ? cause.getClass().getSimpleName()
                    : causeMessage + ")");
        }
        return message;
    }
}
