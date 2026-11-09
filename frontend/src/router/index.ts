import { createRouter, createWebHistory } from 'vue-router'
import AppLayout from '../layouts/AppLayout.vue'
import { session } from '../auth/session'

export const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      component: AppLayout,
      children: [
        { path: '', name: 'home', component: () => import('../views/HomeView.vue'), meta: { title: '概览' } },
        { path: 'login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { title: '登录' } },
        { path: 'register', name: 'register', component: () => import('../views/RegisterView.vue'), meta: { title: '注册' } },
        { path: 'personal', name: 'personal', component: () => import('../views/PersonalAreaView.vue'), meta: { title: '我的资料', requiresAuth: true } },
        { path: 'account', name: 'account', component: () => import('../views/AccountView.vue'), meta: { title: '我的账号', requiresAuth: true } },
        { path: ':pathMatch(.*)*', name: 'not-found', component: () => import('../views/NotFoundView.vue'), meta: { title: '页面不存在' } },
      ],
    },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async (to) => {
  if (!to.meta.requiresAuth) return true
  try {
    return await session.restore() ? true : { name: 'login' }
  } catch {
    // 具体网络错误在登录操作时提供可重试提示，导航不能进入空白账号页。
    return { name: 'login' }
  }
})

router.afterEach((to) => {
  document.title = `${String(to.meta.title ?? '智能文档知识库')} · DocWeave`
})
