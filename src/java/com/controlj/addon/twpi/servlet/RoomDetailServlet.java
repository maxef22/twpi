package com.controlj.addon.twpi.servlet;

import com.controlj.addon.twpi.Buckets;
import com.controlj.addon.twpi.HourlySeries;
import com.controlj.addon.twpi.RoomsStore;
import com.controlj.addon.twpi.SeriesCache;
import com.controlj.addon.twpi.TrendReader;
import com.controlj.addon.twpi.TwpiCalculator;
import com.controlj.green.addonsupport.InvalidConnectionRequestException;
import com.controlj.green.addonsupport.access.DirectAccess;
import com.controlj.green.addonsupport.access.ReadAction;
import com.controlj.green.addonsupport.access.SystemAccess;
import com.controlj.green.addonsupport.access.SystemConnection;
import com.controlj.green.addonsupport.access.SystemTree;
import com.controlj.green.addonsupport.access.Tree;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Подробности по одному помещению: текущий период + диаграмма по интервалам.
 *
 * <pre>
 * GET /twpi/api/room?i=0&days=30
 * </pre>
 *
 * <p>Шаг диаграммы подбирается под период: до 31 дня — по дням, до 120 дней —
 * по неделям, дальше — по календарным месяцам. Тренды читаются один раз на
 * самый широкий интервал (через кэш), а интервалы считаются уже в памяти.</p>
 */
