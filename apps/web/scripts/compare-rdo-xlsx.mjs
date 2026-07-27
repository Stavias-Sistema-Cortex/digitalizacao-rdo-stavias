import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";

import * as XLSX from "@e965/xlsx";

const [serverArgument, offlineArgument, reportArgument] = process.argv.slice(2);
if (!serverArgument || !offlineArgument) {
  throw new Error(
    "Uso: node scripts/compare-rdo-xlsx.mjs <servidor.xlsx> <offline.xlsx> [relatorio.json]",
  );
}

const FRONT = "v.1 RDO frente";
const BACK = "v.1 RDO verso";
const ranges = {
  [FRONT]: [
    "B1:B1", "B6:AJ6", "D10:P12", "Q10:AJ12", "B16:AJ28",
    "M30:AA31", "B36:Q51", "T36:AJ51", "M53:AA54", "B60:AJ80",
  ],
  [BACK]: [
    "B2:B2", "AB4:AD4", "B8:J17", "L8:T17", "V8:AD17",
    "B26:AD61", "B63:AD69",
  ],
};

function readWorkbook(path) {
  return readFile(resolve(path)).then((bytes) => XLSX.read(bytes, {
    type: "buffer",
    cellStyles: true,
  }));
}

function normalizedCell(sheet, address) {
  const cell = sheet[address];
  if (!cell || cell.t === "z" || cell.v === undefined || cell.v === "") {
    return null;
  }
  return {
    type: cell.t,
    value: typeof cell.v === "number"
      ? Math.round(cell.v * 1_000_000_000) / 1_000_000_000
      : cell.v,
  };
}

function regionValues(workbook, sheetName) {
  const sheet = workbook.Sheets[sheetName];
  const values = {};
  for (const rangeAddress of ranges[sheetName]) {
    const range = XLSX.utils.decode_range(rangeAddress);
    for (let row = range.s.r; row <= range.e.r; row += 1) {
      for (let column = range.s.c; column <= range.e.c; column += 1) {
        const address = XLSX.utils.encode_cell({ r: row, c: column });
        const value = normalizedCell(sheet, address);
        if (value !== null) values[address] = value;
      }
    }
  }
  return values;
}

function rowCounts(workbook) {
  const front = workbook.Sheets[FRONT];
  const back = workbook.Sheets[BACK];
  const count = (sheet, rows, columns) => rows.reduce(
    (total, row) => total + (columns.some((column) =>
      normalizedCell(sheet, `${column}${row}`) !== null) ? 1 : 0),
    0,
  );
  return {
    workforce: count(front, Array.from({ length: 13 }, (_, index) => 16 + index), ["B", "M"]),
    equipment: count(front, Array.from({ length: 16 }, (_, index) => 36 + index), ["B", "T"]),
    services: count(front, Array.from({ length: 21 }, (_, index) => 60 + index), ["B", "X"]),
    materials: count(back, Array.from({ length: 10 }, (_, index) => 8 + index), ["B", "L", "V"]),
    geometricControl: count(back, Array.from({ length: 36 }, (_, index) => 26 + index), ["B", "F", "I"]),
  };
}

function workbookContract(workbook) {
  return {
    sheetNames: workbook.SheetNames,
    values: {
      [FRONT]: regionValues(workbook, FRONT),
      [BACK]: regionValues(workbook, BACK),
    },
    rowCounts: rowCounts(workbook),
    merges: Object.fromEntries(workbook.SheetNames.map((sheetName) => [
      sheetName,
      (workbook.Sheets[sheetName]["!merges"] ?? [])
        .map(XLSX.utils.encode_range)
        .sort(),
    ])),
    printAreas: (workbook.Workbook?.Names ?? [])
      .filter((name) => name.Name === "_xlnm.Print_Area")
      .map(({ Name, Ref, Sheet }) => ({ Name, Ref, Sheet }))
      .sort((left, right) => (left.Sheet ?? -1) - (right.Sheet ?? -1)),
  };
}

const [serverWorkbook, offlineWorkbook] = await Promise.all([
  readWorkbook(serverArgument),
  readWorkbook(offlineArgument),
]);
const server = workbookContract(serverWorkbook);
const offline = workbookContract(offlineWorkbook);
const serverJson = JSON.stringify(server);
const offlineJson = JSON.stringify(offline);
if (serverJson !== offlineJson) {
  const report = { equivalent: false, server, offline };
  if (reportArgument) {
    await writeFile(resolve(reportArgument), `${JSON.stringify(report, null, 2)}\n`);
  }
  throw new Error("Os contratos estruturais Java e TypeScript do RDO divergiram.");
}

const report = {
  equivalent: true,
  compared: {
    sheetNames: server.sheetNames,
    valueAndTypeCells: Object.values(server.values)
      .reduce((total, values) => total + Object.keys(values).length, 0),
    rowCounts: server.rowCounts,
    mergedRanges: Object.fromEntries(
      Object.entries(server.merges).map(([sheet, merges]) => [sheet, merges.length]),
    ),
    printAreas: server.printAreas,
  },
};
if (reportArgument) {
  await writeFile(resolve(reportArgument), `${JSON.stringify(report, null, 2)}\n`);
}
console.log(JSON.stringify(report));
