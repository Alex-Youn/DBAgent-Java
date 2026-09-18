# MySQL Overview Dashboard — UI Build Spec

Reference screenshot: Percona Monitoring and Management (PMM) style "MySQL Overview" Grafana dashboard, dark theme.

Goal: recreate this screen visually (static or data-driven mockup — either is fine; wire it to real metrics only if a data source is explicitly requested). Treat all values below as sample/placeholder data unless told otherwise.

## 1. Overall theme

- Theme: Grafana default **dark** theme.
- Base font: system/sans-serif stack — `"Inter", "Helvetica Neue", Arial, sans-serif`.
- Approximate palette (Grafana dark-theme defaults; close enough to match visually):
  | Role | Color |
  |---|---|
  | Page background | `#111217` |
  | Panel / row background | `#1f2024` |
  | Panel border | `#2c2d32` |
  | Primary text | `#d8d9da` |
  | Muted / secondary text | `#8e8e8e` |
  | Accent — teal/cyan (labels, nav icons) | `#5ac8db` |
  | Accent — orange (refresh label, gauge fill, % values) | `#ff9830` |
  | Accent — green (uptime value) | `#73bf69` |
  | Accent — blue (sparkline) | `#5794f2` |
- Corner radius on cards/rows: small, ~4px.
- Sidebar and header bars sit on the page background; panels/rows are a slightly lighter card color with a thin 1px border.

## 2. Page layout (top to bottom)

```
┌────┬─────────────────────────────────────────────────────────────────┐
│    │ Header bar: title + toolbar icons                                │
│    ├─────────────────────────────────────────────────────────────────┤
│Side│ Filter bar: Interval / Host / PMM Annotations                    │
│bar │ Top nav tabs: Query Analytics | OS | MySQL | MongoDB | HA | ...  │
│    ├─────────────────────────────────────────────────────────────────┤
│    │ ⌄ (collapse toggle for the KPI row)                              │
│    │ ┌───────────┬───────────┬───────────┬───────────┐               │
│    │ │ Uptime    │ QPS       │ Buffer    │ Buffer %  │  (4 stat cards)│
│    │ └───────────┴───────────┴───────────┴───────────┘               │
│    ├─────────────────────────────────────────────────────────────────┤
│    │ > Connections                                    (2 panels) ⠿   │
│    │ > Table Locks                                     (2 panels) ⠿   │
│    │ > Temporary Objects                               (2 panels) ⠿   │
│    │ > Sorts                                           (2 panels) ⠿   │
│    │ > Aborted                                         (2 panels) ⠿   │
│    │ > Network                                         (2 panels) ⠿   │
│    │ > Memory                                          (1 panel)  ⠿   │
│    │ > Command, Handlers, Processes                    (6 panels) ⠿   │
│    │ > Query Cache                                     (2 panels) ⠿   │
│    │ > Files and Tables                                (2 panels) ⠿   │
│    │ > Table Openings                                  (2 panels) ⠿   │
│    │ > MySQL Table Definition Cache                    (1 panel)  ⠿   │
│    │ > System Charts                                   (6 panels) ⠿   │
└────┴─────────────────────────────────────────────────────────────────┘
```

## 3. Left sidebar (fixed, ~56–60px wide, full height, page background)

Top to bottom, icons stacked vertically, centered:
1. Grafana "g" logo mark (orange), acts as home button.
2. `+` icon — create.
3. Grid/apps icon (2×2 squares) — **highlighted as the active section** (rounded highlight background, e.g. `#33343b`).
4. Bell icon — alerting.
5. Gear icon — configuration.
6. *(flexible spacer)*
7. Near the bottom: a small red circular icon (robot/plugin mascot — this is the PMM plugin icon; a generic "puzzle piece" or bot icon is an acceptable stand-in).
8. Bottom-most: `?` in a circle — help.

## 4. Header bar

Single row, height ~50–56px.

- **Left**: page/dashboard title, large, semi-bold — `MySQL Overview` — followed by a small `▾` dropdown caret (dashboard switcher).
- **Right**: a row of icon buttons, left to right:
  1. Bar-chart-with-`+` icon (add panel)
  2. Star outline icon (mark as favorite)
  3. Share/export icon
  4. Save icon (floppy disk)
  5. Gear icon (dashboard settings)
  6. *(small vertical divider)*
  7. `‹` chevron (step time range back)
  8. Magnifying-glass icon (zoom time range out)
  9. `›` chevron (step time range forward)
  10. Time-range picker pill: clock icon + text `Last 12 hours`
  11. `Refresh every 1m` — in **orange** text, immediately right of the time-range pill
  12. Circular refresh icon

