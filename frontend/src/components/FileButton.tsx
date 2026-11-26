import type { ReactNode } from "react";
import { buttonClass, type ButtonVariant } from "./Button";
import styles from "./FileButton.module.css";

// 파일 올리기는 드롭존이 아니라 버튼이다. 형식 안내 글자를 붙이지 않는다.
export function FileButton({
  accept,
  variant = "ghost",
  small,
  block,
  disabled,
  multiple,
  onFile,
  onFiles,
  children,
}: {
  accept: string;
  variant?: ButtonVariant;
  small?: boolean;
  block?: boolean;
  disabled?: boolean;
  multiple?: boolean;
  onFile?: (file: File) => void;
  onFiles?: (files: File[]) => void;
  children: ReactNode;
}) {
  return (
    <label className={buttonClass(variant, { small, block, className: styles.file })}>
      {children}
      <input
        type="file"
        accept={accept}
        multiple={multiple}
        disabled={disabled}
        onChange={(e) => {
          const fs = [...(e.target.files ?? [])];
          if (fs.length) {
            onFiles?.(fs);
            onFile?.(fs[0]);
          }
          e.target.value = "";
        }}
      />
    </label>
  );
}
