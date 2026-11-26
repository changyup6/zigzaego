// 회원가입 약관 (DECISIONS #45 · #46): 셋 다 필수
export const TERMS = [
  { key: "service", label: "서비스 이용약관" },
  { key: "privacy", label: "개인정보 수집 · 이용" },
  { key: "menu", label: "메뉴 · 메뉴 사진 수집" },
] as const;

export type TermKey = (typeof TERMS)[number]["key"];
export type TermsState = Record<TermKey, boolean>;

export const NO_TERMS: TermsState = { service: false, privacy: false, menu: false };

export const allAgreed = (t: TermsState) => TERMS.every((x) => t[x.key]);

// 전체 동의: 셋을 함께 켜고 끈다
export const setAll = (on: boolean): TermsState => ({ service: on, privacy: on, menu: on });
