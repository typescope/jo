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

// User 1 (Alice) - 10 documents
stmt.run('Q4 Budget Report', 'Comprehensive financial analysis for Q4 2024', 1, '2024-01-05');
stmt.run('Project Proposal', 'Draft proposal for new product initiative', 1, '2024-01-15');
stmt.run('Meeting Notes', 'Notes from stakeholder meeting on planning', 1, '2024-01-10');
stmt.run('Annual Report', 'Year-end summary and achievements', 1, '2024-01-22');
stmt.run('Q1 Planning', 'Strategic planning document for Q1 2025', 1, '2024-12-20');
stmt.run('Team Status', 'Weekly team progress update', 1, '2024-01-18');
stmt.run('Budget Forecast', 'Financial projections for next quarter', 1, '2024-01-25');
stmt.run('Client Proposal', 'Proposal for new client engagement', 1, '2024-01-12');
stmt.run('Performance Report', 'Team performance metrics and analysis', 1, '2024-01-30');
stmt.run('Urgent: Board Meeting', 'Preparation notes for upcoming board meeting', 1, '2024-01-28');

// User 2 (Bob) - 8 documents
stmt.run('Technical Design', 'Architecture design for microservices platform', 2, '2024-01-12');
stmt.run('Code Review', 'Review notes for major refactoring PR', 2, '2024-01-18');
stmt.run('API Documentation', 'REST API endpoint specifications', 2, '2024-01-08');
stmt.run('Security Report', 'Security audit findings and recommendations', 2, '2024-01-25');
stmt.run('Meeting Notes', 'Technical discussion on scaling strategy', 2, '2024-01-14');
stmt.run('Sprint Planning', 'Sprint goals and task breakdown', 2, '2024-01-20');
stmt.run('Bug Report', 'Critical issues identified in production', 2, '2024-01-27');
stmt.run('Performance Analysis', 'System performance benchmarks and optimization', 2, '2024-01-16');

// User 3 (Carol) - 8 documents
stmt.run('Marketing Plan', 'Q1 2025 marketing campaign strategy', 3, '2024-01-08');
stmt.run('Customer Feedback', 'Summary of customer survey results', 3, '2024-01-22');
stmt.run('Social Media Report', 'Monthly social media analytics', 3, '2024-01-11');
stmt.run('Campaign Budget', 'Marketing budget allocation for campaigns', 3, '2024-01-15');
stmt.run('Content Calendar', 'Editorial calendar for next quarter', 3, '2024-01-19');
stmt.run('Meeting Notes', 'Marketing team brainstorming session', 3, '2024-01-13');
stmt.run('Brand Guidelines', 'Updated brand identity standards', 3, '2024-01-24');
stmt.run('Market Research', 'Competitive analysis and market trends', 3, '2024-01-26');

console.log('✅ Inserted sample data');
console.log('   - User 1 (Alice): 10 documents');
console.log('   - User 2 (Bob): 8 documents');
console.log('   - User 3 (Carol): 8 documents');

// Close database
db.close();

console.log('✅ Database initialized successfully!');
