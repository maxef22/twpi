const fs = require("fs");
const html = fs.readFileSync("web/room.jsp", "utf8");
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];

const elements = {};
function el(id) {
    if (!elements[id]) {
        elements[id] = {
            id,
            innerHTML: "",
            textContent: "",
            style: {},
            disabled: false,
            classList: { add() {}, remove() {}, toggle() {} },
            addEventListener() {}
        };
    }
    return elements[id];
}

const now = Date.UTC(2026, 10, 26, 10, 0, 0);
const buckets = [];
for (let i = 29; i >= 0; i--) {
    const from = now - i * 86400000;
    buckets.push({
        from,
        to: from + 86400000,
        twpi: Math.round((30 + 15 * Math.sin(i / 3)) * 10) / 10,
        hours: 24,
        expectedHours: 24,
        coverage: 1,
        t: 20,
        rh: 50
    });
}

const payload = {
    ok: true,
    name: "Помещение 3",
    days: 30,
    from: now - 30 * 86400000,
    to: now,
    granularity: "day",
    twpi: 39.0,
    piLast: 41.6,
    hours: 711,
    expectedHours: 720,
    coverage: 0.99,
    t: 21.6,
    rh: 47.7,
    tMin: 20.8,
    tMax: 22.8,
    rhMin: 46.0,
    rhMax: 50.0,
    sensors: [],
    notes: [],
    buckets
};

global.document = {
    getElementById: el,
    createElement: () => ({ style: {}, classList: { add() {} }, appendChild() {}, addEventListener() {} })
};
global.window = { location: { pathname: "/twpi/room.jsp", search: "?i=0" } };
global.fetch = () => Promise.resolve({ text: () => Promise.resolve(JSON.stringify(payload)) });

eval(script);

setTimeout(() => {
    console.log("TITLE:", elements.chartTitle.textContent);
    console.log("YAXIS:", elements.yAxis.innerHTML);
    console.log("HINT :", elements.chartHint.textContent);
    const months = elements.months.innerHTML;
    console.log("BARS :", (months.match(/class="bar/g) || []).length);
    console.log("VALS :", (months.match(/class="val"/g) || []).length);
    console.log("LBL  :", (months.match(/class="lbl"/g) || []).length);
    console.log("SAMPLE:", months.slice(0, 420));
}, 50);
