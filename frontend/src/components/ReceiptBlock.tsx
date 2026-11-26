"use client";

import { useState, type ReactNode } from "react";
import { useReceipts } from "@/hooks/useReceipts";
import { fmt } from "@/lib/format";
import { reading, receiptTitle, reflected } from "@/lib/receipts";
import { FROM_API } from "@/lib/units";
import type { ReceiptBoard, ReceiptDetail, ReceiptFile, ReceiptMatch } from "@/lib/types";
import { Button } from "./Button";
import { Card } from "./Card";
import { FileButton } from "./FileButton";
import { Modal } from "./Modal";
import { reviewClass } from "./ReviewRow";
import { useToast } from "./Toast";
import { TabletDocumentPicker } from "./TabletDocumentPicker";
import styles from "./ReceiptBlock.module.css";

const cx = (...c: (string | false | undefined)[]) => c.filter(Boolean).join(" ");

const ERROR: Record<string, string> = {
  RECEIPT_FILE_TOO_LARGE: "10MB 넘는 파일",
  RECEIPT_FILE_NOT_SUPPORTED: "JPG · PNG · PDF만",
  RECEIPT_TOO_MANY_FILES: "한 번에 50장까지",
  RECEIPT_OCR_NOT_CONFIGURED: "지금은 읽을 수 없음",
  RECEIPT_DATE_UNKNOWN: "날짜를 못 읽은 영수증",
};

const Ic = ({ children }: { children: ReactNode }) => (
  <svg className={styles.ic} viewBox="0 0 24 24" aria-hidden="true">
    {children}
  </svg>
);
const CHECK = <path d="M5 12l5 5L20 7" />;
const RECEIPT = (
  <>
    <path d="M6 3h12v18l-3-2-3 2-3-2-3 2z" />
    <path d="M9 8h6M9 12h6" />
  </>
);

// 읽는 중 스피너: 영수증 위 빨간 돋보기가 사람이 읽는 순서대로 (왼→오, 한 줄 내려 다시 왼→오)
function ReadingSpinner() {
  return (
    <div className={styles.spin} role="status" aria-label="영수증 읽는 중">
      <svg viewBox="0 0 120 150" aria-hidden="true">
        <path
          className={styles.paper}
          d="M14 8h92v130l-10-8-10 8-10-8-10 8-10-8-10 8-10-8-10 8-12-8z"
        />
        <path className={styles.txt} d="M30 36h60M30 64h60M30 92h40" />
        <g className={styles.lens}>
          <circle cx="0" cy="0" r="13" />
          <path d="M9 9l11 11" />
        </g>
      </svg>
    </div>
  );
}

function FileLine({
  f,
  d,
  done,
  open,
  onToggle,
  onRetry,
  onRemove,
}: {
  f: ReceiptFile;
  d?: ReceiptDetail;
  done: boolean;
  open: boolean;
  onToggle: () => void;
  onRetry: () => void;
  onRemove: () => void;
}) {
  const title = receiptTitle(f, d);
  return (
    <div className={styles.item}>
      <div className={styles.rc}>
        <button
          type="button"
          className={styles.head}
          aria-expanded={open}
          disabled={!d}
          onClick={onToggle}
        >
          <span className={styles.thumb}>
            <Ic>{RECEIPT}</Ic>
          </span>
          <span className={styles.name}>{title}</span>
          {d && (
            <svg
              className={cx(styles.chev, open && styles.up)}
              viewBox="0 0 24 24"
              aria-hidden="true"
            >
              <path d="m6 9 6 6 6-6" />
            </svg>
          )}
        </button>
        <span className={styles.st}>
          {done && (
            <span className={styles.done}>
              <Ic>{CHECK}</Ic>
              반영 완료
            </span>
          )}
          {(f.st === "wait" || f.st === "read") && <span className={styles.read}>읽는 중</span>}
          {f.st === "fail" && (
            <>
              <span className={styles.fail}>{f.error ? (ERROR[f.error] ?? "실패") : "실패"}</span>
              <Button variant="ghost" small onClick={onRetry} aria-label={`${title} 다시 읽기`}>
                <Ic>
                  <path d="M20 12a8 8 0 1 1-2.3-5.7" />
                  <path d="M20 4v5h-5" />
                </Ic>
              </Button>
              <Button variant="quiet" small onClick={onRemove} aria-label={`${title} 삭제`}>
                <Ic>
                  <path d="M6 6l12 12M18 6L6 18" />
                </Ic>
              </Button>
            </>
          )}
        </span>
      </div>
      {/* 펼침: 그 영수증 품목 */}
      <div className={cx(styles.fold, open && styles.open)}>
        <div>
          <ul className={styles.lines}>
            {d?.lines.map((l) => (
              <li key={l.id}>
                <span>{l.rawName}</span>
                <span className="num">
                  {l.qty == null ? "" : `${fmt(Number(l.qty))}${l.unit ?? ""}`}
                </span>
                <span className="num">
                  {l.amount == null ? "–" : `${l.amount.toLocaleString("ko-KR")}원`}
                </span>
              </li>
            ))}
            {d && !d.lines.length && <li className={styles.none}>품목 없음</li>}
          </ul>
        </div>
      </div>
    </div>
  );
}

