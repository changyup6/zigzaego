"use client";

import { useRouter } from "next/navigation";
import { logout } from "@/lib/api";
import { Button } from "./Button";

// POST /auth/logout 으로 refresh token 을 무효로 하고 로그인 화면으로.
export function LogoutButton() {
  const router = useRouter();
  return (
    <Button
      type="button"
      variant="quiet"
      onClick={async () => {
        await logout();
        router.replace("/login");
      }}
    >
      로그아웃
    </Button>
  );
}
