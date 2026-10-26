<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<template>
  <div class="not-found page-content">
    <span class="eyebrow">404 / PAGE NOT FOUND</span>
    <h1>这一页，还没有被织进来。</h1>
    <p>页面可能已移动，或链接地址有误。回到概览，继续探索。</p>
    <RouterLink
      class="button button-primary"
      to="/"
    >
      返回首页 <span aria-hidden="true">→</span>
    </RouterLink>
  </div>
</template>
