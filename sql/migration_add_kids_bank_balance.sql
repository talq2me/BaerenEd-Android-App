-- BaerenEd: Add kids virtual bank balance (parent adds/subtracts; displayed in BaerenEd Battle Hub)
ALTER TABLE user_data
    ADD COLUMN IF NOT EXISTS kid_bank_balance NUMERIC(12,2) DEFAULT 0;

