"use client";

import { useState } from "react";
import { BottomSheet } from "@/components/BottomSheet";
import { ActionBar } from "@/components/ActionBar";
import { Button } from "@/components/Button";
import { Card } from "@/components/Card";
import { Checkbox } from "@/components/Checkbox";
import { Chip, Tag } from "@/components/Chip";
import { Field, FieldGrid } from "@/components/Field";
import { FileButton } from "@/components/FileButton";
import { Num } from "@/components/Num";
import { ProgressBar } from "@/components/ProgressBar";
import { QtyStepper } from "@/components/QtyStepper";
import { StatusBadge } from "@/components/StatusBadge";
import { Switch } from "@/components/Switch";
import { useToast } from "@/components/Toast";
import styles from "./page.module.css";

const NUM_SIZES = [40, 24, 17, 14] as const;

export default function ComponentsDemo() {
  const [on, setOn] = useState(true);
  const [qty, setQty] = useState(24);
  const [sheet, setSheet] = useState(false);
  const [sw, setSw] = useState(true);
  const [file, setFile] = useState("");
  const [agree, setAgree] = useState(false);
  const { toast, show } = useToast();
  return (
    <main className={styles.page}>
      <h1>컴포넌트</h1>

      <section className={styles.block}>
        <h2>숫자</h2>
        {NUM_SIZES.map((n) => (
          <Num key={n} size={n} value="1,240" unit="kg" />
        ))}
      </section>

      <section className={styles.block}>
        <h2>버튼</h2>
        <div className={styles.line}>
          <Button>승인</Button>
          <Button variant="ghost">보류</Button>
          <Button variant="paper">올리기</Button>
          <Button variant="quiet">취소</Button>
          <Button small variant="ghost">
            복사
          </Button>
          <Button disabled>비활성</Button>
        </div>
      </section>

      <section className={styles.block}>
        <h2>수량 스테퍼</h2>
        <QtyStepper
          value={qty}
          onChange={setQty}
          pack={12}
          packLabel="박스"
          unit="개"
          unitPrice={500}
          max={60}
        />
      </section>

      <section className={styles.block}>
        <h2>상태 글자</h2>
        <div className={styles.line}>
          <StatusBadge tone="red">발주 필요</StatusBadge>
          <StatusBadge tone="amber">곧 소진</StatusBadge>
          <StatusBadge tone="ok">승인 25 kg</StatusBadge>
          <StatusBadge tone="muted">보류</StatusBadge>
        </div>
      </section>

      <section className={styles.block}>
        <h2>진행 바</h2>
        <ProgressBar label="재고" value={0.6} />
        <ProgressBar label="부족" value={0.25} short />
      </section>

      <section className={styles.block}>
        <h2>바텀 시트</h2>
        <Button variant="ghost" onClick={() => setSheet(true)}>
          열기
        </Button>
        <BottomSheet open={sheet} title="주문 메시지" onClose={() => setSheet(false)}>
          <Button variant="ghost" block onClick={() => setSheet(false)}>
            완료
          </Button>
        </BottomSheet>
      </section>

      <section className={styles.block}>
        <h2>스위치</h2>
        <Card className={styles.wide}>
          <Switch
            label="식봄, 쿠팡 가격 비교"
            checked={sw}
            onChange={(e) => setSw(e.target.checked)}
          />
        </Card>
      </section>

      <section className={styles.block}>
        <h2>파일 버튼</h2>
        <div className={styles.line}>
          <FileButton accept="image/*" small onFile={(f) => setFile(f.name)}>
            영수증 올리기
          </FileButton>
          <FileButton accept="image/*" variant="paper" onFile={(f) => setFile(f.name)}>
            레시피 파일 올리기
          </FileButton>
          {file}
        </div>
      </section>

      <section className={styles.block}>
        <h2>칩 · 태그</h2>
        <div className={styles.line}>
          <Chip selected={on} onClick={() => setOn(!on)}>
            전체
          </Chip>
          <Chip selected={!on} onClick={() => setOn(!on)}>
            냉장
          </Chip>
          <span>
            양파<Tag>도착</Tag>
          </span>
        </div>
        <Card>
          <div className={styles.line}>
            <Chip selected={on} onClick={() => setOn(!on)}>
              가져오기
            </Chip>
            <Chip selected={!on} onClick={() => setOn(!on)}>
              제외
            </Chip>
          </div>
        </Card>
      </section>

      <section className={styles.block}>
        <h2>입력 · 셀렉트</h2>
        <Card className={styles.wide}>
          <FieldGrid>
            <Field label="안전재고 (일)">
              <input type="number" inputMode="decimal" min="0" step="0.5" placeholder="2" />
            </Field>
            <Field label="단위">
              <select>
                <option>g</option>
                <option>ml</option>
                <option>개</option>
              </select>
            </Field>
          </FieldGrid>
        </Card>
      </section>

      <section className={styles.block}>
        <h2>체크박스</h2>
        <Card className={styles.wide}>
          <Checkbox checked={agree} onChange={(e) => setAgree(e.target.checked)}>
            서비스 이용약관
          </Checkbox>
        </Card>
      </section>

      <section className={styles.block}>
        <h2>토스트</h2>
        <Button variant="ghost" onClick={() => show("복사됨")}>
          복사
        </Button>
        {toast}
      </section>

      <section className={styles.block}>
        <h2>하단 액션 바</h2>
        <ActionBar>모두 발주</ActionBar>
      </section>
    </main>
  );
}
