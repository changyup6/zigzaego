import Link from "next/link";
import styles from "./Screen.module.css";

export const ButtonLink = ({
  href,
  ghost,
  children,
}: {
  href: string;
  ghost?: boolean;
  children: string;
}) => (
  <Link href={href} className={ghost ? `${styles.link} ${styles.ghost}` : styles.link}>
    {children}
  </Link>
);
