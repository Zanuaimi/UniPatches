const SHAPES = [
  "triangle",
  "roundedTriangle",
  "circle",
  "ring",
  "square",
  "roundedRect",
  "v",
  "line",
  "arc",
  "diamond",
  "star",
  "heart",
  "text",
];
const FILLS = ["solid", "gradient"];
const FONTS = [
  "default",
  "roboto",
  "sansSerif",
  "serif",
  "monospace",
  "sansCondensed",
  "sansMedium",
  "sansBlack",
];
const FONT_LABELS = {
  default: "Default (Android)",
  roboto: "Roboto",
  sansSerif: "Sans serif",
  serif: "Serif",
  monospace: "Monospace",
  sansCondensed: "Sans condensed",
  sansMedium: "Sans medium",
  sansBlack: "Sans black",
};
const MAX_PARTS = 12;
const PREVIEW_CANVAS_SIZE = 420;
const PREVIEW_BODY_SIZE = 376;

const templates = {
  "single-triangle": {
    label: "Blue triangle",
    note: "clean single mark",
    parts: ["triangle|50|48|55|55|0|solid|#4E97F0|#4E97F0|0|100|0"],
    appearance: {
      background: "#20252B",
      background2: "#35414C",
      outline: "#78C8FF",
    },
  },
  lucky: {
    label: "Lucky smile",
    note: "five-part glossy smile",
    parts: [
      "circle|35|35|10|16|5|solid|#000000|#000000|0|100|0",
      "circle|65|35|10|16|-5|solid|#000000|#000000|0|100|0",
      "arc|50|57|50|40|0|solid|#000000|#000000|10|100|1",
      "circle|27|62|8|4|-10|solid|#000000|#000000|0|100|2",
      "circle|73|62|8|4|10|solid|#000000|#000000|0|100|2",
    ],
    appearance: {
      background: "#C6B407",
      background2: "#F0E60A",
      backgroundAngle: 90,
      outline: "#000000",
      outlineWidth: 2,
      outline2: "#E651A0",
      outlineGradient: false,
      highlight: true,
    },
  },
  revanched: {
    label: "ReVanced mark",
    note: "bold V + gradient triangle",
    parts: [
      "v|50|54|57|54|0|solid|#FFFFFF|#FFFFFF|16|100|0",
      "roundedTriangle|50|45|25|26|0|gradient|#E651A0|#6564D3|0|100|1",
    ],
    appearance: {
      background: "#1B1B1D",
      background2: "#1B1B1D",
      outline: "#EF4E99",
      outline2: "#4E97F0",
      outlineGradient: true,
      outlineAngle: 90,
      outlineWidth: 3,
    },
  },
  "z-mark": {
    label: "ZA monogram",
    note: "faceted two-letter mark",
    parts: [
      "text|41|50|45|70|0|solid|#FFFFFF|#FFFFFF|0|100|0|Z|true|default",
      "text|59|50|45|70|0|solid|#FFFFFF|#FFFFFF|0|100|1|A|true|default",
    ],
    appearance: {
      background: "#5BAA08",
      background2: "#5BAA08",
      background3: "#3D7806",
      background4: "#69B90A",
      outline: "#FFFFFF",
      outlineWidth: 0,
      style: "faceted",
      buttonShape: "squircle",
    },
  },
  heart: {
    label: "Heart",
    note: "simple solid shape",
    parts: ["heart|50|48|60|50|0|gradient|#FF6B77|#D62D67|0|100|0"],
    appearance: {
      background: "#241B24",
      background2: "#3A2534",
      outline: "#FF9CA6",
    },
  },
};

const state = {
  parts: [],
  background: "#20252B",
  background2: "#35414C",
  background3: "#3D7806",
  background4: "#69B90A",
  backgroundAngle: 0,
  backgroundGradient: true,
  backgroundStyle: "flat",
  outlineWidth: 2,
  outline: "#78C8FF",
  outlineGradient: false,
  outline2: "#E651A0",
  outlineAngle: 0,
  highlight: false,
  legacyText: "U",
  legacyTextSize: 18,
  legacyBold: true,
  legacyTextFont: "default",
  menuTextFont: "default",
  legacyTextColor: "#FFFFFF",
  iconMode: "parts",
  legacyShape: "triangle",
  legacyShapeColor1: "#FFFFFF",
  legacyShapeColor2: "#FFFFFF",
  legacyShapeGradient: false,
  legacyShapeAngle: 0,
  legacyShapeStroke: 3,
  legacyShapeScale: 70,
  iconSize: 56,
  iconOpacity: 55,
  dragVisibility: 2,
  iconPosition: "topRight",
  buttonShape: "circle",
  image: null,
};
const defaultState = { ...state };

const $ = (id) => document.getElementById(id);
const canvas = $("iconCanvas");
const ctx = canvas.getContext("2d");

function clamp(value, fallback, min, max) {
  const parsed = Number.parseFloat(value);
  return Number.isFinite(parsed)
    ? Math.max(min, Math.min(max, parsed))
    : fallback;
}

function clampInt(value, fallback, min, max) {
  return Math.round(clamp(value, fallback, min, max));
}

function normalizeAngle(value) {
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return 0;
  return ((numeric % 360) + 360) % 360;
}

