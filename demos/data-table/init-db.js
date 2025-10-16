#!/usr/bin/env node

// Initialize SQLite database with sample data for row-level security demo
// Uses node:sqlite (requires Node.js v22.5.0+)

const { DatabaseSync } = require('node:sqlite');
const fs = require('fs');

  // Read userId from command line - ABORT if not provided
let file = process.argv[2];

if (!file) {
    console.log("database file not supplied.");
    return;
}

// Remove existing database
if (fs.existsSync(file)) {
  fs.unlinkSync(file);
  console.log('🗑️  Removed existing database');
}

console.log('📦 Creating database schema...');

// Create new database
const db = new DatabaseSync(file);

// Create documents table
db.exec(`
  CREATE TABLE documents (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT NOT NULL,
    content TEXT NOT NULL,
    owner_id INTEGER NOT NULL,
    created_at TEXT NOT NULL
  )
`);

console.log('✅ Created documents table');

// Insert sample data
const stmt = db.prepare('INSERT INTO documents (title, content, owner_id, created_at) VALUES (?, ?, ?, ?)');

// User 1 (Alice) - 3 documents
stmt.run('Project Proposal', 'Draft proposal for Q4 project initiative', 1, '2025-01-15');
stmt.run('Meeting Notes', 'Notes from stakeholder meeting on Jan 10', 1, '2025-01-10');
stmt.run('Budget Report', 'Financial summary for department', 1, '2025-01-20');

// User 2 (Bob) - 2 documents
stmt.run('Technical Design', 'Architecture design for new microservice', 2, '2025-01-12');
stmt.run('Code Review', 'Review notes for PR #234', 2, '2025-01-18');

// User 3 (Carol) - 2 documents
stmt.run('Marketing Plan', 'Q1 marketing campaign strategy', 3, '2025-01-08');
stmt.run('Customer Feedback', 'Summary of customer survey results', 3, '2025-01-22');

console.log('✅ Inserted sample data');
console.log('   - User 1 (Alice): 3 documents');
console.log('   - User 2 (Bob): 2 documents');
console.log('   - User 3 (Carol): 2 documents');

// Close database
db.close();

console.log('✅ Database initialized successfully!');
console.log('');
console.log('Run the demo with:');
console.log('  ./build.sh');
console.log('  node out/app.js 1  # View Alice\'s documents');
console.log('  node out/app.js 2  # View Bob\'s documents');
console.log('  node out/app.js 3  # View Carol\'s documents');
