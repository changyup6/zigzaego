// 합성 가격의 placeholder는 열지 않는다. 실상품 URL도 가격 검증과 별개의 참조다.
export function productReference(value?: string): string | null {
  if (!value) return null;
  try {
    const url = new URL(value);
    return url.protocol === "https:" &&
      !url.username &&
      !url.password &&
      url.hostname !== "invalid" &&
      !url.hostname.endsWith(".invalid")
      ? url.href
      : null;
  } catch {
    return null;
  }
}
