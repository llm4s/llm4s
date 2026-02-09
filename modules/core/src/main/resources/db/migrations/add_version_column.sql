-- Migration: Add version column for optimistic locking
-- This migration is idempotent and safe to run multiple times

-- Add version column if it doesn't exist
-- Default to 0 for backward compatibility with existing rows
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'agent_memories' 
        AND column_name = 'version'
    ) THEN
        ALTER TABLE agent_memories 
        ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
        
        -- Create index for efficient version checking
        CREATE INDEX IF NOT EXISTS idx_agent_memories_version 
        ON agent_memories(id, version);
        
        RAISE NOTICE 'Version column added successfully';
    ELSE
        RAISE NOTICE 'Version column already exists';
    END IF;
END $$;

-- Note: This script uses 'agent_memories' as the default table name.
-- If using a custom table name, replace 'agent_memories' with your table name.
