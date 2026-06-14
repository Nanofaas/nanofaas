{{- define "nanofaas-runtime.name" -}}
{{- .Release.Name -}}
{{- end -}}

{{- define "nanofaas-runtime.labels" -}}
app.kubernetes.io/name: {{ include "nanofaas-runtime.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}
