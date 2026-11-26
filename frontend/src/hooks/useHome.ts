"use client";

import { useEffect, useState } from "react";
import { getReceiving } from "@/lib/api";
import { homeIncoming } from "@/lib/homeIncoming";

export type HomeItem = ReturnType<typeof homeIncoming>[number];
export type Home = { incoming: HomeItem[] };

// 홈 입고 카드 (DECISIONS #61). 발주 카드는 발주 탭과 같은 useOrderFeed 를 쓴다
export function useHome() {
  const [home, setHome] = useState<Home | null>(null);
  const [error, setError] = useState(false);
  useEffect(() => {
    let live = true;
    getReceiving().then((v) => {
      if (!live) return;
      if (!v.success) return setError(true);
      setHome({
        incoming: homeIncoming(v.data.incoming),
      });
    });
    return () => {
      live = false;
    };
  }, []);
  return { home, error };
}
