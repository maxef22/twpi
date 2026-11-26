package com.controlj.addon.twpi;

/**
 * Формула Preservation Index (PI) Института сохранности изображений (IPI, RIT).
 *
 * <p>PI — ожидаемое время (в годах) до заданной степени химического разрушения
 * органического материала при постоянных температуре и относительной влажности.
 * Модель выведена на гидролизе триацетата целлюлозы и используется как общая мера
 * агрессивности среды:</p>
 *
 * <pre>
 * PI = exp((95220 - 134.9 * RH) / (8.314 * T) + 0.0284 * RH - 28.023) / 365
 * </pre>
 *
 * <p>T — температура в кельвинах, RH — относительная влажность в процентах,
 * результат — годы. Точка отсчёта IPI: 20 °C / 45 % RH ≈ 50 лет.</p>
 */
public final class Preservation {

    /** Газовая постоянная, Дж/(моль·К). */
    private static final double R = 8.314;

    /** Эмпирическая «энергия активации» подгонки IPI, Дж/моль. */
    private static final double E = 95220.0;

    /** Коэффициент влияния влажности на «энергию активации». */
    private static final double RH_SLOPE = 134.9;

    private Preservation() {
    }

    /**
     * Preservation Index для постоянных условий.
     *
     * @param temperatureC температура, °C
     * @param relativeHumidity относительная влажность, %
     * @return ожидаемое время до разрушения, лет
     */
    public static double pi(double temperatureC, double relativeHumidity) {
        double kelvin = temperatureC + 273.15;
        double exponent = (E - RH_SLOPE * relativeHumidity) / (R * kelvin)
                + 0.0284 * relativeHumidity
                - 28.023;
        return Math.exp(exponent) / 365.0;
    }

    /**
     * Годится ли пара значений для расчёта. Формула — эмпирическая подгонка,
     * за пределами разумного диапазона она даёт бессмысленные числа, поэтому
     * такие часы лучше выбросить и честно показать в отчёте.
     */
    public static boolean isPlausible(double temperatureC, double relativeHumidity) {
        return !Double.isNaN(temperatureC) && !Double.isNaN(relativeHumidity)
                && temperatureC >= -60.0 && temperatureC <= 90.0
                && relativeHumidity > 0.0 && relativeHumidity <= 100.0;
    }
}
