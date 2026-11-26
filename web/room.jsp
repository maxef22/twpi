<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta http-equiv="Cache-Control" content="no-store, must-revalidate">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>TWPI — помещение</title>
<link rel="stylesheet" href="theme.css?v=0.3.1">
</head>
<body>
<div class="wrap">

    <div class="top">
        <a class="back" href="./" title="К списку помещений">←</a>
        <h1 id="name">Помещение</h1>
        <div class="controls">
            <select id="days">
                <option value="7">7 дней</option>
                <option value="30" selected>30 дней</option>
                <option value="90">90 дней</option>
                <option value="365">365 дней</option>
                    <option value="550">550 дней</option>
            </select>
            <button type="button" class="primary" id="refresh">Обновить</button>
            <a class="link" href="rooms.jsp">Настроить</a>
        </div>
    </div>

    <div id="error" class="error" style="display:none"></div>

    <section class="card" id="mainCard">
        <div style="display:flex; align-items:baseline; gap:14px; flex-wrap:wrap">
            <div class="value"><span id="twpi">—</span><small>лет</small></div>
            <div class="caption" id="caption"></div>
        </div>

        <div class="gauge"><div class="gauge-marker" id="marker" style="left:0%"></div></div>
        <div class="gauge-scale"><span>0</span><span>30</span><span>60</span><span>90</span><span>120+</span></div>

        <div class="stats" id="stats"></div>
        <div class="hint" id="notes"></div>
    </section>

    <section class="card">
        <h2>Датчики помещения</h2>
        <table id="sensors"><tbody></tbody></table>
    </section>

    <section class="card">
        <h2 id="chartTitle">История</h2>
        <div class="chart">
            <div class="y-axis" id="yAxis"></div>
            <div class="plot-wrap">
                <div class="grid-lines"><i></i><i></i><i></i><i></i></div>
                <div class="months" id="months"></div>
            </div>
        </div>
        <div class="hint" id="chartHint">Столбик — TWPI за интервал, лет. Незавершённый интервал считается по имеющимся часам.</div>
    </section>

</div>

