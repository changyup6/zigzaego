import Link from "next/link";
import { buttonClass } from "@/components/Button";
import { ReceiptBlock } from "@/components/ReceiptBlock";
import { OnboardingStep } from "@/components/OnboardingStep";
import { Screen } from "@/components/Screen";
import styles from "@/components/ReceiptBlock.module.css";

// 온보딩 마지막 단계: 지난 영수증 (DECISIONS #66). 올리지 않고 넘어가도 된다
export default function Page() {
  return (
    <Screen title="지난 영수증">
      <OnboardingStep />
      <ReceiptBlock />
      <div className={styles.end}>
        <Link href="/" className={buttonClass("primary")}>
          완료
        </Link>
      </div>
    </Screen>
  );
}
