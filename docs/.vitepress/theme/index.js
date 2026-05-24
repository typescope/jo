import { h } from 'vue'
import DefaultTheme from 'vitepress/theme'
import HeroAnimation from './HeroAnimation.vue'
import BlogIndex from './BlogIndex.vue'
import './custom.css'

export default {
  extends: DefaultTheme,
  Layout() {
    return h(DefaultTheme.Layout, null, {
      'home-hero-image': () => h(HeroAnimation)
    })
  },
  enhanceApp({ app }) {
    app.component('BlogIndex', BlogIndex)
  }
}
