"use client";

import { useState } from "react";
import { NO_TERMS, allAgreed, setAll, type TermKey } from "@/lib/terms";

export function useTermsAgreement() {
  const [terms, setTerms] = useState(NO_TERMS);
  return {
    terms,
    all: allAgreed(terms),
    toggle: (k: TermKey) => setTerms((t) => ({ ...t, [k]: !t[k] })),
    toggleAll: () => setTerms((t) => setAll(!allAgreed(t))),
  };
}
