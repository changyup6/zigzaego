"use client";

import { useState } from "react";
import { searchPlaces, startOnboardingJob } from "@/lib/api";
import type { ApiError, PlaceCandidate } from "@/lib/types";

export function usePlaceSearch() {
  const [candidates, setCandidates] = useState<PlaceCandidate[]>([]);
  const [searched, setSearched] = useState(false);
  const [searching, setSearching] = useState(false);
  const [selected, setSelected] = useState<PlaceCandidate | null>(null);
  const [includeYogiyo, setIncludeYogiyo] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  const search = async (query: string) => {
    const q = query.trim();
    if (!q || searching) return;
    setSearching(true);
    const r = await searchPlaces(q).finally(() => setSearching(false));
    setError(r.success ? null : r.error);
    setCandidates(r.success ? r.data : []);
    setSelected(null);
    setIncludeYogiyo(false);
    setSearched(true);
  };

  // 시안: 다시 누르면 선택 해제. 요기요 매칭이 있으면 '가져오기'가 기본
  const select = (c: PlaceCandidate) => {
    setSelected((cur) => (cur?.id === c.id ? null : c));
    setIncludeYogiyo(true);
  };

  // 성공하면 job id, 실패하면 null
  const start = async (naverMapUrl: string) => {
    const link = naverMapUrl.trim();
    const r = await startOnboardingJob({
      ...(selected
        ? { placeId: selected.id, placeName: selected.name }
        : link
          ? { naverMapUrl: link }
          : {}),
      includeYogiyo: selected?.yogiyoMatch ? includeYogiyo : false,
    });
    setError(r.success ? null : r.error);
    return r.success ? r.data.id : null;
  };

  return {
    error,
    candidates,
    searched,
    searching,
    selected,
    includeYogiyo,
    setIncludeYogiyo,
    search,
    select,
    start,
  };
}
