// Initialize highlight.js with Jo language support
document.addEventListener('DOMContentLoaded', function() {
  console.log('Initializing highlight.js...');
  console.log('Available languages:', hljs.listLanguages());
  
  // Initialize highlight.js normally
  hljs.highlightAll();
  
  // Debug: check if Jo blocks are properly detected
  document.querySelectorAll('pre code').forEach((block) => {
    if (block.className.includes('jo')) {
      console.log('Found Jo block:', block.className, block.textContent.substring(0, 50));
    }
  });
});