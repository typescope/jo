import { h } from 'vue'
import DefaultTheme from 'vitepress/theme'
import HeroCode from './HeroCode.vue'
import BlogIndex from './BlogIndex.vue'
import JipIndex from './JipIndex.vue'
import JipMeta from './JipMeta.vue'
import './custom.css'

export default {
  extends: DefaultTheme,
  Layout() {
    return h(DefaultTheme.Layout, null, {
      'home-hero-image': () => h(HeroCode)
    })
  },
  enhanceApp({ app }) {
    app.component('BlogIndex', BlogIndex)
    app.component('JipIndex', JipIndex)
    app.component('JipMeta', JipMeta)
  }
}
