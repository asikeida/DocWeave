<script setup lang="ts">
import { computed, onMounted, onUnmounted, shallowRef } from 'vue'
import { ApiError } from '../api/client'
import { checkHealth } from '../api/health'

type State = { kind: 'loading' } | { kind: 'ready'; checkedAt: string } | { kind: 'error'; error: ApiError }
const state = shallowRef<State>({ kind: 'loading' })
const label = computed(() => ({ loading: '正在连接', ready: '连接正常', error: '暂时无法连接' })[state.value.kind])
let activeRequest: AbortController | undefined

async function refresh() {
  activeRequest?.abort()
  const request = new AbortController()
  activeRequest = request
  state.value = { kind: 'loading' }
  try {
    await checkHealth(request.signal)
    if (!request.signal.aborted) {
      state.value = { kind: 'ready', checkedAt: new Date().toLocaleTimeString('zh-CN', { hour12: false }) }
    }
  } catch (error) {
    if (!request.signal.aborted) {
      state.value = {
        kind: 'error',
        error: error instanceof ApiError ? error : new ApiError('连接检查失败，请稍后重试', { code: 'UNKNOWN_ERROR' }),
      }
    }
  }
}

onMounted(refresh)
onUnmounted(() => activeRequest?.abort())
</script>

<template>
  <section
    class="service-card"
    aria-labelledby="service-title"
    :aria-busy="state.kind === 'loading'"
  >
    <div class="section-heading">
      <div>
        <span class="eyebrow">连接状态</span><h2 id="service-title">
          一切，从可靠连接开始
        </h2>
      </div>
      <span
        class="service-badge"
        :class="state.kind"
      ><span class="status-dot" />{{ label }}</span>
    </div>
    <div
      class="service-body"
      role="status"
      aria-live="polite"
    >
      <template v-if="state.kind === 'ready'">
        <p>后端服务已连接，基础服务运行正常。</p>
        <span class="muted">最近检查于 {{ state.checkedAt }}</span>
      </template>
      <template v-else-if="state.kind === 'error'">
        <p>{{ state.error.message }}</p>
        <span
          v-if="state.error.requestId"
          class="request-id"
        >请求编号：{{ state.error.requestId }}</span>
        <span
          v-else
          class="muted"
        >服务恢复后，可以重新检查连接。</span>
      </template>
      <template v-else>
        <p>正在检查后端服务，请稍候…</p><span class="muted">状态来自实时连接检查</span>
      </template>
    </div>
    <div class="service-footer">
      <span class="muted">仅检查服务状态，不读取个人文档</span>
      <button
        class="button button-secondary"
        type="button"
        :disabled="state.kind === 'loading'"
        @click="refresh"
      >
        <span aria-hidden="true">↻</span> {{ state.kind === 'loading' ? '检查中…' : '重新检查' }}
      </button>
    </div>
  </section>
</template>
