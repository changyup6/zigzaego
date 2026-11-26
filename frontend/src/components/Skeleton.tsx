import styles from "./Skeleton.module.css";

type Size = number | string;

// 불러오는 동안 글자 · 숫자 · 버튼 · 그림 자리에 놓는 막대 (S15P31A308-266). 글자는 넣지 않는다.
// ratio 는 그림처럼 폭에 따라 높이가 정해질 때
export function Skeleton({
  w,
  h = "1em",
  r,
  ratio,
}: {
  w?: Size;
  h?: Size;
  r?: string;
  ratio?: string;
}) {
  return (
    <span
      aria-hidden="true"
      className={styles.sk}
      style={{ width: w, height: ratio ? "auto" : h, borderRadius: r, aspectRatio: ratio }}
    />
  );
}

// 화면 제목 자리
export const SkTitle = ({ w = "2.6em" }: { w?: Size }) => (
  <Skeleton w={w} h="0.9em" r="var(--r-input)" />
);

// 검정 · 빨강 카드 대신 옅은 판 (SK_PAD 를 같이 주면 안쪽 여백 · 줄 간격까지)
export const SK_BOX = styles.box;
export const SK_PAD = styles.pad;

// 목록 줄마다 길이를 달리해 실제 목록처럼 보이게
export const SK_W = ["58%", "42%", "50%", "36%", "46%"];
