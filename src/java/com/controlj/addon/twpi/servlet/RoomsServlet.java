package com.controlj.addon.twpi.servlet;

import com.controlj.addon.twpi.RoomsStore;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Настройки списка помещений.
 *
 * <pre>
 * GET  /twpi/api/rooms  — текущий список + путь к файлу настроек
 * POST /twpi/api/rooms  — сохранить (тело — JSON)
 * </pre>
 */
public class RoomsServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final int MAX_ROOMS = 200;
    private static final int MAX_SENSORS = 50;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        RoomsStore store = new RoomsStore(getServletContext());
        JSONObject data = store.load();
        data.put("storage", store.path());
        data.put("writable", store.isWritable());
        write(response, HttpServletResponse.SC_OK, data);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        request.setCharacterEncoding("UTF-8");
        String body = readBody(request);
        if (body == null || body.trim().isEmpty()) {
            error(response, HttpServletResponse.SC_BAD_REQUEST, "Пустое тело запроса");
            return;
        }

        JSONObject incoming;
        try {
            incoming = new JSONObject(body);
        } catch (Exception e) {
            error(response, HttpServletResponse.SC_BAD_REQUEST, "Не разобрать JSON: " + e.getMessage());
            return;
        }

        JSONArray rooms = incoming.optJSONArray("rooms");
        if (rooms == null) {
            error(response, HttpServletResponse.SC_BAD_REQUEST, "Нет массива rooms");
            return;
        }
        if (rooms.length() > MAX_ROOMS) {
            error(response, HttpServletResponse.SC_BAD_REQUEST, "Слишком много помещений (максимум " + MAX_ROOMS + ")");
            return;
        }

        JSONArray clean = new JSONArray();
        for (int i = 0; i < rooms.length(); i++) {
            JSONObject room = rooms.optJSONObject(i);
            if (room == null) {
                continue;
            }
            JSONObject out = new JSONObject();
            String name = room.optString("name", "").trim();
            out.put("name", name.isEmpty() ? ("Помещение " + (i + 1)) : name);

            JSONArray sensors = room.optJSONArray("sensors");
            JSONArray cleanSensors = new JSONArray();
            if (sensors != null) {
                for (int s = 0; s < sensors.length() && s < MAX_SENSORS; s++) {
                    JSONObject sensor = sensors.optJSONObject(s);
                    if (sensor == null) {
                        continue;
                    }
                    String t = sensor.optString("t", "").trim();
                    String rh = sensor.optString("rh", "").trim();
                    if (t.isEmpty() && rh.isEmpty()) {
                        continue;
                    }
                    JSONObject cleanSensor = new JSONObject();
                    cleanSensor.put("name", sensor.optString("name", "").trim());
                    cleanSensor.put("t", t);
                    cleanSensor.put("rh", rh);
                    cleanSensors.put(cleanSensor);
                }
            }
            out.put("sensors", cleanSensors);
            clean.put(out);
        }

        JSONObject data = new JSONObject();
        data.put("rooms", clean);
        data.put("period", incoming.optInt("period", 30));

        RoomsStore store = new RoomsStore(getServletContext());
        try {
            store.save(data);
        } catch (Exception e) {
            error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Не удалось сохранить настройки в " + store.path() + ": " + e.getMessage());
            return;
        }

        JSONObject answer = new JSONObject();
        answer.put("ok", true);
        answer.put("saved", clean.length());
        answer.put("storage", store.path());
        write(response, HttpServletResponse.SC_OK, answer);
    }

    private static String readBody(HttpServletRequest request) throws IOException {
        BufferedReader reader = request.getReader();
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[4096];
        int read;
        while ((read = reader.read(buffer)) > 0) {
            text.append(buffer, 0, read);
        }
        return text.toString();
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
}
