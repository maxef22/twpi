import com.controlj.addon.twpi.Buckets;
import com.controlj.addon.twpi.HourlySeries;
import com.controlj.addon.twpi.Preservation;
import com.controlj.addon.twpi.TwpiCalculator;

/**
 * Проверка формул без WebCTRL: PI в контрольных точках и TWPI на синтетике.
 * Запуск: см. build.ps1 -SelfTest или tools\run-selftest.ps1
 */
public final class SelfTest {

    private static int failures = 0;

    public static void main(String[] args) {
        // Контрольные значения IPI: 20 °C / 45 % RH ≈ 50 лет — точка отсчёта шкалы.
        check("PI(20 / 45)", Preservation.pi(20, 45), 50.0, 1.5);
        check("PI(20 / 50)", Preservation.pi(20, 50), 45.2, 1.0);
        check("PI(15 / 50)", Preservation.pi(15, 50), 84.0, 2.0);
        check("PI(25 / 50)", Preservation.pi(25, 50), 24.4, 1.0);

        // Рост влажности и температуры уменьшает PI.
        if (Preservation.pi(20, 60) >= Preservation.pi(20, 50)) {
            fail("PI должен падать с ростом RH");
        }
        if (Preservation.pi(25, 50) >= Preservation.pi(20, 50)) {
            fail("PI должен падать с ростом температуры");
        }

        // Гармоническое усреднение: 100 часов при 20/45 и 50 часов при 30/70.
        HourlySeries t = new HourlySeries("#t");
        HourlySeries rh = new HourlySeries("#rh");
        long hour = HourlySeries.HOUR_MS;
        double expectedRate = 0.0;
        for (int i = 0; i < 150; i++) {
            double tv = i < 100 ? 20.0 : 30.0;
            double rv = i < 100 ? 45.0 : 70.0;
            t.add(i * hour, tv);
            rh.add(i * hour, rv);
            expectedRate += 1.0 / Preservation.pi(tv, rv);
        }
        TwpiCalculator.Report report = TwpiCalculator.compute(t, rh, 0, 150 * hour);
        check("TWPI (150 ч)", report.twpi, 150 / expectedRate, 0.05);
        if (report.jointHours != 150) {
            fail("jointHours = " + report.jointHours + ", ожидалось 150");
        }
        if (!(report.twpi < Preservation.pi(20, 45)) || !(report.twpi > Preservation.pi(30, 70))) {
            fail("TWPI должен лежать между PI хорошего и плохого периода");
        }

        // Дырка в данных: час без влажности не должен попадать в расчёт.
        HourlySeries t2 = new HourlySeries("#t2");
        HourlySeries rh2 = new HourlySeries("#rh2");
        for (int i = 0; i < 10; i++) {
            t2.add(i * hour, 20.0);
            if (i != 5) {
                rh2.add(i * hour, 50.0);
            }
        }
        TwpiCalculator.Report partial = TwpiCalculator.compute(t2, rh2, 0, 10 * hour);
        if (partial.jointHours != 9) {
            fail("совместных часов " + partial.jointHours + ", ожидалось 9");
        }

        // Среднее внутри часа: два сэмпла по 20 и 24 °C дают 22 °C.
        HourlySeries t3 = new HourlySeries("#t3");
        t3.add(0, 20.0);
        t3.add(30 * 60 * 1000L, 24.0);
        check("среднее за час", t3.valueAt(0), 22.0, 1e-9);

        // Влажность долями (0…1) должна распознаваться и пересчитываться.
        HourlySeries t4 = new HourlySeries("#t4");
        HourlySeries rh4 = new HourlySeries("#rh4");
        for (int i = 0; i < 24; i++) {
            t4.add(i * hour, 20.0);
            rh4.add(i * hour, 0.45);
        }
        TwpiCalculator.Report fractions = TwpiCalculator.compute(t4, rh4, 0, 24 * hour);
        check("TWPI, RH долями", fractions.twpi, Preservation.pi(20, 45), 0.5);
        boolean warned = false;
        for (String note : fractions.notes) {
            if (note.contains("долями")) {
                warned = true;
            }
        }
        if (!warned) {
            fail("нет примечания о пересчёте влажности из долей");
        }

        // Часовой фильтр: ряд прочитан шире периода, считается только окно.
        HourlySeries t5 = new HourlySeries("#t5");
        HourlySeries rh5 = new HourlySeries("#rh5");
        for (int i = 0; i < 200; i++) {
            t5.add(i * hour, 20.0);
            rh5.add(i * hour, 45.0);
        }
        TwpiCalculator.Report window = TwpiCalculator.compute(t5, rh5, 50 * hour, 100 * hour);
        if (window.jointHours != 50) {
            fail("часов в окне " + window.jointHours + ", ожидалось 50");
        }
        if (window.expectedHours != 50) {
            fail("expectedHours " + window.expectedHours + ", ожидалось 50");
        }
        check("TWPI в окне", window.twpi, Preservation.pi(20, 45), 0.5);

        // Шаг диаграммы: неделя — дни, месяц — дни, квартал — недели, год — месяцы.
        long now = System.currentTimeMillis();
        checkPlan("7 дней", now, 7, "day", 7);
        checkPlan("30 дней", now, 30, "day", 30);
        checkPlan("90 дней", now, 90, "week", 13);
        checkPlan("365 дней", now, 365, "month", 12);
        checkPlan("550 дней", now, 550, "month", 18);

        if (failures == 0) {
            System.out.println("OK: все проверки пройдены");
        } else {
            System.out.println("ПРОВАЛЕНО проверок: " + failures);
            System.exit(1);
        }
    }

    private static void checkPlan(String name, long now, int days, String expectedGranularity, int expectedCount) {
        Buckets.Plan plan = Buckets.plan(now, days);
        if (!expectedGranularity.equals(plan.granularity)) {
            fail(name + ": шаг " + plan.granularity + ", ожидался " + expectedGranularity);
            return;
        }
        if (Math.abs(plan.ranges.size() - expectedCount) > 1) {
            fail(name + ": столбиков " + plan.ranges.size() + ", ожидалось ~" + expectedCount);
            return;
        }
        long previousEnd = Long.MIN_VALUE;
        for (long[] range : plan.ranges) {
            if (range[1] <= range[0]) {
                fail(name + ": пустой интервал");
                return;
            }
            if (previousEnd != Long.MIN_VALUE && range[0] < previousEnd) {
                fail(name + ": интервалы не по порядку (старые должны идти первыми)");
                return;
            }
            previousEnd = range[1];
        }
        System.out.printf("  ok  %-18s %-5s столбиков %d%n", name, plan.granularity, plan.ranges.size());
    }

    private static void check(String name, double actual, double expected, double tolerance) {
        if (Double.isNaN(actual) || Math.abs(actual - expected) > tolerance) {
            fail(name + ": получено " + actual + ", ожидалось " + expected + " ± " + tolerance);
        } else {
            System.out.printf("  ok  %-18s %8.2f (ожидалось %.2f)%n", name, actual, expected);
        }
    }

    private static void fail(String message) {
        failures++;
        System.out.println("  FAIL " + message);
    }
}
