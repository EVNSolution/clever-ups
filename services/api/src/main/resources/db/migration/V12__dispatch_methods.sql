-- Two more ways to dispatch, decided by the route engine across drivers: OPTIMAL (each driver up to the most they
-- carry) and BALANCED (orders shared evenly). Do not edit after merge.
ALTER TABLE dispatch_plan DROP CONSTRAINT dispatch_plan_method;
ALTER TABLE dispatch_plan ADD CONSTRAINT dispatch_plan_method CHECK (method IN ('ZONE', 'OPTIMAL', 'BALANCED'));
