"use client";

import { useEffect, useId, useRef, useState } from "react";
import type { DemoStatement } from "@/lib/demoStatements";
import styles from "./TabletDocumentPicker.module.css";

type IconName =
  "close" | "menu" | "recent" | "download" | "document" | "search" | "list" | "grid" | "check" | "folder" | "image" | "video" | "audio" | "apk" | "storage";
const ICONS: Record<IconName, string> = {
  close: "M6 6l12 12M18 6 6 18",
  menu: "M4 6h16M4 12h16M4 18h16",
  recent: "M12 3a9 9 0 1 1-6.36 2.64M3 3v5h5M12 7v5l3 2",
  download: "M12 3v12m-5-5 5 5 5-5M5 17v4h14v-4",
  document: "M14 3H6a1 1 0 0 0-1 1v16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V8l-5-5Zm0 0v5h5M8 12h8M8 16h6",
  search: "M16 16l5 5M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0Z",
  list: "M8 5h13M8 12h13M8 19h13M3 5h.01M3 12h.01M3 19h.01",
  grid: "M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z",
  check: "m5 12 4 4L19 6",
  folder: "M3 7V5a1 1 0 0 1 1-1h6l2 3h8a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V7Z",
  image: "M4 4h16v16H4zM4 16l5-5 5 5 3-3 3 3M15 8h.01",
  video: "M4 4h16v16H4zM10 8l6 4-6 4Z",
  audio: "M10 17V5l9-2v12M10 7l9-2M10 17a3 3 0 1 1-3-3c1.66 0 3 1.34 3 3Zm9-2a3 3 0 1 1-3-3c1.66 0 3 1.34 3 3Z",
  apk: "M5 9h14v11H5zM5 9a7 7 0 0 1 14 0M7 2l2 3M17 2l-2 3M8 7h.01M16 7h.01",
  storage: "M6 3h12v18H6zM10 18h4",
};

function Icon({ name }: { name: IconName }) {
  return (
    <svg
      viewBox="0 0 24 24"
      aria-hidden="true"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d={ICONS[name]} />
    </svg>
  );
}

const LOCATIONS = [
  { id: "recent", label: "최근 파일", icon: "recent" },
  { id: "images", label: "이미지", icon: "image" },
  { id: "videos", label: "동영상", icon: "video" },
  { id: "audio", label: "오디오 파일", icon: "audio" },
  { id: "documents", label: "문서", icon: "document" },
  { id: "download", label: "다운로드", icon: "download" },
  { id: "installation", label: "설치 파일", icon: "apk" },
  { id: "internal", label: "내장 저장공간", icon: "storage" },
] as const;
const money = (value: number | null) =>
  value == null ? "—" : `${value.toLocaleString("ko-KR")}원`;

