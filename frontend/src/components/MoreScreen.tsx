"use client";

import { useStore } from "@/hooks/useStore";
import { setupSteps } from "@/lib/setup";
import { Card } from "./Card";
import { LogoutButton } from "./LogoutButton";
import { PosUpload } from "./PosUpload";
import { PriceCompareSwitch } from "./PriceCompareSwitch";
import { Screen } from "./Screen";
import { SetupChecklist } from "./SetupChecklist";
import styles from "./MoreScreen.module.css";

// 시안 app_all.html 더보기: POS 업로드 | 가게 정보 · 가격 비교 · 가게 설정(어두운 카드) · 로그아웃.
// 가게 정보 · 가게 설정은 GET /stores/me. 가게 정보는 보기만 한다
export function MoreScreen() {
  const store = useStore();
  return (
    <Screen title="더보기">
      <div className={styles.more}>
        <PosUpload />
        <div className={styles.side}>
          {store && (
            <Card title="가게 정보">
              <dl className={styles.info}>
                {[
                  ["상호명", store.name],
                  ["화면 이름", store.displayName],
                  ["위치", store.address?.trim() || store.sigungu],
                ].map(([k, v]) => (
                  <div key={k}>
                    <dt>{k}</dt>
                    <dd>{v || "–"}</dd>
                  </div>
                ))}
              </dl>
            </Card>
          )}
          {store && <PriceCompareSwitch on={store.priceCompare ?? true} />}
          {store && <SetupChecklist steps={setupSteps(store)} />}
          <div className={styles.end}>
            <LogoutButton />
          </div>
        </div>
      </div>
    </Screen>
  );
}
