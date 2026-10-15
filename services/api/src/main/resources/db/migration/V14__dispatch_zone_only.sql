-- Engine-decided dispatch (OPTIMAL, BALANCED from V12) is withdrawn: the user banned optimization work on dispatch
-- on 2026-10-14. Only ZONE stays. V12 is kept because it may have run. Do not edit after merge.
ALTER TABLE dispatch_plan DROP CONSTRAINT dispatch_plan_method;
ALTER TABLE dispatch_plan ADD CONSTRAINT dispatch_plan_method CHECK (method IN ('ZONE'));
