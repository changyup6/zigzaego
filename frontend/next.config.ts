import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // create-next-app 16.4 기본값. 동적 데이터는 Suspense 경계 안에서 읽는다 (node_modules/next/dist/docs 참조).
  cacheComponents: true,
  partialPrefetching: true,
  // 모노레포 루트에도 package-lock.json 이 있어(husky) Turbopack 이 루트를 헷갈린다. 이 폴더로 고정.
  turbopack: { root: __dirname },
};

export default nextConfig;
