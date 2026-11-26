package com.controlj.addon.twpi.servlet;

import com.controlj.addon.twpi.HourlySeries;
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

/**
 * Расчёт TWPI по паре трендов.
 *
 * <pre>
 * GET /twpi/api/calc?t=&lt;адрес тренда температуры&gt;&amp;rh=&lt;адрес тренда влажности&gt;&amp;days=30
 * </pre>
 *
 * <p>Оба тренда читаются в одном {@code runReadAction}. Ответ — JSON; ошибка
 * конкретной точки возвращается текстом, а не стектрейсом.</p>
 */
public class CalcServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final long DAY_MS = 24L * 3600L * 1000L;
    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_DAYS = 366;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");

        final String temperatureAddress = trim(request.getParameter("t"));
        final String humidityAddress = trim(request.getParameter("rh"));
        final int days = clamp(parseInt(request.getParameter("days"), DEFAULT_DAYS), 1, MAX_DAYS);

        if (temperatureAddress.isEmpty() || humidityAddress.isEmpty()) {
            error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Нужны оба адреса: тренд температуры (t) и тренд влажности (rh).");
            return;
        }

        final long toMillis = System.currentTimeMillis();
        final long fromMillis = toMillis - days * DAY_MS;

        final HourlySeries[] series = new HourlySeries[2];
        try {
            SystemConnection connection = DirectAccess.getDirectAccess().getUserSystemConnection(request);
            connection.runReadAction(new ReadAction() {
                @Override
                public void execute(SystemAccess access) throws Exception {
                    Tree geographic = access.getTree(SystemTree.Geographic);
                    series[0] = SeriesCache.get().get(geographic, temperatureAddress);
                    series[1] = SeriesCache.get().get(geographic, humidityAddress);
                }
            });
        } catch (InvalidConnectionRequestException e) {
            error(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "Нет сессии оператора WebCTRL — откройте аддон из интерфейса WebCTRL.");
            return;
        } catch (Exception e) {
            log("Не удалось прочитать тренды для TWPI", e);
            error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Ошибка чтения трендов: " + TrendReader.describe(e));
            return;
        }

        HourlySeries temperature = series[0];
        HourlySeries humidity = series[1];
        if (temperature == null || humidity == null) {
            error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Тренды не прочитаны.");
            return;
        }
        if (!temperature.isOk()) {
            error(response, HttpServletResponse.SC_BAD_REQUEST, temperature.error);
            return;
        }
        if (!humidity.isOk()) {
            error(response, HttpServletResponse.SC_BAD_REQUEST, humidity.error);
            return;
        }

        TwpiCalculator.Report report = TwpiCalculator.compute(temperature, humidity, fromMillis, toMillis);

        JSONObject body = new JSONObject();
        body.put("ok", true);
        body.put("periodDays", days);
        body.put("from", fromMillis);
        body.put("to", toMillis);
        body.put("hours", report.jointHours);
        body.put("expectedHours", report.expectedHours);
        body.put("coverage", round(report.coverage(), 3));
        body.put("hasResult", report.hasResult());
        if (report.hasResult()) {
            body.put("twpi", round(report.twpi, 1));
            body.put("piLast", round(report.piLast, 1));
        }
        body.put("temperature", seriesJson(temperature, report.temperatureAverage, report.temperatureMin,
                report.temperatureMax, report.temperatureHours));
        body.put("humidity", seriesJson(humidity, report.humidityAverage, report.humidityMin,
                report.humidityMax, report.humidityHours));

        JSONArray notes = new JSONArray();
        if (temperature.note != null) {
            notes.put(temperature.note);
        }
        if (humidity.note != null && !humidity.note.equals(temperature.note)) {
            notes.put(humidity.note);
        }
        for (String note : report.notes) {
            notes.put(note);
        }
        body.put("notes", notes);

        ok(response, body);
    }

    private static JSONObject seriesJson(HourlySeries series, double average, double min, double max, int hours) {
        JSONObject json = new JSONObject();
        json.put("address", series.address);
        json.put("name", series.displayName == null ? JSONObject.NULL : series.displayName);
        json.put("path", series.displayPath == null ? JSONObject.NULL : series.displayPath);
        json.put("units", series.units == null ? JSONObject.NULL : series.units);
        json.put("samples", series.sampleCount);
        json.put("hours", hours);
        if (!Double.isNaN(average)) {
            json.put("avg", round(average, 1));
            json.put("min", round(min, 1));
            json.put("max", round(max, 1));
        }
        return json;
    }

    private static void ok(HttpServletResponse response, JSONObject body) throws IOException {
        write(response, HttpServletResponse.SC_OK, body);
    }

    private static void error(HttpServletResponse response, int status, String message) throws IOException {
        JSONObject body = new JSONObject();
        body.put("ok", false);
        body.put("error", message);
        write(response, status, body);
    }

    private static void write(HttpServletResponse response, int status, JSONObject body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json; charset=UTF-8");
        PrintWriter writer = response.getWriter();
        writer.write(body.toString());
        writer.flush();
    }

    private static double round(double value, int digits) {
        double factor = Math.pow(10, digits);
        return Math.round(value * factor) / factor;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(trim(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
