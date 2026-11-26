const KEY = "recipeToast";

export const recipeToastText = (estimated: number) =>
  estimated > 0 ? `추정값 ${estimated}개` : "레시피 확인 완료";

// 홈으로 한 번만 넘기는 신호: 읽으면 지운다 (새로고침에 다시 뜨지 않는다)
export const setRecipeToast = (estimated: number) =>
  sessionStorage.setItem(KEY, recipeToastText(estimated));

export function takeRecipeToast() {
  const text = sessionStorage.getItem(KEY);
  if (text) sessionStorage.removeItem(KEY);
  return text;
}