## 5. Filter / variable bar

Row directly under the header, small height (~34px), left-aligned controls:

- Label `Interval` (teal/cyan text) + dropdown control showing `auto ▾`
- Label `Host` (teal/cyan text) + dropdown control showing `ecy-s-dbrm-001 ▾`
- Label `PMM Annotations` (white text) + a small toggle/checkbox rendered as an **orange checked box** (annotations layer is ON)

These are Grafana dashboard **template variables** (`$interval`, `$host`) plus an annotation toggle — implement as real dropdowns/toggle if the mockup is interactive, otherwise static controls are fine.

## 6. Top navigation tabs

Right-aligned row of section links, each with a small icon before the label:

`Query Analytics` (grid icon) · `OS` (≡) · `MySQL` (≡) · `MongoDB` (≡) · `HA` (≡) · `Cloud` (≡) · `Insight` (≡) · `PMM` (≡)

These represent links to sibling dashboards in a PMM-style navigation bar. `MySQL` is the currently active page (no strong visual distinction needed beyond normal tab styling, matching the reference).

## 7. KPI stat-card row

A collapsible, unlabeled row (shows only a small `⌄` collapse chevron at the far left, no title). Contains **4 equal-width stat cards** side by side, each with:
- Small `ⓘ` info icon in the top-left corner of the card.
- Panel title, small muted text, top-left.
- One large value, bold, left-aligned, dominating the card.
- Optional mini visualization pinned to the bottom of the card.

| # | Title | Value | Value color | Extra visual |
|---|---|---|---|---|
| 1 | MySQL Uptime | `1.6 weeks` | green `#73bf69` | none |
| 2 | Current QPS | `1.48` | light/white | small blue sparkline area-chart along the bottom edge |
| 3 | InnoDB Buffer Pool Size | `8 GiB` | light/white | none |
| 4 | Buffer Pool Size of Total RAM | `51%` | orange `#ff9830` | horizontal bar-gauge along the bottom edge, filled ~90% of the track width in orange |

## 8. Collapsible metric-group rows

Below the KPI row, a vertical stack of **collapsed accordion rows** (all collapsed by default, matching the screenshot). Each row is a single horizontal bar with:
- `>` chevron on the left (indicates collapsed; would rotate to `⌄` when expanded).
- Row title, bold.
- `(N panels)` immediately after the title, italic, muted/gray, smaller font.
- A drag-handle icon (six-dot grid `⠿`) pinned to the far right, indicating the row is reorderable.

Rows, in order, with their panel counts (use as the section list — exact panel contents inside each row aren't shown in the reference and can be filled with representative MySQL metrics, e.g. time-series graphs, for whichever section is expanded):

1. Connections — 2 panels
2. Table Locks — 2 panels
3. Temporary Objects — 2 panels
4. Sorts — 2 panels
5. Aborted — 2 panels
6. Network — 2 panels
7. Memory — 1 panel
8. Command, Handlers, Processes — 6 panels
9. Query Cache — 2 panels
10. Files and Tables — 2 panels
11. Table Openings — 2 panels
12. MySQL Table Definition Cache — 1 panel
13. System Charts — 6 panels

## 9. Interaction notes (optional, implement if the build target supports it)

- Clicking a row's `>` chevron expands it, revealing its panels (typically time-series line/area charts in Grafana's dark theme) and rotates the chevron to `⌄`.
- The KPI row's `⌄` chevron similarly collapses/expands that row.
- `Interval` / `Host` dropdowns filter all panels' underlying queries.
- `Refresh every 1m` auto-refreshes panel data on that cadence; the circular icon triggers a manual refresh.

## 10. Suggested implementation

A single self-contained HTML/CSS(/JS) file is the simplest way to reproduce this pixel-for-pixel as a static or lightly-interactive mockup:
- CSS Grid/Flexbox for the sidebar + main content split, and for the 4-column KPI card row.
- Inline SVG or simple `<canvas>`/CSS for the sparkline and bar-gauge mini-visuals.
- Plain `<details>`/`<summary>` or a small JS toggle for the accordion rows.

If the target is an actual Grafana dashboard instead of a mockup, this document maps directly to a Grafana dashboard JSON model: one un-titled collapsed row containing 4 `stat`-type panels (uptime, QPS, buffer pool size, buffer pool %), followed by 13 collapsed `row` panels with the titles/panel counts listed in section 8, plus the `interval` and `host` template variables and an annotation query named "PMM Annotations".
