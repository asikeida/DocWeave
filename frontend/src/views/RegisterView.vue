<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { register } from '../api/auth'
import { ApiError } from '../api/client'
const username = ref('')
const password = ref('')
const displayName = ref('')
const busy = ref(false)
const done = ref(false)
const message = ref('')
const requestId = ref('')
let pending: AbortController | undefined
async function submit() {
  if (busy.value) return
  busy.value = true
  message.value = ''; requestId.value = ''
  pending = new AbortController()
  try {
    await register(username.value.trim(), password.value, displayName.value.trim(), pending.signal)
    done.value = true
  } catch (error) {
    if (error instanceof ApiError && error.code === 'CANCELLED') return
    message.value = error instanceof ApiError ? error.message : '注册失败，请稍后重试'
    requestId.value = error instanceof ApiError ? error.requestId ?? '' : ''
  } finally { busy.value = false; password.value = '' }
}
onBeforeUnmount(() => { pending?.abort(); password.value = '' })
</script>
<template>
  <section class="page-content auth-page">
    <h1>创建个人账号</h1>
    <p>当前仅供显式启用的开发环境体验，生产注册尚未开放。</p>
    <div
      v-if="done"
      class="auth-card"
      role="status"
    >
      <h2>个人区域已创建</h2>
      <p>请登录进入“我的资料”。示例问答仍待后续模块接入。</p>
      <RouterLink to="/login">
        前往登录
      </RouterLink>
    </div>
    <form
      v-else
      class="auth-card"
      @submit.prevent="submit"
    >
      <label for="register-user">账号（3～64位英文、数字、点、下划线或连字符）</label>
      <input
        id="register-user"
        v-model="username"
        autocomplete="username"
        required
        minlength="3"
        maxlength="64"
        :disabled="busy"
      >
      <label for="register-name">显示名称</label>
      <input
        id="register-name"
        v-model="displayName"
        autocomplete="nickname"
        required
        maxlength="80"
        :disabled="busy"
      >
      <label for="register-password">密码（至少12字符，UTF-8最多72字节）</label>
      <input
        id="register-password"
        v-model="password"
        autocomplete="new-password"
        type="password"
        required
        minlength="12"
        maxlength="72"
        :disabled="busy"
      >
      <div
        v-if="message"
        class="auth-error"
        role="alert"
      >
        {{ message }}<p v-if="requestId">
          请求编号：{{ requestId }}
        </p>
      </div>
      <button
        class="button button-primary auth-submit"
        :disabled="busy"
        :aria-busy="busy"
      >
        {{ busy ? '正在创建…' : '注册并建立个人区域' }}
      </button>
    </form>
  </section>
</template>
