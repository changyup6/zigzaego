import type { ReceiptBoard, ReceiptDetail } from "./types";
import type { DemoStatement } from "./demoStatements";

export const RECEIPT_DEMO_DELAY = 5000;

// 서버 문서는 시연 확정에 섞지 않는다. 재료 선택지의 이름만 미리보기 재료로 쓴다.
export function demoReceiptBoard(source?: ReceiptBoard): ReceiptBoard {
  return { jobId: null, files: [], matches: [], options: source?.options ?? [], readyDocs: [] };
}

export function demoReceiptPreview(
  base: ReceiptBoard,
  documents: (string | DemoStatement)[],
  date: string,
  batch: string,
  at: string,
): { board: ReceiptBoard; details: Record<string, ReceiptDetail> } {
  const files = documents.map((document, index) => ({
    id: `receipt-demo-${batch}-${index}`,
    file: typeof document === "string" ? document : document.filename,
    at,
    st: "done" as const,
    kind: "PAST" as const,
    docDate: typeof document === "string" ? date : document.date,
    lineCount:
      typeof document === "string"
        ? Math.max(1, Math.min(3, base.options.length))
        : document.lines.length,
    error: null,
  }));
  const details = Object.fromEntries(
    files.map((file, index) => [
      file.id,
      {
        ...file,
        supplierName: typeof documents[index] === "string" ? null : documents[index].supplierName,
        lines:
          typeof documents[index] !== "string"
            ? documents[index].lines.map((line) => ({
                ...line,
                id: `${file.id}-${line.id}`,
                status: "CONFIRMED" as const,
              }))
            : (base.options.length ? base.options.slice(0, 3) : [{ name: file.file }]).map(
                (item, index) => ({
                  id: `${file.id}-${index}`,
                  rawName: item.name,
                  qty: 1,
                  unit: null,
                  amount: null,
                  status: "CONFIRMED" as const,
                }),
              ),
      },
    ]),
  );
  return {
    board: {
      ...base,
      files: [...base.files, ...files],
      readyDocs: [
        ...base.readyDocs,
        ...files.map((file) => ({
          docId: file.id,
          date: file.docDate,
          kind: file.kind,
          lines: file.lineCount,
        })),
      ],
    },
    details,
  };
}

export const confirmDemoReceipts = (board: ReceiptBoard): ReceiptBoard => ({
  ...board,
  matches: [],
  readyDocs: [],
});
