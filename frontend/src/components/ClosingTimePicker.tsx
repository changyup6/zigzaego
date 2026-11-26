"use client";

import { useEffect, useId, useRef, useState, type RefObject } from "react";
import styles from "./ClosingTimePicker.module.css";

const HOURS = Array.from({ length: 24 }, (_, i) => String(i).padStart(2, "0"));
const MINUTES = Array.from({ length: 60 }, (_, i) => String(i).padStart(2, "0"));

function TimeColumn({
  label,
  values,
  value,
  listRef,
  onSelect,
  onAcross,
}: {
  label: string;
  values: string[];
  value: string;
  listRef: RefObject<HTMLDivElement | null>;
  onSelect: (value: string) => void;
  onAcross: () => void;
}) {
  const id = useId();
  const [active, setActive] = useState(Math.max(0, values.indexOf(value)));
  useEffect(() => {
    const option = listRef.current?.children[active] as HTMLElement | undefined;
    if (option && listRef.current) {
      listRef.current.scrollTop = option.offsetTop - 2 * option.offsetHeight;
    }
  }, [active, listRef]);

  return (
    <div className={styles.column}>
      <span id={`${id}-label`} className={styles.unit}>
        {label}
      </span>
      <div
        ref={listRef}
        className={styles.list}
        role="listbox"
        tabIndex={0}
        aria-labelledby={`${id}-label`}
        aria-activedescendant={`${id}-${active}`}
        onKeyDown={(event) => {
          const move = { ArrowDown: 1, ArrowUp: -1, PageDown: 5, PageUp: -5 }[event.key];
          if (move !== undefined) {
            event.preventDefault();
            setActive((index) => Math.max(0, Math.min(values.length - 1, index + move)));
          } else if (event.key === "Home" || event.key === "End") {
            event.preventDefault();
            setActive(event.key === "Home" ? 0 : values.length - 1);
          } else if (event.key === "Enter" || event.key === " ") {
            event.preventDefault();
            onSelect(values[active]);
          } else if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
            event.preventDefault();
            onAcross();
          }
        }}
      >
        {values.map((item, index) => (
          <div
            id={`${id}-${index}`}
            key={item}
            role="option"
            aria-label={`${item}${label}`}
            aria-selected={value === item}
            data-active={active === index}
            className={styles.option}
            onPointerDown={(event) => event.preventDefault()}
            onClick={() => {
              setActive(index);
              onSelect(item);
            }}
          >
            <span className="num">{item}</span>
            <span>{label}</span>
            {value === item && (
              <span aria-hidden="true" className={styles.check}>
                ✓
              </span>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

// 브라우저 기본 팝업 대신 같은 입력 토큰을 쓰며, 직접 입력한 HH:mm도 그대로 검증한다.
export function ClosingTimePicker({
  value,
  onChange,
}: {
  value: string;
  onChange: (value: string) => void;
}) {
  const id = useId();
  const root = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const hours = useRef<HTMLDivElement>(null);
  const minutes = useRef<HTMLDivElement>(null);
  const [expanded, setExpanded] = useState(false);
  const [rawHour, rawMinute] = value.split(":");
  const hour = HOURS.includes(rawHour) ? rawHour : "00";
  const minute = MINUTES.includes(rawMinute) ? rawMinute : "00";

  useEffect(() => {
    if (!expanded) return;
    const outside = (event: PointerEvent) => {
      if (!root.current?.contains(event.target as Node)) setExpanded(false);
    };
    document.addEventListener("pointerdown", outside);
    return () => document.removeEventListener("pointerdown", outside);
  }, [expanded]);

  const close = () => {
    setExpanded(false);
    input.current?.focus();
  };
  const show = (focusList: boolean) => {
    setExpanded(true);
    if (focusList) requestAnimationFrame(() => hours.current?.focus());
  };

  return (
    <div
      ref={root}
      className={styles.field}
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) setExpanded(false);
      }}
      onKeyDown={(event) => {
        if (event.key === "Escape" && expanded) {
          event.preventDefault();
          event.stopPropagation();
          close();
        }
      }}
    >
      <label htmlFor={id}>마감 시각</label>
      <div className={styles.control} data-expanded={expanded}>
        <input
          ref={input}
          id={id}
          type="text"
          role="combobox"
          aria-expanded={expanded}
          aria-haspopup="dialog"
          aria-controls={expanded ? `${id}-popup` : undefined}
          autoComplete="off"
          spellCheck={false}
          placeholder="--:--"
          maxLength={5}
          className="num n17"
          value={value}
          onChange={(event) => onChange(event.target.value)}
          onClick={() => show(false)}
          onKeyDown={(event) => {
            if (event.key === "ArrowDown" || event.key === "ArrowUp") {
              event.preventDefault();
              show(true);
            }
          }}
        />
        <button
          type="button"
          className={styles.toggle}
          aria-label="마감 시각 선택"
          aria-haspopup="dialog"
          aria-expanded={expanded}
          aria-controls={expanded ? `${id}-popup` : undefined}
          onClick={() => (expanded ? close() : show(true))}
        >
          <svg
            width="18"
            height="18"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.5"
            aria-hidden="true"
          >
            <path d="m6 9 6 6 6-6" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </button>
      </div>
      {expanded && (
        <div id={`${id}-popup`} role="dialog" aria-label="마감 시각 선택" className={styles.popup}>
          <TimeColumn
            key={`hour-${hour}`}
            label="시"
            values={HOURS}
            value={hour}
            listRef={hours}
            onAcross={() => minutes.current?.focus()}
            onSelect={(selected) => {
              onChange(`${selected}:${minute}`);
              minutes.current?.focus();
            }}
          />
          <TimeColumn
            key={`minute-${minute}`}
            label="분"
            values={MINUTES}
            value={minute}
            listRef={minutes}
            onAcross={() => hours.current?.focus()}
            onSelect={(selected) => {
              onChange(`${hour}:${selected}`);
              close();
            }}
          />
        </div>
      )}
    </div>
  );
}
