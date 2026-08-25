<script setup>
import { data as jips } from '../../jips/jips.data.js'

function statusClass(status) {
  return `status-${String(status).toLowerCase().replace(/[^a-z0-9]+/g, '-')}`
}

function formatDate(date) {
  return new Date(date).toLocaleDateString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    timeZone: 'UTC',
  })
}
</script>

<template>
  <div class="jip-index">
    <div class="jip-header">
      <h1>Jo Improvement Proposals</h1>
      <p>Design proposals for significant changes to the Jo language and toolchain.</p>
    </div>

    <div class="jip-list">
      <div class="jip-table-header" aria-hidden="true">
        <span>Proposal</span>
        <span>Title</span>
        <span>Created</span>
        <span>Status</span>
      </div>
      <a v-for="jip in jips" :key="jip.url" :href="jip.url" class="jip-row">
        <span class="jip-number">JIP-{{ jip.number }}</span>
        <span class="jip-title">{{ jip.frontmatter.title }}</span>
        <time class="jip-created" :datetime="jip.frontmatter.created">
          {{ formatDate(jip.frontmatter.created) }}
        </time>
        <span class="jip-status" :class="statusClass(jip.frontmatter.status)">
          {{ jip.frontmatter.status }}
        </span>
      </a>
    </div>
  </div>
</template>

<style scoped>
.jip-index {
  max-width: 880px;
  margin: 0 auto;
  padding: 48px 24px 80px;
}

.jip-header {
  margin-bottom: 32px;
  border-bottom: 1px solid var(--vp-c-divider);
  padding-bottom: 32px;
}

.jip-header h1 {
  margin: 0 0 12px;
  font-size: 2.2rem;
  font-weight: 800;
  letter-spacing: -0.03em;
}

.jip-header p {
  margin: 0;
  color: var(--vp-c-text-2);
  font-size: 1.05rem;
}

.jip-list {
  overflow: hidden;
  border: 1px solid var(--vp-c-divider);
  border-radius: 8px;
}

.jip-table-header,
.jip-row {
  display: grid;
  grid-template-columns: 100px minmax(240px, 1fr) 110px 76px;
  column-gap: 16px;
  align-items: center;
}

.jip-table-header {
  padding: 8px 16px;
  background: var(--vp-c-bg-soft);
  color: var(--vp-c-text-3);
  font-size: 0.72rem;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.jip-row {
  padding: 12px 16px;
  color: inherit;
  text-decoration: none;
  transition: background-color 0.2s;
}

.jip-row + .jip-row {
  border-top: 1px solid var(--vp-c-divider);
}

.jip-row:hover {
  background: var(--vp-c-bg-soft);
}

.jip-number {
  color: var(--vp-c-text-2);
  font-size: 0.8rem;
  font-weight: 600;
}

.jip-created {
  color: var(--vp-c-text-3);
  font-size: 0.78rem;
  white-space: nowrap;
}

.jip-title {
  font-weight: 600;
}

.jip-row:hover .jip-title {
  color: var(--vp-c-brand-1);
}

.jip-status {
  justify-self: start;
  border-radius: 999px;
  padding: 2px 8px;
  background: var(--vp-c-default-soft);
  color: var(--vp-c-text-2);
  font-size: 0.7rem;
  font-weight: 600;
}

.status-accepted {
  background: var(--vp-c-green-soft);
  color: var(--vp-c-green-1);
}

@media (max-width: 640px) {
  .jip-table-header {
    display: none;
  }

  .jip-row {
    grid-template-columns: 1fr auto;
    row-gap: 8px;
  }

  .jip-title {
    grid-column: 1 / -1;
    grid-row: 2;
  }

  .jip-created {
    grid-column: 1;
    grid-row: 3;
  }

  .jip-status {
    grid-column: 2;
    grid-row: 3;
  }
}
</style>
