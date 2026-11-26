<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="com.controlj.green.addonsupport.access.DirectAccess" %>
<%@ page import="com.controlj.green.addonsupport.access.Location" %>
<%@ page import="com.controlj.green.addonsupport.access.ReadAction" %>
<%@ page import="com.controlj.green.addonsupport.access.SystemAccess" %>
<%@ page import="com.controlj.green.addonsupport.access.SystemConnection" %>
<%@ page import="com.controlj.green.addonsupport.access.SystemTree" %>
<%@ page import="com.controlj.green.addonsupport.access.Tree" %>
<%@ page import="com.controlj.green.addonsupport.access.aspect.TrendSource" %>
<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.Collections" %>
<%@ page import="java.util.Comparator" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%!
    private static final int MAX_VISITED = 40000;
    private static final int MAX_HITS = 300;
    private static final int MAX_DEPTH = 24;
    private static final long TIME_BUDGET_MS = 8000L;

    /**
     * Всё, что нужно для таблицы, снимается внутри runReadAction: обращение к
     * аспектам вне действия доступа в WebCTRL падает (EmptyStackException).
     */
    private static final class Hit {
        final String name;
        final String reference;
        final String lookup;
        final String trend;

        Hit(String name, String reference, String lookup, String trend) {
            this.name = name;
            this.reference = reference;
            this.lookup = lookup;
            this.trend = trend;
        }

        boolean hasAnalogTrend() {
            return "analog".equals(trend);
        }
    }

    private static String esc(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String url(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String ref(Location location) {
        try {
            String value = location.getReferenceName();
            return value == null ? "" : value.trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Display Name, при пустом — локализованный, затем Reference Name. */
    private static String title(Location location) {
        try {
            String value = location.getDisplayName();
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        } catch (Throwable ignored) {
        }
        try {
            String value = location.getDisplayName(Locale.getDefault());
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        } catch (Throwable ignored) {
        }
        String reference = ref(location);
        return reference.isEmpty() ? "(без имени)" : reference;
    }

    /** "analog" — есть аналоговый тренд; иначе пояснение, почему нет. */
    private static String trend(Location location) {
        try {
            TrendSource source = location.getAspect(TrendSource.class);
            if (source == null) {
                return "нет тренда";
            }
            if (source.getType() != TrendSource.Type.Analog) {
                return "не аналоговый";
            }
            try {
                if (!source.isEnabled()) {
                    return "сбор отключён";
                }
            } catch (Throwable ignored) {
            }
            return "analog";
        } catch (Throwable t) {
            String name = t.getClass().getSimpleName();
            if (name.contains("NoSuchAspect")) {
                return "нет тренда";
            }
            String message = t.getMessage();
            return "ошибка: " + name + (message == null ? "" : ": " + message);
        }
    }

    private static String join(String prefix, String segment) {
        if (segment == null || segment.isEmpty() || "#".equals(segment)) {
            return prefix;
        }
        return prefix.isEmpty() ? segment : prefix + "/" + segment;
    }

    /**
     * В этой системе reference-имена уже начинаются с '#', поэтому второй '#'
     * спереди не добавляем.
     */
    private static String asLookup(String referencePath) {
        if (referencePath == null || referencePath.isEmpty()) {
            return "#";
        }
        return referencePath.startsWith("#") ? referencePath : "#" + referencePath;
    }

    private static Hit describe(Location location, String referencePath) {
        String reference = ref(location);
        return new Hit(title(location), reference, asLookup(referencePath), trend(location));
    }

    private static void walk(Location node, String refPath, String query,
                             List<Hit> hits, int depth, int[] budget, long deadline) {
        if (budget[0] <= 0 || hits.size() >= MAX_HITS || System.currentTimeMillis() > deadline
                || depth > MAX_DEPTH) {
            return;
        }
        budget[0]--;

        String reference = ref(node);
        String name = title(node);
        if (matches(reference, query) || matches(name, query)) {
            hits.add(new Hit(name, reference, asLookup(refPath), trend(node)));
        }

        List<Location> children;
        try {
            children = new ArrayList<Location>(node.getChildren());
        } catch (Throwable ignored) {
            return;
        }
        for (Location child : children) {
            if (budget[0] <= 0 || hits.size() >= MAX_HITS || System.currentTimeMillis() > deadline) {
                return;
            }
            walk(child, join(refPath, ref(child)), query, hits, depth + 1, budget, deadline);
        }
    }

    private static boolean matches(String value, String query) {
        return value != null && !value.isEmpty()
                && value.toLowerCase(Locale.ENGLISH).contains(query.toLowerCase(Locale.ENGLISH));
    }
%>
<%
    String query = request.getParameter("q");
    query = query == null ? "" : query.trim();

    String browsePath = request.getParameter("path");
    if (browsePath == null || browsePath.trim().isEmpty()) {
        browsePath = "#";
    }

    String error = null;
    List<Hit> rows = new ArrayList<Hit>();

    try {
        SystemConnection connection = DirectAccess.getDirectAccess().getUserSystemConnection(request);

        final Tree[] treeHolder = new Tree[1];
        connection.runReadAction(new ReadAction() {
            public void execute(SystemAccess access) throws Exception {
                treeHolder[0] = access.getTree(SystemTree.Geographic);
            }
        });
        final Tree geo = treeHolder[0];
        final List<Hit> sink = rows;
        final String start = browsePath.trim();
        final String needle = query;

        connection.runReadAction(new ReadAction() {
            public void execute(SystemAccess access) throws Exception {
                if (needle.isEmpty()) {
                    Location node;
                    String refPath;
                    if ("#".equals(start)) {
                        node = geo.getRoot();
                        refPath = "";
                    } else {
                        node = geo.resolve(start);
                        refPath = start.startsWith("#") ? start.substring(1) : start;
                    }
                    for (Location child : node.getChildren()) {
                        sink.add(describe(child, join(refPath, ref(child))));
                    }
                } else {
                    walk(geo.getRoot(), "", needle, sink, 0, new int[]{MAX_VISITED},
                            System.currentTimeMillis() + TIME_BUDGET_MS);
                }
            }
        });

        Collections.sort(rows, new Comparator<Hit>() {
            public int compare(Hit a, Hit b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
    } catch (Throwable t) {
        String message = t.getMessage();
        String type = t.getClass().getSimpleName();
        if (type.contains("InvalidConnectionRequest")) {
            error = "Нет сессии оператора WebCTRL. Откройте эту страницу из интерфейса WebCTRL.";
        } else {
            error = t.getClass().getName() + (message == null ? "" : ": " + message);
        }
    }
%>
<!doctype html>
<html lang="ru">
<head>
    <meta charset="utf-8">
    <title>TWPI — поиск точки</title>
    <style>
        body { font: 14px/1.5 "Segoe UI", Arial, sans-serif; margin: 22px; color: #1c2b36; background: #f4f7fa; }
        h1 { font-size: 19px; margin: 0 0 4px; }
        p.lead { color: #6b7c8a; margin: 0 0 16px; }
        form { margin: 0 0 16px; }
        input[type=text] { padding: 8px 10px; width: 320px; border: 1px solid #cfd9e2; border-radius: 8px; font: inherit; }
        button { padding: 8px 16px; border: none; border-radius: 8px; background: #10689a; color: #fff; font: inherit; cursor: pointer; }
        table { border-collapse: collapse; width: 100%; background: #fff; box-shadow: 0 1px 3px rgba(0,0,0,.07); }
        th, td { border: 1px solid #dbe3ea; padding: 6px 9px; text-align: left; vertical-align: top; }
        th { background: #eef3f7; }
        code { font-family: Consolas, monospace; font-size: 12px; }
        .muted { color: #8494a2; }
        .err { background: #fdecea; border: 1px solid #f5c6c0; color: #8c2418; padding: 10px 12px; border-radius: 8px; }
        .tag { display: inline-block; padding: 1px 6px; border-radius: 5px; background: #e7f3ea; color: #1e8449; font-size: 11.5px; }
        .tag.no { background: #f0f0f0; color: #97a3ad; }
        .tag.bad { background: #fdecea; color: #b03a2e; }
        a.act { display: inline-block; padding: 2px 8px; border-radius: 6px; background: #10689a; color: #fff; text-decoration: none; font-size: 12px; margin-right: 4px; }
        a.act.rh { background: #1e8449; }
        a { color: #10689a; }
    </style>
</head>
<body>
<h1>Поиск точки с трендом</h1>
<p class="lead">
    Введите часть имени точки (например <code>zone_temp</code>) — страница найдёт её в GEO-дереве
    и покажет Lookup ID. Кнопки «в T» / «в RH» подставят адрес в расчёт.
</p>

<form method="get" action="tree.jsp">
    <input type="text" name="q" value="<%= esc(query) %>" placeholder="часть имени точки или помещения">
    <button type="submit">Найти</button>
    <a href="tree.jsp">к дереву</a>
</form>

<% if (error != null) { %>
<div class="err"><%= esc(error) %></div>
<% } else { %>
    <p>
        <% if (query.isEmpty()) { %>
            Папка: <code><%= esc(browsePath) %></code>
            <% if (!"#".equals(browsePath.trim())) { %> &nbsp;·&nbsp;<a href="tree.jsp">в корень</a><% } %>
        <% } else { %>
            Найдено: <b><%= rows.size() %></b>
            <% if (rows.isEmpty()) { %><span class="muted">— ничего, попробуйте другую часть имени</span><% } %>
        <% } %>
    </p>
    <table>
        <tr><th>Имя</th><th>Reference</th><th>Lookup ID</th><th>Тренд</th><th></th></tr>
        <% for (Hit hit : rows) { %>
            <tr>
                <td>
                    <% if (query.isEmpty()) { %>
                        <a href="tree.jsp?path=<%= url(hit.lookup) %>"><%= esc(hit.name) %></a>
                    <% } else { %>
                        <%= esc(hit.name) %>
                    <% } %>
                </td>
                <td><code><%= esc(hit.reference) %></code></td>
                <td><code class="muted"><%= esc(hit.lookup) %></code></td>
                <td><span class="tag <%= hit.hasAnalogTrend() ? "" : (hit.trend.startsWith("ошибка") ? "bad" : "no") %>"><%= esc(hit.hasAnalogTrend() ? "аналоговый" : hit.trend) %></span></td>
                <td>
                    <% if (hit.hasAnalogTrend()) { %>
                    <a class="act" href="./?t=<%= url(hit.lookup) %>">в T</a>
                    <a class="act rh" href="./?rh=<%= url(hit.lookup) %>">в RH</a>
                    <% } %>
                </td>
            </tr>
        <% } %>
    </table>
<% } %>

<p style="margin-top:18px"><a href="./">&larr; к расчёту</a></p>
</body>
</html>
