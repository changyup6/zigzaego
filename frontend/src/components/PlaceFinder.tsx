"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { usePlaceSearch } from "@/hooks/usePlaceSearch";
import { ActionBar } from "./ActionBar";
import { Button } from "./Button";
import { Card } from "./Card";
import { Chip } from "./Chip";
import { Skeleton, SK_W } from "./Skeleton";
import styles from "./PlaceFinder.module.css";

// 시안 #find: 검색 카드(상호명 → 후보, 없으면 네이버 지도 링크) → 요기요 카드 → 하단 '다음'
export function PlaceFinder() {
  const router = useRouter();
  const p = usePlaceSearch();
  const [query, setQuery] = useState("");
  const [link, setLink] = useState("");
  const [linkOpen, setLinkOpen] = useState(false);
  const [busy, setBusy] = useState(false);

  // 후보 0개면 링크 입력이 펼쳐진다
  const showLink = linkOpen || (!p.searching && p.searched && p.candidates.length === 0);
  const ready = !p.searching && (p.selected !== null || link.trim() !== "");

  const go = async () => {
    setBusy(true);
    const id = await p.start(link);
    setBusy(false);
    if (id) router.push(`/onboarding/analyze?job=${encodeURIComponent(id)}`);
  };

  return (
    <>
      {p.error && (
        <p role="alert" className={styles.error}>
          {p.error.message}
        </p>
      )}
      <Card>
        <form
          className={styles.search}
          role="search"
          onSubmit={(e) => {
            e.preventDefault();
            p.search(query);
          }}
        >
          <input
            type="search"
            aria-label="상호명"
            placeholder="상호명"
            autoComplete="off"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          <Button type="submit" variant="ghost" disabled={p.searching}>
            {p.searching ? "검색 중" : "검색"}
          </Button>
        </form>

        <div className={styles.cands} aria-busy={p.searching}>
          {p.searching &&
            SK_W.slice(0, 3).map((w) => (
              <div key={w} className={styles.cand}>
                <span>
                  <Skeleton w={w} h="1.1em" />
                  <span className={styles.addr}>
                    <Skeleton w="72%" h="0.9em" />
                  </span>
                </span>
              </div>
            ))}
          {!p.searching &&
            p.candidates.map((c) => (
              <button
                key={c.id}
                type="button"
                className={styles.cand}
                aria-pressed={p.selected?.id === c.id}
                onClick={() => p.select(c)}
              >
                <span>
                  <span className={styles.name}>{c.name}</span>
                  <span className={styles.addr}>{c.address}</span>
                </span>
                <svg className={styles.ic} viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M5 12l5 5L20 7" />
                </svg>
              </button>
            ))}
          {!p.searching && p.searched && p.candidates.length === 0 && (
            <p className={styles.empty}>검색 결과 없음</p>
          )}
        </div>

        {showLink ? (
          <input
            className={styles.linkin}
            type="url"
            inputMode="url"
            aria-label="네이버 지도 링크"
            placeholder="naver.me/…"
            value={link}
            onChange={(e) => setLink(e.target.value)}
          />
        ) : (
          <Button variant="quiet" className={styles.linkOpen} onClick={() => setLinkOpen(true)}>
            네이버 지도 링크로 찾기
          </Button>
        )}
      </Card>

      {p.selected?.yogiyoMatch && (
        <Card>
          <div className={styles.ask}>
            <b>요기요 메뉴도 함께 가져올까요?</b>
            <div className={styles.chips} role="group" aria-label="요기요 메뉴">
              <Chip selected={p.includeYogiyo} onClick={() => p.setIncludeYogiyo(true)}>
                가져오기
              </Chip>
              <Chip selected={!p.includeYogiyo} onClick={() => p.setIncludeYogiyo(false)}>
                제외
              </Chip>
            </div>
          </div>
        </Card>
      )}

      {ready && (
        <ActionBar disabled={busy} onClick={go}>
          다음
        </ActionBar>
      )}
    </>
  );
}
