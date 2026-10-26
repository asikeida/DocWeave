<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <a
    class="skip-link"
    href="#main-content"
  >跳至主要内容</a>
  <div class="app-shell">
    <aside class="sidebar">
      <RouterLink
        class="brand"
        to="/"
        aria-label="DocWeave 首页"
      >
        <img
          src="/favicon.svg"
          alt=""
          width="38"
          height="38"
        >
        <span>DocWeave<small>让知识彼此连接</small></span>
      </RouterLink>
      <div class="nav-label">
        你的知识空间
      </div>
      <nav aria-label="主导航">
        <RouterLink
          class="nav-link"
          to="/"
          exact-active-class="is-active"
        >
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.7"
            aria-hidden="true"
          ><rect
            x="3"
            y="3"
            width="7"
            height="7"
            rx="1.5"
          /><rect
            x="14"
            y="3"
            width="7"
            height="7"
            rx="1.5"
          /><rect
            x="3"
            y="14"
            width="7"
            height="7"
            rx="1.5"
          /><rect
            x="14"
            y="14"
            width="7"
            height="7"
            rx="1.5"
          /></svg>
          项目概览
        </RouterLink>
      </nav>
      <div class="sidebar-note">
        <span class="small-label">从文档，到有据可循的答案</span>
        <p>一个正在生长的知识空间。<br>每一步，都留下清晰的依据。</p>
        <span class="stage-tag">工程骨架阶段</span>
      </div>
      <a
        class="source-link"
        href="https://github.com/asikeida/DocWeave"
        target="_blank"
        rel="noopener noreferrer"
      >查看项目源码 <span aria-hidden="true">↗</span></a>
    </aside>
    <div class="main-shell">
      <header class="topbar">
        <span>个人知识 · 更进一步</span>
        <span class="topbar-badge"><span class="status-dot" />持续构建中</span>
      </header>
      <main
        id="main-content"
        tabindex="-1"
      >
        <RouterView />
      </main>
      <footer class="footer">
        <span>DocWeave</span><span>把信息连接成知识，把答案交还给证据。</span>
      </footer>
    </div>
  </div>
</template>
