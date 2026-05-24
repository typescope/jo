<script setup>
import { data as posts } from '../../blog/posts.data.js'

function formatDate(dateStr) {
  const d = new Date(dateStr)
  return d.toLocaleDateString('en-US', { year: 'numeric', month: 'long', day: 'numeric' })
}
</script>

<template>
  <div class="blog-index">
    <div class="blog-header">
      <h1>Blog</h1>
      <p>News, updates, and in-depth articles about the Jo programming language.</p>
    </div>

    <div class="blog-list">
      <article v-for="post in posts" :key="post.url" class="blog-card">
        <a :href="post.url" class="blog-card-link">
          <div class="blog-meta">
            <span class="blog-date">{{ formatDate(post.frontmatter.date) }}</span>
            <span v-if="post.frontmatter.author" class="blog-author">{{ post.frontmatter.author }}</span>
          </div>
          <h2 class="blog-title">{{ post.frontmatter.title }}</h2>
          <p v-if="post.frontmatter.description" class="blog-description">{{ post.frontmatter.description }}</p>
          <div v-else-if="post.excerpt" class="blog-excerpt" v-html="post.excerpt" />
          <span class="blog-read-more">Read more →</span>
        </a>
      </article>

      <p v-if="posts.length === 0" class="blog-empty">No posts yet. Check back soon!</p>
    </div>
  </div>
</template>

<style scoped>
.blog-index {
  max-width: 780px;
  margin: 0 auto;
  padding: 48px 24px 80px;
}

.blog-header {
  margin-bottom: 48px;
  border-bottom: 1px solid var(--vp-c-divider);
  padding-bottom: 32px;
}

.blog-header h1 {
  font-size: 2.2rem;
  font-weight: 800;
  letter-spacing: -0.03em;
  background: linear-gradient(135deg, #7c3aed 0%, #2563eb 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
  margin: 0 0 12px;
  padding-bottom: 8px;
}

.blog-header p {
  color: var(--vp-c-text-2);
  font-size: 1.05rem;
  margin: 0;
}

.blog-list {
  display: flex;
  flex-direction: column;
  gap: 32px;
}

.blog-card {
  border: 1px solid var(--vp-c-divider);
  border-radius: 12px;
  overflow: hidden;
  transition: border-color 0.2s, box-shadow 0.2s;
}

.blog-card:hover {
  border-color: var(--vp-c-brand-1);
  box-shadow: 0 4px 20px rgba(124, 58, 237, 0.1);
}

.blog-card-link {
  display: block;
  padding: 28px 32px;
  text-decoration: none;
  color: inherit;
}

.blog-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
  font-size: 0.85rem;
}

.blog-date {
  color: var(--vp-c-text-3);
}

.blog-author {
  color: var(--vp-c-brand-1);
  font-weight: 500;
}

.blog-author::before {
  content: '·';
  margin-right: 12px;
  color: var(--vp-c-text-3);
}

.blog-title {
  font-size: 1.4rem;
  font-weight: 700;
  letter-spacing: -0.02em;
  margin: 0 0 10px;
  color: var(--vp-c-text-1);
  line-height: 1.3;
  transition: color 0.2s;
}

.blog-card:hover .blog-title {
  color: var(--vp-c-brand-1);
}

.blog-description,
.blog-excerpt {
  color: var(--vp-c-text-2);
  font-size: 0.95rem;
  line-height: 1.7;
  margin: 0 0 16px;
}

.blog-excerpt :deep(p) {
  margin: 0;
}

.blog-read-more {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--vp-c-brand-1);
}

.blog-empty {
  color: var(--vp-c-text-3);
  text-align: center;
  padding: 48px 0;
}
</style>
