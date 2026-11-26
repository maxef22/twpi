<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="com.controlj.addon.twpi.Version" %>
<%@ page import="java.io.ByteArrayOutputStream" %>
<%@ page import="java.io.InputStream" %>
<%@ page import="java.lang.reflect.Method" %>
<%@ page import="java.util.Enumeration" %>
<%!
    private static String esc(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String shortError(Throwable e) {
        if (e == null) {
            return "нет";
        }
        String message = e.getMessage();
        return e.getClass().getName() + (message == null ? "" : ": " + message);
    }
%>
<%
    String contextPath = request.getContextPath();

    String classFileVersion = "не найден";
    String classFileError = null;
    try {
        InputStream in = application.getResourceAsStream(
                "/WEB-INF/classes/com/controlj/addon/twpi/servlet/CalcServlet.class");
        if (in != null) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            in.close();
            byte[] bytes = buffer.toByteArray();
            if (bytes.length > 8) {
                int major = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
                String javaName;
                switch (major) {
                    case 52: javaName = "Java 8"; break;
                    case 53: javaName = "Java 9"; break;
                    case 54: javaName = "Java 10"; break;
                    case 55: javaName = "Java 11"; break;
                    case 56: javaName = "Java 12"; break;
                    case 61: javaName = "Java 17"; break;
                    case 65: javaName = "Java 21"; break;
                    default: javaName = "неизвестно";
                }
                classFileVersion = major + " (" + javaName + ")";
            }
        }
    } catch (Throwable t) {
        classFileError = shortError(t);
    }

    String apiStatus;
    try {
        Class.forName("com.controlj.green.addonsupport.access.DirectAccess");
        apiStatus = "OK";
    } catch (Throwable t) {
        apiStatus = "ОШИБКА: " + shortError(t);
    }

    String jsonStatus;
    try {
        Class<?> jsonClass = Class.forName("org.json.JSONObject");
        jsonStatus = "OK — " + jsonClass.getProtectionDomain().getCodeSource().getLocation();
    } catch (Throwable t) {
        jsonStatus = "ОШИБКА: " + shortError(t);
    }

    String servletStatus;
    try {
        Class.forName("com.controlj.addon.twpi.servlet.CalcServlet");
        servletStatus = "OK";
    } catch (Throwable t) {
        servletStatus = "ОШИБКА: " + shortError(t);
    }

    String registration;
    try {
        Method method = application.getClass().getMethod("getServletRegistration", String.class);
        Object result = method.invoke(application, "CalcServlet");
        registration = result == null ? "НЕ ЗАРЕГИСТРИРОВАН" : "зарегистрирован: " + result;
    } catch (Throwable t) {
        registration = "нет метода getServletRegistration (" + shortError(t) + ")";
    }

    String webXml;
    try {
        InputStream in = application.getResourceAsStream("/WEB-INF/web.xml");
        if (in == null) {
            webXml = "НЕ НАЙДЕН в /WEB-INF/web.xml";
        } else {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            in.close();
            String text = new String(buffer.toByteArray(), "UTF-8");
            webXml = "найден, " + text.length() + " символов";
        }
    } catch (Throwable t) {
        webXml = "ОШИБКА чтения: " + shortError(t);
    }

    String jspStatus;
    try {
        Class.forName("org.apache.jasper.runtime.JspRuntimeLibrary");
        jspStatus = "OK";
    } catch (Throwable t) {
        jspStatus = "нет (" + shortError(t) + ")";
    }
%>
<!doctype html>
<html lang="ru">
<head>
    <meta charset="utf-8">
    <title>TWPI — диагностика</title>
    <style>
        body { font: 14px/1.55 "Segoe UI", Arial, sans-serif; color: #1c2b36; margin: 24px; background: #f4f7fa; }
        h1 { font-size: 19px; margin: 0 0 4px; }
        p.lead { color: #6b7c8a; margin: 0 0 18px; }
        table { border-collapse: collapse; background: #fff; box-shadow: 0 1px 3px rgba(0,0,0,.07); }
        th, td { border: 1px solid #dbe3ea; padding: 7px 11px; text-align: left; vertical-align: top; }
        th { background: #eef3f7; font-weight: 600; width: 260px; }
        code { font-family: Consolas, monospace; background: #eef3f7; padding: 1px 4px; }
        .bad { color: #b03a2e; font-weight: 600; }
        .good { color: #1e8449; font-weight: 600; }
        a { color: #10689a; }
    </style>
</head>
<body>
<h1>TWPI — диагностика контекста</h1>
<p class="lead">Откройте эту страницу, если расчёт возвращает 404 или 500, и пришлите её содержимое.</p>

<table>
    <tr><th>Версия аддона</th>
        <td><b><%= Version.VERSION %></b></td></tr>
    <tr><th>Java (java.version)</th>
        <td><%= esc(System.getProperty("java.version")) %> — <%= esc(System.getProperty("java.vendor")) %></td></tr>
    <tr><th>Java (java.home)</th>
        <td><code><%= esc(System.getProperty("java.home")) %></code></td></tr>
    <tr><th>Класс CalcServlet собран под</th>
        <td><%= esc(classFileVersion) %><%= classFileError == null ? "" : " — " + esc(classFileError) %></td></tr>
    <tr><th>Контейнер</th>
        <td><%= esc(application.getServerInfo()) %> (Servlet API <%= application.getMajorVersion() %>.<%= application.getMinorVersion() %>)</td></tr>
    <tr><th>Контекст</th>
        <td><code><%= esc(contextPath) %></code></td></tr>
    <tr><th>WEB-INF/web.xml</th>
        <td><%= esc(webXml) %></td></tr>
    <tr><th>Сервлет зарегистрирован</th>
        <td><%= esc(registration) %></td></tr>
    <tr><th>Класс сервлета загружается</th>
        <td><span class="<%= servletStatus.equals("OK") ? "good" : "bad" %>"><%= esc(servletStatus) %></span></td></tr>
    <tr><th>API DirectAccess</th>
        <td><span class="<%= apiStatus.equals("OK") ? "good" : "bad" %>"><%= esc(apiStatus) %></span></td></tr>
    <tr><th>org.json</th>
        <td><span class="<%= jsonStatus.startsWith("OK") ? "good" : "bad" %>"><%= esc(jsonStatus) %></span></td></tr>
    <tr><th>Jasper (JSP)</th>
        <td><%= esc(jspStatus) %></td></tr>
    <tr><th>Атрибуты контекста</th>
        <td><code><%
            Enumeration<String> names = application.getAttributeNames();
            StringBuilder attributes = new StringBuilder();
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                attributes.append(name).append(" = ").append(application.getAttribute(name)).append("\n");
            }
            %><%= esc(attributes.toString()) %></code></td></tr>
</table>

<p style="margin-top:18px"><a href="./">← вернуться к расчёту</a></p>
</body>
</html>
