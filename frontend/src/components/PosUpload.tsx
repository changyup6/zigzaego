"use client";

import { useState } from "react";
import { Card } from "./Card";
import { FileButton } from "./FileButton";
import styles from "./PosUpload.module.css";

// 시안 #mapSec: 엑셀 올리기. 메뉴 연결은 뺐다 (DECISIONS #80).
// 파일 선택까지만 시연한다. 매출 업로드 API와 연결하지 않는다.
export function PosUpload() {
  const [filename, setFilename] = useState<string | null>(null);
  return (
    <Card
      id="pos"
      className={styles.anchor}
      title="POS 업로드"
      aside={
        <FileButton accept=".xlsx,.xls,.csv" small onFile={(file) => setFilename(file.name)}>
          <svg className={styles.ic} viewBox="0 0 24 24" aria-hidden="true">
            <path d="M12 16V4M7 9l5-5 5 5M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3" />
          </svg>
          엑셀 올리기
        </FileButton>
      }
    >
      {filename && <p role="status">{filename} · 선택됨</p>}
    </Card>
  );
}