function normalizeHex(value, fallback = "#FFFFFF") {
  const raw = String(value || "").trim();
  if (/^#[0-9a-f]{6}$/i.test(raw)) return raw.toUpperCase();
  if (/^[0-9a-f]{6}$/i.test(raw)) return `#${raw.toUpperCase()}`;
  return fallback.toUpperCase();
}

function resetEditorState(overrides = {}) {
  Object.assign(state, defaultState, overrides);
  state.parts = [];
  state.image = null;
}

function safeColor(value, fallback = "#FFFFFF") {
  const normalized = normalizeHex(value, fallback);
  return normalized;
}

function defaultPart(shape = "triangle") {
  return {
    shape,
    x: 50,
    y: 50,
    width: 55,
    height: 55,
    rotation: 0,
    fill: "solid",
    color1: "#FFFFFF",
    color2: "#FFFFFF",
    stroke: 3,
    opacity: 100,
    layer: state.parts.length,
    text: shape === "text" ? "U" : "",
    bold: shape === "text",
    font: "default",
  };
}

function parseRow(row) {
  const fields = String(row || "").split("|");
  if (fields.length < 12 || fields.length > 15)
    return { valid: false, error: "Expected 12–15 fields", raw: row };
  const rawShape = fields[0].trim();
  const legacyChevron = rawShape === "chevron";
  const shape = legacyChevron ? "text" : rawShape;
  if (!SHAPES.includes(shape))
    return {
      valid: false,
      error: `Unsupported shape: ${shape || "empty"}`,
      raw: row,
    };
  const part = {
    shape,
    x: clamp(fields[1], 50, 0, 100),
    y: clamp(fields[2], 50, 0, 100),
    width: clamp(fields[3], 50, 1, 100),
    height: clamp(fields[4], 50, 1, 100),
    rotation: clamp(fields[5], 0, -360, 360),
    fill: FILLS.includes(fields[6].trim()) ? fields[6].trim() : "solid",
    color1: safeColor(fields[7], "#FFFFFF"),
    color2: safeColor(fields[8], safeColor(fields[7], "#FFFFFF")),
    stroke: clamp(fields[9], 3, 0, 32),
    opacity: clamp(fields[10], 100, 0, 100),
    layer: clampInt(fields[11], 0, -32, 32),
    text: legacyChevron
      ? "V"
      : fields.length >= 13
        ? fields[12].trim().replaceAll("|", " ").slice(0, 3)
        : "",
    bold:
      legacyChevron ||
      (fields.length >= 14 && fields[13].trim().toLowerCase() === "true"),
    font: FONTS.includes(fields[14]?.trim()) ? fields[14].trim() : "default",
  };
  if (shape === "text" && !part.text) part.text = "?";
  return { valid: true, part };
}

function serializePart(part) {
  const text =
    part.shape === "text" ? `|${String(part.text || "?").slice(0, 3)}` : "";
  const bold = part.shape === "text" ? `|${part.bold ? "true" : "false"}` : "";
  const font =
    part.shape === "text"
      ? `|${FONTS.includes(part.font) ? part.font : "default"}`
      : "";
  return (
    [
      part.shape,
      trimNumber(part.x),
      trimNumber(part.y),
      trimNumber(part.width),
      trimNumber(part.height),
      trimNumber(part.rotation),
      part.fill,
      normalizeHex(part.color1),
      normalizeHex(part.color2),
      trimNumber(part.stroke),
      trimNumber(part.opacity),
      trimNumber(part.layer),
    ].join("|") +
    text +
    bold +
    font
  );
}

function trimNumber(value) {
  const numeric = Number(value);
  return Number.isInteger(numeric)
    ? String(numeric)
    : numeric.toFixed(2).replace(/0+$/, "").replace(/\.$/, "");
}

function rowsFromParts(parts = state.parts) {
  return parts.map(serializePart).join("\n");
}

function partsFromRows(text) {
  const rows = String(text || "")
    .split(/\r?\n/)
    .map((row) => row.trim())
    .filter(Boolean);
  return rows.slice(0, MAX_PARTS).map(parseRow);
}

function drawGradient(area, color1, color2, angle) {
  const radians = ((Number(angle) || 0) * Math.PI) / 180;
  const dx = (Math.cos(radians) * area.width) / 2;
  const dy = (Math.sin(radians) * area.height) / 2;
  const gradient = ctx.createLinearGradient(
    area.centerX - dx,
    area.centerY - dy,
    area.centerX + dx,
    area.centerY + dy,
  );
  gradient.addColorStop(0, color1);
  gradient.addColorStop(1, color2);
  return gradient;
}

function roundedRectPath(x, y, width, height, radius) {
  const path = new Path2D();
  path.roundRect(x, y, width, height, radius);
  return path;
}

function drawShape(part, area, body) {
  const width = area.width;
  const height = area.height;
  const centerX = area.centerX;
  const centerY = area.centerY;
  const stroke = Math.max(
    1,
    (part.stroke * Math.min(body.width, body.height)) / PREVIEW_BODY_SIZE,
  );
  ctx.lineCap = "round";
  ctx.lineJoin = "round";
  ctx.fillStyle =
    part.fill === "gradient"
      ? drawGradient(area, part.color1, part.color2, part.rotation)
      : part.color1;
  ctx.strokeStyle = ctx.fillStyle;
  ctx.lineWidth = stroke;

  if (part.shape === "circle") {
    ctx.beginPath();
    ctx.ellipse(centerX, centerY, width / 2, height / 2, 0, 0, Math.PI * 2);
    ctx.fill();
  } else if (part.shape === "ring") {
    ctx.beginPath();
    ctx.ellipse(centerX, centerY, width / 2, height / 2, 0, 0, Math.PI * 2);
    ctx.stroke();
  } else if (part.shape === "square" || part.shape === "roundedRect") {
    const path = roundedRectPath(
      area.left,
      area.top,
      width,
      height,
      part.shape === "roundedRect" ? Math.min(width, height) * 0.18 : 0,
    );
    ctx.fill(path);
  } else if (part.shape === "v") {
    ctx.beginPath();
    ctx.moveTo(area.left + width * 0.22, area.top + height * 0.18);
    ctx.lineTo(centerX, area.bottom - height * 0.18);
    ctx.lineTo(area.right - width * 0.22, area.top + height * 0.18);
    ctx.stroke();
  } else if (part.shape === "roundedTriangle") {
    const radius = Math.min(width, height) * 0.14;
    ctx.beginPath();
    ctx.moveTo(area.left + radius, area.top);
    ctx.lineTo(area.right - radius, area.top);
    ctx.quadraticCurveTo(
      area.right,
      area.top,
      area.right - radius * 0.42,
      area.top + radius * 0.62,
    );
    ctx.lineTo(centerX + radius * 0.42, area.bottom - radius * 1.18);
    ctx.quadraticCurveTo(
      centerX,
      area.bottom,
      centerX - radius * 0.42,
      area.bottom - radius * 1.18,
    );
    ctx.lineTo(area.left + radius * 0.42, area.top + radius * 0.62);
    ctx.quadraticCurveTo(area.left, area.top, area.left + radius, area.top);
    ctx.closePath();
    ctx.fill();
  } else if (part.shape === "line") {
    ctx.beginPath();
    ctx.moveTo(area.left, centerY);
    ctx.lineTo(area.right, centerY);
    ctx.stroke();
  } else if (part.shape === "arc") {
    ctx.beginPath();
    ctx.ellipse(
      centerX,
      centerY,
      width / 2,
      height / 2,
      0,
      (20 * Math.PI) / 180,
      (160 * Math.PI) / 180,
    );
    ctx.stroke();
  } else if (part.shape === "text") {
    const text = String(part.text || "?").slice(0, 3);
    const family =
      {
        default: "Arial",
        roboto: "Roboto, Arial",
        sansSerif: "Arial",
        serif: "Georgia",
        monospace: "monospace",
        sansCondensed: "Arial Narrow, Arial",
        sansMedium: "Arial",
        sansBlack: "Arial",
      }[part.font] || "Arial";
    const weight =
      part.font === "sansBlack"
        ? "900"
        : part.font === "sansMedium"
          ? "500"
          : part.bold
            ? "700"
            : "400";
    ctx.font = `${weight} ${Math.max(1, Math.min(width / Math.max(1, text.length), height) * 0.82)}px ${family}`;
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    ctx.fillText(text, centerX, centerY);
    ctx.textAlign = "start";
    ctx.textBaseline = "alphabetic";
  } else if (part.shape === "diamond") {
    polygon(
      [
        [centerX, area.top],
        [area.right, centerY],
        [centerX, area.bottom],
        [area.left, centerY],
      ],
      true,
    );
  } else if (part.shape === "star") {
    const points = [];
    for (let i = 0; i < 10; i += 1) {
      const angle = -Math.PI / 2 + (i * Math.PI) / 5;
      const radius =
        i % 2 === 0
          ? Math.min(width, height) / 2
          : Math.min(width, height) * 0.2;
      points.push([
        centerX + Math.cos(angle) * radius,
        centerY + Math.sin(angle) * radius,
      ]);
    }
    polygon(points, true);
  } else if (part.shape === "heart") {
    ctx.beginPath();
    ctx.moveTo(centerX, area.bottom);
    ctx.bezierCurveTo(
      area.left - width * 0.05,
      area.top + height * 0.48,
      area.left + width * 0.16,
      area.top,
      centerX,
      area.top + height * 0.28,
    );
    ctx.bezierCurveTo(
      area.right - width * 0.16,
      area.top,
      area.right + width * 0.05,
      area.top + height * 0.48,
      centerX,
      area.bottom,
    );
    ctx.closePath();
    ctx.fill();
  } else {
    polygon(
      [
        [centerX, area.top],
        [area.right, area.bottom],
        [area.left, area.bottom],
      ],
      true,
    );
  }
}

function polygon(points, close) {
  ctx.beginPath();
  ctx.moveTo(points[0][0], points[0][1]);
  points.slice(1).forEach(([x, y]) => ctx.lineTo(x, y));
  if (close) ctx.closePath();
  ctx.fill();
}

function drawPreview() {
  const buttonSize = clampInt(state.iconSize, 56, 32, 128);
  const size = buttonSize;
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  ctx.save();
  ctx.scale(PREVIEW_CANVAS_SIZE / buttonSize, PREVIEW_CANVAS_SIZE / buttonSize);
  const body = {
    left: 0,
    top: 0,
    width: size,
    height: size,
    centerX: size / 2,
    centerY: size / 2,
    right: size,
    bottom: size,
  };
  const radius = Math.min(body.width, body.height) / 2;
  ctx.save();
  ctx.fillStyle = state.backgroundGradient
    ? drawGradient(
        body,
        state.background,
        state.background2,
        state.backgroundAngle,
      )
    : state.background;
  const bodyRadius =
    state.buttonShape === "circle"
      ? radius
      : state.buttonShape === "squircle"
        ? 24
        : 0;
  ctx.beginPath();
  ctx.roundRect(body.left, body.top, body.width, body.height, bodyRadius);
  ctx.fill();
  if (state.backgroundStyle === "faceted") drawFacets(body, bodyRadius);
  if (state.outlineWidth > 0) {
    ctx.strokeStyle = state.outlineGradient
      ? drawGradient(body, state.outline, state.outline2, state.outlineAngle)
      : state.outline;
    ctx.lineWidth = state.outlineWidth;
    ctx.beginPath();
    ctx.roundRect(
      body.left + state.outlineWidth / 2,
      body.top + state.outlineWidth / 2,
      body.width - state.outlineWidth,
      body.height - state.outlineWidth,
      bodyRadius,
    );
    ctx.stroke();
  }
  if (state.iconMode === "image" && state.image) {
    const imageSize = Math.max(1, Math.min(body.width, body.height) - 8);
    ctx.drawImage(
      state.image,
      body.centerX - imageSize / 2,
      body.centerY - imageSize / 2,
      imageSize,
      imageSize,
    );
    ctx.restore();
    return;
  }
  const sorted =
    state.iconMode === "parts"
      ? [...state.parts].sort((a, b) => a.layer - b.layer)
      : [];
  if (state.iconMode === "text") {
    sorted.length = 0;
  }
  sorted.forEach((part) => {
    const width = (body.width * part.width) / 100;
    const height = (body.height * part.height) / 100;
    const area = {
      left: body.left + (body.width * part.x) / 100 - width / 2,
      top: body.top + (body.height * part.y) / 100 - height / 2,
      width,
      height,
    };
    area.right = area.left + width;
    area.bottom = area.top + height;
    area.centerX = area.left + width / 2;
    area.centerY = area.top + height / 2;
    ctx.save();
    ctx.globalAlpha = part.opacity / 100;
    ctx.translate(area.centerX, area.centerY);
    ctx.rotate((part.rotation * Math.PI) / 180);
    ctx.translate(-area.centerX, -area.centerY);
    drawShape(part, area, body);
    ctx.restore();
  });
  if (state.iconMode === "text") {
    ctx.save();
    ctx.fillStyle = state.legacyTextColor;
    ctx.font = `${state.legacyBold ? "700" : "400"} ${Math.max(1, state.legacyTextSize)}px ${
      {
        default: "Arial",
        roboto: "Roboto, Arial",
        sansSerif: "Arial",
        serif: "Georgia",
        monospace: "monospace",
        sansCondensed: "Arial Narrow, Arial",
        sansMedium: "Arial",
        sansBlack: "Arial",
      }[state.legacyTextFont] || "Arial"
    }`;
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    ctx.fillText(
      String(state.legacyText || "?").slice(0, 3),
      body.centerX,
      body.centerY,
    );
    ctx.restore();
  }
  if (state.highlight) {
    ctx.globalAlpha = 0.33;
    ctx.fillStyle = "#FFFFFF";
    ctx.beginPath();
    ctx.ellipse(
      body.left + body.width * 0.4,
      body.top + body.height * 0.225,
      body.width * 0.22,
      body.height * 0.105,
      0,
      0,
      Math.PI * 2,
    );
    ctx.fill();
  }
  ctx.restore();
  ctx.restore();
}

function drawFacets(body, radius) {
  ctx.save();
  ctx.beginPath();
  ctx.roundRect(body.left, body.top, body.width, body.height, radius);
  ctx.clip();
  const cx = body.centerX;
  const cy = body.centerY;
  ctx.fillStyle = state.background3;
  ctx.beginPath();
  ctx.moveTo(body.left, body.top);
  ctx.lineTo(cx, body.top);
  ctx.lineTo(body.left, cy);
  ctx.closePath();
  ctx.fill();
  ctx.fillStyle = state.background4;
  ctx.beginPath();
  ctx.moveTo(cx, body.top);
  ctx.lineTo(body.right, body.top);
  ctx.lineTo(body.right, body.top + body.height * 0.35);
  ctx.lineTo(cx, cy);
  ctx.closePath();
  ctx.fill();
  ctx.fillStyle = state.background2;
  ctx.beginPath();
  ctx.moveTo(body.left, cy);
  ctx.lineTo(cx, cy);
  ctx.lineTo(body.left, body.bottom);
  ctx.closePath();
  ctx.fill();
  ctx.fillStyle = state.background3;
  ctx.beginPath();
  ctx.moveTo(cx, cy);
  ctx.lineTo(body.right, body.top + body.height * 0.35);
  ctx.lineTo(body.right, body.bottom);
  ctx.lineTo(cx, body.bottom);
  ctx.closePath();
  ctx.fill();
  ctx.fillStyle = state.background4;
  ctx.beginPath();
  ctx.moveTo(cx, cy);
  ctx.lineTo(cx, body.bottom);
  ctx.lineTo(body.left + body.width * 0.42, body.bottom);
  ctx.closePath();
  ctx.fill();
  ctx.restore();
}

function renderPartRows() {
  const list = $("partsList");
  list.innerHTML = "";
  $("emptyState").hidden = state.parts.length > 0;
  state.parts.forEach((part, index) => {
    const row = document.createElement("div");
    row.className = "part-row";
    row.dataset.index = index;
    row.innerHTML = `<div class="part-main"><div class="part-number">${index + 1}</div>
      ${selectField("shape", "Shape", SHAPES, part.shape)}
      ${numberField("x", "Position X", part.x, 0, 100)}${numberField("y", "Position Y", part.y, 0, 100)}
      ${numberField("width", "Scale W", part.width, 1, 100)}${numberField("height", "Scale H", part.height, 1, 100)}
      ${selectField("fill", "Fill", FILLS, part.fill)}
      <button class="remove-part" type="button" title="Remove part">×</button></div>`;
    const optional = document.createElement("div");
    optional.className = "part-advanced";
    optional.innerHTML = `
      ${numberField("rotation", "Rotation", part.rotation, -360, 360)}${numberField("stroke", "Stroke", part.stroke, 0, 32)}${numberField("opacity", "Opacity", part.opacity, 0, 100)}${numberField("layer", "Layer", part.layer, -32, 32)}
      ${colorField("color1", "Color 1", part.color1)}${colorField("color2", "Color 2", part.color2)}
      <label class="part-field">TEXT<input data-key="text" type="text" maxlength="3" value="${escapeHtml(part.text)}"></label>
      <label class="part-field">BOLD<input data-key="bold" type="checkbox" ${part.bold ? "checked" : ""}></label>
      <label class="part-field">FONT<select data-key="font">${FONTS.map((font) => `<option value="${font}" ${font === part.font ? "selected" : ""}>${FONT_LABELS[font]}</option>`).join("")}</select></label>`;
    row.append(optional);
    row
      .querySelectorAll("input, select")
      .forEach((input) =>
        input.addEventListener("input", () => updatePart(index, input)),
      );
    row.querySelector(".remove-part").addEventListener("click", () => {
      state.parts.splice(index, 1);
      renderAll();
    });
    list.append(row);
  });
}

function numberField(key, label, value, min, max) {
  return `<label class="part-field">${label}<input data-key="${key}" type="number" min="${min}" max="${max}" step="any" value="${value}"></label>`;
}
function selectField(key, label, options, value) {
  return `<label class="part-field">${label}<select data-key="${key}">${options.map((option) => `<option value="${option}" ${option === value ? "selected" : ""}>${option}</option>`).join("")}</select></label>`;
}
function colorField(key, label, value) {
  return `<label class="part-field">${label}<input data-key="${key}" type="color" value="${normalizeHex(value)}"></label>`;
}
function escapeHtml(value) {
  return String(value || "").replace(
    /[&<>"']/g,
    (char) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        char
      ],
  );
}

function updatePart(index, input) {
  const key = input.dataset.key;
  const part = state.parts[index];
  if (key === "shape" || key === "fill" || key === "text" || key === "font")
    part[key] = input.value;
  else if (key === "bold") part[key] = input.checked;
  else if (key === "color1" || key === "color2")
    part[key] = normalizeHex(input.value);
  else
    part[key] =
      key === "layer"
        ? clampInt(input.value, part[key], -32, 32)
        : clamp(
            input.value,
            part[key],
            key === "rotation" ? -360 : 0,
            key === "width" || key === "height"
              ? 100
              : key === "stroke"
                ? 32
                : 100,
          );
  if (part.shape === "text" && !part.text) part.text = "?";
  renderAll(false);
}

function bindValue(id, key, type = "text") {
  const input = $(id);
  const update = () => {
    state[key] =
      type === "number"
        ? Number(input.value)
        : type === "color"
          ? normalizeHex(input.value)
          : input.value;
    syncInputs();
    drawPreview();
    updateStatus("Edited locally");
  };
  input.addEventListener("input", update);
  input.addEventListener("change", update);
}

function bindCheckbox(id, key) {
  $(id).addEventListener("change", () => {
    state[key] = $(id).checked;
    drawPreview();
    updateStatus("Edited locally");
  });
}

function bindColorPair(colorId, textId, key) {
  $(colorId).addEventListener("input", () => {
    state[key] = normalizeHex($(colorId).value);
    $(textId).value = state[key];
    drawPreview();
  });
  $(textId).addEventListener("input", () => {
    state[key] = normalizeHex($(textId).value, state[key]);
    $(colorId).value = state[key];
    drawPreview();
  });
}

function syncInputs() {
  const map = {
    backgroundColor: state.background,
    backgroundColor2: state.background2,
    outlineColor: state.outline,
    outlineColor2: state.outline2,
    legacyTextColor: state.legacyTextColor,
  };
  Object.entries(map).forEach(([id, value]) => {
    $(id).value = value;
    $(`${id}Text`).value = value;
  });
  $("backgroundAngle").value = state.backgroundAngle;
  $("outlineAngle").value = state.outlineAngle;
  $("outlineWidth").value = state.outlineWidth;
  $("backgroundGradient").checked = state.backgroundGradient;
  $("backgroundStyle").value = state.backgroundStyle;
  $("outlineGradient").checked = state.outlineGradient;
  $("highlight").checked = state.highlight;
  $("legacyText").value = state.legacyText;
  $("legacyTextSize").value = state.legacyTextSize;
  $("legacyBold").checked = state.legacyBold;
  $("legacyTextFont").value = state.legacyTextFont;
  $("menuTextFont").value = state.menuTextFont;
  $("legacyShape").value = state.legacyShape;
  $("iconMode").value = state.iconMode;
  $("iconSize").value = state.iconSize;
  $("iconOpacity").value = state.iconOpacity;
  $("dragVisibility").value = state.dragVisibility;
  $("iconPosition").value = state.iconPosition;
  $("buttonShape").value = state.buttonShape;
  $("legacyShapeGradient").checked = state.legacyShapeGradient;
  $("legacyShapeAngle").value = state.legacyShapeAngle;
  $("legacyShapeStroke").value = state.legacyShapeStroke;
  $("legacyShapeScale").value = state.legacyShapeScale;
  ["legacyShapeColor1", "legacyShapeColor2"].forEach((id) => {
    $(id).value = state[id];
    $(`${id}Text`).value = state[id];
  });
}

function renderAll(rebuildRows = true) {
  if (rebuildRows) renderPartRows();
  $("rawRows").value = rowsFromParts();
  $("partCount").textContent =
    `${state.parts.length} part${state.parts.length === 1 ? "" : "s"}`;
  drawPreview();
}

function updateStatus(message) {
  $("toolbarStatus").textContent = message;
}

function addPart(shape = "triangle") {
  if (state.parts.length < MAX_PARTS) {
    state.parts.push(defaultPart(shape));
    renderAll();
    updateStatus("Part added");
  }
}

function applyRows(text) {
  const parsed = partsFromRows(text);
  const valid = parsed
    .filter((result) => result.valid)
    .map((result) => result.part);
  const invalid = parsed.filter((result) => !result.valid);
  state.parts = valid;
  $("invalidCount").textContent = `${invalid.length} invalid`;
  renderAll();
  updateStatus(
    invalid.length
      ? `${invalid.length} invalid row${invalid.length === 1 ? "" : "s"} skipped`
      : "Rows applied",
  );
}

function loadTemplate(template) {
  resetEditorState();
  state.parts = template.parts.map((row) => parseRow(row).part).filter(Boolean);
  Object.assign(state, {
    backgroundAngle: 0,
    backgroundGradient: true,
    backgroundStyle: "flat",
    outlineWidth: 2,
    outlineGradient: false,
    outline2: "#E651A0",
    outlineAngle: 0,
    highlight: false,
    ...template.appearance,
    background: template.appearance.background || state.background,
    background2: template.appearance.background2 || state.background2,
    background3: template.appearance.background3 || state.background3,
    background4: template.appearance.background4 || state.background4,
    outline: template.appearance.outline || state.outline,
    outline2: template.appearance.outline2 || state.outline2,
    backgroundStyle: template.appearance.style || "flat",
  });
  syncInputs();
  renderAll();
  updateStatus(`${template.label} loaded`);
}

const importedEnums = {
  iconMode: ["parts", "text", "image"],
  backgroundStyle: ["flat", "faceted"],
  legacyTextFont: FONTS,
  menuTextFont: FONTS,
  legacyShape: [
    "triangle",
    "roundedTriangle",
    "circle",
    "square",
    "roundedRect",
    "diamond",
    "star",
    "heart",
  ],
  iconPosition: [
    "topLeft",
    "topMiddle",
    "topRight",
    "centerLeft",
    "centerRight",
    "bottomLeft",
    "bottomMiddle",
    "bottomRight",
  ],
  buttonShape: ["circle", "squircle", "square"],
};
const importedRanges = {
  backgroundAngle: [-360, 360],
  outlineAngle: [-360, 360],
  legacyShapeAngle: [-360, 360],
  outlineWidth: [0, 12],
  legacyTextSize: [8, 48],
  legacyShapeStroke: [0, 32],
  legacyShapeScale: [1, 100],
  iconSize: [32, 128],
  iconOpacity: [10, 100],
  dragVisibility: [0, 60],
};
const importedColors = new Set([
  "background",
  "background2",
  "background3",
  "background4",
  "outline",
  "outline2",
  "legacyTextColor",
  "legacyShapeColor1",
  "legacyShapeColor2",
]);

function applyImportedSettings(settings) {
  const rejected = [];
  Object.entries(settings || {}).forEach(([key, value]) => {
    if (!(key in state) || key === "parts" || key === "image") return;
    if (importedColors.has(key)) {
      const normalized = normalizeHex(value, "");
      if (!normalized) rejected.push(key);
      else state[key] = normalized;
      return;
    }
    if (importedEnums[key]) {
      if (importedEnums[key].includes(value)) state[key] = value;
      else rejected.push(key);
      return;
    }
    if (typeof state[key] === "boolean") {
      if (typeof value === "boolean") state[key] = value;
      else rejected.push(key);
      return;
    }
    if (typeof state[key] === "number") {
      const numeric = Number(value);
      const range = importedRanges[key] || [-Infinity, Infinity];
      if (Number.isFinite(numeric))
        state[key] = Math.max(range[0], Math.min(range[1], numeric));
      else rejected.push(key);
      return;
    }
    if (typeof state[key] === "string") state[key] = String(value);
  });
  return rejected;
}

function importedPart(value) {
  if (!value || typeof value !== "object" || !SHAPES.includes(value.shape))
    return null;
  const part = { ...defaultPart(value.shape), ...value };
  return parseRow(serializePart(part)).part;
}

function download(filename, content, type) {
  const link = document.createElement("a");
  link.href = URL.createObjectURL(new Blob([content], { type }));
  link.download = filename;
  link.click();
  URL.revokeObjectURL(link.href);
}
function readFile(input, callback) {
  const file = input.files[0];
  if (!file) return;
  const reader = new FileReader();
  reader.onload = () => callback(String(reader.result));
  reader.readAsText(file);
  input.value = "";
}

function importJson(text) {
  try {
    const data = JSON.parse(text);
    if (Array.isArray(data)) applyRows(data.join("\n"));
    else if (Array.isArray(data.iconParts))
      applyRows(data.iconParts.join("\n"));
    else if (Array.isArray(data.parts)) {
      const imported = data.parts.map((part) =>
        typeof part === "string" ? parseRow(part).part : importedPart(part),
      );
      state.parts = imported.filter(Boolean);
      $("invalidCount").textContent =
        `${imported.filter((part) => !part).length} invalid`;
    }
    const rejected = [
      ...applyImportedSettings(data.appearance),
      ...applyImportedSettings(data.iconSettings),
    ];
    syncInputs();
    renderAll();
    updateStatus(
      rejected.length
        ? `JSON imported; ${rejected.length} field${rejected.length === 1 ? "" : "s"} skipped`
        : "JSON imported",
    );
  } catch {
    updateStatus("Could not parse JSON");
  }
}

function legacyIconJson() {
  return {
    format: "unipatches-legacy-icon",
    version: 1,
    settings: {
      iconMode: state.iconMode,
      background: state.background,
      background2: state.background2,
      background3: state.background3,
      background4: state.background4,
      backgroundAngle: normalizeAngle(state.backgroundAngle),
      backgroundGradient: state.backgroundGradient,
      backgroundStyle: state.backgroundStyle,
      outline: state.outline,
      outline2: state.outline2,
      outlineEnabled: state.outlineWidth > 0,
      outlineWidth: state.outlineWidth,
      outlineGradient: state.outlineGradient,
      outlineAngle: normalizeAngle(state.outlineAngle),
      highlight: state.highlight,
      legacyText: state.legacyText,
      legacyTextSize: state.legacyTextSize,
      legacyBold: state.legacyBold,
      legacyTextFont: state.legacyTextFont,
      legacyTextColor: state.legacyTextColor,
      legacyShape: state.legacyShape,
      legacyShapeColor1: state.legacyShapeColor1,
      legacyShapeColor2: state.legacyShapeColor2,
      legacyShapeGradient: state.legacyShapeGradient,
      legacyShapeAngle: normalizeAngle(state.legacyShapeAngle),
      legacyShapeStroke: state.legacyShapeStroke,
      legacyShapeScale: state.legacyShapeScale,
      iconParts: state.parts.map(serializePart),
      iconSize: state.iconSize,
      iconOpacity: state.iconOpacity,
      dragVisibility: state.dragVisibility,
      iconPosition: state.iconPosition,
      buttonShape: state.buttonShape,
    },
  };
}

function importLegacyIconJson(text) {
  try {
    const data = JSON.parse(text);
    const settings = data.settings || data;
    if (data.format !== "unipatches-legacy-icon" || data.version !== 1)
      throw new Error("unsupported format");
    const importedParts = Array.isArray(settings.iconParts)
      ? settings.iconParts.join("\n")
      : "";
    const { iconParts, ...iconSettings } = settings;
    const rejected = applyImportedSettings(iconSettings);
    if (importedParts) applyRows(importedParts);
    syncInputs();
    renderAll();
    updateStatus(
      rejected.length
        ? `Legacy icon JSON imported; ${rejected.length} field${rejected.length === 1 ? "" : "s"} skipped`
        : "Legacy icon JSON imported",
    );
  } catch {
    updateStatus("Could not parse legacy icon JSON");
  }
}

$("templateGrid").innerHTML = Object.entries(templates)
  .map(
    ([key, template]) =>
      `<button class="template-button" type="button" data-template="${key}"><strong>${template.label}</strong><span>${template.note}</span></button>`,
  )
  .join("");
$("templateGrid")
  .querySelectorAll("[data-template]")
  .forEach((button) =>
    button.addEventListener("click", () =>
      loadTemplate(templates[button.dataset.template]),
    ),
  );
$("addPartButton").addEventListener("click", () => addPart());
$("clearPartsButton").addEventListener("click", () => {
  state.parts = [];
  renderAll();
  updateStatus("Parts cleared");
});
$("newIconButton").addEventListener("click", () => {
  resetEditorState({
    background: "#20252B",
    background2: "#35414C",
    outline: "#78C8FF",
    backgroundStyle: "flat",
  });
  syncInputs();
  renderAll();
  updateStatus("New icon");
});
$("applyRawButton").addEventListener("click", () =>
  applyRows($("rawRows").value),
);
$("importRowsButton").addEventListener("click", () =>
  $("rowsFileInput").click(),
);
$("importJsonButton").addEventListener("click", () =>
  $("jsonFileInput").click(),
);
$("rowsFileInput").addEventListener("change", () =>
  readFile($("rowsFileInput"), applyRows),
);
$("jsonFileInput").addEventListener("change", () =>
  readFile($("jsonFileInput"), importJson),
);
$("legacyJsonFileInput").addEventListener("change", () =>
  readFile($("legacyJsonFileInput"), importLegacyIconJson),
);
$("exportRowsButton").addEventListener("click", async () => {
  try {
    await navigator.clipboard.writeText(rowsFromParts());
    updateStatus("Rows copied");
  } catch {
    $("rawRows").select();
    document.execCommand("copy");
    updateStatus("Rows copied");
  }
});
$("downloadRowsButton").addEventListener("click", () =>
  download("unipatches-icon-parts.txt", rowsFromParts(), "text/plain"),
);
$("downloadJsonButton").addEventListener("click", () =>
  download(
    "unipatches-icon-parts.json",
    JSON.stringify(
      {
        format: "unipatches-icon-parts",
        version: 2,
        iconParts: state.parts.map(serializePart),
        appearance: {
          background: state.background,
          background2: state.background2,
          backgroundAngle: normalizeAngle(state.backgroundAngle),
          backgroundGradient: state.backgroundGradient,
          backgroundStyle: state.backgroundStyle,
          outline: state.outline,
          outline2: state.outline2,
          outlineWidth: state.outlineWidth,
          outlineGradient: state.outlineGradient,
          outlineAngle: normalizeAngle(state.outlineAngle),
          highlight: state.highlight,
        },
        iconSettings: {
          iconMode: state.iconMode,
          legacyText: state.legacyText,
          legacyTextSize: state.legacyTextSize,
          legacyBold: state.legacyBold,
          legacyTextFont: state.legacyTextFont,
          menuTextFont: state.menuTextFont,
          legacyTextColor: state.legacyTextColor,
          legacyShape: state.legacyShape,
          legacyShapeColor1: state.legacyShapeColor1,
          legacyShapeColor2: state.legacyShapeColor2,
          legacyShapeGradient: state.legacyShapeGradient,
          legacyShapeAngle: normalizeAngle(state.legacyShapeAngle),
          legacyShapeStroke: state.legacyShapeStroke,
          legacyShapeScale: state.legacyShapeScale,
          iconSize: state.iconSize,
          iconOpacity: state.iconOpacity,
          dragVisibility: state.dragVisibility,
          iconPosition: state.iconPosition,
          buttonShape: state.buttonShape,
        },
      },
      null,
      2,
    ),
    "application/json",
  ),
);
$("importLegacyJsonButton").addEventListener("click", () =>
  $("legacyJsonFileInput").click(),
);
$("downloadLegacyJsonButton").addEventListener("click", () =>
  download(
    "unipatches-legacy-icon.json",
    JSON.stringify(legacyIconJson(), null, 2),
    "application/json",
  ),
);
$("themeToggle").addEventListener("click", () => {
  const dark = document.body.classList.toggle("dark-theme");
  $("themeToggle").textContent = dark ? "Light mode" : "Dark mode";
  $("themeToggle").setAttribute("aria-pressed", String(dark));
  updateStatus(dark ? "Dark theme enabled" : "Light theme enabled");
});

bindColorPair("backgroundColor", "backgroundColorText", "background");
bindColorPair("backgroundColor2", "backgroundColor2Text", "background2");
bindColorPair("outlineColor", "outlineColorText", "outline");
bindColorPair("outlineColor2", "outlineColor2Text", "outline2");
bindColorPair("legacyTextColor", "legacyTextColorText", "legacyTextColor");
bindColorPair(
  "legacyShapeColor1",
  "legacyShapeColor1Text",
  "legacyShapeColor1",
);
bindColorPair(
  "legacyShapeColor2",
  "legacyShapeColor2Text",
  "legacyShapeColor2",
);
bindValue("backgroundAngle", "backgroundAngle", "number");
bindValue("outlineAngle", "outlineAngle", "number");
bindValue("outlineWidth", "outlineWidth", "number");
bindValue("backgroundStyle", "backgroundStyle");
bindValue("legacyText", "legacyText");
bindValue("legacyTextSize", "legacyTextSize", "number");
bindValue("legacyTextFont", "legacyTextFont");
bindValue("menuTextFont", "menuTextFont");
bindValue("legacyShape", "legacyShape");
bindValue("iconMode", "iconMode");
bindValue("iconSize", "iconSize", "number");
bindValue("iconOpacity", "iconOpacity", "number");
bindValue("dragVisibility", "dragVisibility", "number");
bindValue("iconPosition", "iconPosition");
bindValue("buttonShape", "buttonShape");
bindValue("legacyShapeAngle", "legacyShapeAngle", "number");
bindValue("legacyShapeStroke", "legacyShapeStroke", "number");
bindValue("legacyShapeScale", "legacyShapeScale", "number");
bindCheckbox("backgroundGradient", "backgroundGradient");
bindCheckbox("outlineGradient", "outlineGradient");
bindCheckbox("highlight", "highlight");
bindCheckbox("legacyBold", "legacyBold");
bindCheckbox("legacyShapeGradient", "legacyShapeGradient");
$("imageInput").addEventListener("change", () => {
  const file = $("imageInput").files[0];
  if (!file) return;
  const image = new Image();
  image.onload = () => {
    state.image = image;
    state.iconMode = "image";
    syncInputs();
    drawPreview();
    updateStatus("Local image loaded");
  };
  image.src = URL.createObjectURL(file);
});

state.parts = [
  parseRow("triangle|50|50|55|55|0|solid|#4E97F0|#4E97F0|0|100|0").part,
];
syncInputs();
renderAll();
