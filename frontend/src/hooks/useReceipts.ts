"use client";

import { useEffect, useRef, useState } from "react";
import {
  confirmReceiptDocs,
  confirmReceiptMatch,
  deleteReceipt,
  getReceiptDetail,
  getReceipts,
  getReceiptDemoEligibility,
  getSuppliers,
  getSupplierProducts,
  retryReceipt,
  uploadReceipts,
  type DemoPreparationStatus,
} from "@/lib/api";
import { POLL_INTERVAL } from "@/lib/pollJob";
import { chunkBySize, reading } from "@/lib/receipts";
import {
  demoReceiptBoard,
  demoReceiptPreview,
  confirmDemoReceipts,
  RECEIPT_DEMO_DELAY,
} from "@/lib/demoReceipts";
import { todayYmd } from "@/lib/today";
import { buildDemoStatements, type DemoStatement } from "@/lib/demoStatements";
import type { ApiResponse, ReceiptBoard, ReceiptDetail } from "@/lib/types";

const PREPARE_POLL_DELAY = 1000;
const PREPARE_POLL_MAX = 60;

// 영수증 카드 (DECISIONS #66): GET /receipts, 읽는 장이 있는 동안 2초마다 다시 부른다.
// 바꾸는 주소는 바뀐 뒤의 화면을 돌려주므로 응답으로 그대로 갈아 끼운다.
export function useReceipts(onError: (code: string) => void) {
  const [board, setBoard] = useState<ReceiptBoard | null>(null);
  const [busy, setBusy] = useState(false);
  const [demo, setDemo] = useState<boolean | null>(null);
  const [statements, setStatements] = useState<DemoStatement[]>([]);
  const [statementLoading, setStatementLoading] = useState(false);
  const [statementError, setStatementError] = useState<string | null>(null);
  const mounted = useRef(false);
  const pending = useRef<{ timer: ReturnType<typeof setTimeout>; cancel: () => void } | null>(null);
  const prepareWait = useRef<{ timer: ReturnType<typeof setTimeout>; cancel: () => void } | null>(
    null,
  );

  const cancelPrepareWait = () => {
    if (!prepareWait.current) return;
    clearTimeout(prepareWait.current.timer);
    prepareWait.current.cancel();
    prepareWait.current = null;
  };

  const take = (r: ApiResponse<ReceiptBoard>) => {
    if (r.success) setBoard(r.data);
    else onError(r.error.code);
    return r.success;
  };

  const loadStatements = async (live: () => boolean = () => mounted.current) => {
    setStatementLoading(true);
    setStatementError(null);
    const [suppliers, products] = await Promise.all([getSuppliers(), getSupplierProducts()]);
    if (!live()) return;
    const documents =
      suppliers.success && products.success
        ? buildDemoStatements(suppliers.data, products.data, todayYmd())
        : [];
    setStatements(documents);
    setStatementError(documents.length ? null : "시연 명세서를 불러오지 못했습니다");
    setStatementLoading(false);
  };

  const syncStatements = async (
    initialStatus: DemoPreparationStatus["status"],
    live: () => boolean = () => mounted.current,
  ) => {
    cancelPrepareWait();
    setStatementLoading(true);
    setStatementError(null);
    let status = initialStatus;
    for (
      let attempt = 0;
      status === "PREPARING" && attempt < PREPARE_POLL_MAX && live();
      attempt++
    ) {
      const waited = await new Promise<boolean>((resolve) => {
        prepareWait.current = {
          timer: setTimeout(() => {
            prepareWait.current = null;
            resolve(true);
          }, PREPARE_POLL_DELAY),
          cancel: () => resolve(false),
        };
      });
      if (!waited || !live()) return;
      const next = await getReceiptDemoEligibility();
      if (!live()) return;
      status = next.success ? next.data.status : undefined;
    }
    if (!live()) return;
    await loadStatements(live);
  };

  useEffect(() => {
    let live = true;
    mounted.current = true;
    Promise.all([getReceipts(), getReceiptDemoEligibility()]).then(([r, eligibility]) => {
      if (!live) return;
      const eligible = eligibility.success && eligibility.data.eligible === true;
      setDemo(eligible);
      if (eligible) {
        setBoard(demoReceiptBoard(r.success ? r.data : undefined));
        void syncStatements(eligibility.data.status, () => live);
      } else take(r);
    });
    return () => {
      live = false;
      mounted.current = false;
      cancelPrepareWait();
      if (pending.current) {
        clearTimeout(pending.current.timer);
        pending.current.cancel();
        pending.current = null;
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 처음 한 번
  }, []);

  const polling = board ? reading(board) : false;
  useEffect(() => {
    if (demo !== false || !polling) return;
    const t = setInterval(
      () => getReceipts().then((r) => r.success && setBoard(r.data)),
      POLL_INTERVAL,
    );
    return () => clearInterval(t);
  }, [polling, demo]);

  // 다 읽은 장의 상세 (거래처 이름 · 품목 줄 · 반영 여부). 장 · 확인 목록이 바뀔 때마다 다시 받는다
  const [details, setDetails] = useState<Record<string, ReceiptDetail>>({});
  const done = board?.files.filter((f) => f.st === "done").map((f) => f.id) ?? [];
  const sig = board
    ? `${done.join(",")}|${board.matches.length}|${board.readyDocs.map((d) => d.docId).join(",")}`
    : "";
  useEffect(() => {
    if (demo !== false || !sig) return;
    let live = true;
    const ids = sig.split("|")[0].split(",").filter(Boolean);
    Promise.all(ids.map(getReceiptDetail)).then((rs) => {
      if (!live) return;
      const m: Record<string, ReceiptDetail> = {};
      for (const r of rs) if (r.success) m[r.data.id] = r.data;
      setDetails(m);
    });
    return () => {
      live = false;
    };
  }, [sig, demo]);

  // 성공 여부를 돌려준다 (실패면 오류는 onError 로 이미 알렸다)
  const run = async (f: () => Promise<boolean>) => {
    setBusy(true);
    try {
      return await f();
    } finally {
      setBusy(false);
    }
  };

  const previewDemo = async (documents: DemoStatement[]) => {
    if (!demo || !board || !documents.length) return false;
    const completed = await new Promise<boolean>((resolve) => {
      pending.current = {
        timer: setTimeout(() => {
          pending.current = null;
          resolve(true);
        }, RECEIPT_DEMO_DELAY),
        cancel: () => resolve(false),
      };
    });
    if (!completed) return false;
    const preview = demoReceiptPreview(
      board,
      documents,
      todayYmd(),
      crypto.randomUUID(),
      new Date().toISOString(),
    );
    setBoard(preview.board);
    setDetails((previous) => ({ ...previous, ...preview.details }));
    return true;
  };

  return {
    board,
    details,
    busy,
    demo: demo === true,
    ready:
      demo !== null && board !== null && (!demo || (!statementLoading && statements.length > 0)),
    statements,
    statementError,
    reloadStatements: async () => {
      const eligibility = await getReceiptDemoEligibility();
      if (!mounted.current) return;
      if (eligibility.success && eligibility.data.eligible === true) {
        await syncStatements(eligibility.data.status);
      } else {
        await loadStatements();
      }
    },
    uploadStatements: (selected: DemoStatement[]) =>
      run(async () => {
        const canonical = selected.map((document) =>
          statements.find((current) => current.id === document.id),
        );
        if (canonical.some((document) => !document)) return false;
        return previewDemo(canonical as DemoStatement[]);
      }),
    upload: (files: File[]) =>
      run(async () => {
        if (demo !== false || !board) return false;
        for (const part of chunkBySize(files)) if (!take(await uploadReceipts(part))) return false;
        return true;
      }),
    retry: (id: string) => run(async () => (demo ? true : take(await retryReceipt(id)))),
    remove: (id: string) =>
      run(async () => {
        if (!demo) return take(await deleteReceipt(id));
        setBoard(
          (previous) =>
            previous && {
              ...previous,
              files: previous.files.filter((file) => file.id !== id),
              readyDocs: previous.readyDocs.filter((doc) => doc.docId !== id),
            },
        );
        return true;
      }),
    // 확인 목록을 고른 대로 저장하고, 그 뒤 확인이 끝난 영수증을 확정한다 (버튼 하나)
    confirmAll: (picks: { id: string; choice: string; basePerPack: number | null }[]) =>
      run(async () => {
        if (demo && board) {
          setBoard(confirmDemoReceipts(board));
          return true;
        }
        let ready = board?.readyDocs ?? [];
        for (const p of picks) {
          const r = await confirmReceiptMatch(p.id, p.choice, p.basePerPack);
          if (!take(r)) return false;
          if (r.success) ready = r.data.readyDocs;
        }
        return !ready.length || take(await confirmReceiptDocs(ready.map((d) => d.docId)));
      }),
  };
}
