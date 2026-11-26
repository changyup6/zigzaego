import type { ReceiptBoard, ReceiptDetail, ReceiptFile } from "./types";

// 요청 하나는 20MB 까지 (백엔드 POST /receipts). 넘으면 여러 번 나눠 보낸다. 한 장이 넘으면 혼자 보내 서버가 거절하게 둔다.
export const UPLOAD_LIMIT = 20 * 1024 * 1024;

export function chunkBySize<T extends { size: number }>(files: T[], limit = UPLOAD_LIMIT): T[][] {
  const out: T[][] = [];
  let cur: T[] = [];
  let sum = 0;
  for (const f of files) {
    if (cur.length && sum + f.size > limit) {
      out.push(cur);
      cur = [];
      sum = 0;
    }
    cur.push(f);
    sum += f.size;
  }
  if (cur.length) out.push(cur);
  return out;
}

// 읽는 중인 장이 있으면 화면이 2초마다 다시 부른다
export const reading = (b: Pick<ReceiptBoard, "files">) =>
  b.files.some((f) => f.st === "wait" || f.st === "read");

const md = (d: string) => `${Number(d.slice(5, 7))}월 ${Number(d.slice(8, 10))}일`;

// 장 이름: "거래처 이름 (M월 D일)". 날짜는 영수증 날짜, 못 읽었으면 올린 날. 상세를 아직 못 받았으면 날짜만
export function receiptTitle(
  f: Pick<ReceiptFile, "docDate" | "at">,
  d?: Pick<ReceiptDetail, "supplierName">,
): string {
  const day = md(f.docDate ?? f.at);
  if (!d) return day;
  return `${d.supplierName ?? "거래처 모름"} (${day})`;
}

// 반영 완료: 다 읽었고, 확인 전 줄이 없고, 확정만 남은 영수증(readyDocs)도 아니다
export const reflected = (
  f: Pick<ReceiptFile, "id" | "st">,
  d: Pick<ReceiptDetail, "lines"> | undefined,
  ready: string[],
) =>
  f.st === "done" && !!d && d.lines.every((l) => l.status !== "PENDING") && !ready.includes(f.id);
