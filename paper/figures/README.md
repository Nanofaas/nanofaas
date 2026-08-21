# nanofaas paper figures (TikZ)

Standalone, print-ready TikZ figures for the nanofaas paper and for the
Italian monograph in `paper/nanofaas.tex`. Modern flat
style with a grayscale-safe palette; sans-serif labels.

| File | Figure |
|------|--------|
| `fig-architecture.tex` | Single-pod control plane, optional SPI modules, pluggable execution backends |
| `fig-invocation-flow.tex` | Sync (`:invoke`) vs async (`:enqueue`) invocation as a UML `alt` sequence diagram |
| `fig-offload.tex` | Edge→cloud transparent offload (pressure / eager policy, single hop, no fallback) |
| `fig-execution-modes.tex` | LOCAL / EXTERNAL / DEPLOYMENT dispatch + cold-start vs warm-start timeline |
| `fig-sojourn-curve.tex` | Schematic: service time has no interior optimum, sojourn time does (illustrative shapes, not measured data) |
| `fig-release-series.tex` | Throughput and p95 across releases, from `docs/performance/history.md` (requires `pgfplots`) |
| `nanofaas-figs.sty` | Shared palette + TikZ styles (required by all of them) |

## Compile

Each `.tex` is a `standalone` document that crops to the figure:

```bash
for f in fig-*.tex; do pdflatex -interaction=nonstopmode "$f"; done
```

Produces a tightly-cropped `.pdf` per figure. `nanofaas-figs.sty` must be on the
TeX input path (same directory is fine). Tested on TeX Live 2026 (`pdflatex`).
The `.png` files are 150-dpi previews, not needed for the paper.

Requires the `fontawesome5` package (icons in the architecture figure), which
ships with TeX Live / MiKTeX. Icons are used via slugs, e.g. `\ic{dharmachakra}`
(Kubernetes), `\ic{docker}`, `\ic{sync-alt}` — `\ic{}` is defined in the `.sty`.

## Include in the paper

Either drop in the cropped PDF:

```latex
\includegraphics[width=\columnwidth]{figures/fig-architecture}
```

or `\input` the source (put `nanofaas-figs.sty` on the path and load
`\usepackage{standalone}` in the preamble):

```latex
\usepackage{standalone}
...
\begin{figure}\centering\input{figures/fig-architecture}\end{figure}
```

## Notes

- **Font:** labels are sans-serif for a modern look. To match a serif body, drop
  the `\sffamily` from the styles in `nanofaas-figs.sty`.
- **Colours** live in `nanofaas-figs.sty` (`nfblue`/`nfgreen`/`nfamber`/…). They
  stay distinguishable in grayscale.
- **Language:** the four original figures label components in English; the two
  added for the Italian monograph (`fig-sojourn-curve`, `fig-release-series`)
  label their axes in Italian, since only that document includes them.
- **Offload figure** reflects the shipped module: header `X-NanoFaaS-Offloaded`,
  triggers `EAGER` / `DEPTH` / `EST_WAIT`, counters `nanofaas_offload_total`
  (tagged `function`,`trigger`) and `nanofaas_offload_failure_total`.
- **Editing gotcha 1 (architecture & offload):** the grouping rectangles live on
  the TikZ background layer, and `shadows.blur` (the node drop-shadows)
  manipulates layers. A blur-shadowed node emitted *after* background-layer
  content silently suppresses that content. Keep the `\begin{scope}[on
  background layer]` block **after every shadowed node**, and put its container
  labels after it. This is why those two files declare containers late. (The
  icon "card" styles in the architecture figure are therefore flat, no shadow.)
- **Editing gotcha 2 (line breaks in cards):** inside a TikZ `align` node, `\\`
  only works at the node's top level. A `\\` *inside* a `{\footnotesize …}`
  group throws "Undefined control sequence" — split it into one group per line
  with the `\\` between the groups (see the two-line subtitles).