function StatementThumbnail({ document }: { document: DemoStatement }) {
  return (
    <div className={styles.preview} aria-hidden="true">
      <div className={styles.sheet}>
        <div className={styles.sheetTitle}>거래명세서</div>
        <div className={styles.sheetDate}>{document.date.replaceAll("-", ".")}</div>
        <div className={styles.sheetSupplier}>
          <span>공급처</span>
          <strong>{document.supplierName}</strong>
        </div>
        <table>
          <thead>
            <tr>
              <th>품목</th>
              <th>수량</th>
              <th>금액</th>
            </tr>
          </thead>
          <tbody>
            {document.lines.slice(0, 6).map((line) => (
              <tr key={line.id}>
                <td>{line.rawName}</td>
                <td>
                  {line.qty.toLocaleString("ko-KR")}
                  {line.unit ? ` ${line.unit}` : ""}
                </td>
                <td>{money(line.amount)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {document.lines.length > 6 && (
          <span className={styles.moreLines}>외 {document.lines.length - 6}개 품목</span>
        )}
        <div className={styles.sheetTotal}>
          <span>합계</span>
          <strong>{money(document.total)}</strong>
        </div>
        <div className={styles.sheetFoot}>{document.supplierName}</div>
      </div>
    </div>
  );
}

/** Galaxy Tab의 삼성 내 파일을 참고한 앱 내부 시연 화면. OS 파일에는 접근하지 않는다. */
export function TabletDocumentPicker({
  documents,
  onComplete,
  onClose,
}: {
  documents: DemoStatement[];
  onComplete: (documents: DemoStatement[]) => void;
  onClose: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const sidebar = useRef<HTMLElement>(null);
  const menu = useRef<HTMLButtonElement>(null);
  const search = useRef<HTMLInputElement>(null);
  const id = useId();
  const [location, setLocation] = useState<(typeof LOCATIONS)[number]["id"]>("recent");
  const [selected, setSelected] = useState<string[]>([]);
  const [drawer, setDrawer] = useState(false);
  const [searching, setSearching] = useState(false);
  const [query, setQuery] = useState("");
  const [view, setView] = useState<"grid" | "list">("list");
  const chosen = documents.filter((document) => selected.includes(document.id));
  const current = LOCATIONS.find((item) => item.id === location)!;
  const needle = query.trim().toLocaleLowerCase("ko-KR");
  const hasDocuments = ["recent", "documents", "download", "internal"].includes(location);
  const visible = (hasDocuments ? documents : []).filter((document) =>
    `${document.filename} ${document.supplierName}`.toLocaleLowerCase("ko-KR").includes(needle),
  );

  useEffect(() => {
    const element = dialog.current;
    if (!element) return;
    const previousOverflow = document.documentElement.style.overflow;
    if (!element.open) element.showModal();
    document.documentElement.style.overflow = "hidden";
    return () => {
      document.documentElement.style.overflow = previousOverflow;
    };
  }, []);

  useEffect(() => {
    if (!drawer) return;
    const activeLocation =
      sidebar.current?.querySelector<HTMLButtonElement>('[aria-current="page"]');
    const firstLocation = sidebar.current?.querySelector<HTMLButtonElement>("button");
    (activeLocation ?? firstLocation)?.focus();
  }, [drawer]);

  const closeDrawer = () => {
    setDrawer(false);
    requestAnimationFrame(() => menu.current?.focus());
  };

  return (
    <dialog
      ref={dialog}
      className={styles.picker}
      aria-label="파일 선택"
      onClose={onClose}
      onCancel={(event) => {
        event.preventDefault();
        if (drawer) closeDrawer();
        else onClose();
      }}
      onClick={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div className={styles.surface}>
        {drawer && (
          <button
            type="button"
            className={styles.drawerScrim}
            aria-label="탐색 메뉴 닫기"
            onClick={closeDrawer}
          />
        )}
        <aside ref={sidebar} id={`${id}-locations`} className={styles.sidebar} data-open={drawer}>
          <div className={styles.brand}>
            <span className={styles.brandIcon}>
              <Icon name="folder" />
            </span>
            <strong>내 파일</strong>
          </div>
          <nav aria-label="문서 위치">
            {LOCATIONS.map((item) => (
              <button
                type="button"
                key={item.id}
                aria-current={location === item.id ? "page" : undefined}
                className={styles.location}
                data-location={item.id}
                onClick={() => {
                  setLocation(item.id);
                  setQuery("");
                  closeDrawer();
                }}
              >
                <Icon name={item.icon} />
                <span>{item.label}{item.id === "internal" && <small className={styles.storageDetail}>Download · 문서 {documents.length}개</small>}</span>
              </button>
            ))}
          </nav>
        </aside>

        <div className={styles.workspace} inert={drawer}>
          <header className={styles.toolbar}>
            <h2>{chosen.length ? `${chosen.length}개 선택됨` : current.label}</h2>
            <button
              type="button"
              className={styles.iconButton}
              aria-label={searching ? "검색 닫기" : "파일 검색"}
              aria-expanded={searching}
              onClick={() => {
                setSearching(!searching);
                setQuery("");
                if (!searching) requestAnimationFrame(() => search.current?.focus());
              }}
            >
              <Icon name="search" />
            </button>
            <button
              type="button"
              className={styles.iconButton}
              aria-label={view === "grid" ? "목록 보기" : "그리드 보기"}
              onClick={() => setView(view === "grid" ? "list" : "grid")}
            >
              <Icon name={view === "grid" ? "list" : "grid"} />
            </button>
          </header>

          {searching && (
            <div className={styles.search}>
              <Icon name="search" />
              <input
                ref={search}
                aria-label="파일 이름 또는 거래처 검색"
                placeholder="파일 검색"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
              />
              {query && (
                <button
                  type="button"
                  className={styles.iconButton}
                  aria-label="검색어 지우기"
                  onClick={() => {
                    setQuery("");
                    search.current?.focus();
                  }}
                >
                  <Icon name="close" />
                </button>
              )}
            </div>
          )}

          <div className={styles.pathbar}>
            <button
              ref={menu}
              type="button"
              className={`${styles.iconButton} ${styles.menuButton}`}
              aria-label="탐색 메뉴 열기"
              aria-expanded={drawer}
              aria-controls={`${id}-locations`}
              onClick={() => setDrawer(true)}
            >
              <Icon name="menu" />
            </button>
            <span>{location === "internal" ? "내장 저장공간 › Download" : current.label}</span>
            <span className={styles.count}>{visible.length}개 파일</span>
          </div>
          <div className={styles.content}>
            {visible.length ? (
              <div
                className={view === "grid" ? styles.grid : styles.list}
                role="group"
                aria-label="거래명세서 파일"
              >
                {visible.map((document) => {
                  const checked = selected.includes(document.id);
                  return (
                    <div key={document.id} className={styles.file} data-selected={checked}>
                      <button
                        type="button"
                        className={styles.fileButton}
                        role="checkbox"
                        aria-label={document.filename}
                        aria-checked={checked}
                        onClick={() =>
                          setSelected((previous) =>
                            previous.includes(document.id)
                              ? previous.filter((item) => item !== document.id)
                              : [...previous, document.id],
                          )
                        }
                      >
                        <span className={styles.selection}>{checked && <Icon name="check" />}</span>
                      </button>
                      {view === "grid" ? (
                        <StatementThumbnail document={document} />
                      ) : (
                        <span className={styles.pdfIcon} aria-hidden="true">
                          <Icon name="document" />
                          <small>PDF</small>
                        </span>
                      )}
                      <div className={styles.fileInfo}>
                        <strong>{document.filename}</strong>
                        <span className={styles.supplier}>{document.supplierName}</span>
                        <span className={styles.fileDetail}>
                          PDF · {document.date.replaceAll("-", ".")}
                        </span>
                      </div>
                    </div>
                  );
                })}
              </div>
            ) : (
              <p className={styles.empty}>{needle ? "검색 결과 없음" : "문서 없음"}</p>
            )}
          </div>
          <div className={styles.selectionStatus} aria-live="polite">
            <span>{chosen.length ? `${chosen.length}개 파일` : "파일을 선택하세요"}</span>
            <button type="button" className={styles.cancel} aria-label="문서 선택 취소" onClick={onClose}>취소</button>
            <button type="button" className={styles.done} disabled={chosen.length === 0} onClick={() => onComplete(chosen)}>완료</button>
          </div>
        </div>
      </div>
    </dialog>
  );
}
