import { createContentLoader } from 'vitepress'

export default createContentLoader('jips/[0-9][0-9][0-9][0-9]-*.md', {
  transform(rawData) {
    return rawData
      .map(page => ({
        ...page,
        number: page.url.split('/').pop().slice(0, 4),
      }))
      .sort((a, b) => a.number.localeCompare(b.number))
  }
})
