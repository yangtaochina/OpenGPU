import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

declare module 'vue-router' {
  interface RouteMeta {
    /** 需要登录 */
    requiresAuth?: boolean
    /** 需要 ADMIN 角色 */
    requiresAdmin?: boolean
    /** 公开页面 */
    public?: boolean
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/RegisterView.vue'),
    meta: { public: true },
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true },
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    meta: { requiresAuth: true },
    children: [
      {
        path: '',
        name: 'task-submit',
        component: () => import('@/views/TaskSubmitView.vue'),
      },
      {
        path: 'tasks/:id',
        name: 'task-detail',
        component: () => import('@/views/TaskDetailView.vue'),
        props: true,
      },
      {
        path: 'admin/tasks',
        name: 'admin-tasks',
        component: () => import('@/views/admin/AdminTasksView.vue'),
        meta: { requiresAdmin: true },
      },
      {
        path: 'admin/workers',
        name: 'admin-workers',
        component: () => import('@/views/admin/AdminWorkersView.vue'),
        meta: { requiresAdmin: true },
      },
      {
        path: 'admin/users',
        name: 'admin-users',
        component: () => import('@/views/admin/AdminUsersView.vue'),
        meta: { requiresAdmin: true },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior(_to, _from, savedPosition) {
    if (savedPosition) return savedPosition
    return { top: 0 }
  },
})

router.beforeEach((to) => {
  const auth = useAuthStore()

  if (to.meta.requiresAuth && !auth.isAuthenticated) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.requiresAdmin && !auth.isAdmin) {
    return { path: '/' }
  }
  if ((to.path === '/login' || to.path === '/register') && auth.isAuthenticated) {
    return { path: '/' }
  }
  return true
})

export default router
