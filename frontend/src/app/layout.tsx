import type { Metadata, Viewport } from "next";
import { Jua, Lexend, Noto_Sans_KR } from "next/font/google";
import "@/styles/tokens.css";
import "./globals.css";

// 본문은 Noto Sans KR, 숫자는 Lexend. 스택 순서는 tokens.css 의 --font · --numeral.
const noto = Noto_Sans_KR({
  subsets: ["latin"],
  variable: "--font-noto",
  display: "swap",
});

const lexend = Lexend({
  subsets: ["latin"],
  weight: ["200", "300", "400", "500"],
  variable: "--font-lexend",
  display: "swap",
});

// 그림 글씨 (거래처 간판 · 재료 그림 이름). 로고 손글씨와 비슷한 둥근 글꼴
const jua = Jua({
  subsets: ["latin"],
  weight: "400",
  variable: "--font-jua",
  display: "swap",
});

export const metadata: Metadata = {
  title: "지그재고",
  description: "오늘의 발주를 추천하고 승인하는 운영 화면",
};

export const viewport: Viewport = {
  colorScheme: "light",
  themeColor: "#f3f1ec",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="ko" className={`${noto.variable} ${lexend.variable} ${jua.variable}`}>
      <body>{children}</body>
    </html>
  );
}
