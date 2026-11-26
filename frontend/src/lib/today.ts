// 가게의 오늘 (YYYY-MM-DD). GET /stores/me 의 today 를 받으면 그 값, 받기 전에는 기기의 서울 날짜.
let serverToday: string | null = null;

export const setToday = (ymd: string | undefined) => {
  serverToday = ymd ?? null;
};

export const todayYmd = () =>
  serverToday ?? new Intl.DateTimeFormat("sv-SE", { timeZone: "Asia/Seoul" }).format(new Date());
