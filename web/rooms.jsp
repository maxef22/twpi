<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="com.controlj.addon.twpi.RoomsStore" %>
<%@ page import="org.json.JSONObject" %>
<%!
    private static String js(String value) {
        return value.replace("\\", "\\\\").replace("</", "<\\/");
    }
%>
<%
    RoomsStore store = new RoomsStore(application);
    JSONObject settings = store.load();
    String configJson = js(settings.toString());
    String storagePath = store.path();
    boolean writable = store.isWritable();
%>
<!doctype html>
<html lang="ru">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>TWPI — помещения</title>
    <style>
        :root {
            --bg: #eef2f6; --card: #fff; --ink: #1c2b36; --muted: #6b7c8a;
            --line: #dbe3ea; --accent: #10689a; --accent-dark: #0b4f76;
        }
        * { box-sizing: border-box; }
        body { margin: 0; background: var(--bg); color: var(--ink); font: 15px/1.45 "Segoe UI", Roboto, Arial, sans-serif; }
        .wrap { max-width: 1000px; margin: 0 auto; padding: 26px 18px 60px; }
        h1 { font-size: 21px; margin: 0 0 4px; }
        .lead { color: var(--muted); font-size: 13.5px; margin: 0 0 18px; }
        .card { background: var(--card); border: 1px solid var(--line); border-radius: 14px; padding: 16px 18px; margin-bottom: 14px; box-shadow: 0 1px 2px rgba(28,43,54,.05); }
        .room { background: var(--card); border: 1px solid var(--line); border-left: 5px solid var(--accent); border-radius: 14px; padding: 14px 16px; margin-bottom: 12px; }
        .room-head { display: flex; gap: 10px; align-items: center; margin-bottom: 10px; }
        .badge {
            flex: 0 0 auto; padding: 5px 10px; border-radius: 8px; background: #e8f1f7;
            color: var(--accent-dark); font-size: 12px; font-weight: 700; white-space: nowrap;
        }
        .room-head input[type=text] { flex: 1; font-weight: 600; }
        input[type=text], textarea {
            width: 100%; padding: 8px 10px; border: 1px solid var(--line); border-radius: 8px;
            font: inherit; font-size: 13.5px; background: #fbfdfe;
        }
        input[type=text].addr { font-family: Consolas, "Cascadia Mono", monospace; font-size: 12.5px; }
        textarea { font-family: Consolas, "Cascadia Mono", monospace; font-size: 12.5px; resize: vertical; }
        input:focus, textarea:focus { outline: none; border-color: var(--accent); box-shadow: 0 0 0 3px rgba(16,104,154,.13); }
        .sensors-title { font-size: 11.5px; text-transform: uppercase; letter-spacing: .45px; color: var(--muted); margin: 4px 0 6px; }
        .sensor { display: grid; grid-template-columns: 150px 1fr 1fr 30px; gap: 8px; margin-bottom: 6px; align-items: center; }
        .sensor-head { font-size: 11.5px; text-transform: uppercase; letter-spacing: .4px; color: var(--muted); }
        button { padding: 8px 16px; border: none; border-radius: 8px; background: var(--accent); color: #fff; font: inherit; font-size: 13.5px; cursor: pointer; }
        button:hover { background: var(--accent-dark); }
        button.ghost { background: #eef3f7; color: var(--accent); }
        button.ghost:hover { background: #e2ebf2; }
        button.danger { background: #fdecea; color: #b03a2e; padding: 4px 9px; }
        .toolbar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin: 16px 0; }
        .status { font-size: 13.5px; }
        .ok { color: #1e8449; }
        .bad { color: #b03a2e; }
        .hint { color: var(--muted); font-size: 12.5px; margin-top: 10px; }
        code { font-family: Consolas, monospace; background: #eef3f7; padding: 1px 4px; border-radius: 4px; }
        a { color: var(--accent); }
        .empty { color: var(--muted); padding: 10px 0; }
        details summary { cursor: pointer; font-weight: 600; font-size: 14px; margin-bottom: 10px; }
        details[open] summary { margin-bottom: 12px; }
    </style>
</head>
<body>
<div class="wrap">
    <h1>Помещения</h1>
    <p class="lead">
        <b>Помещение</b> — это карточка с названием; внутри неё один или несколько <b>датчиков</b>
        (каждый — пара трендов: температура и влажность). Если нужно несколько помещений,
        нажимайте «+ Помещение», а не «+ датчик».
        Адреса удобно брать на странице <a href="tree.jsp">поиска по дереву</a>.
    </p>

    <details class="card">
        <summary>Быстрое заполнение по списку адресов</summary>
        <p class="lead" style="margin:0 0 10px">
            Вставьте адреса трендов по одному в строке. Адреса с общим родителем
            (<code>…/zone5/zone_temp</code> и <code>…/zone5/zone_humidity</code>)
            соберутся в одно помещение с именем папки.
        </p>
        <textarea id="bulk" rows="6" spellcheck="false"
placeholder="#counters/#zone1/zone_temp
#counters/#zone1/zone_humidity
#counters/#zone2/zone_temp
#counters/#zone2/zone_humidity"></textarea>
        <div class="toolbar" style="margin-bottom:0">
            <button type="button" class="ghost" id="bulkGo">Разобрать и добавить</button>
            <span class="status" id="bulkStatus"></span>
        </div>
    </details>

    <div id="rooms"></div>
    <div id="empty" class="card empty" style="display:none">Пока ни одного помещения — добавьте первое.</div>

    <div class="toolbar">
        <button type="button" class="ghost" id="addRoom">+ Помещение</button>
        <button type="button" id="save">Сохранить</button>
        <span class="status" id="status"></span>
    </div>

    <p class="hint">
        Настройки хранятся в файле <code><%= storagePath %></code><%= writable ? "" : " — каталог недоступен для записи!" %>.<br>
        <a href="./">← к расчёту</a>
    </p>
</div>

<script>
var TWPI_CONFIG = <%= configJson %>;

(function () {
    "use strict";

    var roomsHost = document.getElementById("rooms");
    var emptyHost = document.getElementById("empty");
    var statusHost = document.getElementById("status");

    function el(tag, className, text) {
        var node = document.createElement(tag);
        if (className) { node.className = className; }
        if (text) { node.textContent = text; }
        return node;
    }

    function input(value, placeholder, extraClass) {
        var node = document.createElement("input");
        node.type = "text";
        node.value = value || "";
        node.placeholder = placeholder || "";
        node.className = extraClass || "";
        return node;
    }

    function sensorRow(sensor) {
        var row = el("div", "sensor");
        var name = input(sensor.name, "Датчик");
        var t = input(sensor.t, "#…/zone_temp", "addr");
        var rh = input(sensor.rh, "#…/zone_humidity", "addr");
        var remove = el("button", "danger", "×");
        remove.type = "button";
        remove.title = "Удалить датчик";
        remove.addEventListener("click", function () {
            row.parentNode.removeChild(row);
        });
        row.appendChild(name);
        row.appendChild(t);
        row.appendChild(rh);
        row.appendChild(remove);
        row._read = function () {
            return { name: name.value.trim(), t: t.value.trim(), rh: rh.value.trim() };
        };
        return row;
    }

    function roomBlock(room) {
        var block = el("div", "room");

        var head = el("div", "room-head");
        var badge = el("div", "badge", "Помещение");
        var name = input(room.name, "Название помещения, например «Зал 5»");
        var remove = el("button", "danger", "Удалить");
        remove.type = "button";
        remove.title = "Удалить помещение";
        remove.addEventListener("click", function () {
            block.parentNode.removeChild(block);
            renumber();
            refreshEmpty();
        });
        head.appendChild(badge);
        head.appendChild(name);
        head.appendChild(remove);
        block.appendChild(head);

        block.appendChild(el("div", "sensors-title", "Датчики в этом помещении"));

        var header = el("div", "sensor");
        header.appendChild(el("div", "sensor-head", "Датчик"));
        header.appendChild(el("div", "sensor-head", "Тренд температуры"));
        header.appendChild(el("div", "sensor-head", "Тренд влажности"));
        header.appendChild(el("div"));
        block.appendChild(header);

        var sensors = el("div", "sensors");
        (room.sensors || []).forEach(function (sensor) {
            sensors.appendChild(sensorRow(sensor));
        });
        block.appendChild(sensors);

        var addSensor = el("button", "ghost", "+ датчик в это помещение");
        addSensor.type = "button";
        addSensor.style.marginTop = "6px";
        addSensor.addEventListener("click", function () {
            sensors.appendChild(sensorRow({}));
        });
        block.appendChild(addSensor);

        block._read = function () {
            var list = [];
            var rows = sensors.getElementsByClassName("sensor");
            for (var i = 0; i < rows.length; i++) {
                list.push(rows[i]._read());
            }
            return { name: name.value.trim(), sensors: list };
        };
        return block;
    }

    function renumber() {
        var blocks = roomsHost.children;
        for (var i = 0; i < blocks.length; i++) {
            var badge = blocks[i].getElementsByClassName("badge")[0];
            if (badge) { badge.textContent = "Помещение " + (i + 1); }
        }
    }

    function refreshEmpty() {
        emptyHost.style.display = roomsHost.children.length ? "none" : "block";
    }

    function addRoom(room) {
        roomsHost.appendChild(roomBlock(room || { name: "", sensors: [{}] }));
        renumber();
        refreshEmpty();
    }

    function render(config) {
        roomsHost.innerHTML = "";
        (config.rooms || []).forEach(function (room) {
            roomsHost.appendChild(roomBlock(room));
        });
        renumber();
        refreshEmpty();
    }

    function setStatus(text, ok) {
        statusHost.textContent = text;
        statusHost.className = "status " + (ok ? "ok" : "bad");
    }

    function collect() {
        var rooms = [];
        var blocks = roomsHost.children;
        for (var i = 0; i < blocks.length; i++) {
            rooms.push(blocks[i]._read());
        }
        return { rooms: rooms, period: TWPI_CONFIG.period || 30 };
    }

    /* Быстрое заполнение: адреса группируются по родительской папке. */
    function bulkAdd() {
        var text = document.getElementById("bulk").value;
        var status = document.getElementById("bulkStatus");
        var lines = text.split(/\r?\n/);
        var groups = {};
        var order = [];
        var skipped = 0;

        lines.forEach(function (line) {
            var address = line.trim();
            if (!address) { return; }
            address = address.replace(/^ABSPATH:\d+:/i, "");
            var slash = address.lastIndexOf("/");
            if (slash <= 0) { skipped++; return; }
            var parent = address.substring(0, slash);
            var leaf = address.substring(slash + 1).toLowerCase();
            if (!groups[parent]) { groups[parent] = {}; order.push(parent); }
            var group = groups[parent];
            if (/temp|темп/.test(leaf)) {
                group.t = address;
            } else if (/humid|rh|влаж/.test(leaf)) {
                group.rh = address;
            } else if (!group.t) {
                group.t = address;
            } else {
                group.rh = address;
            }
        });

        var added = 0;
        order.forEach(function (parent) {
            var group = groups[parent];
            var title = parent.substring(parent.lastIndexOf("/") + 1).replace(/^#/, "");
            addRoom({
                name: title || parent,
                sensors: [{ name: "Датчик 1", t: group.t || "", rh: group.rh || "" }]
            });
            added++;
        });

        status.textContent = added
            ? ("Добавлено помещений: " + added + ". Не забудьте сохранить.")
            : "Не нашёл ни одного адреса.";
        status.className = "status " + (added ? "ok" : "bad");
        if (skipped) { status.textContent += " Пропущено строк: " + skipped + "."; }
    }

    document.getElementById("addRoom").addEventListener("click", function () { addRoom(); });
    document.getElementById("bulkGo").addEventListener("click", bulkAdd);

    document.getElementById("save").addEventListener("click", function () {
        var body = JSON.stringify(collect());
        setStatus("Сохраняю…", true);
        fetch("api/rooms", {
            method: "POST",
            headers: { "Content-Type": "application/json; charset=UTF-8" },
            body: body
        }).then(function (response) {
            return response.text().then(function (text) {
                var data;
                try { data = JSON.parse(text); } catch (e) { data = { ok: false, error: "HTTP " + response.status }; }
                if (!data.ok) { throw new Error(data.error || ("HTTP " + response.status)); }
                setStatus("Сохранено помещений: " + data.saved + ". Файл: " + data.storage, true);
            });
        }).catch(function (e) {
            setStatus("Ошибка: " + (e.message || e), false);
        });
    });

    render(TWPI_CONFIG);
}());
</script>
</body>
</html>
