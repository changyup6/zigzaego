import type { ButtonHTMLAttributes } from "react";
import styles from "./Button.module.css";

export type ButtonVariant = "primary" | "ghost" | "paper" | "quiet";

/** 버튼 모양. `<label>` 같은 다른 요소에도 쓴다 */
export const buttonClass = (
  variant: ButtonVariant,
  opts: { small?: boolean; block?: boolean; className?: string } = {},
) =>
  [styles.btn, styles[variant], opts.small && styles.sm, opts.block && styles.block, opts.className]
    .filter(Boolean)
    .join(" ");

type Props = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: ButtonVariant;
  small?: boolean;
  block?: boolean;
};

export function Button({
  variant = "primary",
  small,
  block,
  type = "button",
  className,
  ...rest
}: Props) {
  return (
    <button type={type} className={buttonClass(variant, { small, block, className })} {...rest} />
  );
}