public class RoomDetailServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final long DAY_MS = 24L * 3600L * 1000L;
    private static final int MAX_DAYS = 366;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        int index = parseInt(request.getParameter("i"), -1);
        int days = clamp(parseInt(request.getParameter("days"), 30), 1, MAX_DAYS);

        RoomsStore store = new RoomsStore(getServletContext());
        JSONObject config = store.load();
        JSONArray rooms = config.optJSONArray("rooms");
        if (index < 0 || rooms == null || index >= rooms.length()) {
            error(response, HttpServletResponse.SC_BAD_REQUEST, "Помещение не найдено — обновите список в настройках.");
            return;
        }
        JSONObject room = rooms.optJSONObject(index);
        if (room == null) {
            error(response, HttpServletResponse.SC_BAD_REQUEST, "Пустая запись помещения.");
            return;
        }

        final List<SensorInput> sensors = new ArrayList<SensorInput>();
        JSONArray sensorsJson = room.optJSONArray("sensors");
        if (sensorsJson != null) {
            for (int s = 0; s < sensorsJson.length(); s++) {
                JSONObject sensor = sensorsJson.optJSONObject(s);
                if (sensor == null) {
                    continue;
                }
                sensors.add(new SensorInput(sensor.optString("name", "Датчик " + (s + 1)),
                        sensor.optString("t", "").trim(),
                        sensor.optString("rh", "").trim()));
            }
        }
        if (sensors.isEmpty()) {
            error(response, HttpServletResponse.SC_BAD_REQUEST, "В помещении нет датчиков.");
            return;
        }

        final long toMillis = System.currentTimeMillis();
        final long periodFrom = toMillis - days * DAY_MS;
        final Buckets.Plan plan = Buckets.plan(toMillis, days);

        try {
            SystemConnection connection = DirectAccess.getDirectAccess().getUserSystemConnection(request);
            connection.runReadAction(new ReadAction() {
                @Override
                public void execute(SystemAccess access) throws Exception {
                    Tree geographic = access.getTree(SystemTree.Geographic);
                    for (SensorInput sensor : sensors) {
                        if (!sensor.tAddress.isEmpty()) {
                            sensor.temperature = SeriesCache.get().get(geographic, sensor.tAddress);
                        }
                        if (!sensor.rhAddress.isEmpty()) {
                            sensor.humidity = SeriesCache.get().get(geographic, sensor.rhAddress);
                        }
                    }
                }
            });
        } catch (InvalidConnectionRequestException e) {
            error(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "Нет сессии оператора WebCTRL — откройте аддон из интерфейса WebCTRL.");
            return;
        } catch (Throwable e) {
            log("Не удалось прочитать тренды помещения", e);
            error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Ошибка чтения трендов: " + TrendReader.describe(e));
            return;
        }

        List<HourlySeries> temperatures = new ArrayList<HourlySeries>();
        List<HourlySeries> humidities = new ArrayList<HourlySeries>();
        for (SensorInput sensor : sensors) {
            if (sensor.temperature != null && sensor.temperature.isOk()) {
                temperatures.add(sensor.temperature);
            }
            if (sensor.humidity != null && sensor.humidity.isOk()) {
                humidities.add(sensor.humidity);
            }
        }

        JSONObject body = new JSONObject();
        body.put("ok", true);
        body.put("name", room.optString("name", "Помещение " + (index + 1)));
        body.put("index", index);
        body.put("days", days);
        body.put("from", periodFrom);
        body.put("to", toMillis);
        body.put("granularity", plan.granularity);

        long cacheAge = 0L;
        for (SensorInput sensor : sensors) {
            cacheAge = Math.max(cacheAge, SeriesCache.get().ageSeconds(sensor.tAddress));
            cacheAge = Math.max(cacheAge, SeriesCache.get().ageSeconds(sensor.rhAddress));
        }
        body.put("cacheAgeSec", cacheAge < 0 ? 0 : cacheAge);

        JSONArray sensorsOut = new JSONArray();
        for (SensorInput sensor : sensors) {
            sensorsOut.put(sensor.toJson());
        }
        body.put("sensors", sensorsOut);

        if (temperatures.isEmpty() || humidities.isEmpty()) {
            body.put("error", "нет данных: " + (temperatures.isEmpty() ? "температура" : "влажность"));
            write(response, HttpServletResponse.SC_OK, body);
            return;
        }

        TwpiCalculator.Report report = TwpiCalculator.computeMulti(temperatures, humidities,
                periodFrom, toMillis);
        if (report.hasResult()) {
            body.put("twpi", round(report.twpi, 1));
            body.put("piLast", Double.isNaN(report.piLast) ? null : round(report.piLast, 1));
            body.put("hours", report.jointHours);
            body.put("expectedHours", report.expectedHours);
            body.put("coverage", round(report.coverage(), 3));
            body.put("t", round(report.temperatureAverage, 1));
            body.put("rh", round(report.humidityAverage, 1));
            body.put("tMin", round(report.temperatureMin, 1));
            body.put("tMax", round(report.temperatureMax, 1));
            body.put("rhMin", round(report.humidityMin, 1));
            body.put("rhMax", round(report.humidityMax, 1));
            body.put("lastT", round(report.lastTemperature, 1));
            body.put("lastRh", round(report.lastHumidity, 1));
        } else {
            body.put("error", "нет общих часов с температурой и влажностью");
        }
        JSONArray notes = new JSONArray();
        for (String note : report.notes) {
            notes.put(note);
        }
        body.put("notes", notes);

        JSONArray bucketsOut = new JSONArray();
        for (long[] range : plan.ranges) {
            TwpiCalculator.Report bucketReport = TwpiCalculator.computeMulti(temperatures, humidities,
                    range[0], range[1]);
            JSONObject item = new JSONObject();
            item.put("from", range[0]);
            item.put("to", range[1]);
            if (bucketReport.hasResult()) {
                item.put("twpi", round(bucketReport.twpi, 1));
                item.put("hours", bucketReport.jointHours);
                item.put("expectedHours", bucketReport.expectedHours);
                item.put("coverage", round(bucketReport.coverage(), 3));
                item.put("t", round(bucketReport.temperatureAverage, 1));
                item.put("rh", round(bucketReport.humidityAverage, 1));
            }
            bucketsOut.put(item);
        }
        body.put("buckets", bucketsOut);

        write(response, HttpServletResponse.SC_OK, body);
    }

    private static final class SensorInput {
        final String name;
        final String tAddress;
        final String rhAddress;
        HourlySeries temperature;
        HourlySeries humidity;

        SensorInput(String name, String tAddress, String rhAddress) {
            this.name = name;
            this.tAddress = tAddress;
            this.rhAddress = rhAddress;
        }

        JSONObject toJson() {
            JSONObject json = new JSONObject();
            json.put("name", name);
            json.put("t", tAddress);
            json.put("rh", rhAddress);
            if (temperature != null) {
                json.put("tSamples", temperature.sampleCount);
                if (!temperature.isOk()) {
                    json.put("tError", temperature.error);
                }
            }
            if (humidity != null) {
                json.put("rhSamples", humidity.sampleCount);
                if (!humidity.isOk()) {
                    json.put("rhError", humidity.error);
                }
            }
            return json;
        }
    }

    private static double round(double value, int digits) {
        double factor = Math.pow(10, digits);
        return Math.round(value * factor) / factor;
    }

    private static void error(HttpServletResponse response, int status, String message) throws IOException {
        JSONObject body = new JSONObject();
        body.put("ok", false);
        body.put("error", message);
        write(response, status, body);
    }

    private static void write(HttpServletResponse response, int status, JSONObject body) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json; charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        PrintWriter writer = response.getWriter();
        writer.write(body.toString());
        writer.flush();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
