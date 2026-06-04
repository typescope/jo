<template>
  <div class="hero-code">
    <div class="hero-code-header">
      <span class="dot red"/><span class="dot yellow"/><span class="dot green"/>
      <span class="filename">{{ snippets[current].filename }}</span>
    </div>

    <div class="hero-code-body">
      <transition name="fade" mode="out-in">
        <pre :key="current"><code v-html="snippets[current].code"/></pre>
      </transition>
    </div>

    <div class="hero-code-dots">
      <button
        v-for="(_, i) in snippets"
        :key="i"
        :class="['dot-btn', { active: i === current }]"
        @click="go(i)"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'

const kw  = s => `<span class="kw">${s}</span>`
const fn  = s => `<span class="fn">${s}</span>`
const str = s => `<span class="str">${s}</span>`
const c   = s => `<span class="c">${s}</span>`
const err = s => `<span class="err">${s}</span>`
const op  = s => `<span class="op">${s}</span>`
const ty  = s => `<span class="ty">${s}</span>`
const num = s => `<span class="num">${s}</span>`

const snippets = [
  {
    filename: 'main.jo',
    code:
      c('// capabilities are inferred, tracked transitively') + '\n' +
      kw('def ') + fn('fetch') + '() ' + op('=') + ' httpGet(' + str('"https://api.example.com"') + ')\n' +
      kw('def ') + fn('process') + '() ' + op('=') + ' fetch()\n' +
      '\n' +
      kw('def ') + fn('main') + ' ' + op('=') + '\n' +
      '  ' + c('// prove: process() uses no capabilities') + '\n' +
      '  ' + kw('allow none in') + ' process()\n' +
      c('  // error: Parameter not allowed: IO.http')
  },
  {
    filename: 'html-dsl.jo',
    code:
      c('// HTML as a first-class DSL — tree syntax, typed attributes') + '\n' +
      kw('val ') + 'doc ' + op('=') + '\n' +
      '  ' + fn('html') + op(':') + '\n' +
      '    ' + str('"lang"') + ' ' + op(':=') + ' ' + str('"en"') + '\n' +
      '    ' + fn('head') + op(':') + '\n' +
      '      ' + fn('title') + op(':') + ' ' + str('"Pixel Bloom"') + '\n' +
      '    ' + fn('body') + op(':') + '\n' +
      '      ' + fn('div') + op(':') + ' ' + str('"id"') + ' ' + op(':=') + ' ' + str('"app"') + '\n' +
      '        ' + fn('h1') + op(':') + ' ' + str('"Pixel Bloom"') + '\n' +
      '        ' + fn('p') + op(':') + ' ' + str('"A computer-art sketch in Jo."') + '\n' +
      '        ' + fn('div') + op(':') + ' ' + str('"class"') + ' ' + op(':=') + ' ' + str('"art-card"') + '\n' +
      '          ' + fn('span') + op(':') + ' ' + str('"class"') + ' ' + op(':=') + ' ' + str('"pixel p1"') + '\n' +
      '          ' + fn('span') + op(':') + ' ' + str('"class"') + ' ' + op(':=') + ' ' + str('"pixel p2"')
  },
  {
    filename: 'patterns.jo',
    code:
      c('// named, reusable pattern predicates') + '\n' +
      kw('pattern ') + ty('Positive') + op(': ') + ty('Partial') + '[' + ty('Int') + '] ' + op('=') + ' ' + kw('case') + ' x ' + kw('if') + ' x ' + op('>') + ' ' + num('0') + '\n' +
      '\n' +
      kw('match ') + 'list\n' +
      kw('case ') + '[..positives ' + kw('while ') + ty('Positive') + ', ..rest] ' + op('=>') + '\n' +
      '  println ' + str('"pos = \\{positives}, rest = \\{rest}"') + '\n' +
      '\n' +
      c('// regex with named groups') + '\n' +
      kw('if ') + 'msg ' + kw('is') + ' `(?s)' + op('<') + 'code' + op('>') + '(?\\<prog\\>.*' + op(')') + '\\<\\/code\\>` ' + kw('then') + '\n' +
      '  println prog'
  }
]

