<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%
    // Простое табло для рядовых пользователей: только плашки помещений.
    // Период можно задать ссылкой: board.jsp?days=90 (по умолчанию 30).
    String daysParam = request.getParameter("days");
    int days = 30;
    try {
        if (daysParam != null) {
            days = Integer.parseInt(daysParam.trim());
        }
    } catch (NumberFormatException ignored) {
        days = 30;
    }
    if (days < 7) { days = 7; }
    if (days > 550) { days = 550; }
%>
<!DOCTYPE html>
<html lang="ru">
<head>
    <meta charset="utf-8">
    <meta http-equiv="Cache-Control" content="no-store, must-revalidate">
    <title>TWPI — состояние помещений</title>
    <link rel="stylesheet" href="theme.css?v=0.4.0">
</head>
<body class="board">
<div class="wrap board-wrap">
    <div class="rooms board-rooms" id="roomsHost"></div>
    <p class="board-status" id="status">Загрузка…</p>
</div>

<script>
(function () {
    "use strict";

    var DAYS = <%= days %>;
    var REFRESH_MS = 5 * 60 * 1000;
    var $ = function (id) { return document.getElementById(id); };

    function fmt(value) {
        if (value === null || value === undefined || isNaN(value)) { return "—"; }
        return Number(value).toFixed(1).replace(".", ",");
    }

    function esc(text) {
        return String(text === null || text === undefined ? "" : text)
            .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;");
    }

    function apiBase() {
        var path = window.location.pathname;
        if (!/\/$/.test(path) && !/\.[a-z0-9]+$/i.test(path)) { path += "/"; }
        return path.replace(/[^\/]*$/, "");
    }

    /** Внутри WebCTRL location может быть чужим — пробуем и абсолютный /twpi/. */
    function getJson(path) {
        var bases = [apiBase()];
        if (bases.indexOf("/twpi/") === -1) { bases.push("/twpi/"); }
        var index = 0;
        function attempt() {
            if (index >= bases.length) {
                return Promise.reject(new Error("не удалось запросить " + path));
            }
            var url = bases[index++] + path;
            return fetch(url, { headers: { "Accept": "application/json" }, cache: "no-store" })
                .then(function (response) {
                    if (response.status === 404 && index < bases.length) { return attempt(); }
                    return response.text().then(function (text) {
                        var body;
                        try { body = JSON.parse(text); }
                        catch (e) {
                            throw new Error("сервер вернул не JSON (HTTP " + response.status + ")");
                        }
                        if (!body.ok) { throw new Error(body.error || ("HTTP " + response.status)); }
                        return body;
                    });
                });
        }
        return attempt();
    }

    function card(room) {
        var className = "room-card";
        if (room.error) {
            className += " err";
        } else if (room.twpi >= 60) {
            className += " good";
        } else if (room.twpi >= 40) {
            className += " warn";
        } else {
            className += " bad";
        }

        var body;
        if (room.error) {
            body = '<div class="big">' + esc(room.error) + '</div>';
        } else {
            body = '<div class="big">' + fmt(room.twpi) + '<small>лет</small></div>'
                + '<div class="sub">'
                + 'T ' + fmt(room.t) + ' °C · RH ' + fmt(room.rh) + ' %<br>'
                + 'покрытие ' + Math.round((room.coverage || 0) * 100) + ' %'
                + '</div>';
        }
        return '<div class="' + className + '"><h3>' + esc(room.name) + '</h3>' + body + '</div>';
    }

    function clock() {
        var now = new Date();
        return now.toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" });
    }

    function load() {
        getJson("api/all?days=" + DAYS)
            .then(function (data) {
                var rooms = data.rooms || [];
                $("roomsHost").innerHTML = rooms.map(card).join("");
                $("status").textContent = rooms.length
                    ? "обновлено в " + clock()
                    : "Помещения ещё не заведены — обратитесь к администратору";
                $("status").className = "board-status";
            })
            .catch(function (e) {
                $("status").textContent = "Нет данных: " + (e.message || e);
                $("status").className = "board-status board-status-error";
            });
    }

    load();
    setInterval(load, REFRESH_MS);
    document.addEventListener("visibilitychange", function () {
        if (!document.hidden) { load(); }
    });
}());
</script>
</body>
</html>
