import type { ClosingTime } from "./types";

export const DAYS = ["월", "화", "수", "목", "금", "토", "일"];

// 고른 요일 전부에 같은 마감 시각 (DECISIONS #88). 고르지 않은 요일은 줄이 없다 = 휴무
// dayOfWeek 는 월 = 1 … 일 = 7. 시각이 HH:mm 이 아니면 빈 목록
export const toClosingTimes = (time: string, open: boolean[]): ClosingTime[] =>
  /^([01]\d|2[0-3]):[0-5]\d$/.test(time)
    ? open.flatMap((on, i) => (on ? [{ dayOfWeek: i + 1, closeTime: time }] : []))
    : [];
