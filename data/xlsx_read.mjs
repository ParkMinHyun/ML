// Minimal .xlsx reader: zip central directory + raw inflate + sheet XML scan.
// Exists because the Python toolchain on this machine is blocked by an
// application-control policy; Node ships everything this needs.
// ponytail: handles the exporter's own output only (no zip64, no styles, no
// dates) - swap in a real library if these workbooks ever grow past 4 GB or
// start carrying date-formatted cells.
import { readFileSync } from "node:fs";
import { inflateRawSync } from "node:zlib";

function unzip(buffer) {
  let end = buffer.length - 22;
  while (end >= 0 && buffer.readUInt32LE(end) !== 0x06054b50) {
    end--;
  }
  if (end < 0) {
    throw new Error("not a zip file");
  }
  const count = buffer.readUInt16LE(end + 10);
  let offset = buffer.readUInt32LE(end + 16);
  const files = new Map();
  for (let i = 0; i < count; i++) {
    const method = buffer.readUInt16LE(offset + 10);
    const compressedSize = buffer.readUInt32LE(offset + 20);
    const nameLength = buffer.readUInt16LE(offset + 28);
    const extraLength = buffer.readUInt16LE(offset + 30);
    const commentLength = buffer.readUInt16LE(offset + 32);
    const localOffset = buffer.readUInt32LE(offset + 42);
    const name = buffer.toString("utf8", offset + 46, offset + 46 + nameLength);
    const localNameLength = buffer.readUInt16LE(localOffset + 26);
    const localExtraLength = buffer.readUInt16LE(localOffset + 28);
    const start = localOffset + 30 + localNameLength + localExtraLength;
    const raw = buffer.subarray(start, start + compressedSize);
    files.set(name, method === 0 ? raw : inflateRawSync(raw));
    offset += 46 + nameLength + extraLength + commentLength;
  }
  return files;
}

const ENTITIES = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'" };
function decode(text) {
  return text.replace(/&(amp|lt|gt|quot|apos|#\d+);/g, (whole, code) =>
    code.startsWith("#") ? String.fromCharCode(Number(code.slice(1))) : ENTITIES[code]);
}

function sharedStrings(xml) {
  if (!xml) {
    return [];
  }
  return [...xml.matchAll(/<si>([\s\S]*?)<\/si>/g)].map((match) =>
    [...match[1].matchAll(/<t[^>]*>([\s\S]*?)<\/t>/g)].map((t) => decode(t[1])).join(""));
}

function columnIndex(reference) {
  let index = 0;
  for (const character of reference.replace(/\d+/g, "")) {
    index = index * 26 + (character.charCodeAt(0) - 64);
  }
  return index - 1;
}

/** Returns { sheetName: [rowObject, ...] } keyed by the first row's headers. */
export function readWorkbook(path) {
  const files = unzip(readFileSync(path));
  const strings = sharedStrings(files.get("xl/sharedStrings.xml")?.toString("utf8"));
  const rels = new Map(
    [...(files.get("xl/_rels/workbook.xml.rels")?.toString("utf8") ?? "")
      .matchAll(/<Relationship[^>]*Id="([^"]+)"[^>]*Target="([^"]+)"/g)]
      .map((match) => [match[1], match[2].replace(/^\/?xl\//, "")]));

  const sheets = {};
  const workbookXml = files.get("xl/workbook.xml").toString("utf8");
  for (const match of workbookXml.matchAll(/<sheet[^>]*name="([^"]+)"[^>]*r:id="([^"]+)"/g)) {
    const xml = files.get(`xl/${rels.get(match[2])}`)?.toString("utf8");
    if (!xml) {
      continue;
    }
    const grid = [];
    for (const rowMatch of xml.matchAll(/<row[^>]*>([\s\S]*?)<\/row>/g)) {
      const cells = [];
      for (const cell of rowMatch[1].matchAll(/<c r="([A-Z]+)\d+"([^>]*)\/?>(?:([\s\S]*?)<\/c>)?/g)) {
        const attributes = cell[2] ?? "";
        const body = cell[3] ?? "";
        const type = /t="([^"]+)"/.exec(attributes)?.[1];
        let value;
        if (type === "inlineStr") {
          value = [...body.matchAll(/<t[^>]*>([\s\S]*?)<\/t>/g)].map((t) => decode(t[1])).join("");
        } else {
          const raw = /<v>([\s\S]*?)<\/v>/.exec(body)?.[1];
          if (raw === undefined) {
            value = null;
          } else if (type === "s") {
            value = strings[Number(raw)];
          } else if (type === "b") {
            value = raw === "1";
          } else if (type === "str" || type === "e") {
            value = decode(raw);
          } else {
            value = Number(raw);
          }
        }
        cells[columnIndex(cell[1])] = value === "" ? null : value;
      }
      grid.push(cells);
    }
    const headers = (grid[0] ?? []).map((h, i) => (h === null || h === undefined ? `col${i}` : String(h)));
    sheets[decode(match[1])] = grid.slice(1).map((cells) => {
      const row = {};
      headers.forEach((header, i) => {
        row[header] = cells[i] === undefined ? null : cells[i];
      });
      return row;
    });
  }
  return sheets;
}

export function asBool(value) {
  return value === true || String(value).trim().toLowerCase() === "true" || value === 1;
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].replaceAll("\\", "/"))) {
  const sheets = readWorkbook(process.argv[2]);
  for (const [name, rows] of Object.entries(sheets)) {
    console.log(name, rows.length, Object.keys(rows[0] ?? {}).length);
  }
}
