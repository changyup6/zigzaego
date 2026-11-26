import { Suspense } from "react";
import { AnalysisProgress } from "@/components/AnalysisProgress";
import { OnboardingStep } from "@/components/OnboardingStep";
import { Screen } from "@/components/Screen";

async function Progress({ searchParams }: { searchParams: Promise<{ job?: string }> }) {
  const { job } = await searchParams;
  return <AnalysisProgress jobId={job ?? null} />;
}

export default function Page({ searchParams }: { searchParams: Promise<{ job?: string }> }) {
  return (
    <Screen title="메뉴 분석">
      <OnboardingStep />
      <Suspense>
        <Progress searchParams={searchParams} />
      </Suspense>
    </Screen>
  );
}
