package com.controlj.addon.twpi;

import org.json.JSONObject;

import javax.servlet.ServletContext;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;

/**
 * Хранилище списка помещений.
 *
 * <p>Настройки лежат в JSON-файле рядом с WebCTRL, а не внутри аддона: при
 * переустановке .addon каталог веб-приложения пересоздаётся, и файл бы пропал.
 * Путь выбирается так:</p>
 *
 * <ol>
 *   <li>{@code <catalina.base>/conf/twpi-rooms.json}</li>
 *   <li>{@code <catalina.base>/twpi-rooms.json}</li>
 *   <li>каталог временных файлов контекста (крайний случай)</li>
 * </ol>
 */
public final class RoomsStore {

    private static final String FILE_NAME = "twpi-rooms.json";

    private final File file;

    public RoomsStore(ServletContext context) {
        this.file = chooseFile(context);
    }

    private static File chooseFile(ServletContext context) {
        String base = System.getProperty("catalina.base");
        if (base != null && !base.trim().isEmpty()) {
            File root = new File(base);
            File conf = new File(root, "conf");
            if (conf.isDirectory() && conf.canWrite()) {
                return new File(conf, FILE_NAME);
            }
            if (root.isDirectory() && root.canWrite()) {
                return new File(root, FILE_NAME);
            }
        }
        Object temp = context == null ? null : context.getAttribute("javax.servlet.context.tempdir");
        if (temp instanceof File) {
            return new File((File) temp, FILE_NAME);
        }
        return new File(System.getProperty("java.io.tmpdir", "."), FILE_NAME);
    }

    public String path() {
        return file.getAbsolutePath();
    }

    public boolean isWritable() {
        try {
            return file.getParentFile() != null && file.getParentFile().canWrite();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Читает настройки; при отсутствии файла возвращает пустой список. */
    public JSONObject load() {
        if (!file.isFile()) {
            return empty();
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line);
            }
            JSONObject json = new JSONObject(text.toString());
            if (!json.has("rooms")) {
                json.put("rooms", new org.json.JSONArray());
            }
            return json;
        } catch (Throwable e) {
            JSONObject fallback = empty();
            fallback.put("error", "не удалось прочитать " + path() + ": " + e);
            return fallback;
        } finally {
            close(reader);
        }
    }

    /** Сохраняет настройки (через временный файл, чтобы не потерять при сбое). */
    public void save(JSONObject data) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        File temp = new File(file.getAbsolutePath() + ".tmp");
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(temp), "UTF-8");
            writer.write(data.toString(2));
            writer.flush();
        } finally {
            close(writer);
        }
        if (file.exists() && !file.delete()) {
            throw new IOException("не удалось перезаписать " + path());
        }
        if (!temp.renameTo(file)) {
            throw new IOException("не удалось сохранить " + path());
        }
    }

    private static JSONObject empty() {
        JSONObject json = new JSONObject();
        json.put("rooms", new org.json.JSONArray());
        json.put("period", 30);
        return json;
    }

    private static void close(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }
}
