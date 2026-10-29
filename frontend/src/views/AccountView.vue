<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '../api/client'
import { session } from '../auth/session'

const router = useRouter()
const user = session.user
const busy = ref(false)
const message = ref('')
const requestId = ref('')
let active = true
let pending: AbortController | undefined

watch(user, (value) => { if (!value && active) void router.replace('/login') })

async function verify() {
  busy.value = true
  message.value = ''
  requestId.value = ''
  pending = new AbortController()
  try {
    if (!await session.verify(pending.signal) && active) await router.replace('/login')
    else if (active) message.value = '当前登录有效'
  } catch (error) {
    if (active) {
      message.value = error instanceof ApiError ? error.message : '暂时无法核验登录，请重试'
      requestId.value = error instanceof ApiError ? error.requestId ?? '' : ''
    }
  } finally {
    if (active) busy.value = false
  }
}

async function signOut() {
  if (busy.value) return
  busy.value = true
  message.value = ''
  requestId.value = ''
  try {
    await session.signOut()
    if (active) await router.replace('/login')
  } catch (error) {
    if (active) {
      message.value = error instanceof ApiError ? error.message : '退出未完成，请检查网络后重试'
      requestId.value = error instanceof ApiError ? error.requestId ?? '' : ''
    }
  } finally {
    if (active) busy.value = false
  }
}

onMounted(() => { void verify() })
onBeforeUnmount(() => {
  active = false
  pending?.abort()
})
</script>

<template>
  <section class="page-content auth-page">
    <div class="page-intro">
      <span class="eyebrow">YOUR ACCOUNT</span>
      <h1>你好，{{ user?.displayName }}</h1>
      <p>欢迎回到 DocWeave。</p>
    </div>
    <div class="auth-card">
      <h2>当前账号</h2>
      <dl class="account-details">
        <dt>账号</dt><dd>{{ user?.username }}</dd>
      </dl>
      <p class="muted">
        个人空间与企业协作功能正在开发中。
      </p>
      <p
        v-if="message"
        role="status"
      >
        {{ message }}
      </p>
      <span
        v-if="requestId"
        class="request-id"
      >请求编号：{{ requestId }}</span>
      <div class="auth-actions">
        <button
          class="button button-secondary"
          :disabled="busy"
          @click="verify"
        >
          检查登录状态
        </button>
        <button
          class="button button-primary"
          :disabled="busy"
          @click="signOut"
        >
          退出登录
        </button>
      </div>
    </div>
  </section>
</template>
