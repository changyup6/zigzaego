import type { ReactNode } from "react";
import { FlowShell } from "@/components/FlowShell";

export default function FlowLayout({ children }: { children: ReactNode }) {
  return <FlowShell>{children}</FlowShell>;
}
