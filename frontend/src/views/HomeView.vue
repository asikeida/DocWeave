<script setup lang="ts">
import ServiceStatus from '../components/ServiceStatus.vue'
</script>

<template>
  <div class="page-content">
    <div class="page-intro">
      <span class="eyebrow">DOCWEAVE / 项目概览</span><h1>让知识，慢慢织成答案。</h1><p>收好每一份资料，让每一个回答都有迹可循。</p>
    </div>
    <section
      class="welcome-card"
      aria-labelledby="welcome-title"
    >
      <div class="welcome-copy">
        <span class="pill">你的智能文档知识库</span>
        <h2 id="welcome-title">
          从一份文档开始，<br>连接属于你的知识。
        </h2>
        <p>整理资料，发现关联，回到原文。<br>DocWeave 正在为有依据的知识问答打好基础。</p>
        <a
          class="text-link"
          href="#project-direction"
        >了解接下来的方向 <span aria-hidden="true">↓</span></a>
      </div>
      <svg
        class="document-art"
        viewBox="0 0 320 260"
        fill="none"
        aria-hidden="true"
      >
        <circle
          cx="170"
          cy="132"
          r="103"
          fill="#e4eee3"
        />
        <path
          d="M38 198h244M58 220h206"
          stroke="#c3d4c2"
          stroke-width="1.2"
        />
        <rect
          x="71"
          y="55"
          width="117"
          height="158"
          rx="10"
          transform="rotate(-12 71 55)"
          fill="#b7cbb6"
        />
        <rect
          x="112"
          y="39"
          width="125"
          height="166"
          rx="10"
          transform="rotate(8 112 39)"
          fill="#fffdf6"
          stroke="#d8dfd1"
        />
        <path
          d="m135 76 61 9m-64 13 76 11m-79 12 65 9m-68 14 39 5"
          stroke="#b1bfae"
          stroke-width="5"
          stroke-linecap="round"
        />
        <rect
          x="171"
          y="150"
          width="91"
          height="58"
          rx="11"
          fill="#245b4b"
        />
        <path
          d="m192 178 10 10 24-24"
          stroke="#fffdf6"
          stroke-width="4"
          stroke-linecap="round"
          stroke-linejoin="round"
        />
        <path
          d="m257 71 5-12m-1 31 13-2M66 115l-11-6"
          stroke="#8aaa88"
          stroke-width="3"
          stroke-linecap="round"
        />
        <circle
          cx="73"
          cy="173"
          r="5"
          fill="#d4ad66"
        />
      </svg>
    </section>
    <ServiceStatus />
    <section
      id="project-direction"
      class="direction-section"
      aria-labelledby="direction-title"
    >
      <div class="section-heading">
        <h2 id="direction-title">
          正在走向的知识体验
        </h2><span class="muted">以下功能将逐步开放</span>
      </div>
      <div class="direction-grid">
        <article class="direction-card">
          <span class="step-number">01 / 收集</span><h3>让资料各有所归</h3><p>在自己的知识空间里，整理文档与知识库。</p><span class="planned-label">规划中</span>
        </article>
        <article class="direction-card">
          <span class="step-number">02 / 发现</span><h3>找到值得连接的内容</h3><p>从原文中检索线索，把分散的信息联系起来。</p><span class="planned-label">规划中</span>
        </article>
        <article class="direction-card">
          <span class="step-number">03 / 理解</span><h3>回答始终有据可循</h3><p>带着引用阅读答案，随时回到具体的文档片段。</p><span class="planned-label">规划中</span>
        </article>
      </div>
    </section>
  </div>
</template>
