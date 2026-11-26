package com.controlj.addon.twpi;

import com.controlj.green.addonsupport.access.Tree;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кэш часовых рядов, прочитанных из трендов WebCTRL.
 *
 * <p>Дорогое место — не арифметика, а чтение: API 6.5 отдаёт на каждый запрос
 * весь архив тренда (~100 тыс. сэмплов), и список помещений из 13 комнат
 * превращается в десятки таких чтений. Поэтому ряд читается один раз на
 * {@link #LOOKBACK_DAYS} суток и переиспользуется всеми страницами — списком,
 * карточкой помещения и месячной историей. Период и месяцы считаются уже
 * в памяти: {@link TwpiCalculator} отбрасывает часы вне окна.</p>
 *
 * <p>Ключ — адрес тренда. Запись живёт {@link #TTL_MS}; ошибки чтения не
 * кэшируются, чтобы исправленный адрес подхватился сразу.</p>
 */
public final class SeriesCache {

    /** Сколько держать прочитанный ряд. */
    private static final long TTL_MS = 10L * 60L * 1000L;

    /** Глубина чтения: покрывает и период (до года), и 12 месяцев истории. */
    private static final int LOOKBACK_DAYS = 600;

    /** Верхняя граница числа адресов в кэше — защита от разрастания памяти. */
    private static final int MAX_ENTRIES = 48;

    private static final SeriesCache INSTANCE = new SeriesCache();

    public static SeriesCache get() {
        return INSTANCE;
    }

    private static final class Entry {
        final HourlySeries series;
        final long loadedAt;

        Entry(HourlySeries series, long loadedAt) {
            this.series = series;
            this.loadedAt = loadedAt;
        }
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<String, Entry>();

    private SeriesCache() {
    }

    /**
     * Ряд по адресу: из кэша, если он свежий, иначе чтение из WebCTRL.
     * Вызывать внутри {@code runReadAction}.
     */
    public HourlySeries get(Tree tree, String address) {
        long now = System.currentTimeMillis();
        Entry cached = entries.get(address);
        if (cached != null && now - cached.loadedAt < TTL_MS) {
            return cached.series;
        }

        long from = now - LOOKBACK_DAYS * 24L * 3600L * 1000L;
        HourlySeries series = TrendReader.read(tree, address, from, now);
        if (series.isOk()) {
            if (entries.size() >= MAX_ENTRIES) {
                evict(now);
            }
            entries.put(address, new Entry(series, now));
        }
        return series;
    }

    /** Возраст данных по адресу в секундах или -1, если в кэше нет. */
    public long ageSeconds(String address) {
        Entry cached = entries.get(address);
        if (cached == null) {
            return -1L;
        }
        return (System.currentTimeMillis() - cached.loadedAt) / 1000L;
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    private void evict(long now) {
        for (Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
             iterator.hasNext(); ) {
            if (now - iterator.next().getValue().loadedAt >= TTL_MS) {
                iterator.remove();
            }
        }
        if (entries.size() >= MAX_ENTRIES) {
            // Совсем редкий случай: все записи свежие, но их слишком много.
            entries.clear();
        }
    }
}
