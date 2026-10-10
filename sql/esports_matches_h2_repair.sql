-- Idempotent H2 repair for databases created before match score/status fields existed.
ALTER TABLE esports_matches ADD COLUMN IF NOT EXISTS best_of VARCHAR(20);
ALTER TABLE esports_matches ADD COLUMN IF NOT EXISTS finished BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE esports_matches ADD COLUMN IF NOT EXISTS team1score INTEGER;
ALTER TABLE esports_matches ADD COLUMN IF NOT EXISTS team2score INTEGER;
ALTER TABLE esports_matches ADD COLUMN IF NOT EXISTS winner VARCHAR(300);
ALTER TABLE esports_matches ADD COLUMN IF NOT EXISTS end_time TIMESTAMP WITH TIME ZONE;
