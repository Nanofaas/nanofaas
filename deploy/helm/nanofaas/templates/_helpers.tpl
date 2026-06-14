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
