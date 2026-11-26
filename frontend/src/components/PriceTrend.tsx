"use client";

import { useId, useState, type CSSProperties } from "react";
import { chartPath, chartScale, weekLabel } from "@/lib/marketComparison";
import { won } from "@/lib/format";
import type { PriceHistory } from "@/lib/types";
import styles from "./PriceTrend.module.css";

export function PriceTrend({
  price,
  name,
  unit,
  compare,
}: {
  price: PriceHistory;
  name: string;
  unit: string;
  compare: boolean;
}) {
  const series = [
    { name, values: price.history },
    ...(compare
      ? (price.markets ?? []).map((m) => ({ name: m.name, values: m.weekly ?? [] }))
      : []),
  ];
  const count = Math.max(price.history.length, ...series.map((s) => s.values.length));
  const [selected, setSelected] = useState({ series: 0, index: Math.max(0, count - 1) });
  const [activeSeries, setActiveSeries] = useState(0);
  const tooltipId = useId();
  const [hover, setHover] = useState<{ series: number; index: number } | null>(null);
  const scale = chartScale(series.flatMap((s) => s.values));
  const x = (i: number) => (count > 1 ? (100 * i) / (count - 1) : 50);
  const y = (v: number) => 100 - ((v - scale.min) * 100) / (scale.max - scale.min);
  const bucket = (i: number) => price.buckets?.[i];
  const label = (i: number) =>
    bucket(i) ? weekLabel(bucket(i)!) : i === count - 1 ? "이번 주" : `${count - 1 - i}주 전`;
  const range = (i: number) => (bucket(i) ? `${bucket(i)!.from} ~ ${bucket(i)!.to}` : "");
  const describe = (si: number, i: number) =>
    `${label(i)} · ${series[si].name} ${series[si].values[i] == null ? "수집 없음" : won(series[si].values[i]!)}/1${unit}`;
  const at = series[selected.series]?.values[selected.index];
  const any = series.some((s) => s.values.some((v) => v !== null && Number.isFinite(v)));
  return (
    <div className={styles.chart} aria-label="가격 추이">
      <div className={styles.title}>
        최근 {count}주 단가 · 원/1{unit}
      </div>
      <div className={styles.legend}>
        {series.map((s, si) => (
          <button
            key={s.name}
            type="button"
            aria-pressed={activeSeries === si}
            onClick={() => {
              setActiveSeries(si);
              setSelected({
                series: si,
                index: Math.max(
                  0,
                  s.values.findLastIndex((v) => v !== null),
                ),
              });
            }}
          >
            <i style={{ borderTopStyle: si === 0 ? "solid" : "dashed" }} />
            {s.name}
          </button>
        ))}
      </div>
      {any ? (
        <>
          <div className={styles.graph}>
            <div className={styles.yLabels}>
              {[scale.max, (scale.max + scale.min) / 2, scale.min].map((v, i) => (
                <span key={i} style={{ top: `${i * 50}%` }}>
                  {Math.round(v).toLocaleString("ko-KR")}
                </span>
              ))}
            </div>
            <div
              className={styles.plot}
              onPointerMove={(e) => {
                const rect = e.currentTarget.getBoundingClientRect();
                let nearest: { series: number; index: number; distance: number } | null = null;
                series.forEach((s, si) =>
                  s.values.forEach((v, i) => {
                    if (v === null || !Number.isFinite(v)) return;
                    const distance = Math.hypot(
                      e.clientX - rect.left - (x(i) * rect.width) / 100,
                      e.clientY - rect.top - (y(v) * rect.height) / 100,
                    );
                    if (distance <= 22 && (!nearest || distance < nearest.distance))
                      nearest = { series: si, index: i, distance };
                  }),
                );
                setHover(nearest);
                if (nearest) setSelected(nearest);
              }}
              onClick={(e) => {
                const rect = e.currentTarget.getBoundingClientRect();
                const px = e.clientX - rect.left,
                  py = e.clientY - rect.top;
                let closest = { series: activeSeries, distance: 12 };
                series.forEach((s, si) =>
                  s.values.forEach((v, i) => {
                    if (v == null || !Number.isFinite(v)) return;
                    const pointDistance = Math.hypot(
                      px - (x(i) * rect.width) / 100,
                      py - (y(v) * rect.height) / 100,
                    );
                    if (pointDistance < closest.distance)
                      closest = { series: si, distance: pointDistance };
                    const previous = s.values[i - 1];
                    if (i === 0 || v == null || previous == null) return;
                    const ax = (x(i - 1) * rect.width) / 100,
                      ay = (y(previous) * rect.height) / 100;
                    const dx = (x(i) * rect.width) / 100 - ax,
                      dy = (y(v) * rect.height) / 100 - ay;
                    const t = Math.max(
                      0,
                      Math.min(1, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy || 1)),
                    );
                    const distance = Math.hypot(px - ax - t * dx, py - ay - t * dy);
                    if (distance < closest.distance) closest = { series: si, distance };
                  }),
                );
                setActiveSeries(closest.series);
              }}
              onPointerLeave={() => setHover(null)}
            >
              <svg viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
                <path
                  d="M0,0V100H100 M-2,0H0 M-2,50H0 M-2,100H0"
                  className={styles.axis}
                  vectorEffect="non-scaling-stroke"
                />
                {[0, 50, 100].map((v) => (
                  <path
                    key={v}
                    d={`M0,${v}H100`}
                    className={styles.grid}
                    vectorEffect="non-scaling-stroke"
                  />
                ))}
                {series.map((s, si) => (
                  <path
                    key={s.name}
                    d={chartPath(s.values, scale)}
                    className={si === activeSeries ? styles.line : styles.market}
                    style={{ strokeDasharray: si === 0 ? "none" : "5 4" }}
                    vectorEffect="non-scaling-stroke"
                  />
                ))}
              </svg>
              {series.flatMap((s, si) =>
                s.values.map((v, i) =>
                  v === null || !Number.isFinite(v) ? null : (
                    <button
                      type="button"
                      key={`${s.name}-${i}`}
                      className={styles.point}
                      data-price-point="true"
                      style={
                        {
                          left: `${x(i)}%`,
                          top: `${y(v)}%`,
                          zIndex: selected.series === si ? 3 : 2,
                          "--point-color": si === activeSeries ? "var(--red)" : "var(--ink-2)",
                        } as CSSProperties
                      }
                      aria-label={describe(si, i)}
                      aria-pressed={selected.series === si && selected.index === i}
                      aria-describedby={
                        hover?.series === si && hover.index === i ? tooltipId : undefined
                      }
                      onFocus={() => {
                        setHover({ series: si, index: i });
                        setSelected({ series: si, index: i });
                      }}
                      onBlur={() => setHover(null)}
                      onClick={(e) => {
                        e.stopPropagation();
                        setActiveSeries(si);
                        setSelected({ series: si, index: i });
                      }}
                      onKeyDown={(e) => {
                        if (e.key === "Escape") return setHover(null);
                        const direction =
                          e.key === "ArrowRight" ? 1 : e.key === "ArrowLeft" ? -1 : 0;
                        if (!direction) return;
                        e.preventDefault();
                        let next = i + direction;
                        while (next >= 0 && next < s.values.length && s.values[next] === null)
                          next += direction;
                        if (next >= 0 && next < s.values.length)
                          (
                            e.currentTarget.parentElement?.querySelector(
                              `[data-series="${si}"][data-index="${next}"]`,
                            ) as HTMLButtonElement
                          )?.focus();
                      }}
                      data-series={si}
                      data-index={i}
                    >
                      <i data-price-circle="true" aria-hidden="true" />
                    </button>
                  ),
                ),
              )}
              {hover && (
                <div
                  id={tooltipId}
                  role="tooltip"
                  className={styles.tooltip}
                  style={{
                    left: `clamp(0px, calc(${x(hover.index)}% - 90px), max(0px, 100% - 180px))`,
                    top: `${y(series[hover.series].values[hover.index]!)}%`,
                  }}
                >
                  <span>{label(hover.index)}</span>
                  <strong>{won(series[hover.series].values[hover.index]!)}</strong>
                  <small>{series[hover.series].name}</small>
                  <small>{range(hover.index)}</small>
                </div>
              )}
              <div className={styles.xLabels}>
                {Array.from({ length: count }, (_, i) => (
                  <span
                    key={i}
                    className={i !== 0 && i !== count - 1 ? styles.middle : ""}
                    style={{ left: `${x(i)}%` }}
                  >
                    <i />
                    {bucket(i)
                      ? `${Number(bucket(i)!.to.slice(5, 7))}/${Number(bucket(i)!.to.slice(8, 10))}`
                      : label(i)}
                  </span>
                ))}
              </div>
            </div>
          </div>
          <div
            className={styles.value}
            role="status"
            aria-live="polite"
            aria-label={series[selected.series]?.name}
          >
            <span>{label(selected.index)}</span>
            <strong>{at == null ? "수집 없음" : won(at)}</strong>
          </div>
        </>
      ) : (
        <p>단가 이력 없음</p>
      )}
    </div>
  );
}