type Pick = { choice: string; per: string };
const firstPick = (m: ReceiptMatch): Pick => ({
  choice: m.guess || "cycle",
  per: m.basePerPack == null ? "" : String(m.basePerPack),
});
const needPer = (m: ReceiptMatch, p: Pick) => m.unitNeeded && p.choice !== "ignore";

function MatchRow({
  m,
  options,
  pick,
  onPick,
}: {
  m: ReceiptMatch;
  options: ReceiptBoard["options"];
  pick: Pick;
  onPick: (p: Pick) => void;
}) {
  const mark = reviewClass(m.needsReview, "field");
  return (
    <div className={styles.match}>
      <div className={styles.matchName}>
        “{m.name}”<small>영수증 {m.receipts}장</small>
      </div>
      <div className={styles.matchCtl}>
        <select
          className={cx(styles.sel, mark)}
          value={pick.choice}
          onChange={(e) => onPick({ ...pick, choice: e.target.value })}
          aria-label={`${m.name} 재료`}
        >
          {options.map((o) => (
            <option key={o.id} value={o.id}>
              {o.name}
            </option>
          ))}
          <option value="cycle">주기형 품목</option>
          <option value="ignore">무시</option>
        </select>
        {needPer(m, pick) && (
          <span className={styles.unit}>
            1개 ={" "}
            <input
              className={cx(styles.qtyIn, "num n17", mark)}
              type="number"
              inputMode="decimal"
              min={0}
              value={pick.per}
              onChange={(e) => onPick({ ...pick, per: e.target.value })}
              aria-label="1개당 양"
            />{" "}
            {m.baseUnit ? FROM_API[m.baseUnit] : "g"}
          </span>
        )}
      </div>
    </div>
  );
}

