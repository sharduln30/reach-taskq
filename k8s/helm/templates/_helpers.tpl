{{/* common labels */}}
{{- define "taskq.labels" -}}
app.kubernetes.io/name: reach-taskq
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
{{- end -}}

{{- define "taskq.selectorLabels" -}}
app.kubernetes.io/name: reach-taskq
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/* shared environment block (referenced from both api and worker pods) */}}
{{- define "taskq.env" -}}
- name: SPRING_PROFILES_ACTIVE
  value: prod
- name: TASKQ_BROKER
  value: {{ .Values.broker | quote }}
- name: POSTGRES_HOST
  value: {{ .Values.postgres.host | quote }}
- name: POSTGRES_PORT
  value: {{ .Values.postgres.port | quote }}
- name: POSTGRES_DB
  value: {{ .Values.postgres.db | quote }}
- name: POSTGRES_USER
  valueFrom:
    secretKeyRef:
      name: {{ .Values.postgres.existingSecret }}
      key: username
- name: POSTGRES_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ .Values.postgres.existingSecret }}
      key: password
- name: REDIS_HOST
  value: {{ .Values.redis.host | quote }}
- name: REDIS_PORT
  value: {{ .Values.redis.port | quote }}
{{- if .Values.redis.existingSecret }}
- name: REDIS_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ .Values.redis.existingSecret }}
      key: password
{{- end }}
- name: TASKQ_LEASE_TTL_SECONDS
  value: {{ .Values.leaseTtlSeconds | quote }}
- name: TASKQ_LEASE_REAPER_INTERVAL_SECONDS
  value: {{ .Values.leaseReaperIntervalSeconds | quote }}
- name: TASKQ_OUTBOX_POLL_INTERVAL_MS
  value: {{ .Values.outboxPollIntervalMs | quote }}
- name: TASKQ_SCHEDULER_POLL_INTERVAL_MS
  value: {{ .Values.schedulerPollIntervalMs | quote }}
- name: TASKQ_DEFAULT_MAX_ATTEMPTS
  value: {{ .Values.defaultMaxAttempts | quote }}
- name: TASKQ_BACKOFF_BASE_MS
  value: {{ .Values.backoffBaseMs | quote }}
- name: TASKQ_BACKOFF_MAX_MS
  value: {{ .Values.backoffMaxMs | quote }}
- name: TASKQ_IDEMPOTENCY_TTL_HOURS
  value: {{ .Values.idempotencyTtlHours | quote }}
- name: TASKQ_PAYLOAD_MAX_BYTES
  value: {{ .Values.payloadMaxBytes | quote }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ .Values.otel.endpoint | quote }}
- name: OTEL_SERVICE_NAME
  value: {{ .Values.otel.serviceName | quote }}
{{- end -}}
