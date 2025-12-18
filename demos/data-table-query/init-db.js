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
    created_at TEXT NOT NULL,
    draft INTEGER NOT NULL DEFAULT 0
  )
`);

console.log('✅ Created documents table');

// Insert sample data
const stmt = db.prepare('INSERT INTO documents (title, content, owner_id, created_at, draft) VALUES (?, ?, ?, ?, ?)');

// User 1 (Alice) - 10 documents
stmt.run('Q4 Budget Report', 'Comprehensive financial analysis for Q4 2024', 1, '2024-01-05', 0);
stmt.run('Project Proposal', 'Draft proposal for new product initiative', 1, '2024-01-15', 1);
stmt.run('Meeting Notes', 'Notes from stakeholder meeting on planning', 1, '2024-01-10', 0);
stmt.run('Annual Report', 'Year-end summary and achievements', 1, '2024-01-22', 0);
stmt.run('Q1 Planning', 'Strategic planning document for Q1 2025', 1, '2024-12-20', 1);
stmt.run('Team Status', 'Weekly team progress update', 1, '2024-01-18', 0);
stmt.run('Budget Forecast', 'Financial projections for next quarter', 1, '2024-01-25', 1);
stmt.run('Client Proposal', 'Proposal for new client engagement', 1, '2024-01-12', 1);
stmt.run('Performance Report', 'Team performance metrics and analysis', 1, '2024-01-30', 0);
stmt.run('Urgent: Board Meeting', 'Preparation notes for upcoming board meeting', 1, '2024-01-28', 0);

// User 2 (Bob) - 8 documents
stmt.run('Technical Design', 'Architecture design for microservices platform', 2, '2024-01-12', 0);
stmt.run('Code Review', 'Review notes for major refactoring PR', 2, '2024-01-18', 0);
stmt.run('API Documentation', 'REST API endpoint specifications', 2, '2024-01-08', 1);
stmt.run('Security Report', 'Security audit findings and recommendations', 2, '2024-01-25', 0);
stmt.run('Meeting Notes', 'Technical discussion on scaling strategy', 2, '2024-01-14', 0);
stmt.run('Sprint Planning', 'Sprint goals and task breakdown', 2, '2024-01-20', 1);
stmt.run('Bug Report', 'Critical issues identified in production', 2, '2024-01-27', 0);
stmt.run('Performance Analysis', 'System performance benchmarks and optimization', 2, '2024-01-16', 1);

// User 3 (Carol) - 8 documents
stmt.run('Marketing Plan', 'Q1 2025 marketing campaign strategy', 3, '2024-01-08', 0);
stmt.run('Customer Feedback', 'Summary of customer survey results', 3, '2024-01-22', 0);
stmt.run('Social Media Report', 'Monthly social media analytics', 3, '2024-01-11', 0);
stmt.run('Campaign Budget', 'Marketing budget allocation for campaigns', 3, '2024-01-15', 1);
stmt.run('Content Calendar', 'Editorial calendar for next quarter', 3, '2024-01-19', 1);
stmt.run('Meeting Notes', 'Marketing team brainstorming session', 3, '2024-01-13', 0);
stmt.run('Brand Guidelines', 'Updated brand identity standards', 3, '2024-01-24', 0);
stmt.run('Market Research', 'Competitive analysis and market trends', 3, '2024-01-26', 1);

console.log('✅ Inserted sample data');
console.log('   - User 1 (Alice): 10 documents');
console.log('   - User 2 (Bob): 8 documents');
console.log('   - User 3 (Carol): 8 documents');

// Close database
db.close();

console.log('✅ Database initialized successfully!');
