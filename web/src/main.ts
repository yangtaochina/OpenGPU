import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'

import App from '@/App.vue'
import router from '@/router'
import '@/styles/index.css'

const app = createApp(App)

// Pinia 必须先于 router 安装：路由守卫会读取 auth store。
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')
