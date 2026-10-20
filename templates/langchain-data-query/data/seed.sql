-- Sample documents for users 1, 2, and 3. prepare.sh loads this into out/database.db.
CREATE TABLE documents (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  title TEXT NOT NULL,
  content TEXT NOT NULL,
  owner_id INTEGER NOT NULL,
  created_at TEXT NOT NULL,
  draft INTEGER NOT NULL DEFAULT 0
);

INSERT INTO documents (title, content, owner_id, created_at, draft) VALUES
  ('Q4 Budget Report', 'Comprehensive financial analysis for Q4 2024', 1, '2024-01-05', 0),
  ('Project Proposal', 'Draft proposal for new product initiative', 1, '2024-01-15', 1),
  ('Meeting Notes', 'Notes from stakeholder meeting on planning', 1, '2024-01-10', 0),
  ('Annual Report', 'Year-end summary and achievements', 1, '2024-01-22', 0),
  ('Q1 Planning', 'Strategic planning document for Q1 2025', 1, '2024-12-20', 1),
  ('Team Status', 'Weekly team progress update', 1, '2024-01-18', 0),
  ('Budget Forecast', 'Financial projections for next quarter', 1, '2024-01-25', 1),
  ('Client Proposal', 'Proposal for new client engagement', 1, '2024-01-12', 1),
  ('Performance Report', 'Team performance metrics and analysis', 1, '2024-01-30', 0),
  ('Urgent: Board Meeting', 'Preparation notes for upcoming board meeting', 1, '2024-01-28', 0),
  ('Technical Design', 'Architecture design for microservices platform', 2, '2024-01-12', 0),
  ('Code Review', 'Review notes for major refactoring PR', 2, '2024-01-18', 0),
  ('API Documentation', 'REST API endpoint specifications', 2, '2024-01-08', 1),
  ('Security Report', 'Security audit findings and recommendations', 2, '2024-01-25', 0),
  ('Meeting Notes', 'Technical discussion on scaling strategy', 2, '2024-01-14', 0),
  ('Sprint Planning', 'Sprint goals and task breakdown', 2, '2024-01-20', 1),
  ('Bug Report', 'Critical issues identified in production', 2, '2024-01-27', 0),
  ('Performance Analysis', 'System performance benchmarks and optimization', 2, '2024-01-16', 1),
  ('Marketing Plan', 'Q1 2025 marketing campaign strategy', 3, '2024-01-08', 0),
  ('Customer Feedback', 'Summary of customer survey results', 3, '2024-01-22', 0),
  ('Social Media Report', 'Monthly social media analytics', 3, '2024-01-11', 0),
  ('Campaign Budget', 'Marketing budget allocation for campaigns', 3, '2024-01-15', 1),
  ('Content Calendar', 'Editorial calendar for next quarter', 3, '2024-01-19', 1),
  ('Meeting Notes', 'Marketing team brainstorming session', 3, '2024-01-13', 0),
  ('Brand Guidelines', 'Updated brand identity standards', 3, '2024-01-24', 0),
  ('Market Research', 'Competitive analysis and market trends', 3, '2024-01-26', 1);
