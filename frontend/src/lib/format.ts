// 표시만 천 단위 구분. 수량의 기존 소수 첫째 자리 반올림 규칙은 유지한다.
export const fmt = (n: number) => {
  const rounded = Math.round(n * 10) / 10;
  return (rounded === 0 ? 0 : rounded).toLocaleString("ko-KR", { maximumFractionDigits: 1 });
};

export const won = (n: number) => `${Math.round(n).toLocaleString("ko-KR")}원`;