// 이전 발주 거래 명세서 카드 (DECISIONS #66 · S15P31A308-271). 발주 이력 탭 맨 아래 · 온보딩 마지막 단계.
// 올리면 모달: 읽는 동안 스피너 → 다 읽으면 같은 모달에서 상품명 ↔ 재료 확인 → 확정하면 닫히고 반영.
// 카드에는 장마다 "거래처 (M월 D일)" · 반영 완료, 누르면 품목이 펼쳐진다.
export function ReceiptBlock() {
  const { toast, show } = useToast();
  const r = useReceipts((code) => show(ERROR[code] ?? "실패"));
  const b = r.board;
  const [modal, setModal] = useState(false);
  const [picker, setPicker] = useState(false);
  const [openId, setOpenId] = useState<string | null>(null);
  const [picks, setPicks] = useState<Record<string, Pick>>({});
  const matches = b?.matches ?? [];
  const readyIds = b?.readyDocs.map((d) => d.docId) ?? [];
  const left = matches.length + readyIds.length;
  const busy = r.busy || (b ? reading(b) : false);
  const fails = b?.files.filter((f) => f.st === "fail") ?? [];
  const pickOf = (m: ReceiptMatch) => picks[m.id] ?? firstPick(m);
  const filled = matches.every((m) => !needPer(m, pickOf(m)) || Number(pickOf(m).per) > 0);

  // 실패하면 모달과 고른 재료를 그대로 둔다
  const confirm = async () => {
    const ok = await r.confirmAll(
      matches.map((m) => {
        const p = pickOf(m);
        return { id: m.id, choice: p.choice, basePerPack: p.per === "" ? null : Number(p.per) };
      }),
    );
    if (!ok) return;
    setPicks({});
    setModal(false);
  };

  return (
    <>
      <Card>
        <div className={styles.top}>
          <h2 className={styles.title}>이전 발주 거래 명세서</h2>
          {r.demo ? (
            <Button
              variant="ghost"
              small
              disabled={r.busy || !r.ready}
              onClick={() => setPicker(true)}
            >
              <Ic>
                <path d="M4 8h3l2-3h6l2 3h3v11H4z" />
                <circle cx="12" cy="13" r="3.5" />
              </Ic>
              올리기
            </Button>
          ) : (
            <FileButton
              accept="image/*,.pdf"
              multiple
              small
              disabled={r.busy || !r.ready}
              onFiles={(fs) => {
                setModal(true);
                r.upload(fs);
              }}
            >
              <Ic>
                <path d="M4 8h3l2-3h6l2 3h3v11H4z" />
                <circle cx="12" cy="13" r="3.5" />
              </Ic>
              올리기
            </FileButton>
          )}
        </div>
        {r.demo && r.statementError && (
          <div role="alert" className={styles.failRow}>
            <span>{r.statementError}</span>
            <Button variant="ghost" small onClick={() => r.reloadStatements()}>
              다시 불러오기
            </Button>
          </div>
        )}
        {b && !b.files.length && <p className={styles.empty}>없음</p>}
        {left > 0 && !busy && (
          <div className={styles.left}>
            <span>
              확정 전 <span className="num n17">{left}</span>
            </span>
            <Button variant="ghost" small onClick={() => setModal(true)}>
              확인
            </Button>
          </div>
        )}
        {b?.files.map((f) => (
          <FileLine
            key={f.id}
            f={f}
            d={r.details[f.id]}
            done={reflected(f, r.details[f.id], readyIds)}
            open={openId === f.id}
            onToggle={() => setOpenId(openId === f.id ? null : f.id)}
            onRetry={() => r.retry(f.id)}
            onRemove={() => r.remove(f.id)}
          />
        ))}
      </Card>
      {picker && r.demo && (
        <TabletDocumentPicker
          documents={r.statements}
          onClose={() => setPicker(false)}
          onComplete={(documents) => {
            setPicker(false);
            setModal(true);
            void r.uploadStatements(documents);
          }}
        />
      )}
      {modal && b && (
        <Modal
          open
          label="이전 발주 거래 명세서"
          head={<h2>이전 발주 거래 명세서</h2>}
          onClose={() => setModal(false)}
        >
          {busy ? (
            <div className={styles.reading}>
              <ReadingSpinner />
            </div>
          ) : (
            <>
              {r.demo && left > 0 && (
                <p className={styles.success} role="status">
                  <Ic>{CHECK}</Ic>분석 완료
                </p>
              )}
              {fails.map((f) => (
                <div key={f.id} className={styles.failRow}>
                  <span>{f.file ?? receiptTitle(f)}</span>
                  <span className={styles.fail}>
                    {f.error ? (ERROR[f.error] ?? "실패") : "실패"}
                  </span>
                  <Button variant="ghost" small onClick={() => r.retry(f.id)}>
                    다시 읽기
                  </Button>
                </div>
              ))}
              {matches.length > 0 && (
                <div className={styles.blk}>
                  {matches.map((m) => (
                    <MatchRow
                      key={m.id}
                      m={m}
                      options={b.options}
                      pick={pickOf(m)}
                      onPick={(p) => setPicks({ ...picks, [m.id]: p })}
                    />
                  ))}
                </div>
              )}
              {b.readyDocs.length > 0 && (
                <ul className={styles.ready}>
                  {b.readyDocs.map((d) => {
                    const f = b.files.find((x) => x.id === d.docId);
                    return (
                      <li key={d.docId}>
                        <span>{f ? receiptTitle(f, r.details[d.docId]) : d.date}</span>
                        <span>
                          품목 <span className="num">{d.lines}</span>
                        </span>
                      </li>
                    );
                  })}
                </ul>
              )}
              <div className={styles.acts}>
                <Button disabled={r.busy || !filled} onClick={confirm}>
                  확정
                </Button>
              </div>
            </>
          )}
        </Modal>
      )}
      {toast}
    </>
  );
}
