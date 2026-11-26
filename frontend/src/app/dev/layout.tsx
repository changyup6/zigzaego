import type { ReactNode } from "react";
import { notFound } from "next/navigation";
import { DEMO_BUILD } from "@/lib/demo";

export default function DevLayout({ children }: { children: ReactNode }) {
  if (!DEMO_BUILD) notFound();
  return children;
}
