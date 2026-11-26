"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { startTransition, useActionState } from "react";
import { useTermsAgreement } from "@/hooks/useTermsAgreement";
import { isOnboarded } from "@/hooks/useStore";
import { getStoreMe, login, signup as postSignup } from "@/lib/api";
import { TERMS } from "@/lib/terms";
import { Button, buttonClass } from "./Button";
import { Card } from "./Card";
import { Checkbox } from "./Checkbox";
import { Field } from "./Field";
import styles from "./AuthForm.module.css";

// 로그인: POST /auth/login 후 GET /stores/me 로 갈 곳(온보딩 · 홈)을 정한다.
// 가입: POST /auth/signup 이 가게까지 만들므로 바로 온보딩. 토큰이 메모리에 있어 클라이언트에서 부른다.
type Props = { mode: "login" | "signup" };

const text = (form: FormData, key: string) => String(form.get(key) ?? "");

// 시안 #login · #signup. 가입은 필수 약관 셋을 모두 켜야 다음 (DECISIONS #46).
export function AuthForm(props: Props) {
  const signup = props.mode === "signup";
  const router = useRouter();
  // 실패 문구. 성공하면 null
  const [failed, submit, pending] = useActionState(async (_: string | null, form: FormData) => {
    const c = { email: text(form, "email"), password: text(form, "password") };
    if (signup) {
      const r = await postSignup(c);
      if (!r.success)
        return r.error.code === "AUTH_EMAIL_TAKEN" ? "이미 가입된 이메일" : "가입 실패";
      router.replace("/onboarding/find");
      return null;
    }
    const r = await login(c);
    if (!r.success) return "로그인 실패";
    const me = await getStoreMe();
    if (!me.data) return "로그인 실패";
    router.replace(isOnboarded(me.data) ? "/" : "/onboarding/find");
    return null;
  }, null);
  const { terms, all, toggle, toggleAll } = useTermsAgreement();
  return (
    <section className={styles.login}>
      <div className={styles.mark}>
        <h1>환영합니다</h1>
      </div>
      {/* action 은 하이드레이션 전 기본 제출(GET ?password=)을 막는다.
          제출은 onSubmit 이 맡아 실패해도 React가 입력을 비우지 않는다 */}
      <form
        action={submit}
        onSubmit={(e) => {
          e.preventDefault();
          const form = new FormData(e.currentTarget);
          startTransition(() => submit(form));
        }}
      >
        <Card className={styles.block}>
          <Field label="이메일">
            <input type="email" name="email" autoComplete="username" required />
          </Field>
          <Field label="비밀번호">
            <input
              type="password"
              name="password"
              autoComplete={signup ? "new-password" : "current-password"}
              minLength={signup ? 8 : undefined}
              required
            />
          </Field>
          {signup && (
            <div className={styles.terms}>
              <Checkbox className={styles.all} checked={all} onChange={toggleAll}>
                전체 동의
              </Checkbox>
              {TERMS.map((t) => (
                <Checkbox
                  key={t.key}
                  required
                  checked={terms[t.key]}
                  onChange={() => toggle(t.key)}
                >
                  {t.label}
                  <span className={styles.req}>필수</span>
                </Checkbox>
              ))}
            </div>
          )}
          {failed && (
            <p className={styles.fail} role="alert">
              {failed}
            </p>
          )}
          <Button type="submit" block disabled={pending || (signup && !all)}>
            {signup ? "다음" : "로그인"}
          </Button>
        </Card>
      </form>
      <div className={styles.alt}>
        <Link href={signup ? "/login" : "/signup"} className={buttonClass("quiet")}>
          {signup ? "로그인" : "회원가입"}
        </Link>
      </div>
    </section>
  );
}
