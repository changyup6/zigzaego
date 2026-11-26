// 홈 발주 카드 → 발주 탭 화면 전환 (S15P31A308-265).
// 같은 view-transition-name 을 단 카드 · 품목명 · 수량 · 매출 카드가 제자리로 이어진다.
// 발주 탭이 그려지면(추천 목록까지) ready() 를 불러 전환을 끝낸다. 지원 안 하는 브라우저는 그냥 이동

let done: (() => void) | null = null;
let fromHome = false;

export function goWithTransition(push: () => void) {
  fromHome = true;
  const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (!document.startViewTransition || reduce) return push();
  document.startViewTransition(
    () =>
      new Promise<void>((resolve) => {
        done = resolve;
        push();
        // 발주 탭을 못 그려도 4초 안에 전환을 끝낸다 (브라우저 제한보다 짧게)
        setTimeout(resolve, 3000);
      }),
  );
}

export function transitionReady() {
  done?.();
  done = null;
}

// 홈에서 넘어왔으면 추천 품목을 추천 주문처로 담아 둔다. 한 번 읽으면 지운다
export function takeFromHome() {
  const v = fromHome;
  fromHome = false;
  return v;
}

export const vt = (name: string) => ({ viewTransitionName: name });
