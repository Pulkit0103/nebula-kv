{{/*
Expand the name of the chart.
*/}}
{{- define "nebula-kv.name" -}}
{{- .Chart.Name | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "nebula-kv.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "nebula-kv.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{ include "nebula-kv.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "nebula-kv.selectorLabels" -}}
app.kubernetes.io/name: {{ include "nebula-kv.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Build SEEDS list: nebula-kv-{0..N-1}.headless:kvPort excluding current pod
(used as env var — all pods get the full list; they can filter self).
*/}}
{{- define "nebula-kv.seeds" -}}
{{- $name := include "nebula-kv.fullname" . -}}
{{- $svc  := printf "%s-headless" $name -}}
{{- $port := .Values.service.kvPort -}}
{{- $ns   := .Release.Namespace -}}
{{- $seeds := list -}}
{{- range $i, $e := until (int .Values.replicaCount) -}}
  {{- $seeds = append $seeds (printf "%s-%d.%s.%s.svc.cluster.local:%d" $name $i $svc $ns $port) -}}
{{- end -}}
{{- join "," $seeds -}}
{{- end }}
