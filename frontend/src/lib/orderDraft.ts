export const draftKey = (store: string, today: string, ids: string[]) =>
  JSON.stringify([store, today, [...ids].sort()]);
