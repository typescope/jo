<script setup>
import { computed } from 'vue'
import { useData } from 'vitepress'

const { frontmatter } = useData()

const created = computed(() => {
  const date = frontmatter.value.created
  if (!date) return ''

  return new Date(date).toLocaleDateString('en-US', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    timeZone: 'UTC',
  })
})

const statusClass = computed(() =>
  `status-${String(frontmatter.value.status).toLowerCase().replace(/[^a-z0-9]+/g, '-')}`
)
</script>

<template>
  <div v-if="frontmatter.author || created || frontmatter.status" class="jip-meta">
    <span>
      <span v-if="frontmatter.author">By {{ frontmatter.author }}</span>
      <span v-if="frontmatter.author && created" aria-hidden="true"> · </span>
      <span v-if="created">
        Created <time :datetime="frontmatter.created">{{ created }}</time>
      </span>
    </span>
    <span v-if="frontmatter.status" class="jip-status" :class="statusClass">
      {{ frontmatter.status }}
    </span>
  </div>
</template>

<style scoped>
.jip-meta {
  display: flex;
  gap: 10px;
  align-items: center;
  margin: 8px 0 32px;
  color: var(--vp-c-text-2);
  font-size: 0.9rem;
}

.jip-status {
  border-radius: 999px;
  padding: 2px 8px;
  background: var(--vp-c-default-soft);
  color: var(--vp-c-text-2);
  font-size: 0.7rem;
  font-weight: 600;
  line-height: 1.5;
}

.status-accepted {
  background: var(--vp-c-green-soft);
  color: var(--vp-c-green-1);
}
</style>
