// Initialize highlight.js with Jo language support
document.addEventListener('DOMContentLoaded', function() {
  // Fix class names: change language-java to language-jo for jo blocks
  document.querySelectorAll('pre code[class*="language-"]').forEach((block) => {
    // Check if this looks like Jo code
    const content = block.textContent;
    if (content.includes('def ') || content.includes('data ') || content.includes('receives ')) {
      // Replace language-java with language-jo
      block.className = block.className.replace(/language-\w+/, 'language-jo');
    }
  });

  // Initialize highlight.js
  hljs.highlightAll();
});