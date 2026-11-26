"use client";

import { useEffect, useRef } from "react";
import type { Place } from "@/lib/stockPlace";

type Cls = { over: string; lift: string; ghost: string; dragging: string };

// 재료를 끌어 다른 그림(data-drop)에 놓는다 (S15P31A308-270).
// 마우스는 6px 움직이면, 터치는 0.35초 누르고 있으면 시작. 끌기 뒤 따라오는 click 은 막는다.
// 재료 칸은 data-ing (재료 id) · data-place 를 단다.
export function useDragPlace(
  enabled: boolean,
  cls: Cls,
  onDrop: (id: string, place: Place) => void,
) {
  const drop = useRef(onDrop);
  useEffect(() => {
    drop.current = onDrop;
  });

  useEffect(() => {
    if (!enabled) return;
    type D = {
      el: HTMLElement;
      x: number;
      y: number;
      touch: boolean;
      on: boolean;
      timer?: number;
      ghost?: HTMLElement;
      dx: number;
      dy: number;
      zone: HTMLElement | null;
    };
    let d: D | null = null;
    let swallow = false;

    const start = (g: D) => {
      const r = g.el.getBoundingClientRect();
      const ghost = g.el.cloneNode(true) as HTMLElement;
      ghost.classList.add(cls.ghost);
      Object.assign(ghost.style, {
        width: `${r.width}px`,
        height: `${r.height}px`,
        left: `${r.left}px`,
        top: `${r.top}px`,
      });
      document.body.append(ghost);
      g.ghost = ghost;
      g.dx = g.x - r.left;
      g.dy = g.y - r.top;
      g.on = true;
      g.el.classList.add(cls.lift);
      document.body.classList.add(cls.dragging);
      navigator.vibrate?.(10);
    };
    const down = (e: PointerEvent) => {
      const el = (e.target as Element).closest<HTMLElement>("[data-ing]");
      if (!el || e.button > 0) return;
      d = {
        el,
        x: e.clientX,
        y: e.clientY,
        touch: e.pointerType !== "mouse",
        on: false,
        dx: 0,
        dy: 0,
        zone: null,
      };
      if (d.touch) {
        const g = d;
        g.timer = window.setTimeout(() => d === g && start(g), 350);
      }
    };
    const move = (e: PointerEvent) => {
      if (!d) return;
      const moved = Math.hypot(e.clientX - d.x, e.clientY - d.y);
      if (!d.on) {
        if (d.touch) {
          // 길게 누르기 전에 움직이면 스크롤로 본다
          if (moved > 8) {
            clearTimeout(d.timer);
            d = null;
          }
          return;
        }
        if (moved < 6) return;
        start(d);
      }
      d.ghost!.style.left = `${e.clientX - d.dx}px`;
      d.ghost!.style.top = `${e.clientY - d.dy}px`;
      const z =
        document.elementFromPoint(e.clientX, e.clientY)?.closest<HTMLElement>("[data-drop]") ??
        null;
      if (z !== d.zone) {
        d.zone?.classList.remove(cls.over);
        z?.classList.add(cls.over);
        d.zone = z;
      }
    };
    const end = (cancel: boolean) => () => {
      if (!d) return;
      const g = d;
      d = null;
      clearTimeout(g.timer);
      if (!g.on) return;
      swallow = true;
      setTimeout(() => (swallow = false), 0);
      g.zone?.classList.remove(cls.over);
      g.el.classList.remove(cls.lift);
      document.body.classList.remove(cls.dragging);
      const to = g.zone?.dataset.drop as Place | undefined;
      if (cancel || !to || to === g.el.dataset.place) {
        // 제자리로 돌아가는 모습
        const r = g.el.getBoundingClientRect();
        g.ghost!.animate([{}, { left: `${r.left}px`, top: `${r.top}px` }], {
          duration: 180,
          easing: "cubic-bezier(0.23, 1, 0.32, 1)",
        }).onfinish = () => g.ghost!.remove();
        return;
      }
      g.ghost!.remove();
      drop.current(g.el.dataset.ing!, to);
    };
    const up = end(false);
    const cancel = end(true);
    const click = (e: MouseEvent) => {
      if (swallow) {
        e.stopPropagation();
        e.preventDefault();
      }
    };
    // 길게 누른 뒤에는 화면이 스크롤되지 않게
    const touch = (e: TouchEvent) => d?.on && e.preventDefault();
    const menu = (e: Event) => (e.target as Element).closest("[data-ing]") && e.preventDefault();

    document.addEventListener("pointerdown", down);
    document.addEventListener("pointermove", move);
    document.addEventListener("pointerup", up);
    document.addEventListener("pointercancel", cancel);
    document.addEventListener("click", click, true);
    document.addEventListener("touchmove", touch, { passive: false });
    document.addEventListener("contextmenu", menu);
    return () => {
      document.removeEventListener("pointerdown", down);
      document.removeEventListener("pointermove", move);
      document.removeEventListener("pointerup", up);
      document.removeEventListener("pointercancel", cancel);
      document.removeEventListener("click", click, true);
      document.removeEventListener("touchmove", touch);
      document.removeEventListener("contextmenu", menu);
    };
  }, [enabled, cls]);
}