<script>
(function () {
    "use strict";

    var $ = function (id) { return document.getElementById(id); };

    function fmt(value) {
        if (value === null || value === undefined || isNaN(value)) { return "—"; }
        return Number(value).toFixed(1).replace(".", ",");
    }

    function esc(text) {
        return String(text === null || text === undefined ? "" : text)
            .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
    }

    function queryParam(name) {
        var match = new RegExp("[?&]" + name + "=([^&]*)").exec(window.location.search);
        return match ? decodeURIComponent(match[1].replace(/\+/g, " ")) : "";
    }

    function apiBase() {
        var path = window.location.pathname;
        if (!/\/$/.test(path) && !/\.[a-z0-9]+$/i.test(path)) { path += "/"; }
        return path.replace(/[^\/]*$/, "");
    }

    function dateTime(ms) {
        return new Date(ms).toLocaleString("ru-RU", {
            day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit"
        });
    }

    function pad(value) {
        return value < 10 ? "0" + value : String(value);
    }

    /** Округлённый шаг шкалы: 1, 2, 5, 10, 20, 50, 100… */
    function niceStep(value) {
        if (!value || value <= 0) { return 10; }
        var pow = Math.pow(10, Math.floor(Math.log10(value)));
        var n = value / pow;
        var factor = n <= 1 ? 1 : (n <= 2 ? 2 : (n <= 5 ? 5 : 10));
        return factor * pow;
    }

    function bucketLabel(millis, granularity) {
        var date = new Date(millis);
        if (granularity === "month") {
            var name = date.toLocaleString("ru-RU", { month: "short" }).replace(".", "");
            return name + " " + String(date.getFullYear()).slice(2);
        }
        return pad(date.getDate()) + "." + pad(date.getMonth() + 1);
    }

    function bucketTitle(bucket, granularity, has) {
        var from = new Date(bucket.from);
        var to = new Date(bucket.to);
        var range = pad(from.getDate()) + "." + pad(from.getMonth() + 1);
        if (granularity !== "month") {
            range += "—" + pad(to.getDate()) + "." + pad(to.getMonth() + 1);
        }
        if (!has) { return range + ": нет данных"; }
        return range + ": TWPI " + fmt(bucket.twpi) + " лет · " + bucket.hours + " ч";
    }

    function stat(value, label) {
        return '<div class="stat"><b>' + value + '</b><i>' + label + '</i></div>';
    }

    function showError(message) {
        $("error").style.display = "block";
        $("error").textContent = message;
        $("mainCard").style.display = "none";
    }

    function render(data) {
        $("name").textContent = data.name || "Помещение";

        if (data.error) {
            $("twpi").textContent = "—";
            $("caption").textContent = data.error;
            $("marker").style.left = "0%";
            $("stats").innerHTML = "";
            $("notes").textContent = "";
        } else {
            $("twpi").textContent = fmt(data.twpi);
            var caption = "за " + data.days + " дн. · " + dateTime(data.from) + " — " + dateTime(data.to);
            if (data.cacheAgeSec && data.cacheAgeSec >= 60) {
                caption += " · кэш " + Math.round(data.cacheAgeSec / 60) + " мин";
            }
            $("caption").textContent = caption;
            $("marker").style.left = (Math.max(0, Math.min(120, data.twpi)) / 120 * 100).toFixed(1) + "%";
            $("stats").innerHTML =
                stat(fmt(data.twpi) + " лет", "TWPI") +
                stat(fmt(data.piLast) + " лет", "PI сейчас") +
                stat(data.hours + " / " + data.expectedHours, "Часов с данными") +
                stat(Math.round((data.coverage || 0) * 100) + " %", "Покрытие периода") +
                stat(fmt(data.t) + " °C", "Температура, средняя") +
                stat(fmt(data.rh) + " %", "Влажность, средняя") +
                stat(fmt(data.tMin) + "…" + fmt(data.tMax) + " °C", "Температура, мин…макс") +
                stat(fmt(data.rhMin) + "…" + fmt(data.rhMax) + " %", "Влажность, мин…макс");
            $("notes").textContent = (data.notes || []).join(" ");
        }

        var rows = (data.sensors || []).map(function (sensor) {
            var problem = sensor.tError || sensor.rhError;
            return "<tr>"
                + "<td>" + esc(sensor.name) + "</td>"
                + "<td><code>" + esc(sensor.t || "—") + "</code></td>"
                + "<td><code>" + esc(sensor.rh || "—") + "</code></td>"
                + "<td>" + (sensor.tSamples === undefined ? "—" : sensor.tSamples) + " / "
                + (sensor.rhSamples === undefined ? "—" : sensor.rhSamples) + "</td>"
                + "<td class=\"" + (problem ? "bad" : "good") + "\">" + (problem ? esc(problem) : "ok") + "</td>"
                + "</tr>";
        }).join("");
        $("sensors").innerHTML = "<tr><th>Датчик</th><th>Тренд температуры</th><th>Тренд влажности</th>"
            + "<th>Сэмплов T / RH</th><th>Состояние</th></tr>" + rows;

        var buckets = data.buckets || [];
        var granularity = data.granularity || "month";
        var titles = { day: "История по дням", week: "История по неделям", month: "История по месяцам" };
        $("chartTitle").textContent = titles[granularity] || "История";

        var max = 0;
        buckets.forEach(function (b) { if (b.twpi > max) { max = b.twpi; } });
        var step = niceStep(Math.max(max, 10) / 3);
        var top = step * 3;

        $("yAxis").innerHTML = [3, 2, 1, 0].map(function (k) {
            var value = Math.round(step * k * 10) / 10;
            return "<span>" + String(value).replace(".", ",") + "</span>";
        }).join("");

        var showAll = buckets.length <= 14;
        var valueEvery = Math.max(1, Math.ceil(buckets.length / 12));
        var labelEvery = Math.max(1, Math.ceil(buckets.length / 12));

        $("months").innerHTML = buckets.map(function (b, i) {
            var has = b.twpi !== undefined && b.twpi !== null;
            var height = has ? Math.max(3, Math.round(b.twpi / top * 100)) : 2;
            var cls = "bar";
            if (has) { cls += b.twpi < 40 ? " low" : " has"; }
            var label = (i % labelEvery === 0) ? bucketLabel(b.from, granularity) : "";
            var value = (has && (showAll || i % valueEvery === 0))
                ? '<div class="val" style="bottom:calc(' + height + '% + 4px)">' + fmt(b.twpi) + "</div>"
                : "";
            return '<div class="month" title="' + esc(bucketTitle(b, granularity, has)) + '">'
                + value
                + '<div class="' + cls + '" style="height:' + height + '%"></div>'
                + '<div class="lbl">' + label + '</div>'
                + '</div>';
        }).join("");

        var unit = granularity === "day" ? "день" : (granularity === "week" ? "неделю" : "календарный месяц");
        $("chartHint").textContent = "Столбик — TWPI за " + unit
            + ", лет. Шкала слева — годы; точное значение и часы — в подсказке при наведении.";
    }

    function load() {
        var index = parseInt(queryParam("i"), 10);
        if (isNaN(index) || index < 0) {
            showError("Не указано помещение. Откройте его из списка на главной странице.");
            return;
        }
        var days = $("days").value;
        $("refresh").disabled = true;
        $("refresh").textContent = "Считаю…";

        fetch(apiBase() + "api/room?i=" + index + "&days=" + encodeURIComponent(days) + "&months=12",
              { headers: { "Accept": "application/json" }, cache: "no-store" })
            .then(function (response) {
                return response.text().then(function (text) {
                    var body;
                    try { body = JSON.parse(text); }
                    catch (e) { body = { ok: false, error: "Сервер вернул не JSON (HTTP " + response.status + ")" }; }
                    if (!body.ok) { throw new Error(body.error || ("HTTP " + response.status)); }
                    return body;
                });
            })
            .then(function (data) {
                $("error").style.display = "none";
                $("mainCard").style.display = "block";
                render(data);
            })
            .catch(function (e) { showError(e.message || String(e)); })
            .then(function () {
                $("refresh").disabled = false;
                $("refresh").textContent = "Обновить";
            });
    }

    $("refresh").addEventListener("click", load);
    $("days").addEventListener("change", load);
    load();
}());
</script>
</body>
</html>
