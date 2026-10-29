<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '../api/client'
import { session } from '../auth/session'

const router = useRouter()
const username = ref('')
const password = ref('')
const busy = ref(false)
const message = ref('')
const requestId = ref('')
let pending: AbortController | undefined

async function submit() {
  if (busy.value) return
  busy.value = true
  message.value = ''
  requestId.value = ''
  pending = new AbortController()
  try {
    await session.signIn(username.value.trim(), password.value, pending.signal)
    password.value = ''
    await router.replace('/account')
  } catch (error) {
    password.value = ''
    if (error instanceof ApiError && error.code === 'CANCELLED') return
    message.value = error instanceof ApiError ? error.message : '登录暂时不可用，请重试'
    requestId.value = error instanceof ApiError ? error.requestId ?? '' : ''
  } finally {
    busy.value = false
  }
}

onBeforeUnmount(() => {
  pending?.abort()
  password.value = ''
})
</script>

<template>
  <section class="page-content auth-page">
    <div class="page-intro">
      <span class="eyebrow">WELCOME BACK</span>
      <h1>登录 DocWeave</h1>
      <p>从你的账号开始，连接有据可循的知识。</p>
    </div>
    <div class="auth-card">
      <form @submit.prevent="submit">
        <label for="username">账号</label>
        <input
          id="username"
          v-model="username"
          name="username"
          autocomplete="username"
          required
          minlength="3"
          maxlength="64"
          :disabled="busy"
          placeholder="请输入账号"
        >
        <label for="password">密码</label>
        <input
          id="password"
          v-model="password"
          name="password"
          type="password"
          autocomplete="current-password"
          required
          maxlength="256"
          :disabled="busy"
          placeholder="请输入密码"
        >
        <div
          v-if="message"
          class="auth-error"
          role="alert"
        >
          <p>{{ message }}</p>
          <span
            v-if="requestId"
            class="request-id"
          >请求编号：{{ requestId }}</span>
        </div>
        <button
          class="button button-primary auth-submit"
          type="submit"
          :disabled="busy"
          :aria-busy="busy"
        >
          {{ busy ? '正在登录…' : '登录' }}
        </button>
      </form>
      <p class="muted auth-note">
        当前仅开放已有账号登录。注册与个人空间功能正在开发中。
      </p>
    </div>
  </section>
</template>