const current = ref(0)
let timer = null

function go(i) {
  current.value = i
  restart()
}

function next() {
  current.value = (current.value + 1) % snippets.length
}

function restart() {
  clearInterval(timer)
  timer = setInterval(next, 5000)
}

onMounted(() => { timer = setInterval(next, 5000) })
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.hero-code {
  width: 500px;
  margin-top: 56px;
  border-radius: 10px;
  overflow: hidden;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 12.5px;
  line-height: 1.7;
  box-shadow: 0 4px 24px rgba(0, 0, 0, 0.08);
  border: 1px solid var(--vp-c-divider);
  background: #f6f8fa;
}

.dark .hero-code {
  background: #1e1e2e;
  box-shadow: 0 4px 24px rgba(0, 0, 0, 0.4);
}

.hero-code-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 9px 14px;
  background: rgba(0,0,0,0.04);
  border-bottom: 1px solid var(--vp-c-divider);
}

.dark .hero-code-header {
  background: rgba(255,255,255,0.04);
  border-bottom-color: rgba(255,255,255,0.07);
}

.dot { width: 10px; height: 10px; border-radius: 50%; }
.dot.red    { background: #ff5f57; }
.dot.yellow { background: #febc2e; }
.dot.green  { background: #28c840; }

.filename {
  margin-left: 6px;
  font-size: 11px;
  color: var(--vp-c-text-3);
}

.hero-code-body {
  padding: 16px 18px;
  height: 242px;
  overflow: hidden;
}

pre {
  margin: 0;
  background: transparent;
  overflow: visible;
  white-space: pre;
}

code {
  background: none;
  padding: 0;
  font-size: inherit;
  color: #cdd6f4;
}

/* Transitions */
.fade-enter-active, .fade-leave-active { transition: opacity 0.25s ease; }
.fade-enter-from, .fade-leave-to       { opacity: 0; }

/* Dots */
.hero-code-dots {
  display: flex;
  justify-content: center;
  gap: 5px;
  padding: 5px 0 6px;
  background: transparent;
  border-top: none;
}

.dot-btn {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  border: none;
  background: var(--vp-c-text-3);
  cursor: pointer;
  padding: 4px;
  box-sizing: content-box;
  transition: background 0.2s;
  opacity: 0.4;
}
.dot-btn.active { background: #7c3aed; opacity: 1; }
.dot-btn:hover  { opacity: 0.8; }

/* Syntax colour tokens — light defaults, overridden in dark via global */
:global(:root) {
  --jo-code: #24292e;
  --jo-kw:   #d73a49;
  --jo-fn:   #6f42c1;
  --jo-str:  #032f62;
  --jo-c:    #6a737d;
  --jo-err:  #cb2431;
  --jo-op:   #005cc5;
  --jo-ty:   #e36209;
  --jo-num:  #005cc5;
}
:global(.dark) {
  --jo-code: #cdd6f4;
  --jo-kw:   #cba6f7;
  --jo-fn:   #89b4fa;
  --jo-str:  #a6e3a1;
  --jo-c:    #585b70;
  --jo-err:  #f38ba8;
  --jo-op:   #89dceb;
  --jo-ty:   #f9e2af;
  --jo-num:  #fab387;
}

:deep(code) { color: var(--jo-code); }
:deep(.kw)  { color: var(--jo-kw); }
:deep(.fn)  { color: var(--jo-fn); }
:deep(.str) { color: var(--jo-str); }
:deep(.c)   { color: var(--jo-c); }
:deep(.err) { color: var(--jo-err); display: block; }
:deep(.op)  { color: var(--jo-op); }
:deep(.ty)  { color: var(--jo-ty); }
:deep(.num) { color: var(--jo-num); }
</style>
