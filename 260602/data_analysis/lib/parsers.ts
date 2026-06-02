import Papa from 'papaparse';
import * as XLSX from 'xlsx';
import type { ParsedData } from './types';

export async function parseCSV(file: File): Promise<ParsedData> {
  return new Promise((resolve, reject) => {
    Papa.parse<Record<string, unknown>>(file, {
      header: true,
      skipEmptyLines: true,
      dynamicTyping: false,
      complete: (result) => {
        if (result.errors.length > 0 && result.data.length === 0) {
          reject(new Error(result.errors[0].message));
          return;
        }
        const columns = result.meta.fields ?? [];
        resolve({ columns, rows: result.data });
      },
      error: (err) => reject(new Error(err.message)),
    });
  });
}

export async function parseXLSX(file: File): Promise<ParsedData> {
  const buffer = await file.arrayBuffer();
  const workbook = XLSX.read(buffer, { type: 'array' });
  const sheetName = workbook.SheetNames[0];
  if (!sheetName) throw new Error('파일에 시트가 없습니다.');

  const sheet = workbook.Sheets[sheetName];
  const rows = XLSX.utils.sheet_to_json<Record<string, unknown>>(sheet, { defval: null });

  if (rows.length === 0) return { columns: [], rows: [] };

  const columns = Object.keys(rows[0]);
  return { columns, rows };
}

export async function parseFile(file: File): Promise<ParsedData> {
  const ext = file.name.split('.').pop()?.toLowerCase();
  if (ext === 'csv') return parseCSV(file);
  if (ext === 'xlsx' || ext === 'xls') return parseXLSX(file);
  throw new Error(`지원하지 않는 파일 형식: .${ext ?? '알 수 없음'} (CSV, XLSX, XLS만 가능)`);
}
