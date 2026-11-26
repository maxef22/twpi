package com.controlj.addon.twpi.servlet;

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
 * Расчёт TWPI по всем помещениям из настроек.
 *
 * <pre>
 * GET /twpi/api/all?days=30
 * </pre>
 *
 * <p>Все тренды читаются в одном {@code runReadAction}. Внутри помещения
 * несколько датчиков усредняются по часам, поэтому помещение с четырьмя
 * точками не получает вчетверо больший вес.</p>
 */
public class CalcAllServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final long DAY_MS = 24L * 3600L * 1000L;
    private static final int MAX_DAYS = 366;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        int days = clamp(parseInt(request.getParameter("days"), 30), 1, MAX_DAYS);
        final long toMillis = System.currentTimeMillis();
        final long fromMillis = toMillis - days * DAY_MS;

        if ("1".equals(request.getParameter("fresh"))) {
            SeriesCache.get().clear();
        }

        RoomsStore store = new RoomsStore(getServletContext());
        JSONObject config = store.load();
        JSONArray roomsJson = config.optJSONArray("rooms");

        final List<RoomInput> inputs = new ArrayList<RoomInput>();
        if (roomsJson != null) {
            for (int i = 0; i < roomsJson.length(); i++) {
                JSONObject room = roomsJson.optJSONObject(i);
                if (room == null) {
                    continue;
                }
                RoomInput input = new RoomInput(room.optString("name", "Помещение " + (i + 1)));
                JSONArray sensors = room.optJSONArray("sensors");
                if (sensors != null) {
                    for (int s = 0; s < sensors.length(); s++) {
                        JSONObject sensor = sensors.optJSONObject(s);
                        if (sensor == null) {
                            continue;
                        }
                        String t = sensor.optString("t", "").trim();
                        String rh = sensor.optString("rh", "").trim();
                        if (t.isEmpty() && rh.isEmpty()) {
                            continue;
                        }
                        input.sensors.add(new SensorInput(sensor.optString("name", "Датчик " + (s + 1)), t, rh));
                    }
                }
                inputs.add(input);
            }
        }

        if (!inputs.isEmpty()) {
            try {
                SystemConnection connection = DirectAccess.getDirectAccess().getUserSystemConnection(request);
                connection.runReadAction(new ReadAction() {
                    @Override
                    public void execute(SystemAccess access) throws Exception {
                        Tree geographic = access.getTree(SystemTree.Geographic);
                        for (RoomInput room : inputs) {
                            for (SensorInput sensor : room.sensors) {
                                if (!sensor.tAddress.isEmpty()) {
                                    sensor.temperature = SeriesCache.get().get(geographic, sensor.tAddress);
                                }
                                if (!sensor.rhAddress.isEmpty()) {
                                    sensor.humidity = SeriesCache.get().get(geographic, sensor.rhAddress);
                                }
                            }
                        }
                    }
                });
            } catch (InvalidConnectionRequestException e) {
                error(response, HttpServletResponse.SC_UNAUTHORIZED,
                        "Нет сессии оператора WebCTRL — откройте аддон из интерфейса WebCTRL.");
                return;
            } catch (Throwable e) {
                log("Не удалось прочитать тренды для списка помещений", e);
                error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                        "Ошибка чтения трендов: " + TrendReader.describe(e));
                return;
            }
        }

        JSONArray result = new JSONArray();
        for (int i = 0; i < inputs.size(); i++) {
            JSONObject roomJson = inputs.get(i).toJson(fromMillis, toMillis);
            roomJson.put("index", i);
            result.put(roomJson);
        }

        JSONObject body = new JSONObject();
        body.put("ok", true);
        body.put("days", days);
        body.put("from", fromMillis);
        body.put("to", toMillis);
        body.put("storage", store.path());
        body.put("cacheAgeSec", cacheAge(inputs));
        body.put("rooms", result);
        write(response, HttpServletResponse.SC_OK, body);
    }

    /** Самый старый из использованных рядов — сколько секунд назад прочитан. */
    private static long cacheAge(List<RoomInput> inputs) {
        long oldest = -1L;
        for (RoomInput room : inputs) {
            for (SensorInput sensor : room.sensors) {
                oldest = Math.max(oldest, SeriesCache.get().ageSeconds(sensor.tAddress));
                oldest = Math.max(oldest, SeriesCache.get().ageSeconds(sensor.rhAddress));
            }
        }
        return oldest < 0 ? 0 : oldest;
    }

    /** Помещение из настроек плюс прочитанные ряды. */
    private static final class RoomInput {
        final String name;
        final List<SensorInput> sensors = new ArrayList<SensorInput>();

        RoomInput(String name) {
            this.name = name;
        }

        JSONObject toJson(long fromMillis, long toMillis) {
            JSONObject json = new JSONObject();
            json.put("name", name);

            List<HourlySeries> temperatures = new ArrayList<HourlySeries>();
            List<HourlySeries> humidities = new ArrayList<HourlySeries>();
            JSONArray sensorsJson = new JSONArray();
            for (SensorInput sensor : sensors) {
                if (sensor.temperature != null && sensor.temperature.isOk()) {
                    temperatures.add(sensor.temperature);
                }
                if (sensor.humidity != null && sensor.humidity.isOk()) {
                    humidities.add(sensor.humidity);
                }
                sensorsJson.put(sensor.toJson());
            }
            json.put("sensors", sensorsJson);

            if (sensors.isEmpty()) {
                json.put("error", "в помещении нет датчиков");
                return json;
            }
            if (temperatures.isEmpty() || humidities.isEmpty()) {
                json.put("error", "нет данных: " + (temperatures.isEmpty() ? "температура" : "влажность"));
                return json;
            }

            TwpiCalculator.Report report = TwpiCalculator.computeMulti(temperatures, humidities,
                    fromMillis, toMillis);
            if (!report.hasResult()) {
                json.put("error", "нет общих часов с температурой и влажностью");
                JSONArray notes = new JSONArray();
                for (String note : report.notes) {
                    notes.put(note);
                }
                json.put("notes", notes);
                return json;
            }

            json.put("twpi", round(report.twpi, 1));
            json.put("piLast", Double.isNaN(report.piLast) ? null : round(report.piLast, 1));
            json.put("hours", report.jointHours);
            json.put("expectedHours", report.expectedHours);
            json.put("coverage", round(report.coverage(), 3));
            json.put("t", round(report.temperatureAverage, 1));
            json.put("rh", round(report.humidityAverage, 1));
            json.put("tMin", round(report.temperatureMin, 1));
            json.put("tMax", round(report.temperatureMax, 1));
            json.put("rhMin", round(report.humidityMin, 1));
            json.put("rhMax", round(report.humidityMax, 1));
            JSONArray notes = new JSONArray();
            for (String note : report.notes) {
                notes.put(note);
            }
            json.put("notes", notes);
            return json;
        }
    }

    /** Один датчик: адреса и прочитанные ряды. */
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
            if (temperature != null && !temperature.isOk()) {
                json.put("tError", temperature.error);
            }
            if (humidity != null && !humidity.isOk()) {
                json.put("rhError", humidity.error);
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
