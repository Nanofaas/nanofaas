{{- define "nanofaas.name" -}}
nanofaas
{{- end -}}

{{- define "nanofaas.namespace" -}}
{{- if .Values.namespace.create -}}
{{- .Values.namespace.name -}}
{{- else -}}
{{- .Release.Namespace -}}
{{- end -}}
{{- end -}}

{{- define "nanofaas.controlPlane.labels" -}}
app.kubernetes.io/name: {{ include "nanofaas.name" . }}
app.kubernetes.io/component: control-plane
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "nanofaas.controlPlane.selectorLabels" -}}
app: nanofaas-control-plane
{{- end -}}

{{/*
Round a Kubernetes CPU quantity up to the nearest whole core, minimum 1.
Accepts a plain number ("1", "2", "1.5") or millicores ("500m", "1500m").

Sizes reactor-netty's event loop count (controlPlane.jvm.ioWorkerCount in
values.yaml) to controlPlane.resources.limits.cpu automatically, instead of
a value kept in sync by hand. Rounds UP, not down: a single event-loop
thread can never use more than one core of quota no matter how much is
available, so a 1.5-core limit gets 2 workers, not 1 - the same rule
Envoy's own worker-thread sizing guide gives for the identical problem (see
docs/plans/2026-09-04-overload-path-fixes.md, Part I.2).
*/}}
{{- define "nanofaas.controlPlane.ioWorkerCount" -}}
{{- $cpu := .Values.controlPlane.resources.limits.cpu | default "1" | toString -}}
{{- $cores := 0.0 -}}
{{- if hasSuffix "m" $cpu -}}
{{- $cores = divf (trimSuffix "m" $cpu | float64) 1000.0 -}}
{{- else -}}
{{- $cores = $cpu | float64 -}}
{{- end -}}
{{- $workers := ceil $cores -}}
{{- if lt $workers 1.0 -}}
{{- $workers = 1.0 -}}
{{- end -}}
{{- $workers | int64 -}}
{{- end -}}
