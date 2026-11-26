// 데모 빌드에서만 /dev 화면과 ?demo 목업을 연다. NEXT_PUBLIC_* 는 빌드 때 박힌다
export const DEMO_BUILD = process.env.NEXT_PUBLIC_DEMO === "1";
