# MySQL Overview Dashboard — Expanded Panel Detail Spec

Companion doc to `mysql-overview-dashboard-spec.md` (the base layout/theme spec — read that first for the sidebar, header toolbar, color palette, and KPI-card row). This file documents what the **expanded row panels** look like, captured from a screenshot where the `Connections` and `Table Locks` rows are open.

Note: in this screenshot the filter bar (`Interval` / `Host` / `PMM Annotations`) and the top nav tab strip (`Query Analytics · OS · MySQL · ...`) are not visible — the header goes straight from the title/toolbar row into the KPI cards. Treat that as this particular view; keep those bars from the base spec if reproducing the full page.

## 1. KPI row (unchanged structure, one value differs)

Same 4 stat cards as the base spec. Only the **Current QPS** value differs in this capture:

| Title | Value | Value color | Extra visual |
|---|---|---|---|
| MySQL Uptime | `1.6 weeks` | green | none |
| Current QPS | `2.29` | white | blue sparkline area-chart along the bottom edge |
| InnoDB Buffer Pool Size | `8 GiB` | white | none |
| Buffer Pool Size of Total RAM | `51%` | orange | horizontal bar-gauge, ~90% filled |

## 2. Expanded row header (once opened)

When a row is expanded, its header collapses to just:

`⌄ <Row Title>`

— a down chevron (rotated from `>`) plus the bold title. The `(N panels)` count and the drag-handle icon shown in the collapsed state (see base spec, section 8) are **not shown** while expanded — the row's panels render directly beneath the header instead.

## 3. Row: "Connections" — 2 panels, side by side (50/50 width)

General panel chrome (applies to every panel in this doc): dark card background, thin border, small `ⓘ` info icon top-left, panel title centered at the top, chart fills the rest of the card, thin muted gridlines, a legend/data table strip along the bottom of the panel.

### 3.1 Panel: "MySQL Connections" (left)

- Chart type: line chart, time series.
- Y-axis ticks: `0, 100, 200, 300, 400`.
- X-axis ticks: `00:00, 02:00, 04:00, 06:00, 08:00, 10:00` (12-hour window, matching the "Last 12 hours" time picker).
- Series:
  | Series | Color | Shape |
  |---|---|---|
  | Max Connections | blue/cyan | flat line at ~300 for the whole window |
  | Max Used Connections | yellow/gold | flat line near the bottom at ~6 |
- Legend table under the chart, columns `min / max / avg ▾` (the `avg` column header has a sort-direction caret, i.e. the legend table is sortable):
  | Series | min | max | avg |
  |---|---|---|---|
  | Max Connections | 300 | 300 | 300 |
  | Max Used Connections | 6 | 6 | 6 |

### 3.2 Panel: "MySQL Client Thread Activity" (right)

- Chart type: line chart, time series, with one series rendered as a filled area.
- Y-axis label (rotated vertical text): `Threads`.
- Y-axis ticks: `0, 0.5, 1.0, 1.5, 2.0, 2.5`.
- X-axis ticks: same as the left panel, `00:00`–`10:00` in 2-hour steps.
- Series:
  | Series | Color | Shape |
  |---|---|---|
  | Peak Threads Connected | blue | flat at ~1.0 with a single sharp spike to ~2.0 (small red dot marker at the spike peak) around the 03:00–04:00 mark |
  | Peak Threads Running | red/orange, dotted line | flat at ~1.0, rendered with an olive/dark-yellow semi-transparent fill under the line spanning the full width |
  | *(a third legend row is cut off at the bottom of the screenshot — include a third thread-activity series if the source dashboard has one; otherwise 2 series is enough)* |  |  |
- Legend table columns: `min / max / avg / current`:
  | Series | min | max | avg | current |
  |---|---|---|---|---|
  | Peak Threads Connected | 1.00 | 2.00 | 1.01 | 1.00 |
  | Peak Threads Running | 1.00 | 2.00 | 1.01 | 1.00 |

## 4. Row: "Table Locks" — 2 panels, side by side (50/50 width)

### 4.1 Panel: "MySQL Questions" (left)

- Chart type: area chart (line + green semi-transparent fill down to 0), time series.
- Y-axis ticks: `0, 0.5, 1.0, 1.5, 2.0, 2.5, 3.0`.
- X-axis ticks: `00:00, 02:00, 04:00, 06:00, 08:00, 10:00`.
- Series: single line, green, hovering around `2.5–2.7` with light noise across the window, with a small dip right at the end of the visible range.
- Legend: present below the chart but cropped out of the screenshot — include a single-series legend row consistent with the other panels (`min / max / avg` columns).

### 4.2 Panel: "MySQL Thread Cache" (right)

- Chart type: area chart (line + green semi-transparent fill down to 0), time series.
- Y-axis ticks: `0, 100, 200, 300`.
- X-axis ticks: `00:00, 02:00, 04:00, 06:00, 08:00, 10:00`.
- Series: single line, green, flat at ~`250` for the entire window.
- Legend: cropped out of the screenshot in the source image; add a single-series legend row for consistency with the other panels.

## 5. Implementation notes

- Both rows follow the same 2-column panel grid used throughout the dashboard (see base spec's overall page grid) — reuse one "time-series panel" component with props for: title, y-axis label/ticks, series list `{name, color, style: line|dotted|area}`, and a legend-table with configurable columns (`min/max/avg` vs `min/max/avg/current`).
- Gridlines are thin and muted (low-contrast gray on the dark background), horizontal only (no vertical gridlines visible).
- X-axis time labels and the 12-hour range should stay driven by the same time-range picker (`Last 12 hours`) described in the base spec's header section, so all panels — KPI sparkline included — share one time window.
